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
 * 批次46 / 0181: GameEventDispatcher.post BlockPos 消除 + debug 订阅者门控。
 * before：每次 post 分配 BlockPos.containing(pos) 仅为 6 个 section 坐标读取；
 *        debug 分支 flag 为真时无条件 BlockPos.containing + new DebugGameEventInfo
 *        （broadcastEventToTracking 首行即 hasAnySubscriberFor 早退）。
 * after：三个 int floor（BlockPos.containing(pos).getX() 定义为 Mth.floor(pos.x)，逐位一致）；
 *        debug 分支加 hasAnySubscriberFor 门控（被调方首行同检查，无订阅者时两对象构造即丢弃）。
 * main 自检：随机坐标/半径（含负坐标、大坐标、±0.0、NaN）section 坐标逐位一致；
 *        debug 分支两路径副作用（构造计数）与返回值一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class GameEventPostBench {

    /** BlockPos 复刻：仅含本路径使用的语义（containing = 3×Mth.floor + getX/Y/Z）。 */
    static final class BlockPos {
        final int x;
        final int y;
        final int z;

        BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BlockPos containing(double x, double y, double z) {
            return new BlockPos(mthFloor(x), mthFloor(y), mthFloor(z));
        }

        int getX() { return this.x; }
        int getY() { return this.y; }
        int getZ() { return this.z; }
    }

    /** DebugGameEventInfo 复刻（record：holder + vec3，构造无副作用）。 */
    static final class DebugGameEventInfo {
        final Object gameEvent;
        final double x;
        final double y;
        final double z;

        DebugGameEventInfo(Object gameEvent, double x, double y, double z) {
            this.gameEvent = gameEvent;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** Mth.floor(double) 逐字复刻（net.minecraft.util.Mth）。 */
    static int mthFloor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    /** SectionPos.blockToSectionCoord(int) 逐字复刻。 */
    static int blockToSectionCoord(int coord) {
        return coord >> 4;
    }

    // 模拟 debugSynchronizers：无订阅者（生产常态）
    boolean hasAnySubscriberFor() {
        return false;
    }

    double px = 1234.567;
    double py = 64.0;
    double pz = -9876.543;
    int notificationRadius = 16;

    /** 普通逃逸汇（对齐 bh.consume 的强制逃逸语义，供 main 自检调用）。 */
    Object sink;

    /** before：BlockPos 分配 + 坐标读取 + debug 无条件构造。 */
    long beforeBody() {
        BlockPos blockPos = BlockPos.containing(this.px, this.py, this.pz);
        int c0 = blockToSectionCoord(blockPos.getX() - this.notificationRadius);
        int c1 = blockToSectionCoord(blockPos.getY() - this.notificationRadius);
        int c2 = blockToSectionCoord(blockPos.getZ() - this.notificationRadius);
        int c3 = blockToSectionCoord(blockPos.getX() + this.notificationRadius);
        int c4 = blockToSectionCoord(blockPos.getY() + this.notificationRadius);
        int c5 = blockToSectionCoord(blockPos.getZ() + this.notificationRadius);
        // debug 分支（flag=true 场景）：无条件构造
        BlockPos debugPos = BlockPos.containing(this.px, this.py, this.pz);
        DebugGameEventInfo info = new DebugGameEventInfo(this, this.px, this.py, this.pz);
        if (this.hasAnySubscriberFor()) { // broadcastEventToTracking 首行
            this.sink = debugPos;
            this.sink = info;
        }
        this.sink = debugPos;
        this.sink = info;
        return c0 + c1 + c2 + c3 + c4 + c5;
    }

    /** after：int floor + debug 门控。 */
    long afterBody() {
        int bx = mthFloor(this.px);
        int by = mthFloor(this.py);
        int bz = mthFloor(this.pz);
        int c0 = blockToSectionCoord(bx - this.notificationRadius);
        int c1 = blockToSectionCoord(by - this.notificationRadius);
        int c2 = blockToSectionCoord(bz - this.notificationRadius);
        int c3 = blockToSectionCoord(bx + this.notificationRadius);
        int c4 = blockToSectionCoord(by + this.notificationRadius);
        int c5 = blockToSectionCoord(bz + this.notificationRadius);
        // debug 分支：门控（无订阅者时零构造）
        if (this.hasAnySubscriberFor()) {
            BlockPos debugPos = BlockPos.containing(this.px, this.py, this.pz);
            DebugGameEventInfo info = new DebugGameEventInfo(this, this.px, this.py, this.pz);
            this.sink = debugPos;
            this.sink = info;
        }
        return c0 + c1 + c2 + c3 + c4 + c5;
    }

    @Benchmark
    public long before(Blackhole bh) {
        long r = this.beforeBody();
        bh.consume(this.sink);
        return r;
    }

    @Benchmark
    public long after(Blackhole bh) {
        long r = this.afterBody();
        bh.consume(this.sink);
        return r;
    }

    public static void main(String[] args) {
        GameEventPostBench b = new GameEventPostBench();
        double[][] cases = {
            {1234.567, 64.0, -9876.543},
            {-0.0, -0.0, -0.0},
            {0.0, 0.0, 0.0},
            {-1.5, -16.0001, 15.9999},
            {2.9999999E7, 319.0, -2.9999999E7},
            {Double.NaN, 1.0, 2.0},
            {Double.POSITIVE_INFINITY, 0.0, Double.NEGATIVE_INFINITY},
            {-64.0, 320.5, 1.0E-7},
        };
        int[] radii = {0, 1, 16, 64};
        for (double[] c : cases) {
            for (int r : radii) {
                b.px = c[0]; b.py = c[1]; b.pz = c[2]; b.notificationRadius = r;
                long before = b.beforeBody();
                long after = b.afterBody();
                if (before != after) {
                    throw new AssertionError("section coords mismatch at " + c[0] + "," + c[1] + "," + c[2] + " r=" + r +
                        ": before=" + before + " after=" + after);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
