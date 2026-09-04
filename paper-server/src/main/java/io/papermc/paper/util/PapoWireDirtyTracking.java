package io.papermc.paper.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// Papo start - batch 126: production wire input-dirty tracking
// Model: a redstone wire is "dirty" when any block transition occurred within its 3x3x3
// input cube since its last evaluation. Notification-driven evaluations that arrive while
// clean are skipped: unchanged inputs mean calculateTargetStrength would return exactly
// the stored POWER, and the no-change path of updatePowerStrength performs zero observable
// effects (no event, no setBlock, no fan-out) - so skipping it is equivalent by
// construction. Batch 126 survey measured 85.0% of ring-oscillator evaluations skippable.
//
// Marking: every real block transition (old != new) marks wires within Chebyshev distance
// 1 - exactly the set of wires whose input cubes contain the transition. Two hooks cover
// all transition surfaces: Level.notifyAndUpdatePhysics (post-generation, main thread)
// and WorldGenRegion.setBlock (generation threads - structures can paste next to live
// chunks). The set is striped into 8 locks so main-thread marking/evaluation and
// generation-thread marking share it safely.
//
// Bound: entries are only ever wires, cleared at their next evaluation; a leak valve
// clears everything past a large cap (all wires then simply re-evaluate once dirty).
public final class PapoWireDirtyTracking {

    private static final int STRIPES = 8;
    private static final LongOpenHashSet[] DIRTY = new LongOpenHashSet[STRIPES];
    private static final Object[] LOCKS = new Object[STRIPES];
    // generous leak valve: live oscillating loads churn the whole set every tick;
    // anything past this is stale entries from unloaded wires - dropping them only
    // costs one dirty re-evaluation per still-active wire
    private static final int CAP = 1 << 18;

    static {
        for (int i = 0; i < STRIPES; i++) {
            DIRTY[i] = new LongOpenHashSet(512);
            LOCKS[i] = new Object();
        }
    }

    private PapoWireDirtyTracking() {}

    private static int stripe(final long packed) {
        // mix the high bits (y is low in BlockPos packing) into the stripe choice
        return (int) ((packed ^ (packed >>> 27)) & (STRIPES - 1));
    }

    /** Marks wires within Chebyshev distance 1 of a block transition at pos. */
    public static void mark(final BlockGetter level, final BlockPos pos) {
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        final BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        int marked = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // (0,0,0) IS included: a newly placed wire's stored power is the
                    // placement default, not the computed value - its first onPlace
                    // evaluation must proceed, so the wire's own placement transition
                    // has to mark itself. (For a mere POWER flip the self-mark only
                    // costs one extra no-change evaluation via the fan-out's self-
                    // notification - correctness over that micro-skip.)
                    scan.set(x + dx, y + dy, z + dz);
                    final BlockState state = level.getBlockState(scan);
                    if (state.is(Blocks.REDSTONE_WIRE)) {
                        final long packed = scan.asLong();
                        final int s = stripe(packed);
                        synchronized (LOCKS[s]) {
                            if (DIRTY[s].size() >= CAP) {
                                DIRTY[s].clear();
                            }
                            DIRTY[s].add(packed);
                            marked++;
                        }
                    }
                }
            }
        }
    }

    /**
     * Wire-evaluation entry. @return true when the wire is clean at entry - the
     * evaluation is provably redundant and must be skipped; false when dirty (the
     * bit is cleared and the evaluation must proceed).
     */
    public static boolean evalEntry(final Level level, final BlockPos pos) {
        final long packed = pos.asLong();
        final int s = stripe(packed);
        synchronized (LOCKS[s]) {
            if (!DIRTY[s].contains(packed)) {
                return true;
            }
            DIRTY[s].remove(packed);
        }
        return false;
    }
}
// Papo end - batch 126
