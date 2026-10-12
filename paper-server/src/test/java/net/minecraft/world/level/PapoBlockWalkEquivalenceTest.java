package net.minecraft.world.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Papo batch 188 (R47): oracle differential for the de-allocation rewrite of
 * {@code BlockGetter.forEachBlockIntersectedBetween} (travel-dedupe LongOpenHashSet -> caller-owned
 * scratch; three {@code BlockPos.betweenCornersInDirection} iterator loops -> index walk through a
 * per-loop MutableBlockPos cursor).
 *
 * The oracle below is a verbatim copy of the pre-R47 method body built on the still-unmodified
 * public {@code BlockPos.betweenCornersInDirection} primitives; the production path is the patched
 * package-private static in this same package. Identity = identical position sequences, identical
 * step indices, identical early-termination, identical per-loop cursor identity structure (the
 * shared-cursor aliasing vanilla had inside one loop), and the scratch contract (empty on every
 * exit path, reusable, exception-safe).
 */
public class PapoBlockWalkEquivalenceTest {

    // ---------- oracle: verbatim pre-R47 body (public primitives only) ----------

    private static boolean oracleForEach(Vec3 from, Vec3 to, AABB boundingBox, BlockGetter.BlockStepVisitor visitor) {
        Vec3 vec3 = to.subtract(from);
        if (vec3.lengthSqr() < Mth.square(1.0E-5F)) {
            for (BlockPos blockPos : BlockPos.betweenClosed(boundingBox)) {
                if (!visitor.visit(blockPos, 0)) {
                    return false;
                }
            }

            return true;
        } else {
            LongSet set = new LongOpenHashSet();

            for (BlockPos blockPos1 : BlockPos.betweenCornersInDirection(boundingBox.move(vec3.scale(-1.0)), vec3)) {
                if (!visitor.visit(blockPos1, 0)) {
                    return false;
                }

                set.add(blockPos1.asLong());
            }

            int i = oracleAddCollisionsAlongTravel(set, vec3, boundingBox, visitor);
            if (i < 0) {
                return false;
            } else {
                for (BlockPos blockPos2 : BlockPos.betweenCornersInDirection(boundingBox, vec3)) {
                    if (set.add(blockPos2.asLong()) && !visitor.visit(blockPos2, i + 1)) {
                        return false;
                    }
                }

                return true;
            }
        }
    }

    private static int oracleAddCollisionsAlongTravel(LongSet output, Vec3 travelVector, AABB boundingBox, BlockGetter.BlockStepVisitor stepVisitor) {
        double xsize = boundingBox.getXsize();
        double ysize = boundingBox.getYsize();
        double zsize = boundingBox.getZsize();
        Vec3i furthestCorner = oracleGetFurthestCorner(travelVector);
        Vec3 center = boundingBox.getCenter();
        Vec3 vec3 = new Vec3(
            center.x() + xsize * 0.5 * furthestCorner.getX(),
            center.y() + ysize * 0.5 * furthestCorner.getY(),
            center.z() + zsize * 0.5 * furthestCorner.getZ()
        );
        Vec3 vec31 = vec3.subtract(travelVector);
        int floor = Mth.floor(vec31.x);
        int floor1 = Mth.floor(vec31.y);
        int floor2 = Mth.floor(vec31.z);
        int i = Mth.sign(travelVector.x);
        int i1 = Mth.sign(travelVector.y);
        int i2 = Mth.sign(travelVector.z);
        double d = i == 0 ? Double.MAX_VALUE : i / travelVector.x;
        double d1 = i1 == 0 ? Double.MAX_VALUE : i1 / travelVector.y;
        double d2 = i2 == 0 ? Double.MAX_VALUE : i2 / travelVector.z;
        double d3 = d * (i > 0 ? 1.0 - Mth.frac(vec31.x) : Mth.frac(vec31.x));
        double d4 = d1 * (i1 > 0 ? 1.0 - Mth.frac(vec31.y) : Mth.frac(vec31.y));
        double d5 = d2 * (i2 > 0 ? 1.0 - Mth.frac(vec31.z) : Mth.frac(vec31.z));
        int i3 = 0;

        while (d3 <= 1.0 || d4 <= 1.0 || d5 <= 1.0) {
            if (d3 < d4) {
                if (d3 < d5) {
                    floor += i;
                    d3 += d;
                } else {
                    floor2 += i2;
                    d5 += d2;
                }
            } else if (d4 < d5) {
                floor1 += i1;
                d4 += d1;
            } else {
                floor2 += i2;
                d5 += d2;
            }

            Optional<Vec3> optional = AABB.clip(floor, floor1, floor2, floor + 1, floor1 + 1, floor2 + 1, vec31, vec3);
            if (!optional.isEmpty()) {
                i3++;
                Vec3 vec32 = optional.get();
                double d6 = Mth.clamp(vec32.x, floor + 1.0E-5F, floor + 1.0 - 1.0E-5F);
                double d7 = Mth.clamp(vec32.y, floor1 + 1.0E-5F, floor1 + 1.0 - 1.0E-5F);
                double d8 = Mth.clamp(vec32.z, floor2 + 1.0E-5F, floor2 + 1.0 - 1.0E-5F);
                int floor3 = Mth.floor(d6 - xsize * furthestCorner.getX());
                int floor4 = Mth.floor(d7 - ysize * furthestCorner.getY());
                int floor5 = Mth.floor(d8 - zsize * furthestCorner.getZ());
                int i4 = i3;

                for (BlockPos blockPos : BlockPos.betweenCornersInDirection(floor, floor1, floor2, floor3, floor4, floor5, travelVector)) {
                    if (output.add(blockPos.asLong()) && !stepVisitor.visit(blockPos, i4)) {
                        return -1;
                    }
                }
            }
        }

        return i3;
    }

