package papo.bench;

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
 * 0100: EntityJumpEvent/PlayerVelocityEvent 无插件监听器门控。
 * before: 每次跳跃/击退都构建事件对象（含 Vector.clone()）再 callEvent
 * after:  HandlerList 长度检查守卫，零监听器（绝大多数服务器）时整段跳过
 * 模型化复刻 Bukkit HandlerList（空监听器数组）与事件构建成本。
 * 参数: listeners=0/2（零监听器为常态；2 模拟有插件监听时门控开销不劣化）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EventGateBench {

    @Param({"0", "2"})
    int listeners;

    /** 模型化 HandlerList。 */
    static final class HandlerList {
        private final Object[] registeredListeners;

        HandlerList(int n) {
            this.registeredListeners = new Object[n];
            for (int i = 0; i < n; i++) {
                this.registeredListeners[i] = new Object();
            }
        }

        Object[] getRegisteredListeners() {
            return this.registeredListeners;
        }
    }

    /** 模型化事件对象（3 字段 + 1 克隆，近似 EntityJumpEvent/PlayerVelocityEvent 构建成本）。 */
    static final class JumpEvent {
        final Object entity;
        final double[] velocity; // 克隆
        boolean cancelled;

        JumpEvent(Object entity, double[] velocity) {
            this.entity = entity;
            this.velocity = velocity.clone();
        }

        boolean callEvent(HandlerList handlers) {
            // 模拟派发：对每个监听器一次虚调用
            for (Object l : handlers.getRegisteredListeners()) {
                if (l == this) { // 永不成立，仅防止循环被完全消除
                    this.cancelled = true;
                }
            }
            return !this.cancelled;
        }
    }

    private HandlerList handlerList;
    private Object entity;
    private double[] velocity;

    @Setup
    public void setup() {
        this.handlerList = new HandlerList(this.listeners);
        this.entity = new Object();
        this.velocity = new double[]{0.42, 0.08, -0.13};
    }

    /** 原实现：无条件构建 + 派发。 */
    @Benchmark
    public void before_alwaysConstruct(Blackhole bh) {
        JumpEvent event = new JumpEvent(this.entity, this.velocity);
        bh.consume(event.callEvent(this.handlerList));
    }

    /** Papo 0100：零监听器门控。 */
    @Benchmark
    public void after_gated(Blackhole bh) {
        if (this.handlerList.getRegisteredListeners().length == 0) {
            bh.consume(true); // callEvent() 零监听器恒返回 true
            return;
        }
        JumpEvent event = new JumpEvent(this.entity, this.velocity);
        bh.consume(event.callEvent(this.handlerList));
    }

    /** 等价性自检：零监听器时两路径返回值一致；有监听器时两路径一致。 */
    public static void main(String[] args) {
        boolean ok = true;
        for (int n : new int[]{0, 1, 2}) {
            EventGateBench bench = new EventGateBench();
            bench.listeners = n;
            bench.setup();
            JumpEvent event = new JumpEvent(bench.entity, bench.velocity);
            boolean beforeResult = event.callEvent(bench.handlerList);
            boolean afterResult = bench.handlerList.getRegisteredListeners().length == 0
                || new JumpEvent(bench.entity, bench.velocity).callEvent(bench.handlerList);
            ok &= beforeResult == afterResult;
            System.out.println("listeners=" + n + " before=" + beforeResult + " after=" + afterResult);
        }
        System.out.println(ok ? "ALL OK" : "MISMATCH");
        if (!ok) {
            System.exit(1);
        }
    }
}
