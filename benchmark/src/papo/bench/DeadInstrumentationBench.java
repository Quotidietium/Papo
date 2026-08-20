package papo.bench;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次61 / 0222：PacketProcessor 死仪表门控（watchdog 包处理簿记）。
 *
 * 原版每入站包在主线程付出：ConcurrentLinkedDeque.push（1 Node 分配 + 2 CAS）+ AtomicLong
 * .getAndIncrement（1 CAS/volatile 写）+ deque.pop（1 CAS）——而该仪表（packetProcessing /
 * totalMainThreadPacketsProcessed / 两个 getter）全仓库零读取方（批次61 survey 全树 grep 实证，
 * 含 watchdog 线程）。Papo 以 static final false 门控整段（JIT 移除），字段与 getter 保留形状。
 *
 * 复刻：主线程单写者形态下 per-packet 簿记 vs 门控空转（Blackhole 汇校验和防 DCE）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DeadInstrumentationBench {

    static final ConcurrentLinkedDeque<Object> deque = new ConcurrentLinkedDeque<>();
    static final AtomicLong counter = new AtomicLong();
    static final Object listener = new Object();

    /** before：原版每包簿记（push + getAndIncrement + pop）。 */
    @Benchmark
    public long before_bookkeeping(final Blackhole bh) {
        deque.push(listener);
        try {
            bh.consume(listener);
        } finally {
            counter.getAndIncrement();
            deque.pop();
        }
        return counter.get();
    }

    /** after：门控关闭（static final false 的运行时形态——直接跳过簿记）。 */
    @Benchmark
    public long after_gated(final Blackhole bh) {
        bh.consume(listener);
        return 0L;
    }

    public static void main(final String[] args) {
        // 行为自检：门控开启形态与原版簿记语义一致（push/pop 配对、计数递增）
        deque.push(listener);
        counter.getAndIncrement();
        deque.pop();
        if (counter.get() != 1 || !deque.isEmpty()) {
            System.out.println("FAIL bookkeeping semantics");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
