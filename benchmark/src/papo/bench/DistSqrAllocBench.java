package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0045/0058: 距离平方比较中的 Vec3 分配消除。
 * before: new Vec3(x+0.5, y+0.5, z+0.5).distanceToSqr(vec) —— 每次比较一次堆分配。
 * after:  distToCenterSqr(x,y,z) 纯标量计算，零分配。
 * 模拟每轮 1024 次比较（实体扫描/粒子广播量级）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DistSqrAllocBench {

    @Param({"1024"})
    int count;

    /** 精简版 Vec3，仅保留距离计算所需的形态。 */
    static final class Vec3 {
        final double x, y, z;

        Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double distanceToSqr(Vec3 o) {
            double dx = this.x - o.x;
            double dy = this.y - o.y;
            double dz = this.z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Vec3 target = new Vec3(128.5, 64.0, -200.5);

    @Benchmark
    public void before_allocVec3(Blackhole bh) {
        for (int i = 0; i < count; i++) {
            Vec3 v = new Vec3(i * 0.5 + 0.5, 64.0 + 0.5, i * -0.25 + 0.5);
            bh.consume(target.distanceToSqr(v) < 576.0);
        }
    }

    @Benchmark
    public void after_scalar(Blackhole bh) {
        for (int i = 0; i < count; i++) {
            double dx = (i * 0.5 + 0.5) - target.x;
            double dy = (64.0 + 0.5) - target.y;
            double dz = (i * -0.25 + 0.5) - target.z;
            bh.consume(dx * dx + dy * dy + dz * dz < 576.0);
        }
    }
}
