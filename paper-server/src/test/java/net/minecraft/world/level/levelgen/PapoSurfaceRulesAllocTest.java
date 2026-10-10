package net.minecraft.world.level.levelgen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 165 (R24): the manual biome memoization contract that replaced the
// per-updateY Suppliers.memoize wrapper. Vanilla semantics being pinned:
//   (1) lazy - the biome getter is not invoked by updateY itself, only at first use;
//   (2) exactly once per updateY window - repeated biome() calls return the same holder
//       without re-invoking the getter, and the next updateY invalidates the cache;
//   (3) coordinates read at first use - the getter observes blockX/blockY/blockZ exactly
//       as captured by the vanilla lambda at updateY time (fields hold those values for
//       the whole evaluation window);
//   (4) throw retries - a throwing getter leaves the window un-computed so the next call
//       retries, matching Guava memoize (which only caches successful results).
// SequenceRule.tryApply is pinned alongside: indexed iteration with identical order and
// short-circuit (first non-null state wins, later rules never evaluated).
public class PapoSurfaceRulesAllocTest {

    private static final Holder<Biome> BIOME_A = Holder.direct(null);
    private static final Holder<Biome> BIOME_B = Holder.direct(null);

    private static BlockState stone;
    private static BlockState dirt;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
        stone = Blocks.STONE.defaultBlockState();
        dirt = Blocks.DIRT.defaultBlockState();
    }

    private static SurfaceRules.Context context(final Function<BlockPos, Holder<Biome>> biomeGetter) {
        // the Context constructor only assigns fields; nulls are never dereferenced on the
        // updateY/biome()/tryApply paths under test
        return new SurfaceRules.Context(null, null, null, null, biomeGetter, null, null);
    }

    @Test
    void biomeIsLazyAndComputedAtMostOncePerWindow() {
        final int[] calls = {0};
        final SurfaceRules.Context context = context(pos -> {
            calls[0]++;
            return BIOME_A;
        });

        context.blockX = 10; // updateXZ needs a SurfaceSystem; assigning the fields it sets is equivalent here
        context.blockZ = -20;
        context.updateY(3, 1, 64, 10, 55, -20);
        Assertions.assertEquals(0, calls[0], "updateY must not invoke the getter (lazy)");

        Assertions.assertSame(BIOME_A, context.biome());
        Assertions.assertSame(BIOME_A, context.biome());
        Assertions.assertSame(BIOME_A, context.biome());
        Assertions.assertEquals(1, calls[0], "repeated biome() must reuse the memoized holder");

        context.updateY(4, 2, 64, 10, 54, -20);
        Assertions.assertSame(BIOME_A, context.biome());
        Assertions.assertEquals(2, calls[0], "a new updateY window must recompute");
    }

    @Test
    void biomeGetterSeesUpdateYCoordinatesAtFirstUse() {
        final List<Long> seen = new ArrayList<>();
        final SurfaceRules.Context context = context(pos -> {
            seen.add(pos.asLong());
            return BIOME_B;
        });

        context.blockX = 100;
        context.blockZ = 200;
        context.updateY(1, 1, Integer.MIN_VALUE, 100, 40, 200);
        Assertions.assertTrue(seen.isEmpty(), "deferred: no invocation until first use");
        context.biome();
        Assertions.assertEquals(1, seen.size());
        Assertions.assertEquals(new BlockPos(100, 40, 200).asLong(), seen.get(0).longValue(),
            "getter must observe exactly the updateY (blockX, blockY, blockZ)");
    }

    @Test
    void throwingGetterRetriesOnNextCall() {
        final int[] calls = {0};
        final SurfaceRules.Context context = context(pos -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new IllegalStateException("first window lookup fails");
            }
            return BIOME_B;
        });

        context.blockX = 0;
        context.blockZ = 0;
        context.updateY(1, 1, 0, 0, 0, 0);
        Assertions.assertThrows(IllegalStateException.class, context::biome);
        Assertions.assertSame(BIOME_B, context.biome(), "failure must not poison the window (Guava memoize retries)");
        Assertions.assertEquals(2, calls[0]);

        // a successful compute is cached for the rest of the window
        Assertions.assertSame(BIOME_B, context.biome());
        Assertions.assertEquals(2, calls[0]);
    }

    @Test
    void sequenceRuleOrderAndShortCircuitPreserved() {
        final List<Integer> evaluated = new ArrayList<>();
        final SurfaceRules.SurfaceRule miss = (x, y, z) -> {
            evaluated.add(0);
            return null;
        };
        final SurfaceRules.SurfaceRule hit = (x, y, z) -> {
            evaluated.add(1);
            return stone;
        };
        final SurfaceRules.SurfaceRule never = (x, y, z) -> {
            evaluated.add(2);
            return dirt;
        };

        Assertions.assertNull(new SurfaceRules.SequenceRule(List.of(miss, miss)).tryApply(1, 2, 3), "all-miss sequence returns null");
        Assertions.assertEquals(List.of(0, 0), evaluated, "every rule evaluated when none matches");

        evaluated.clear();
        Assertions.assertSame(stone, new SurfaceRules.SequenceRule(List.of(miss, hit, never)).tryApply(1, 2, 3));
        Assertions.assertEquals(List.of(0, 1), evaluated, "first non-null wins and later rules are skipped");
    }

    @Test
    void emptySequenceReturnsNull() {
        Assertions.assertNull(new SurfaceRules.SequenceRule(List.of()).tryApply(0, 0, 0));
    }
}
// Papo end - batch 165 (R24)
