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
 * 批次43 / 0174: AbstractHurtingProjectile tick Vec3 消除。
 * (a) applyInertia：normalize().scale(power) → add(dm) → scale(inertia) 四个中间 Vec3 →
 *     逐分量内联，保留 normalize 的 squareRoot < 1.0E-5F 阈值分支（Vec3.ZERO.scale(power)
 *     产生 0.0*power 分量，与内联 papoN*power 在阈值路径下逐位一致）。
 * (b) MISS 路径：position().add(getDeltaMovement()) 中间 Vec3 → 逐分量 setPos
 *     （rotateTowardsMovement 只改朝向不移动实体，getX/Y/Z 与 position() 捕获值相等）。
 * main 自检：阈值上下/全零/极小/-0.0 分量/负值矩阵 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FireballVec3Bench {

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

        Vec3 add(Vec3 v) {
            return new Vec3(this.x + v.x, this.y + v.y, this.z + v.z);
        }

        Vec3 scale(double s) {
            return new Vec3(this.x * s, this.y * s, this.z * s);
        }

        Vec3 normalize() {
            double d = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
            return d < 1.0E-5F ? ZERO : new Vec3(this.x / d, this.y / d, this.z / d);
        }
    }

    /** 火球语义复刻：deltaMovement + position + accelerationPower。 */
    static final class Fireball {
        double dx;
        double dy;
        double dz;
        double px;
        double py;
        double pz;
        double accelerationPower = 0.1;

        Vec3 getDeltaMovement() {
            return new Vec3(this.dx, this.dy, this.dz);
        }

        Vec3 position() {
            return new Vec3(this.px, this.py, this.pz);
        }

        double getX() {
            return this.px;
        }

        double getY() {
            return this.py;
        }

        double getZ() {
            return this.pz;
        }

        void setDeltaMovement(Vec3 v) {
            this.dx = v.x;
            this.dy = v.y;
            this.dz = v.z;
        }

        void setPos(Vec3 v) {
            this.px = v.x;
            this.py = v.y;
            this.pz = v.z;
        }

        void setPos(double x, double y, double z) {
            this.px = x;
            this.py = y;
            this.pz = z;
        }
    }

    private Fireball fireball;

    @Setup
    public void setup() {
        this.fireball = new Fireball();
        this.fireball.setDeltaMovement(new Vec3(0.5, -0.1, 1.2));
        this.fireball.setPos(100.5, 64.0, -200.5);
    }

    // ---- (a) applyInertia ----
    @Benchmark
    public void before_inertiaAlloc(Blackhole bh) {
        this.fireball.setDeltaMovement(new Vec3(0.5, -0.1, 1.2));
        float liquidInertia = 0.95F;
        Vec3 deltaMovement = this.fireball.getDeltaMovement();
        this.fireball.setDeltaMovement(deltaMovement.add(deltaMovement.normalize().scale(this.fireball.accelerationPower)).scale(liquidInertia));
        bh.consume(this.fireball.dx);
    }

    @Benchmark
    public void after_inertiaInline(Blackhole bh) {
        this.fireball.setDeltaMovement(new Vec3(0.5, -0.1, 1.2));
        float liquidInertia = 0.95F;
        Vec3 deltaMovement = this.fireball.getDeltaMovement();
        double papoLength = Math.sqrt(deltaMovement.x * deltaMovement.x + deltaMovement.y * deltaMovement.y + deltaMovement.z * deltaMovement.z);
        double papoNX = papoLength < 1.0E-5F ? 0.0 : deltaMovement.x / papoLength;
        double papoNY = papoLength < 1.0E-5F ? 0.0 : deltaMovement.y / papoLength;
        double papoNZ = papoLength < 1.0E-5F ? 0.0 : deltaMovement.z / papoLength;
        this.fireball.setDeltaMovement(new Vec3(
            (deltaMovement.x + papoNX * this.fireball.accelerationPower) * liquidInertia,
            (deltaMovement.y + papoNY * this.fireball.accelerationPower) * liquidInertia,
            (deltaMovement.z + papoNZ * this.fireball.accelerationPower) * liquidInertia
        ));
        bh.consume(this.fireball.dx);
    }

    // ---- (b) MISS 路径 setPos ----
    @Benchmark
    public void before_missSetPosAlloc(Blackhole bh) {
        this.fireball.setPos(100.5, 64.0, -200.5);
        Vec3 location = this.fireball.position().add(this.fireball.getDeltaMovement());
        this.fireball.setPos(location);
        bh.consume(this.fireball.px);
    }

    @Benchmark
    public void after_missSetPosInline(Blackhole bh) {
        this.fireball.setPos(100.5, 64.0, -200.5);
        Vec3 papoMissDelta = this.fireball.getDeltaMovement();
        this.fireball.setPos(this.fireball.getX() + papoMissDelta.x, this.fireball.getY() + papoMissDelta.y, this.fireball.getZ() + papoMissDelta.z);
        bh.consume(this.fireball.px);
    }

    /** 逐位等价自检。 */
    public static void main(String[] args) {
        double[][] dms = {
            {0.5, -0.1, 1.2},          // 典型
            {0.0, 0.0, 0.0},           // 全零 -> 阈值分支
            {-0.0, -0.0, -0.0},        // -0.0 全分量 -> 阈值分支
            {1e-6, 0.0, 0.0},          // 长度 1e-6 < 1e-5 -> 阈值分支（非零输入）
            {7e-6, 7e-6, 0.0},         // 长度 ~9.9e-6 < 1e-5 -> 阈值分支
            {7e-6, 7e-6, 7e-6},        // 长度 ~1.2e-5 >= 1e-5 -> 正常分支（边界上方）
            {1e-4, 0.0, 0.0},          // 边界上方
            {-2.5, 3.9, -0.001},       // 负值
            {-0.0, 0.5, 0.0},          // -0.0 单分量 + 正常长度
            {55.7, -1e-300, 3.3}       // 极值混合
        };
        double[] powers = {0.1, 0.05, 0.0};
        float[] inertias = {0.95F, 0.8F};
        for (double[] dm : dms) {
            for (double power : powers) {
                for (float inertia : inertias) {
                    Vec3 deltaMovement = new Vec3(dm[0], dm[1], dm[2]);
                    Vec3 before = deltaMovement.add(deltaMovement.normalize().scale(power)).scale(inertia);
                    double len = Math.sqrt(dm[0] * dm[0] + dm[1] * dm[1] + dm[2] * dm[2]);
                    double nx = len < 1.0E-5F ? 0.0 : dm[0] / len;
                    double ny = len < 1.0E-5F ? 0.0 : dm[1] / len;
                    double nz = len < 1.0E-5F ? 0.0 : dm[2] / len;
                    Vec3 after = new Vec3(
                        (dm[0] + nx * power) * inertia,
                        (dm[1] + ny * power) * inertia,
                        (dm[2] + nz * power) * inertia
                    );
                    if (!bitEquals(before, after)) {
                        System.out.println("MISMATCH inertia dm=[" + dm[0] + "," + dm[1] + "," + dm[2] + "] power=" + power + " inertia=" + inertia);
                        System.exit(1);
                    }
                }
            }
        }
        // MISS 路径：position().add(dm) vs 逐分量
        double[][] poss = {{100.5, 64.0, -200.5}, {0.0, 0.0, 0.0}, {-0.0, 0.0, -0.0}, {2.9999999E7, 255.0, -2.9999999E7}};
        for (double[] p : poss) {
            for (double[] dm : dms) {
                Vec3 before = new Vec3(p[0], p[1], p[2]).add(new Vec3(dm[0], dm[1], dm[2]));
                Vec3 after = new Vec3(p[0] + dm[0], p[1] + dm[1], p[2] + dm[2]);
                if (!bitEquals(before, after)) {
                    System.out.println("MISMATCH missSetPos");
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
