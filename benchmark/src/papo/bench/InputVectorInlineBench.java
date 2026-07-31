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
 * 批次44 / 0175: Entity.getInputVector 内联（normalize/scale 中间 Vec3 消除）。
 * before：(d>1.0 ? relative.normalize() : relative).scale(motionScaler) + 朝向旋转（每次调用 2-3 分配）。
 * after：逐分量内联；d>1.0 时 len=sqrt(d)>1.0 故 normalize 的 <1.0E-5F 阈值分支不可达；
 *        lengthSqr 与 normalize 的平方和表达式逐字相同（值逐位一致）。
 * main 自检：d<1e-7/d≤1/d>1/-0.0/极值 × motionScaler × facing 矩阵 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class InputVectorInlineBench {

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

        double lengthSqr() {
            return this.x * this.x + this.y * this.y + this.z * this.z;
        }

        Vec3 normalize() {
            double squareRoot = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
            return squareRoot < 1.0E-5F ? ZERO : new Vec3(this.x / squareRoot, this.y / squareRoot, this.z / squareRoot);
        }

        Vec3 scale(double factor) {
            return new Vec3(this.x * factor, this.y * factor, this.z * factor);
        }
    }

    private Vec3 relative;

    @Setup
    public void setup() {
        this.relative = new Vec3(0.6, 0.0, -0.8); // 典型移动输入（d=1.0 边界外情形另见自检）
    }

    private static float sinDeg(float facing) {
        return (float) Math.sin(facing * (float) (Math.PI / 180.0)); // 复刻：两路径共用同一函数
    }

    private static float cosDeg(float facing) {
        return (float) Math.cos(facing * (float) (Math.PI / 180.0));
    }

    static Vec3 before(Vec3 relative, float motionScaler, float facing) {
        double d = relative.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            Vec3 vec3 = (d > 1.0 ? relative.normalize() : relative).scale(motionScaler);
            float sin = sinDeg(facing);
            float cos = cosDeg(facing);
            return new Vec3(vec3.x * cos - vec3.z * sin, vec3.y, vec3.z * cos + vec3.x * sin);
        }
    }

    static Vec3 after(Vec3 relative, float motionScaler, float facing) {
        double d = relative.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        } else {
            double scaledX;
            double scaledY;
            double scaledZ;
            if (d > 1.0) {
                double len = Math.sqrt(d);
                scaledX = relative.x / len * motionScaler;
                scaledY = relative.y / len * motionScaler;
                scaledZ = relative.z / len * motionScaler;
            } else {
                scaledX = relative.x * motionScaler;
                scaledY = relative.y * motionScaler;
                scaledZ = relative.z * motionScaler;
            }
            float sin = sinDeg(facing);
            float cos = cosDeg(facing);
            return new Vec3(scaledX * cos - scaledZ * sin, scaledY, scaledZ * cos + scaledX * sin);
        }
    }

    @Benchmark
    public void before_normalizeScaleAlloc(Blackhole bh) {
        bh.consume(before(this.relative, 0.98F, 42.0F));
    }

    @Benchmark
    public void after_inline(Blackhole bh) {
        bh.consume(after(this.relative, 0.98F, 42.0F));
    }

    /** 逐位等价自检。 */
    public static void main(String[] args) {
        double[][] inputs = {
            {0.0, 0.0, 0.0},            // d=0 -> ZERO
            {1e-8, 0.0, 0.0},           // d=1e-16 < 1e-7 -> ZERO
            {3.2e-4, 0.0, 0.0},         // d≈1.02e-7 略超阈值 -> 非 ZERO（边界上方）
            {0.6, 0.0, -0.8},           // d=1.0 恰好（非 >1.0，走 scale 路径）
            {0.6, 0.2, -0.8},           // d=1.04 > 1.0（normalize 路径）
            {1.0, 1.0, 1.0},            // d=3 > 1
            {-0.0, 0.5, 0.0},           // -0.0 分量
            {-0.7, -0.0, -0.7},         // -0.0 + 负值
            {55.0, -32.0, 128.0},       // 大值 normalize
            {1e-300, 0.0, 0.0},         // 次正规 d -> ZERO
            {1e200, 0.0, 0.0}           // d 溢出 Inf > 1 -> normalize（len=Inf -> 0.0 分量）
        };
        float[] scalers = {0.98F, 1.0F, 0.0F, 0.02F};
        float[] facings = {0.0F, 42.0F, 90.0F, -13.5F, 720.25F};
        for (double[] in : inputs) {
            for (float s : scalers) {
                for (float f : facings) {
                    Vec3 rel = new Vec3(in[0], in[1], in[2]);
                    Vec3 b = before(rel, s, f);
                    Vec3 a = after(rel, s, f);
                    if (!bitEquals(b, a)) {
                        System.out.println("MISMATCH in=[" + in[0] + "," + in[1] + "," + in[2] + "] scaler=" + s + " facing=" + f);
                        System.exit(1);
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
