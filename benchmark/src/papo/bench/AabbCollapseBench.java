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
 * 批次39 / 0161: getHumansInRange AABB 链折叠 3→1。
 * before：new AABB(pos).inflate(d).expandTowards(0,height,0)（3 次分配，2 个中间体）。
 * after：单构造直接给六坐标（浮点结合序逐位保持；构造器 Math.min/max 归一化输入相同）。
 * 语义复刻：AABB 六 double 字段 + 构造归一化 + inflate/expandTowards 逐公式。
 * main 自检：坐标/范围/高度矩阵（含负坐标、d=0、巨大 d、非整数坐标）逐位 double 相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class AabbCollapseBench {

    /** AABB 语义复刻（含构造归一化）。 */
    static final class Aabb {
        final double minX;
        final double minY;
        final double minZ;
        final double maxX;
        final double maxY;
        final double maxZ;

        Aabb(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        static Aabb fromPos(int x, int y, int z) {
            return new Aabb(x, y, z, x + 1, y + 1, z + 1);
        }

        Aabb inflate(double x, double y, double z) {
            return new Aabb(this.minX - x, this.minY - y, this.minZ - z, this.maxX + x, this.maxY + y, this.maxZ + z);
        }

        Aabb expandTowards(double x, double y, double z) {
            double d = this.minX;
            double d1 = this.minY;
            double d2 = this.minZ;
            double d3 = this.maxX;
            double d4 = this.maxY;
            double d5 = this.maxZ;
            if (x < 0.0) {
                d += x;
            } else if (x > 0.0) {
                d3 += x;
            }
            if (y < 0.0) {
                d1 += y;
            } else if (y > 0.0) {
                d4 += y;
            }
            if (z < 0.0) {
                d2 += z;
            } else if (z > 0.0) {
                d5 += z;
            }
            return new Aabb(d, d1, d2, d3, d4, d5);
        }

        boolean bitEquals(Aabb o) {
            return Double.doubleToRawLongBits(this.minX) == Double.doubleToRawLongBits(o.minX)
                && Double.doubleToRawLongBits(this.minY) == Double.doubleToRawLongBits(o.minY)
                && Double.doubleToRawLongBits(this.minZ) == Double.doubleToRawLongBits(o.minZ)
                && Double.doubleToRawLongBits(this.maxX) == Double.doubleToRawLongBits(o.maxX)
                && Double.doubleToRawLongBits(this.maxY) == Double.doubleToRawLongBits(o.maxY)
                && Double.doubleToRawLongBits(this.maxZ) == Double.doubleToRawLongBits(o.maxZ);
        }
    }

    private int px;
    private int py;
    private int pz;
    private double range;
    private double height;

    @Setup
    public void setup() {
        this.px = 128;
        this.py = 70;
        this.pz = -256;
        this.range = 50.0; // 4 级信标
        this.height = 384; // level.getHeight()
    }

    @Benchmark
    public Aabb before_threeAllocs(Blackhole bh) {
        Aabb aabb = Aabb.fromPos(this.px, this.py, this.pz).inflate(this.range, this.range, this.range).expandTowards(0.0, this.height, 0.0);
        bh.consume(aabb);
        return aabb;
    }

    @Benchmark
    public Aabb after_singleAlloc(Blackhole bh) {
        double d = this.range;
        Aabb aabb = new Aabb(this.px - d, this.py - d, this.pz - d, this.px + 1 + d, this.py + 1 + d + this.height, this.pz + 1 + d);
        bh.consume(aabb);
        return aabb;
    }

    /** 等价性自检：逐位矩阵。 */
    public static void main(String[] args) {
        int[][] poss = {{0, 0, 0}, {128, 70, -256}, {-1000, 320, 999}, {Integer.MAX_VALUE >> 4, -60, Integer.MIN_VALUE >> 4}};
        double[] ranges = {0.0, 10.0, 50.0, 1.0E7, 0.1};
        double[] heights = {384, 2032, 64};
        for (int[] p : poss) {
            for (double d : ranges) {
                for (double h : heights) {
                    Aabb a = Aabb.fromPos(p[0], p[1], p[2]).inflate(d, d, d).expandTowards(0.0, h, 0.0);
                    Aabb b = new Aabb(p[0] - d, p[1] - d, p[2] - d, p[0] + 1 + d, p[1] + 1 + d + h, p[2] + 1 + d);
                    if (!a.bitEquals(b)) {
                        System.out.println("MISMATCH pos=" + java.util.Arrays.toString(p) + " d=" + d + " h=" + h);
                        System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
