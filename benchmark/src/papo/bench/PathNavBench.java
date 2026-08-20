package papo.bench;

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
 * 批次66 / 0230：PathNavigation 寻路 tick 的纯分量内联（shouldTargetNextNodeInDirection + 两处
 * getNextEntityPos 调用点 + getGroundY 体内 mutable）。
 *
 * before：atBottomCenterOf（Node→BlockPos→Vec3）×2、closerThan、subtract×2、normalize×2、dot——
 * 每次昂贵 tick 至多 4 Vec3 + 2 BlockPos；canMoveDirectly 为虚方法实参必须物化（红线，保留）。
 * after：Node 字段直读 + 逐分量公式（FP 序/1.0E-5F 守卫/NaN 流穿逐字照抄，0173-0180 模式）。
 *
 * main 自检：布尔等价矩阵（近阈值/零向量/NaN/相同点/正反向）全等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PathNavBench {

    /** 虚方法边界模型（canMoveDirectly 不可消除的实参物化）。 */
    interface DirectCheck { boolean canMoveDirectly(double x, double y, double z); }
    static final DirectCheck NEVER = (x, y, z) -> false;

    static final class Node { final int x, y, z; Node(final int x, final int y, final int z) { this.x = x; this.y = y; this.z = z; } }

    // 模型状态：当前节点、下一节点、mob 位置
    Node cur;
    Node next;
    double px, py, pz;
    DirectCheck check;

    @Setup
    public void setup() {
        this.cur = new Node(100, 64, 100);
        this.next = new Node(103, 64, 103);
        this.px = 101.3;
        this.py = 64.0;
        this.pz = 101.9;
        this.check = NEVER;
    }

    static double[] bottomCenter(final Node n) {
        return new double[]{n.x + 0.5, n.y, n.z + 0.5};
    }

    /** before：完整 Vec3 链（atBottomCenterOf/closerThan/subtract/normalize/dot 全物化）。 */
    @Benchmark
    public boolean before_vec3Chain() {
        final double[] vec3 = bottomCenter(this.cur);
        final double dx = vec3[0] - this.px, dy = vec3[1] - this.py, dz = vec3[2] - this.pz;
        if (dx * dx + dy * dy + dz * dz >= 2.0 * 2.0) { // closerThan
            return false;
        }
        if (this.check.canMoveDirectly(this.px, this.py, this.pz)) {
            return true;
        }
        final double[] vec31 = bottomCenter(this.next);
        final double[] vec32 = {vec3[0] + (-this.px), vec3[1] + (-this.py), vec3[2] + (-this.pz)};
        final double[] vec33 = {vec31[0] + (-this.px), vec31[1] + (-this.py), vec31[2] + (-this.pz)};
        final double d = vec32[0] * vec32[0] + vec32[1] * vec32[1] + vec32[2] * vec32[2];
        final double d1 = vec33[0] * vec33[0] + vec33[1] * vec33[1] + vec33[2] * vec33[2];
        if (!(d1 < d) && !(d < 0.5)) {
            return false;
        }
        final double s32 = Math.sqrt(d);
        final double s33 = Math.sqrt(d1);
        final double[] vec34 = {s32 < 1.0E-5F ? 0.0 : vec32[0] / s32, s32 < 1.0E-5F ? 0.0 : vec32[1] / s32, s32 < 1.0E-5F ? 0.0 : vec32[2] / s32};
        final double[] vec35 = {s33 < 1.0E-5F ? 0.0 : vec33[0] / s33, s33 < 1.0E-5F ? 0.0 : vec33[1] / s33, s33 < 1.0E-5F ? 0.0 : vec33[2] / s33};
        return vec35[0] * vec34[0] + vec35[1] * vec34[1] + vec35[2] * vec34[2] < 0.0;
    }

    /** after：Node 直读逐分量（无中间物化，虚边界保留）。 */
    @Benchmark
    public boolean after_componentInline() {
        final double curX = this.cur.x + 0.5, curY = this.cur.y, curZ = this.cur.z + 0.5;
        final double dx = curX - this.px, dy = curY - this.py, dz = curZ - this.pz;
        if (dx * dx + dy * dy + dz * dz >= 2.0 * 2.0) {
            return false;
        }
        if (this.check.canMoveDirectly(this.px, this.py, this.pz)) {
            return true;
        }
        final double nextX = this.next.x + 0.5, nextY = this.next.y, nextZ = this.next.z + 0.5;
        final double v32x = curX + (-this.px), v32y = curY + (-this.py), v32z = curZ + (-this.pz);
        final double v33x = nextX + (-this.px), v33y = nextY + (-this.py), v33z = nextZ + (-this.pz);
        final double d = v32x * v32x + v32y * v32y + v32z * v32z;
        final double d1 = v33x * v33x + v33y * v33y + v33z * v33z;
        if (!(d1 < d) && !(d < 0.5)) {
            return false;
        }
        final double s32 = Math.sqrt(d);
        final double s33 = Math.sqrt(d1);
        final double v34x = s32 < 1.0E-5F ? 0.0 : v32x / s32, v34y = s32 < 1.0E-5F ? 0.0 : v32y / s32, v34z = s32 < 1.0E-5F ? 0.0 : v32z / s32;
        final double v35x = s33 < 1.0E-5F ? 0.0 : v33x / s33, v35y = s33 < 1.0E-5F ? 0.0 : v33y / s33, v35z = s33 < 1.0E-5F ? 0.0 : v33z / s33;
        return v35x * v34x + v35y * v34y + v35z * v34z < 0.0;
    }

    public static void main(final String[] args) {
        final PathNavBench b = new PathNavBench();
        b.setup();
        // 布尔等价矩阵：近阈值/零向量/同点/远点/NaN
        final double[][] positions = {
            {101.3, 64.0, 101.9},   // 常规（2 格内）
            {100.5, 64.0, 100.5},   // 与当前节点同点（零向量 v32）
            {103.5, 64.0, 103.5},   // 下一节点同点（零向量 v33）
            {140.0, 64.0, 140.0},   // 2 格外（早退）
            {Double.NaN, 64.0, 101.0},
            {101.0, Double.NaN, 101.0},
            {100.5, 64.0, 100.5},   // v32/v33 同时近零
        };
        final Node[] nexts = {new Node(103, 64, 103), new Node(100, 64, 100), new Node(120, 70, 120)};
        for (final double[] p : positions) {
            for (final Node n : nexts) {
                b.px = p[0]; b.py = p[1]; b.pz = p[2]; b.next = n;
                if (b.before_vec3Chain() != b.after_componentInline()) {
                    System.out.println("MISMATCH at " + p[0] + "," + p[1] + "," + p[2] + " next=" + n.x + "," + n.z);
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
