package ca.spottedleaf.moonrise.common.util;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class MoonriseCommon {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    public static final long WORKER_QUEUE_HOLD_TIME = (long)(20.0e6); // 20ms
    public static final BalancedPrioritisedThreadPool WORKER_POOL = new BalancedPrioritisedThreadPool(
        WORKER_QUEUE_HOLD_TIME,
            new Consumer<>() {
                private final AtomicInteger idGenerator = new AtomicInteger();

                @Override
                public void accept(Thread thread) {
                    thread.setDaemon(true);
                    thread.setName(PlatformHooks.get().getBrand() + " Common Worker #" + this.idGenerator.getAndIncrement());
                    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(final Thread thread, final Throwable throwable) {
                            LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
                        }
                    });
                }
            }
    );
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup CLIENT_GROUP = MoonriseCommon.WORKER_POOL.createOrderedStreamGroup();
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup SERVER_GROUP = MoonriseCommon.WORKER_POOL.createOrderedStreamGroup();

    public static void adjustWorkerThreads(final int configWorkerThreads, final int configIoThreads) {
        // Papo start - core-aware auto worker thread count (batch 80)
        // Historical moonrise curve is cores/4 (cores/2 halved again) to leave room for
        // netty/IO/main on shared hosts. PapoParallelism.workerThreadCount raises the
        // dedicated-host default to cores/2 clamp [2,12]; workers stay at NORM priority
        // under the NORM+2 tick thread, so tick smoothness is not traded for throughput.
        // Explicit config and the -D WorkerThreadCount override keep winning.
        int defaultWorkerThreads = io.papermc.paper.util.PapoParallelism.workerThreadCount();
        // Papo end - core-aware auto worker thread count (batch 80)
        defaultWorkerThreads = Integer.getInteger(PlatformHooks.get().getBrand() + ".WorkerThreadCount", Integer.valueOf(defaultWorkerThreads));

        int workerThreads = configWorkerThreads;

        if (workerThreads <= 0) {
            workerThreads = defaultWorkerThreads;
        }

        // Papo start - core-aware auto I/O thread count
        // configIoThreads <= 0 is the "auto" sentinel (the materialised paper-global
        // default is -1). The historical auto value was a flat 1 regardless of host
        // size, serialising every world's region files through one thread. The chunk
        // system's AreaDependentQueue already serialises per-region-file access, so
        // extra threads only parallelise distinct region files. Explicit positive
        // config values still win.
        final int ioThreads = configIoThreads > 0 ? configIoThreads
            : io.papermc.paper.util.PapoParallelism.regionIoThreadCount();
        // Papo end - core-aware auto I/O thread count

        WORKER_POOL.adjustThreadCount(workerThreads);
        IO_POOL.adjustThreadCount(ioThreads);

        LOGGER.info(PlatformHooks.get().getBrand() + " is using " + workerThreads + " worker threads, " + ioThreads + " I/O threads");
    }

    public static final long IO_QUEUE_HOLD_TIME = (long)(25.0e6); // 25ms
    public static final BalancedPrioritisedThreadPool IO_POOL = new BalancedPrioritisedThreadPool(
        IO_QUEUE_HOLD_TIME,
            new Consumer<>() {
                private final AtomicInteger idGenerator = new AtomicInteger();

                @Override
                public void accept(final Thread thread) {
                    thread.setDaemon(true);
                    thread.setName(PlatformHooks.get().getBrand() + " I/O Worker #" + this.idGenerator.getAndIncrement());
                    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(final Thread thread, final Throwable throwable) {
                            LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
                        }
                    });
                }
            }
    );
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup CLIENT_IO_GROUP = IO_POOL.createOrderedStreamGroup();
    public static final BalancedPrioritisedThreadPool.OrderedStreamGroup SERVER_IO_GROUP = IO_POOL.createOrderedStreamGroup();

    public static void haltExecutors() {
        MoonriseCommon.WORKER_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of worker pool for up to 60s...");
        if (!MoonriseCommon.WORKER_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("Worker pool did not shut down in time!");
            MoonriseCommon.WORKER_POOL.halt(false);
        }

        MoonriseCommon.IO_POOL.shutdown(false);
        LOGGER.info("Awaiting termination of I/O pool for up to 60s...");
        if (!MoonriseCommon.IO_POOL.join(TimeUnit.SECONDS.toMillis(60L))) {
            LOGGER.error("I/O pool did not shut down in time!");
            MoonriseCommon.IO_POOL.halt(false);
        }
    }

    private MoonriseCommon() {}
}
