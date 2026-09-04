package papo.bench;

import java.util.Random;
import org.openjdk.jmh.annotations.*;

/**
 * 批次125 / 补丁 0258：calculateTargetStrength 单遍合并扫描。
 *
 * before = 两遍参考：getBlockSignal（0255 特化拉取：六向+导体六向扇出，flag off）
 *   + getIncomingWireSignal（水平同层粉 + 导体规则上下变体粉，取 max-1）；
 * after = papoCalculateTargetStrength 单遍扫描（同位置读合一、粉跳过保留、
 *   方向序/15 早退逐行复刻、wireMax 只喂 vanilla incoming 认的位置）。
 *
 * 世界模型：中心粉位 (0,0,0) 的 y-1..y+1 × 水平 ±1 邻域（含导体位再±1 层），
 * 五种方块（粉 power 0-15 / 二极管朝向+通断 / 导体 / 绝缘体 / 空气）随机摆放。
 *
 * 自检 main：100 万随机邻域对拍 before==after（含定向构造：粉在正上/正下列、
 * 导体全包、15 早退、变体位上粉等）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx256m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WireTargetMergeBench {

    enum Kind { WIRE, DIODE, CONDUCTOR, INSULATOR, AIR }
    static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    static final int[] ORDER = {DOWN, UP, NORTH, SOUTH, WEST, EAST};

    static final class St {
        final Kind kind; final int facing; final boolean powered; final int power;
        St(Kind k, int f, boolean p, int pw) { kind = k; facing = f; powered = p; power = pw; }
    }

    /** 世界：以 (2,2,2) 为中心（y 0..4, x/z 0..4），5³。 */
    static final class W {
        final St[][][] g = new St[5][5][5];
        St at(int layer, int dx, int dz) { return g[2 + layer][2 + dx][2 + dz]; } // layer -2..2: y 维
        void set(int layer, int dx, int dz, St s) { g[2 + layer][2 + dx][2 + dz] = s; }
        // conductor 位 (±1,-1,0) 等的邻位在 layer 维上直接索引
        St atAbs(int y, int x, int z) { return g[2 + y][2 + x][2 + z]; }
    }

    static int getSignal(St s, int side) {
        if (s.kind == Kind.DIODE) return s.powered && s.facing == side ? 15 : 0;
        return 0; // 粉在 flag 下 0；导体/其他 0
    }
    static int getDirect(St s, int side) {
        if (s.kind == Kind.DIODE) return s.powered && s.facing == side ? 15 : 0;
        return 0; // 粉 flag 下 0
    }
    static boolean isCond(St s) { return s.kind == Kind.CONDUCTOR; }

    // ---- before：两遍参考（0255 特化版 getBlockSignal + vanilla getIncomingWireSignal） ----
    static int directTo(W w, int cy, int cx, int cz) {
        int i = 0;
        i = Math.max(i, dirAt(w, cy - 1, cx, cz, DOWN));
        if (i >= 15) return i;
        i = Math.max(i, dirAt(w, cy + 1, cx, cz, UP));
        if (i >= 15) return i;
        i = Math.max(i, dirAt(w, cy, cx, cz - 1, NORTH));
        if (i >= 15) return i;
        i = Math.max(i, dirAt(w, cy, cx, cz + 1, SOUTH));
        if (i >= 15) return i;
        i = Math.max(i, dirAt(w, cy, cx - 1, cz, WEST));
        if (i >= 15) return i;
        i = Math.max(i, dirAt(w, cy, cx + 1, cz, EAST));
        return i;
    }
    static int dirAt(W w, int y, int x, int z, int from) {
        St s = w.atAbs(y, x, z);
        return s.kind == Kind.WIRE ? 0 : getDirect(s, from);
    }

    static int beforeBlockSignal(W w) {
        int best = 0;
        for (int d : ORDER) {
            int dy = d == DOWN ? -1 : d == UP ? 1 : 0;
            int dx = d == WEST ? -1 : d == EAST ? 1 : 0;
            int dz = d == NORTH ? -1 : d == SOUTH ? 1 : 0;
            St s = w.atAbs(dy, dx, dz);
            int sig;
            if (s.kind == Kind.WIRE) {
                sig = 0;
            } else {
                sig = getSignal(s, d);
                if (isCond(s)) {
                    sig = Math.max(sig, directTo(w, dy, dx, dz));
                }
            }
            if (sig >= 15) return 15;
            if (sig > best) best = sig;
        }
        return best;
    }

    static final int[] HORIZ = {NORTH, EAST, SOUTH, WEST}; // Plane.HORIZONTAL 序

    static int beforeIncoming(W w) {
        int i = 0;
        boolean aboveIsCond = isCond(w.atAbs(1, 0, 0));
        for (int d : HORIZ) {
            int dx = d == WEST ? -1 : d == EAST ? 1 : 0;
            int dz = d == NORTH ? -1 : d == SOUTH ? 1 : 0;
            St s = w.atAbs(0, dx, dz);
            if (s.kind == Kind.WIRE) i = Math.max(i, s.power);
            if (isCond(s) && !aboveIsCond) {
                St up = w.atAbs(1, dx, dz);
                if (up.kind == Kind.WIRE) i = Math.max(i, up.power);
            } else if (!isCond(s)) {
                St dn = w.atAbs(-1, dx, dz);
                if (dn.kind == Kind.WIRE) i = Math.max(i, dn.power);
            }
        }
        return Math.max(0, i - 1);
    }

    static int beforeTwoPass(W w) {
        int blockSignal = beforeBlockSignal(w);
        return blockSignal == 15 ? blockSignal : Math.max(blockSignal, beforeIncoming(w));
    }

    // ---- after：单遍合并（复刻 0258 实现） ----
    static int afterMerged(W w) {
        int blockSignal = 0, wireMax = 0;
        boolean aboveIsCond = false;
        for (int d = 0; d < 6; d++) {
            int dir = ORDER[d];
            int dy = dir == DOWN ? -1 : dir == UP ? 1 : 0;
            int dx = dir == WEST ? -1 : dir == EAST ? 1 : 0;
            int dz = dir == NORTH ? -1 : dir == SOUTH ? 1 : 0;
            St s = w.atAbs(dy, dx, dz);
            boolean cond = isCond(s);
            if (s.kind == Kind.WIRE) {
                if (d >= 2) wireMax = Math.max(wireMax, s.power);
            } else {
                int sig = getSignal(s, dir);
                if (cond) sig = Math.max(sig, directTo(w, dy, dx, dz));
                if (sig >= 15) return 15;
                if (sig > blockSignal) blockSignal = sig;
            }
            if (d == 1) aboveIsCond = cond;
            else if (d >= 2) {
                if (cond) {
                    if (!aboveIsCond) {
                        St up = w.atAbs(1, dx, dz);
                        if (up.kind == Kind.WIRE) wireMax = Math.max(wireMax, up.power);
                    }
                } else {
                    St dn = w.atAbs(-1, dx, dz);
                    if (dn.kind == Kind.WIRE) wireMax = Math.max(wireMax, dn.power);
                }
            }
        }
        return blockSignal >= 15 ? 15 : Math.max(blockSignal, Math.max(0, wireMax - 1));
    }

    // ---- 基准态 ----
    W[] worlds;

    @Setup
    public void setup() {
        Random r = new Random(42);
        worlds = new W[512];
        for (int i = 0; i < worlds.length; i++) {
            W w = new W();
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) {
                        w.g[y][x][z] = randSt(r);
                    }
                }
            }
            w.g[2][2][2] = new St(Kind.AIR, -1, false, 0); // 中心位占位（不读）
            worlds[i] = w;
        }
    }

    static St randSt(Random r) {
        int k = r.nextInt(5);
        return new St(Kind.values()[k], k == 1 ? r.nextInt(6) : -1, r.nextBoolean(), r.nextInt(16));
    }

    @Benchmark
    public int beforeTwoPass() {
        int acc = 0;
        for (W w : worlds) acc += beforeTwoPass(w);
        return acc;
    }

    @Benchmark
    public int afterMerged() {
        int acc = 0;
        for (W w : worlds) acc += afterMerged(w);
        return acc;
    }

    public static void main(String[] args) {
        WireTargetMergeBench b = new WireTargetMergeBench();
        b.setup();
        Random r = new Random(99);
        int maxSeen = 0;
        for (int i = 0; i < 1_000_000; i++) {
            W w = new W();
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    for (int z = 0; z < 5; z++) w.g[y][x][z] = randSt(r);
                }
            }
            w.g[2][2][2] = new St(Kind.AIR, -1, false, 0);
            int x = beforeTwoPass(w), y2 = afterMerged(w);
            if (x != y2) throw new AssertionError("mismatch @" + i + ": two-pass=" + x + " merged=" + y2);
            maxSeen = Math.max(maxSeen, x);
        }
        // 定向用例（fresh 状态对象赋进网格）
        W t = new W();
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) t.g[y][x][z] = new St(Kind.AIR, -1, false, 0);
        // 正上方粉 power 15：vanilla incoming 不读竖列 → 结果应为 0
        t.g[3][2][2] = new St(Kind.WIRE, -1, false, 15);
        if (beforeTwoPass(t) != 0 || afterMerged(t) != 0) throw new AssertionError("above-column wire must not count");
        // 水平邻粉 power 7 → 6
        t.g[2][3][2] = new St(Kind.WIRE, -1, false, 7);
        if (beforeTwoPass(t) != 6 || afterMerged(t) != 6) throw new AssertionError("horizontal wire 7->6");
        System.out.println("WireTargetMergeBench self-check ALL OK (1M random + directed cases; max seen=" + maxSeen + ")");
    }
}
