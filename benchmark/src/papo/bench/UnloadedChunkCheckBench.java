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
 * 0114: Entity.touchingUnloadedChunk 内联 inflate(1.0) 边界算术免 AABB 分配。
 * 原实现每次调用 getBoundingBox().inflate(1.0) 分配新 AABB（doCheckFallDamage 每次移动、
 * updateFluidHeightAndDoFluidPushing 每实体每 tick 各调用一次）。
 * AABB.inflate(v) == new AABB(minX - v, ..., maxX + v, ...)，只读 minX/maxX/minZ/maxZ。
 * main 自检：10 万随机 box 的 floor/ceil 结果逐位一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class UnloadedChunkCheckBench {

    /** AABB 语义复刻（仅本场景用到的部分）。 */
    static final class Aabb {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
        Aabb inflate(double x, double y, double z) {
            return new Aabb(this.minX - x, this.minY - y, this.minZ - z,
                this.maxX + x, this.maxY + y, this.maxZ + z);
        }
        Aabb inflate(double v) { return this.inflate(v, v, v); }
    }

    private static int floor(double v) { return (int) Math.floor(v); } // Mth.floor 语义
    private static int ceil(double v) { return (int) Math.ceil(v); }   // Mth.ceil 语义

    private final Aabb bb = new Aabb(123.3, 64.0, -87.7, 123.9, 65.8, -87.1);

    /** 原实现：inflate(1.0) 分配新 AABB。 */
    @Benchmark
    public boolean before_inflateAlloc(Blackhole bh) {
        Aabb aabb = this.bb.inflate(1.0);
        int f0 = floor(aabb.minX);
        int c0 = ceil(aabb.maxX);
        int f1 = floor(aabb.minZ);
        int c1 = ceil(aabb.maxZ);
        bh.consume(aabb);
        return (f0 + c0 + f1 + c1) % 16 == 0; // 模拟 hasChunksAt 消费坐标
    }

    /** Papo 0114：内联边界算术，零分配。 */
    @Benchmark
    public boolean after_inlineBounds(Blackhole bh) {
        Aabb box = this.bb;
        int f0 = floor(box.minX - 1.0);
        int c0 = ceil(box.maxX + 1.0);
        int f1 = floor(box.minZ - 1.0);
        int c1 = ceil(box.maxZ + 1.0);
        return (f0 + c0 + f1 + c1) % 16 == 0;
    }

    /** 等价性自检：随机 box 两实现 floor/ceil 结果逐位一致。 */
    public static void main(String[] args) {
        java.util.Random rnd = new java.util.Random(20260731);
        for (int i = 0; i < 100000; i++) {
            double cx = (rnd.nextDouble() - 0.5) * 6.0e7; // 覆盖世界边界量级
            double cy = rnd.nextDouble() * 512 - 128;
            double cz = (rnd.nextDouble() - 0.5) * 6.0e7;
            double w = rnd.nextDouble() * 4, h = rnd.nextDouble() * 4;
            Aabb bb = new Aabb(cx, cy, cz, cx + w, cy + h, cz + w);

            Aabb aabb = bb.inflate(1.0);
            int[] before = {floor(aabb.minX), ceil(aabb.maxX), floor(aabb.minZ), ceil(aabb.maxZ)};
            int[] after = {floor(bb.minX - 1.0), ceil(bb.maxX + 1.0), floor(bb.minZ - 1.0), ceil(bb.maxZ + 1.0)};
            for (int k = 0; k < 4; k++) {
                if (before[k] != after[k]) {
                    System.out.println("MISMATCH at " + i + " component " + k);
                    System.exit(1);
                }
            }
        }
        // 边界特例：坐标恰为整数（inflate 后 floor/ceil 无舍入余量）
        for (int p = -30000000; p <= 30000000; p += 999983) {
            Aabb bb = new Aabb(p, 0.0, -p, p + 1.0, 2.0, -p + 1.0);
            Aabb aabb = bb.inflate(1.0);
            if (floor(aabb.minX) != floor(bb.minX - 1.0) || ceil(aabb.maxX) != ceil(bb.maxX + 1.0)
                || floor(aabb.minZ) != floor(bb.minZ - 1.0) || ceil(aabb.maxZ) != ceil(bb.maxZ + 1.0)) {
                System.out.println("EDGE MISMATCH at " + p);
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
