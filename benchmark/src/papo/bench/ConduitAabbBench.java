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
 * 批次40 / 0164: 潮涌核心 AABB 三次分配折叠为一次（两处）。
 * (a) applyEffects 效果范围：new AABB(x,y,z,x+1,y+1,z+1).inflate(i).expandTowards(0,h,0)
 *     → new AABB(x-i, y-i, z-i, x+1+i, y+1+i+h, z+1+i)。x/y/z/i/h 均为 int，
 *     原链每步为精确整数 double 运算，折叠式 int 求和后一次拓宽，逐位相等（< 2^53）。
 * (b) getDestroyRangeAABB：new AABB(pos).inflate(8.0)
 *     → new AABB(x-8, y-8, z-8, x+1+8, y+1+8, z+1+8)。同理逐位相等。
 * 语义复刻：AABB 构造器 Math.min/max 归一化 + inflate/expandTowards 逐字复刻。
 * main 自检：负坐标/边界坐标/i=0/多档 h 矩阵 doubleToRawLongBits 逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ConduitAabbBench {

    /** AABB 语义复刻（构造归一化 + inflate + expandTowards 与原版一致）。 */
    static final class Aabb {
        final double minX;
        final double minY;
        final double minZ;
        final double maxX;
        final double maxY;
        final double maxZ;

        Aabb(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.minZ = Math.min(z1, z2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.maxZ = Math.max(z1, z2);
        }

        Aabb inflate(double d) {
            return new Aabb(this.minX - d, this.minY - d, this.minZ - d, this.maxX + d, this.maxY + d, this.maxZ + d);
        }

        Aabb expandTowards(double x, double y, double z) {
            double minX = this.minX;
            double minY = this.minY;
            double minZ = this.minZ;
            double maxX = this.maxX;
            double maxY = this.maxY;
            double maxZ = this.maxZ;
            if (x < 0.0) {
                minX += x;
            } else if (x > 0.0) {
                maxX += x;
            }
            if (y < 0.0) {
                minY += y;
            } else if (y > 0.0) {
                maxY += y;
            }
            if (z < 0.0) {
                minZ += z;
            } else if (z > 0.0) {
                maxZ += z;
            }
            return new Aabb(minX, minY, minZ, maxX, maxY, maxZ);
        }

        boolean bitEquals(Aabb o) {
            return Double.doubleToRawLongBits(this.minX) == Double.doubleToRawLongBits(o.minX)
                && Double.doubleToRawLongBits(this.minY) == Double.doubleToRawLongBits(o.minY)
                && Double.doubleToRawLongBits(this.minZ) == Double.doubleToRawLongBits(o.minZ)
                && Double.doubleToRawLongBits(this.maxX) == Double.doubleToRawLongBits(o.maxX)
                && Double.doubleToRawLongBits(this.maxY) == Double.doubleToRawLongBits(o.maxY)
                && Double.doubleToRawLongBits(this.maxZ) == Double.doubleToRawLongBits(o.maxZ);
        }
    }

    private int x;
    private int y;
    private int z;
    private int range; // i = 框架块数 / 7 * 16
    private int height; // level.getHeight()

    @Setup
    public void setup() {
        this.x = 500;
        this.y = 60;
        this.z = -300;
        this.range = 96; // 42 块框架：42/7*16 = 96
        this.height = 384;
    }

    // ---- (a) applyEffects ----
    private static Aabb applyEffectsBefore(int x, int y, int z, int i, int h) {
        return new Aabb(x, y, z, x + 1, y + 1, z + 1).inflate(i).expandTowards(0.0, h, 0.0);
    }

    private static Aabb applyEffectsAfter(int x, int y, int z, int i, int h) {
        return new Aabb(x - i, y - i, z - i, x + 1 + i, y + 1 + i + h, z + 1 + i);
    }

    @Benchmark
    public void before_applyEffectsAabb(Blackhole bh) {
        bh.consume(applyEffectsBefore(this.x, this.y, this.z, this.range, this.height));
    }

    @Benchmark
    public void after_applyEffectsAabb(Blackhole bh) {
        bh.consume(applyEffectsAfter(this.x, this.y, this.z, this.range, this.height));
    }

    // ---- (b) getDestroyRangeAABB ----
    private static Aabb destroyRangeBefore(int x, int y, int z) {
        return new Aabb(x, y, z, x + 1, y + 1, z + 1).inflate(8.0); // AABB(BlockPos).inflate(8.0)
    }

    private static Aabb destroyRangeAfter(int x, int y, int z) {
        return new Aabb(x - 8, y - 8, z - 8, x + 1 + 8, y + 1 + 8, z + 1 + 8);
    }

    @Benchmark
    public void before_destroyRangeAabb(Blackhole bh) {
        bh.consume(destroyRangeBefore(this.x, this.y, this.z));
    }

    @Benchmark
    public void after_destroyRangeAabb(Blackhole bh) {
        bh.consume(destroyRangeAfter(this.x, this.y, this.z));
    }

    /** 逐位等价自检：坐标 × 范围 × 高度矩阵。 */
    public static void main(String[] args) {
        int[] coords = {0, 1, -1, 12345, -12345, 30000000, -30000000};
        int[] ranges = {0, 16, 32, 96};
        int[] heights = {256, 384, 4064};
        for (int cx : coords) {
            for (int cy : new int[]{-64, 0, 60, 320, 2032}) {
                for (int cz : coords) {
                    for (int i : ranges) {
                        for (int h : heights) {
                            if (!applyEffectsBefore(cx, cy, cz, i, h).bitEquals(applyEffectsAfter(cx, cy, cz, i, h))) {
                                System.out.println("MISMATCH applyEffects @" + cx + "," + cy + "," + cz + " i=" + i + " h=" + h);
                                System.exit(1);
                            }
                        }
                    }
                    if (!destroyRangeBefore(cx, cy, cz).bitEquals(destroyRangeAfter(cx, cy, cz))) {
                        System.out.println("MISMATCH destroyRange @" + cx + "," + cy + "," + cz);
                        System.exit(1);
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
