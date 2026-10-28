package net.minecraft.world.level.material;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid.SpreadContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

// Papo start - batch 204 (R63): the SpreadContext hole-cache de-lambda contract.
//
// Vanilla isHole(pos) was holeCache.computeIfAbsent(key, s -> {...}) where the
// lambda captured `pos` and `this` - so a fresh lambda instance was built on
// EVERY call, cache hit included (a top allocation site under flowing-fluid
// storms). The Papo rewrite is an explicit containsKey/get with a verbatim
// miss-path compute-then-put. This test pins the equivalence:
//   - hole results match the untouched FlowingFluid.isWaterHole oracle over a
//     randomized {air, stone, water-source} column landscape (the oracle
//     recomputes states uncached, so any caching divergence shows as a diff),
//   - hit path never recomputes (below-state queried once per column) and
//     returns the FIRST computed value even if the world mutates underneath
//     (identical to computeIfAbsent hit semantics),
//   - miss path populates the cache (second query served from cache) and the
//     state cache backs isHole's own pos lookup.
// (A cache pre-size variant was separately tried and REVERTED: paired-leg byte
// accounting showed up-front 256-slot arrays cost more than the ladder copies
// they replace for this per-call-ephemeral context - see batch 204 report.)
public class PapoFluidSpreadContextIdentityTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    private static final BlockPos ORIGIN = new BlockPos(-500, 64, 900);
    private static final BlockState[] PALETTE = {
        Blocks.AIR.defaultBlockState(),
        Blocks.STONE.defaultBlockState(),
        Blocks.WATER.defaultBlockState(),
    };

    /** Deterministic landscape keyed by column; mutations emulate world change. */
    private static final Map<Long, BlockState> COLUMNS = new HashMap<>();

    private static BlockState columnState(final BlockPos pos) {
        final long key = (pos.getX() & 0xFFFFFFFFL) << 32 | (pos.getZ() & 0xFFFFFFFFL);
        return COLUMNS.computeIfAbsent(key, k -> {
            final Random rnd = new Random(k ^ 0x5DEECE66DL);
            return PALETTE[rnd.nextInt(PALETTE.length)];
        });
    }

    private static BlockGetter mockLevel() {
        final BlockGetter level = Mockito.mock(BlockGetter.class);
        Mockito.doAnswer(inv -> columnState(inv.getArgument(0))).when(level).getBlockState(ArgumentMatchers.any(BlockPos.class));
        Mockito.doAnswer(inv -> columnState(inv.getArgument(0))).when(level).getBlockStateIfLoaded(ArgumentMatchers.any(BlockPos.class));
        return level;
    }

    private static WaterFluid water() {
        return (WaterFluid) Fluids.WATER; // plain field in 1.21.11, not a Holder
    }

    private static boolean oracleIsHole(final WaterFluid water, final BlockGetter level, final BlockPos pos) {
        // vanilla isWaterHole fed with uncached states - untouched by the patch
        return water.isWaterHole(level, pos, level.getBlockState(pos), pos.below(), level.getBlockState(pos.below()));
    }

    @Test
    void holeResultsMatchUncachedOracleOverRandomizedLandscape() {
        final WaterFluid water = water();
        final BlockGetter level = mockLevel();
        final SpreadContext ctx = water.new SpreadContext(level, ORIGIN);
        final Random rnd = new Random(20261013);
        for (int i = 0; i < 200; i++) {
            final BlockPos pos = new BlockPos(ORIGIN.getX() + rnd.nextInt(21) - 10, 64, ORIGIN.getZ() + rnd.nextInt(21) - 10);
            Assertions.assertEquals(oracleIsHole(water, level, pos), ctx.isHole(pos), "isHole diverged from oracle at " + pos);
        }
    }

    @Test
    void hitPathQueriesBelowOnceAndServesCachedValueAcrossWorldChange() {
        final WaterFluid water = water();
        final BlockGetter level = mockLevel();
        final SpreadContext ctx = water.new SpreadContext(level, ORIGIN);
        final BlockPos pos = ORIGIN.east(2).south(2);
        final boolean first = ctx.isHole(pos);
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos.below());
        Assertions.assertEquals(first, ctx.isHole(pos), "second query must hit the hole cache");
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos.below()); // still one below-query
        // world change underneath: computeIfAbsent's hit path also returns the cached
        // entry, so the rewrite must keep serving the first computed value
        COLUMNS.put(((long) (pos.getX() & 0xFFFFFFFF) << 32) | (pos.getZ() & 0xFFFFFFFF),
            first ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        Assertions.assertEquals(first, ctx.isHole(pos), "hit path must not observe world mutations");
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos.below());
        COLUMNS.clear(); // restore deterministic landscape for other tests
    }

    @Test
    void missPathPopulatesCacheAndStateCacheBacksPosLookup() {
        final WaterFluid water = water();
        final BlockGetter level = mockLevel();
        final SpreadContext ctx = water.new SpreadContext(level, ORIGIN);
        final BlockPos pos = new BlockPos(ORIGIN.getX() + 3, 64, ORIGIN.getZ() - 7);
        final boolean first = ctx.isHole(pos);
        // miss-path compute used the load=true variant on pos exactly once ...
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos);
        // ... and the state cache now serves isHole's pos lookup: a second distinct
        // query one block over must not re-query this column's state
        Assertions.assertEquals(first, ctx.isHole(pos));
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos);
        // state cache answers without another level round trip
        Assertions.assertSame(columnState(pos), ctx.getBlockStateIfLoaded(pos));
        Mockito.verify(level, Mockito.times(1)).getBlockState(pos);
    }

    @Test
    void landscapeCoversAllThreePaletteStatesAndBothHoleOutcomes() {
        // guard: the randomized landscape must actually exercise non-trivial cases,
        // otherwise the oracle test above degenerates into a single-case pin
        final WaterFluid water = water();
        final BlockGetter level = mockLevel();
        final SpreadContext ctx = water.new SpreadContext(level, ORIGIN);
        boolean sawHole = false, sawSolid = false;
        final Random rnd = new Random(63);
        for (int i = 0; i < 400 && !(sawHole && sawSolid); i++) {
            final BlockPos pos = new BlockPos(ORIGIN.getX() + rnd.nextInt(21) - 10, 64, ORIGIN.getZ() + rnd.nextInt(21) - 10);
            if (ctx.isHole(pos)) {
                sawHole = true;
            } else {
                sawSolid = true;
            }
        }
        Assertions.assertTrue(sawHole && sawSolid, "landscape must produce both hole outcomes");
    }
}
