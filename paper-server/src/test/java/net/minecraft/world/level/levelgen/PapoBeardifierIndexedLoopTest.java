package net.minecraft.world.level.levelgen;

import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 174 (R33): Beardifier.compute's enhanced-for loops were rewritten
// as indexed loops (the per-call ListItr allocation was 228 JFR trips in the streaming
// census; compute() runs per noise cell of every structure-adjacent chunk). The
// formulas are asserted against a call-identical oracle for every terrain-adjustment
// arm plus the junction path, additivity, and the two early-zero paths, so a
// loop-carried rewrite mistake cannot pass silently.
public class PapoBeardifierIndexedLoopTest {

    private static final BoundingBox BIG = new BoundingBox(0, 0, 0, 32, 128, 32);

    private static final DensityFunction.FunctionContext AT = new DensityFunction.FunctionContext() {
        @Override
        public int blockX() {
            return 8;
        }

        @Override
        public int blockY() {
            return 64;
        }

        @Override
        public int blockZ() {
            return 8;
        }
    };

    // == Beardifier.getBuryContribution, call-identical
    private static double bury(final double x, final double y, final double z) {
        return Mth.clampedMap(Mth.length(x, y, z), 0.0, 6.0, 1.0, 0.0);
    }

    // == Beardifier.getBeardContribution, with the BEARD_KERNEL value re-derived from
    // the exact initializing expression: KERNEL[(z+12)*576 + (x+12)*24 + (y+12)] is
    // (float) Math.pow(Math.E, -lengthSquared(x, y + 0.5, z) / 16.0)
    private static double beard(final int x, final int y, final int z, final int height) {
        final int i = x + 12;
        final int i1 = y + 12;
        final int i2 = z + 12;
        if (i < 0 || i >= 24 || i1 < 0 || i1 >= 24 || i2 < 0 || i2 >= 24) {
            return 0.0;
        }
        final double d = height + 0.5;
        final double d1 = Mth.lengthSquared((double)x, d, (double)z);
        final double d2 = -d * Mth.fastInvSqrt(d1 / 2.0) / 2.0;
        return d2 * (float) Math.pow(Math.E, -Mth.lengthSquared((double)x, y + 0.5, (double)z) / 16.0);
    }

    private static Beardifier.Rigid rigid(final BoundingBox box, final TerrainAdjustment adjust, final int groundLevelDelta) {
        return new Beardifier.Rigid(box, adjust, groundLevelDelta);
    }

    private static JigsawJunction junction(final int x, final int y, final int z) {
        return new JigsawJunction(x, y, z, 0, StructureTemplatePool.Projection.RIGID);
    }

    @Test
    void nullBoxAndOutsideBoxReturnZero() {
        final Beardifier nullBox = new Beardifier(
            List.of(rigid(new BoundingBox(0, 60, 0, 16, 70, 16), TerrainAdjustment.BEARD_BOX, 0)),
            List.<JigsawJunction>of(), null);
        Assertions.assertEquals(0.0, nullBox.compute(AT));

        final BoundingBox far = new BoundingBox(1000, 60, 1000, 1016, 70, 1016);
        final Beardifier outside = new Beardifier(
            List.of(rigid(far, TerrainAdjustment.BEARD_BOX, 0)),
            List.<JigsawJunction>of(), far);
        Assertions.assertEquals(0.0, outside.compute(AT));
    }

    @Test
    void everyAdjustmentArmMatchesVanillaFormula() {
        // query point AT=(8,64,8) throughout
        //
        // ENCAPSULATE, groundLevelDelta=0, box(0,60,0)-(4,68,4):
        //   lateral X/Z distance 4 each, i3=60, i4=4, i5=0
        //   -> getBuryContribution(4/2, 0/2, 4/2) * 0.8
        final Beardifier encapsulate = new Beardifier(
            List.of(rigid(new BoundingBox(0, 60, 0, 4, 68, 4), TerrainAdjustment.ENCAPSULATE, 0)),
            List.<JigsawJunction>of(), BIG);
        Assertions.assertEquals(bury(2.0, 0.0, 2.0) * 0.8, encapsulate.compute(AT), 0.0);

        // BURY, groundLevelDelta=0, box(4,55,4)-(12,60,12):
        //   lateral 0, i3=55, i4=9 (== i5)
        //   -> getBuryContribution(0, 9/2, 0)
        final Beardifier burying = new Beardifier(
            List.of(rigid(new BoundingBox(4, 55, 4, 12, 60, 12), TerrainAdjustment.BURY, 0)),
            List.<JigsawJunction>of(), BIG);
        Assertions.assertEquals(bury(0.0, 4.5, 0.0), burying.compute(AT), 0.0);

        // BEARD_BOX, groundLevelDelta=0, same box: i5 = 4, i4 = 9
        //   -> getBeardContribution(0, 4, 0, 9) * 0.8
        final Beardifier beardBox = new Beardifier(
            List.of(rigid(new BoundingBox(4, 55, 4, 12, 60, 12), TerrainAdjustment.BEARD_BOX, 0)),
            List.<JigsawJunction>of(), BIG);
        Assertions.assertEquals(beard(0, 4, 0, 9) * 0.8, beardBox.compute(AT), 0.0);

        // BEARD_THIN shares the BEARD_BOX contribution formula but takes i5 from the
        // BURY arm (i5 = i4 = 9), so its kernel lookup sits at a different y
        final Beardifier beardThin = new Beardifier(
            List.of(rigid(new BoundingBox(4, 55, 4, 12, 60, 12), TerrainAdjustment.BEARD_THIN, 0)),
            List.<JigsawJunction>of(), BIG);
        Assertions.assertEquals(beard(0, 9, 0, 9) * 0.8, beardThin.compute(AT), 0.0);

        // NONE contributes exactly zero
        final Beardifier none = new Beardifier(
            List.of(rigid(new BoundingBox(4, 55, 4, 12, 60, 12), TerrainAdjustment.NONE, 0)),
            List.<JigsawJunction>of(), BIG);
        Assertions.assertEquals(0.0, none.compute(AT), 0.0);
    }

    @Test
    void junctionAndAdditivityMatchVanillaFormula() {
        // junction at (10,65,10): dx=-2, dy=-1, dz=-2 -> beard(-2,-1,-2,-1) * 0.4
        final Beardifier onlyJunction = new Beardifier(
            List.<Beardifier.Rigid>of(), List.of(junction(10, 65, 10)), BIG);
        Assertions.assertEquals(beard(-2, -1, -2, -1) * 0.4, onlyJunction.compute(AT), 0.0);

        // out-of-kernel-range junction (dx=+13 -> i=25 >= 24) contributes zero
        final Beardifier farJunction = new Beardifier(
            List.<Beardifier.Rigid>of(), List.of(junction(-5, 64, 8)), BIG);
        Assertions.assertEquals(0.0, farJunction.compute(AT), 0.0);

        // piece + junction add, and piece order accumulation is preserved
        final Beardifier both = new Beardifier(
            List.of(rigid(new BoundingBox(4, 55, 4, 12, 60, 12), TerrainAdjustment.BEARD_BOX, 0)),
            List.of(junction(10, 65, 10)), BIG);
        Assertions.assertEquals(beard(0, 4, 0, 9) * 0.8 + beard(-2, -1, -2, -1) * 0.4, both.compute(AT), 0.0);
    }
}
