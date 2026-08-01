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
 * 批次48 / 0195: MoveToBlockGoal.getMoveToTarget() above() identity 缓存。
 * before：每活跃 goal 每 tick blockPos.above() 分配 1 个 BlockPos。
 * after：按 blockPos 身份缓存 above() 结果（blockPos 只被重赋值、从不原地 mutate——
 *        findNearestBlock 赋新 MutableBlockPos 后立即 return、stop() 赋 ZERO 单例、
 *        子类审计无写无原地 mutate；身份相同 ⟹ 坐标相同 ⟹ above 结果相同）。
 * main 自检：两路径返回坐标逐 tick 一致；重赋值后缓存正确失效；返回身份一致性（缓存语义）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MoveToTargetCacheBench {

    static final class BlockPos {
        final int x;
        final int y;
        final int z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        BlockPos above() { return new BlockPos(this.x, this.y + 1, this.z); }
    }

    BlockPos blockPos = new BlockPos(10, 64, -7);
    int tickCount;

    /** before：每 tick above()。 */
    public BlockPos tickBeforeBody() {
        BlockPos moveToTarget = this.blockPos.above();
        // tick() 复刻：坐标读取
        int r = moveToTarget.x + moveToTarget.y + moveToTarget.z;
        this.sink = r;
        return moveToTarget;
    }

    // after：identity 缓存（0195 改法逐字复刻）
    BlockPos cacheKey;
    BlockPos cacheValue;

    public BlockPos tickAfterBody() {
        BlockPos pos = this.blockPos;
        if (pos != this.cacheKey) {
            this.cacheKey = pos;
            this.cacheValue = pos.above();
        }
        BlockPos moveToTarget = this.cacheValue;
        int r = moveToTarget.x + moveToTarget.y + moveToTarget.z;
        this.sink = r;
        return moveToTarget;
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    @Setup
    public void reset() { /* 保持 blockPos 不变模拟活跃 goal 的连续 tick */ }

    @Benchmark public BlockPos tickBefore(Blackhole bh) { BlockPos r = this.tickBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public BlockPos tickAfter(Blackhole bh) { BlockPos r = this.tickAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        MoveToTargetCacheBench b = new MoveToTargetCacheBench();
        // 连续 tick：两路径坐标一致
        for (int i = 0; i < 5; i++) {
            BlockPos p1 = b.tickBeforeBody();
            BlockPos p2 = b.tickAfterBody();
            if (p1.x != p2.x || p1.y != p2.y || p1.z != p2.z) throw new AssertionError("tick " + i);
        }
        // 缓存身份语义：after 连续两次返回同一实例（above 结果复用）
        if (b.tickAfterBody() != b.tickAfterBody()) throw new AssertionError("cache identity");
        // 重赋值（findNearestBlock 语义：赋新对象）→ 缓存失效、结果正确
        b.blockPos = new BlockPos(-3, 70, 42);
        BlockPos p3 = b.tickAfterBody();
        if (p3.x != -3 || p3.y != 71 || p3.z != 42) throw new AssertionError("invalidation");
        if (b.tickAfterBody() != p3) throw new AssertionError("post-invalidation identity");
        System.out.println("ALL OK");
    }
}
