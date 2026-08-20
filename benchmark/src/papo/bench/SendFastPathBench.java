package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 批次60 / 0218：Connection.send 立即发送判定的析取重排。
 *
 * 原版：`canSendImmediate(connection, packet) || (isMainThread && isReady && queueEmpty && noExtra)`
 * ——play 阶段主线程发送（压倒性常态）时，非白名单包（实体移动/数据/方块/区块更新等）要先走完
 * canSendImmediate 的 ~20 个 instanceof 链（全部 miss）才落到恒真的主线程臂。
 * Papo：两个析取臂均为无副作用纯谓词且都导向同一 sendPacket 调用，求值顺序不可观察——把廉价的
 * 主线程臂提前，instanceof 链只在该臂为假（异步线程发送）时才求值。
 *
 * 复刻：20 个白名单类 instanceof 链（照抄 InnerUtil.canSendImmediate 的结构与数量）。
 * 输入全部经 @State 非终态字段传入（JMH 保证 state 字段读不被 DCE；首版用 static final 常量
 * 被 JIT 整链常量折叠为 0.48ns——判例：谓词基准的输入必须经 state 字段）。
 * 载荷为非白名单类（最坏情形：全链 miss 后落到主线程臂）。
 *
 * main 自检：两版对所有输入（白名单命中/全 miss × 主/非主线程 × 协议）布尔结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SendFastPathBench {

    // ===== 白名单类层级模型（20 个，与 InnerUtil.canSendImmediate 数量一致）=====
    static class P0 {}
    static class P1 {}
    static class P2 {}
    static class P3 {}
    static class P4 {}
    static class P5 {}
    static class P6 {}
    static class P7 {}
    static class P8 {}
    static class P9 {}
    static class P10 {}
    static class P11 {}
    static class P12 {}
    static class P13 {}
    static class P14 {}
    static class P15 {}
    static class P16 {}
    static class P17 {}

    // 非终态输入字段（防 JIT 常量折叠/DCE）
    boolean isPending;
    boolean playProtocol;
    boolean isMainThread;
    boolean isReady;
    boolean queueEmpty;
    Object packet;

    @Setup
    public void setup() {
        this.isPending = false;
        this.playProtocol = true;   // play 阶段常态
        this.isMainThread = true;   // 主线程发送常态
        this.isReady = true;
        this.queueEmpty = true;
        this.packet = new Object(); // 非白名单载荷：20 instanceof 全 miss（最坏情形）
    }

    boolean canSendImmediateModel(final boolean pending, final boolean play, final Object p) {
        return pending || !play
            || p instanceof P0 || p instanceof P1 || p instanceof P2 || p instanceof P3
            || p instanceof P4 || p instanceof P5 || p instanceof P6 || p instanceof P7
            || p instanceof P8 || p instanceof P9 || p instanceof P10 || p instanceof P11
            || p instanceof P12 || p instanceof P13 || p instanceof P14 || p instanceof P15
            || p instanceof P16 || p instanceof P17
            || p instanceof java.lang.Runnable; // 与原链最后一个条目对齐
    }

    boolean mainThreadArm(final boolean main, final boolean ready, final boolean empty) {
        return main && ready && empty && true; // noExtraPackets 常态为 true
    }

    /** before：原版求值序——instanceof 链先（非白名单全 miss），再主线程臂。 */
    @Benchmark
    public boolean before_chainFirst() {
        return this.canSendImmediateModel(this.isPending, this.playProtocol, this.packet)
            || this.mainThreadArm(this.isMainThread, this.isReady, this.queueEmpty);
    }

    /** after：重排——主线程臂先短路，instanceof 链不求值。 */
    @Benchmark
    public boolean after_cheapArmFirst() {
        return this.mainThreadArm(this.isMainThread, this.isReady, this.queueEmpty)
            || this.canSendImmediateModel(this.isPending, this.playProtocol, this.packet);
    }

    public static void main(final String[] args) {
        final SendFastPathBench b = new SendFastPathBench();
        b.setup();
        // 布尔等价矩阵：白名单命中/全 miss × 主线程臂真/假 × 协议真/假
        final Object[] packets = {new P0(), new Object(), new P17(), new P16(), (java.lang.Runnable) () -> {}};
        for (final Object p : packets) {
            for (final boolean main : new boolean[]{true, false}) {
                for (final boolean play : new boolean[]{true, false}) {
                    for (final boolean pending : new boolean[]{false, true}) {
                        final boolean before = b.canSendImmediateModel(pending, play, p) || b.mainThreadArm(main, true, true);
                        final boolean after = b.mainThreadArm(main, true, true) || b.canSendImmediateModel(pending, play, p);
                        if (before != after) {
                            System.out.println("MISMATCH main=" + main + " play=" + play + " pending=" + pending);
                            System.exit(1);
                        }
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
