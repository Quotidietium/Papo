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
        addNanos(phase, System.nanoTime() - startNs);
    }

    // Papo start - batch 98: pre-accumulated segment variant (loop-level accumulation, one add per window segment)
    /** Adds an already-measured duration in nanos (for loop-accumulated segments). */
    public static void addNanos(final String phase, final long nanos) {
        if (!ENABLED) {
            return;
        }
        final long[] arr = TOTALS.computeIfAbsent(phase, k -> new long[2]);
        synchronized (arr) {
            arr[0] += nanos;
            arr[1]++;
        }
    }
    // Papo end - batch 98

    // Papo start - batch 98: per-window GC time attribution (collection ms delta across all GC beans)
    private static final java.util.List<java.lang.management.GarbageCollectorMXBean> PAPO_GC_BEANS =
        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
    private static long lastGcMs = sumGcMs();

    private static long sumGcMs() {
        long sum = 0;
        for (final java.lang.management.GarbageCollectorMXBean bean : PAPO_GC_BEANS) {
            sum += bean.getCollectionTime();
        }
        return sum;
    }
    // Papo end - batch 98

    // Papo start - batch 107: raw event counters (same [total, samples] shape as TOTALS; semantics = events)
    private static final ConcurrentHashMap<String, long[]> COUNTERS = new ConcurrentHashMap<>();

    /** Adds an event count (e.g. packets by type); printed in a separate .count section per window. */
    public static void addCount(final String name, final long count) {
        if (!ENABLED) {
            return;
        }
        final long[] arr = COUNTERS.computeIfAbsent(name, k -> new long[2]);
        synchronized (arr) {
            arr[0] += count;
            arr[1]++;
        }
    }
    // Papo end - batch 107

    // Papo start - batch 113: per-tick duration + inter-tick gap histogram (TPS stability axis)
    // 400-tick mean windows hide spikes: a single 500ms stall shifts the window average by
    // ~1.25% while TPS observably crashes. This section records every tick's wall duration
    // and the gap since the previous tick's END (sleep included), printing p50/p95/p99/max
    // percentiles + over-45/50ms overrun counts per window. Gap > nominal 50ms exposes
    // multi-tick stalls (GC pause, OS scheduling, lock contention).
    private static final Object TICK_LOCK = new Object();
    private static final java.util.ArrayList<long[]> TICK_STATS = new java.util.ArrayList<>(512);
    private static long lastTickEndNanos;
    // stall events: [tickCount, gapNanos, durationNanos, wallEpochMs] for gaps >= 100ms —
    // exact wall-clock correlation with server log lines (join/autosave/worldgen bursts)
    private static final java.util.ArrayList<long[]> STALL_EVENTS = new java.util.ArrayList<>(8);
    private static int stallTickCount;

    /** Records one tick's wall duration (tickServer tallying site; gated, zero-cost off). */
    public static void recordTickTime(final long durationNanos) {
        if (!ENABLED) {
            return;
        }
        final long now = System.nanoTime();
        synchronized (TICK_LOCK) {
            final long gap = lastTickEndNanos == 0 ? 0 : now - lastTickEndNanos;
            lastTickEndNanos = now;
            TICK_STATS.add(new long[]{durationNanos, gap});
            if (gap >= 100_000_000L) {
                STALL_EVENTS.add(new long[]{stallTickCount, gap, durationNanos, System.currentTimeMillis()});
            }
            stallTickCount++;
        }
    }

    private static long pct(final long[] sorted, final double q) {
        return sorted[(int) Math.min(sorted.length - 1L, (long) (q * sorted.length))];
    }
    // Papo end - batch 113

    /** Prints + resets the window every {@link #REPORT_EVERY_TICKS} ticks (call from tickServer). */
    public static void maybeReport(final int tickCount) {
        if (!ENABLED || tickCount % REPORT_EVERY_TICKS != 0) {
            return;
        }
        final long windowNanos = System.nanoTime() - windowStart;
        windowStart = System.nanoTime();
        // Papo start - batch 98: GC ms delta for this window (attribution of superlinear phases to GC pressure)
        final long gcNow = sumGcMs();
        final long gcDeltaMs = gcNow - lastGcMs;
        lastGcMs = gcNow;
        // Papo end - batch 98
        // Papo start - batch 107: raw counters (events, not nanos) — packet-type decomposition probes
        final List<Map.Entry<String, long[]>> countRows = new ArrayList<>(COUNTERS.entrySet());
        COUNTERS.clear();
        // Papo end - batch 107
        final List<Map.Entry<String, long[]>> rows = new ArrayList<>(TOTALS.entrySet());
        rows.sort(Comparator.comparingLong(e -> -e.getValue()[0]));
        final long measured = rows.stream().mapToLong(e -> e.getValue()[0]).sum();
        System.out.println("PapoTickProfile window=400ticks totalWallMs=" + windowNanos / 1_000_000
            + " measuredMs=" + measured / 1_000_000 + " gcMs=" + gcDeltaMs);
        // 逐行 println（多行单次 println 的续行会被日志系统吞掉——实测判例）
        for (final Map.Entry<String, long[]> e : rows) {
            final long[] v = e.getValue();
            System.out.println(String.format("PapoTickProfile.phase %-26s total=%7.1fms avg/tick=%7.1fus share=%5.1f%% n=%d",
                e.getKey(), v[0] / 1_000_000.0, v[0] / 1_000.0 / REPORT_EVERY_TICKS,
                100.0 * v[0] / Math.max(1, measured), v[1]));
        }
        // Papo start - batch 107: print raw counters sorted descending (total = events)
        if (!countRows.isEmpty()) {
            countRows.sort(Comparator.comparingLong(e -> -e.getValue()[0]));
            for (final Map.Entry<String, long[]> e : countRows) {
                final long[] v = e.getValue();
                System.out.println(String.format("PapoTickProfile.count %-26s total=%9d avg/tick=%9.1f n=%d",
                    e.getKey(), v[0], v[0] / (double) REPORT_EVERY_TICKS, v[1]));
            }
        }
        // Papo end - batch 107
        // Papo start - batch 113: per-tick duration/gap histogram section
        final List<long[]> ticksCopy;
        final List<long[]> stallCopy;
        synchronized (TICK_LOCK) {
            ticksCopy = new ArrayList<>(TICK_STATS);
            TICK_STATS.clear();
            stallCopy = new ArrayList<>(STALL_EVENTS);
            STALL_EVENTS.clear();
        }
        if (!ticksCopy.isEmpty()) {
            final long[] durs = new long[ticksCopy.size()];
            final long[] gaps = new long[ticksCopy.size()];
            for (int i = 0; i < ticksCopy.size(); i++) {
                durs[i] = ticksCopy.get(i)[0];
                gaps[i] = ticksCopy.get(i)[1];
            }
            java.util.Arrays.sort(durs);
            java.util.Arrays.sort(gaps);
            long over45 = 0;
            long over50 = 0;
            long stallTicks = 0; // gaps >= 2× nominal (100ms): single-tick wall or multi-tick stall
            for (int i = 0; i < durs.length; i++) {
                if (durs[i] >= 50_000_000L) {
                    over50++;
                } else if (durs[i] >= 45_000_000L) {
                    over45++;
                }
                if (gaps[i] >= 100_000_000L) {
                    stallTicks++;
                }
            }
            System.out.println(String.format(java.util.Locale.ROOT,
                "PapoTickProfile.tickdist n=%d durMs[p50=%.2f p95=%.2f p99=%.2f max=%.2f] over45=%d over50=%d | gapMs[p95=%.2f p99=%.2f max=%.2f] stalls100ms=%d | tps=%.2f",
                durs.length,
                pct(durs, 0.50) / 1e6, pct(durs, 0.95) / 1e6, pct(durs, 0.99) / 1e6, durs[durs.length - 1] / 1e6,
                over45, over50,
                pct(gaps, 0.95) / 1e6, pct(gaps, 0.99) / 1e6, gaps[gaps.length - 1] / 1e6,
                stallTicks,
                durs.length / (windowNanos / 1e9)));
        }
        // stall events with exact wall-clock (correlate with server log lines: joins,
        // autosaves, worldgen bursts, plugin tasks)
        for (final long[] ev : stallCopy) {
            System.out.println(String.format(java.util.Locale.ROOT,
                "PapoTickProfile.stall tick=%d gapMs=%.1f durMs=%.1f at=%4$tH:%4$tM:%4$tS.%4$tL",
                ev[0], ev[1] / 1e6, ev[2] / 1e6, ev[3]));
        }
        // Papo end - batch 113
        TOTALS.clear();
    }
}
// Papo end - batch 90: opt-in per-phase main-thread tick profiling
