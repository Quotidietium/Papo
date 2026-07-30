package papo.bench;

import java.util.Optional;
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
 * 0087: Slot.tryRemove Optional 协议 vs @Nullable 内部路径。
 * before: Optional.ofNullable(...) + ifPresent(lambda) + orElse(EMPTY)
 * after:  null 判断直走
 * 模拟每次背包点击/取物的调用形态（成功/失败两分支）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SlotTakeOptionalBench {

    @Param({"true", "false"})
    boolean success;

    private Object item; // 模拟 ItemStack
    private int consumed;

    @Setup
    public void setup() {
        this.item = new Object();
    }

    private Object tryRemoveInternal() {
        return success ? item : null;
    }

    @Benchmark
    public Object before_optionalProtocol() {
        Optional<Object> optional = Optional.ofNullable(tryRemoveInternal());
        optional.ifPresent(stack -> consumed += stack.hashCode() & 1);
        return optional.orElse(this);
    }

    @Benchmark
    public Object after_nullablePath(Blackhole bh) {
        Object result = tryRemoveInternal();
        if (result != null) {
            consumed += result.hashCode() & 1;
        }
        bh.consume(consumed);
        return result == null ? this : result;
    }
}
