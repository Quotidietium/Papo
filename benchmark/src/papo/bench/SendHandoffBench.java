package papo.bench;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次108：挂起窗发送移交成本微基准——per-send {@code eventLoop().execute(task)} vs
 * 批量 append + 单任务排水（Connection.papoSuspendBatch 的隔离模型）。
 *
 * 批次107 在位测量：2000 牛 × 10 观察者 ≈ 15k sends/tick、每 send 902-1060ns 主线程
 * （包装链+execute lambda 分配/MPSC 入队；flush 已按 tick 合并）。本基准隔离"移交"分量：
 * 两种模式向真实 NioEventLoop 投递同样的逐包工作（loop 侧消费计入黑洞），只有主线程
 * 侧的入队路径不同。预期：per-send ~1us 量级 vs 批量 ~50-100ns/包。
 *
 * 自检 main：单线程验证两种模式投递的工作总量一致（latch 计数），非 JMH 环境可直跑。
 */
@State(Scope.Benchmark)
public class SendHandoffBench {

    private EventLoopGroup group;
    private io.netty.channel.EventLoop loop;

    @Param({"1500", "15000"})
    public int sendsPerTick;

    /** loop 侧逐包工作载荷（消费队列元素）；static 避免捕获分配。 */
    static final java.util.concurrent.atomic.AtomicLong SINK = new java.util.concurrent.atomic.AtomicLong();
    static volatile long papoPayloadBase;

    /**
     * loop 侧逐包工作量模拟：~600ns 的 xorshift 混合（对应 channel.write 走管线的量级）。
     * 无载荷时 MPSC 入队不与忙循环争抢（~10ns/send），会低估 in-situ 的 ~1us/send——
     * 批次107 在位测量的移交成本主体正是忙循环上的队列争抢。
     */
    static long papoLoopWork(final long payload) {
        long x = payload ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 240; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    @Setup(Level.Trial)
    public void setup() {
        this.group = new NioEventLoopGroup(1);
        this.loop = this.group.next();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    /** before：每 send 一次 execute（lambda 捕获 + 逐任务入队）——现状即时路径。 */
    @Benchmark
    public long perSendExecute() {
        final int n = this.sendsPerTick;
        final io.netty.channel.EventLoop l = this.loop;
        final long base = papoPayloadBase;
        for (int i = 0; i < n; i++) {
            final long payload = base + i;
            l.execute(() -> SINK.addAndGet(papoLoopWork(payload)));
        }
        return base;
    }

    /** after：主线程 append 进数组 + 单任务排水——批次108 形态。 */
    @Benchmark
    public long batchDrainSingleTask() {
        final int n = this.sendsPerTick;
        final io.netty.channel.EventLoop l = this.loop;
        final long base = papoPayloadBase;
        final long[] batch = new long[n];
        for (int i = 0; i < n; i++) {
            batch[i] = base + i; // append（对应 PacketSendAction 写入）
        }
        l.execute(() -> {
            long acc = 0;
            for (int i = 0; i < n; i++) {
                acc += papoLoopWork(batch[i]); // 逐包 loop 侧工作（对应 doSendPacket 走管线）
            }
            SINK.addAndGet(acc);
        });
        return base;
    }

    /** 自检：两模式投递的 payload 总和一致且全部被 loop 执行（latch 等待完成）。 */
    public static void main(final String[] args) throws Exception {
        final SendHandoffBench b = new SendHandoffBench();
        b.sendsPerTick = 2000;
        b.setup();
        try {
            for (int round = 0; round < 3; round++) {
                papoPayloadBase = (round + 1) * 1_000_000L;
                final long expectSum = 2000L * papoPayloadBase + 2000L * 1999 / 2;
                final long before = SINK.get();
                b.perSendExecute();
                b.batchDrainSingleTask();
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (SINK.get() - before < 2 * expectSum) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("timeout: sink=" + (SINK.get() - before) + " expect=" + 2 * expectSum);
                    }
                    Thread.sleep(1);
                }
                final long got = SINK.get() - before;
                if (got != 2 * expectSum) {
                    throw new AssertionError("sum mismatch: " + got + " != " + 2 * expectSum);
                }
                System.out.println("selfcheck round " + round + " ok: both modes delivered " + expectSum + " each");
            }
        } finally {
            b.tearDown();
        }
        System.out.println("SELF CHECK PASS");
    }
}
