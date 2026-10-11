package net.minecraft.world.level.levelgen.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 176 (R35): PlacedFeature.placeWithContext was rewritten from a
// flatMap stream chain to a depth-first forEachPosition walk. The walk must preserve
// two things exactly: the order in which positions reach the leaf, and the order in
// which modifiers consume randomness (worldgen is seed-identical by contract). The
// oracle is the vanilla chain, inlined below, over fake modifiers that only override
// getPositions (so they run through the inherited default forEachPosition). A
// divergence in either order shows up as different emissions or different residual
// random state (compared via nextLong after the run). The modifier set is built
// fresh for each side - the fakes count their invocations.
public class PapoPlacementDriverEquivalenceTest {

    /** Emits pattern[calls % pattern.length] positions per call; every emission
     *  encodes the modifier id, the call index and the emission index, and the
     *  random draw of that call. */
    private static final class PatternModifier extends PlacementModifier {
        final int id;
        final int[] pattern;
        int calls;

        PatternModifier(final int id, final int... pattern) {
            this.id = id;
            this.pattern = pattern;
        }

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            final int call = this.calls++;
            final int draw = random.nextInt(97);
            final int count = this.pattern[call % this.pattern.length];
            final List<BlockPos> emitted = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                emitted.add(new BlockPos(this.id * 1_000_003 + call * 101 + draw, i, call));
            }
            return emitted.stream();
        }

        @Override
        public PlacementModifierType<?> type() {
            throw new UnsupportedOperationException();
        }
    }

    private static List<BlockPos> vanillaChain(final List<PlacementModifier> modifiers, final RandomSource random, final BlockPos origin) {
        Stream<BlockPos> stream = Stream.of(origin);
        for (final PlacementModifier placementModifier : modifiers) {
            stream = stream.flatMap(blockPos -> placementModifier.getPositions(null, random, blockPos));
        }
        return stream.toList();
    }

    private static List<BlockPos> papoWalk(final List<PlacementModifier> modifiers, final RandomSource random, final BlockPos origin) {
        final PlacedFeature placedFeature = new PlacedFeature(Holder.direct(null), modifiers);
        final List<BlockPos> emissions = new ArrayList<>();
        placedFeature.papoWalkPositions(null, random, origin, emissions::add);
        return emissions;
    }

    private static void assertEquivalent(final Supplier<List<PlacementModifier>> chain, final long seed) {
        final BlockPos origin = new BlockPos(7, 64, 7);
        final RandomSource randomA = new XoroshiroRandomSource(seed);
        final List<BlockPos> expected = vanillaChain(chain.get(), randomA, origin);
        final RandomSource randomB = new XoroshiroRandomSource(seed);
        final List<BlockPos> actual = papoWalk(chain.get(), randomB, origin);

        Assertions.assertEquals(expected, actual, "emission sequence, seed " + seed);
        Assertions.assertEquals(randomA.nextLong(), randomB.nextLong(), "residual random state, seed " + seed);
    }

    @Test
    void walkMatchesVanillaChainAcrossPatternsAndSeeds() {
        // empty chain: the leaf receives the origin directly
        assertEquivalent(List::of, 1L);
        // single modifiers of every emission arity
        assertEquivalent(() -> List.of(new PatternModifier(1, 1)), 2L);
        assertEquivalent(() -> List.of(new PatternModifier(1, 0)), 3L);
        assertEquivalent(() -> List.of(new PatternModifier(1, 3, 0, 2)), 4L);
        // multi-emission mid-chain (the case where emission order and random
        // consumption can diverge between traversal strategies)
        assertEquivalent(() -> List.of(new PatternModifier(1, 3, 2), new PatternModifier(2, 1)), 5L);
        assertEquivalent(() -> List.of(new PatternModifier(1, 2, 0, 3), new PatternModifier(2, 2), new PatternModifier(3, 1, 0)), 6L);
        assertEquivalent(() -> List.of(new PatternModifier(1, 1, 4), new PatternModifier(2, 3, 0), new PatternModifier(3, 2, 2, 1)), 7L);
        // the same chains over more seeds
        for (long seed = 8L; seed < 16L; seed++) {
            assertEquivalent(() -> List.of(new PatternModifier(1, 2, 1), new PatternModifier(2, 3, 0, 2), new PatternModifier(3, 1)), seed);
        }
    }

    @Test
    void walkOrderIsDepthFirstPerVanillaFlatMapSemantics() {
        // a 3-emission modifier followed by a single-emission modifier: the single
        // origin expands to 3 leaves, and every emission of the first modifier must
        // flow through the whole chain before the walk finishes - flatMap's
        // sequential pull order (3 leaves, not the 3x3 a breadth-first staging of
        // both modifiers would risk)
        final List<BlockPos> expected = vanillaChain(
            List.of(new PatternModifier(1, 3, 1), new PatternModifier(2, 1)), new XoroshiroRandomSource(42L), new BlockPos(0, 0, 0));
        final List<BlockPos> actual = papoWalk(
            List.of(new PatternModifier(1, 3, 1), new PatternModifier(2, 1)), new XoroshiroRandomSource(42L), new BlockPos(0, 0, 0));
        Assertions.assertEquals(expected, actual);
        Assertions.assertEquals(3, actual.size());
    }
}
