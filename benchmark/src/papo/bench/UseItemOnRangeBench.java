package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: handleUseItemOn 命中距离检查 Vec3×2 展开。
 * 原版：Vec3.atCenterOf(blockPos)（1 分配，每轴 +0.5）→ location.subtract(center)（再 1 分配）
 *       → Math.abs(vec.x) < 1.0000001 && … 逐分量。
 * 新版：Math.abs(location.x() - (pos.getX() + 0.5)) < 1.0000001 && … 分量直读。
 * 复刻：Vec3 记录 + atCenterOf/subtract 语义；分量算术位级一致（double 加减）。
 * main 自检：全坐标域抽样布尔结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class UseItemOnRangeBench {

    /** Vec3 语义复刻。 */
    record Vec3(double x, double y, double z) {
        Vec3 subtract(Vec3 o) { return new Vec3(this.x - o.x, this.y - o.y, this.z - o.z); }
    }

    record BlockPos(int x, int y, int z) {}

    private final Vec3[] locations = new Vec3[64];
    private final BlockPos[] positions = new BlockPos[64];

    public UseItemOnRangeBench() {
        for (int i = 0; i < 64; i++) {
            this.positions[i] = new BlockPos(i - 32, 64, -i);
            this.locations[i] = new Vec3(i - 32 + (i % 3) * 0.4, 64.2 + (i % 5) * 0.3, -i + 0.45);
        }
    }

    private static boolean beforeCheck(Vec3 location, BlockPos pos) {
        Vec3 center = new Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5); // atCenterOf
        Vec3 d = location.subtract(center);
        return Math.abs(d.x) < 1.0000001 && Math.abs(d.y) < 1.0000001 && Math.abs(d.z) < 1.0000001;
    }

    private static boolean afterCheck(Vec3 location, BlockPos pos) {
        return Math.abs(location.x - (pos.x + 0.5)) < 1.0000001
            && Math.abs(location.y - (pos.y + 0.5)) < 1.0000001
            && Math.abs(location.z - (pos.z + 0.5)) < 1.0000001;
    }

    @Benchmark
    public int before_vec3Allocs(Blackhole bh) {
        int hits = 0;
        for (int i = 0; i < 64; i++) {
            if (beforeCheck(this.locations[i], this.positions[i])) hits++;
        }
        bh.consume(hits);
        return hits;
    }

    @Benchmark
    public int after_scalarMath(Blackhole bh) {
        int hits = 0;
        for (int i = 0; i < 64; i++) {
            if (afterCheck(this.locations[i], this.positions[i])) hits++;
        }
        bh.consume(hits);
        return hits;
    }

    /** 等价性自检：边界值穷举（±1.0000001 附近）。 */
    public static void main(String[] args) {
        UseItemOnRangeBench bench = new UseItemOnRangeBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_vec3Allocs(bh) != bench.after_scalarMath(bh)) {
            System.out.println("MISMATCH bulk"); System.exit(1);
        }
        double[] deltas = {-1.000001, -1.0000001, -1.0, -0.5, 0.0, 0.5, 1.0, 1.0000001, 1.000001, Double.NaN, Double.POSITIVE_INFINITY};
        BlockPos pos = new BlockPos(100, 64, -100);
        for (double dx : deltas) {
            for (double dy : deltas) {
                for (double dz : deltas) {
                    Vec3 loc = new Vec3(pos.x + 0.5 + dx, pos.y + 0.5 + dy, pos.z + 0.5 + dz);
                    if (beforeCheck(loc, pos) != afterCheck(loc, pos)) {
                        System.out.println("MISMATCH @" + dx + "," + dy + "," + dz); System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
