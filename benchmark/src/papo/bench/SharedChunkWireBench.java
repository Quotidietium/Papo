package papo.bench;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次71: 多观众区块包共享实例 + 压缩输出 memo（CompressionEncoder 段复用）。
 *
 * 多观众发送同一 BE-free chunk（版本未变）时，每个连接对相同字节各自 DEFLATE——是
 * 多观众区块突发的主导成本（0129 实测 64KB level-6 ≈ 1.3ms/次）。memo 使首个连接压缩后，
 * 后续连接直接 memcpy 该段（[数据长 varint][压缩负载]，自描述、字节恒等）。
 *
 * 成本模型（JDK Deflater，与 Windows 回退后端同源；libdeflate 结构性收益相同）：
 *  before = 每观众：序列化（headroom buffer 写出）+ DEFLATE + 帧化
 *  afterFill = 首观众：before + 段快照（一次性）
 *  afterHit = 后续观众：序列化 + memo 段 memcpy + 帧化
 * 载荷：8 非空 section（12 项加权调色板，主符号占优——真实区块可压缩性 3-5×）+ 16 空 section。
 *
 * main 自检：两路径全观众 wire 字节逐字节全等 / Deflater 同输入同 level 输出确定性 /
 * memo 阈值戳不匹配不命中。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SharedChunkWireBench {

    static final int THRESHOLD = 256;
    static final int VIEWERS = 8;

    static final int NON_EMPTY_SECTIONS = 8;
    static final int PALETTE_SIZE = 12;
    static final int STORAGE_BITS = 4;
    static final int STORAGE_LONGS = 4096 * STORAGE_BITS / 64;

    byte[] payload; // 已序列化的 chunk 包内容（PacketEncoder 产物模型）
    final Memo memo = new Memo();

    /** memo 语义复刻（PapoSharedWireMemo：threshold 戳 + 单槽 + 首写胜出）。 */
    static final class Memo {
        int threshold = -1;
        byte[] segment;
        byte[] segmentFor(int threshold) {
            return this.threshold == threshold ? this.segment : null;
        }
        void store(int threshold, byte[] segment) {
            if (this.segment == null || this.threshold != threshold) {
                this.threshold = threshold;
                this.segment = segment;
            }
        }
    }

    @Setup
    public void setup() {
        // 真实可压缩载荷：加权调色板索引（主符号 ~70%），打包进位存储
        long[] storage = new long[STORAGE_LONGS];
        java.util.Random rnd = new java.util.Random(0x5EED_71L);
        int bitsFree = 0;
        long acc = 0;
        int idx = 0;
        for (int cell = 0; cell < 4096; cell++) {
            int sym = cell % 2 == 0 ? rnd.nextInt(2) : 2 + rnd.nextInt(PALETTE_SIZE - 2); // 半随机符号（贴近真实区块熵）
            acc |= (long) sym << bitsFree;
            bitsFree += STORAGE_BITS;
            if (bitsFree == 64) {
                storage[idx++] = acc;
                acc = 0;
                bitsFree = 0;
            }
        }
        byte[] section = new byte[2 + 1 + 1 + PALETTE_SIZE * 2 + 5 + STORAGE_LONGS * 8];
        int w = 0;
        section[w++] = 0x0B; section[w++] = (byte) 0xB8; // short 3000 非空计数（BE 两字节）
        section[w++] = 0x01;                              // 线性调色板 tag
        section[w++] = (byte) PALETTE_SIZE;
        for (int j = 0; j < PALETTE_SIZE; j++) { section[w++] = (byte) (1 + j * 13); }
        // 位存储长度 varint（256 longs → varint 2 字节）
        int n = STORAGE_LONGS;
        while ((n & ~0x7F) != 0) { section[w++] = (byte) ((n & 0x7F) | 0x80); n >>>= 7; }
        section[w++] = (byte) n;
        for (long v : storage) {
            for (int b = 7; b >= 0; b--) section[w++] = (byte) (v >>> (b * 8));
        }
        byte[] emptySection = {0x00, 0x00, 0x00, 0x00, 0x05};

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            for (int i = 0; i < NON_EMPTY_SECTIONS; i++) bos.write(section, 0, w);
            for (int i = 0; i < 16; i++) bos.write(emptySection, 0, emptySection.length);
            // 光照型数据：上方 section 全亮（0xFF）、下方全暗（0x00）、每三个一个过渡噪声块——
            // 贴近真实 chunk 包的天空光照熵结构（批次63 实测整包 level-6 压缩比 ≈ 4.9×）
            for (int blk = 0; blk < 12; blk++) {
                byte[] light = new byte[2048];
                if (blk % 3 == 2) {
                    for (int i = 0; i < light.length; i++) light[i] = (byte) ((rnd.nextInt(16) << 4) | rnd.nextInt(16));
                } else {
                    java.util.Arrays.fill(light, blk < 8 ? (byte) 0xFF : (byte) 0x00);
                }
                bos.write(light, 0, light.length);
            }
            bos.write(new byte[]{0x00, 0x00, 0x00, 0x01, 0x00}); // 包 id + chunk x/z 模型
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        this.payload = bos.toByteArray();
    }

    static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** DEFLATE 一段（threshold 之上路径：[数据长 varint][zlib 负载]）。 */
    static byte[] deflateSegment(Deflater deflater, byte[] input) {
        deflater.setInput(input, 0, input.length);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length / 3 + 64);
        writeVarInt(out, input.length);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.reset();
        return out.toByteArray();
    }

    /** 帧化 wire 输出：[帧长 varint][段]。 */
    static byte[] frame(byte[] segment) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(segment.length + 5);
        writeVarInt(out, segment.length);
        out.write(segment, 0, segment.length);
        return out.toByteArray();
    }

    /** before：每观众 序列化（模型=载荷拷贝）+ DEFLATE + 帧。 */
    static byte[] sendFresh(Deflater deflater, byte[] payload) {
        byte[] encoded = payload.clone(); // PacketEncoder 序列化（模型为拷贝）
        return frame(deflateSegment(deflater, encoded));
    }

    final Deflater benchDeflater = new Deflater();

    @Benchmark
    public void before(final Blackhole bh) {
        bh.consume(sendFresh(this.benchDeflater, this.payload));
    }

    @Benchmark
    public void afterFill(final Blackhole bh) {
        byte[] encoded = this.payload.clone();
        byte[] segment = deflateSegment(this.benchDeflater, encoded);
        this.memo.store(THRESHOLD, segment);
        bh.consume(frame(segment));
    }

    @Benchmark
    public void afterHit(final Blackhole bh) {
        byte[] encoded = this.payload.clone(); // 序列化仍每连接发生
        byte[] cached = this.memo.segmentFor(THRESHOLD);
        if (cached == null) {
            cached = deflateSegment(this.benchDeflater, encoded);
            this.memo.store(THRESHOLD, cached);
        }
        bh.consume(frame(cached));
    }

    // ---- main 自检 ----
    public static void main(String[] args) {
        SharedChunkWireBench b = new SharedChunkWireBench();
        b.setup();
        int failures = 0;
        Deflater d = new Deflater();

        // 1) Deflater 确定性：同输入两次压缩逐字节一致
        byte[] s1 = deflateSegment(d, b.payload);
        byte[] s2 = deflateSegment(d, b.payload);
        if (!Arrays.equals(s1, s2)) { failures++; System.out.println("FAIL deflate determinism"); }

        // 2) N 观众：before 全走 DEFLATE vs after（首 fill + 其余 hit）——wire 字节逐观众全等
        b.memo.store(THRESHOLD, null); // 清空（store(null) 会跳过写？—— 用反射式重置：直接置空）
        b.memo.threshold = -1;
        b.memo.segment = null;
        for (int v = 0; v < VIEWERS; v++) {
            byte[] before = sendFresh(d, b.payload);
            byte[] cached = b.memo.segmentFor(THRESHOLD);
            byte[] segment;
            if (cached == null) {
                segment = deflateSegment(d, b.payload.clone());
                b.memo.store(THRESHOLD, segment);
            } else {
                segment = cached;
            }
            byte[] after = frame(segment);
            if (!Arrays.equals(before, after)) { failures++; System.out.println("FAIL viewer " + v + " wire bytes differ"); }
        }

        // 3) 阈值戳不匹配不命中（不同 threshold 的连接各自压缩）
        if (b.memo.segmentFor(THRESHOLD + 1) != null) { failures++; System.out.println("FAIL memo hit on threshold mismatch"); }

        // 4) payload 可压缩性 sanity（真实区块 3-5×；模型应 >2×）
        if (b.payload.length / (double) s1.length < 2.5 || b.payload.length / (double) s1.length > 8.0) { failures++; System.out.println("FAIL payload not chunk-like compressible"); }

        System.out.println("payload=" + b.payload.length + "B compressed=" + s1.length + "B ratio=" + String.format("%.2f", b.payload.length / (double) s1.length));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
