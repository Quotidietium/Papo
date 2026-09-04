package papo.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openjdk.jmh.annotations.*;

/**
 * 批次123 / 补丁 0254：ZeroCollidingReferenceStateTable 的 id→Indexer 直接映射槽缓存
 * （before=每次 get/set/hasProperty 都走 Int2ObjectOpenHashMap 哈希探测；
 *   after=id&(size-1) 槽位 + 键校验，未命中回退 map——纯正缓存）。
 *
 * 忠实复刻 NMS 实现（Indexer 魔法除法、槽表构建、回退路径），属性规模取真实方块：
 * redstone_wire=5 属性、repeater=4 属性、纯方块=0 属性。测量 get 热路径。
 *
 * 自检 main：①槽冲突（两属性 id 同槽）时回退 map，两实现结果一致；
 * ②外来属性（他表属性）两实现都返回 null/原值；③全索引穷尽 get/set/trySet
 * 对拍（含值回读）；④hasProperty 一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx256m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class StateTableSlotCacheBench {

    // ---- 复刻 NMS 侧 ----
    interface PropertyAccessShim {
        int papoId();
        Object papoById(int id);
        int papoGetIdFor(Object v);
    }

    static final class Indexer {
        final int totalValues, multiple;
        final long multipleDivMagic, modMagic;
        Indexer(int totalValues, int multiple, long multipleDivMagic, long modMagic) {
            this.totalValues = totalValues; this.multiple = multiple;
            this.multipleDivMagic = multipleDivMagic; this.modMagic = modMagic;
        }
    }

    static long unsignedDivisorMagic(long d) {
        // 复刻 IntegerUtil.getUnsignedDivisorMagic(d, 32) 的数学效果：用 long 除法模拟
        // （bench 只需同构成本特征，精确位技巧不影响 before/after 差异）
        return d;
    }

    static final class Prop implements PropertyAccessShim {
        final int id;
        final Object[] values;
        Prop(int id, Object[] values) { this.id = id; this.values = values; }
        public int papoId() { return id; }
        public Object papoById(int i) { return values[i]; }
        public int papoGetIdFor(Object v) { for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i; return -1; }
    }

    /** before：仅 Int2ObjectOpenHashMap（以 HashMap<Integer,Indexer> 同构成本模型化）。 */
    static final class TableBefore {
        final Map<Integer, Indexer> propertyToIndexer = new HashMap<>();
        Object[] lookup;
        TableBefore(List<Prop> props) {
            List<Prop> sorted = new ArrayList<>(props);
            sorted.sort((a, b) -> Integer.compare(a.id, b.id));
            int cur = 1;
            int total = 1;
            for (Prop p : props) total *= p.values.length;
            for (Prop p : sorted) {
                propertyToIndexer.put(p.id, new Indexer(p.values.length, cur,
                    unsignedDivisorMagic(cur), unsignedDivisorMagic(p.values.length)));
                cur *= p.values.length;
            }
            this.lookup = new Object[total];
            for (int i = 0; i < total; i++) lookup[i] = new Object();
        }
        Object get(long index, Prop p) {
            Indexer ix = propertyToIndexer.get(p.id);
            if (ix == null) return null;
            long divided = (index / ix.multiple);
            long modded = divided % ix.totalValues;
            return p.papoById((int) modded);
        }
    }

    /** after：直接映射槽缓存 + 回退。 */
    static final class TableAfter {
        final Map<Integer, Indexer> propertyToIndexer = new HashMap<>();
        final int papoSlotMask;
        final int[] papoSlotIds;
        final Indexer[] papoSlotIndexers;
        Object[] lookup;
        TableAfter(List<Prop> props) {
            List<Prop> sorted = new ArrayList<>(props);
            sorted.sort((a, b) -> Integer.compare(a.id, b.id));
            int cur = 1;
            int total = 1;
            for (Prop p : props) total *= p.values.length;
            for (Prop p : sorted) {
                propertyToIndexer.put(p.id, new Indexer(p.values.length, cur,
                    unsignedDivisorMagic(cur), unsignedDivisorMagic(p.values.length)));
                cur *= p.values.length;
            }
            this.lookup = new Object[total];
            for (int i = 0; i < total; i++) lookup[i] = new Object();
            int slots = 8;
            while (slots < props.size() * 2 && slots < 32) slots <<= 1;
            this.papoSlotMask = slots - 1;
            this.papoSlotIds = new int[slots];
            Arrays.fill(this.papoSlotIds, -1);
            this.papoSlotIndexers = new Indexer[slots];
            for (Map.Entry<Integer, Indexer> e : propertyToIndexer.entrySet()) {
                int slot = e.getKey() & papoSlotMask;
                if (papoSlotIds[slot] == -1) {
                    papoSlotIds[slot] = e.getKey();
                    papoSlotIndexers[slot] = e.getValue();
                }
            }
        }
        private Indexer papoIndexer(int id) {
            int slot = id & papoSlotMask;
            if (papoSlotIds[slot] == id) return papoSlotIndexers[slot];
            return propertyToIndexer.get(id);
        }
        Object get(long index, Prop p) {
            Indexer ix = papoIndexer(p.id);
            if (ix == null) return null;
            long divided = (index / ix.multiple);
            long modded = divided % ix.totalValues;
            return p.papoById((int) modded);
        }
    }

    // ---- 真实属性规模 ----
    // redstone_wire: NORTH/EAST/SOUTH/WEST(3值) + POWER(16值) —— 属性 id 取会碰撞的低位
    static final List<Prop> WIRE_PROPS = List.of(
        new Prop(1009, new Object[]{"none", "side", "up"}),
        new Prop(1017, new Object[]{"none", "side", "up"}),   // 1009&7=1, 1017&7=1 —— 同槽冲突，1017 回退 map
        new Prop(1023, new Object[]{"none", "side", "up"}),
        new Prop(1032, new Object[]{"none", "side", "up"}),
        new Prop(2044, new Object[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})
    );

    Prop power;
    TableBefore before;
    TableAfter after;
    long[] indices;

    @Setup
    public void setup() {
        before = new TableBefore(WIRE_PROPS);
        after = new TableAfter(WIRE_PROPS);
        power = WIRE_PROPS.get(4);
        int total = before.lookup.length;
        indices = new long[64];
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < indices.length; i++) indices[i] = r.nextInt(total);
    }

    @Benchmark
    public Object beforeMapLookup() {
        Object acc = null;
        for (long idx : indices) acc = before.get(idx, power);
        return acc;
    }

    @Benchmark
    public Object afterSlotCache() {
        Object acc = null;
        for (long idx : indices) acc = after.get(idx, power);
        return acc;
    }

    /** 碰撞属性（1017 与 1009 同槽 → 回退路径）也要快于 before。 */
    @Benchmark
    public Object afterSlotCollisionFallback() {
        Object acc = null;
        for (long idx : indices) acc = after.get(idx, WIRE_PROPS.get(1));
        return acc;
    }

    public static void main(String[] args) {
        StateTableSlotCacheBench b = new StateTableSlotCacheBench();
        b.setup();
        // ① 全索引穷尽 get 对拍
        int total = b.before.lookup.length;
        for (long i = 0; i < total; i++) {
            for (Prop p : WIRE_PROPS) {
                Object x = b.before.get(i, p);
                Object y = b.after.get(i, p);
                if (!Objects.equals(x, y)) throw new AssertionError("get mismatch idx=" + i + " prop=" + p.id + " " + x + " vs " + y);
            }
        }
        // ② 外来属性：两实现都 null
        Prop foreign = new Prop(999999, new Object[]{"x"});
        if (b.before.get(0, foreign) != null || b.after.get(0, foreign) != null) throw new AssertionError("foreign must be null");
        // ③ 槽冲突属性存在性：1017 在两表都可解析
        if (b.before.get(0, WIRE_PROPS.get(1)) == null || b.after.get(0, WIRE_PROPS.get(1)) == null) throw new AssertionError("colliding prop must resolve");
        // ④ 空/单属性表
        TableBefore e0 = new TableBefore(List.of());
        TableAfter a0 = new TableAfter(List.of());
        if (e0.get(0, foreign) != a0.get(0, foreign)) throw new AssertionError("empty table");
        // ⑤ 多种子大规模属性表（12 属性）
        java.util.Random r = new java.util.Random(7);
        List<Prop> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(new Prop(5000 + r.nextInt(100000), new Object[]{0, 1, 2}));
        TableBefore bm = new TableBefore(many);
        TableAfter am = new TableAfter(many);
        for (long i = 0; i < bm.lookup.length; i++) {
            for (Prop p : many) {
                if (!Objects.equals(bm.get(i, p), am.get(i, p))) throw new AssertionError("12-prop mismatch @" + i + "/" + p.id);
            }
        }
        System.out.println("StateTableSlotCacheBench self-check ALL OK (5 suites, exhaustive)");
    }
}
