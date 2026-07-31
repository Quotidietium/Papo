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
 * 批次36: Entity.updateFluidOnEyes 每 tick 为眼位流体检查 new MutableBlockPos
 * → 复用实体级 scratch（set(Mth.floor)×3 只写坐标，getFluidState 只读）。
 * 复刻：MutableBlockPos 分配 + 三轴 floor 写入；getFluidState 用坐标散列模拟读。
 * main 自检：两路径坐标与"流体查询"结果逐点一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FluidOnEyesBench {

    /** MutableBlockPos 语义复刻。 */
    static final class MutableBlockPos {
        int x, y, z;
        MutableBlockPos set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
    }

    private final MutableBlockPos scratch = new MutableBlockPos();
    private final double[] xs = new double[64];
    private final double[] ys = new double[64];
    private final double[] zs = new double[64];

    public FluidOnEyesBench() {
        for (int i = 0; i < 64; i++) {
            this.xs[i] = i * 0.37 - 11.5;
            this.ys[i] = 64.0 + (i % 7) * 0.13;
            this.zs[i] = -i * 0.53 + 4.2;
        }
    }

    /** getFluidState 语义复刻：只读坐标的散列查询。 */
    private static int fluidAt(MutableBlockPos pos) {
        return (pos.x * 31 + pos.y * 17 + pos.z) & 0xFF;
    }

    @Benchmark
    public int before_newPosPerCall(Blackhole bh) {
        int acc = 0;
        for (int i = 0; i < 64; i++) {
            MutableBlockPos pos = new MutableBlockPos();
            pos.set((int) Math.floor(this.xs[i]), (int) Math.floor(this.ys[i]), (int) Math.floor(this.zs[i]));
            acc += fluidAt(pos);
            bh.consume(pos);
        }
        return acc;
    }

    @Benchmark
    public int after_scratchReuse(Blackhole bh) {
        int acc = 0;
        for (int i = 0; i < 64; i++) {
            this.scratch.set((int) Math.floor(this.xs[i]), (int) Math.floor(this.ys[i]), (int) Math.floor(this.zs[i]));
            acc += fluidAt(this.scratch);
        }
        bh.consume(this.scratch);
        return acc;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        FluidOnEyesBench bench = new FluidOnEyesBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        int a = bench.before_newPosPerCall(bh);
        int b = bench.after_scratchReuse(bh);
        if (a != b) { System.out.println("MISMATCH fluid query"); System.exit(1); }
        // 坐标逐点一致
        for (int i = 0; i < 64; i++) {
            MutableBlockPos p1 = new MutableBlockPos();
            p1.set((int) Math.floor(bench.xs[i]), (int) Math.floor(bench.ys[i]), (int) Math.floor(bench.zs[i]));
            bench.scratch.set((int) Math.floor(bench.xs[i]), (int) Math.floor(bench.ys[i]), (int) Math.floor(bench.zs[i]));
            if (p1.x != bench.scratch.x || p1.y != bench.scratch.y || p1.z != bench.scratch.z) {
                System.out.println("MISMATCH coords @" + i); System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
