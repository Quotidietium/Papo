package papo.bench;

import java.util.EnumMap;
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
 * 批22: 寻路邻居展开 / 流体扩散 / 红石信号 中的每调用 EnumMap + Plane.HORIZONTAL 迭代器。
 * before: Maps.newEnumMap(Direction.class) + Plane.HORIZONTAL for-each（每次迭代器分配）。
 * after:  ordinal 索引数组 + 缓存静态数组迭代。
 * 模拟每轮 512 次"邻居展开"（6 向写入 + 4 向对角查询）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NeighborExpandBench {

    enum Dir {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;

        Dir cw() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
        }
    }

    private static final Dir[] DIRECTIONS = Dir.values();
    private static final Dir[] HORIZONTAL = {Dir.NORTH, Dir.EAST, Dir.SOUTH, Dir.WEST};
    /** 模拟 Direction.Plane.HORIZONTAL（顺序完全一致，但 for-each 走 Iterable 分配迭代器）。 */
    private static final Iterable<Dir> PLANE_HORIZONTAL = java.util.Arrays.asList(HORIZONTAL);

    @Param({"512"})
    int expansions;

    @Benchmark
    public void before_enumMapAndPlane(Blackhole bh) {
        for (int n = 0; n < expansions; n++) {
            EnumMap<Dir, Integer> map = new EnumMap<>(Dir.class);
            for (Dir d : DIRECTIONS) {
                map.put(d, d.ordinal() + n);
            }
            for (Dir d : PLANE_HORIZONTAL) {
                Dir cw = d.cw();
                Integer a = map.get(d);
                Integer b = map.get(cw);
                if (a != null && b != null) {
                    bh.consume(a + b);
                }
            }
        }
    }

    @Benchmark
    public void after_ordinalArray(Blackhole bh) {
        for (int n = 0; n < expansions; n++) {
            Integer[] byDir = new Integer[6];
            for (Dir d : DIRECTIONS) {
                byDir[d.ordinal()] = d.ordinal() + n;
            }
            for (Dir d : HORIZONTAL) {
                Dir cw = d.cw();
                Integer a = byDir[d.ordinal()];
                Integer b = byDir[cw.ordinal()];
                if (a != null && b != null) {
                    bh.consume(a + b);
                }
            }
        }
    }
}
