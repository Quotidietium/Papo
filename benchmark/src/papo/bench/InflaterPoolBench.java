package papo.bench;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
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
 * 0103: RegionFileVersion DEFLATE 读侧 Inflater ThreadLocal 池化 + fill 缓冲 512→8192。
 * before: 每次读盘 new InflaterInputStream(in)——每次分配 native zlib 状态（inflateInit）
 *         + Cleaner 注册，close 时 JNI end()；512 字节 fill 缓冲
 * after:  (in, pooledInflater, 8192) 构造，close 不 end（usesDefaultInflater=false，
 *         JDK 字节码实证），Inflater reset() 后归还 ThreadLocal 单槽池
 * 等价性：解压输出只取决于压缩输入，与 fill 分块无关；Inflater.reset() 契约即复用。
 * 自检见 main：池化流连续多轮解压输出与全新 InflaterInputStream 逐字节一致。
 * 参数: size=4096/32768（典型区块 NBT 压缩前规模）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class InflaterPoolBench {

    @Param({"4096", "32768"})
    int size;

    private byte[] compressed;
    private int rawSize;

    /** 与补丁中 RegionFileVersion.PAPO_INFLATER_POOL/PapoInflaterInputStream 逐行一致。 */
    private static final int PAPO_INFLATER_BUFFER_SIZE = 8192;
    private static final ThreadLocal<Inflater> PAPO_INFLATER_POOL = new ThreadLocal<>();

    private static Inflater papoBorrowInflater() {
        final Inflater pooled = PAPO_INFLATER_POOL.get();
        if (pooled != null) {
            PAPO_INFLATER_POOL.set(null);
            return pooled;
        }
        return new Inflater();
    }

    static final class PapoInflaterInputStream extends InflaterInputStream {
        PapoInflaterInputStream(InputStream in) {
            super(in, papoBorrowInflater(), PAPO_INFLATER_BUFFER_SIZE);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                this.inf.reset();
                PAPO_INFLATER_POOL.set(this.inf);
            }
        }
    }

    @Setup
    public void setup() throws IOException {
        // 生成"类区块 NBT"数据：低熵模式 + 部分随机（压缩率接近真实区块）
        byte[] raw = new byte[this.size];
        Random random = new Random(42);
        for (int i = 0; i < raw.length; i++) {
            if (i % 16 < 8) {
                raw[i] = (byte) (i % 7); // 低熵模式段
            } else {
                raw[i] = (byte) random.nextInt(256); // 随机段
            }
        }
        this.rawSize = raw.length;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(bos)) {
            def.write(raw);
        }
        this.compressed = bos.toByteArray();
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Benchmark
    public void before_newInflaterPerRead(Blackhole bh) throws IOException {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(this.compressed))) {
            byte[] raw = drain(in);
            bh.consume(raw.length);
        }
    }

    @Benchmark
    public void after_pooledInflater(Blackhole bh) throws IOException {
        try (InputStream in = new PapoInflaterInputStream(new ByteArrayInputStream(this.compressed))) {
            byte[] raw = drain(in);
            bh.consume(raw.length);
        }
    }

    /** 等价性自检：池化流多轮复用的解压输出与全新流逐字节一致。 */
    public static void main(String[] args) throws IOException {
        InflaterPoolBench bench = new InflaterPoolBench();
        for (int n : new int[]{512, 4096, 32768}) {
            bench.size = n;
            bench.setup();
            byte[] reference = drain(new InflaterInputStream(new ByteArrayInputStream(bench.compressed)));
            // 同一池连续 3 轮复用（验证 reset 语义与归还/借出正确性）
            for (int round = 0; round < 3; round++) {
                byte[] pooled;
                try (InputStream in = new PapoInflaterInputStream(new ByteArrayInputStream(bench.compressed))) {
                    pooled = drain(in);
                }
                boolean ok = Arrays.equals(reference, pooled) && pooled.length == bench.rawSize;
                System.out.println("size=" + n + " round=" + round + " equal=" + ok + " bytes=" + pooled.length);
                if (!ok) {
                    System.out.println("MISMATCH");
                    System.exit(1);
                }
            }
        }
        // 并发双开：同一线程同时持有两个流（借出后池空 → 新建；归还时后还者覆盖单槽）
        InflaterPoolBench bench2 = new InflaterPoolBench();
        bench2.size = 4096;
        bench2.setup();
        try (InputStream a = new PapoInflaterInputStream(new ByteArrayInputStream(bench2.compressed));
             InputStream b = new PapoInflaterInputStream(new ByteArrayInputStream(bench2.compressed))) {
            byte[] ra = drain(a);
            byte[] rb = drain(b);
            boolean ok = Arrays.equals(ra, rb);
            System.out.println("concurrent-two-streams equal=" + ok);
            if (!ok) {
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
