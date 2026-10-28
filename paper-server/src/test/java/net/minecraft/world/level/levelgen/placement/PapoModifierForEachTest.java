package net.minecraft.world.level.levelgen.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 176 (R35): every vanilla PlacementModifier override of
// forEachPosition is asserted against its own getPositions stream form on
// independent same-seed randoms - identical emissions imply identical random
// consumption. Covers all modifiers constructible without a live level/context
// (the level-bound ones - Heightmap/EnvironmentScan/CountOnEveryLayer and the
// shouldPlace filters - share the same emit-structure; the driver-order property
// they rely on is covered by PapoPlacementDriverEquivalenceTest).
public class PapoModifierForEachTest {

    private static List<BlockPos> forEach(final PlacementModifier modifier, final long seed, final BlockPos origin) {
        final List<BlockPos> emissions = new ArrayList<>();
        modifier.forEachPosition(null, new XoroshiroRandomSource(seed), origin, emissions::add);
        return emissions;
    }

    private static List<BlockPos> stream(final PlacementModifier modifier, final long seed, final BlockPos origin) {
        return modifier.getPositions(null, new XoroshiroRandomSource(seed), origin).toList();
    }

    private static void assertEquivalent(final PlacementModifier modifier, final BlockPos origin) {
        for (final long seed : new long[] {1L, 42L, 907L, 123_456_789L}) {
            Assertions.assertEquals(stream(modifier, seed, origin), forEach(modifier, seed, origin), modifier.getClass().getSimpleName() + " seed " + seed);
        }
    }

    @Test
    void inSquarePlacementMatchesStreamForm() {
        final InSquarePlacement modifier = InSquarePlacement.spread();
        assertEquivalent(modifier, new BlockPos(100, 64, 100));
    }

    @Test
    void heightRangePlacementMatchesStreamForm() {
        // absolute anchors never dereference the context, so a null context is safe
        final HeightRangePlacement modifier = HeightRangePlacement.uniform(VerticalAnchor.absolute(0), VerticalAnchor.absolute(63));
        assertEquivalent(modifier, new BlockPos(100, 64, 100));
        final HeightRangePlacement biased = HeightRangePlacement.triangle(VerticalAnchor.absolute(32), VerticalAnchor.absolute(63));
        assertEquivalent(biased, new BlockPos(-100, 1, -100));
    }

    @Test
    void randomOffsetPlacementMatchesStreamForm() {
        final RandomOffsetPlacement modifier = RandomOffsetPlacement.of(ConstantInt.of(3), ConstantInt.of(1));
        assertEquivalent(modifier, new BlockPos(0, 64, 0));
        final RandomOffsetPlacement spread = RandomOffsetPlacement.of(ConstantInt.of(0), ConstantInt.of(0));
        assertEquivalent(spread, new BlockPos(5, 5, 5));
    }

    @Test
    void countPlacementMatchesStreamForm() {
        assertEquivalent(CountPlacement.of(ConstantInt.of(5)), new BlockPos(1, 2, 3));
        assertEquivalent(CountPlacement.of(ConstantInt.of(0)), new BlockPos(1, 2, 3));
    }

    @Test
    void rarityFilterMatchesStreamForm() {
        assertEquivalent(RarityFilter.onAverageOnceEvery(2), new BlockPos(1, 2, 3));
        assertEquivalent(RarityFilter.onAverageOnceEvery(7), new BlockPos(1, 2, 3));
    }

    @Test
    void fixedPlacementMatchesStreamForm() {
        final BlockPos anchor = new BlockPos(16, 64, 16);
        // origin in the same chunk as one position; and a far origin hitting no position
        assertEquivalent(FixedPlacement.of(anchor, new BlockPos(20, 70, 20), new BlockPos(1000, 5, 1000)), anchor);
        assertEquivalent(FixedPlacement.of(anchor, new BlockPos(20, 70, 20), new BlockPos(1000, 5, 1000)), new BlockPos(5000, 5, 5000));
    }

