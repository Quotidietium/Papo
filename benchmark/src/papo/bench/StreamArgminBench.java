package papo.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0069: Raid.moveRaidCenterToNearbyVillageSection 的最近点搜索。
 * before: SectionPos.cube(...) 流 + min(comparing(...))（流对象 + Comparator + Optional 链）。
 * after:  手动 argmin 循环（d < bestDist，严格小于，保持与 min() 相同的首并列胜出语义）。
 * 尺寸: 27 = 3x3x3（真实 cube 半径），216 = 6x6x6 对照。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class StreamArgminBench {

    @Param({"27", "216"})
    int cube;

    private List<long[]> positions; // 每个元素 {x, y, z}
    private int cx, cy, cz;

    @Setup
    public void setup() {
        int side = (int) Math.round(Math.cbrt(cube));
        int r = (side - 1) / 2;
        positions = new ArrayList<>(side * side * side);
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    positions.add(new long[]{x, y, z});
                }
            }
        }
        cx = 3;
        cy = -2;
        cz = 5;
    }

    private double dist(long[] p) {
        double dx = p[0] - cx;
        double dy = p[1] - cy;
        double dz = p[2] - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Benchmark
    public void before_streamMin(Blackhole bh) {
        long[] best = positions.stream()
            .min(Comparator.comparingDouble(this::dist))
            .orElse(null);
        bh.consume(best);
    }

    @Benchmark
    public void after_manualArgmin(Blackhole bh) {
        long[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (long[] p : positions) {
            double d = dist(p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        bh.consume(best);
    }

    // 防止 unused import 警告，保持与真实代码的流来源一致
    static Stream<?> unused() {
        return Stream.empty();
    }
}
