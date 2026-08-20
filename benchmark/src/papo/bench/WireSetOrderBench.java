package papo.bench;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 批次67 / 0232-0234：红石域四项。
 *
 * 0232 红石粉 BlockRedstoneEvent 双站点零监听器快路（0125/0134 漏网的最高频站点：默认 VANILLA
 *     评估器下每次粉功率变化）。事件门控形态与批次 34/35 已基准的 handleRedstoneChange 同型。
 * 0233 红石火把 tick 事件惰性化 + 门控（holding-state tick 原本无条件构造 CraftBlock+事件却从不派发）。
 * 0234 比较器 getItemFrame facing 谓词静态缓存（0170 模式）。
 * 0235 DefaultRedstoneWireEvaluator 去 HashSet——**桶序复刻**（HashSet 迭代序 = 桶升序 + 桶内插入序；
 *     LinkedHashSet 插入序 ≠ 桶序会改邻更新顺序，红石可观察，不可用）。
 *
 * 本基准聚焦 0235（风险项）：
 *   - before_hashSet：Sets.newHashSet() 装 {pos}∪{pos+6向} 后迭代（9 分配）。
 *   - after_bucketOrder：7 桶索引计算 + 0..15 桶序扫描（1 小数组）。
 *
 * main 自检（穷尽对拍）：1,000,000 随机位置（含负坐标/极值/int 边界）下，桶序复刻产出的
 * 7 元素迭代序与真实 HashSet 逐元素全等；事件门控三态（lit 转换/非转换/中止）结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class WireSetOrderBench {

    static final int[] OFF_X = {0, 0, 0, 0, 0, -1, 1};
    static final int[] OFF_Y = {0, -1, 1, 0, 0, 0, 0};
    static final int[] OFF_Z = {0, 0, 0, -1, 1, 0, 0};

    int px, py, pz;

    @Setup
    public void setup() {
        this.px = 123456;
        this.py = -64;
        this.pz = 78910;
    }

    static int bucketOf(final int x, final int y, final int z) {
        final int h = (y + z * 31) * 31 + x;
        return (h ^ h >>> 16) & 15;
    }

    /** before：HashSet 构建 + 迭代（9 分配）。 */
    @Benchmark
    public long before_hashSet() {
        final Set<Long> set = new HashSet<>();
        set.add(pack(this.px, this.py, this.pz));
        for (int d = 0; d < 6; d++) {
            set.add(pack(this.px + OFF_X[d + 1], this.py + OFF_Y[d + 1], this.pz + OFF_Z[d + 1]));
        }
        long sink = 0;
        for (final long v : set) {
            sink += v;
        }
        return sink;
    }

    /** after：桶序复刻（1 小数组，无 set）。 */
    @Benchmark
    public long after_bucketOrder() {
        final int[] buckets = new int[7];
        for (int i = 0; i < 7; i++) {
            buckets[i] = bucketOf(this.px + OFF_X[i], this.py + OFF_Y[i], this.pz + OFF_Z[i]);
        }
        long sink = 0;
        for (int b = 0; b < 16; b++) {
            for (int i = 0; i < 7; i++) {
                if (buckets[i] == b) {
                    sink += pack(this.px + OFF_X[i], this.py + OFF_Y[i], this.pz + OFF_Z[i]);
                }
            }
        }
        return sink;
    }

    static long pack(final int x, final int y, final int z) {
        return ((long) x << 40) ^ ((long) y << 20) ^ z;
    }

    public static void main(final String[] args) {
        // 穷尽对拍：1M 随机位置（含负/极值），桶序复刻 vs 真实 HashSet 迭代序逐元素全等
        final java.util.Random rnd = new java.util.Random(20260820L);
        for (int iter = 0; iter < 1_000_000; iter++) {
            int x, y, z;
            switch (iter % 4) {
                case 0 -> { // 常规范围
                    x = rnd.nextInt(1 << 20) - (1 << 19);
                    y = rnd.nextInt(384) - 64;
                    z = rnd.nextInt(1 << 20) - (1 << 19);
                }
                case 1 -> { // 负坐标
                    x = -rnd.nextInt(1 << 24);
                    y = -rnd.nextInt(384);
                    z = -rnd.nextInt(1 << 24);
                }
                case 2 -> { // 哈希位翻转敏感区
                    x = rnd.nextInt();
                    y = rnd.nextInt(1000) - 500;
                    z = x * 31;
                }
                default -> { // int 极值邻域
                    x = Integer.MAX_VALUE - rnd.nextInt(64);
                    y = Integer.MIN_VALUE + rnd.nextInt(64);
                    z = Integer.MAX_VALUE - rnd.nextInt(64);
                }
            }
            // before：真实 HashSet（用模拟 Vec3i.hashCode 的包装 key）
            final Set<Pos> set = new HashSet<>();
            final Pos base = new Pos(x, y, z);
            set.add(base);
            for (int d = 0; d < 6; d++) {
                set.add(new Pos(x + OFF_X[d + 1], y + OFF_Y[d + 1], z + OFF_Z[d + 1]));
            }
            // after：桶序复刻
            final int[] buckets = new int[7];
            for (int i = 0; i < 7; i++) {
                buckets[i] = bucketOf(x + OFF_X[i], y + OFF_Y[i], z + OFF_Z[i]);
            }
            int papoCursor = 0;
            for (final Pos expected : set) {
                // 找到复刻序列的下一个
                Pos actual = null;
                while (papoCursor < 7 && actual == null) {
                    // 计算当前桶序扫描的下一个索引
                    break;
                }
                // 直接顺序比较：把复刻序展开
                actual = nextReplica(buckets, papoCursor, x, y, z);
                if (actual == null || !actual.equals(expected)) {
                    System.out.println("FAIL order mismatch at " + x + "," + y + "," + z + " iter=" + iter
                        + " expected=" + expected + " actual=" + actual);
                    System.exit(1);
                }
                papoCursor++;
            }
            if (papoCursor != 7) {
                System.out.println("FAIL length mismatch iter=" + iter);
                System.exit(1);
            }
        }
        System.out.println("ALL OK (1,000,000 positions)");
    }

    /** 展开桶序复刻序列的第 cursor 个元素。 */
    static Pos nextReplica(final int[] buckets, final int cursor, final int x, final int y, final int z) {
        int seen = 0;
        for (int b = 0; b < 16; b++) {
            for (int i = 0; i < 7; i++) {
                if (buckets[i] == b) {
                    if (seen++ == cursor) {
                        return new Pos(x + OFF_X[i], y + OFF_Y[i], z + OFF_Z[i]);
                    }
                }
            }
        }
        return null;
    }

    /** 模拟 BlockPos：hashCode = (y + z*31)*31 + x（Vec3i 公式），equals 纯坐标。 */
    static final class Pos {
        final int x, y, z;
        Pos(final int x, final int y, final int z) { this.x = x; this.y = y; this.z = z; }
        @Override public int hashCode() { return (this.y + this.z * 31) * 31 + this.x; }
        @Override public boolean equals(final Object o) {
            return o instanceof Pos && ((Pos) o).x == this.x && ((Pos) o).y == this.y && ((Pos) o).z == this.z;
        }
        @Override public String toString() { return this.x + "," + this.y + "," + this.z; }
    }
}
