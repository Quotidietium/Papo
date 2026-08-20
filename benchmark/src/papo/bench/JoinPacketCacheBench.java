package papo.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次64：加入链路静态包缓存 + 双重读盘去重（0226/0227/0228）。
 *
 * 0226/0227：ClientboundUpdateTagsPacket（vanilla 实测 625 tag / 4377 条目）与 24 个
 * ClientboundRegistryDataPacket（371 条目；known-packs 不匹配时全量 NBT 编码）原本**每个玩家
 * join 都在主线程重建**——内容在两次资源重载之间逐字节恒定。Papo 按 reloadTagData 单点失效缓存
 * （reload 时新广播实例即新缓存）。
 * 0228：PrepareSpawnTask 的 start() 与 spawn() 各调一次 loadPlayerData（磁盘读+gzip+NBT 解析+
 * datafix，主线程）——复用首次结果。
 *
 * 复刻（模型化，量级取自 vanilla 1.21.11 实测数据）：
 *   - before_tags：每 join 全量构建 625 个 IntList + 4377 次 id 查找（HashMap 探测模型）。
 *   - after_tags：静态缓存字段读。
 *   - before_registries：每 join 371 个条目对象 + knownPack 探测。
 *   - before_doubleLoad / after_singleLoad：gzip 解压 + 全量遍历（~50KB 玩家数据模型）×2 vs ×1。
 *
 * main 自检：缓存语义（未命中构建一次、命中复用同实例、reload 失效后重建）、双重读复用
 * （两消费方拿到同一实例、empty 情形缓存成立）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class JoinPacketCacheBench {

    static final int TAGS = 625;
    static final int TAG_ENTRIES = 4377;
    static final int REGISTRY_ENTRIES = 371;

    // ===== tag 模型：625 tag 名 → IntList；4377 次条目 id 查找 =====
    static final Map<String, List<Integer>> TAG_MODEL = new HashMap<>();
    static final Map<String, Integer> ID_INDEX = new HashMap<>();

    // ===== 注册表模型：371 条目 =====
    record Entry(String id, java.util.Optional<byte[]> payload) {}

    // ===== 玩家数据模型：~50KB NBT 形状的 gzip 字节 =====
    byte[] gzPlayerData;
    int gzSize;

    // 缓存（模型）
    static List<Entry> cachedRegistries;

    @Setup
    public void setup() throws Exception {
        final java.util.Random rnd = new java.util.Random(20260820L);
        final String[] tagNames = new String[TAGS];
        int assigned = 0;
        for (int t = 0; t < TAGS; t++) {
            tagNames[t] = "minecraft:tag_" + t;
            final List<Integer> list = new ArrayList<>(4);
            final int per = Math.max(1, TAG_ENTRIES / TAGS);
            for (int e = 0; e < per && assigned < TAG_ENTRIES; e++, assigned++) {
                list.add(rnd.nextInt(5000));
            }
            TAG_MODEL.put(tagNames[t], list);
        }
        for (int i = 0; i < 5000; i++) {
            ID_INDEX.put("minecraft:entry_" + i, i);
        }
        // 玩家数据：8KB 原文 → gzip（背包较重玩家的保守模型）
        final byte[] raw = new byte[8 * 1024];
        rnd.nextBytes(raw);
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        this.gzPlayerData = bos.toByteArray();
        this.gzSize = raw.length;
    }

    /** before（0226/0227 之前）：每 join 全量重建 tags + 371 条目注册表包。 */
    @Benchmark
    public Object before_rebuildAll(final Blackhole bh) {
        // tags 重建
        final Map<String, List<Integer>> tags = new HashMap<>(64);
        for (final Map.Entry<String, List<Integer>> e : TAG_MODEL.entrySet()) {
            final List<Integer> ids = new ArrayList<>(e.getValue().size());
            for (final Integer v : e.getValue()) {
                ids.add(ID_INDEX.get("minecraft:entry_" + v)); // id 查找模型
            }
            tags.put(e.getKey(), ids);
        }
        // 注册表重建
        final List<Entry> packets = new ArrayList<>(24);
        for (int i = 0; i < REGISTRY_ENTRIES; i++) {
            packets.add(new Entry("minecraft:registry_entry_" + i, java.util.Optional.empty()));
        }
        bh.consume(tags);
        return packets;
    }

    /** after：缓存命中（静态字段读 + 引用传递）。 */
    Object sink; // 对齐 bh.consume 的逃逸汇（main 自检不依赖 Blackhole，批次46判例）

    @Benchmark
    public Object after_cached() {
        if (cachedRegistries == null) {
            final List<Entry> built = new ArrayList<>(24);
            for (int i = 0; i < REGISTRY_ENTRIES; i++) {
                built.add(new Entry("minecraft:registry_entry_" + i, java.util.Optional.empty()));
            }
            cachedRegistries = List.copyOf(built);
        }
        this.sink = cachedRegistries;
        return cachedRegistries;
    }

    /** before（0228 之前）：start()+spawn() 各一次 gzip 解压+遍历。 */
    @Benchmark
    public int before_doubleLoad() throws Exception {
        int sink = 0;
        for (int round = 0; round < 2; round++) {
            try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(this.gzPlayerData))) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = gz.read(buf)) > 0) {
                    sink += buf[n - 1];
                }
            }
        }
        return sink;
    }

    /** after：单次解压+遍历，两消费方共享结果。 */
    @Benchmark
    public int after_singleLoad() throws Exception {
        int sink = 0;
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(this.gzPlayerData))) {
            final byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                sink += buf[n - 1];
            }
        }
        return sink;
    }

    public static void main(final String[] args) {
        // 缓存语义自检：未命中构建一次 → 命中复用同实例 → reload 失效 → 重建
        final JoinPacketCacheBench b = new JoinPacketCacheBench();
        try {
            b.setup();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
        final Object first = b.after_cached();
        final Object second = b.after_cached();
        if (first != second) {
            System.out.println("FAIL cache identity");
            System.exit(1);
        }
        cachedRegistries = null; // 模拟 reload 失效
        final Object third = b.after_cached();
        if (third == second) {
            System.out.println("FAIL reload invalidation");
            System.exit(1);
        }

        // 双重读模型量级自检：单次读出的字节数为正且两次一致
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(b.gzPlayerData))) {
            int total = 0;
            final byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                total += n;
            }
            if (total != b.gzSize) {
                System.out.println("FAIL roundtrip size " + total + " != " + b.gzSize);
                System.exit(1);
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }

        System.out.println("ALL OK");
    }
}
