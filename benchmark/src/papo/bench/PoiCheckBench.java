package papo.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.openjdk.jmh.annotations.*;

/**
 * 批次123 / 补丁 0257：updatePOIOnBlockStateChange 的每次 setBlock POI 检查去 Optional。
 * before = Optional.ofNullable(map.get) ×2 + Objects.equals；after = map.get ×2 + 引用比较。
 * 场景：绝大多数 setBlock（红石翻转等）双方都无 POI。
 *
 * 自检 main：无/无、有/有（同实例）、有/异、有/无、无/有 五形态判定与执行分支全等
 * （以计数器模型化 execute 回调与 stale-POI 分支）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx128m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PoiCheckBench {

    static final class State {
        final int id;
        State(int id) { this.id = id; }
    }
    static final class Holder {
        final String key;
        Holder(String key) { this.key = key; }
    }

    Map<State, Holder> typeByState;
    State noPoiA, noPoiB, beehive1, beehive2; // beehive1/2 同类型（同 Holder 实例=注册表驻留模型）
    Holder beeHolder;

    // 分支计数（自检用 + 防止死代码消除）
    int removes, adds, staleRemoves;

    @Setup
    public void setup() {
        typeByState = new HashMap<>();
        beeHolder = new Holder("bee");
        noPoiA = new State(1);
        noPoiB = new State(2);
        beehive1 = new State(3);
        beehive2 = new State(4);
        typeByState.put(beehive1, beeHolder);
        typeByState.put(beehive2, beeHolder); // 注册表驻留：同类型同实例
        removes = adds = staleRemoves = 0;
    }

    boolean beforeRun(State oldS, State newS) {
        Optional<Holder> o = Optional.ofNullable(typeByState.get(oldS));
        Optional<Holder> n = Optional.ofNullable(typeByState.get(newS));
        if (!Objects.equals(o, n)) {
            o.ifPresent(h -> removes++);
            n.ifPresent(h -> {
                if (o.isEmpty() && existsMock()) staleRemoves++;
                adds++;
            });
            return true;
        }
        return false;
    }

    boolean afterRun(State oldS, State newS) {
        Holder o = typeByState.get(oldS);
        Holder n = typeByState.get(newS);
        if (o != n) {
            if (o != null) removes++;
            if (n != null) {
                if (o == null && existsMock()) staleRemoves++;
                adds++;
            }
            return true;
        }
        return false;
    }

    boolean existsMock() { return false; }

    @Benchmark
    public boolean beforeOptionalPair() {
        boolean acc = false;
        for (int i = 0; i < 64; i++) { // 红石形态：全 no-POI 翻转
            acc ^= beforeRun(noPoiA, noPoiB);
        }
        return acc;
    }

    @Benchmark
    public boolean afterReferenceCompare() {
        boolean acc = false;
        for (int i = 0; i < 64; i++) {
            acc ^= afterRun(noPoiA, noPoiB);
        }
        return acc;
    }

    public static void main(String[] args) {
        PoiCheckBench b = new PoiCheckBench();
        b.setup();
        // 异类型目标（注册表另一 Holder 实例）
        State other = new State(5);
        b.typeByState.put(other, new Holder("other"));
        State[][] cases = {
            {b.noPoiA, b.noPoiB},   // 无→无：无动作
            {b.beehive1, b.beehive2}, // 有→有（同类型同实例）：无动作（等价关键形态）
            {b.beehive1, b.noPoiA}, // 有→无：remove
            {b.noPoiA, b.beehive1}, // 无→有：add
            {b.beehive1, other},    // 有→异类型：remove+add
        };
        for (State[] c : cases) {
            PoiCheckBench x = new PoiCheckBench();
            x.setup();
            x.typeByState.put(other, b.typeByState.get(other));
            boolean rb = x.beforeRun(c[0], c[1]);
            int tb = x.removes * 100 + x.adds * 10 + x.staleRemoves;
            PoiCheckBench y = new PoiCheckBench();
            y.setup();
            y.typeByState.put(other, b.typeByState.get(other));
            boolean ra = y.afterRun(c[0], c[1]);
            int ta = y.removes * 100 + y.adds * 10 + y.staleRemoves;
            if (rb != ra || tb != ta) throw new AssertionError("case mismatch: " + rb + "/" + tb + " vs " + ra + "/" + ta);
        }
        System.out.println("PoiCheckBench self-check ALL OK (5 branch shapes)");
    }
}
