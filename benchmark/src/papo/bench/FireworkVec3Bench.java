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
 * 批次43 / 0173: 烟花火箭 tick 两分支 Vec3 中间量消除。
 * (a) 推进分支：deltaMovement.add(expr) + setDeltaMovement(Vec3) → 直接 setDeltaMovement(dm + expr)（每分量）。
 * (b) 自由飞行分支：multiply(d2,1.0,d2).add(0,0.04,0) 两个 Vec3 → 逐分量内联（保留 +0.0 项逐字）。
 * 语义复刻：setDeltaMovement(Vec3) 纯委托分量读取。
 * main 自检：典型/零/负值/极值分量矩阵 doubleToRawLongBits 逐位相等（含 -0.0 分量）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FireworkVec3Bench {

    /** Vec3 语义复刻。 */
    static final class Vec3 {
        final double x;
        final double y;
        final double z;

        Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vec3 add(double x, double y, double z) {
            return new Vec3(this.x + x, this.y + y, this.z + z);
        }

        Vec3 multiply(double x, double y, double z) {
            return new Vec3(this.x * x, this.y * y, this.z * z);
        }
    }

    /** 实体语义复刻：deltaMovement 字段。 */
    static final class Firework {
        double dx;
        double dy;
        double dz;

        Vec3 getDeltaMovement() {
            return new Vec3(this.dx, this.dy, this.dz);
        }

        void setDeltaMovement(Vec3 v) {
            this.setDeltaMovement(v.x, v.y, v.z);
        }

        void setDeltaMovement(double x, double y, double z) {
            this.dx = x;
            this.dy = y;
            this.dz = z;
        }
    }

    private Firework firework;
    private Vec3 lookAngle;

    @Setup
    public void setup() {
        this.firework = new Firework();
        this.firework.setDeltaMovement(0.5, -0.1, 1.2);
        this.lookAngle = new Vec3(0.3, -0.05, 0.95);
    }

    // ---- (a) 推进分支 ----
    @Benchmark
    public void before_boostAlloc(Blackhole bh) {
        this.firework.setDeltaMovement(0.5, -0.1, 1.2); // 复位（模拟每 tick 从实体读取）
        Vec3 deltaMovement = this.firework.getDeltaMovement();
        this.firework.setDeltaMovement(
            deltaMovement.add(
                this.lookAngle.x * 0.1 + (this.lookAngle.x * 1.5 - deltaMovement.x) * 0.5,
                this.lookAngle.y * 0.1 + (this.lookAngle.y * 1.5 - deltaMovement.y) * 0.5,
                this.lookAngle.z * 0.1 + (this.lookAngle.z * 1.5 - deltaMovement.z) * 0.5
            )
        );
        bh.consume(this.firework.dx);
    }

    @Benchmark
    public void after_boostInline(Blackhole bh) {
        this.firework.setDeltaMovement(0.5, -0.1, 1.2);
        Vec3 deltaMovement = this.firework.getDeltaMovement();
        this.firework.setDeltaMovement(
            deltaMovement.x + (this.lookAngle.x * 0.1 + (this.lookAngle.x * 1.5 - deltaMovement.x) * 0.5),
            deltaMovement.y + (this.lookAngle.y * 0.1 + (this.lookAngle.y * 1.5 - deltaMovement.y) * 0.5),
            deltaMovement.z + (this.lookAngle.z * 0.1 + (this.lookAngle.z * 1.5 - deltaMovement.z) * 0.5)
        );
        bh.consume(this.firework.dx);
    }

    // ---- (b) 自由飞行分支 ----
    @Benchmark
    public void before_freeFlightAlloc(Blackhole bh) {
        this.firework.setDeltaMovement(0.5, -0.1, 1.2);
        double d2 = 1.15;
        this.firework.setDeltaMovement(this.firework.getDeltaMovement().multiply(d2, 1.0, d2).add(0.0, 0.04, 0.0));
        bh.consume(this.firework.dx);
    }

    @Benchmark
    public void after_freeFlightInline(Blackhole bh) {
        this.firework.setDeltaMovement(0.5, -0.1, 1.2);
        double d2 = 1.15;
        Vec3 dm = this.firework.getDeltaMovement();
        this.firework.setDeltaMovement(dm.x * d2 + 0.0, dm.y * 1.0 + 0.04, dm.z * d2 + 0.0);
        bh.consume(this.firework.dx);
    }

    /** 逐位等价自检。 */
    public static void main(String[] args) {
        double[][] dms = {{0.5, -0.1, 1.2}, {0.0, 0.0, 0.0}, {-0.0, 0.0, -0.0}, {-2.5, 3.9, -0.001}, {55.7, -0.0, 1e-300}};
        double[][] looks = {{0.3, -0.05, 0.95}, {0.0, 0.0, 0.0}, {-1.0, 1.0, -0.5}};
        for (double[] dm : dms) {
            for (double[] la : looks) {
                Vec3 lookAngle = new Vec3(la[0], la[1], la[2]);
                Vec3 deltaMovement = new Vec3(dm[0], dm[1], dm[2]);
                // (a)
                Vec3 beforeA = deltaMovement.add(
                    lookAngle.x * 0.1 + (lookAngle.x * 1.5 - deltaMovement.x) * 0.5,
                    lookAngle.y * 0.1 + (lookAngle.y * 1.5 - deltaMovement.y) * 0.5,
                    lookAngle.z * 0.1 + (lookAngle.z * 1.5 - deltaMovement.z) * 0.5
                );
                Vec3 afterA = new Vec3(
                    deltaMovement.x + (lookAngle.x * 0.1 + (lookAngle.x * 1.5 - deltaMovement.x) * 0.5),
                    deltaMovement.y + (lookAngle.y * 0.1 + (lookAngle.y * 1.5 - deltaMovement.y) * 0.5),
                    deltaMovement.z + (lookAngle.z * 0.1 + (lookAngle.z * 1.5 - deltaMovement.z) * 0.5)
                );
                if (!bitEquals(beforeA, afterA)) {
                    System.out.println("MISMATCH boost");
                    System.exit(1);
                }
                // (b)
                double d2 = 1.15;
                Vec3 beforeB = deltaMovement.multiply(d2, 1.0, d2).add(0.0, 0.04, 0.0);
                Vec3 afterB = new Vec3(deltaMovement.x * d2 + 0.0, deltaMovement.y * 1.0 + 0.04, deltaMovement.z * d2 + 0.0);
                if (!bitEquals(beforeB, afterB)) {
                    System.out.println("MISMATCH freeFlight");
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }

    private static boolean bitEquals(Vec3 a, Vec3 b) {
        return Double.doubleToRawLongBits(a.x) == Double.doubleToRawLongBits(b.x)
            && Double.doubleToRawLongBits(a.y) == Double.doubleToRawLongBits(b.y)
            && Double.doubleToRawLongBits(a.z) == Double.doubleToRawLongBits(b.z);
    }
}
