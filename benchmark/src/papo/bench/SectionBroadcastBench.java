package papo.bench;

import java.io.ByteArrayOutputStream;
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
 * 批次73: 编码阶段 memo（PacketEncoder codec 走查复用）+ SectionBlocksUpdate 广播接入。
 *
 * 大红石装置/爆炸时 ChunkHolder.broadcastChanges 把同一 ClientboundSectionBlocksUpdatePacket
 * 实例发给每 chunk 全部追踪玩家；每观众各自执行 varlong 走查编码（每方块 Block.getId 移位 +
 * varlong 编码）+ DEFLATE。本批双 memo：编码快照（首连接编码字节，后续 memcpy）+ 压缩段
 * （0242 机制）——把每观众出站成本打到 memcpy 地板。
 *
 * 模型：480 方块变更（大爆炸/TNT 大炮级批量）→ 编码 ~2KB；threshold=256 之上 → 压缩路径。
 *  before = 每观众 varlong 走查 + DEFLATE + 帧
 *  afterFill = 首观众（+ 编码/压缩两快照）
 *  afterHit = 后续观众 编码 memcpy + 压缩段 memcpy + 帧
 *
 * main 自检：全观众 wire 字节全等 / varlong 编码确定性 / 两 memo 快照内容与新鲜编码全等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SectionBroadcastBench {

    static final int THRESHOLD = 256;
    static final int VIEWERS = 20;
    static final int CHANGES = 480;

    final int[] blockIds = new int[CHANGES];
    final short[] positions = new short[CHANGES];
    final SharedChunkWireBench.Memo compMemo = new SharedChunkWireBench.Memo();
    byte[] encodedMemo; // 编码快照（批73）
    final Deflater benchDeflater = new Deflater();

    @Setup
    public void setup() {
        // 真实红石批量更新：方块 id 来自小调色板（重复性高、可压缩），位置聚簇
        java.util.Random rnd = new java.util.Random(0x5EC_73L);
        int[] palette = new int[14];
        for (int j = 0; j < palette.length; j++) palette[j] = 1 + rnd.nextInt(25000);
        short pos = (short) rnd.nextInt(2048);
        for (int i = 0; i < CHANGES; i++) {
            this.blockIds[i] = palette[rnd.nextInt(palette.length)];
            this.positions[i] = pos;
            pos += 1 + rnd.nextInt(3); // 聚簇步进（跨 4096 回绕）
            if (pos >= 4096) pos = (short) (pos - 4096);
        }
    }

    static void writeVarLong(ByteArrayOutputStream out, long value) {
        // 复刻 FriendlyByteBuf.writeVarLong（7bit 一组，低有效组先行）
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7FL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** varlong 走查编码（ClientboundSectionBlocksUpdatePacket.write 复刻）。 */
    static byte[] encodeWalk(int[] blockIds, short[] positions) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + CHANGES * 5);
        writeVarLong(out, 12345L); // sectionPos 模型（长 varint）
        writeVarInt(out, blockIds.length);
        for (int i = 0; i < blockIds.length; i++) {
            writeVarLong(out, ((long) blockIds[i] << 12) | positions[i]);
        }
        return out.toByteArray();
    }

    @Benchmark
    public void before(final Blackhole bh) {
        byte[] encoded = encodeWalk(this.blockIds, this.positions);
        bh.consume(SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded)));
    }

    @Benchmark
    public void afterFill(final Blackhole bh) {
        byte[] encoded = encodeWalk(this.blockIds, this.positions);
        this.encodedMemo = encoded;
        byte[] segment = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
        this.compMemo.store(THRESHOLD, segment);
        bh.consume(SharedChunkWireBench.frame(segment));
    }

    @Benchmark
    public void afterHit(final Blackhole bh) {
        byte[] encoded = this.encodedMemo != null ? this.encodedMemo : encodeWalk(this.blockIds, this.positions);
        byte[] cached = this.compMemo.segmentFor(THRESHOLD);
        if (cached == null) {
            cached = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
            this.compMemo.store(THRESHOLD, cached);
        }
        bh.consume(SharedChunkWireBench.frame(cached));
    }

    public static void main(String[] args) {
        SectionBroadcastBench b = new SectionBroadcastBench();
        b.setup();
        int failures = 0;
        Deflater d = new Deflater();

        // 1) 编码确定性
        byte[] e1 = encodeWalk(b.blockIds, b.positions);
        byte[] e2 = encodeWalk(b.blockIds, b.positions);
        if (!Arrays.equals(e1, e2)) { failures++; System.out.println("FAIL encode determinism"); }

        // 2) 编码 memo 内容 = 新鲜编码
        b.encodedMemo = e1;
        if (!Arrays.equals(b.encodedMemo, encodeWalk(b.blockIds, b.positions))) { failures++; System.out.println("FAIL encode memo differs"); }

        // 3) N 观众 wire 字节全等（before 全走查+压缩 vs after 双 memo）
        for (int v = 0; v < VIEWERS; v++) {
            byte[] before = SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(d, encodeWalk(b.blockIds, b.positions)));
            byte[] cached = b.compMemo.segmentFor(THRESHOLD);
            if (cached == null) {
                cached = SharedChunkWireBench.deflateSegment(d, b.encodedMemo);
                b.compMemo.store(THRESHOLD, cached);
            }
            if (!Arrays.equals(before, SharedChunkWireBench.frame(cached))) { failures++; System.out.println("FAIL viewer " + v); }
        }

        // 4) 压缩比带内（~1.1KB 高熵 varlong 流，应 >1.3×——可压缩但不夸张）
        byte[] seg = SharedChunkWireBench.deflateSegment(d, e1);
        double ratio = e1.length / (double) seg.length;
        if (ratio < 1.3 || ratio > 8.0) { failures++; System.out.println("FAIL ratio out of band"); }

        System.out.println("encoded=" + e1.length + "B compressed=" + seg.length + "B ratio=" + String.format("%.2f", ratio));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
