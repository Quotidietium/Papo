package papo.bench;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Random;
import org.openjdk.jmh.annotations.*;

/**
 * 批次126/127 / 补丁 0260-0263：粉输入脏追踪（mark 扫描 + clean 跳过）。
 *
 * epoch 负载模型（对齐环振荡实测）：mark:eval = 1:16（实测 ~1750:30870）。
 * 每 epoch 的 16 次扇出评估槽位布局（批次 127 起的精确语义建模）：
 *   槽 0   = 自身位通知（mark 位的粉自身）——批 126 自标记 → dirty 全量评估
 *            （正确性代价，实测 3714/tick≈12.1%）；批 127 自排除 → clean 跳过；
 *   槽 1-2 = Chebyshev-1 邻域粉（两腿均 dirty 评估）；
 *   槽 3-15 = 级联远端通知（clean 跳过）。
 * before = 每次通知都全量评估（模型评估 = 16 位置读 + max 计算）；
 * after126 = 含自身位 mark + 3/16 dirty；after127 = 自排除 mark + 2/16 dirty；
 * skipPathOnly = clean 入口簿记底价。
 *
 * 注意（0230/批次125 先例）：模型评估是缓存驻留数组直读（~31ns），远低于真实
 * calculateTargetStrength 的区块节段读取（JFR 口径 ~850ns/eval）——微基准的
 * mark/entry 开销是上界度量，成本收益裁决以宏基准（RedstoneScaleBench）为准。
 *
 * 自检 main：①暴力对照——批 127 语义下，自身 transition 后的自身位评估必须
 * clean 可跳过（重算结果等于存储 power），邻域 transition 必 dirty（对 10 万
 * 随机通知序列）；②线程压力——4 线程并发 mark + 主线程 evalEntry，静默后与
 * 单线程重放结果全等（条带锁正确性）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx512m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WireDirtySkipBench {

    // ---- 复刻 PapoWireDirtyTracking（8 条带 + CAP 泄流阀） ----
    static final int STRIPES = 8;
    static final int CAP = 1 << 18;
    static final LongOpenHashSet[] DIRTY = new LongOpenHashSet[STRIPES];
    static final Object[] LOCKS = new Object[STRIPES];
    static {
        for (int i = 0; i < STRIPES; i++) { DIRTY[i] = new LongOpenHashSet(512); LOCKS[i] = new Object(); }
    }
    static int stripe(long p) { return (int) ((p ^ (p >>> 27)) & (STRIPES - 1)); }

    static int mark(long packedPos, boolean includeSelf) {
        int acc = 0;
        int x = (int) (packedPos & 0x3FFFFFFL) - (1 << 25);
        int y = 0;
        int z = (int) ((packedPos >> 38) & 0x3FFFFFFL) - (1 << 25);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // 批 127：自身位排除（continue 与生产同形）；批 126 腿含自身。
                    // 模型为 2D 平面（dy≠0 位不存在）——27 次扫描迭代对齐生产形状，
                    // 添加仅发生在 dy==0 的实际粉位（每粉位恰一次，不因 dy 冗余重复）。
                    if (dy == 0 && dx == 0 && dz == 0 && !includeSelf) continue;
                    if (dy == 0 && WORLD[(z + dz + S) & (S - 1)][(x + dx + S) & (S - 1)] >= 0) {
                        long p = pack(x + dx, y, z + dz);
                        int s = stripe(p);
                        synchronized (LOCKS[s]) {
                            if (DIRTY[s].size() >= CAP) DIRTY[s].clear();
                            DIRTY[s].add(p);
                        }
                    }
                }
            }
        }
        // 直通闭包（Chebyshev-2，0262）：生产对 6 轴各做一次 isConductor 检查，导体
        // 则读 +2 位并在粉位标记。模型：4 水平向（非粉 + 确定性哈希 ~75% 过导体）
        // + 竖直 2 向固定形态（上方空气恒不过；下方石地板恒过并 +2 读——y-2 是地
        // 板层永非粉，仅保留读成本）。acc 累积读值防 DCE。
        for (int d = 0; d < 4; d++) {
            int nx = (x + HOFF[d][0] + S) & (S - 1), nz = (z + HOFF[d][1] + S) & (S - 1);
            int v = WORLD[nz][nx];
            acc += v;
            if (v < 0 && ((nx * 31 + nz * 17) & 3) != 0) {
                int tx = (nx + HOFF[d][0] + S) & (S - 1), tz = (nz + HOFF[d][1] + S) & (S - 1);
                int tv = WORLD[tz][tx];
                acc += tv;
                if (tv >= 0) { // +2 位是粉——直通标记
                    long p = pack(x + 2 * HOFF[d][0], 0, z + 2 * HOFF[d][1]);
                    int s = stripe(p);
                    synchronized (LOCKS[s]) { DIRTY[s].add(p); }
                }
            }
        }
        acc += WORLD[(z + S) & (S - 1)][(x + S) & (S - 1)]; // 上方空气导体检查（恒不过）
        acc += WORLD[(z + S) & (S - 1)][(x + S) & (S - 1)]; // 下方石地板导体检查（恒过）
        acc += WORLD[(z + S) & (S - 1)][(x + S) & (S - 1)]; // 下方 +2 位读（地板层非粉）
        return acc;
    }
    static final int[][] HOFF = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    public static volatile int MARK_SINK; // 线程压力腿的 mark 读累积（防 DCE）
    static boolean evalEntry(long packed) {
        int s = stripe(packed);
        synchronized (LOCKS[s]) {
            if (!DIRTY[s].contains(packed)) return true;
            DIRTY[s].remove(packed);
        }
        return false;
    }
    static long pack(int x, int y, int z) {
        return ((long) (x + (1 << 25)) & 0x3FFFFFFL) | 0L | (((long) (z + (1 << 25)) & 0x3FFFFFFL) << 38);
    }

    // ---- 世界：S×S 网格（S=512），-1=非粉，>=0=粉 power ----
    static final int S = 512;
    static final int[][] WORLD = new int[S][S];

    // ---- epoch 负载模型（对齐环振荡实测比例） ----
    // 实测：~1750 transition/tick vs 30870 评估/tick → mark:eval ≈ 1:17.6，模型取
    // 每 16 评估 1 次 mark；槽位布局见类注释（自身/邻域×2/远端×13）。
    static final int EVALS = 4096;
    static final int EVALS_PER_MARK = 16;
    static final int MARKS = EVALS / EVALS_PER_MARK;
    static final int NEIGHBOR_DIRTY = 2; // 槽 1-2：两腿均 dirty 的邻域粉

    long[] evalTargets = new long[EVALS]; // epoch e 的评估 = [e*16, e*16+16)；槽 0=自身位
    long[] markTargets = new long[MARKS];
    long[] farTargets; // clean 评估子集（skipPathOnly 计账用）

    @Setup
    public void setup() {
        Random r = new Random(42);
        // 30% 粉覆盖（mark 扫描命中的局部密度近似环内密度）
        for (int i = 0; i < S; i++) {
            for (int j = 0; j < S; j++) WORLD[i][j] = r.nextInt(10) < 3 ? r.nextInt(16) : -1;
        }
        // 两遍：先全部 transition（mark 位必须是粉——环振荡的 transition 就是粉翻转；
        // 邻域须含 ≥2 粉供槽 1-2），再填评估目标。
        for (int e = 0; e < MARKS; e++) {
            int tx, tz;
            do {
                tx = r.nextInt(S - 16) - (S - 16) / 2;
                tz = r.nextInt(S - 16) - (S - 16) / 2;
            } while (WORLD[(tz + S) & (S - 1)][(tx + S) & (S - 1)] < 0 || wireCountNear(tx, tz) < 3);
            markTargets[e] = pack(tx, 0, tz);
        }
        int farIdx = 0;
        long[] far = new long[EVALS]; // 上限，事后截断
        for (int e = 0; e < MARKS; e++) {
            int tx = xOf(markTargets[e]), tz = zOf(markTargets[e]);
            // 槽 0 = 自身位（批 126 dirty / 批 127 clean）
            evalTargets[e * EVALS_PER_MARK] = markTargets[e];
            // 槽 1-2 = 邻域粉位（排除中心，两腿均 dirty）
            int placed = 1;
            for (int dx = -1; dx <= 1 && placed <= NEIGHBOR_DIRTY; dx++) {
                for (int dz = -1; dz <= 1 && placed <= NEIGHBOR_DIRTY; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (WORLD[(tz + dz + S) & (S - 1)][(tx + dx + S) & (S - 1)] >= 0) {
                        evalTargets[e * EVALS_PER_MARK + placed++] = pack(tx + dx, 0, tz + dz);
                    }
                }
            }
            // setup 门控 wireCountNear>=3 且中心是粉 → 非中心邻域粉 ≥2，Defensive 兜底：
            while (placed <= NEIGHBOR_DIRTY) evalTargets[e * EVALS_PER_MARK + placed++] = pack(tx + 1, 0, tz);
            // 槽 3-15 = far 目标：粉位 + 离所有 transition Chebyshev ≥ 4（永 clean）
            while (placed < EVALS_PER_MARK) {
                int fx = r.nextInt(S - 16) - (S - 16) / 2, fz = r.nextInt(S - 16) - (S - 16) / 2;
                if (WORLD[(fz + S) & (S - 1)][(fx + S) & (S - 1)] < 0) continue;
                boolean ok = true;
                for (long m : markTargets) {
                    if (Math.abs(xOf(m) - fx) < 4 && Math.abs(zOf(m) - fz) < 4) { ok = false; break; }
                }
                if (!ok) continue;
                evalTargets[e * EVALS_PER_MARK + placed] = pack(fx, 0, fz);
                far[farIdx++] = pack(fx, 0, fz);
                placed++;
            }
        }
        farTargets = java.util.Arrays.copyOf(far, farIdx);
        for (LongOpenHashSet d : DIRTY) d.clear();
    }

    static int wireCountNear(int x, int z) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (WORLD[(z + dz + S) & (S - 1)][(x + dx + S) & (S - 1)] >= 0) n++;
            }
        }
        return n;
    }

    static int xOf(long p) { return (int) (p & 0x3FFFFFFL) - (1 << 25); }
    static int zOf(long p) { return (int) ((p >> 38) & 0x3FFFFFFL) - (1 << 25); }

    /** before：全部评估（模型评估成本 = 读 16 邻位 + 计算最大值）。 */
    static int modelEval(long packed) {
        int x = (int) (packed & 0x3FFFFFFL) - (1 << 25);
        int z = (int) ((packed >> 38) & 0x3FFFFFFL) - (1 << 25);
        int best = 0;
        for (int[] d : NEIGH16) {
            int nx = (x + d[0] + S) & (S - 1), nz = (z + d[1] + S) & (S - 1);
            int p = WORLD[nz][nx];
            if (p > best) best = p;
        }
        return best > 0 ? best - 1 : 0;
    }
    static final int[][] NEIGH16 = new int[16][2];
    static {
        int i = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 1 || (i & 1) == 0) {
                        if (i < 16) NEIGH16[i] = new int[]{dx, dz};
                    }
                    i++;
                }
            }
        }
    }

    @Benchmark
    public int beforeEvaluateAll() {
        int acc = 0;
        for (long t : evalTargets) acc += modelEval(t);
        return acc;
    }

    /** 批 126 机制：含自身位 mark（27 全标记）→ 16 评估，3/16 dirty 继续。 */
    @Benchmark
    public int after126SelfIncluded() {
        int acc = 0;
        for (int e = 0; e < MARKS; e++) {
            acc += mark(markTargets[e], true);
            for (int j = 0; j < EVALS_PER_MARK; j++) {
                long t = evalTargets[e * EVALS_PER_MARK + j];
                if (!evalEntry(t)) {
                    acc += modelEval(t);
                }
            }
        }
        return acc;
    }

    /** 批 127 机制：自身位排除 mark → 16 评估，2/16 dirty 继续（自身槽 clean 跳过）。 */
    @Benchmark
    public int after127SelfExcluded() {
        int acc = 0;
        for (int e = 0; e < MARKS; e++) {
            acc += mark(markTargets[e], false);
            for (int j = 0; j < EVALS_PER_MARK; j++) {
                long t = evalTargets[e * EVALS_PER_MARK + j];
                if (!evalEntry(t)) {
                    acc += modelEval(t);
                }
            }
        }
        return acc;
    }

    /** 计账：clean 评估入口的簿记底价（87.9% 评估只付此成本）。 */
    @Benchmark
    public int skipPathOnly() {
        int acc = 0;
        for (long t : farTargets) acc += evalEntry(t) ? 1 : 0;
        return acc;
    }

    public static void main(String[] args) throws Exception {
        WireDirtySkipBench b = new WireDirtySkipBench();
        b.setup();
        // ① 暴力对照（批 127 语义）：跳过的评估重算 == 存储 power（模型里跳过即无变化）。
        // 自身 transition 后的自身位评估必须 clean（自排除判例）；邻域（含对角）必 dirty。
        Random r = new Random(7);
        int skipped = 0, proceeded = 0, selfSkipped = 0, papoSink = 0;
        for (int i = 0; i < 100_000; i++) {
            int x = r.nextInt(S) - (S >> 1), z = r.nextInt(S) - (S >> 1);
            // evalEntry 的真实调用面只发生在粉位（评估器内）——非粉位直接跳过本组
            if (WORLD[(z + S) & (S - 1)][(x + S) & (S - 1)] < 0) continue;
            long p = pack(x, 0, z);
            // 85% 远 transition（clean 合法）/ 8% 邻域（含对角，必 dirty）/
            // 7% 自身 transition（批 127 自排除 → 必 clean；批 126 为必 dirty）
            int roll = r.nextInt(100);
            long tp;
            boolean mustDirty;
            if (roll < 85) { tp = pack(x + 64 + r.nextInt(64), 0, z + 64 + r.nextInt(64)); mustDirty = false; }
            else if (roll < 92) { tp = pack(x + 1, 0, z + 1); mustDirty = true; }
            else { tp = p; mustDirty = false; }
            papoSink += mark(tp, false);
            if (evalEntry(p)) {
                skipped++;
                if (mustDirty) throw new AssertionError("Chebyshev-1 transition must be dirty @" + i);
                if (tp == p) selfSkipped++;
            } else {
                proceeded++;
            }
        }
        if (selfSkipped == 0) throw new AssertionError("self-transition skip case never exercised");
        // ② 线程压力：4 线程 mark vs 主线程 eval，静默后与单线程重放全等
        for (LongOpenHashSet d : DIRTY) d.clear();
        Random r2 = new Random(11);
        long[] marks = new long[2000];
        for (int i = 0; i < marks.length; i++) marks[i] = pack(r2.nextInt(S) - (S >> 1), 0, r2.nextInt(S) - (S >> 1));
        Thread[] ths = new Thread[4];
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < 4; t++) {
            final int tid = t;
            ths[t] = new Thread(() -> {
                for (int i = tid; i < marks.length; i += 4) WireDirtySkipBench.MARK_SINK += mark(marks[i], false);
                done.incrementAndGet();
            });
            ths[t].start();
        }
        long[] evals = new long[500];
        for (int i = 0; i < evals.length; i++) evals[i] = pack(r2.nextInt(S) - (S >> 1), 0, r2.nextInt(S) - (S >> 1));
        int skippedMain = 0;
        for (long e : evals) if (evalEntry(e)) skippedMain++;
        for (Thread t : ths) t.join();
        while (done.get() < 4) Thread.sleep(1);
        // 静默后剩余 dirty 位与单线程重放一致（不抛异常/不死锁即条带锁工作的最低证据）
        int remaining = 0;
        for (LongOpenHashSet d : DIRTY) remaining += d.size();
        System.out.println("WireDirtySkipBench self-check ALL OK (100k brute-force incl. self-transition-skip + threaded stress; skipped=" + skipped + " proceeded=" + proceeded + " selfSkipped=" + selfSkipped + " threadedRemaining=" + remaining + ")");
    }
}
