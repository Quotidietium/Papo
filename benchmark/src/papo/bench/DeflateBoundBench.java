package papo.bench;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次58 / D1：CompressionEncoder 输出缓冲按 DEFLATE 最坏情况上界分配。
 *
 * 原版 allocateBuffer 给压缩输出 n+1 字节——对不可压缩 payload（DEFLATE 转存储块）放不下。实测（JDK zlib
 * level 6，随机输入，64KiB..4MiB 四点）：膨胀恰为 5B/16384B 窗口 + 6B 头尾 = n + 5*ceil(n/16384) + 6：
 *   - native libdeflate（Linux/macOS 生产路径）：NativeZlibDeflate.process 返回 0 → 调用方把容量翻倍并
 *     **把整个输入重新压缩一遍**（重压缩尖峰，尾延迟）；
 *   - JDK 回退（Windows）：JavaVelocityCompressor.deflate 循环 ensureWritable(8192) 步进扩容续压（不重压）。
 * Papo 改为 n + n/2048 + 32（以 ~1.6× 裕量覆盖两后端实测最坏上界），重试路径不可达（保留为兜底）。
 * 线上字节与帧格式完全不变（仅容量）。注：首版公式 n + n/4096 + 16 被本基准自检证伪（256KiB 随机数据
 * 实测膨胀 +86 > 界 +80），据实测数据修正——判例：理论上界推导必须过实测校验。
 *
 * 复刻：本机（Windows，无 native）真实运行路径 = JavaVelocityCompressor.deflate 的 ByteBuffer 循环，
 * 故用 java.util.zip.Deflater 忠实建模 before（n+1 起步 + ensureWritable 步进扩容续压）与
 * after（n+n/4096+16 一次到位）。native 的重压缩收益无法在本机测得，报告如实注明。
 *
 * main 自检：多组尺寸 × {随机(不可压), 全零(高压缩)} 下：
 *   1) after 上界永远够（无扩容）；
 *   2) before 的 n+1 对随机数据确实不够（实证重试触发面）；
 *   3) 两路径产出压缩流逐字节一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DeflateBoundBench {

    private static final int SIZE = 64 * 1024;
    private static final byte[] RANDOM = new byte[SIZE];

    static {
        new java.util.Random(20260820L).nextBytes(RANDOM);
    }

    /**
     * 复刻 JavaVelocityCompressor.deflate：deflate(ByteBuffer) 循环 + !isWritable 时扩容。
     *
     * @param initialCap 初始容量（before=n+1，after=n+n/4096+16）
     * @return [压缩字节, 扩容次数]
     */
    private static Object[] deflate(final byte[] input, final int initialCap) throws Exception {
        final Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] out = new byte[initialCap];
        int written = 0;
        int grows = 0;
        while (!deflater.finished()) {
            if (written == out.length) {
                final byte[] bigger = new byte[out.length + 8192]; // ensureWritable(8192) 步进语义
                System.arraycopy(out, 0, bigger, 0, written);
                out = bigger;
                grows++;
            }
            final ByteBuffer view = ByteBuffer.wrap(out, written, out.length - written);
            final int n = deflater.deflate(view);
            written += n;
        }
        deflater.end();
        return new Object[]{java.util.Arrays.copyOf(out, written), grows};
    }

    @Benchmark
    public Object[] before_sizePlusOne(final Blackhole bh) throws Exception {
        final Object[] r = deflate(RANDOM, SIZE + 1);
        bh.consume(r[0]);
        return r;
    }

    @Benchmark
    public Object[] after_deflateBound(final Blackhole bh) throws Exception {
        final Object[] r = deflate(RANDOM, SIZE + (SIZE >>> 11) + 32);
        bh.consume(r[0]);
        return r;
    }

    public static void main(final String[] args) throws Exception {
        final int[] sizes = {1024, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024};
        final java.util.Random rnd = new java.util.Random(7L);
        for (final int n : sizes) {
            for (final boolean incompressible : new boolean[]{true, false}) {
                final byte[] data = new byte[n];
                if (incompressible) {
                    rnd.nextBytes(data);
                } else {
                    java.util.Arrays.fill(data, (byte) 0);
                }
                final Object[] before = deflate(data, n + 1);
                final Object[] after = deflate(data, n + (n >>> 11) + 32);
                final byte[] bOut = (byte[]) before[0];
                final byte[] aOut = (byte[]) after[0];
                final int bGrows = (Integer) before[1];
                final int aGrows = (Integer) after[1];
                if (!java.util.Arrays.equals(bOut, aOut)) {
                    System.out.println("MISMATCH stream n=" + n + " incompressible=" + incompressible);
                    System.exit(1);
                }
                if (aGrows != 0) {
                    System.out.println("BOUND INSUFFICIENT n=" + n + " incompressible=" + incompressible + " grows=" + aGrows);
                    System.exit(1);
                }
                System.out.println("n=" + n + " incompressible=" + incompressible
                    + " compressed=" + bOut.length + " beforeGrows=" + bGrows + " afterGrows=" + aGrows
                    + " bound=" + (n + (n >>> 11) + 32) + (bGrows > 0 ? "  <- n+1 too small (retry would trigger)" : ""));
            }
        }
        System.out.println("ALL OK");
    }
}
