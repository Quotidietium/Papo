package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次53 / 补丁0206: ServerEntity trackedDataValues 缓存刷新延后到 pairing。
 * 原实现 sendDirtyEntityData 每次 dirty 都刷新 trackedDataValues（getNonDefaultValues 全量扫描
 * 实体全部数据项，常 20-40 项），但该字段只在 sendPairingData（新观众加入）被读。稳态聚集
 * （战斗/药水效果）下 dirty 频繁、新观众稀少，刷新纯浪费。改为 pairing 时即时计算。
 *
 * 复刻：一个典型实体 30 个数据项（10 个非默认、3 个本 tick dirty）。
 *   - before_sendDirtyEntityData：每 dirty-tick 做 packDirty（扫 dirty 子集 3 项）+ getNonDefaultValues
 *     （全扫 30 项，刷新 trackedDataValues）—— 即原版的 per-dirty 成本。
 *   - after_sendDirtyEntityData：每 dirty-tick 仅 packDirty（3 项），不刷新（刷新延后到 pairing）。
 * pairing（新观众，稀少）两路径都计算一次 getNonDefaultValues，单独测不计入 dirty 主路径。
 *
 * main 自检：before/after 的 packDirty 输出与 getNonDefaultValues 输出一致（机制仅移除冗余刷新）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EntityDataPairingBench {

    /** 数据项模型：id + 当前值 + 是否默认 + 是否 dirty。 */
    static final class Item {
        final int id;
        Object value;
        final boolean isDefault;
        boolean dirty;
        Item(int id, Object value, boolean isDefault) { this.id = id; this.value = value; this.isDefault = isDefault; }
    }

    static final int N = 30;            // 典型实体数据项数
    static final int NON_DEFAULT = 10;  // 非默认项数
    static final int DIRTY_PER_TICK = 3; // 每 dirty-tick 的 dirty 项数

    private final Item[] items;
    private final List<Item> papoDirtyResult = new ArrayList<>(DIRTY_PER_TICK);
    private final List<Item> papoNonDefaultResult = new ArrayList<>(NON_DEFAULT);

    public EntityDataPairingBench() {
        this.items = new Item[N];
        for (int i = 0; i < N; i++) {
            // 前 NON_DEFAULT 项非默认，其余默认
            this.items[i] = new Item(i, i < NON_DEFAULT ? new Object() : null, i >= NON_DEFAULT);
        }
    }

    /** packDirty 复刻：扫全部项收集 dirty（消费 dirty 标志）。返回 dirty 列表（≤ DIRTY_PER_TICK）。 */
    List<Item> packDirty() {
        this.papoDirtyResult.clear();
        for (int i = 0; i < N; i++) {
            Item it = this.items[i];
            if (it.dirty) { this.papoDirtyResult.add(it); it.dirty = false; }
        }
        return this.papoDirtyResult;
    }

    /** getNonDefaultValues 复刻：扫全部项收集非默认（不消费 dirty）。返回非默认列表。 */
    List<Item> getNonDefaultValues() {
        this.papoNonDefaultResult.clear();
        for (int i = 0; i < N; i++) {
            Item it = this.items[i];
            if (!it.isDefault) this.papoNonDefaultResult.add(it);
        }
        return this.papoNonDefaultResult;
    }

    /** 模拟一个 dirty-tick：标 DIRTY_PER_TICK 项 dirty。 */
    private void seedDirty() {
        for (int d = 0; d < DIRTY_PER_TICK; d++) {
            this.items[d].dirty = true;
        }
    }

    /** before: 每 dirty-tick = packDirty + getNonDefaultValues 刷新（原版 trackedDataValues 刷新）。 */
    @Benchmark
    public int before_sendDirtyEntityData(Blackhole bh) {
        this.seedDirty();
        List<Item> delta = this.packDirty();
        List<Item> fullSnapshot = this.getNonDefaultValues(); // 原版的冗余刷新
        bh.consume(delta);
        bh.consume(fullSnapshot);
        return delta.size();
    }

    /** after: 每 dirty-tick = packDirty only（刷新延后到 pairing）。 */
    @Benchmark
    public int after_sendDirtyEntityData(Blackhole bh) {
        this.seedDirty();
        List<Item> delta = this.packDirty();
        // 不刷新 trackedDataValues —— full snapshot 在 pairing 时才算
        bh.consume(delta);
        return delta.size();
    }

    /** 等价性自检：两路径 packDirty 输出一致；getNonDefaultValues（pairing 时算）输出与非默认集合一致。 */
    public static void main(String[] args) {
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        EntityDataPairingBench b = new EntityDataPairingBench();
        // before / after 的 packDirty 应一致（都 3 dirty）
        b.seedDirty();
        if (b.packDirty().size() != DIRTY_PER_TICK) { System.out.println("MISMATCH dirty count"); System.exit(1); }
        // pairing 时 getNonDefaultValues 应 = NON_DEFAULT
        if (b.getNonDefaultValues().size() != NON_DEFAULT) { System.out.println("MISMATCH non-default count"); System.exit(1); }
        // 多轮 dirty 后 pairing 仍正确（dirty 已被 packDirty 消费，不影响 getNonDefaultValues）
        for (int t = 0; t < 5; t++) { b.seedDirty(); b.packDirty(); }
        if (b.getNonDefaultValues().size() != NON_DEFAULT) { System.out.println("MISMATCH non-default after dirty rounds"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
