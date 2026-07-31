package papo.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: POI 查询 Optional 包装消除。PoiManager.getType / PoiSection.exists 原本
 * 每次查询构造 Optional（getTypeOrNull 的 Optional.ofNullable 包装 + isPresent/get 拆包），
 * 调用点（PoiCompetitorScan 等每 tick 扫描）只需要"是否存在/引用"。
 * 复刻：Map 查询 + Optional 包装 vs 直接 null 返回；命中/未命中混合（3:1，贴合
 * 村庄扫描中大量非 POI 位置）。
 * main 自检：命中与未命中两路径可观察结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PoiLookupBench {

    /** PoiSection 语义复刻。 */
    static final class PoiSection {
        final Map<Long, Object> byPos = new HashMap<>();
        /** 原版：Optional 包装。 */
        Optional<Object> getType(long pos) {
            return Optional.ofNullable(this.byPos.get(pos));
        }
        /** 新版：可空引用。 */
        Object getTypeOrNull(long pos) {
            return this.byPos.get(pos);
        }
    }

    private final PoiSection section = new PoiSection();
    private final long[] queryPos = new long[64];

    public PoiLookupBench() {
        for (int i = 0; i < 64; i++) {
            this.queryPos[i] = i * 0x9E3779B97F4A7C15L;
            if (i % 4 != 0) { // 3/4 命中
                this.section.byPos.put(this.queryPos[i], new Object());
            }
        }
    }

    @Benchmark
    public int before_optionalWrap(Blackhole bh) {
        int hits = 0;
        for (long pos : this.queryPos) {
            Optional<Object> opt = this.section.getType(pos);
            if (opt.isPresent()) {
                bh.consume(opt.get());
                hits++;
            }
        }
        return hits;
    }

    @Benchmark
    public int after_nullableRef(Blackhole bh) {
        int hits = 0;
        for (long pos : this.queryPos) {
            Object poi = this.section.getTypeOrNull(pos);
            if (poi != null) {
                bh.consume(poi);
                hits++;
            }
        }
        return hits;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        PoiLookupBench bench = new PoiLookupBench();
        for (long pos : bench.queryPos) {
            Optional<Object> a = bench.section.getType(pos);
            Object b = bench.section.getTypeOrNull(pos);
            if (a.isPresent() != (b != null)) { System.out.println("MISMATCH presence @" + pos); System.exit(1); }
            if (a.isPresent() && a.get() != b) { System.out.println("MISMATCH ref @" + pos); System.exit(1); }
        }
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_optionalWrap(bh) != bench.after_nullableRef(bh)) {
            System.out.println("MISMATCH hit count"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
