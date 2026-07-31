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
 * 批次45 / 0180: checkInsideBlocks(List) 的 movement.to().subtract(from) 位移差内联。
 * before：subtract 构造 1 个 Vec3（每 movement 1 分配），随后读 lengthSqr() 与 get(axis)。
 * after：三分量直取，lengthSqr 表达式（x*x+y*y+z*z 左结合）与 get(axis) 分量选择逐字。
 * main 自检：典型/零/负/-0.0/NaN/巨值 delta × 三轴 对 lengthSqr 值与轴分量逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MovementDeltaInlineBench {

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

        Vec3 subtract(Vec3 v) {
            return new Vec3(this.x - v.x, this.y - v.y, this.z - v.z);
        }

        double lengthSqr() {
            return this.x * this.x + this.y * this.y + this.z * this.z;
        }
    }

    enum Axis {X, Y, Z}

    private Vec3 from;
    private Vec3 to;

    @Setup
    public void setup() {
        this.from = new Vec3(100.5, 64.0, -200.5);
        this.to = new Vec3(100.9, 64.0, -200.1);
    }

    static double[] before(Vec3 from, Vec3 to) {
        Vec3 vec31 = to.subtract(from);
        double len = vec31.lengthSqr();
        return new double[]{len, axisGet(vec31, Axis.X), axisGet(vec31, Axis.Y), axisGet(vec31, Axis.Z)};
    }

    static double[] after(Vec3 from, Vec3 to) {
        double papoDeltaX = to.x - from.x;
        double papoDeltaY = to.y - from.y;
        double papoDeltaZ = to.z - from.z;
        double len = papoDeltaX * papoDeltaX + papoDeltaY * papoDeltaY + papoDeltaZ * papoDeltaZ;
        return new double[]{len, papoDeltaX, papoDeltaY, papoDeltaZ};
    }

    private static double axisGet(Vec3 v, Axis axis) {
        return switch (axis) {
            case X -> v.x;
            case Y -> v.y;
            case Z -> v.z;
        };
    }

    @Benchmark
    public void before_subtractAlloc(Blackhole bh) {
        bh.consume(before(this.from, this.to));
    }

    @Benchmark
    public void after_inline(Blackhole bh) {
        bh.consume(after(this.from, this.to));
    }

    /** 逐位等价自检。 */
    public static void main(String[] args) {
        double[][] froms = {{100.5, 64.0, -200.5}, {0.0, 0.0, 0.0}, {-0.0, 0.0, -0.0}, {2.9e7, 255.0, -2.9e7}};
        double[][] deltas = {{0.4, 0.0, 0.4}, {0.0, 0.0, 0.0}, {-1.5, 0.078, 2.5}, {-0.0, -0.0, -0.0}, {Double.NaN, 0.0, 0.0}, {1e308, -1e308, 0.0}};
        for (double[] f : froms) {
            for (double[] d : deltas) {
                Vec3 from = new Vec3(f[0], f[1], f[2]);
                Vec3 to = new Vec3(f[0] + d[0], f[1] + d[1], f[2] + d[2]);
                double[] b = before(from, to);
                double[] a = after(from, to);
                for (int i = 0; i < 4; i++) {
                    if (Double.doubleToRawLongBits(b[i]) != Double.doubleToRawLongBits(a[i])) {
                        System.out.println("MISMATCH from=[" + f[0] + "," + f[1] + "," + f[2] + "] delta=[" + d[0] + "," + d[1] + "," + d[2] + "] idx=" + i);
                        System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
