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
 * 批次44 / 0176: LivingEntity.knockback 内联（new/normalize/scale 三中间 Vec3 消除，仅取 x/z 分量）。
 * before：new Vec3(x,0,z).normalize().scale(strength) → finalVelocity 读 vec3.x/vec3.z（y 从不被读）。
 * after：papoKbX/papoKbZ 逐分量内联。while 循环保证 !(x²+z² < 1.0E-5F) ⇒ len ≥ sqrt(1e-5) > 1e-5，
 *        normalize 阈值分支不可达；normalize 求和 x²+0.0*0.0+z² 与 x²+z² 逐位一致（x² 非负，+0.0 恒等）。
 * main 自检：while 循环后可能值矩阵（典型/贴阈值/单轴/-0.0/巨值溢出 Inf）× strength × onGround
 *        对 finalVelocity 与 diff 全分量 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class KnockbackVec3Bench {

    /** Vec3 语义复刻。 */
    static final class Vec3 {
        static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);
        final double x;
        final double y;
        final double z;

        Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vec3 normalize() {
            double squareRoot = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
            return squareRoot < 1.0E-5F ? ZERO : new Vec3(this.x / squareRoot, this.y / squareRoot, this.z / squareRoot);
        }

        Vec3 scale(double factor) {
            return new Vec3(this.x * factor, this.y * factor, this.z * factor);
        }

        Vec3 subtract(Vec3 v) {
            return new Vec3(this.x - v.x, this.y - v.y, this.z - v.z);
        }
    }

    private Vec3 deltaMovement;

    @Setup
    public void setup() {
        this.deltaMovement = new Vec3(0.3, -0.08, -0.55);
    }

    static Vec3[] before(Vec3 deltaMovement, double strength, double x, double z, boolean onGround) {
        Vec3 vec3 = new Vec3(x, 0.0, z).normalize().scale(strength);
        Vec3 finalVelocity = new Vec3(
            deltaMovement.x / 2.0 - vec3.x,
            onGround ? Math.min(0.4, deltaMovement.y / 2.0 + strength) : deltaMovement.y,
            deltaMovement.z / 2.0 - vec3.z
        );
        return new Vec3[]{finalVelocity, finalVelocity.subtract(deltaMovement)};
    }

    static Vec3[] after(Vec3 deltaMovement, double strength, double x, double z, boolean onGround) {
        double papoKbLen = Math.sqrt(x * x + z * z);
        double papoKbX = x / papoKbLen * strength;
        double papoKbZ = z / papoKbLen * strength;
        Vec3 finalVelocity = new Vec3(
            deltaMovement.x / 2.0 - papoKbX,
            onGround ? Math.min(0.4, deltaMovement.y / 2.0 + strength) : deltaMovement.y,
            deltaMovement.z / 2.0 - papoKbZ
        );
        return new Vec3[]{finalVelocity, finalVelocity.subtract(deltaMovement)};
    }

    @Benchmark
    public void before_normalizeScaleAlloc(Blackhole bh) {
        bh.consume(before(this.deltaMovement, 0.4, 0.6, -0.8, true));
    }

    @Benchmark
    public void after_inline(Blackhole bh) {
        bh.consume(after(this.deltaMovement, 0.4, 0.6, -0.8, true));
    }

    /** 逐位等价自检（输入均为 while 循环后的合法状态：x²+z² ≥ 1.0E-5F）。 */
    public static void main(String[] args) {
        double[][] xz = {
            {0.6, -0.8},            // 典型
            {0.00317, 0.0},         // 贴阈值（x²≈1.005e-5 ≥ 1e-5F）
            {0.0, -0.1},            // 单轴 z
            {-0.01, 0.01},          // while 随机化量级
            {-0.0, 0.5},            // -0.0 x
            {1e308, 1e308},         // x²+z² 溢出 Inf -> len=Inf -> 0.0 分量
            {3.0, -4.0}             // 整数勾股
        };
        double[] strengths = {0.4, 0.0, 1.0, 0.05};
        boolean[] grounds = {true, false};
        Vec3 dm = new Vec3(0.3, -0.08, -0.55);
        for (double[] p : xz) {
            for (double s : strengths) {
                for (boolean g : grounds) {
                    Vec3[] b = before(dm, s, p[0], p[1], g);
                    Vec3[] a = after(dm, s, p[0], p[1], g);
                    for (int i = 0; i < 2; i++) {
                        if (!bitEquals(b[i], a[i])) {
                            System.out.println("MISMATCH x=" + p[0] + " z=" + p[1] + " strength=" + s + " onGround=" + g + " idx=" + i);
                            System.exit(1);
                        }
                    }
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
