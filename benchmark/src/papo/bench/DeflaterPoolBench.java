package papo.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次35: RegionFileVersion DEFLATE 写侧 Deflater ThreadLocal 池化 + 缓冲加大。
 * 原实现：每次区块保存 new Deflater(level)（本地 zlib 状态 + Cleaner 注册）+ close 时 end()，
 *         DeflaterOutputStream 内部缓冲 512。
 * 新实现：ThreadLocal 单槽池（close 时 reset()+归还，显式 Deflater 构造 close 不 end），内部缓冲 8192。
 * 使用真实 java.util.zip.Deflater（JDK 类，无需复刻）；压缩字节流仅取决于输入与 flush 模式。
 * main 自检：池化复用产出的压缩字节与全新 Deflater 逐字节一致（reset 等价）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class DeflaterPoolBench {

    private static final int LEVEL = 6;
    private static final ThreadLocal<Deflater> POOL = new ThreadLocal<>();

    private byte[] chunkData;

    @Setup
    public void setup() {
        // 模拟典型区块 NBT：~64KB 半随机半重复数据
        Random rnd = new Random(42);
        this.chunkData = new byte[65536];
        rnd.nextBytes(this.chunkData);
        for (int i = 4096; i < this.chunkData.length; i++) {
            if (rnd.nextInt(3) == 0) this.chunkData[i] = this.chunkData[i - 4096];
        }
    }

    private static Deflater borrow() {
        Deflater pooled = POOL.get();
        if (pooled != null) {
            POOL.set(null);
            return pooled;
        }
        return new Deflater(LEVEL);
    }

    /** 原实现：新 Deflater + end()。 */
    @Benchmark
    public int before_newDeflaterPerSave(Blackhole bh) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(LEVEL);
        try {
            DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater);
            stream.write(this.chunkData);
            stream.close();
        } finally {
            deflater.end();
        }
        bh.consume(out);
        return out.size();
    }

    /** 批次35：池化借还 + 8192 内部缓冲。 */
    @Benchmark
    public int after_pooledDeflater(Blackhole bh) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = borrow();
        try {
            DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater, 8192);
            stream.write(this.chunkData);
            stream.close(); // 显式 Deflater 构造：close 不 end
        } finally {
            deflater.reset();
            POOL.set(deflater);
        }
        bh.consume(out);
        return out.size();
    }

    /** 等价性自检：池化复用（reset 后再压缩）与全新 Deflater 的压缩字节逐字节一致。 */
    public static void main(String[] args) throws IOException {
        DeflaterPoolBench bench = new DeflaterPoolBench();
        bench.setup();

        // 全新 Deflater 基线
        ByteArrayOutputStream baseline = new ByteArrayOutputStream();
        Deflater fresh = new Deflater(LEVEL);
        DeflaterOutputStream s1 = new DeflaterOutputStream(baseline, fresh);
        s1.write(bench.chunkData);
        s1.close();
        fresh.end();

        // 池化：连续两次借还，第二次用的是 reset 后的同一实例
        byte[][] pooledOut = new byte[2][];
        for (int round = 0; round < 2; round++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Deflater deflater = borrow();
            DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater, 8192);
            stream.write(bench.chunkData);
            stream.close();
            deflater.reset();
            POOL.set(deflater);
            pooledOut[round] = out.toByteArray();
        }
        if (!Arrays.equals(baseline.toByteArray(), pooledOut[0])) { System.out.println("MISMATCH round0"); System.exit(1); }
        if (!Arrays.equals(baseline.toByteArray(), pooledOut[1])) { System.out.println("MISMATCH round1 (reset reuse)"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
