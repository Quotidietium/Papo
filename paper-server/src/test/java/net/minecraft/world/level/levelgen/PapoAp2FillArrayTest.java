package net.minecraft.world.level.levelgen;

import net.minecraft.world.level.levelgen.DensityFunction.ContextProvider;
import net.minecraft.world.level.levelgen.DensityFunction.FunctionContext;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunctions.TwoArgumentSimpleFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 179 (R38): Ap2(ADD).fillArray stages its combine temp in a
// per-thread per-depth slot stack (PapoAddTempStack) instead of allocating a fresh
// double[array.length] per call - the largest allocation face of the streaming
// census (~943MB per 60s window during chunk noise fill). The temp is fully
// written by argument2.fillArray and consumed in the same call, so reuse is
// unobservable; asserted here: exact combine arithmetic (plain and through the
// public factory), no residue across sequential calls with different values,
// correct behavior under nested Ap2(ADD) trees (each depth needs its own live
// temp), prefix zeroing when slot capacity exceeds request, stack unwinding when
// argument2 throws, and the zero-prefix parity property (an argument that leaves
// indices unwritten contributes 0.0 exactly like a fresh array would).
public class PapoAp2FillArrayTest {

    private static final ContextProvider NULL_PROVIDER = new ContextProvider() {
        @Override
        public FunctionContext forIndex(int index) {
            throw new UnsupportedOperationException("fillAll path not used by these tests");
        }

        @Override
        public void fillAllDirectly(double[] values, DensityFunction function) {
            throw new UnsupportedOperationException();
        }
    };

    /** writes array[i] = i * factor; compute returns factor. */
    private static class Linear implements DensityFunction {
        final double factor;

        Linear(double factor) {
            this.factor = factor;
        }

        @Override
        public double compute(FunctionContext context) {
            return this.factor;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            for (int i = 0; i < array.length; i++) {
                array[i] = i * this.factor;
            }
        }

        @Override
        public double minValue() {
            return -Double.MAX_VALUE;
        }

        @Override
        public double maxValue() {
            return Double.MAX_VALUE;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("not needed in fillArray tests");
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this;
        }
    }

    /** writes only every stride-th index (deliberately violates the fill contract). */
    private static class SparseFill implements DensityFunction {
        final double factor;
        final int stride;

        SparseFill(double factor, int stride) {
            this.factor = factor;
            this.stride = stride;
        }

        @Override
        public double compute(FunctionContext context) {
            return this.factor;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {
            for (int i = 0; i < array.length; i += this.stride) {
                array[i] = i * this.factor;
            }
        }

        @Override
        public double minValue() {
            return -Double.MAX_VALUE;
        }

        @Override
        public double maxValue() {
            return Double.MAX_VALUE;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("not needed in fillArray tests");
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this;
        }
    }

    private static DensityFunction add(DensityFunction argument1, DensityFunction argument2) {
        return TwoArgumentSimpleFunction.create(TwoArgumentSimpleFunction.Type.ADD, argument1, argument2);
    }

    @Test
    public void addCombinesExactly() {
        DensityFunction function = add(new Linear(3.0), new Linear(7.0));
        double[] array = new double[128];
        function.fillArray(array, NULL_PROVIDER);
        for (int i = 0; i < array.length; i++) {
            Assertions.assertEquals(i * 3.0 + i * 7.0, array[i], 0.0, "index " + i);
        }
    }

    @Test
    public void sequentialCallsLeaveNoResidue() {
        double[] array = new double[128];
        add(new Linear(1.0), new Linear(2.0)).fillArray(array, NULL_PROVIDER);
        add(new Linear(5.0), new Linear(-4.0)).fillArray(array, NULL_PROVIDER);
        for (int i = 0; i < array.length; i++) {
            Assertions.assertEquals(i * 5.0 + i * -4.0, array[i], 0.0, "index " + i);
        }
    }

    @Test
    public void nestedAddTreeUsesDistinctTemps() {
        // ((L1 + (L2 + (L3 + ... L8)))) - every level needs its own live temp
        DensityFunction tree = new Linear(64.0);
        for (int level = 7; level >= 1; level--) {
            tree = add(new Linear(level), tree);
        }
        double[] array = new double[96];
        tree.fillArray(array, NULL_PROVIDER);
        double expectedScale = 1 + 2 + 3 + 4 + 5 + 6 + 7 + 64;
        for (int i = 0; i < array.length; i++) {
            Assertions.assertEquals(i * expectedScale, array[i], 0.0, "index " + i);
        }
    }

    @Test
    public void slotLargerThanRequestUsesZeroedPrefix() {
        // prime a deep slot with a long array, then request a short one
        add(new Linear(1.0), add(new Linear(1.0), new Linear(1.0))).fillArray(new double[256], NULL_PROVIDER);
        double[] shortArray = new double[16];
        add(new Linear(3.0), new Linear(4.0)).fillArray(shortArray, NULL_PROVIDER);
        for (int i = 0; i < shortArray.length; i++) {
            Assertions.assertEquals(i * 7.0, shortArray[i], 0.0, "index " + i);
        }
    }

    @Test
    public void throwingArgumentUnwindsDepth() {
        DensityFunction thrower = new Linear(1.0) {
            @Override
            public void fillArray(double[] array, ContextProvider contextProvider) {
                array[0] = 42.0;
                throw new IllegalStateException("boom");
            }
        };
        double[] array = new double[32];
        Assertions.assertThrows(IllegalStateException.class, () -> add(new Linear(2.0), thrower).fillArray(array, NULL_PROVIDER));
        // depth must be restored: a follow-up nested fill still computes correctly
        DensityFunction nested = add(new Linear(3.0), add(new Linear(4.0), new Linear(5.0)));
        nested.fillArray(array, NULL_PROVIDER);
        for (int i = 0; i < array.length; i++) {
            Assertions.assertEquals(i * 12.0, array[i], 0.0, "index " + i);
        }
    }

    @Test
    public void zeroPrefixMatchesFreshArraySemantics() {
        // SparseFill leaves stride-1 indices unwritten: the temp must contribute 0.0
        // there exactly like a freshly allocated (zeroed) array would
        double[] array = new double[64];
        add(new Linear(10.0), new SparseFill(3.0, 4)).fillArray(array, NULL_PROVIDER);
        for (int i = 0; i < array.length; i++) {
            double expected = i % 4 == 0 ? i * 10.0 + i * 3.0 : i * 10.0;
            Assertions.assertEquals(expected, array[i], 0.0, "index " + i);
        }
    }
}
