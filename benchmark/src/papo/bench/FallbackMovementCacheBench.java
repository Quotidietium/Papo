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
 * 批次45 / 0181: 静止实体回退 Movement 键控缓存。
 * before：每次 applyEffectsFromBlocks（空移动列表）new Vec3(xOld,yOld,zOld) + new Movement（2 分配）。
 * after：六分量 Double.compare 键控缓存命中复用（-0.0/+0.0 区分；NaN 键相等亦安全——
 *        NaN 负载经下游 FP 分量读不可观测）。
 * main 自检：①重复同值 -> 复用且分量与新建逐位一致；②任一键变 -> 重建；
 *        ③-0.0/+0.0 视为不同键；④NaN 键不崩溃且行为一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FallbackMovementCacheBench {

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
    }

    /** Movement 语义复刻（from/to 两引用）。 */
    static final class Movement {
        final Vec3 from;
        final Vec3 to;

        Movement(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
        }
    }

    /** 实体语义复刻。 */
    static final class Entity {
        double xOld;
        double yOld;
        double zOld;
        Vec3 position;
        Movement papoFallbackMovement;
        double kXOld;
        double kYOld;
        double kZOld;
        double kX;
        double kY;
        double kZ;

        Vec3 oldPosition() {
            return new Vec3(this.xOld, this.yOld, this.zOld);
        }

        Movement before() {
            return new Movement(this.oldPosition(), this.position);
        }

        Movement after() {
            if (this.papoFallbackMovement == null
                || !keyEquals(this.kXOld, this.xOld)
                || !keyEquals(this.kYOld, this.yOld)
                || !keyEquals(this.kZOld, this.zOld)
                || !keyEquals(this.kX, this.position.x)
                || !keyEquals(this.kY, this.position.y)
                || !keyEquals(this.kZ, this.position.z)) {
                this.papoFallbackMovement = new Movement(this.oldPosition(), this.position);
                this.kXOld = this.xOld;
                this.kYOld = this.yOld;
                this.kZOld = this.zOld;
                this.kX = this.position.x;
                this.kY = this.position.y;
                this.kZ = this.position.z;
            }
            return this.papoFallbackMovement;
        }

        /** 与 Entity.papoFallbackKeyEquals 逐字一致：命中 ⟺ 逐位相同（NaN 不命中仅重算）。 */
        static boolean keyEquals(double k, double v) {
            return k == v && (k != 0.0 || Double.doubleToRawLongBits(k) == Double.doubleToRawLongBits(v));
        }
    }

    private Entity entity;

    @Setup
    public void setup() {
        this.entity = new Entity();
        this.entity.xOld = 100.5;
        this.entity.yOld = 63.0;
        this.entity.zOld = -200.5;
        this.entity.position = new Vec3(100.5, 64.0, -200.5);
        this.entity.after(); // 预热缓存（模拟首 tick）
    }

    @Benchmark
    public void before_twoAlloc(Blackhole bh) {
        bh.consume(this.entity.before());
    }

    @Benchmark
    public void after_cacheHit(Blackhole bh) {
        bh.consume(this.entity.after());
    }

    /** 缓存语义自检。 */
    public static void main(String[] args) {
        // ① 同值重复：复用对象且分量与新建逐位一致
        Entity e = new Entity();
        e.xOld = 1.5;
        e.yOld = 2.5;
        e.zOld = 3.5;
        e.position = new Vec3(1.5, 2.0, 3.5);
        Movement first = e.after();
        Movement second = e.after();
        Movement fresh = e.before();
        if (first != second || !bitEquals(first, fresh)) {
            System.out.println("MISMATCH reuse");
            System.exit(1);
        }
        // ② 键变 -> 重建
        e.position = new Vec3(1.5, 2.0, 3.500000000000001);
        Movement third = e.after();
        if (third == first || !bitEquals(third, e.before())) {
            System.out.println("MISMATCH rebuild");
            System.exit(1);
        }
        // ③ -0.0/+0.0 区分
        e.position = new Vec3(-0.0, 2.0, 3.500000000000001);
        Movement negZero = e.after();
        e.position = new Vec3(0.0, 2.0, 3.500000000000001);
        Movement posZero = e.after();
        if (negZero == posZero || Double.doubleToRawLongBits(posZero.to.x) != Double.doubleToRawLongBits(0.0)) {
            System.out.println("MISMATCH zero-sign key");
            System.exit(1);
        }
        // ④ NaN 键：NaN != NaN 永不命中 -> 每次重建（行为与 before 一致，不崩溃）
        e.xOld = Double.NaN;
        e.position = new Vec3(0.0, 2.0, 3.500000000000001);
        Movement nan1 = e.after();
        Movement nan2 = e.after();
        if (!bitEquals(nan1, e.before()) || !bitEquals(nan2, e.before())) {
            System.out.println("MISMATCH nan rebuild");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    private static boolean bitEquals(Movement a, Movement b) {
        return Double.doubleToRawLongBits(a.from.x) == Double.doubleToRawLongBits(b.from.x)
            && Double.doubleToRawLongBits(a.from.y) == Double.doubleToRawLongBits(b.from.y)
            && Double.doubleToRawLongBits(a.from.z) == Double.doubleToRawLongBits(b.from.z)
            && Double.doubleToRawLongBits(a.to.x) == Double.doubleToRawLongBits(b.to.x)
            && Double.doubleToRawLongBits(a.to.y) == Double.doubleToRawLongBits(b.to.y)
            && Double.doubleToRawLongBits(a.to.z) == Double.doubleToRawLongBits(b.to.z);
    }
}
