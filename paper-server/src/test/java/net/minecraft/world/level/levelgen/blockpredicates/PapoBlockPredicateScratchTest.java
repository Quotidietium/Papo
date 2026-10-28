package net.minecraft.world.level.levelgen.blockpredicates;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 177 (R36): StateTestingPredicate/HasSturdyFacePredicate stage
// their query offset in PapoPredicateScratch (thread-local mutable) instead of
// allocating pos.offset(this.offset) per test. The helper is asserted here at the
// BlockGetter level: the queried coordinates must be exactly input+offset - including
// interleave stress proving the scratch never leaks a previous query's coordinates
// into a later one. The predicate call sites are a one-line composition of this
// helper with getBlockState/isFaceSturdy (both coordinate-read-only, vanilla feature
// loops pass their own mutables into the same queries), so helper correctness plus
// the census pair leg covers the wiring. The fake getter is a Proxy routing only
// getBlockState - robust to Paper/Moonrise additions on the interface surface.
public class PapoBlockPredicateScratchTest {

    private static final class RecordingGetter {
        final List<long[]> queried = new ArrayList<>();
        final int expectedX;
        final int expectedY;
        final int expectedZ;

        RecordingGetter(final int expectedX, final int expectedY, final int expectedZ) {
            this.expectedX = expectedX;
            this.expectedY = expectedY;
            this.expectedZ = expectedZ;
        }

        BlockGetter asGetter() {
            return (BlockGetter)java.lang.reflect.Proxy.newProxyInstance(
                BlockGetter.class.getClassLoader(),
                new Class<?>[] {BlockGetter.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBlockState" -> {
                            BlockPos pos = (BlockPos)args[0];
                            // copy coordinates immediately: pos may be the shared
                            // scratch, mutated by the next query
                            RecordingGetter.this.queried.add(new long[] {pos.getX(), pos.getY(), pos.getZ()});
                            if (pos.getX() == RecordingGetter.this.expectedX
                                && pos.getY() == RecordingGetter.this.expectedY
                                && pos.getZ() == RecordingGetter.this.expectedZ) {
                                return Blocks.STONE.defaultBlockState();
                            }
                            return Blocks.AIR.defaultBlockState();
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        case "toString" -> {
                            return "RecordingGetter";
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                });
        }
    }

    private static void assertQueried(final RecordingGetter getter, final int x, final int y, final int z) {
        Assertions.assertEquals(1, getter.queried.size(), "exactly one query expected");
        Assertions.assertEquals(x, getter.queried.get(0)[0]);
        Assertions.assertEquals(y, getter.queried.get(0)[1]);
        Assertions.assertEquals(z, getter.queried.get(0)[2]);
    }

    @Test
    void offsetBlockStateQueriesExactOffsetCoordinates() {
        final RecordingGetter stoneAt = new RecordingGetter(11, 66, -2);
        Assertions.assertSame(
            Blocks.STONE.defaultBlockState(), PapoPredicateScratch.offsetBlockState(stoneAt.asGetter(), new BlockPos(10, 64, -5), new Vec3i(1, 2, 3)));
        assertQueried(stoneAt, 11, 66, -2);

        final RecordingGetter miss = new RecordingGetter(0, 0, 0);
        Assertions.assertSame(
            Blocks.AIR.defaultBlockState(), PapoPredicateScratch.offsetBlockState(miss.asGetter(), new BlockPos(10, 64, -5), new Vec3i(1, 2, 3)));
        assertQueried(miss, 11, 66, -2);

        // Vec3i.ZERO constant and a freshly-constructed zero offset both land on the
        // input coordinates
        final RecordingGetter zero = new RecordingGetter(10, 64, -5);
        PapoPredicateScratch.offsetBlockState(zero.asGetter(), new BlockPos(10, 64, -5), Vec3i.ZERO);
        assertQueried(zero, 10, 64, -5);

        final RecordingGetter freshZero = new RecordingGetter(-7, 1, 200);
        PapoPredicateScratch.offsetBlockState(freshZero.asGetter(), new BlockPos(-7, 1, 200), new Vec3i(0, 0, 0));
        assertQueried(freshZero, -7, 1, 200);

        // negative components
        final RecordingGetter negative = new RecordingGetter(6, 64, 12);
        PapoPredicateScratch.offsetBlockState(negative.asGetter(), new BlockPos(10, 64, 5), new Vec3i(-4, 0, 7));
        assertQueried(negative, 6, 64, 12);
    }

    @Test
    void interleavedQueriesNeverLeakScratchCoordinates() {
        final BlockPos posA = new BlockPos(100, 50, -100);
        final Vec3i offsetA = new Vec3i(3, -2, 5);
        final BlockPos posB = new BlockPos(-900, 7, 900);
        final Vec3i offsetB = new Vec3i(-11, 40, -13);

        for (int i = 0; i < 100; i++) {
            final RecordingGetter getterA = new RecordingGetter(103, 48, -95);
            PapoPredicateScratch.offsetBlockState(getterA.asGetter(), posA, offsetA);
            assertQueried(getterA, 103, 48, -95);

            final RecordingGetter getterB = new RecordingGetter(-911, 47, 887);
            PapoPredicateScratch.offsetBlockState(getterB.asGetter(), posB, offsetB);
            assertQueried(getterB, -911, 47, 887);
        }
    }
}
