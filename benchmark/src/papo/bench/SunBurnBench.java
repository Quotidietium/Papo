package papo.bench;

import java.util.concurrent.TimeUnit;
import java.util.Random;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: Mob.isSunBurnTick 的 BlockPos.containing(x, eyeY, z) 延迟构造。
 * 原版在三道前置门（光照>0.5、随机阈值、非水中/雨中/粉雪）之前就分配 BlockPos，
 * 仅最后一道 canSeeSky 使用；新版在 canSeeSky 调用点内联构造。
 * BlockPos.containing 无副作用，跳过分配不改变可观察行为。
 * 复刻：随机门通过率约 1/15（(light-0.4)*2/30，light≈1.0），10% 湿身跳过，
 * canSeeSky 坐标散列读。
 * main 自检：同一随机序列下两路径布尔结果与随机消耗步数一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SunBurnBench {

    /** BlockPos 语义复刻。 */
    static final class BlockPos {
        final int x, y, z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        static BlockPos containing(double x, double y, double z) {
            return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
    }

    /** canSeeSky 语义复刻：只读坐标。 */
    private static boolean canSeeSky(BlockPos pos) {
        return ((pos.x * 31 + pos.y * 17 + pos.z) & 0xFF) > 100;
    }

    private final double[] xs = new double[64];
    private final double[] ys = new double[64];
    private final double[] zs = new double[64];
    private final float[] lightLevels = new float[64];
    private final boolean[] wet = new boolean[64];

    public SunBurnBench() {
        Random init = new Random(42);
        for (int i = 0; i < 64; i++) {
            this.xs[i] = init.nextDouble() * 200 - 100;
            this.ys[i] = 60 + init.nextDouble() * 20;
            this.zs[i] = init.nextDouble() * 200 - 100;
            this.lightLevels[i] = init.nextFloat(); // 约半数 > 0.5
            this.wet[i] = init.nextFloat() < 0.1f;
        }
    }

    private static boolean beforePath(float light, boolean wet, Random random, double x, double y, double z, Blackhole bh) {
        BlockPos blockPos = BlockPos.containing(x, y, z); // 原版：提前分配
        bh.consume(blockPos);
        boolean flag = wet;
        return light > 0.5F
            && random.nextFloat() * 30.0F < (light - 0.4F) * 2.0F
            && !flag
            && canSeeSky(blockPos);
    }

    private static boolean afterPath(float light, boolean wet, Random random, double x, double y, double z, Blackhole bh) {
        boolean flag = wet;
        return light > 0.5F
            && random.nextFloat() * 30.0F < (light - 0.4F) * 2.0F
            && !flag
            && canSeeSky(BlockPos.containing(x, y, z)); // 新版：仅在消费点构造
    }

    @Benchmark
    public int before_eagerPos(Blackhole bh) {
        Random random = new Random(7);
        int burns = 0;
        for (int i = 0; i < 64; i++) {
            if (beforePath(this.lightLevels[i], this.wet[i], random, this.xs[i], this.ys[i], this.zs[i], bh)) burns++;
        }
        return burns;
    }

    @Benchmark
    public int after_lazyPos(Blackhole bh) {
        Random random = new Random(7);
        int burns = 0;
        for (int i = 0; i < 64; i++) {
            if (afterPath(this.lightLevels[i], this.wet[i], random, this.xs[i], this.ys[i], this.zs[i], bh)) burns++;
        }
        return burns;
    }

    /** 等价性自检：同种子随机序列 → 结果与随机消耗一致。 */
    public static void main(String[] args) {
        SunBurnBench bench = new SunBurnBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        int a = bench.before_eagerPos(bh);
        int b = bench.after_lazyPos(bh);
        if (a != b) { System.out.println("MISMATCH burns: " + a + " vs " + b); System.exit(1); }
        // 逐点对比（独立随机实例）
        for (int i = 0; i < 64; i++) {
            Random r1 = new Random(i);
            Random r2 = new Random(i);
            boolean p1 = beforePath(bench.lightLevels[i], bench.wet[i], r1, bench.xs[i], bench.ys[i], bench.zs[i], bh);
            boolean p2 = afterPath(bench.lightLevels[i], bench.wet[i], r2, bench.xs[i], bench.ys[i], bench.zs[i], bh);
            if (p1 != p2) { System.out.println("MISMATCH @" + i); System.exit(1); }
        }
        System.out.println("ALL OK");
    }
}
