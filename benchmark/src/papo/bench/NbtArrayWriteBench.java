package papo.bench;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
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
 * 批21: NBT int/long 数组写出（0068 的对称面，区块保存路径）。
 * before: 逐元素 writeInt/writeLong（DataOutputStream 每元素 4/8 字节小缓冲拷贝 + 调用开销）。
 * after:  ByteBuffer 大端批量编码 + 一次 write(byte[])。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NbtArrayWriteBench {

    @Param({"256", "4096", "65536"})
    int size;

    private int[] ints;
    private long[] longs;

    @Setup
    public void setup() {
        Random r = new Random(42);
        ints = new int[size];
        longs = new long[size];
        for (int i = 0; i < size; i++) {
            ints[i] = r.nextInt();
            longs[i] = r.nextLong();
        }
    }

    // ---- int[] ----

    @Benchmark
    public void before_intPerElement(Blackhole bh) throws IOException {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream(size * 4));
        for (int i : ints) {
            out.writeInt(i);
        }
        bh.consume(out);
    }

    @Benchmark
    public void after_intBulk(Blackhole bh) throws IOException {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream(size * 4));
        byte[] buf = new byte[size << 2];
        ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).asIntBuffer().put(ints);
        out.write(buf);
        bh.consume(out);
    }

    // ---- long[] ----

    @Benchmark
    public void before_longPerElement(Blackhole bh) throws IOException {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream(size * 8));
        for (long l : longs) {
            out.writeLong(l);
        }
        bh.consume(out);
    }

    @Benchmark
    public void after_longBulk(Blackhole bh) throws IOException {
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream(size * 8));
        byte[] buf = new byte[size << 3];
        ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).asLongBuffer().put(longs);
        out.write(buf);
        bh.consume(out);
    }
}
