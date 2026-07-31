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
 * 批次41 / 0167: 弹射物命中扫描 expandTowards(dm).inflate(1.0) 两次分配折叠为一次。
 * before：bb.expandTowards(dx,dy,dz).inflate(1.0)（逐字复刻原版 <0/>0 分支与 inflate 减法/加法）。
 * after：单构造六坐标直给，min' = (min + (dm<0?dm:0)) - 1，max' = (max + (dm>0?dm:0)) + 1。
 * 等价关键：三元式与原版分支对 NaN 同构（比较全 false → 走 0.0 分支，与原版不动该轴后 ±1.0 结果一致），
 *        -0.0 边缘（min=-0.0 时 min+0.0=+0.0）被后续 ∓1.0 抹除，左结合保持 FP 结合序。
 * main 自检：dm 分量 ∈ {0, -0.0, ±小值, ±大值, NaN} × bb 含 -0.0 边界 矩阵 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ProjectileScanAabbBench {

    /** AABB 语义复刻。 */
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

        Aabb inflate(double x, double y, double z) {
            return new Aabb(this.minX - x, this.minY - y, this.minZ - z, this.maxX + x, this.maxY + y, this.maxZ + z);
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

    private Aabb bb;
    private double dx;
    private double dy;
    private double dz;

    @Setup
    public void setup() {
        this.bb = new Aabb(100.2, 64.0, -200.9, 100.8, 65.4, -200.3);
        this.dx = 0.42;
        this.dy = -0.078;
        this.dz = 1.31;
    }

    private static Aabb before(Aabb bb, double dx, double dy, double dz) {
        return bb.expandTowards(dx, dy, dz).inflate(1.0, 1.0, 1.0);
    }

    private static Aabb after(Aabb bb, double dx, double dy, double dz) {
        return new Aabb(
            bb.minX + (dx < 0.0 ? dx : 0.0) - 1.0,
            bb.minY + (dy < 0.0 ? dy : 0.0) - 1.0,
            bb.minZ + (dz < 0.0 ? dz : 0.0) - 1.0,
            bb.maxX + (dx > 0.0 ? dx : 0.0) + 1.0,
            bb.maxY + (dy > 0.0 ? dy : 0.0) + 1.0,
            bb.maxZ + (dz > 0.0 ? dz : 0.0) + 1.0
        );
    }

    @Benchmark
    public void before_expandInflate(Blackhole bh) {
        bh.consume(before(this.bb, this.dx, this.dy, this.dz));
    }

    @Benchmark
    public void after_collapsed(Blackhole bh) {
        bh.consume(after(this.bb, this.dx, this.dy, this.dz));
    }

    /** 逐位等价自检：含 NaN / -0.0 / 混合符号矩阵。 */
    public static void main(String[] args) {
        double[] dms = {0.0, -0.0, 0.42, -0.078, 1.31, -55.7, Double.NaN};
        Aabb[] bbs = {
            new Aabb(100.2, 64.0, -200.9, 100.8, 65.4, -200.3),
            new Aabb(-0.0, 0.0, -0.0, 0.6, 1.8, 0.6), // min 含 -0.0（构造归一化保持 -0.0/+0.0 序）
            new Aabb(-1.5, -64.0, 30000000.0, -0.5, -60.0, 30000001.0),
        };
        for (Aabb bb : bbs) {
            for (double dx : dms) {
                for (double dy : dms) {
                    for (double dz : dms) {
                        if (!before(bb, dx, dy, dz).bitEquals(after(bb, dx, dy, dz))) {
                            System.out.println("MISMATCH @" + dx + "," + dy + "," + dz);
                            System.exit(1);
                        }
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
