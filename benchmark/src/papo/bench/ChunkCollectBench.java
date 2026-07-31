package papo.bench;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: PlayerChunkSender.collectChunksToSend 两分支流管道改命令式。
 * (a) else 分支（pending ≤ quota 的稳态）：longStream().mapToObj().filter().sorted()
 *     .collect(toList()) → ArrayList + LongIterator 循环 + list.sort（同比较器、同稳定性）。
 * (b) least 分支：Comparators.least 选择保持原版不变，仅其后的
 *     mapToLong/mapToObj/filter/toList 二级管道 → 普通循环解析。
 *     （基准不含 guava，leastK 用排序截断复刻 least 的"k 个最小且升序"输出；
 *       两路径共用同一 leastK，故只度量解析阶段差异。）
 * 复刻：LongOpenHashSet 存区块键、getChunkToSend 用 set 模拟（25% 未命中）、
 * distanceSquared 用键低位散列。
 * main 自检：两分支输出列表内容（含顺序）一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ChunkCollectBench {

    private static final int PENDING = 48;
    private static final int FLOOR = 9; // least 分支 quota

    private final LongSet pending = new LongOpenHashSet();
    private final LongSet loaded = new LongOpenHashSet(); // getChunkToSend 命中的键

    @Setup
    public void setup() {
        for (int i = 0; i < PENDING; i++) {
            long key = i * 0x9E3779B97F4A7C15L;
            this.pending.add(key);
            if (i % 4 != 0) { // 75% 已加载
                this.loaded.add(key);
            }
        }
    }

    /** getChunkToSend 语义复刻。 */
    private Long chunkToSend(long key) {
        return this.loaded.contains(key) ? key : null;
    }

    /** distanceSquared 语义复刻（对区块中心）：键散列。 */
    private static int distanceSquared(long key) {
        long h = key ^ (key >>> 33);
        return (int) (h & 0x7FFFFFFF);
    }

    /** Comparators.least(k, comparator) 语义复刻：k 个最小元素，升序返回。 */
    private List<Long> leastK(int k) {
        List<Long> all = new ArrayList<>(this.pending);
        all.sort(Comparator.comparingInt(ChunkCollectBench::distanceSquared));
        return new ArrayList<>(all.subList(0, Math.min(k, all.size())));
    }

    // (a) else 分支

    @Benchmark
    public Object before_streamPipeline() {
        return this.pending.longStream()
            .mapToObj(this::chunkToSend)
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparingInt(ChunkCollectBench::distanceSquared))
            .collect(Collectors.toList());
    }

    @Benchmark
    public Object after_imperative() {
        List<Long> list = new ArrayList<>(this.pending.size());
        LongIterator it = this.pending.longIterator();
        while (it.hasNext()) {
            Long chunk = this.chunkToSend(it.nextLong());
            if (chunk != null) {
                list.add(chunk);
            }
        }
        list.sort(Comparator.comparingInt(ChunkCollectBench::distanceSquared));
        return list;
    }

    // (b) least 分支（解析阶段）

    @Benchmark
    public Object before_leastStreamResolve() {
        return this.leastK(FLOOR).stream()
            .mapToLong(Long::longValue)
            .mapToObj(this::chunkToSend)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Benchmark
    public Object after_leastLoopResolve() {
        List<Long> leastKeys = this.leastK(FLOOR);
        List<Long> list = new ArrayList<>(leastKeys.size());
        for (Long key : leastKeys) {
            Long chunk = this.chunkToSend(key);
            if (chunk != null) {
                list.add(chunk);
            }
        }
        return list;
    }

    /** 等价性自检。 */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        ChunkCollectBench bench = new ChunkCollectBench();
        bench.setup();
        List<Long> a = (List<Long>) bench.before_streamPipeline();
        List<Long> b = (List<Long>) bench.after_imperative();
        if (!a.equals(b)) { System.out.println("MISMATCH else-branch: " + a + " vs " + b); System.exit(1); }
        List<Long> c = (List<Long>) bench.before_leastStreamResolve();
        List<Long> d = (List<Long>) bench.after_leastLoopResolve();
        if (!c.equals(d)) { System.out.println("MISMATCH least-branch: " + c + " vs " + d); System.exit(1); }
        System.out.println("ALL OK");
    }
}
