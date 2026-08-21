package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次70: 多观众区块包构造缓存（LevelChunk 每实例版本计数 + 序列化 heightmaps/buffer 跨观众复用）。
 *
 * 成本模型复刻 ClientboundLevelChunkPacketData 构造的三段负载（info=null、anti-xray 关闭路径）：
 *  A) heightmaps：HashMap 填充 6 个条目（sendToClient 的类型数），每条目 clone long[37]
 *     （256 列 × ceil(log2(height)) 位 ≈ 36-37 longs，vanilla getRawData().clone()）。
 *  B) buffer：24 section 的调色板序列化——每 section writeShort(非空计数) + 调色板 varint 头 +
 *     逐调色板项 varint 全局 id + 位存储 long[]（varint 长度 + 逐 long 写入）；空 section 走
 *     单值调色板（数十字节），非空 section（模型 12 项调色板、4 bit/项 → 256 longs = 2KB）。
 *  C) blockEntitiesData：4 个方块实体，每个 getUpdateTag（模型分配）+ BlockEntityInfo 包装。
 *
 * before = 每次发送完整构造 A+B+C（vanilla：每观众每 chunk 一次全量序列化）。
 * afterHit = 版本校验命中，A/B 引用复用，仅 C 重建（BE 内容随时间变化，不能缓存——与补丁语义一致）。
 * afterMiss = 首个观众：完整构造 + 存缓存（≈ before，报告对照）。
 * 光照数据两路径均每次新鲜构造（ClientboundLightUpdatePacketData memcpy），不在模型内、两侧同价。
 *
 * main 自检：序列化确定性 / 缓存命中字节恒等 / 版本递增失效 / BE 列表内容一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ChunkPacketCacheBench {

    // ---- 模型常量（对齐 1.21.11 默认主世界：16 section 数据 + 高度图 6 类型 + 4 BE）----
    static final int SECTIONS = 24;           // lightSection 维度无关，数据 section 混合如下
    static final int NON_EMPTY_SECTIONS = 8;  // 地表型 chunk：约 8 个非空 section
    static final int PALETTE_SIZE = 12;       // 石/土/草/矿混合调色板
    static final int STORAGE_BITS = 4;        // ceil(log2(12)) = 4
    static final int STORAGE_LONGS = 4096 * STORAGE_BITS / 64; // 256 longs = 2KB
    static final int HEIGHTMAP_TYPES = 6;
    static final int HEIGHTMAP_LONGS = 37;
    static final int BLOCK_ENTITIES = 4;

    /** section 模型：非空计数 + 调色板全局 id + 位存储。 */
    static final class Section {
        final short nonEmptyCount;
        final int[] palette;      // 全局 id（varint 编码）
        final long[] storage;     // 位存储（非空 section）；空 section 为 null
        Section(short nonEmptyCount, int[] palette, long[] storage) {
            this.nonEmptyCount = nonEmptyCount;
            this.palette = palette;
            this.storage = storage;
        }
    }

    /** 方块实体模型：getUpdateTag 每次分配一个小 tag 对象。 */
    static final class ModelBlockEntity {
        final int type;
        ModelBlockEntity(int type) { this.type = type; }
        ModelTag getUpdateTag() { return new ModelTag(this.type); }
    }

    static final class ModelTag {
        final int type;
        ModelTag(int type) { this.type = type; }
    }

    static final class ModelBlockEntityInfo {
        final int packedXZ;
        final int y;
        final int type;
        final ModelTag tag;
        ModelBlockEntityInfo(int packedXZ, int y, int type, ModelTag tag) {
            this.packedXZ = packedXZ;
            this.y = y;
            this.type = type;
            this.tag = tag;
        }
    }

    /** heightmap 类型模型（仅枚举身份）。 */
    enum HmType { MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE, T1, T2 }

    Section[] sections;
    long[][] heightmapsRaw;           // 6 × long[37]
    ModelBlockEntity[] blockEntities;

    /** 每实例"chunk"缓存状态（对应 LevelChunk 字段）。 */
    final AtomicLong version = new AtomicLong();
    long cacheVersion = Long.MIN_VALUE;
    Map<HmType, long[]> cachedHeightmaps;
    byte[] cachedBuffer;

    @Setup
    public void setup() {
        this.sections = new Section[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            if (i < NON_EMPTY_SECTIONS) {
                int[] palette = new int[PALETTE_SIZE];
                for (int j = 0; j < PALETTE_SIZE; j++) {
                    palette[j] = 1 + j * 13; // 模型全局 id（varint 1-2 字节）
                }
                long[] storage = new long[STORAGE_LONGS];
                for (int j = 0; j < STORAGE_LONGS; j++) {
                    storage[j] = 0x9E3779B97F4A7C15L * (j + 1) + i; // 非平凡位模式
                }
                this.sections[i] = new Section((short) 3000, palette, storage);
            } else {
                // 空 section：单值调色板（vanilla air 全空 → 0 号调色板 + 1 个全局 id）
                this.sections[i] = new Section((short) 0, new int[] {0}, null);
            }
        }
        this.heightmapsRaw = new long[HEIGHTMAP_TYPES][HEIGHTMAP_LONGS];
        for (int t = 0; t < HEIGHTMAP_TYPES; t++) {
            for (int j = 0; j < HEIGHTMAP_LONGS; j++) {
                this.heightmapsRaw[t][j] = 0x2545F4914F6CDD1DL * (j + 1) + t;
            }
        }
        this.blockEntities = new ModelBlockEntity[BLOCK_ENTITIES];
        for (int i = 0; i < BLOCK_ENTITIES; i++) {
            this.blockEntities[i] = new ModelBlockEntity(100 + i);
        }
    }

    // ---- 序列化原语（复刻 FriendlyByteBuf 语义）----

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /** A) heightmaps 填充（HashMap + clone，复刻 ClientboundLevelChunkPacketData 主构造器）。 */
    static Map<HmType, long[]> buildHeightmaps(long[][] raw) {
        Map<HmType, long[]> map = new HashMap<>();
        for (int t = 0; t < raw.length; t++) {
            map.put(HmType.values()[t], raw[t].clone());
        }
        return map;
    }

    /** B) 全 section 序列化（短计数 + 调色板 varint + 位存储逐 long 写入）。 */
    static byte[] serializeSections(Section[] sections) {
        ByteBuf buf = Unpooled.buffer(24 * 1024);
        for (Section section : sections) {
            buf.writeShort(section.nonEmptyCount);
            if (section.storage == null) {
                writeVarInt(buf, 0);            // 单值调色板 tag
                writeVarInt(buf, section.palette[0]);
            } else {
                writeVarInt(buf, 1);            // 线性调色板 tag
                writeVarInt(buf, section.palette.length);
                for (int id : section.palette) {
                    writeVarInt(buf, id);
                }
                writeVarInt(buf, section.storage.length);
                for (long v : section.storage) {
                    buf.writeLong(v);
                }
            }
            writeVarInt(buf, 0);                // 生物群系单值调色板（常态）
            writeVarInt(buf, 5);                // plains 模型 id
        }
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** C) BE 列表（每次新鲜：getUpdateTag + info 包装）。 */
    static List<ModelBlockEntityInfo> buildBlockEntityData(ModelBlockEntity[] blockEntities) {
        List<ModelBlockEntityInfo> list = new ArrayList<>(blockEntities.length);
        for (ModelBlockEntity be : blockEntities) {
            list.add(new ModelBlockEntityInfo(be.type & 0xFF, 64, be.type, be.getUpdateTag()));
        }
        return list;
    }

    boolean cacheValid() {
        return this.cachedBuffer != null && this.cacheVersion == this.version.get();
    }

    // ---- 基准 ----

    /** before：每观众完整构造（heightmaps + buffer 序列化 + BE）。 */
    @Benchmark
    public void before(final Blackhole bh) {
        bh.consume(buildHeightmaps(this.heightmapsRaw));
        bh.consume(serializeSections(this.sections));
        bh.consume(buildBlockEntityData(this.blockEntities));
    }

    /** after（命中）：版本校验 + A/B 复用 + BE 重建。 */
    @Benchmark
    public void afterHit(final Blackhole bh) {
        if (this.cacheValid()) {
            bh.consume(this.cachedHeightmaps);
            bh.consume(this.cachedBuffer);
        } else {
            Map<HmType, long[]> hm = buildHeightmaps(this.heightmapsRaw);
            byte[] buffer = serializeSections(this.sections);
            this.cachedHeightmaps = hm;
            this.cachedBuffer = buffer;
            this.cacheVersion = this.version.get();
            bh.consume(hm);
            bh.consume(buffer);
        }
        bh.consume(buildBlockEntityData(this.blockEntities));
    }

    /** after（未命中/首个观众）：完整构造 + 存缓存（对照，≈ before）。 */
    @Benchmark
    public void afterMiss(final Blackhole bh) {
        Map<HmType, long[]> hm = buildHeightmaps(this.heightmapsRaw);
        byte[] buffer = serializeSections(this.sections);
        bh.consume(buildBlockEntityData(this.blockEntities));
        this.cachedHeightmaps = hm;
        this.cachedBuffer = buffer;
        this.cacheVersion = this.version.get();
    }

    // ---- main 自检（无 JMH 运行时；不构造 Blackhole，body 用 sink 汇）----
    Object sink;

    void bodyBefore() {
        this.sink = buildHeightmaps(this.heightmapsRaw);
        this.sink = serializeSections(this.sections);
        this.sink = buildBlockEntityData(this.blockEntities);
    }

    void bodyAfterHit() {
        if (this.cacheValid()) {
            this.sink = this.cachedHeightmaps;
            this.sink = this.cachedBuffer;
        } else {
            Map<HmType, long[]> hm = buildHeightmaps(this.heightmapsRaw);
            byte[] buffer = serializeSections(this.sections);
            this.cachedHeightmaps = hm;
            this.cachedBuffer = buffer;
            this.cacheVersion = this.version.get();
            this.sink = hm;
            this.sink = buffer;
        }
        this.sink = buildBlockEntityData(this.blockEntities);
    }

    public static void main(String[] args) {
        ChunkPacketCacheBench b = new ChunkPacketCacheBench();
        b.setup();
        int failures = 0;

        // 1) 序列化确定性：两次独立序列化字节全等
        byte[] s1 = serializeSections(b.sections);
        byte[] s2 = serializeSections(b.sections);
        if (!java.util.Arrays.equals(s1, s2)) { failures++; System.out.println("FAIL determinism"); }

        // 2) 缓存命中复用字节恒等：第二次（命中）返回同一数组实例，内容与新鲜序列化逐字节相同
        b.bodyAfterHit();                 // 首次 = miss，填充缓存
        if (!b.cacheValid()) { failures++; System.out.println("FAIL cache not valid after store"); }
        byte[] storedBuf = b.cachedBuffer;
        if (!java.util.Arrays.equals(storedBuf, s1)) { failures++; System.out.println("FAIL stored buffer bytes differ from fresh serialization"); }
        b.bodyAfterHit();                 // 第二次 = hit
        if (b.cachedBuffer != storedBuf) { failures++; System.out.println("FAIL hit did not reuse stored buffer"); }

        // 3) 版本递增失效：bump 后 cacheValid 为 false，再次发送重建并复用新字节
        b.version.getAndIncrement();
        if (b.cacheValid()) { failures++; System.out.println("FAIL stale cache considered valid after bump"); }
        long[] mutated = b.sections[0].storage;
        if (mutated != null) { mutated[0] ^= 1L; }   // 模拟方块变更
        b.bodyAfterHit();
        if (!b.cacheValid()) { failures++; System.out.println("FAIL cache not valid after rebuild"); }
        byte[] s3 = serializeSections(b.sections);
        if (!java.util.Arrays.equals(b.cachedBuffer, s3)) { failures++; System.out.println("FAIL rebuilt bytes differ from fresh serialization"); }

        // 4) BE 列表内容一致（每次新鲜构造，内容相同）
        List<ModelBlockEntityInfo> l1 = buildBlockEntityData(b.blockEntities);
        List<ModelBlockEntityInfo> l2 = buildBlockEntityData(b.blockEntities);
        if (l1.size() != l2.size()) { failures++; System.out.println("FAIL BE list size"); }
        for (int i = 0; i < l1.size(); i++) {
            if (l1.get(i).type != l2.get(i).type || l1.get(i).tag.type != l2.get(i).tag.type) {
                failures++; System.out.println("FAIL BE content at " + i);
            }
        }

        // 5) before 路径可运行（sanity）
        b.bodyBefore();

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
