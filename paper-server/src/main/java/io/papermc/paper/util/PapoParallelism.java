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

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
// Papo end - core-aware auto-sizing for server thread pools
