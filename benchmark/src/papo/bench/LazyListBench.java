package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0101/0102: 惰性 list 分配（GameEventDispatcher.post BY_DISTANCE 队列、Player.aiStep 经验球列表、
 * TrackedEntity 清理循环防御性拷贝）。
 * before: 每调用无条件 new ArrayList（0103 为全量防御性拷贝），即使无元素装入
 * after:  首个命中时才创建；0103 改为惰性收集移除项
 * 模型化复刻：N 个候选中 hits 个命中（0 为常态：无 sculk 类监听器/无经验球/无待清理玩家）。
 * 参数: "32,0"=32 候选 0 命中（常态）；"32,2"=32 候选 2 命中。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LazyListBench {

    @Param({"32,0", "32,2"})
    String scenario;

    private int candidates;
    private int hits;
    private Object[] items;

    @Setup
    public void setup() {
        String[] parts = this.scenario.split(",");
        this.candidates = Integer.parseInt(parts[0]);
        this.hits = Integer.parseInt(parts[1]);
        this.items = new Object[this.candidates];
        for (int i = 0; i < this.candidates; i++) {
            this.items[i] = new Object();
        }
    }

    private boolean isHit(int index) {
        return index < this.hits;
    }

    /** 原实现：无条件分配（0101）/ 全量防御性拷贝（0102）。 */
    @Benchmark
    public void before_eagerAlloc(Blackhole bh) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < this.candidates; i++) {
            if (this.isHit(i)) {
                list.add(this.items[i]);
            }
        }
        if (!list.isEmpty()) {
            bh.consume(list);
        }
    }

    /** 0102 原实现变体：先全量拷贝 seenBy 再遍历判定（拷贝即是被消除的成本）。 */
    @Benchmark
    public void before_defensiveCopy(Blackhole bh) {
        List<Object> copy = new ArrayList<>(this.candidates);
        for (int i = 0; i < this.candidates; i++) {
            copy.add(this.items[i]);
        }
        int hitCount = 0;
        for (int i = 0; i < copy.size(); i++) {
            if (this.isHit(i)) {
                hitCount++;
            }
        }
        bh.consume(hitCount);
    }

    /** Papo 0101/0102：惰性分配。 */
    @Benchmark
    public void after_lazyAlloc(Blackhole bh) {
        List<Object> list = null;
        for (int i = 0; i < this.candidates; i++) {
            if (this.isHit(i)) {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(this.items[i]);
            }
        }
        if (list != null) {
            bh.consume(list);
        }
    }

    /** 等价性自检：收集到的元素序列一致（顺序、内容、空/非空行为）。 */
    public static void main(String[] args) {
        boolean ok = true;
        for (int[] sc : new int[][]{{32, 0}, {32, 1}, {32, 2}, {32, 32}, {1, 0}}) {
            LazyListBench bench = new LazyListBench();
            bench.scenario = sc[0] + "," + sc[1];
            bench.setup();
            List<Object> eager = new ArrayList<>();
            for (int i = 0; i < bench.candidates; i++) {
                if (bench.isHit(i)) {
                    eager.add(bench.items[i]);
                }
            }
            List<Object> lazy = null;
            for (int i = 0; i < bench.candidates; i++) {
                if (bench.isHit(i)) {
                    if (lazy == null) {
                        lazy = new ArrayList<>();
                    }
                    lazy.add(bench.items[i]);
                }
            }
            boolean scenarioOk = eager.equals(lazy == null ? new ArrayList<>() : lazy)
                && (eager.isEmpty() == (lazy == null));
            System.out.println(sc[0] + " candidates/" + sc[1] + " hits equal=" + scenarioOk);
            ok &= scenarioOk;
        }
        System.out.println(ok ? "ALL OK" : "MISMATCH");
        if (!ok) {
            System.exit(1);
        }
    }
}
