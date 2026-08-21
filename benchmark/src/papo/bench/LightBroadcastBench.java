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
 * 批次72: 光照增量广播压缩 memo（ClientboundLightUpdatePacket 实现 Carrier）。
 *
 * ChunkHolder.broadcastChanges 把同一 ClientboundLightUpdatePacket 实例发给每 chunk 的全部
 * 追踪玩家（黄昏/黎明光照传播期：每 tick 多 chunk × N 玩家）；每连接对相同 10-40KB 各自
 * DEFLATE。memo 使首连接压缩、后续 memcpy（0242 机制，本批扩展到光照广播域）。
 *
 * 载荷：光照型熵结构（16 个 2048B section：天空过渡区随机 nibble + 全亮/全暗大块），
 * 压缩比 ~2×（比区块包 4× 更难压缩 ⇒ 单次 deflate 更贵、memo 收益更大）。
 * before = 每观众 序列化+DEFLATE+帧；afterFill = 首观众(+快照)；afterHit = 后续观众 memcpy。
 *
 * main 自检：全观众 wire 字节全等 / Deflater 确定性 / 阈值戳不匹配不命中 / 压缩比带内 (1.5-4×)。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LightBroadcastBench {

    static final int THRESHOLD = 256;
    static final int VIEWERS = 20;
    static final int LIGHT_BLOCKS = 16;

    byte[] payload;
    final SharedChunkWireBench.Memo memo = new SharedChunkWireBench.Memo();
    final Deflater benchDeflater = new Deflater();

    @Setup
    public void setup() {
        java.util.Random rnd = new java.util.Random(0x1A17_71L);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(LIGHT_BLOCKS * 2048 + 16);
        for (int blk = 0; blk < LIGHT_BLOCKS; blk++) {
            byte[] light = new byte[2048];
            if (blk % 4 == 1 || blk % 4 == 2) {
                // 天空过渡 section：随机 nibble 对（高熵，接近不可压缩）
                for (int i = 0; i < light.length; i++) light[i] = (byte) ((rnd.nextInt(16) << 4) | rnd.nextInt(16));
            } else {
                // 全亮（0xFF）/全暗（0x00）大块
                Arrays.fill(light, blk % 4 == 0 ? (byte) 0xFF : (byte) 0x00);
            }
            bos.write(light, 0, light.length);
        }
        bos.write(0x00); bos.write(0x00); // 包 id 模型
        this.payload = bos.toByteArray();
    }

    // 复用 0242 基准的 varint/段/帧原语（包级静态）

    @Benchmark
    public void before(final Blackhole bh) {
        bh.consume(SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(this.benchDeflater, this.payload.clone())));
    }

    @Benchmark
    public void afterFill(final Blackhole bh) {
        byte[] segment = SharedChunkWireBench.deflateSegment(this.benchDeflater, this.payload.clone());
        this.memo.store(THRESHOLD, segment);
        bh.consume(SharedChunkWireBench.frame(segment));
    }

    @Benchmark
    public void afterHit(final Blackhole bh) {
        byte[] encoded = this.payload.clone();
        byte[] cached = this.memo.segmentFor(THRESHOLD);
        if (cached == null) {
            cached = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
            this.memo.store(THRESHOLD, cached);
        }
        bh.consume(SharedChunkWireBench.frame(cached));
    }

    public static void main(String[] args) {
        LightBroadcastBench b = new LightBroadcastBench();
        b.setup();
        int failures = 0;
        Deflater d = new Deflater();

        byte[] s1 = SharedChunkWireBench.deflateSegment(d, b.payload);
        byte[] s2 = SharedChunkWireBench.deflateSegment(d, b.payload);
        if (!Arrays.equals(s1, s2)) { failures++; System.out.println("FAIL deflate determinism"); }

        b.memo.threshold = -1;
        b.memo.segment = null;
        for (int v = 0; v < VIEWERS; v++) {
            byte[] before = SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(d, b.payload.clone()));
            byte[] cached = b.memo.segmentFor(THRESHOLD);
            if (cached == null) {
                cached = SharedChunkWireBench.deflateSegment(d, b.payload.clone());
                b.memo.store(THRESHOLD, cached);
            }
            if (!Arrays.equals(before, SharedChunkWireBench.frame(cached))) { failures++; System.out.println("FAIL viewer " + v); }
        }
        if (b.memo.segmentFor(THRESHOLD + 1) != null) { failures++; System.out.println("FAIL threshold stamp"); }

        double ratio = b.payload.length / (double) s1.length;
        if (ratio < 1.5 || ratio > 4.0) { failures++; System.out.println("FAIL light payload ratio out of band"); }

        System.out.println("payload=" + b.payload.length + "B compressed=" + s1.length + "B ratio=" + String.format("%.2f", ratio));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
