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
 * 批次46 / 0188: LivingEntity.baseTick 气泡柱检查 scratch pos。
 * before：水下存活实体每 tick BlockPos.containing(getX(), getEyeY(), getZ()) 分配一个 BlockPos，
 *        仅为一次只读 getBlockState(...).is(BUBBLE_COLUMN)。
 * after：per-entity MutableBlockPos scratch set-and-read（containing 定义为 3×Mth.floor；
 *        getBlockState 只读坐标不保留引用）。
 * main 自检：随机坐标（含负/大/±0.0/NaN）两路径坐标逐位一致；scratch 复用 10 轮
 *        每轮读取值与 fresh 一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class BubbleColumnPosBench {

    static class BlockPos {
        final int x;
        final int y;
        final int z;

        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        static BlockPos containing(double x, double y, double z) {
            return new BlockPos(mthFloor(x), mthFloor(y), mthFloor(z));
        }

        int getX() { return this.x; }
        int getY() { return this.y; }
        int getZ() { return this.z; }
    }

    static final class MutableBlockPos extends BlockPos {
        MutableBlockPos() { super(0, 0, 0); }
        private int mx;
        private int my;
        private int mz;

        MutableBlockPos set(int x, int y, int z) {
            this.mx = x; this.my = y; this.mz = z;
            return this;
        }

        @Override int getX() { return this.mx; }
        @Override int getY() { return this.my; }
        @Override int getZ() { return this.mz; }
    }

    static int mthFloor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    /** getBlockState(...).is(BUBBLE_COLUMN) 复刻：只读坐标。 */
    boolean getBlockStateIsBubbleColumn(BlockPos pos) {
        return (pos.getX() + pos.getY() + pos.getZ() & 15) == 0;
    }

    double ex = 100.5;
    double eyeY = 63.9;
    double ez = -200.25;

    @Benchmark
    public boolean before(Blackhole bh) {
        BlockPos pos = BlockPos.containing(this.ex, this.eyeY, this.ez);
        boolean r = this.getBlockStateIsBubbleColumn(pos);
        bh.consume(pos);
        return r;
    }

    private MutableBlockPos scratch;

    @Benchmark
    public boolean after(Blackhole bh) {
        MutableBlockPos pos = this.scratch != null ? this.scratch : (this.scratch = new MutableBlockPos());
        pos.set(mthFloor(this.ex), mthFloor(this.eyeY), mthFloor(this.ez));
        boolean r = this.getBlockStateIsBubbleColumn(pos);
        bh.consume(pos);
        return r;
    }

    public static void main(String[] args) {
        BubbleColumnPosBench b = new BubbleColumnPosBench();
        double[][] cases = {
            {100.5, 63.9, -200.25},
            {-0.0, -0.0, -0.0},
            {-1.5, -16.0001, 15.9999},
            {2.9999999E7, 319.0, -2.9999999E7},
            {Double.NaN, 1.0, 2.0},
            {0.9999999999, -0.9999999999, 5.5},
        };
        for (double[] c : cases) {
            b.ex = c[0]; b.eyeY = c[1]; b.ez = c[2];
            BlockPos fresh = BlockPos.containing(c[0], c[1], c[2]);
            for (int round = 0; round < 10; round++) {
                MutableBlockPos pos = b.scratch != null ? b.scratch : (b.scratch = new MutableBlockPos());
                pos.set(mthFloor(c[0]), mthFloor(c[1]), mthFloor(c[2]));
                if (pos.getX() != fresh.getX() || pos.getY() != fresh.getY() || pos.getZ() != fresh.getZ()) {
                    throw new AssertionError("coords mismatch at " + c[0] + "," + c[1] + "," + c[2] + " round " + round);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
