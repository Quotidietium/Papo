package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.openjdk.jmh.annotations.*;

/**
 * 批次123 / 补丁 0255：RedStoneWireBlock.getBlockSignal 的粉侧信号拉取特化。
 *
 * before = SignalGetter.getBestNeighborSignal：每方向 BlockPos.relative() 分配 +
 *   通用 getSignal 派发（粉在 shouldSignal=false 下仍走完整虚调用返回 0）；
 *   导体邻居（石地板）再走 getDirectSignalTo 六向（below/above/… 各一次分配+派发）。
 * after = papoPullBestNeighborSignal：两个 MutableBlockPos 复用 + 粉邻居零派化短路。
 *
 * 场景模型 = 环振荡器 cell 邻域：下方石头（导体→内层六向）、两侧中继器（朝向匹配
 * 一侧 15）、上方玻璃、一侧邻粉（拉取期恒 0）。方向序/早退结构逐行复刻。
 *
 * 自检 main：随机摆放（粉/中继器朝向四向/导体/绝缘体/空）× 随机相位 × 10 万组，
 * before/after 拉取值全等；外加 15 早退、导体直供、纯零三定向用例。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx256m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WirePullBench {

    // ---- 方块模型 ----
    enum Kind { WIRE, DIODE, CONDUCTOR, INSULATOR, AIR }
    static final class St {
        final Kind kind;
        final int facing;      // DIODE 输出朝向 0..5（Direction.ordinal），-1=n/a
        final boolean powered;
        final int power;       // WIRE 强度
        St(Kind k, int facing, boolean powered, int power) {
            this.kind = k; this.facing = facing; this.powered = powered; this.power = power;
        }
    }
    /** 世界模型：中心 pos 的 6 邻 + 导体的 6 邻（两层），按 (level, dirIndex) 寻址。 */
    static final class World {
        final St[][] layers = new St[2][6]; // [0]=中心邻, [1]=导体位邻
    }

    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final int[] ORDER = {DOWN, UP, NORTH, SOUTH, WEST, EAST};
    static final int[] OPPOSITE = {UP, DOWN, SOUTH, NORTH, EAST, WEST};

    // ---- before：通用拉取（虚派发以显式调用模型化，分配计数） ----

    static int stateGetSignal(St s, int side, boolean shouldSignalFlag) {
        // RedStoneWireBlock.getSignal：flag 关 → 0；否则 side!=DOWN 且连接才给 power
        if (s.kind == Kind.WIRE) {
            if (!shouldSignalFlag || side == DOWN) return 0;
            return s.power == 0 ? 0 : s.power; // 连接性简化：连着（环场景成立）
        }
        if (s.kind == Kind.DIODE) {
            return s.powered && s.facing == side ? 15 : 0;
        }
        return 0;
    }

    static int stateGetDirectSignal(St s, int side, boolean shouldSignalFlag) {
        if (s.kind == Kind.WIRE) return !shouldSignalFlag ? 0 : stateGetSignal(s, side, true);
        if (s.kind == Kind.DIODE) return s.powered && s.facing == side ? 15 : 0;
        return 0;
    }

    static boolean isConductor(St s) {
        return s.kind == Kind.CONDUCTOR;
    }

    static int beforeGetDirectSignalTo(World w, int conductorDir) {
        int i = 0;
        i = Math.max(i, modelDirect(w, conductorDir, DOWN));
        if (i >= 15) return i;
        i = Math.max(i, modelDirect(w, conductorDir, UP));
        if (i >= 15) return i;
        i = Math.max(i, modelDirect(w, conductorDir, NORTH));
        if (i >= 15) return i;
        i = Math.max(i, modelDirect(w, conductorDir, SOUTH));
        if (i >= 15) return i;
        i = Math.max(i, modelDirect(w, conductorDir, WEST));
        if (i >= 15) return i;
        i = Math.max(i, modelDirect(w, conductorDir, EAST));
        return i;
    }

    static int modelDirect(World w, int conductorDir, int dir) {
        St s = w.layers[1][dir];
        return stateGetDirectSignal(s, dir, false /* 拉取期 flag=false */);
    }

    static long allocCounter;

    static int beforePull(World w) {
        allocCounter = 0;
        int best = 0;
        for (int dir : ORDER) {
            allocCounter++; // pos.relative(dir)
            St s = w.layers[0][dir];
            int sig;
            sig = stateGetSignal(s, dir, false);
            if (isConductor(s)) {
                allocCounter += 6; // getDirectSignalTo 内层的 6 次 below/above/... 分配
                sig = Math.max(sig, beforeGetDirectSignalTo(w, dir));
            }
            if (sig >= 15) return 15;
            if (sig > best) best = sig;
        }
        return best;
    }

    // ---- after：特化拉取（零分配，粉短路） ----
    static int afterDirectSkippingWire(World w, int layer, int dir) {
        St s = w.layers[layer][dir];
        return s.kind == Kind.WIRE ? 0 : stateGetDirectSignal(s, dir, false);
    }

    static int afterPull(World w) {
        allocCounter = 0;
        int best = 0;
        for (int dir : ORDER) {
            St s = w.layers[0][dir];
            int sig;
            if (s.kind == Kind.WIRE) {
                sig = 0;
            } else {
                sig = stateGetSignal(s, dir, false);
                if (isConductor(s)) {
                    int d = 0;
                    d = Math.max(d, afterDirectSkippingWire(w, 1, DOWN));
                    if (d < 15) d = Math.max(d, afterDirectSkippingWire(w, 1, UP));
                    if (d < 15) d = Math.max(d, afterDirectSkippingWire(w, 1, NORTH));
                    if (d < 15) d = Math.max(d, afterDirectSkippingWire(w, 1, SOUTH));
                    if (d < 15) d = Math.max(d, afterDirectSkippingWire(w, 1, WEST));
                    if (d < 15) d = Math.max(d, afterDirectSkippingWire(w, 1, EAST));
                    sig = Math.max(sig, d);
                }
            }
            if (sig >= 15) return 15;
            if (sig > best) best = sig;
        }
        return best;
    }

    // ---- 场景 ----
    World ringWorld;
    World[] randomWorlds;

    @Setup
    public void setup() {
        // 环 cell 邻域：DOWN=导体（石地板）、WEST=上电中继器朝 WEST、EAST=断电中继器、
        // NORTH=粉 power=7、UP=绝缘体（玻璃）、SOUTH=粉 power=0
        ringWorld = new World();
        ringWorld.layers[0][DOWN] = new St(Kind.CONDUCTOR, -1, false, 0);
        ringWorld.layers[0][UP] = new St(Kind.INSULATOR, -1, false, 0);
        ringWorld.layers[0][NORTH] = new St(Kind.WIRE, -1, false, 7);
        ringWorld.layers[0][SOUTH] = new St(Kind.WIRE, -1, false, 0);
        ringWorld.layers[0][WEST] = new St(Kind.DIODE, WEST, true, 0);
        ringWorld.layers[0][EAST] = new St(Kind.DIODE, EAST, false, 0);
        // 石地板的邻（内层）：UP=粉 power=9（粉在头顶——导体直供拉取的粉邻居），其余石/空气
        ringWorld.layers[1][DOWN] = new St(Kind.CONDUCTOR, -1, false, 0);
        ringWorld.layers[1][UP] = new St(Kind.WIRE, -1, false, 9);
        ringWorld.layers[1][NORTH] = new St(Kind.CONDUCTOR, -1, false, 0);
        ringWorld.layers[1][SOUTH] = new St(Kind.AIR, -1, false, 0);
        ringWorld.layers[1][WEST] = new St(Kind.CONDUCTOR, -1, false, 0);
        ringWorld.layers[1][EAST] = new St(Kind.DIODE, EAST, true, 0); // 导体旁有上电中继器直供

        java.util.Random r = new java.util.Random(1234);
        randomWorlds = new World[256];
        for (int i = 0; i < randomWorlds.length; i++) {
            World w = new World();
            for (int l = 0; l < 2; l++) {
                for (int d = 0; d < 6; d++) {
                    int k = r.nextInt(5);
                    w.layers[l][d] = new St(Kind.values()[k], k == 1 ? r.nextInt(6) : -1, r.nextBoolean(), r.nextInt(16));
                }
            }
            randomWorlds[i] = w;
        }
    }

    @Benchmark
    public int beforeGenericPull() {
        int acc = 0;
        for (World w : randomWorlds) acc ^= beforePull(w);
        acc ^= beforePull(ringWorld);
        return acc;
    }

    @Benchmark
    public int afterSpecializedPull() {
        int acc = 0;
        for (World w : randomWorlds) acc ^= afterPull(w);
        acc ^= afterPull(ringWorld);
        return acc;
    }

    public static void main(String[] args) {
        WirePullBench b = new WirePullBench();
        b.setup();
        // ① 环场景：上电中继器 WEST 朝向 → getSignal(WEST 侧)=15 → 外层早退 15
        int ring = b.beforePull(b.ringWorld);
        if (ring != 15 || b.afterPull(b.ringWorld) != 15) throw new AssertionError("ring 15 mismatch");
        // ② 随机世界 ×10 万组等价对拍
        java.util.Random r = new java.util.Random(99);
        for (int i = 0; i < 100_000; i++) {
            World w = new World();
            for (int l = 0; l < 2; l++) {
                for (int d = 0; d < 6; d++) {
                    int k = r.nextInt(5);
                    w.layers[l][d] = new St(Kind.values()[k], k == 1 ? r.nextInt(6) : -1, r.nextBoolean(), r.nextInt(16));
                }
            }
            int x = beforePull(w), y = afterPull(w);
            if (x != y) throw new AssertionError("pull mismatch: " + x + " vs " + y + " @" + i);
        }
        // ③ 纯零场景：全空
        World air = new World();
        for (int l = 0; l < 2; l++) for (int d = 0; d < 6; d++) air.layers[l][d] = new St(Kind.AIR, -1, false, 0);
        if (beforePull(air) != 0 || afterPull(air) != 0) throw new AssertionError("air zero");
        // ④ 导体直供：中心邻 DOWN 导体，内层 EAST 有上电中继器 → 15
        World cd = new World();
        for (int l = 0; l < 2; l++) for (int d = 0; d < 6; d++) cd.layers[l][d] = new St(Kind.AIR, -1, false, 0);
        cd.layers[0][DOWN] = new St(Kind.CONDUCTOR, -1, false, 0);
        cd.layers[1][EAST] = new St(Kind.DIODE, EAST, true, 0);
        if (beforePull(cd) != 15 || afterPull(cd) != 15) throw new AssertionError("conductor direct-supply 15");
        // ⑤ 分配消除度量：before 每拉取 7~13 分配，after 0
        beforePull(b.ringWorld);
        long beforeAllocs = allocCounter;
        afterPull(b.ringWorld);
        if (allocCounter != 0) throw new AssertionError("after must be zero-alloc, got " + allocCounter);
        System.out.println("WirePullBench self-check ALL OK (100k equivalence + 4 directed cases; before allocs/pull=" + beforeAllocs + ", after=0)");
    }
}
