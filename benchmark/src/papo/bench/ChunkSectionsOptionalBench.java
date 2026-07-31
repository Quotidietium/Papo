package papo.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次35: SerializableChunkData sections 循环 Optional 消除。
 * 原实现每 section 约 9 个 Optional：ListTag.getCompound(int) 1 个 + CompoundTag.getCompound("block_states"/"biomes")
 * 各 1(+map/orElseGet 各 1) + getByteArray("BlockLight"/"SkyLight") 各 1(+map 各 1)；
 * 新实现全部为 instanceof 三元，语义复刻私有 getNullable 的界内等价（List.get(i)）。
 * main 自检：两路径对每个 section 的"存在性/取值引用/空分支"逐项一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ChunkSectionsOptionalBench {

    /** NBT 标签语义复刻（仅类型身份）。 */
    static class Tag {}
    static final class CompoundTag extends Tag {
        final Map<String, Tag> tags = new HashMap<>();
        Tag get(String key) { return this.tags.get(key); }
        /** 原实现：getCompound(String) → Optional。 */
        Optional<CompoundTag> getCompound(String key) {
            return this.tags.get(key) instanceof CompoundTag ct ? Optional.of(ct) : Optional.empty();
        }
        /** 原实现：getByteArray(String) → Optional。 */
        Optional<byte[]> getByteArray(String key) {
            return this.tags.get(key) instanceof ByteArrayTag bat ? Optional.of(bat.value) : Optional.empty();
        }
    }
    static final class ByteArrayTag extends Tag {
        final byte[] value;
        ByteArrayTag(byte[] value) { this.value = value; }
    }
    static final class ListTag {
        final List<Tag> list = new ArrayList<>();
        Tag get(int i) { return this.list.get(i); }
        int size() { return this.list.size(); }
        /** 原实现：getCompound(int) → Optional（内部私有 getNullable 界内 == list.get(i)）。 */
        Optional<CompoundTag> getCompound(int i) {
            return this.list.get(i) instanceof CompoundTag ct ? Optional.of(ct) : Optional.empty();
        }
    }

    private final ListTag sections = new ListTag();

    @Setup
    public void setup() {
        // 24 个 section：均含 block_states/biomes/BlockLight/SkyLight（典型存档）
        for (int i = 0; i < 24; i++) {
            CompoundTag section = new CompoundTag();
            section.tags.put("block_states", new CompoundTag());
            section.tags.put("biomes", new CompoundTag());
            section.tags.put("BlockLight", new ByteArrayTag(new byte[2048]));
            section.tags.put("SkyLight", new ByteArrayTag(new byte[2048]));
            this.sections.list.add(section);
        }
    }

    /** 原实现：Optional 链读取一个区块的全部 section 数据。 */
    @Benchmark
    public int before_optionalChains(Blackhole bh) {
        int hash = 1;
        for (int i = 0; i < this.sections.size(); i++) {
            Optional<CompoundTag> compound = this.sections.getCompound(i);
            if (!compound.isEmpty()) {
                CompoundTag tag = compound.get();
                Optional<CompoundTag> bs = tag.getCompound("block_states");
                Optional<CompoundTag> bi = tag.getCompound("biomes");
                byte[] blockLight = tag.getByteArray("BlockLight").map(v -> v).orElse(null);
                byte[] skyLight = tag.getByteArray("SkyLight").map(v -> v).orElse(null);
                hash = hash * 31 + (bs.isPresent() ? 1 : 0) + (bi.isPresent() ? 2 : 0);
                if (blockLight != null) hash ^= blockLight.length;
                if (skyLight != null) hash ^= skyLight.length;
            }
        }
        bh.consume(hash);
        return hash;
    }

    /** 批次35：instanceof 三元直读。 */
    @Benchmark
    public int after_instanceofTernary(Blackhole bh) {
        int hash = 1;
        for (int i = 0; i < this.sections.size(); i++) {
            if (this.sections.get(i) instanceof CompoundTag tag) {
                boolean bs = tag.get("block_states") instanceof CompoundTag;
                boolean bi = tag.get("biomes") instanceof CompoundTag;
                byte[] blockLight = tag.get("BlockLight") instanceof ByteArrayTag bat ? bat.value : null;
                byte[] skyLight = tag.get("SkyLight") instanceof ByteArrayTag bat ? bat.value : null;
                hash = hash * 31 + (bs ? 1 : 0) + (bi ? 2 : 0);
                if (blockLight != null) hash ^= blockLight.length;
                if (skyLight != null) hash ^= skyLight.length;
            }
        }
        bh.consume(hash);
        return hash;
    }

    /** 等价性自检：存在性、取值引用、空分支逐项一致（含缺键 section 与非 Compound 元素）。 */
    public static void main(String[] args) {
        ChunkSectionsOptionalBench bench = new ChunkSectionsOptionalBench();
        bench.setup();
        CompoundTag sparse = new CompoundTag();
        sparse.tags.put("biomes", new CompoundTag()); // 缺 block_states/BlockLight/SkyLight
        bench.sections.list.add(sparse);
        bench.sections.list.add(new Tag()); // 非 Compound 元素

        for (int i = 0; i < bench.sections.size(); i++) {
            Optional<CompoundTag> oc = bench.sections.getCompound(i);
            boolean afterPresent = bench.sections.get(i) instanceof CompoundTag;
            if (!oc.isEmpty() != afterPresent) { System.out.println("MISMATCH presence @" + i); System.exit(1); }
            if (!afterPresent) continue;
            CompoundTag tag = oc.get();
            if (tag != bench.sections.get(i)) { System.out.println("MISMATCH identity @" + i); System.exit(1); }
            for (String key : new String[]{"block_states", "biomes"}) {
                Optional<CompoundTag> o = tag.getCompound(key);
                boolean after = tag.get(key) instanceof CompoundTag;
                if (o.isPresent() != after) { System.out.println("MISMATCH " + key + " presence @" + i); System.exit(1); }
                if (after && o.get() != tag.get(key)) { System.out.println("MISMATCH " + key + " ref @" + i); System.exit(1); }
            }
            for (String key : new String[]{"BlockLight", "SkyLight"}) {
                byte[] before = tag.getByteArray(key).orElse(null);
                byte[] after = tag.get(key) instanceof ByteArrayTag bat ? bat.value : null;
                if (before != after) { System.out.println("MISMATCH " + key + " ref @" + i); System.exit(1); }
            }
        }
        System.out.println("ALL OK");
    }
}
