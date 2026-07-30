package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
 * 0085: FriendlyByteBuf.writeFixedSizeLongArray 批量写出。
 * before: 逐元素 buffer.writeLong（每次 ensureWritable 检查）
 * after:  ensureWritable 一次 + internalNioBuffer(...).asLongBuffer().put(array) + writerIndex 推进
 * 目标 buffer 模拟区块包场景 Unpooled.wrappedBuffer(byte[])（单组件大端）。
 * 参数: longs = 数组长度（256=4bits/entry 区块 states，512=8bits，4096=高度图级）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LongArrayWriteBench {

    @Param({"256", "512", "4096"})
    int longs;

    private long[] array;
    private ByteBuf target;

    @Setup
    public void setup() {
        this.array = new long[longs];
        for (int i = 0; i < longs; i++) {
            array[i] = 0x123456789ABCDEFL * (i + 1);
        }
        this.target = Unpooled.wrappedBuffer(new byte[longs * 8 + 8]);
        this.target.clear();
    }

    @Benchmark
    public void before_perElementWriteLong(Blackhole bh) {
        target.clear();
        for (long l : array) {
            target.writeLong(l);
        }
        bh.consume(target);
    }

    @Benchmark
    public void after_bulkLongBuffer(Blackhole bh) {
        target.clear();
        int n = array.length << 3;
        target.ensureWritable(n);
        int w = target.writerIndex();
        target.internalNioBuffer(w, n).asLongBuffer().put(array);
        target.writerIndex(w + n);
        bh.consume(target);
    }

    /** 等价性自检（非基准）：两种写法的线上字节必须一致。 */
    public static void main(String[] args) {
        LongArrayWriteBench b = new LongArrayWriteBench();
        b.longs = 300; // 非 2 幂，覆盖边界
        b.setup();
        ByteBuf a = Unpooled.wrappedBuffer(new byte[b.longs * 8]);
        a.clear();
        for (long l : b.array) {
            a.writeLong(l);
        }
        ByteBuf c = Unpooled.wrappedBuffer(new byte[b.longs * 8]);
        c.clear();
        int n = b.array.length << 3;
        c.internalNioBuffer(0, n).asLongBuffer().put(b.array);
        c.writerIndex(n);
        boolean equal = true;
        for (int i = 0; i < n; i++) {
            if (a.getByte(i) != c.getByte(i)) {
                equal = false;
                break;
            }
        }
        System.out.println("equal=" + equal + " bytes=" + n);
    }
}
