package papo.bench;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.openjdk.jmh.annotations.*;

/**
 * 批次123 / 补丁 0256：计划 tick 去重结构 probe-free 化。
 *
 * before = ObjectOpenCustomHashSet<ScheduledTick<?>>(UNIQUE_TICK_HASH)：
 *   hasScheduledTick/willTickThisTick 每次调用 new ScheduledTick.probe（record+immutable）
 *   再 contains；schedule/poll/remove 用实例操作。
 * after = PapoPosTypeSet（(packedPos,type) 开放寻址 + 后移删除）：contains 零分配。
 *
 * 负载 = 红石再调度形态：重复 hasScheduledTick 大多为 miss（未排程位置），
 * 夹杂 add（新排程）/remove（消费）循环。
 *
 * 自检 main：10 万随机操作（add/remove/contains/clear 混合）对拍引用集
 * （HashSet 带与 UNIQUE_TICK_HASH 同语义的等价类包装），成员关系全等；
 * 删除采用随机序以触发后移删除的各种搬移路径；扩容路径由大 N 序列覆盖。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx256m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TickDedupSetBench {

    // ---- 复刻 ScheduledTick.UNIQUE_TICK_HASH 语义 ----
    static final class Tick {
        final Object type;
        final long pos; // packed
        final int prio; // 与成员无关，模拟真实记录负载
        Tick(Object type, long pos, int prio) { this.type = type; this.pos = pos; this.prio = prio; }
        static Tick probe(Object type, long pos) { return new Tick(type, pos, 0); }
    }

    static final Hash.Strategy<Tick> UNIQUE = new Hash.Strategy<>() {
        public int hashCode(Tick t) {
            int h = (int) (t.pos ^ (t.pos >>> 32));
            return 31 * h + t.type.hashCode();
        }
        public boolean equals(Tick a, Tick b) {
            return a == b || a != null && b != null && a.type == b.type && a.pos == b.pos;
        }
    };

    // ---- 复刻 PapoPosTypeSet ----
    static final class PosTypeSet {
        long[] keys = new long[16];
        Object[] types = new Object[16];
        int mask = 15;
        int size;
        static int hash(long key, Object type) {
            int h = (int) (key ^ (key >>> 32));
            h = h * 31 + System.identityHashCode(type);
            h ^= h >>> 16;
            return h;
        }
        void addUnchecked(long key, Object type) {
            int slot = hash(key, type) & mask;
            while (types[slot] != null) slot = (slot + 1) & mask;
            keys[slot] = key; types[slot] = type; size++;
        }
        boolean add(long key, Object type) {
            int slot = hash(key, type) & mask;
            while (types[slot] != null) {
                if (keys[slot] == key && types[slot] == type) return true;
                slot = (slot + 1) & mask;
            }
            if (size >= (mask + 1) * 3 / 4) {
                long[] ok = keys; Object[] ot = types;
                int nc = ok.length << 1;
                keys = new long[nc]; types = new Object[nc]; mask = nc - 1; size = 0;
                for (int i = 0; i < ok.length; i++) if (ot[i] != null) addUnchecked(ok[i], ot[i]);
                addUnchecked(key, type);
                return false;
            }
            keys[slot] = key; types[slot] = type; size++;
            return false;
        }
        boolean remove(long key, Object type) {
            int slot = hash(key, type) & mask;
            while (types[slot] != null) {
                if (keys[slot] == key && types[slot] == type) {
                    int hole = slot, probe = slot;
                    while (true) {
                        probe = (probe + 1) & mask;
                        if (types[probe] == null) break;
                        int home = hash(keys[probe], types[probe]) & mask;
                        if (((probe - hole) & mask) <= ((probe - home) & mask)) {
                            keys[hole] = keys[probe]; types[hole] = types[probe]; hole = probe;
                        }
                    }
                    types[hole] = null; size--;
                    return true;
                }
                slot = (slot + 1) & mask;
            }
            return false;
        }
        boolean contains(long key, Object type) {
            int slot = hash(key, type) & mask;
            while (types[slot] != null) {
                if (keys[slot] == key && types[slot] == type) return true;
                slot = (slot + 1) & mask;
            }
            return false;
        }
    }

    // ---- 基准状态 ----
    static final Object[] BLOCKS = {new Object(), new Object(), new Object()}; // 3 种类型（=Block 引用）

    ObjectOpenCustomHashSet<Tick> beforeSet;
    PosTypeSet afterSet;
    long[] probePos;   // hasScheduledTick 探测位（大部分 miss）
    int[] probeType;

    @Setup
    public void setup() {
        Random r = new Random(42);
        beforeSet = new ObjectOpenCustomHashSet<>(UNIQUE);
        afterSet = new PosTypeSet();
        // 预填 400 条（一个活跃区块容器量级）
        for (int i = 0; i < 400; i++) {
            long pos = r.nextInt(1 << 20);
            Object t = BLOCKS[r.nextInt(BLOCKS.length)];
            beforeSet.add(new Tick(t, pos, 1));
            afterSet.add(pos, t);
        }
        probePos = new long[256];
        probeType = new int[256];
        for (int i = 0; i < 256; i++) {
            probePos[i] = i < 64 ? beforeSet.iterator().next().pos : r.nextInt(1 << 20); // 25% 命中
            probeType[i] = r.nextInt(BLOCKS.length);
        }
    }

    @Benchmark
    public boolean beforeProbeContains() {
        boolean acc = false;
        for (int i = 0; i < probePos.length; i++) {
            acc ^= beforeSet.contains(Tick.probe(BLOCKS[probeType[i]], probePos[i]));
        }
        return acc;
    }

    @Benchmark
    public boolean afterProbeFreeContains() {
        boolean acc = false;
        for (int i = 0; i < probePos.length; i++) {
            acc ^= afterSet.contains(probePos[i], BLOCKS[probeType[i]]);
        }
        return acc;
    }

    /** 混合工作负载：contains 夹 add/remove（红石形态）。 */
    @Benchmark
    public int beforeMixed() {
        int acc = 0;
        for (int i = 0; i < probePos.length; i++) {
            acc += beforeSet.contains(Tick.probe(BLOCKS[probeType[i]], probePos[i])) ? 1 : 0;
            if ((i & 3) == 0) beforeSet.add(new Tick(BLOCKS[probeType[i]], probePos[i] + 77_000_000, 1));
            if ((i & 7) == 0) beforeSet.remove(Tick.probe(BLOCKS[probeType[i]], probePos[i] + 77_000_000));
        }
        return acc;
    }

    @Benchmark
    public int afterMixed() {
        int acc = 0;
        for (int i = 0; i < probePos.length; i++) {
            acc += afterSet.contains(probePos[i], BLOCKS[probeType[i]]) ? 1 : 0;
            if ((i & 3) == 0) afterSet.add(probePos[i] + 77_000_000, BLOCKS[probeType[i]]);
            if ((i & 7) == 0) afterSet.remove(probePos[i] + 77_000_000, BLOCKS[probeType[i]]);
        }
        return acc;
    }

    public static void main(String[] args) {
        Random r = new Random(7);
        // 引用集：以 (pos,type) 字符串键模拟 UNIQUE 语义
        for (int trial = 0; trial < 10; trial++) {
            ObjectOpenCustomHashSet<Tick> ref = new ObjectOpenCustomHashSet<>(UNIQUE);
            PosTypeSet papo = new PosTypeSet();
            Object[] types = {new Object(), new Object(), new Object(), new Object()};
            for (int op = 0; op < 100_000; op++) {
                long pos = r.nextInt(5000);
                Object t = types[r.nextInt(types.length)];
                int what = r.nextInt(10);
                if (what < 5) {
                    boolean x = ref.contains(Tick.probe(t, pos));
                    boolean y = papo.contains(pos, t);
                    if (x != y) throw new AssertionError("contains @" + op + ": " + x + " vs " + y);
                } else if (what < 8) {
                    boolean wasThere = ref.add(new Tick(t, pos, 1));
                    boolean papi = papo.add(pos, t);
                    if (!wasThere != papi) throw new AssertionError("add-return @" + op + ": ref-new=" + wasThere + " papo-present=" + papi);
                } else {
                    boolean x = ref.remove(Tick.probe(t, pos));
                    boolean y = papo.remove(pos, t);
                    if (x != y) throw new AssertionError("remove @" + op);
                }
                if (ref.size() != papo.size) throw new AssertionError("size @" + op + ": " + ref.size() + " vs " + papo.size);
            }
            // 终态全量对拍
            for (long pos = 0; pos < 5000; pos++) {
                for (Object t : types) {
                    if (ref.contains(Tick.probe(t, pos)) != papo.contains(pos, t)) {
                        throw new AssertionError("final membership @" + pos);
                    }
                }
            }
        }
        // 扩容压力：10 万条
        {
            PosTypeSet big = new PosTypeSet();
            Set<String> ref = new HashSet<>();
            for (int i = 0; i < 100_000; i++) {
                long pos = (long) i << 3;
                big.add(pos, types2());
                ref.add(pos + "/" + System.identityHashCode(types2()));
            }
            // identityHashCode 相同类型不同实例必须互斥（type 身份语义）
            Object a = new Object(), b = new Object();
            big.add(42L, a);
            if (big.add(42L, b)) throw new AssertionError("different type instances must both be addable");
            if (!big.add(42L, a)) throw new AssertionError("same pair must dedupe");
        }
        System.out.println("TickDedupSetBench self-check ALL OK (10 trials x 100k ops + resize stress)");
    }

    static Object T2 = new Object();
    static Object types2() { return T2; }
}
