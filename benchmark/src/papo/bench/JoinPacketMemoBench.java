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
 * 批次74: join 静态大包（recipes/tags）构造缓存 + 编码/压缩双 memo。
 *
 * 每 join 此前各自构造并编码+压缩 ClientboundUpdateRecipesPacket（~100-300KB，0129 量级下
 * level-6 压缩毫秒级/join）；tags/registry 包 0226 已共享实例但仍逐 join 编码+压缩。
 * 本批：recipes 构造缓存（finalizeRecipeLoading→reloadResources 完备失效）+ 三族包 arm
 * 双 memo——每 join 出站成本降到 2×memcpy。
 *
 * 模型：recipes 型载荷 ~150KB（配方集合流：每配方 varint id + 小物品栈模型），压缩比 ~3×。
 *  before = 每 join：编码走查 + DEFLATE + 帧
 *  afterFill = 首 join（+ 编码/压缩双快照）
 *  afterHit = 后续 join：编码 memcpy + 压缩段 memcpy + 帧
 *
 * main 自检：N join 两路径 wire 字节全等 / 编码确定性 / 双快照内容=新鲜编码与压缩。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class JoinPacketMemoBench {

    static final int THRESHOLD = 256;
    static final int JOINS = 10;
    static final int RECIPES = 1200;

    final int[] recipeIds = new int[RECIPES];
    final int[] ingredientIds = new int[RECIPES * 3];
    final int[] componentWords = new int[RECIPES * 3 * 8]; // 每物品栈 8 个组件 varint（重复模式）
    final SharedChunkWireBench.Memo compMemo = new SharedChunkWireBench.Memo();
    byte[] encodedMemo;
    final Deflater benchDeflater = new Deflater();

    @Setup
    public void setup() {
        // 真实形态：1200 配方，成分 item id 来自 ~900 小池（重复多、可压缩），每栈带 8 词
        // 组件流（RecipePropertySet/ItemStack 组件编码形态）→ 载荷 ~130KB、压缩 ~2-3×
        java.util.Random rnd = new java.util.Random(0x30C_74L);
        for (int i = 0; i < RECIPES; i++) this.recipeIds[i] = 1 + rnd.nextInt(1200);
        for (int i = 0; i < this.ingredientIds.length; i++) this.ingredientIds[i] = rnd.nextInt(900);
        int[] compPool = new int[64];
        for (int i = 0; i < compPool.length; i++) compPool[i] = 1 + rnd.nextInt(60000);
        for (int i = 0; i < this.componentWords.length; i++) this.componentWords[i] = compPool[rnd.nextInt(compPool.length)];
    }

    /** 编码走查模型：每配方 varint id + 3 物品栈（id + count + 8 词组件流）。 */
    static byte[] encodeWalk(int[] recipeIds, int[] ingredientIds, int[] componentWords) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(recipeIds.length * 110 + 16);
        SharedChunkWireBench.writeVarInt(out, recipeIds.length);
        for (int i = 0; i < recipeIds.length; i++) {
            SharedChunkWireBench.writeVarInt(out, recipeIds[i]);
            for (int s = 0; s < 3; s++) {
                int idx = i * 3 + s;
                SharedChunkWireBench.writeVarInt(out, ingredientIds[idx]);
                out.write(1); // count
                for (int w = 0; w < 8; w++) {
                    SharedChunkWireBench.writeVarInt(out, componentWords[idx * 8 + w]);
                }
            }
        }
        return out.toByteArray();
    }

    @Benchmark
    public void before(final Blackhole bh) {
        bh.consume(SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(this.benchDeflater, encodeWalk(this.recipeIds, this.ingredientIds, this.componentWords))));
    }

    @Benchmark
    public void afterFill(final Blackhole bh) {
        byte[] encoded = encodeWalk(this.recipeIds, this.ingredientIds, this.componentWords);
        this.encodedMemo = encoded;
        byte[] segment = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
        this.compMemo.store(THRESHOLD, segment);
        bh.consume(SharedChunkWireBench.frame(segment));
    }

    @Benchmark
    public void afterHit(final Blackhole bh) {
        byte[] encoded = this.encodedMemo != null ? this.encodedMemo : encodeWalk(this.recipeIds, this.ingredientIds, this.componentWords);
        byte[] cached = this.compMemo.segmentFor(THRESHOLD);
        if (cached == null) {
            cached = SharedChunkWireBench.deflateSegment(this.benchDeflater, encoded);
            this.compMemo.store(THRESHOLD, cached);
        }
        bh.consume(SharedChunkWireBench.frame(cached));
    }

    public static void main(String[] args) {
        JoinPacketMemoBench b = new JoinPacketMemoBench();
        b.setup();
        int failures = 0;
        Deflater d = new Deflater();

        byte[] e1 = encodeWalk(b.recipeIds, b.ingredientIds, b.componentWords);
        byte[] e2 = encodeWalk(b.recipeIds, b.ingredientIds, b.componentWords);
        if (!Arrays.equals(e1, e2)) { failures++; System.out.println("FAIL encode determinism"); }
        b.encodedMemo = e1;

        for (int v = 0; v < JOINS; v++) {
            byte[] before = SharedChunkWireBench.frame(SharedChunkWireBench.deflateSegment(d, encodeWalk(b.recipeIds, b.ingredientIds, b.componentWords)));
            byte[] cached = b.compMemo.segmentFor(THRESHOLD);
            if (cached == null) {
                cached = SharedChunkWireBench.deflateSegment(d, b.encodedMemo);
                b.compMemo.store(THRESHOLD, cached);
            }
            if (!Arrays.equals(before, SharedChunkWireBench.frame(cached))) { failures++; System.out.println("FAIL join " + v); }
        }

        byte[] seg = SharedChunkWireBench.deflateSegment(d, e1);
        double ratio = e1.length / (double) seg.length;
        if (ratio < 1.5 || ratio > 8.0) { failures++; System.out.println("FAIL ratio out of band"); }

        System.out.println("encoded=" + e1.length + "B compressed=" + seg.length + "B ratio=" + String.format("%.2f", ratio));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
