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
 * 批次42 / 0169: 弹射物候选循环零半径 inflate 分配跳过（两处：getPickRadius / margin）。
 * before：每候选 entity.getBoundingBox().inflate(radius) 分配。
 * after：radius == 0.0F 时直接用原 bb（inflate(0) 值等价：min-0.0==min 含 -0.0；
 *        max+0.0==max——makeBoundingBox maxes 为 pos+非负半宽，不可能为 -0.0；下游 clip/contains 纯读）。
 * 语义复刻：8 候选实体（常见场景半径全 0）；拾取半径 0/1 与 margin 0/0.3 两站点同构复刻。
 * main 自检：radius ∈ {0.0F, 1.0F, 0.3F} × bb 含 -0.0 min / 零宽实体 矩阵逐位相等（跳过路径与原 bb 同一对象）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ProjectileInflateSkipBench {

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

        Aabb inflate(double d) {
            return new Aabb(this.minX - d, this.minY - d, this.minZ - d, this.maxX + d, this.maxY + d, this.maxZ + d);
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

    /** 候选实体语义复刻：bb + pickRadius。 */
    static final class Candidate {
        final Aabb bb;
        final float pickRadius;

        Candidate(Aabb bb, float pickRadius) {
            this.bb = bb;
            this.pickRadius = pickRadius;
        }
    }

    private Candidate[] candidates;

    @Setup
    public void setup() {
        this.candidates = new Candidate[8];
        for (int i = 0; i < 8; i++) {
            this.candidates[i] = new Candidate(new Aabb(100 + i, 64, -200, 100.6 + i, 65.8, -199.4), 0.0F); // 常规生物半径 0
        }
    }

    /** before：站点 1（pickRadius）——每候选 inflate。 */
    @Benchmark
    public void before_pickRadiusInflate(Blackhole bh) {
        for (Candidate c : this.candidates) {
            bh.consume(c.bb.inflate(c.pickRadius));
        }
    }

    /** after：站点 1——零半径跳过。 */
    @Benchmark
    public void after_pickRadiusSkip(Blackhole bh) {
        for (Candidate c : this.candidates) {
            bh.consume(c.pickRadius == 0.0F ? c.bb : c.bb.inflate(c.pickRadius));
        }
    }

    /** before：站点 2（margin，此处以 0.0F 场景测量分配路径）。 */
    @Benchmark
    public void before_marginInflate(Blackhole bh) {
        for (Candidate c : this.candidates) {
            bh.consume(c.bb.inflate(0.0F));
        }
    }

    /** after：站点 2——零 margin 跳过。 */
    @Benchmark
    public void after_marginSkip(Blackhole bh) {
        float margin = 0.0F;
        for (Candidate c : this.candidates) {
            bh.consume(margin == 0.0F ? c.bb : c.bb.inflate(margin));
        }
    }

    /** 等价性自检：radius/bb 矩阵逐位相等 + 跳过路径返回同一对象。 */
    public static void main(String[] args) {
        Aabb[] bbs = {
            new Aabb(100, 64, -200, 100.6, 65.8, -199.4),
            new Aabb(-0.0, 0.0, -0.0, 0.6, 1.8, 0.6), // min 含 -0.0
            new Aabb(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), // 零宽实体（Marker）
            new Aabb(-1e-3, -1e-3, -1e-3, 0.0, 1.0, 1.0),
        };
        float[] radii = {0.0F, 1.0F, 0.3F};
        for (Aabb bb : bbs) {
            for (float r : radii) {
                Aabb inflated = bb.inflate(r);
                Aabb viaSkip = r == 0.0F ? bb : bb.inflate(r);
                if (!inflated.bitEquals(viaSkip)) {
                    System.out.println("MISMATCH radius=" + r);
                    System.exit(1);
                }
                if (r == 0.0F && viaSkip != bb) {
                    System.out.println("MISMATCH identity: skip path must return the original bb object");
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
