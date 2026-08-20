package papo.bench;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次63：压缩级别选型基准（带宽主题运营收尾，配套 /paper netstat + paper-global misc.compression-level）。
 *
 * 目的：量化 network 压缩 level（服务端可用 -1(默认6) 与 1..9；Linux native libdeflate 另有 10..12）
 * 对三类代表性 MC 出站载荷的**压缩比**与**编/解码时间**，给服主按带宽/CPU 预算选级的决策数据。
 * 京东 Deflater 为 Windows/JDK 回退路径的真实后端；生产 Linux 为 libdeflate（同级别压缩比接近、
 * 绝对速度快一个量级——本表的相对趋势可迁移，绝对时间不可直接套用，报告如实注明）。
 *
 * 载荷模型（构造性、确定性种子）：
 *   - chunk：24 section × 4096 方块状态（每 section 小调色板 + 少量噪声）+ biome —— 高度可压（真实区块 10-20×）。
 *   - text：ASCII 聊天/记分板文本 ~1KB —— 中度可压。
 *   - light：24 section × 2048B 半随机的光照 nibble —— 低可压（真实光照有结构性，此处保守）。
 *
 * main 自检：每个 level 的压缩流经 Inflater 解压后与原文逐字节一致（roundtrip 完整性）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CompressionLevelBench {

    byte[] chunkPayload;
    byte[] textPayload;
    byte[] lightPayload;
    /** 选型矩阵的固定级（JMH 方法按级生成；矩阵数据由 main 打印）。 */

    @Setup
    public void setup() {
        final java.util.Random rnd = new java.util.Random(20260820L);
        // chunk：24 sections，每 section 一个 8-16 值的小调色板 + 2% 噪声越界值
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(24 * 4096 * 4);
        for (int s = 0; s < 24; s++) {
            final int[] palette = new int[8 + rnd.nextInt(8)];
            for (int p = 0; p < palette.length; p++) {
                palette[p] = rnd.nextInt(1 << 20);
            }
            for (int i = 0; i < 4096; i++) {
                final int v = rnd.nextInt(100) < 2 ? rnd.nextInt(1 << 24) : palette[rnd.nextInt(palette.length)];
                bos.write(v);
                bos.write(v >>> 8);
                bos.write(v >>> 16);
                bos.write(v >>> 24);
            }
        }
        this.chunkPayload = bos.toByteArray();
        // text：重复词汇的 ASCII 文本
        final String[] words = {"player", "score", "attack", "defense", "level", "world", "damage", "heal", "quest", "guild"};
        final StringBuilder sb = new StringBuilder(2048);
        for (int i = 0; sb.length() < 1024; i++) {
            sb.append(words[rnd.nextInt(words.length)]).append(' ');
        }
        this.textPayload = sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // light：半随机 nibble（保守下界——真实光照有长程结构，压缩比更好）
        final byte[] light = new byte[24 * 2048];
        rnd.nextBytes(light);
        for (int i = 0; i < light.length; i++) {
            light[i] &= 0x0F;
        }
        this.lightPayload = light;
    }

    static byte[] deflate(final byte[] input, final int level) {
        final Deflater d = new Deflater(level);
        try {
            d.setInput(input);
            d.finish();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length / 2);
            final byte[] buf = new byte[8192];
            while (!d.finished()) {
                final int n = d.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    static byte[] inflate(final byte[] input, final int expectedLen) throws DataFormatException {
        final Inflater inf = new Inflater();
        try {
            inf.setInput(input);
            final byte[] out = new byte[expectedLen];
            int w = 0;
            while (!inf.finished() && w < out.length) {
                w += inf.inflate(out, w, out.length - w);
            }
            return out;
        } finally {
            inf.end();
        }
    }

    // ===== JMH：默认级 6 与对比级在 chunk 载荷上的编码时间 =====

    @Benchmark
    public byte[] encode_chunk_level6() {
        return deflate(this.chunkPayload, 6);
    }

    @Benchmark
    public byte[] encode_chunk_level1() {
        return deflate(this.chunkPayload, 1);
    }

    @Benchmark
    public byte[] encode_chunk_level9() {
        return deflate(this.chunkPayload, 9);
    }

    @Benchmark
    public byte[] encode_light_level6() {
        return deflate(this.lightPayload, 6);
    }

    public static void main(final String[] args) throws Exception {
        final CompressionLevelBench b = new CompressionLevelBench();
        b.setup();
        final String[] names = {"chunk", "text", "light"};
        final byte[][] data = {b.chunkPayload, b.textPayload, b.lightPayload};
        System.out.println("== 选型矩阵（JDK Deflater；生产 libdeflate 压缩比接近、绝对更快）==");
        System.out.println("payload\tlevel\tratio\tencode_ns");
        for (int p = 0; p < data.length; p++) {
            for (final int level : new int[]{1, 3, 6, 9}) {
                // 预热一轮
                deflate(data[p], level);
                final long t0 = System.nanoTime();
                final int N = 30;
                byte[] out = null;
                for (int i = 0; i < N; i++) {
                    out = deflate(data[p], level);
                }
                final long perOp = (System.nanoTime() - t0) / N;
                // roundtrip 完整性
                final byte[] back = inflate(out, data[p].length);
                if (!java.util.Arrays.equals(back, data[p])) {
                    System.out.println("FAIL roundtrip " + names[p] + " level=" + level);
                    System.exit(1);
                }
                System.out.printf("%s\t%d\t%.2fx\t%d%n", names[p], level, (double) data[p].length / out.length, perOp);
            }
        }
        System.out.println("ALL OK");
    }
}
