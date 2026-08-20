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
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次66 / 任务2 路线A 的一票裁决探针：声明式记忆链的 Present 读 Optional 是否真实分配。
 * 复刻：map 查找 → Optional.map(ExpirableValue::getValue) → 条件虚分发（3 实现）→ MemoryAccessor(IdF.create)。
 * before = getMemoryInternal（Optional 包装）；after = papoGetMemoryInternalRaw（直值 + null 判）。
 * Escape 形态对齐生产：Optional 不逃逸出 tryTrigger，MemoryAccessor 逃逸（consume）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MemoryOptionalProbe {

    static final class Expirable<T> { final T value; Expirable(final T v) { this.value = v; } }
    static final class Accessor { final Object value; Accessor(final Object v) { this.value = v; } }
    interface Cond { Object create(Optional<String> memory); }
    static final class Present implements Cond {
        @Override public Object create(final Optional<String> memory) {
            return memory.isEmpty() ? null : new Accessor(memory.get());
        }
    }
    static final class AbsentC implements Cond {
        @Override public Object create(final Optional<String> memory) {
            return memory.isPresent() ? null : new Accessor("unit");
        }
    }
    static final class Registered implements Cond {
        @Override public Object create(final Optional<String> memory) {
            return new Accessor(memory);
        }
    }

    Map<String, Expirable<String>> memories;
    Cond present;
    Cond absent;
    Cond registered;
    Cond[] rotation; // 让 createAccessor 的内联缓存不单态（贴近 trigger 变体切换）
    int i;

    @Setup
    public void setup() {
        this.memories = new HashMap<>();
        this.memories.put("job_site", new Expirable<>("pos"));
        this.present = new Present();
        this.absent = new AbsentC();
        this.registered = new Registered();
        this.rotation = new Cond[]{this.present, this.present, this.absent, this.registered};
    }

    /** before：getMemoryInternal（Optional.map 包装）→ 虚分发 createAccessor。 */
    @Benchmark
    public Object before_optionalChain(final Blackhole bh) {
        final Cond trigger = this.rotation[this.i++ & 3];
        final Optional<String> memoryInternal = this.getMemoryInternal("job_site");
        final Object r = memoryInternal == null ? null : trigger.create(memoryInternal);
        bh.consume(r);
        return r;
    }

    /** after：Present 特判走 raw（null 判），非 Present 保持原路。 */
    @Benchmark
    public Object after_rawPresent(final Blackhole bh) {
        final Cond trigger = this.rotation[this.i++ & 3];
        final Object r;
        if (trigger == this.present) {
            final String raw = this.getRaw("job_site");
            r = raw == null ? null : new Accessor(raw);
        } else {
            final Optional<String> memoryInternal = this.getMemoryInternal("job_site");
            r = memoryInternal == null ? null : trigger.create(memoryInternal);
        }
        bh.consume(r);
        return r;
    }

    private Optional<String> getMemoryInternal(final String key) {
        final Expirable<String> e = this.memories.get(key);
        return e == null ? null : Optional.of(e.value);
    }

    private String getRaw(final String key) {
        final Expirable<String> e = this.memories.get(key);
        return e == null ? null : e.value;
    }
}
