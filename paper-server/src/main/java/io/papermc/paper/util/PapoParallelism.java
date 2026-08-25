package io.papermc.paper.util;

import ca.spottedleaf.concurrentutil.numa.OSNuma;

// Papo start - core-aware auto-sizing for server thread pools
/**
 * Central place for multi-core sizing decisions so individual pools stay coherent
 * (a single host budget instead of every pool inventing its own curve).
 *
 * Only defaults live here; every pool keeps its existing explicit configuration
 * override, and an explicit value always wins.
 */
public final class PapoParallelism {

    private static final int CORES = Math.max(1, OSNuma.getNativeInstance().getTotalCores());

    private PapoParallelism() {}

    public static int availableCores() {
        return CORES;
    }

    /**
     * Netty event loop count (spigot.yml {@code settings.netty-threads} default).
     * Legacy default was a flat 4 regardless of host size. Loops park on their
     * selector when idle, so the cost of scaling up on large hosts is negligible
     * while small hosts keep exactly the previous behaviour via the floor of 4.
     * Formula: cores/4, clamped to [4, 16].
     */
    public static int nettyEventLoopCount() {
        return clamp(CORES / 4, 4, 16);
    }

    /**
     * Region file IO thread count (paper-global {@code chunk-system.io-threads}
     * auto value). The chunk system's AreaDependentQueue already serialises all
     * access to a single region file, so extra threads only ever parallelise
     * distinct region files - never concurrent access to the same file.
     * Formula: cores/8, clamped to [1, 4].
     */
    public static int regionIoThreadCount() {
        return clamp(CORES / 8, 1, 4);
    }

    /**
     * Chunk system worker thread count (paper-global {@code chunk-system.worker-threads}
     * auto value, overridable by the existing -D flag). The historical moonrise default is
     * cores/4, sized to leave room for netty/IO/main on shared hosts. On dedicated hosts
     * the worker pool (parallel gen/load/light/compression/save) is the throughput
     * bottleneck during exploration bursts while cores idle; workers run at NORM priority
     * under the NORM+2 main tick thread, so scaling them up does not steal tick time.
     * Formula: cores/2, clamped to [2, 12].
     */
    public static int workerThreadCount() {
        return clamp(CORES / 2, 2, 12);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
// Papo end - core-aware auto-sizing for server thread pools
