package papo.bench;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * 批次78：核心感知 I/O 池自动 sizing（before=平 1 线程 auto，after=cores/8 clamp[1,4]）。
 *
 * 模型忠实复刻 MoonriseRegionFileIO 的派发结构：
 *   BalancedPrioritisedThreadPool（IO_QUEUE_HOLD_TIME=25ms）
 *   → OrderedStreamGroup.createExecutor()
 *   → AreaDependentQueue(executor, shift=5)
 *   → 每 region file 一个点区域 (chunkX>>5, chunkZ>>5, range 0)（MoonriseRegionFileIO:1446 同构）
 * 任务体 = 真实 4KiB 文件读（每任务独立扇区）+ 500µs 模拟设备延迟（parkNanos——
 * 冷读时 NVMe 队列延迟的量级；两配置每任务付出相同延迟，差异只来自池的串行/并行）。
 * 1 线程时任一 region file 的阻塞读会卡住其余全部 region file 的 IO——这正是单线程
 * auto 的结构性瓶颈；多线程时不同 region file 的阻塞互相重叠。
 *
 * 自检（安全性红线）：
 *   1. 任一 region file 内并发度恒为 1（AreaDependentQueue 串行化——多 IO 线程下同一
 *      region file 绝不并发访问，本优化安全性的核心论断）；
 *   2. 每 region file 内任务完成顺序 == 提交顺序（FIFO）；
 *   3. 全部任务恰好完成一次；
 *   4. 多线程 + 多 region file 时不同 region file 确实并发（并行度真实兑现）。
 *
 * 非 JMH（吞吐/墙钟 scaling 形态，ReobfScaling 先例），java 直接运行。
 */
public final class IoPoolScalingBench {

    private static final long IO_QUEUE_HOLD_TIME = 25_000_000L; // MoonriseCommon.IO_QUEUE_HOLD_TIME
    private static final int REGION_SHIFT = 5;                  // new AreaDependentQueue(ioExecutor, 5)

    private static final int REGION_FILES = 8;       // 同时在飞的 region file 数（多世界+玩家分散稳态下界）
    private static final int CHUNKS_PER_REGION = 16; // 每 region file 排队的 chunk IO 任务数
    private static final int READ_SIZE = 4096;       // 单任务读 4KiB（一个 sector）
    private static final long DEVICE_LATENCY_NANOS = 500_000L; // 500µs 模拟设备延迟
    private static final int FILE_SIZE = REGION_FILES * CHUNKS_PER_REGION * READ_SIZE;