    @Test
    void placementFilterSubclassesCoveredByBaseOverride() {
        // PlacementFilter's final forEachPosition covers Biome/BlockPredicate/
        // Rarity/SurfaceRelativeThreshold/SurfaceWaterDepth structurally; assert the
        // base behaviour with a shouldPlace that flips per call and consumes randomness
        final PlacementModifier filter = new PlacementFilter() {
            @Override
            protected boolean shouldPlace(final PlacementContext context, final RandomSource random, final BlockPos pos) {
                return pos.getY() + random.nextInt(2) > 0;
            }

            @Override
            public PlacementModifierType<?> type() {
                throw new UnsupportedOperationException();
            }
        };
        assertEquivalent(filter, new BlockPos(0, 0, 0));
        assertEquivalent(filter, new BlockPos(0, -1, 0));
    }

    @Test
    void repeatingPlacementSubclassesCoveredByBaseOverride() {
        // RepeatingPlacement's forEachPosition covers Count/NoiseBasedCount/
        // NoiseThresholdCount structurally; assert with a varying count that itself
        // consumes randomness per call
        final PlacementModifier repeating = new RepeatingPlacement() {
            @Override
            protected int count(final RandomSource random, final BlockPos pos) {
                return random.nextInt(4);
            }

            @Override
            public PlacementModifierType<?> type() {
                throw new UnsupportedOperationException();
            }
        };
        assertEquivalent(repeating, new BlockPos(3, 3, 3));
    }

    @Test
    void defaultForEachDelegatesToGetPositions() {
        // the inherited default is the compatibility path for subclasses compiled
        // against the old surface (plugins) - a modifier that only overrides
        // getPositions must traverse identically through forEachPosition
        final PlacementModifier streamOnly = new PlacementModifier() {
            @Override
            public java.util.stream.Stream<BlockPos> getPositions(final PlacementContext context, final RandomSource random, final BlockPos pos) {
                return java.util.stream.Stream.of(pos.above(), pos.below());
            }

            @Override
            public PlacementModifierType<?> type() {
                throw new UnsupportedOperationException();
            }
        };
        assertEquivalent(streamOnly, new BlockPos(7, 64, 7));
    }

    @Test
    void walkThroughOverriddenAndDefaultModifiers() {
        // chain a loop-form modifier with a default-path modifier through the real
        // walk: order and randomness must still match the vanilla chain
        final PlacementModifier streamOnly = new PlacementModifier() {
            @Override
            public java.util.stream.Stream<BlockPos> getPositions(final PlacementContext context, final RandomSource random, final BlockPos pos) {
                return random.nextBoolean() ? java.util.stream.Stream.of(pos) : java.util.stream.Stream.empty();
            }

            @Override
            public PlacementModifierType<?> type() {
                throw new UnsupportedOperationException();
            }
        };
        final BlockPos origin = new BlockPos(0, 60, 0);
        for (final long seed : new long[] {5L, 6L, 7L}) {
            final RandomSource oracleRandom = new XoroshiroRandomSource(seed);
            java.util.stream.Stream<BlockPos> chain = java.util.stream.Stream.of(origin);
            chain = chain.flatMap(p -> InSquarePlacement.spread().getPositions(null, oracleRandom, p));
            chain = chain.flatMap(p -> streamOnly.getPositions(null, oracleRandom, p));
            final List<BlockPos> expected = chain.toList();

            final PlacedFeature placedFeature = new PlacedFeature(net.minecraft.core.Holder.direct(null), List.of(InSquarePlacement.spread(), streamOnly));
            final List<BlockPos> actual = new ArrayList<>();
            placedFeature.papoWalkPositions(null, new XoroshiroRandomSource(seed), origin, actual::add);
            Assertions.assertEquals(expected, actual, "seed " + seed);
        }
    }
}
