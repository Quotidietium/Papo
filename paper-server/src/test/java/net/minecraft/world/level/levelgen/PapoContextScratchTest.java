package net.minecraft.world.level.levelgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import net.minecraft.world.level.levelgen.DensityFunction.FunctionContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 178 (R37): Climate.Sampler.sample and WorldCarver.getCarveState
// stage their per-position SinglePointContext in PapoContextScratch (thread-local
// mutable FunctionContext) instead of a fresh record per call. Asserted here: the
// staged coordinates are exact (the six climate computes and aquifer.computeSubstance
// read blockX/blockY/blockZ only), a later set() fully overwrites an earlier one (no
// cross-call leakage), and holders are per-thread (concurrent samplers on different
// generation workers cannot see each other's coordinates). The call sites are a
// one-line substitution whose consumers are coordinate-read-only and synchronous,
// so helper correctness plus the census pair leg covers the wiring.
public class PapoContextScratchTest {

    @Test
    public void coordinatesExact() {
        int[][] cases = {
            {0, 0, 0}, {-5, 1, -5}, {1_000_000, -64, 1_000_000},
            {Integer.MIN_VALUE, Integer.MAX_VALUE, -1}, {0x3FFFFFFF, 0, 0x3FFFFFFF}
        };
        for (int[] expected : cases) {
            FunctionContext context = PapoContextScratch.singlePoint(expected[0], expected[1], expected[2]);
            Assertions.assertEquals(expected[0], context.blockX(), "blockX");
            Assertions.assertEquals(expected[1], context.blockY(), "blockY");
            Assertions.assertEquals(expected[2], context.blockZ(), "blockZ");
        }
    }

    @Test
    public void sequentialReuseOverwrites() {
        // interleave stress: a later singlePoint must never observe any coordinate
        // of an earlier one (the scratch is one per thread, climate sampling and
        // carving share it)
        java.util.Random random = new java.util.Random(178L);
        for (int i = 0; i < 200; i++) {
            int x0 = random.nextInt(), y0 = random.nextInt(), z0 = random.nextInt();
            int x1 = random.nextInt(), y1 = random.nextInt(), z1 = random.nextInt();
            PapoContextScratch.singlePoint(x0, y0, z0);
            FunctionContext context = PapoContextScratch.singlePoint(x1, y1, z1);
            Assertions.assertEquals(x1, context.blockX(), "iter " + i + " x");
            Assertions.assertEquals(y1, context.blockY(), "iter " + i + " y");
            Assertions.assertEquals(z1, context.blockZ(), "iter " + i + " z");
        }
    }

    @Test
    public void holdersArePerThread() throws InterruptedException {
        CountDownLatch bothSet = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> failures = Collections.synchronizedList(new ArrayList<>());

        Thread thread1 = new Thread(() -> stageAndVerify(111, 222, 333, bothSet, release, failures));
        Thread thread2 = new Thread(() -> stageAndVerify(-7, -8, -9, bothSet, release, failures));
        thread1.start();
        thread2.start();

        // main thread stages its own coordinates and must still read them after the
        // other two threads staged theirs
        FunctionContext mainContext = PapoContextScratch.singlePoint(42, 43, 44);
        bothSet.await();
        Assertions.assertEquals(42, mainContext.blockX(), "main x");
        Assertions.assertEquals(43, mainContext.blockY(), "main y");
        Assertions.assertEquals(44, mainContext.blockZ(), "main z");
        release.countDown();
        thread1.join(10_000L);
        thread2.join(10_000L);
        Assertions.assertTrue(failures.isEmpty(), "worker threads observed foreign coordinates " + failures.size() + " times");
    }

    @Test
    public void blenderDefaultInherited() {
        // FunctionContext.getBlender() has a default; the holder must inherit it
        // (not implement a divergent one)
        Assertions.assertNotNull(PapoContextScratch.singlePoint(0, 0, 0).getBlender());
    }

    private static void stageAndVerify(int x, int y, int z, CountDownLatch staged, CountDownLatch release, List<Integer> failures) {
        FunctionContext context = PapoContextScratch.singlePoint(x, y, z);
        staged.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            failures.add(-1);
            return;
        }
        if (context.blockX() != x || context.blockY() != y || context.blockZ() != z) {
            failures.add(1);
        }
    }
}
