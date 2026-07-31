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
 * 批次45 / 0179: checkInsideBlocks 的 makeBoundingBox(to).deflate(1.0E-5F) AABB 折叠。
 * before：makeBoundingBox 构造 1 个 AABB，deflate 再构造 1 个（每次调用 2 分配）。
 * after：单构造，六分量保持相同左结合 FP 链（deflate(v)==inflate(-v)：mins-(-v)/maxes+(-v)），
 *        AABB 构造器 min/max 归一化接收相同输入（含极小宽度 min>max 交换边缘）。
 * main 自检：典型/零宽/极小宽（归一化交换）/负尺寸/-0.0 坐标/巨值 矩阵六分量逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class InsideBlocksAabbBench {

    /** AABB 语义复刻（含构造器归一化）。 */
    static final class AABB {
        final double minX;
        final double minY;
        final double minZ;
        final double maxX;
        final double maxY;
        final double maxZ;

        AABB(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        AABB inflate(double x, double y, double z) {
            double d = this.minX - x;
            double d1 = this.minY - y;
            double d2 = this.minZ - z;
            double d3 = this.maxX + x;
            double d4 = this.maxY + y;
            double d5 = this.maxZ + z;
            return new AABB(d, d1, d2, d3, d4, d5);
        }

        AABB deflate(double value) {
            return this.inflate(-value);
        }

        AABB inflate(double value) {
            return this.inflate(value, value, value);
        }
    }

    static AABB makeBoundingBox(double x, double y, double z, float width, float height) {
        float f = width / 2.0F;
        float f1 = height;
        return new AABB(x - f, y, z - f, x + f, y + f1, z + f);
    }

    static AABB before(double x, double y, double z, float width, float height) {
        return makeBoundingBox(x, y, z, width, height).deflate(1.0E-5F);
    }

    static AABB after(double x, double y, double z, float width, float height) {
        float papoHalfWidth = width / 2.0F;
        float papoHeight = height;
        return new AABB(
            x - papoHalfWidth - -1.0E-5F,
            y - -1.0E-5F,
            z - papoHalfWidth - -1.0E-5F,
            x + papoHalfWidth + -1.0E-5F,
            y + papoHeight + -1.0E-5F,
            z + papoHalfWidth + -1.0E-5F
        );
    }

    private double px;
    private double py;
    private double pz;

    @Setup
    public void setup() {
        this.px = 100.5;
        this.py = 64.0;
        this.pz = -200.5;
    }

    @Benchmark
    public void before_twoAlloc(Blackhole bh) {
        bh.consume(before(this.px, this.py, this.pz, 0.6F, 1.8F));
    }

    @Benchmark
    public void after_collapsed(Blackhole bh) {
        bh.consume(after(this.px, this.py, this.pz, 0.6F, 1.8F));
    }

    /** 逐位等价自检。 */
    public static void main(String[] args) {
        double[][] poss = {{100.5, 64.0, -200.5}, {0.0, 0.0, 0.0}, {-0.0, 0.0, -0.0}, {2.9e7, 255.0, -2.9e7}, {-500.25, 1.0, 700.75}};
        float[][] dims = {{0.6F, 1.8F}, {0.0F, 0.0F}, {0.00001F, 0.00001F}, {2.0F, 0.5F}, {0.3F, 0.3F}};
        for (double[] p : poss) {
            for (float[] d : dims) {
                AABB b = before(p[0], p[1], p[2], d[0], d[1]);
                AABB a = after(p[0], p[1], p[2], d[0], d[1]);
                if (!bitEquals(b, a)) {
                    System.out.println("MISMATCH pos=[" + p[0] + "," + p[1] + "," + p[2] + "] dims=[" + d[0] + "," + d[1] + "]");
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }

    private static boolean bitEquals(AABB a, AABB b) {
        return Double.doubleToRawLongBits(a.minX) == Double.doubleToRawLongBits(b.minX)
            && Double.doubleToRawLongBits(a.minY) == Double.doubleToRawLongBits(b.minY)
            && Double.doubleToRawLongBits(a.minZ) == Double.doubleToRawLongBits(b.minZ)
            && Double.doubleToRawLongBits(a.maxX) == Double.doubleToRawLongBits(b.maxX)
            && Double.doubleToRawLongBits(a.maxY) == Double.doubleToRawLongBits(b.maxY)
            && Double.doubleToRawLongBits(a.maxZ) == Double.doubleToRawLongBits(b.maxZ);
    }
}
