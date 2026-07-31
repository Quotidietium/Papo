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
 * 批次44 / 0177: 格挡视角向量内联×2（applyItemBlocking/resolveBlockedDamage）。
 * before：sourcePosition.subtract(position()) → new Vec3(x,0,z) 水平化 → normalize → dot → acos
 *        （position/subtract/水平化/normalize 四中间分配）。
 * after：sx/sz 直取 → len 阈值分支保留 → nx*nz 点积两分量内联。
 * 等价支点：①normalize 求和 sx²+0.0*0.0+sz² == sx²+sz²（平方非负，+0.0 恒等）；
 *        ②被丢弃的 y 项 0.0*view.y = ±0.0：加 ±0.0 恒等，除非其余项塌缩为异号零——
 *        该唯一分歧被 Math.acos 抹除（acos(±0.0)=π/2 逐位相同）。
 * main 自检：典型/同位置（阈值分支）/贴阈值两侧/-0.0 坐标/巨值/构造全零点积 × view 朝向矩阵
 *        对 acos 结果 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class BlockViewAngleBench {

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

        Vec3 subtract(Vec3 v) {
            return new Vec3(this.x - v.x, this.y - v.y, this.z - v.z);
        }

        Vec3 normalize() {
            double squareRoot = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
            return squareRoot < 1.0E-5F ? ZERO : new Vec3(this.x / squareRoot, this.y / squareRoot, this.z / squareRoot);
        }

        double dot(Vec3 v) {
            return this.x * v.x + this.y * v.y + this.z * v.z;
        }
    }

    private Vec3 sourcePosition;
    private Vec3 view;
    private double selfX;
    private double selfZ;

    @Setup
    public void setup() {
        this.selfX = 100.5;
        this.selfZ = -200.5;
        this.sourcePosition = new Vec3(103.0, 64.0, -199.0);
        this.view = new Vec3(0.6, -0.0, 0.8); // calculateViewVector(0, yRot)：y 恒 ±0.0
    }

    static double before(Vec3 sourcePosition, double selfX, double selfY, double selfZ, Vec3 view) {
        Vec3 vec31 = sourcePosition.subtract(new Vec3(selfX, selfY, selfZ));
        vec31 = new Vec3(vec31.x, 0.0, vec31.z).normalize();
        return Math.acos(vec31.dot(view));
    }

    static double after(Vec3 sourcePosition, double selfX, double selfZ, Vec3 view) {
        double papoSx = sourcePosition.x - selfX;
        double papoSz = sourcePosition.z - selfZ;
        double papoLen = Math.sqrt(papoSx * papoSx + papoSz * papoSz);
        double papoNX = papoLen < 1.0E-5F ? 0.0 : papoSx / papoLen;
        double papoNZ = papoLen < 1.0E-5F ? 0.0 : papoSz / papoLen;
        return Math.acos(papoNX * view.x + papoNZ * view.z);
    }

    @Benchmark
    public void before_fourAlloc(Blackhole bh) {
        bh.consume(before(this.sourcePosition, this.selfX, 64.0, this.selfZ, this.view));
    }

    @Benchmark
    public void after_inline(Blackhole bh) {
        bh.consume(after(this.sourcePosition, this.selfX, this.selfZ, this.view));
    }

    /** 逐位等价自检（比对 acos 输出）。 */
    public static void main(String[] args) {
        double selfY = 64.0;
        double[][] selves = {{100.5, -200.5}, {0.0, 0.0}, {-0.0, 0.0}, {2.9e7, -2.9e7}};
        double[][] sources = {
            {103.0, -199.0},        // 典型
            {100.5, -200.5},        // 同位置 -> len=0 阈值分支
            {100.500001, -200.5},   // sx=1e-6 <1e-5 阈值分支（非零）
            {100.50317, -200.5},    // sx≈3.17e-3 阈值上方
            {-0.0, 0.0},            // -0.0 源（sx=-0.0 情形）
            {1e308, 1e308},         // 巨值 -> len=Inf -> 0.0 分量
            {100.5, -200.49999},    // sz 微小
            {0.0, 0.0}
        };
        double[][] views = {
            {0.6, -0.0, 0.8},       // 典型，y=-0.0
            {-1.0, -0.0, 0.0},      // 朝 -x（构造 nx*view.x=-0.0 组合）
            {0.0, -0.0, -1.0},      // 朝 -z
            {0.0, -0.0, 1.0},       // 朝 +z
            {0.7, -0.0, -0.7}
        };
        for (double[] self : selves) {
            for (double[] src : sources) {
                for (double[] vw : views) {
                    Vec3 sourcePosition = new Vec3(src[0], 65.0, src[1]);
                    Vec3 view = new Vec3(vw[0], vw[1], vw[2]);
                    double b = before(sourcePosition, self[0], selfY, self[1], view);
                    double a = after(sourcePosition, self[0], self[1], view);
                    if (Double.doubleToRawLongBits(b) != Double.doubleToRawLongBits(a)) {
                        System.out.println("MISMATCH self=[" + self[0] + "," + self[1] + "] src=[" + src[0] + "," + src[1] + "] view=[" + vw[0] + "," + vw[2] + "]: " + b + " vs " + a);
                        System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
