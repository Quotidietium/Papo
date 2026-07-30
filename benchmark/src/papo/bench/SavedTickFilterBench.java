package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 0090: SavedTick.filterTickListForChunk stream 过滤 vs 预尺寸循环。
 * before: tickList.stream().filter(t -> chunkOf(t) == target).toList()
 * after:  isEmpty 早退 + new ArrayList(size) 单遍收集
 * 模拟每区块加载对 block_ticks/fluid_ticks 各一次的调用形态。
 * 参数: ticks = 列表长度；列表元素为 long（打包的 ChunkPos，1/8 命中目标区块）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SavedTickFilterBench {

    @Param({"0", "16", "128"})
    int ticks;

    private List<long[]> tickList; // 每个元素 [packedChunkPos]
    private static final long TARGET = 0L;

    @Setup
    public void setup() {
        this.tickList = new ArrayList<>(ticks);
        for (int i = 0; i < ticks; i++) {
            tickList.add(new long[]{i % 8}); // 1/8 命中 TARGET=0
        }
    }

    @Benchmark
    public List<long[]> before_streamFilter() {
        return tickList.stream().filter(t -> t[0] == TARGET).toList();
    }

    @Benchmark
    public List<long[]> after_presizedLoop() {
        if (tickList.isEmpty()) {
            return List.of();
        }
        List<long[]> result = new ArrayList<>(tickList.size());
        for (long[] t : tickList) {
            if (t[0] == TARGET) {
                result.add(t);
            }
        }
        return result;
    }
}
