package papo.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次50 / 补丁0203: PlayerChunkSender.collectChunksToSend 的 pending > floor 分支。
 * 原实现 .stream().collect(Comparators.least(floor, Comparator.comparingInt(chunkPos::distanceSquared)))
 * 把每个 pending long 装箱为 Long + Guava PriorityQueue/collector；跑图突发期 pending 可达数百/玩家/tick。
 * 改为原语 k 近邻选择（long[] + int[]，floor<=64），零装箱。
 *
 * 复刻：玩家位于区块 (0,0)，pending=200 个区块 long（±20 内），floor=9（START_CHUNKS_PER_TICK）。
 *   - before: 忠实复刻装箱成本——逐个装箱为 Long 进 ArrayList，再用 comparingInt 比较器排序取前 floor。
 *     （原版另含 stream/Comparators.least 的 PriorityQueue + Collector 额外对象开销，故 before 为
 *      原版成本的保守下界；装箱本身——本优化的目标——完全一致。）
 *   - after:  补丁的原语 k 近邻算法（与落地代码逐行同构）。
 *
 * main 自检：多组随机输入下，before 与 after 选出的 floor 个最近区块的"距离多重集"均等于
 * 输入的 floor 个最小距离（证明两者都正确选出 floor 个最近区块；并列时具体哪个被选对客户端不可观察）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ChunkSelectBench {

    private static final int FLOOR = 9;
    private static final int CX = 0;
    private static final int CZ = 0;

    private final long[] pending;

    public ChunkSelectBench() {
        Random rnd = new Random(0xC0DEL);
        this.pending = new long[200];
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int i = 0;
        while (i < this.pending.length) {
            int x = rnd.nextInt(41) - 20; // -20..20
            int z = rnd.nextInt(41) - 20;
            long key = asLong(x, z);
            if (seen.add(key)) {
                this.pending[i++] = key;
            }
        }
    }

    // === ChunkPos 公式忠实复刻 ===
    static long asLong(int x, int z) { return x & 4294967295L | (z & 4294967295L) << 32; }
    static int getX(long packed) { return (int) (packed & 4294967295L); }
    static int getZ(long packed) { return (int) (packed >>> 32 & 4294967295L); }
    static int distSqr(int cx, int cz, long key) {
        int dx = getX(key) - cx;
        int dz = getZ(key) - cz;
        return dx * dx + dz * dz;
    }

    /** before: 装箱 + 比较器排序取前 floor（保守复刻原版装箱成本）。 */
    @Benchmark
    public int before_boxedStream(Blackhole bh) {
        int floor = FLOOR;
        int cx = CX, cz = CZ;
        long[] src = this.pending;
        List<Long> boxed = new ArrayList<>(src.length);
        for (long key : src) {
            boxed.add(key); // 装箱：pending 个 Long 分配
        }
        boxed.sort(java.util.Comparator.comparingInt(key -> distSqr(cx, cz, (long) key)));
        int n = Math.min(floor, boxed.size());
        int sum = 0;
        for (int i = 0; i < n; i++) {
            long k = boxed.get(i);
            sum += getX(k);
            bh.consume(k);
        }
        return sum;
    }

    /** after: 原语 k 近邻选择（与补丁代码逐行同构）。 */
    @Benchmark
    public int after_primitiveKNearest(Blackhole bh) {
        int floor = FLOOR;
        int cx = CX, cz = CZ;
        long[] src = this.pending;
        final long[] selKey = new long[floor];
        final int[] selDist = new int[floor];
        int sel = 0;
        int maxIdx = 0;
        for (long key : src) {
            int dist = distSqr(cx, cz, key);
            if (sel < floor) {
                selKey[sel] = key;
                selDist[sel] = dist;
                if (dist > selDist[maxIdx]) maxIdx = sel;
                sel++;
            } else if (dist < selDist[maxIdx]) {
                selKey[maxIdx] = key;
                selDist[maxIdx] = dist;
                for (int j = 0; j < floor; j++) {
                    if (selDist[j] > selDist[maxIdx]) maxIdx = j;
                }
            }
        }
        for (int a = 0; a < sel; a++) {
            int best = a;
            for (int b = a + 1; b < sel; b++) {
                if (selDist[b] < selDist[best]) best = b;
            }
            if (best != a) {
                long tk = selKey[a]; selKey[a] = selKey[best]; selKey[best] = tk;
                int td = selDist[a]; selDist[a] = selDist[best]; selDist[best] = td;
            }
        }
        int sum = 0;
        for (int a = 0; a < sel; a++) {
            long k = selKey[a];
            sum += getX(k);
            bh.consume(k);
        }
        return sum;
    }

    /** 等价性自检：多组随机输入下两路径选出的 floor 个最近"距离多重集"一致。 */
    public static void main(String[] args) {
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        for (int seed = 1; seed <= 200; seed++) {
            Random rnd = new Random(seed);
            int n = 5 + rnd.nextInt(300); // pending 规模 5..304
            int floor = 1 + rnd.nextInt(9); // floor 1..9
            long[] src = new long[n];
            java.util.Set<Long> seen = new java.util.HashSet<>();
            int i = 0;
            while (i < n) {
                int x = rnd.nextInt(41) - 20;
                int z = rnd.nextInt(41) - 20;
                long key = asLong(x, z);
                if (seen.add(key)) src[i++] = key;
            }

            // 参考答案：全部距离排序后取最小 floor 个（多重集）
            int[] allDist = new int[n];
            for (int j = 0; j < n; j++) allDist[j] = distSqr(0, 0, src[j]);
            int[] ref = allDist.clone();
            Arrays.sort(ref);
            int[] refFloor = Arrays.copyOf(ref, Math.min(floor, n));
            int refFloorLen = refFloor.length;

            // before 选出的距离（排序后）
            List<Long> boxed = new ArrayList<>(n);
            for (long key : src) boxed.add(key);
            boxed.sort(java.util.Comparator.comparingInt(key -> distSqr(0, 0, (long) key)));
            int[] beforeDist = new int[Math.min(floor, boxed.size())];
            for (int j = 0; j < beforeDist.length; j++) beforeDist[j] = distSqr(0, 0, boxed.get(j));
            Arrays.sort(beforeDist);

            // after 选出的距离（排序后）——复刻补丁算法
            long[] selKey = new long[floor];
            int[] selDist = new int[floor];
            int sel = 0, maxIdx = 0;
            for (long key : src) {
                int dist = distSqr(0, 0, key);
                if (sel < floor) {
                    selKey[sel] = key; selDist[sel] = dist;
                    if (dist > selDist[maxIdx]) maxIdx = sel;
                    sel++;
                } else if (dist < selDist[maxIdx]) {
                    selKey[maxIdx] = key; selDist[maxIdx] = dist;
                    for (int j = 0; j < floor; j++) if (selDist[j] > selDist[maxIdx]) maxIdx = j;
                }
            }
            int[] afterDist = new int[sel];
            for (int j = 0; j < sel; j++) afterDist[j] = selDist[j];
            Arrays.sort(afterDist);

            if (beforeDist.length != refFloorLen || afterDist.length != refFloorLen) {
                System.out.println("MISMATCH length seed=" + seed); System.exit(1);
            }
            for (int j = 0; j < refFloorLen; j++) {
                if (beforeDist[j] != refFloor[j] || afterDist[j] != refFloor[j]) {
                    System.out.println("MISMATCH dist seed=" + seed + " j=" + j); System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
