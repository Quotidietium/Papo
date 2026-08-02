package papo.bench;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次54 / 补丁0207: Connection.PacketSendAction 消除 delegate lambda + WrappedConsumer AtomicBoolean→boolean。
 * 原实现每排队包（send 的非 canSendImmediate 路径，突发负载下 queue 非空时命中）分配 3 个对象：
 * PacketSendAction + delegate lambda（捕获 packet/listener/flush）+ AtomicBoolean（consumed）。
 * 改为：PacketSendAction 存 listener/flush 字段、override accept 直调 sendPacket（免 lambda）；
 * WrappedConsumer.consumed 由 AtomicBoolean 降为 boolean（processQueue 单线程，CAS 非必要）。
 *
 * 复刻：Conn（sendPacket 计数）+ before/after 两套 WrappedConsumer/PacketSendAction。
 *   - before：new PSA_before(packet,null,false)（3 对象）+ accept（delegate→sendPacket）。
 *   - after： new PSA_after(packet,null,false)（1 对象）+ accept（直调 sendPacket）。
 * gc alloc.norm 应显示 ~3× 分配差异；突发负载下队列路径的实际分配源。
 *
 * main 自检：before/after 的 accept 均触发 sendPacket（conn.sends 递增一致）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PacketSendActionBench {

    /** Connection 语义复刻：sendPacket 计数。 */
    static final class Conn {
        int sends;
        void sendPacket(Object packet, Object listener, boolean flush) { this.sends++; }
    }

    // ===== before：原版结构（delegate lambda + AtomicBoolean）=====
    static class WCBefore implements Consumer<Conn> {
        final Consumer<Conn> delegate;
        final AtomicBoolean consumed = new AtomicBoolean(false);
        WCBefore(Consumer<Conn> d) { this.delegate = d; }
        @Override public void accept(Conn c) { this.delegate.accept(c); }
        boolean tryMarkConsumed() { return this.consumed.compareAndSet(false, true); }
        boolean isConsumed() { return this.consumed.get(); }
    }
    static final class PSABefore extends WCBefore {
        final Object packet;
        PSABefore(Object packet, Object listener, boolean flush) {
            super(c -> c.sendPacket(packet, listener, flush)); // delegate lambda：捕获三元组
            this.packet = packet;
        }
    }

    // ===== after：Papo 结构（直调 accept + boolean consumed）=====
    static class WCAfter implements Consumer<Conn> {
        final Consumer<Conn> delegate;
        boolean consumed;
        WCAfter(Consumer<Conn> d) { this.delegate = d; }
        @Override public void accept(Conn c) { this.delegate.accept(c); }
        boolean tryMarkConsumed() { if (this.consumed) return false; this.consumed = true; return true; }
        boolean isConsumed() { return this.consumed; }
    }
    static final class PSAAfter extends WCAfter {
        final Object packet;
        final Object listener;
        final boolean flush;
        PSAAfter(Object packet, Object listener, boolean flush) {
            super(null); // delegate 未用（accept 已 override）
            this.packet = packet; this.listener = listener; this.flush = flush;
        }
        @Override public void accept(Conn c) { c.sendPacket(this.packet, this.listener, this.flush); }
    }

    private final Conn conn = new Conn();
    private final Object packet = new Object();

    @Benchmark
    public int before_createAndAccept(Blackhole bh) {
        PSABefore action = new PSABefore(this.packet, null, false); // 3 对象
        action.accept(this.conn); // delegate -> sendPacket
        bh.consume(action);
        return this.conn.sends;
    }

    @Benchmark
    public int after_createAndAccept(Blackhole bh) {
        PSAAfter action = new PSAAfter(this.packet, null, false); // 1 对象
        action.accept(this.conn); // 直调 sendPacket
        bh.consume(action);
        return this.conn.sends;
    }

    /** 等价性自检：两路径 accept 均触发 sendPacket；tryMarkConsumed 语义一致。不依赖 Blackhole（main 不可实例化）。 */
    public static void main(String[] args) {
        Object sink = new Object(); // 逃逸汇（对齐 @Benchmark 的 bh.consume 语义）
        PacketSendActionBench b = new PacketSendActionBench();
        // before
        int s0 = b.conn.sends;
        PSABefore before = new PSABefore(b.packet, null, false);
        before.accept(b.conn);
        if (b.conn.sends - s0 != 1) { System.out.println("MISMATCH before send"); System.exit(1); }
        java.util.Objects.requireNonNull(before); sink.hashCode();
        // after
        int s1 = b.conn.sends;
        PSAAfter after = new PSAAfter(b.packet, null, false);
        after.accept(b.conn);
        if (b.conn.sends - s1 != 1) { System.out.println("MISMATCH after send"); System.exit(1); }
        java.util.Objects.requireNonNull(after); sink.hashCode();
        // tryMarkConsumed 语义（before AtomicBoolean CAS vs after boolean）：首次 true、再次 false、isConsumed true
        WCBefore wb = new WCBefore(c -> {});
        WCAfter wa = new WCAfter(c -> {});
        if (!wb.tryMarkConsumed() || wb.tryMarkConsumed() || !wb.isConsumed()) { System.out.println("MISMATCH before consumed"); System.exit(1); }
        if (!wa.tryMarkConsumed() || wa.tryMarkConsumed() || !wa.isConsumed()) { System.out.println("MISMATCH after consumed"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
