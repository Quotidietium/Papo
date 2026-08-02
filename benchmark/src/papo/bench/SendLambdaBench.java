package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次56 / 补丁0209: Connection.sendPacket 消除 execute(() -> doSendPacket(...)) lambda。
 * 主线程发包（非 netty event loop，服务端发包常态）原每包 `execute(() -> doSendPacket(...))`
 * 分配一个 lambda（网络出站最高频分配点）。改为直调 doSendPacket（channel.write 跨线程安全）。
 *
 * 复刻：Conn（sendPacket + doSendPacket 计数）+ EventLoop 接口（inEventLoop()=false 模拟主线程，
 * execute(r) 经接口虚调用——避免 EA 把 lambda 消除，还原真实跨方法边界）。
 *   - before：sendPacket_before → execute(() -> doSendPacket(p))（lambda 分配 + 虚调用 execute）。
 *   - after： sendPacket_after  → doSendPacket(p) 直调。
 *
 * main 自检：两路径 writes 递增一致（doSendPacket 均被调用一次）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SendLambdaBench {

    /** EventLoop 接口（虚调用，防 EA 消除 lambda）。 */
    interface EventLoop {
        boolean inEventLoop();
        void execute(Runnable r);
    }

    /** 主线程模型：inEventLoop() 恒 false；execute 立即运行 r。 */
    static final class MainThreadLoop implements EventLoop {
        @Override public boolean inEventLoop() { return false; }
        @Override public void execute(Runnable r) { r.run(); }
    }

    static final class Packet {}

    static final class Conn {
        final EventLoop el;
        int sentPackets;
        int writes;
        Conn(EventLoop el) { this.el = el; }
        void doSendPacket(Packet p) { this.writes++; } // model channel.write

        /** before：原版——非 event loop 时 execute(lambda)。 */
        void sendPacket_before(Packet p) {
            this.sentPackets++;
            if (this.el.inEventLoop()) {
                this.doSendPacket(p);
            } else {
                this.el.execute(() -> this.doSendPacket(p)); // lambda 分配
            }
        }

        /** after：Papo——直调 doSendPacket。 */
        void sendPacket_after(Packet p) {
            this.sentPackets++;
            this.doSendPacket(p);
        }
    }

    private final Conn conn = new Conn(new MainThreadLoop());
    private final Packet packet = new Packet();

    @Benchmark
    public int before_sendLambda(Blackhole bh) {
        this.conn.sendPacket_before(this.packet);
        bh.consume(this.conn);
        return this.conn.writes;
    }

    @Benchmark
    public int after_directDoSend(Blackhole bh) {
        this.conn.sendPacket_after(this.packet);
        bh.consume(this.conn);
        return this.conn.writes;
    }

    /** 等价性自检：两路径 writes/sentPackets 递增一致。 */
    public static void main(String[] args) {
        Conn a = new Conn(new MainThreadLoop());
        Conn b = new Conn(new MainThreadLoop());
        Packet p = new Packet();
        a.sendPacket_before(p);
        b.sendPacket_after(p);
        if (a.writes != 1 || b.writes != 1 || a.sentPackets != 1 || b.sentPackets != 1) {
            System.out.println("MISMATCH counts"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