    public static void main(final String[] args) throws Exception {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int after = clamp(cores / 8, 1, 4); // PapoParallelism.regionIoThreadCount()
        System.out.println("cores=" + cores + "  regionIoThreadCount(auto)=" + after);
        System.out.println("model: " + REGION_FILES + " region files x " + CHUNKS_PER_REGION
            + " chunk tasks, " + READ_SIZE + "B real file read each (distinct sectors)");
        System.out.println();

        System.out.println("-- sizing curve (cores -> nettyLoops, regionIoThreads) --");
        for (int c = 1; c <= 256; c <<= 1) {
            System.out.printf("  %3d cores -> netty=%2d regionIo=%d%n", c, clamp(c / 4, 4, 16), clamp(c / 8, 1, 4));
        }
        System.out.println();

        final Path tmp = Files.createTempFile("papo-iopool-", ".bin");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                final ByteBuffer pattern = ByteBuffer.allocateDirect(FILE_SIZE);
                for (int i = 0; i < FILE_SIZE; i += 4) pattern.putInt(i, 0x5A5A0000 + i);
                while (pattern.hasRemaining()) ch.write(pattern, pattern.position());

                System.out.println("-- self-checks (threads=1 and threads=" + after + ") --");
                runOnce(ch, 1, true);
                runOnce(ch, after, true);
                System.out.println("  ALL OK (per-region concurrency<=1, per-region FIFO, exact-once completion)");
                System.out.println();

                System.out.println("-- scaling (3 reps each, best/mean wall ms for "
                    + (REGION_FILES * CHUNKS_PER_REGION) + " tasks) --");
                System.out.printf("%-9s %-10s %-10s%n", "threads", "best(ms)", "mean(ms)");
                long beforeBest = Long.MAX_VALUE, afterBest = Long.MAX_VALUE;
                for (final int t : new int[]{1, after}) {
                    long best = Long.MAX_VALUE, sum = 0;
                    for (int i = 0; i < 3; i++) {
                        final long ms = runOnce(ch, t, false);
                        best = Math.min(best, ms);
                        sum += ms;
                    }
                    if (t == 1) beforeBest = best; else afterBest = best;
                    System.out.printf("%-9d %-10d %-10d%n", t, best, sum / 3);
                }
                System.out.println();
                System.out.printf("before(1 thread) best=%dms  after(auto=%d) best=%dms  speedup=%.2fx%n",
                    beforeBest, after, afterBest, (double) beforeBest / afterBest);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 跑一轮：建池 → 排队全部任务 → 等完成 → 收池。返回墙钟 ms；assertOn 时执行全部自检断言。 */
    private static long runOnce(final FileChannel ch, final int threads, final boolean assertOn) throws Exception {
        final BalancedPrioritisedThreadPool pool = new BalancedPrioritisedThreadPool(IO_QUEUE_HOLD_TIME, thread -> {
            thread.setDaemon(true);
            thread.setName("bench-io-" + thread.getId());
        });
        pool.adjustThreadCount(threads);
        final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue ioExecutor =
            pool.createOrderedStreamGroup().createExecutor();
        final AreaDependentQueue queue = new AreaDependentQueue(ioExecutor, REGION_SHIFT);

        final int total = REGION_FILES * CHUNKS_PER_REGION;
        final CountDownLatch done = new CountDownLatch(total);
        final Object lock = new Object();
        final int[] activePerRegion = new int[REGION_FILES];   // 自检1
        final int[] nextCompletion = new int[REGION_FILES];    // 自检2
        final int[] completedPerRegion = new int[REGION_FILES]; // 自检3
        final AtomicInteger activeTotal = new AtomicInteger();
        final AtomicInteger maxGlobalActive = new AtomicInteger();

        final long start = System.nanoTime();
        for (int seq = 0; seq < CHUNKS_PER_REGION; seq++) {
            for (int r = 0; r < REGION_FILES; r++) {
                final int region = r;
                final int mySeq = seq;
                queue.queueTask(region, 0, 0, () -> {
                    synchronized (lock) { activePerRegion[region]++; }
                    maxGlobalActive.accumulateAndGet(activeTotal.incrementAndGet(), Math::max);
                    if (assertOn && activePerRegion[region] > 1) {
                        throw new AssertionError("region " + region + " 并发度 " + activePerRegion[region] + " > 1");
                    }
                    try {
                        final ByteBuffer buf = ByteBuffer.allocateDirect(READ_SIZE);
                        ch.read(buf, (long) (region * CHUNKS_PER_REGION + mySeq) * READ_SIZE);
                        buf.position(0);
                        int acc = 0;
                        while (buf.hasRemaining()) acc = 31 * acc + buf.get();
                        if (acc == 0xDEADBEEF) System.out.print(""); // 防死码消除
                        LockSupport.parkNanos(DEVICE_LATENCY_NANOS);
                    } catch (final IOException e) {
                        throw new RuntimeException(e);
                    }
                    synchronized (lock) {
                        activePerRegion[region]--;
                        activeTotal.decrementAndGet();
                        if (assertOn) {
                            if (nextCompletion[region] != mySeq) {
                                throw new AssertionError("region " + region + " FIFO 破坏: 期望 "
                                    + nextCompletion[region] + " 实得 " + mySeq);
                            }
                            nextCompletion[region]++;
                        }
                        completedPerRegion[region]++;
                    }
                    done.countDown();
                });
            }
        }
        done.await();
        final long wallMs = (System.nanoTime() - start) / 1_000_000L;

        if (assertOn) {
            for (int r = 0; r < REGION_FILES; r++) {
                if (completedPerRegion[r] != CHUNKS_PER_REGION) {
                    throw new AssertionError("region " + r + " 完成 " + completedPerRegion[r] + " != " + CHUNKS_PER_REGION);
                }
            }
        }
        System.out.printf("  [threads=%d] maxConcurrentTasks=%d%n", threads, maxGlobalActive.get());
        // 自检4：多线程 + 8 个 region file，若全局并发度恒 1 则并行未兑现
        if (assertOn && threads > 1 && maxGlobalActive.get() <= 1) {
            throw new AssertionError("threads=" + threads + " 下全局并发度恒 1 —— 并行未兑现");
        }
        pool.shutdown(false);
        if (!pool.join(10_000L)) {
            pool.halt(false);
            throw new AssertionError("池未在 10s 内收束");
        }
        return wallMs;
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private IoPoolScalingBench() {}
}
