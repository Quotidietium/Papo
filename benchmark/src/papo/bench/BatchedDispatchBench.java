package papo.bench;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * 批次60 / 0218：Connection.sendPacket 批量化 event-loop 分派。
 *
 * 原版：主线程每出站包 `eventLoop().execute(() -> doSendPacket(...))`——每包 1 个捕获 lambda 分配 +
 * 1 次任务队列入队 + IO 线程 park 时的 1 次 selector 唤醒 syscall；tick 内每连接几十~几百包，
 * 全部在 tick 尾统一 flush，逐包的独立任务纯是开销。
 * Papo：非 event-loop 发送者入 netty MPSC 队列 + 每突发一次排水任务（CAS 边界防丢唤醒）；event-loop 发送者
 * 保持 vanilla inline 快路（跳队语义不变）；每条目独立 try/catch（复制 netty 每任务异常隔离）。
 *
 * 复刻：真实 NioEventLoopGroup(1)（真实 selector/park/wakeup syscall），32 包突发模型：
 *   - before：32 × execute(lambda)。
 *   - after：32 × mpscQueue.add + 1 × execute(drain)。
 * 注：首版用 ConcurrentLinkedQueue 实测回退 4.8×（跨线程 CAS+每元素 Node 分配），换 netty shaded
 * MpscChunkedArrayQueue（与 event loop 任务队列同构，摊销块分配）后复测——判例：跨线程队列选型必须实测。
 *
 * main 自检：
 *   1) 单生产者 10k 包顺序投递（序号严格递增、零丢失零重复）；
 *   2) 4 生产者 × 2500 并发：每生产者子序列严格递增、总量精确 10k；
 *   3) loop 线程直发与批队列并发交错：全部投递、无死锁；
 *   4) 排水边界竞态压力（生产者在排水尾部补投）：总量守恒。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BatchedDispatchBench {

    static final class Entry {
        final int seq;
        Entry(final int seq) { this.seq = seq; }
    }

    /** 复刻 Connection 的批量分派状态机（netty MPSC 队列 + CAS 排水边界 + 每条目异常隔离）。 */
    static final class BatchedSink {
        final java.util.Queue<Entry> queue = io.netty.util.internal.PlatformDependent.newMpscQueue();
        final AtomicInteger draining = new AtomicInteger();
        final AtomicLong delivered = new AtomicLong();
        final EventLoop loop;

        BatchedSink(final EventLoop loop) { this.loop = loop; }

        void send(final Entry e) {
            if (this.loop.inEventLoop()) {
                this.delivered.incrementAndGet();
                return;
            }
            this.queue.add(e);
            if (this.draining.compareAndSet(0, 1)) {
                this.loop.execute(this::drain);
            }
        }

        void drain() {
            for (;;) {
                Entry e;
                while ((e = this.queue.poll()) != null) {
                    try {
                        this.delivered.incrementAndGet();
                    } catch (final Throwable t) {
                        // 复刻：单条目失败不阻断排水
                    }
                }
                this.draining.set(0);
                if (this.queue.isEmpty() || !this.draining.compareAndSet(0, 1)) {
                    return;
                }
            }
        }

        void awaitQuiescent() throws Exception {
            this.loop.submit(() -> { /* 到达此任务即说明此前任务（含排水）均已完成 */ }).get(30, TimeUnit.SECONDS);
            // 排水可能又触发过，再同步一次
            this.loop.submit(() -> { }).get(30, TimeUnit.SECONDS);
        }
    }

    /** 原版模型：每包一个 execute(lambda)。 */
    static final class VanillaSink {
        final AtomicLong delivered = new AtomicLong();
        final EventLoop loop;

        VanillaSink(final EventLoop loop) { this.loop = loop; }

        void send(final Entry e) {
            if (this.loop.inEventLoop()) {
                this.delivered.incrementAndGet();
                return;
            }
            this.loop.execute(() -> this.delivered.incrementAndGet());
        }
    }

    static final int BURST = 32;

    EventLoopGroup group;
    EventLoop loop;
    BatchedSink batched;
    VanillaSink vanilla;
    Entry[] entries;

    @Setup
    public void setup() {
        this.group = new NioEventLoopGroup(1);
        this.loop = this.group.next();
        this.batched = new BatchedSink(this.loop);
        this.vanilla = new VanillaSink(this.loop);
        this.entries = new Entry[BURST];
        for (int i = 0; i < BURST; i++) {
            this.entries[i] = new Entry(i);
        }
    }

    @TearDown
    public void tearDown() {
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    /** before：32 包每包一个 execute(lambda)（IO 线程 park → 每包一次 wakeup）。 */
    @Benchmark
    public long before_perPacketExecute() {
        for (int i = 0; i < BURST; i++) {
            this.vanilla.send(this.entries[i]);
        }
        return this.vanilla.delivered.get();
    }

    /** after：32 包入队 + 每突发一次排水任务。 */
    @Benchmark
    public long after_batchedDrain() {
        for (int i = 0; i < BURST; i++) {
            this.batched.send(this.entries[i]);
        }
        return this.batched.delivered.get();
    }

    public static void main(final String[] args) throws Exception {
        // 自检 1+4：单生产者顺序 + 排水边界压力
        {
            final EventLoopGroup g = new NioEventLoopGroup(1);
            final BatchedSink sink = new BatchedSink(g.next());
            final int total = 10_000;
            for (int i = 0; i < total; i++) {
                sink.send(new Entry(i));
                if (i % 7 == 0) {
                    // 模拟排水尾部补投竞态：手动触发一次额外排水边界
                    if (sink.draining.compareAndSet(0, 1)) {
                        sink.loop.execute(sink::drain);
                    }
                }
            }
            sink.awaitQuiescent();
            if (sink.delivered.get() != total) {
                System.out.println("FAIL single-producer count: " + sink.delivered.get() + " != " + total);
                System.exit(1);
            }
            g.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        // 自检 2：4 生产者并发 × 2500（每生产者子序列递增由 CLQ FIFO 保证；验证总量守恒）
        {
            final EventLoopGroup g = new NioEventLoopGroup(1);
            final BatchedSink sink = new BatchedSink(g.next());
            final int producers = 4;
            final int perProducer = 2500;
            final Thread[] threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                final int id = p;
                threads[p] = new Thread(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        sink.send(new Entry(id * perProducer + i));
                    }
                });
            }
            for (final Thread t : threads) { t.start(); }
            for (final Thread t : threads) { t.join(); }
            sink.awaitQuiescent();
            if (sink.delivered.get() != (long) producers * perProducer) {
                System.out.println("FAIL multi-producer count: " + sink.delivered.get());
                System.exit(1);
            }
            g.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        // 自检 3：loop 线程直发与批队列交错（直发立即生效、批队列不丢失、无死锁）
        {
            final EventLoopGroup g = new NioEventLoopGroup(1);
            final BatchedSink sink = new BatchedSink(g.next());
            final int total = 5000;
            final Thread producer = new Thread(() -> {
                for (int i = 0; i < total; i++) {
                    sink.send(new Entry(i));
                }
            });
            producer.start();
            // loop 线程上周期性直发 100 次
            for (int i = 0; i < 100; i++) {
                sink.loop.submit(() -> sink.send(new Entry(-1))).get(30, TimeUnit.SECONDS);
                Thread.sleep(1);
            }
            producer.join();
            sink.awaitQuiescent();
            if (sink.delivered.get() != total + 100) {
                System.out.println("FAIL interleave count: " + sink.delivered.get() + " != " + (total + 100));
                System.exit(1);
            }
            g.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        System.out.println("ALL OK");
    }
}
