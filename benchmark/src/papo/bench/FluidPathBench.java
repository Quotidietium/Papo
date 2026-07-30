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
 * 0104: Entity.updateFluidHeightAndDoFluidPushing 每调用 3 处分配消除。
 * before: getBoundingBox().deflate(1e-3) 分配新 AABB + new MutableBlockPos + new Object[1][] 包装数组。
 * after:  边界 double 内联（位级同运算）+ 复用 scratch pos + 单 chunk 直引用。
 * 基准复刻方法序言的分配与边界算术（不含世界访问），main 自检位级等价。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FluidPathBench {

    /** AABB 语义复刻（inflate/deflate 运算顺序与 net.minecraft.world.phys.AABB 一致）。 */
    static final class Box {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
        Box inflate(double x, double y, double z) {
            double d = this.minX - x, d1 = this.minY - y, d2 = this.minZ - z;
            double d3 = this.maxX + x, d4 = this.maxY + y, d5 = this.maxZ + z;
            return new Box(d, d1, d2, d3, d4, d5);
        }
        Box inflate(double value) { return this.inflate(value, value, value); }
        Box deflate(double value) { return this.inflate(-value); }
    }

    /** BlockPos.MutableBlockPos 语义复刻。 */
    static final class MutablePos {
        int x, y, z;
        void set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private Box base;                 // 实体 bb（每 tick 已存在，非本次分配）
    private final Object[] chunkSections = new Object[24]; // 单 chunk 的 sections 数组（已存在）
    private MutablePos scratchPos;    // after: 逐实体复用
    private int tick;

    @Setup
    public void setup() {
        this.base = new Box(100.25, 63.0, -40.75, 100.85, 64.8, -40.15);
        this.scratchPos = new MutablePos();
    }

    private static int mthFloor(double v) { return (int) Math.floor(v); }
    private static int mthCeil(double v) { return (int) Math.ceil(v); }

    /** 原实现序言：deflate 分配 + 新 MutablePos + 包装数组分配与填充。 */
    @Benchmark
    public int before_prologue(Blackhole bh) {
        Box boundingBox = this.base.deflate(1.0E-3);
        int minBlockX = mthFloor(boundingBox.minX);
        int minBlockY = Math.max(-64, mthFloor(boundingBox.minY));
        int minBlockZ = mthFloor(boundingBox.minZ);
        int maxBlockX = mthCeil(boundingBox.maxX) - 1;
        int maxBlockY = Math.min(320, mthCeil(boundingBox.maxY) - 1);
        int maxBlockZ = mthCeil(boundingBox.maxZ) - 1;

        MutablePos mutablePos = new MutablePos();
        mutablePos.set(minBlockX, minBlockY, minBlockZ);

        int minChunkX = minBlockX >> 4, maxChunkX = maxBlockX >> 4;
        int minChunkZ = minBlockZ >> 4, maxChunkZ = maxBlockZ >> 4;
        int chunkLenX = maxChunkX - minChunkX + 1;
        int chunkOffset = -(minChunkX + chunkLenX * minChunkZ);
        Object[][] sections = new Object[chunkLenX * (maxChunkZ - minChunkZ + 1)][];
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                sections[x + chunkLenX * z + chunkOffset] = this.chunkSections;
            }
        }
        bh.consume(sections);
        bh.consume(mutablePos);
        return minBlockX + minBlockY + minBlockZ + maxBlockX + maxBlockY + maxBlockZ;
    }

    /** Papo 0104：边界 double 内联 + 复用 scratch pos + 单 chunk 直引用。 */
    @Benchmark
    public int after_prologue(Blackhole bh) {
        Box papoBox = this.base;
        double boundingBoxMinX = papoBox.minX - -1.0E-3;
        double boundingBoxMinY = papoBox.minY - -1.0E-3;
        double boundingBoxMinZ = papoBox.minZ - -1.0E-3;
        double boundingBoxMaxX = papoBox.maxX + -1.0E-3;
        double boundingBoxMaxY = papoBox.maxY + -1.0E-3;
        double boundingBoxMaxZ = papoBox.maxZ + -1.0E-3;

        int minBlockX = mthFloor(boundingBoxMinX);
        int minBlockY = Math.max(-64, mthFloor(boundingBoxMinY));
        int minBlockZ = mthFloor(boundingBoxMinZ);
        int maxBlockX = mthCeil(boundingBoxMaxX) - 1;
        int maxBlockY = Math.min(320, mthCeil(boundingBoxMaxY) - 1);
        int maxBlockZ = mthCeil(boundingBoxMaxZ) - 1;

        MutablePos mutablePos = this.scratchPos;
        mutablePos.set(minBlockX, minBlockY, minBlockZ);

        int minChunkX = minBlockX >> 4, maxChunkX = maxBlockX >> 4;
        int minChunkZ = minBlockZ >> 4, maxChunkZ = maxBlockZ >> 4;
        int chunkLenX = maxChunkX - minChunkX + 1;
        int chunkOffset = -(minChunkX + chunkLenX * minChunkZ);
        Object[] singleChunkSections;
        Object[][] sections;
        if (minChunkX == maxChunkX && minChunkZ == maxChunkZ) {
            singleChunkSections = this.chunkSections;
            sections = null;
        } else {
            singleChunkSections = null;
            sections = new Object[chunkLenX * (maxChunkZ - minChunkZ + 1)][];
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                for (int x = minChunkX; x <= maxChunkX; x++) {
                    sections[x + chunkLenX * z + chunkOffset] = this.chunkSections;
                }
            }
        }
        Object[] resolved = sections != null ? sections[(minBlockX >> 4) + chunkLenX * (minBlockZ >> 4) + chunkOffset] : singleChunkSections;
        bh.consume(resolved);
        return minBlockX + minBlockY + minBlockZ + maxBlockX + maxBlockY + maxBlockZ;
    }

    /** 等价性自检：边界位级一致 + 单 chunk 索引恒 0 + 多 chunk 索引布局一致。 */
    public static void main(String[] args) {
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 100000; i++) {
            double cx = rnd.nextDouble() * 6000 - 3000, cy = rnd.nextDouble() * 400 - 80, cz = rnd.nextDouble() * 6000 - 3000;
            double w = rnd.nextDouble() * 2 + 0.1, h = rnd.nextDouble() * 3 + 0.1;
            Box bb = new Box(cx, cy, cz, cx + w, cy + h, cz + w);
            Box deflated = bb.deflate(1.0E-3);
            // after 路径的内联运算
            double minX = bb.minX - -1.0E-3, minY = bb.minY - -1.0E-3, minZ = bb.minZ - -1.0E-3;
            double maxX = bb.maxX + -1.0E-3, maxY = bb.maxY + -1.0E-3, maxZ = bb.maxZ + -1.0E-3;
            if (Double.doubleToRawLongBits(deflated.minX) != Double.doubleToRawLongBits(minX)
                || Double.doubleToRawLongBits(deflated.minY) != Double.doubleToRawLongBits(minY)
                || Double.doubleToRawLongBits(deflated.minZ) != Double.doubleToRawLongBits(minZ)
                || Double.doubleToRawLongBits(deflated.maxX) != Double.doubleToRawLongBits(maxX)
                || Double.doubleToRawLongBits(deflated.maxY) != Double.doubleToRawLongBits(maxY)
                || Double.doubleToRawLongBits(deflated.maxZ) != Double.doubleToRawLongBits(maxZ)) {
                System.out.println("BOUNDS MISMATCH at " + i);
                System.exit(1);
            }
            // 索引布局：任意 box 范围内每个位置的索引 = 线性行优先；单 chunk 时恒 0
            int minBlockX = mthFloor(minX), maxBlockX = mthCeil(maxX) - 1;
            int minBlockZ = mthFloor(minZ), maxBlockZ = mthCeil(maxZ) - 1;
            int minChunkX = minBlockX >> 4, maxChunkX = maxBlockX >> 4;
            int minChunkZ = minBlockZ >> 4, maxChunkZ = maxBlockZ >> 4;
            int chunkLenX = maxChunkX - minChunkX + 1;
            int chunkOffset = -(minChunkX + chunkLenX * minChunkZ);
            boolean single = minChunkX == maxChunkX && minChunkZ == maxChunkZ;
            for (int x = minBlockX; x <= maxBlockX; x++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    int idx = (x >> 4) + chunkLenX * (z >> 4) + chunkOffset;
                    int expected = ((x >> 4) - minChunkX) + chunkLenX * ((z >> 4) - minChunkZ);
                    if (idx != expected || (single && idx != 0)) {
                        System.out.println("INDEX MISMATCH x=" + x + " z=" + z + " idx=" + idx + " expected=" + expected + " single=" + single);
                        System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
