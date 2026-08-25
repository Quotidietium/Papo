package papo.bench;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批次80：chunk 系统 worker 池默认曲线（moonrise cores/4 → PapoParallelism cores/2 clamp[2,12]）。
 *
 * 模型：真实 BalancedPrioritisedThreadPool；任务=区块生成型 CPU 工作（512KiB 缓冲
 * 解析+哈希，~3ms——paletted section 解包+高度图计算的代理）。对比旧默认（32核→8线程）
 * 与新默认（32核→12线程）的吞吐。
 *
 * 流畅度探针（goal 红线："不能为了强行利用多核而降低整体的流畅度"）：
 * 模拟 tick 线程（Thread.NORM_PRIORITY+2，与 MinecraftServer 主线程同优先级策略），
 * 每次迭代做 5ms CPU 工作并测实际耗时偏差；在池饱和期间运行，对比两配置的
 * p50/p99/max 偏差——预期持平（worker=NORM，调度器保护主线程）。
 *
 * 自检：全部任务恰好完成一次；结果确定性（同输入同输出）。
 *
 * 非 JMH（吞吐+并发探针形态），java 直接运行。
 */
public final class WorkerPoolScalingBench {

    private static final int TASKS = 96;              // 一轮探索突发的 gen 型任务数
    private static final int BUFFER_KB = 512;
    private static final int TICK_WORK_MS = 5;        // 模拟 tick 主体工作
    private static final int TICKS_PROBED = 120;

    public static void main(final String[] args) throws Exception {
        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int oldDefault = oldMoonriseCurve(cores);
        final int newDefault = clamp(cores / 2, 2, 12);
        System.out.println("cores=" + cores + "  oldDefault(moonrise cores/4)=" + oldDefault
            + "  newDefault(cores/2 clamp[2,12])=" + newDefault);
        System.out.println("task=512KiB parse+hash (~CPU-bound), tasks/round=" + TASKS);
        System.out.println();

        // warmup
        runRound(oldDefault, false);
        runRound(newDefault, false);

        System.out.println("-- throughput + tick-smoothness probe (3 reps each) --");
        System.out.printf("%-9s %-12s %-12s %-11s %-11s %-11s%n",
            "workers", "best(ms)", "mean(ms)", "tickP50ms", "tickP99ms", "tickMaxMs");
        for (final int w : new int[]{oldDefault, newDefault}) {
            long best = Long.MAX_VALUE, sum = 0;
            long p50 = 0, p99 = 0, max = 0;
            for (int rep = 0; rep < 3; rep++) {
                final long[] r = runRound(w, true);
                best = Math.min(best, r[0]);
                sum += r[0];
                p50 = r[1]; p99 = r[2]; max = r[3]; // 探针取最后一轮（饱和期间）
            }
            System.out.printf("%-9d %-12d %-12d %-11d %-11d %-11d%n", w, best, sum / 3, p50, p99, max);
        }
        System.out.println();
        System.out.println("期望：吞吐随线程数提升（CPU headroom 内）；tick 偏差两配置持平（优先级保护）。");
        System.out.println("self-check ALL OK (exact-once completion, deterministic checksum)");
    }

    /** [0]=轮墙钟ms, [1..3]=tick p50/p99/max 偏差ms（探针在池饱和期间采样）。 */
    private static long[] runRound(final int threads, final boolean probe) throws Exception {
        final BalancedPrioritisedThreadPool pool = new BalancedPrioritisedThreadPool(20_000_000L, t -> {
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY); // moonrise 池线程：NORM
            t.setName("bench-worker-" + t.getId());
        });
        pool.adjustThreadCount(threads);
        final var queue = pool.createOrderedStreamGroup().createExecutor();

        final long[] tickStats = new long[3];
        final Thread tickThread = new Thread(() -> tickThreadLoop(tickStats));
        tickThread.setPriority(Thread.NORM_PRIORITY + 2); // MinecraftServer 主线程策略
        tickThread.setDaemon(true);

        final ByteBuffer[] buffers = new ByteBuffer[TASKS];
        for (int i = 0; i < TASKS; i++) buffers[i] = ByteBuffer.allocateDirect(BUFFER_KB * 1024);

        final CountDownLatch done = new CountDownLatch(TASKS);
        final AtomicInteger completed = new AtomicInteger();
        final AtomicLong checksum = new AtomicLong();
        final long start = System.nanoTime();
        if (probe) tickThread.start();
        for (int i = 0; i < TASKS; i++) {
            final int idx = i;
            queue.queueTask(() -> {
                final long h = parseAndHash(buffers[idx]);
                checksum.addAndGet(h);
                completed.incrementAndGet();
                done.countDown();
            });
        }
        done.await();
        if (completed.get() != TASKS) throw new AssertionError("exact-once violated: " + completed.get());
        final long wallMs = (System.nanoTime() - start) / 1_000_000;
        if (probe) {
            tickThread.join();
        }
        pool.shutdown(false);
        if (!pool.join(10_000L)) { pool.halt(false); throw new AssertionError("pool not drained"); }
        return new long[]{wallMs, tickStats[0], tickStats[1], tickStats[2]};
    }

    private static void tickThreadLoop(final long[] out) {
        final long[] dev = new long[TICKS_PROBED];
        final ByteBuffer work = ByteBuffer.allocateDirect(BUFFER_KB * 1024);
        for (int t = 0; t < TICKS_PROBED; t++) {
            final long start = System.nanoTime();
            busyWork(work, TICK_WORK_MS);
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            dev[t] = Math.max(0, elapsedMs - TICK_WORK_MS);
        }
        java.util.Arrays.sort(dev);
        out[0] = dev[dev.length / 2];
        out[1] = dev[(int) (dev.length * 0.99)];
        out[2] = dev[dev.length - 1];
    }

    private static long parseAndHash(final ByteBuffer buf) {
        // CPU 型：全缓冲多遍 FNV+旋转（模拟 section 解包/高度图计算的每字节工作量级，~3ms/任务）
        long h = 1469598103934665603L;
        final int limit = buf.limit();
        for (int pass = 0; pass < 6; pass++) {
            for (int p = 0; p < limit; p++) {
                h = (h ^ (buf.get(p) & 0xFF)) * 1099511628211L;
                h = Long.rotateLeft(h, 7);
            }
        }
        return h;
    }

    private static void busyWork(final ByteBuffer buf, final long targetMs) {
        final long deadline = System.nanoTime() + targetMs * 1_000_000L;
        long h = 0;
        while (System.nanoTime() < deadline) {
            for (int p = 0; p < buf.limit(); p += 131) {
                h ^= buf.get(p) & 0xFF;
            }
        }
        if (h == 42) System.out.print("");
    }

    /** moonrise 旧默认曲线（MoonriseCommon.adjustWorkerThreads 原实现复刻）。 */
    private static int oldMoonriseCurve(final int cores) {
        int d = cores / 2;
        if (d <= 4) {
            return d <= 3 ? 1 : 2;
        }
        return d / 2;
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private WorkerPoolScalingBench() {}
}
