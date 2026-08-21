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
 * 批次75: join 玩家信息广播压缩/编码 memo。
 *
 * 每次 join，PlayerList 把同一 ClientboundPlayerInfoUpdatePacket 实例（新玩家条目：UUID +
 * profile 名 + 皮肤 properties 签名，~0.5-1.5KB，超压缩阈值）发给每个在线玩家——每连接各自
 * 编码 + DEFLATE 相同字节。unlisted 变体此前更在循环内对每个接收者重复构造同参数包。
 *
 * 模型：单玩家条目 ~1.2KB（UUID 16B + 名 varint + textures property：签名值 ~700B base64 形
 * 变长流 + 显示名组件），压缩比 ~1.6×；N=30 在线观众。
 *  before = 每观众：编码走查 + DEFLATE + 帧（另：unlisted 每观众重新构造）
 *  afterFill = 首观众（+ 双快照）
 *  afterHit = 后续观众：编码 memcpy + 压缩段 memcpy + 帧
 *
 * main 自检：N 观众两路径 wire 字节全等 / 编码确定性 / 压缩比带内。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PlayerInfoBroadcastBench {

    static final int THRESHOLD = 256;
    static final int VIEWERS = 30;
    static final int SKIN_WORDS = 700; // 皮肤签名值模型：~700 词（真实 textures 签名 ~700-1400 base64 字符）

    int[] skinWords;
    final SharedChunkWireBench.Memo compMemo = new SharedChunkWireBench.Memo();
    byte[] encodedMemo;
    final Deflater benchDeflater = new Deflater();

    @Setup
    public void setup() {
        java.util.Random rnd = new java.util.Random(0x91A_75L);
        // base64 值域（A-Za-z0-9+/）→ varint 词 1-2 字节、熵高但有字符集结构性
        this.skinWords = new int[SKIN_WORDS];
        for (int i = 0; i < SKIN_WORDS; i++) {
            this.skinWords[i] = 65 + rnd.nextInt(64);
        }
    }

    /** 编码走查模型：actions varint + 条目数 + UUID 16B + 名 + textures property 变长流。 */
    static byte[] encodeWalk(int[] skinWords) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1300);
        SharedChunkWireBench.writeVarInt(out, 1);  // ADD_PLAYER action 位集
        SharedChunkWireBench.writeVarInt(out, 1);  // 条目数
        for (int i = 0; i < 16; i++) out.write(0x11 + i); // UUID
        SharedChunkWireBench.writeVarInt(out, 8);  // 名长
        out.write(0x50); out.write(0x61); out.write(0x70); out.write(0x6F); out.write(0x50); out.write(0x6C); out.write(0x61); out.write(0x79); // "PapoPlay"
        SharedChunkWireBench.writeVarInt(out, skinWords.length * 2 + 40); // property 值长
        for (int w : skinWords) SharedChunkWireBench.writeVarInt(out, w);
        SharedChunkWireBench.writeVarInt(out, 0);  // 无显示名
        return out.toByteArray();
    }

    @Benchmark
    public void before(final Blackhole bh) {
        bh.consume(SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(this.benchDeflater, encodeWalk(this.skinWords))));
    }

    @Benchmark
    public void afterFill(final Blackhole bh) {
        byte[] encoded = encodeWalk(this.skinWords);
        this.encodedMemo = encoded;
        byte[] segment = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
        this.compMemo.store(THRESHOLD, segment);
        bh.consume(SharedChunkWireBench.frame(segment));
    }

    @Benchmark
    public void afterHit(final Blackhole bh) {
        byte[] encoded = this.encodedMemo != null ? this.encodedMemo : encodeWalk(this.skinWords);
        byte[] cached = this.compMemo.segmentFor(THRESHOLD);
        if (cached == null) {
            cached = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
            this.compMemo.store(THRESHOLD, cached);
        }
        bh.consume(SharedChunkWireBench.frame(cached));
    }

    public static void main(String[] args) {
        PlayerInfoBroadcastBench b = new PlayerInfoBroadcastBench();
        b.setup();
        int failures = 0;
        Deflater d = new Deflater();

        byte[] e1 = encodeWalk(b.skinWords);
        if (!Arrays.equals(e1, encodeWalk(b.skinWords))) { failures++; System.out.println("FAIL encode determinism"); }
        b.encodedMemo = e1;

        for (int v = 0; v < VIEWERS; v++) {
            byte[] before = SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(d, encodeWalk(b.skinWords)));
            byte[] cached = b.compMemo.segmentFor(THRESHOLD);
            if (cached == null) {
                cached = SharedChunkWireBench.deflateSegment(d, b.encodedMemo);
                b.compMemo.store(THRESHOLD, cached);
            }
            if (!Arrays.equals(before, SharedChunkWireBench.frame(cached))) { failures++; System.out.println("FAIL viewer " + v); }
        }

        byte[] seg = SharedChunkWireBench.deflateSegment(d, e1);
        double ratio = e1.length / (double) seg.length;
        if (ratio < 1.2 || ratio > 4.0) { failures++; System.out.println("FAIL ratio out of band"); }

        System.out.println("encoded=" + e1.length + "B compressed=" + seg.length + "B ratio=" + String.format("%.2f", ratio));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