    private static Vec3i oracleGetFurthestCorner(Vec3 vector) {
        double abs = Math.abs(Vec3.X_AXIS.dot(vector));
        double abs1 = Math.abs(Vec3.Y_AXIS.dot(vector));
        double abs2 = Math.abs(Vec3.Z_AXIS.dot(vector));
        int i = vector.x >= 0.0 ? 1 : -1;
        int i1 = vector.y >= 0.0 ? 1 : -1;
        int i2 = vector.z >= 0.0 ? 1 : -1;
        if (abs <= abs1 && abs <= abs2) {
            return new Vec3i(-i, -i2, i1);
        } else {
            return abs1 <= abs2 ? new Vec3i(i2, -i1, -i) : new Vec3i(-i1, i, -i2);
        }
    }

    // ---------- recorded runs ----------

    private record Run(List<String> visits, List<Integer> cursorGroups, boolean completed) {}

    private static Run recordOracle(Vec3 from, Vec3 to, AABB box) {
        List<String> visits = new ArrayList<>();
        List<Integer> cursorGroups = new ArrayList<>();
        int[] lastCursor = new int[]{-1};
        boolean completed = oracleForEach(from, to, box, (pos, index) -> {
            visits.add(index + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            int id = System.identityHashCode(pos);
            if (id != lastCursor[0]) {
                cursorGroups.add(1);
                lastCursor[0] = id;
            } else {
                cursorGroups.set(cursorGroups.size() - 1, cursorGroups.get(cursorGroups.size() - 1) + 1);
            }
            return true;
        });
        return new Run(visits, cursorGroups, completed);
    }

    private static Run recordProduction(Vec3 from, Vec3 to, AABB box, LongSet scratch) {
        List<String> visits = new ArrayList<>();
        List<Integer> cursorGroups = new ArrayList<>();
        int[] lastCursor = new int[]{-1};
        boolean completed = BlockGetter.forEachBlockIntersectedBetween(from, to, box, (pos, index) -> {
            visits.add(index + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            int id = System.identityHashCode(pos);
            if (id != lastCursor[0]) {
                cursorGroups.add(1);
                lastCursor[0] = id;
            } else {
                cursorGroups.set(cursorGroups.size() - 1, cursorGroups.get(cursorGroups.size() - 1) + 1);
            }
            return true;
        }, scratch);
        return new Run(visits, cursorGroups, completed);
    }

    private static final Vec3[] FROM_TO = {
        new Vec3(0.5, 64.5, 0.5), new Vec3(0.5, 65.3, 0.5),          // item falling +y 0.8
        new Vec3(0.2, 64.2, 0.3), new Vec3(1.8, 65.9, 2.4),          // diagonal multi-block
        new Vec3(-3.7, 200.3, 820.5), new Vec3(-3.7, 201.1, 820.5),  // y-up, far negative/positive coords
        new Vec3(10.5, 70.5, 10.5), new Vec3(13.5, 70.5, 10.5),      // pure +x 3 blocks
        new Vec3(5.5, 70.5, 5.5), new Vec3(5.5000005, 70.5, 5.5),    // sub-1e-5 -> stationary branch
        new Vec3(-2.5, -64.5, -100.5), new Vec3(-6.5, -66.5, -104.5) // negative multi-block
    };

    private static final double[][] BOXES = {
        {0.25, 0.25, 0.25},  // item-sized
        {0.6, 1.8, 0.6},     // player-sized
        {0.9, 0.9, 0.9}      // cube
    };

    private static AABB boxAround(Vec3 to, double hx, double hy, double hz) {
        return new AABB(to.x - hx, to.y - hy, to.z - hz, to.x + hx, to.y + hy, to.z + hz);
    }

    @Test
    void walkEquivalenceAndCursorAliasingParity() {
        int combos = 0;
        for (int p = 0; p < FROM_TO.length; p += 2) {
            for (double[] box : BOXES) {
                Vec3 from = FROM_TO[p];
                Vec3 to = FROM_TO[p + 1];
                AABB aabb = boxAround(to, box[0], box[1], box[2]);
                Run oracle = recordOracle(from, to, aabb);
                Run production = recordProduction(from, to, aabb, null);
                assertEquals(oracle.visits(), production.visits(),
                    "visit sequence mismatch for from=" + from + " to=" + to + " box=" + boxDims(aabb)
                        + "\noracle=" + oracle.visits() + "\nprod   =" + production.visits());
                assertEquals(oracle.completed(), production.completed(), "completion mismatch for " + from + "->" + to);
                // One shared cursor per loop in BOTH implementations: the per-loop cursor identity
                // structure (visit counts per distinct cursor instance) must match exactly.
                assertEquals(oracle.cursorGroups(), production.cursorGroups(),
                    "cursor aliasing structure mismatch (per-loop shared cursor) for from=" + from + " to=" + to);
                combos++;
            }
        }
        assertTrue(combos >= 18, "grid did not run, combos=" + combos);
    }

    @Test
    void earlyTerminationParity() {
        Vec3 from = new Vec3(0.2, 64.2, 0.3);
        Vec3 to = new Vec3(1.8, 65.9, 2.4);
        AABB aabb = boxAround(to, 0.6, 1.8, 0.6);
        Run oracleFull = recordOracle(from, to, aabb);
        assertTrue(oracleFull.visits().size() > 3, "oracle produced too few visits to test termination");
        for (int stopAfter : new int[]{1, 3, oracleFull.visits().size() / 2}) {
            for (boolean useScratch : new boolean[]{false, true}) {
                LongSet scratch = useScratch ? new LongOpenHashSet() : null;
                int[] seen = new int[]{0};
                boolean completed = BlockGetter.forEachBlockIntersectedBetween(from, to, aabb, (pos, index) -> {
                    seen[0]++;
                    return seen[0] < stopAfter;
                }, scratch);
                assertEquals(false, completed, "visitor declining must terminate the walk (stopAfter=" + stopAfter + ")");
                assertEquals(stopAfter, seen[0], "must stop exactly at the declining visit (stopAfter=" + stopAfter + ")");
                if (useScratch) {
                    assertTrue(scratch.isEmpty(), "scratch must be empty after early-termination exit");
                }
            }
        }
    }

    @Test
    void scratchVariantIdentityReuseAndExceptionSafety() {
        Vec3 from = new Vec3(-3.7, 200.3, 820.5);
        Vec3 to = new Vec3(-3.7, 201.1, 820.5);
        AABB aabb = boxAround(to, 0.25, 0.25, 0.25);
        Run oracle = recordOracle(from, to, aabb);
        LongSet scratch = new LongOpenHashSet();
        Run withScratch = recordProduction(from, to, aabb, scratch);
        assertEquals(oracle.visits(), withScratch.visits(), "scratch variant must produce the oracle sequence");
        assertTrue(scratch.isEmpty(), "scratch must be left empty after a normal completed run");
        // reuse the same scratch for a second, different walk: still the oracle sequence
        Vec3 from2 = new Vec3(10.5, 70.5, 10.5);
        Vec3 to2 = new Vec3(13.5, 70.5, 10.5);
        AABB aabb2 = boxAround(to2, 0.9, 0.9, 0.9);
        Run oracle2 = recordOracle(from2, to2, aabb2);
        Run reused = recordProduction(from2, to2, aabb2, scratch);
        assertEquals(oracle2.visits(), reused.visits(), "reused scratch must not leak state into the next walk");
        // exception path: visitor throws mid-walk -> propagates AND scratch is cleared (try/finally)
        int[] seen = new int[]{0};
        RuntimeException boom = new RuntimeException("stop-mid-walk");
        RuntimeException caught = null;
        try {
            BlockGetter.forEachBlockIntersectedBetween(from, to, aabb, (pos, index) -> {
                seen[0]++;
                if (seen[0] == 2) {
                    throw boom;
                }
                return true;
            }, scratch);
        } catch (RuntimeException e) {
            caught = e;
        }
        assertEquals(boom, caught, "visitor exception must propagate unchanged");
        assertTrue(scratch.isEmpty(), "scratch must be empty even when the visitor throws (finally-clear)");
    }

    private static String boxDims(AABB box) {
        return box.getXsize() + "x" + box.getYsize() + "x" + box.getZsize();
    }
}
