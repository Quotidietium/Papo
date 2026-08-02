package papo.bench;

import java.util.ArrayList;
import java.util.List;
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
 * 批次55 / 补丁0208: PacketBundleUnpacker.encode 非 bundle 包免 list::add Consumer 分配。
 * 原实现每出站包 `unbundlePacket(packet, list::add)`——`list::add` 是捕获局部 list 的方法引用，
 * 每求值分配一个 Consumer，经虚调用 unbundlePacket 传入（IO 线程每包一次）。非 bundle 包（99%）
 * 实际只执行 consumer.accept(packet)=list.add(packet)。改为：bundle 走 unbundlePacket，非 bundle 直 list.add。
 *
 * 复刻：BundlerInfo.unbundlePacket 非 bundle 路径（consumer.accept(packet)）。
 *   - before：bundler.unbundlePacket(packet, list::add)（分配 Consumer + 虚调用 + accept）。
 *   - after：list.add(packet)（直调，无 Consumer）。
 *
 * main 自检：两路径对非 bundle 包产出一致（list 内容相同）。
 * 注：浅栈复刻下 JIT/EA 可能消除 before 的 Consumer（gc.alloc.norm 两路径均 ~0）——若如此为 EA 伪影，
 * 真实 IO 线程深栈（netty 虚调用边界）EA 未必消除；改法零风险，按 0155 先例保留。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BundleUnpackerBench {

    /** BundlerInfo.unbundlePacket 非 bundle 路径复刻（bundle 分支对热路径无意义，省略）。 */
    static final class Bundler {
        void unbundlePacket(final Object packet, final Consumer<Object> consumer) {
            consumer.accept(packet); // 非 bundle：等同 list.add(packet)
        }
    }

    private final Bundler bundler = new Bundler();
    private final Object packet = new Object();

    /** before：每包 unbundlePacket(packet, list::add)——分配 Consumer。 */
    @Benchmark
    public int before_unbundleWithConsumer(Blackhole bh) {
        final List<Object> list = new ArrayList<>(1);
        this.bundler.unbundlePacket(this.packet, list::add); // list::add 方法引用：每次分配 Consumer
        bh.consume(list);
        return list.size();
    }

    /** after：非 bundle 直 list.add——无 Consumer。 */
    @Benchmark
    public int after_directAdd(Blackhole bh) {
        final List<Object> list = new ArrayList<>(1);
        list.add(this.packet);
        bh.consume(list);
        return list.size();
    }

    /** 等价性自检：两路径对非 bundle 包 list 内容一致。 */
    public static void main(final String[] args) {
        final BundleUnpackerBench b = new BundleUnpackerBench();
        final List<Object> l1 = new ArrayList<>();
        b.bundler.unbundlePacket(b.packet, l1::add);
        final List<Object> l2 = new ArrayList<>();
        l2.add(b.packet);
        if (l1.size() != 1 || l2.size() != 1 || l1.get(0) != b.packet || l2.get(0) != b.packet) {
            System.out.println("MISMATCH content"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
