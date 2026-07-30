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
 * 批19: 热路径 Direction.values() 每次克隆 → 每类私有静态缓存数组。
 * 模拟红石邻更新/寻路邻居展开中典型的 6 向循环，每轮 1024 次循环。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DirectionCloneBench {

    enum Dir {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;

        int stepX() {
            return ordinal() >= 4 ? (ordinal() == 4 ? -1 : 1) : 0;
        }
    }

    private static final Dir[] CACHED = Dir.values();

    @Param({"1024"})
    int loops;

    @Benchmark
    public void before_valuesClone(Blackhole bh) {
        for (int i = 0; i < loops; i++) {
            for (Dir d : Dir.values()) { // 每次克隆 6 元素数组
                bh.consume(d.stepX());
            }
        }
    }

    @Benchmark
    public void after_cachedArray(Blackhole bh) {
        for (int i = 0; i < loops; i++) {
            for (Dir d : CACHED) {
                bh.consume(d.stepX());
            }
        }
    }
}
