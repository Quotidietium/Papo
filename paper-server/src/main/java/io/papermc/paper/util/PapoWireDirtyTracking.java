package io.papermc.paper.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// Papo start - batch 126: production wire input-dirty tracking (per-Level instance)
// Model: a redstone wire is "dirty" when any input of it changed since its last
// evaluation. Notification-driven evaluations that arrive while clean are skipped:
// unchanged inputs mean calculateTargetStrength would return exactly the stored
// POWER, and the no-change path of updatePowerStrength performs zero observable
// effects (no event, no setBlock, no fan-out) - so skipping it is equivalent by
// construction. Batch 126 survey measured 85.0% of ring-oscillator evaluations
// skippable.
//
// Input closure (what a transition at pos must mark):
//  - every wire within Chebyshev distance 1 (the 3x3x3 input cube of a wire
//    contains pos), INCLUDING pos itself: a newly placed wire's stored power is
//    the placement default, not the computed value - its first onPlace
//    evaluation must proceed (self-exclusion stalled every oscillator), and a
//    wire's own POWER flip must re-mark itself for the fan-out's self-
//    notification (one extra no-change evaluation as the correctness cost);
//  - the straight-through Chebyshev-2 positions: for each axis direction d, if
//    the block at pos+d is a redstone conductor, the wire at pos+2d reads a
//    direct signal THROUGH it (strong power from a source behind a block) - the
//    classic repeater-powers-block-with-dust-beside topology.
//
// Marking hooks (all real input-change surfaces):
//  - LevelChunk.setBlockState: at the section commit, BEFORE any dispatch of the
//    transition (onPlace / affectNeighborsAfterRemoval run inline from there and
//    their fan-out evaluations must already see the wires dirty - a mark placed
//    in Level.notifyAndUpdatePhysics runs after the whole onPlace cascade, which
//    is how the seed-pulse evaluations arrived clean and got skipped).
//  - WorldGenRegion.setBlock: generation threads skip the chunk hook (the
//    ServerLevel reader could force-load chunks off-thread); the region-bounded
//    reader marks into the live level's tracker (structure paste next to a
//    loaded chunk).
//  - DiodeBlock.updateNeighborsInFront + Level.updateNeighbourForOutputSignal:
//    the two notification funnels for signal-strength changes that happen
//    WITHOUT a block transition (comparator block-entity output refresh, analog
//    output pokes). State-based sources (daylight sensor POWER, plates, buttons,
//    repeaters, torches, ...) all transition and are covered by the commit hook.
//
// Instance-per-Level: bits are keyed by packed pos only, so a global set shared
// across dimensions/sides would let two wires at the same packed pos in
// different levels CLEAR each other's bits - an unsound skip. One tracker per
// Level (field Level.papoWireDirty).
//
// Bound: entries are only ever wires, cleared at their next evaluation; a leak
// valve clears everything past a large cap (all wires then simply re-evaluate
// once dirty).
public final class PapoWireDirtyTracking {

    private static final int STRIPES = 8;
    private static final int CAP = 1 << 18;
    private final LongOpenHashSet[] dirty = new LongOpenHashSet[STRIPES];
    private final Object[] locks = new Object[STRIPES];

    public PapoWireDirtyTracking() {
        for (int i = 0; i < STRIPES; i++) {
            this.dirty[i] = new LongOpenHashSet(64);
            this.locks[i] = new Object();
        }
    }

    private static int stripe(final long packed) {
        // mix the high bits (y is low in BlockPos packing) into the stripe choice
        return (int) ((packed ^ (packed >>> 27)) & (STRIPES - 1));
    }

    /**
     * Marks every wire whose input closure contains a change at {@code pos}.
     * The reader must be safe for the calling thread (a tick-thread Level, a
     * client level, or a WorldGenRegion).
     */
    public void mark(final BlockGetter reader, final BlockPos pos) {
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        final BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // (0,0,0) IS included - see the class comment
                    scan.set(x + dx, y + dy, z + dz);
                    this.markIfWire(reader, scan);
                }
            }
        }
        // straight-through closure: a source behind a conductor feeds the wire two
        // out along the axis (direct signal through the strongly powered block)
        scan.set(x + 1, y, z);
        if (this.isConductor(reader, scan)) {
            scan.set(x + 2, y, z);
            this.markIfWire(reader, scan);
        }
        scan.set(x - 1, y, z);
        if (this.isConductor(reader, scan)) {
            scan.set(x - 2, y, z);
            this.markIfWire(reader, scan);
        }
        scan.set(x, y + 1, z);
        if (this.isConductor(reader, scan)) {
            scan.set(x, y + 2, z);
            this.markIfWire(reader, scan);
        }
        scan.set(x, y - 1, z);
        if (this.isConductor(reader, scan)) {
            scan.set(x, y - 2, z);
            this.markIfWire(reader, scan);
        }
        scan.set(x, y, z + 1);
        if (this.isConductor(reader, scan)) {
            scan.set(x, y, z + 2);
            this.markIfWire(reader, scan);
        }
        scan.set(x, y, z - 1);
        if (this.isConductor(reader, scan)) {
            scan.set(x, y, z - 2);
            this.markIfWire(reader, scan);
        }
    }

    private boolean isConductor(final BlockGetter reader, final BlockPos pos) {
        return reader.getBlockState(pos).isRedstoneConductor(reader, pos);
    }

    private void markIfWire(final BlockGetter reader, final BlockPos pos) {
        final BlockState state = reader.getBlockState(pos);
        if (state.is(Blocks.REDSTONE_WIRE)) {
            final long packed = pos.asLong();
            final int s = stripe(packed);
            synchronized (this.locks[s]) {
                if (this.dirty[s].size() >= CAP) {
                    this.dirty[s].clear();
                }
                this.dirty[s].add(packed);
            }
        }
    }

    /**
     * Wire-evaluation entry. @return true when the wire is clean at entry - the
     * evaluation is provably redundant and must be skipped; false when dirty (the
     * bit is cleared and the evaluation must proceed).
     */
    public boolean evalEntry(final BlockPos pos) {
        final long packed = pos.asLong();
        final int s = stripe(packed);
        synchronized (this.locks[s]) {
            if (!this.dirty[s].contains(packed)) {
                return true;
            }
            this.dirty[s].remove(packed);
        }
        return false;
    }
}
// Papo end - batch 126
