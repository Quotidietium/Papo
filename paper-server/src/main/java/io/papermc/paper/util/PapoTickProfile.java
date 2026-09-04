package io.papermc.paper.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Papo start - batch 90: opt-in per-phase main-thread tick profiling
/**
 * Aggregates wall-time of the main tick phases (tickChildren sections + per-world ServerLevel
 * sections). Enabled only via {@code -Dpapo.tickProfile=1}; disabled it costs one static
 * final boolean check per phase boundary and never touches the maps. Prints a sorted report
 * (total ms / per-tick avg / share of measured total) every 400 ticks and resets - intended
 * for diagnostic survey runs (see batch 90 report), not for production overhead.
 */
public final class PapoTickProfile {

    // 注意 Boolean.getBoolean 只认 "true"；此处同时接受 "1"，避免 -Dpapo.tickProfile=1 静默失效
    private static final String PROP = System.getProperty("papo.tickProfile");
    public static final boolean ENABLED = "true".equalsIgnoreCase(PROP) || "1".equals(PROP);
    private static final int REPORT_EVERY_TICKS = 400;

    // [0]=totalNanos [1]=samples
    private static final ConcurrentHashMap<String, long[]> TOTALS = new ConcurrentHashMap<>();
    private static long windowStart = System.nanoTime();

    private PapoTickProfile() {}

    /** Adds a phase sample; {@code startNs} from {@code System.nanoTime()} before the phase. */
    public static void add(final String phase, final long startNs) {
        if (!ENABLED) {
            return;
        }
        final long[] arr = TOTALS.computeIfAbsent(phase, k -> new long[2]);
        synchronized (arr) {
            arr[0] += System.nanoTime() - startNs;
            arr[1]++;
        }
    }

    // Papo start - batch 123 (R3 batch 112 port): activity counters for tick sub-phases
    // Counts occurrences (not wall time) of per-block events, reported as "count <key>" rows
    // so load presence (e.g. redstone oscillation) can be gated independently of timing.
    private static final ConcurrentHashMap<String, long[]> COUNTS = new ConcurrentHashMap<>();

    /** Adds {@code delta} to a named counter (typ. 1 per event). */
    public static void addCount(final String key, final long delta) {
        if (!ENABLED) {
            return;
        }
        final long[] arr = COUNTS.computeIfAbsent(key, k -> new long[1]);
        synchronized (arr) {
            arr[0] += delta;
        }
    }
    // Papo end - batch 123

    /** Prints + resets the window every {@link #REPORT_EVERY_TICKS} ticks (call from tickServer). */
    public static void maybeReport(final int tickCount) {
        if (!ENABLED || tickCount % REPORT_EVERY_TICKS != 0) {
            return;
        }
        final long windowNanos = System.nanoTime() - windowStart;
        windowStart = System.nanoTime();
        final List<Map.Entry<String, long[]>> rows = new ArrayList<>(TOTALS.entrySet());
        rows.sort(Comparator.comparingLong(e -> -e.getValue()[0]));
        final long measured = rows.stream().mapToLong(e -> e.getValue()[0]).sum();
        System.out.println("PapoTickProfile window=400ticks totalWallMs=" + windowNanos / 1_000_000
            + " measuredMs=" + measured / 1_000_000);
        // 逐行 println（多行单次 println 的续行会被日志系统吞掉——实测判例）
        for (final Map.Entry<String, long[]> e : rows) {
            final long[] v = e.getValue();
            System.out.println(String.format("PapoTickProfile.phase %-26s total=%7.1fms avg/tick=%7.1fus share=%5.1f%% n=%d",
                e.getKey(), v[0] / 1_000_000.0, v[0] / 1_000.0 / REPORT_EVERY_TICKS,
                100.0 * v[0] / Math.max(1, measured), v[1]));
        }
        // Papo start - batch 123: activity counter rows (avg/tick of counted events)
        if (!COUNTS.isEmpty()) {
            final List<Map.Entry<String, long[]>> counts = new ArrayList<>(COUNTS.entrySet());
            counts.sort(Map.Entry.comparingByKey());
            for (final Map.Entry<String, long[]> e : counts) {
                System.out.println(String.format("PapoTickProfile.count %-26s total=%7d avg/tick=%7.1f",
                    e.getKey(), e.getValue()[0], e.getValue()[0] / (double) REPORT_EVERY_TICKS));
            }
            COUNTS.clear();
        }
        // Papo end - batch 123
        TOTALS.clear();
    }
}
// Papo end - batch 90: opt-in per-phase main-thread tick profiling
