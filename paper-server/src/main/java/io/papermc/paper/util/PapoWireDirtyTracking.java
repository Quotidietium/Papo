package io.papermc.paper.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

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
//  - every wire within Chebyshev distance 1 EXCEPT pos itself and EXCEPT the 8
//    corners (the 3x3x3 input cube of a wire contains pos; own pos excluded since
//    batch 127 - a wire's own POWER is not an input to its own calculation, so the
//    self-notification from its own flip is provably redundant; the freshly-placed
//    wire's first onPlace evaluation, whose stored power is the placement default,
//    bypasses the skip via the updateShape parameter in the evaluator instead).
//    The 8 cube corners (±1,±1,±1) are excluded since batch 129: the exact set of
//    positions a wire's evaluation reads is the 6 faces (neighbour signal +
//    conductor), the 12 edges (conductor fan-out W+d+e, variant columns W+h±v),
//    and the axis-2 straight-through positions - every vanilla override involved
//    (getSignal/getDirectSignal/isRedstoneConductor/canSupportCenter) reads only
//    its own position or own block state, so no read path ever touches a diagonal
//    corner; marking a corner wire could only ever produce a redundant no-change
//    evaluation. Since batch 130 the scan reads through a per-mark 2x2 chunk-slot
//    cache (see markLevel) instead of one chunk-map lookup per read;
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
     * client level, or a WorldGenRegion). NOTE: pos itself is deliberately NOT
     * marked (batch 127): a wire's own POWER is not an input to its own
     * calculation (calculateTargetStrength reads neighbours only), so the
     * self-notification from its own flip is provably redundant - the
     * onPlace-driven first evaluation of a freshly placed wire (whose stored
     * power is the placement default) bypasses the skip in the evaluator
     * instead (updateShape == true). The 8 cube corners are skipped since
     * batch 129 (exact input closure - see the class comment): the scan walks
     * the 18 face+edge offsets instead of the 27-cell cube.
     */
    // Papo start - batch 129: the 18 face+edge offsets (6 faces + 12 edges) in a
    // fixed order - provably the only Chebyshev-1 positions any wire evaluation
    // reads; the 8 corners and the center are unreachable by every read path.
    // Order: 6 faces (W E D U N S), 4 x&y edges, 4 x&z edges, 4 y&z edges.
    private static final int[] SCAN_DX = {
        -1, 1, 0, 0, 0, 0,              // faces
        -1, -1, 1, 1,                   // x&y edges
        -1, -1, 1, 1,                   // x&z edges
        0, 0, 0, 0,                     // y&z edges
    };
    private static final int[] SCAN_DY = {
        0, 0, -1, 1, 0, 0,              // faces
        -1, 1, -1, 1,                   // x&y edges
        0, 0, 0, 0,                     // x&z edges
        -1, -1, 1, 1,                   // y&z edges
    };
    private static final int[] SCAN_DZ = {
        0, 0, 0, 0, -1, 1,              // faces
        0, 0, 0, 0,                     // x&y edges
        -1, 1, -1, 1,                   // x&z edges
        -1, 1, -1, 1,                   // y&z edges
    };
    // Papo end - batch 129

    public void mark(final BlockGetter reader, final BlockPos pos) {
        // Papo start - batch 130: Level readers take the column-fixed chunk resolution
        // path (markLevel); every other BlockGetter (WorldGenRegion during generation)
        // keeps the per-read generic path.
        if (reader instanceof Level level && !level.captureTreeGeneration) {
            this.markLevel(level, pos);
        } else {
            this.markGeneric(reader, pos);
        }
        // Papo end - batch 130
    }

    // Papo start - batch 130: axial probe directions (x+, x-, y+, y-, z+, z- - the
    // same sequence the previous explicit six-block form used; probe order does not
    // matter for set population, kept identical for review symmetry).
    private static final int[] AX_DX = {1, -1, 0, 0, 0, 0};
    private static final int[] AX_DY = {0, 0, 1, -1, 0, 0};
    private static final int[] AX_DZ = {0, 0, 0, 0, 1, -1};
    // Papo end - batch 130

    // Papo start - batch 130: column-fixed chunk resolution. Every position mark()
    // reads (18 face+edge scan offsets, 6 axial conductor probes, up to 6
    // straight-through targets at axial ±2) lies within x±2/z±2 of pos, which spans
    // at most a 2x2 grid of chunks. Each grid slot is resolved ONCE through the exact
    // call Level.getBlockState makes internally (getChunk(cx, cz, FULL, true):
    // fullChunks map get with the force-load fallback preserved), and every read in
    // that slot goes straight to chunk.getBlockState - the per-read chunk-map lookup
    // collapses from ~24-30 to <=4 per mark. Equivalence: Level.getBlockState
    // = captureTreeGeneration check (routed out at mark() entry) + isInValidBounds +
    // getChunk + chunk.getBlockState; bounds parity holds because VOID_AIR (out of
    // horizontal bounds) and the section-gap AIR (out of vertical bounds, returned by
    // LevelChunk.getBlockStateFinal) are never a wire and never a conductor, so the
    // preflight ChunkPos.isValid check below routes exotic world-border sources to
    // the generic path and everything else reads bit-identical states.
    private void markLevel(final Level level, final BlockPos pos) {
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        final int baseCx = (x - 2) >> 4;
        final int baseCz = (z - 2) >> 4;
        if (!ChunkPos.isValid(baseCx, baseCz) || !ChunkPos.isValid(baseCx + 1, baseCz)
            || !ChunkPos.isValid(baseCx, baseCz + 1) || !ChunkPos.isValid(baseCx + 1, baseCz + 1)) {
            this.markGeneric(level, pos);
            return;
        }
        final ChunkAccess[] chunks = new ChunkAccess[4]; // slot = ((cx-baseCx)<<1) | (cz-baseCz)
        final BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SCAN_DX.length; i++) {
            scan.set(x + SCAN_DX[i], y + SCAN_DY[i], z + SCAN_DZ[i]);
            this.markIfWire(this.papoChunk(level, chunks, baseCx, baseCz, scan.getX(), scan.getZ()).getBlockState(scan), scan);
        }
        // straight-through closure: a source behind a conductor feeds the wire two
        // out along the axis (direct signal through the strongly powered block)
        for (int d = 0; d < 6; d++) {
            final int ax = AX_DX[d];
            final int ay = AX_DY[d];
            final int az = AX_DZ[d];
            scan.set(x + ax, y + ay, z + az);
            if (!this.papoChunk(level, chunks, baseCx, baseCz, scan.getX(), scan.getZ()).getBlockState(scan).isRedstoneConductor(level, scan)) {
                continue;
            }
            scan.set(x + 2 * ax, y + 2 * ay, z + 2 * az);
            this.markIfWire(this.papoChunk(level, chunks, baseCx, baseCz, scan.getX(), scan.getZ()).getBlockState(scan), scan);
        }
    }

    private static ChunkAccess papoChunk(final Level level, final ChunkAccess[] cache, final int baseCx, final int baseCz, final int px, final int pz) {
        final int slot = (((px >> 4) - baseCx) << 1) | ((pz >> 4) - baseCz);
        ChunkAccess chunk = cache[slot];
        if (chunk == null) {
            chunk = cache[slot] = level.getChunk(px >> 4, pz >> 4, ChunkStatus.FULL, true);
        }
        return chunk;
    }
    // Papo end - batch 130

    // Papo start - batch 130: the generic per-read path (WorldGenRegion readers and
    // the world-border/capture exotics) - byte-for-byte the pre-130 scan sequence.
    private void markGeneric(final BlockGetter reader, final BlockPos pos) {
        final int x = pos.getX();
        final int y = pos.getY();
        final int z = pos.getZ();
        final BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        for (int i = 0; i < SCAN_DX.length; i++) {
            scan.set(x + SCAN_DX[i], y + SCAN_DY[i], z + SCAN_DZ[i]);
            this.markIfWire(reader, scan);
        }
        // straight-through closure: a source behind a conductor feeds the wire two
        // out along the axis (direct signal through the strongly powered block)
        for (int d = 0; d < 6; d++) {
            final int ax = AX_DX[d];
            final int ay = AX_DY[d];
            final int az = AX_DZ[d];
            scan.set(x + ax, y + ay, z + az);
            if (!reader.getBlockState(scan).isRedstoneConductor(reader, scan)) {
                continue;
            }
            scan.set(x + 2 * ax, y + 2 * ay, z + 2 * az);
            this.markIfWire(reader, scan);
        }
    }
    // Papo end - batch 130

    private void markIfWire(final BlockGetter reader, final BlockPos pos) {
        this.markIfWire(reader.getBlockState(pos), pos);
    }

    // Papo start - batch 130: state-based core shared by both paths (the generic
    // wrapper above keeps the original reader-based signature).
    private void markIfWire(final BlockState state, final BlockPos pos) {
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
    // Papo end - batch 130

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
