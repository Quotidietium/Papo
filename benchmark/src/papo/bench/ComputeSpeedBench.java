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
 * 0105: Entity.computeSpeed 每实体每 tick 的 Vec3 分配消除。
 * before: lastKnownSpeed = position().subtract(lastKnownPosition) 每次 1 个 Vec3 分配。
 * after:  分量 double 存储（a - b 与 Vec3.subtract 的 a + (-b) 位级一致），getKnownSpeed 惰性重建。
 * main 自检：多段位置序列下两条路径的速度分量逐位一致 + hasMovedHorizontallyRecently 公式一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ComputeSpeedBench {

    /** Vec3 语义复刻（不可变，subtract 经 add(-x,-y,-z)）。 */
    static final class Vec3Like {
        final double x, y, z;
        Vec3Like(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Vec3Like add(double x, double y, double z) { return new Vec3Like(this.x + x, this.y + y, this.z + z); }
        Vec3Like subtract(Vec3Like v) { return this.add(-v.x, -v.y, -v.z); }
        double horizontalDistance() { return Math.sqrt(this.x * this.x + this.z * this.z); }
    }

    // ---- before 状态（Vec3 字段）----
    private Vec3Like position;
    private Vec3Like lastKnownSpeed;
    private Vec3Like lastKnownPosition;

    // ---- after 状态（double 分量）----
    private double papoLastKnownSpeedX, papoLastKnownSpeedY, papoLastKnownSpeedZ;
    private double papoLastKnownPosX, papoLastKnownPosY, papoLastKnownPosZ;
    private boolean papoLastKnownPosValid;

    private int tick;

    @Setup
    public void setup() {
        this.position = new Vec3Like(100.0, 64.0, -50.0);
        this.lastKnownSpeed = new Vec3Like(0, 0, 0);
        this.lastKnownPosition = null;
        this.papoLastKnownPosValid = false;
    }

    private Vec3Like nextPosition() {
        // 模拟实体每 tick 移动（setPos 产生新 Vec3，position 字段不可变替换）
        this.tick++;
        return new Vec3Like(100.0 + this.tick * 0.15, 64.0, -50.0 + this.tick * 0.1);
    }

    /** 原实现：每 tick 1 个 Vec3 分配（subtract 结果）。 */
    @Benchmark
    public double before_computeSpeed(Blackhole bh) {
        this.position = nextPosition();
        if (this.lastKnownPosition == null) {
            this.lastKnownPosition = this.position;
        }
        this.lastKnownSpeed = this.position.subtract(this.lastKnownPosition);
        this.lastKnownPosition = this.position;
        bh.consume(this.lastKnownSpeed);
        return this.lastKnownSpeed.x;
    }

    /** Papo 0105：纯 double 运算，零分配。 */
    @Benchmark
    public double after_computeSpeed(Blackhole bh) {
        Vec3Like papoPos = nextPosition();
        if (!this.papoLastKnownPosValid) {
            this.papoLastKnownPosX = papoPos.x;
            this.papoLastKnownPosY = papoPos.y;
            this.papoLastKnownPosZ = papoPos.z;
            this.papoLastKnownPosValid = true;
        }
        this.papoLastKnownSpeedX = papoPos.x - this.papoLastKnownPosX;
        this.papoLastKnownSpeedY = papoPos.y - this.papoLastKnownPosY;
        this.papoLastKnownSpeedZ = papoPos.z - this.papoLastKnownPosZ;
        this.papoLastKnownPosX = papoPos.x;
        this.papoLastKnownPosY = papoPos.y;
        this.papoLastKnownPosZ = papoPos.z;
        bh.consume(this.papoLastKnownSpeedX);
        return this.papoLastKnownSpeedX;
    }

    /** 等价性自检：首 tick 零速度、移动/静止/往返序列下分量逐位一致，hasMovedHorizontallyRecently 公式一致。 */
    public static void main(String[] args) {
        double[][] sequences = {
            {100.0, 64.0, -50.0, 100.15, 64.0, -49.9, 100.3, 64.0, -49.8, 100.3, 64.0, -49.8, 99.0, 63.5, -50.2},
            {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0},
            {-1.5e7, 128.0, 2.3e7, -1.5e7 + 0.001, 128.0, 2.3e7, -1.5e7 - 0.5, 127.0, 2.3e7 + 0.25},
        };
        for (double[] seq : sequences) {
            Vec3Like speedB = new Vec3Like(0, 0, 0);
            Vec3Like lastB = null;
            double sx = 0, sy = 0, sz = 0, lx = 0, ly = 0, lz = 0;
            boolean valid = false;
            for (int i = 0; i + 2 < seq.length; i += 3) {
                Vec3Like pos = new Vec3Like(seq[i], seq[i + 1], seq[i + 2]);
                // before
                if (lastB == null) lastB = pos;
                speedB = pos.subtract(lastB);
                lastB = pos;
                // after
                if (!valid) { lx = pos.x; ly = pos.y; lz = pos.z; valid = true; }
                sx = pos.x - lx; sy = pos.y - ly; sz = pos.z - lz;
                lx = pos.x; ly = pos.y; lz = pos.z;
                if (Double.doubleToRawLongBits(speedB.x) != Double.doubleToRawLongBits(sx)
                    || Double.doubleToRawLongBits(speedB.y) != Double.doubleToRawLongBits(sy)
                    || Double.doubleToRawLongBits(speedB.z) != Double.doubleToRawLongBits(sz)) {
                    System.out.println("SPEED MISMATCH i=" + i);
                    System.exit(1);
                }
            }
            // hasMovedHorizontallyRecently: Math.abs(horizontalDistance()) vs 内联公式
            boolean before = Math.abs(speedB.horizontalDistance()) > 1.0E-5F;
            boolean after = Math.abs(Math.sqrt(sx * sx + sz * sz)) > 1.0E-5F;
            if (before != after) {
                System.out.println("MOVED MISMATCH");
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
