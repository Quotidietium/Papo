package papo.bench;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次35: ChunkGenerator.addVanillaDecorations 流消除。
 * (a) ChunkPos.rangeClosed(center,1).forEach：1 Stream + 9 ChunkPos + 闭包 → 3×3 双循环直取坐标；
 *     集合内容序无关（下游排序输出），双循环覆盖同一闭区域 9 点。【已应用】
 * (b) holderSet.stream().map(Holder::value).forEach → for-each：JMH 实测 0.82× 回退
 *     （ArrayList spliterator 索引循环 + 管道内联优于 Iterator 对象 + 逐元素虚调用），
 *     遵循延迟否决规则【不予应用】，测量保留于此以存档。
 * main 自检：(a) 两路径访问坐标集合一致；(b) 两路径产出的 IntSet 内容一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DecorationsStreamBench {

    /** ChunkPos 语义复刻（rangeClosed(center,1) 的坐标序列）。 */
    record ChunkPos(int x, int z) {
        static Stream<ChunkPos> rangeClosed(ChunkPos center, int radius) {
            List<ChunkPos> out = new ArrayList<>();
            for (int x = center.x - radius; x <= center.x + radius; x++) {
                for (int z = center.z - radius; z <= center.z + radius; z++) {
                    out.add(new ChunkPos(x, z));
                }
            }
            return out.stream();
        }
    }

    /** HolderSet/Holder 语义复刻（Direct：iterator 与 stream 同源同序）。 */
    record Holder<T>(T value) {}
    static final class HolderSet<T> {
        final List<Holder<T>> contents;
        HolderSet(List<Holder<T>> contents) { this.contents = contents; }
        Stream<Holder<T>> stream() { return this.contents.stream(); }
        java.util.Iterator<Holder<T>> iterator() { return this.contents.iterator(); }
    }

    private final HolderSet<String> features;
    private final int[] indexMapping;

    public DecorationsStreamBench() {
        List<Holder<String>> holders = new ArrayList<>();
        this.indexMapping = new int[64];
        for (int i = 0; i < 32; i++) {
            holders.add(new Holder<>("feature_" + i));
            this.indexMapping[i] = (i * 7) % 64;
        }
        this.features = new HolderSet<>(holders);
    }

    // (a) 3×3 邻域生物群系收集

    @Benchmark
    public int before_rangeClosedStream(Blackhole bh) {
        Set<Long> set = new HashSet<>();
        ChunkPos.rangeClosed(new ChunkPos(120, -45), 1).forEach(chunkPos -> {
            set.add(((long) chunkPos.x() << 32) | (chunkPos.z() & 0xFFFFFFFFL));
        });
        bh.consume(set);
        return set.size();
    }

    @Benchmark
    public int after_doubleLoop(Blackhole bh) {
        Set<Long> set = new HashSet<>();
        ChunkPos center = new ChunkPos(120, -45);
        for (int x = center.x() - 1; x <= center.x() + 1; x++) {
            for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                set.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            }
        }
        bh.consume(set);
        return set.size();
    }

    // (b) 特性索引收集

    @Benchmark
    public int before_holderSetStream(Blackhole bh) {
        Set<Integer> set = new HashSet<>();
        this.features.stream().map(Holder::value).forEach(feature -> set.add(this.indexMapping[feature.hashCode() & 31]));
        bh.consume(set);
        return set.size();
    }

    @Benchmark
    public int after_holderSetForEach(Blackhole bh) {
        Set<Integer> set = new HashSet<>();
        java.util.Iterator<Holder<String>> it = this.features.iterator();
        while (it.hasNext()) {
            set.add(this.indexMapping[it.next().value().hashCode() & 31]);
        }
        bh.consume(set);
        return set.size();
    }

    /** 等价性自检：(a) 坐标集合一致；(b) 索引集合一致。 */
    public static void main(String[] args) {
        DecorationsStreamBench bench = new DecorationsStreamBench();
        Set<Long> a = new HashSet<>();
        ChunkPos.rangeClosed(new ChunkPos(-7, 900), 1).forEach(p -> a.add(((long) p.x() << 32) | (p.z() & 0xFFFFFFFFL)));
        Set<Long> b = new HashSet<>();
        for (int x = -8; x <= -6; x++) for (int z = 899; z <= 901; z++) b.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        if (!a.equals(b) || a.size() != 9) { System.out.println("MISMATCH rangeClosed"); System.exit(1); }

        Set<Integer> s1 = new HashSet<>();
        bench.features.stream().map(Holder::value).forEach(f -> s1.add(bench.indexMapping[f.hashCode() & 31]));
        Set<Integer> s2 = new HashSet<>();
        java.util.Iterator<Holder<String>> it = bench.features.iterator();
        while (it.hasNext()) s2.add(bench.indexMapping[it.next().value().hashCode() & 31]);
        if (!s1.equals(s2)) { System.out.println("MISMATCH holderSet"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
