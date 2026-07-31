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
 * 批次37: ValidateNearbyPoi 声明式 BehaviorBuilder 链改手写 OneShot + Brain 原生读取。
 * (a) 记忆门：getMemoryInternal（map 命中时 Optional.map 分配）+ present 判空
 *     → papoGetMemoryInternalRaw 直出可空引用。
 * (b) 访问器：声明式 createAccessor 每次 tryStart 一个 MemoryAccessor 实例 +
 *     应用闭包层调用 → 直接方法体。
 * 复刻：Brain.memories（HashMap<类型, Optional<ExpirableValue>>）、ExpirableValue.getValue、
 * POI 校验主体（维度比较 + 距离平方 + exists 查询 + erase 路径）。
 * main 自检：记忆存在/为空/未注册三态与 POI 分支两路径结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ValidatePoiHandwrittenBench {

    /** ExpirableValue 语义复刻。 */
    record ExpirableValue(Object value) {
        Object getValue() { return this.value; }
    }

    /** Brain 记忆表语义复刻。 */
    static final class Brain {
        final Map<String, Optional<ExpirableValue>> memories = new HashMap<>();
        int eraseCount;

        Optional<Object> getMemoryInternal(String type) {
            Optional<ExpirableValue> optional = this.memories.get(type);
            return optional == null ? null : optional.map(ExpirableValue::getValue);
        }

        Object papoGetMemoryInternalRaw(String type) {
            Optional<ExpirableValue> optional = this.memories.get(type);
            return optional != null && optional.isPresent() ? optional.get().getValue() : null;
        }

        void eraseMemory(String type) {
            this.eraseCount++;
            this.memories.put(type, Optional.empty());
        }
    }

    /** MemoryAccessor 语义复刻（声明式每次 tryStart 一个实例）。 */
    static final class MemoryAccessor {
        final Brain brain;
        final String type;
        final Optional<Object> value;
        MemoryAccessor(Brain brain, String type, Optional<Object> value) {
            this.brain = brain;
            this.type = type;
            this.value = value;
        }
        Object get() { return this.value.get(); }
        void erase() { this.brain.eraseMemory(this.type); }
    }

    /** GlobalPos 语义复刻。 */
    record GlobalPos(String dimension, long pos) {}

    private final Brain brain = new Brain();
    private final GlobalPos poiPos = new GlobalPos("overworld", 12345L);
    private String dimension = "overworld";
    private final boolean[] poiExists = {true, false, true, true, false, true, true, true};

    public ValidatePoiHandwrittenBench() {
        this.brain.memories.put("job_site", Optional.of(new ExpirableValue(this.poiPos)));
    }

    /** POI 校验主体语义复刻：维度比较 + exists 查询（数组长表）+ erase。 */
    private boolean body(Object globalPosRaw, MemoryAccessor accessor, int iter) {
        GlobalPos globalPos = globalPosRaw instanceof GlobalPos g ? g : (GlobalPos) globalPosRaw;
        if (this.dimension.equals(globalPos.dimension())) {
            if (!this.poiExists[iter & 7]) {
                if (accessor != null) accessor.erase(); else this.brain.eraseMemory("job_site");
            }
            return true;
        }
        return false;
    }

    @Benchmark
    public int before_declarativeChain(Blackhole bh) {
        int runs = 0;
        for (int i = 0; i < 16; i++) {
            this.brain.memories.put("job_site", Optional.of(new ExpirableValue(this.poiPos))); // 重置 erase
            Optional<Object> memoryInternal = this.brain.getMemoryInternal("job_site");
            if (memoryInternal == null || memoryInternal.isEmpty()) continue;
            MemoryAccessor accessor = new MemoryAccessor(this.brain, "job_site", memoryInternal); // createAccessor 分配
            if (this.body(accessor.get(), accessor, i)) runs++;
            bh.consume(accessor);
        }
        return runs;
    }

    @Benchmark
    public int after_handwritten(Blackhole bh) {
        int runs = 0;
        for (int i = 0; i < 16; i++) {
            this.brain.memories.put("job_site", Optional.of(new ExpirableValue(this.poiPos))); // 重置 erase
            Object globalPos = this.brain.papoGetMemoryInternalRaw("job_site");
            if (globalPos == null) continue;
            if (this.body(globalPos, null, i)) runs++;
        }
        bh.consume(runs);
        return runs;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        // 三态门一致
        ValidatePoiHandwrittenBench bench = new ValidatePoiHandwrittenBench();
        Brain brain = bench.brain;
        if (brain.getMemoryInternal("job_site").isEmpty() || brain.papoGetMemoryInternalRaw("job_site") == null) {
            System.out.println("MISMATCH present"); System.exit(1);
        }
        brain.memories.put("job_site", Optional.empty());
        if (brain.getMemoryInternal("job_site").isPresent() || brain.papoGetMemoryInternalRaw("job_site") != null) {
            System.out.println("MISMATCH empty"); System.exit(1);
        }
        brain.memories.remove("job_site");
        if (brain.getMemoryInternal("job_site") != null || brain.papoGetMemoryInternalRaw("job_site") != null) {
            System.out.println("MISMATCH unregistered"); System.exit(1);
        }
        // 双路径整体一致
        ValidatePoiHandwrittenBench b1 = new ValidatePoiHandwrittenBench();
        ValidatePoiHandwrittenBench b2 = new ValidatePoiHandwrittenBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        int r1 = b1.before_declarativeChain(bh);
        int r2 = b2.after_handwritten(bh);
        if (r1 != r2 || b1.brain.eraseCount != b2.brain.eraseCount) {
            System.out.println("MISMATCH body: " + r1 + "/" + b1.brain.eraseCount + " vs " + r2 + "/" + b2.brain.eraseCount); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
