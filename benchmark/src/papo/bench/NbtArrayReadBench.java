package papo.bench;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
 * 0068: NBT int/long 数组读取。
 * before: 逐元素 readInt/readLong（每次 4/8 次单字节拼接 + bounds check）。
 * after:  readFully 整块读入 + ByteBuffer BIG_ENDIAN 视图批量解码。
 * 尺寸: 256（区块 Section 高度图级）、4096（生物群系/区块数据级）、65536（大型结构）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NbtArrayReadBench {

    @Param({"256", "4096", "65536"})
    int size;

    private byte[] intBytes;  // size * 4
    private byte[] longBytes; // size * 8

    @Setup
    public void setup() {
        Random r = new Random(42);
        intBytes = new byte[size * 4];
        longBytes = new byte[size * 8];
        r.nextBytes(intBytes);
        r.nextBytes(longBytes);
    }

    // ---- int[] ----

    @Benchmark
    public void before_intPerElement(Blackhole bh) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(intBytes));
        int[] ints = new int[size];
        for (int i = 0; i < size; i++) {
            ints[i] = in.readInt();
        }
        bh.consume(ints);
    }

    @Benchmark
    public void after_intBulk(Blackhole bh) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(intBytes));
        int[] ints = new int[size];
        byte[] buf = new byte[size << 2];
        in.readFully(buf);
        ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(ints);
        bh.consume(ints);
    }

    // ---- long[] ----

    @Benchmark
    public void before_longPerElement(Blackhole bh) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(longBytes));
        long[] longs = new long[size];
        for (int i = 0; i < size; i++) {
            longs[i] = in.readLong();
        }
        bh.consume(longs);
    }

    @Benchmark
    public void after_longBulk(Blackhole bh) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(longBytes));
        long[] longs = new long[size];
        byte[] buf = new byte[size << 3];
        in.readFully(buf);
        ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).asLongBuffer().get(longs);
        bh.consume(longs);
    }
}
