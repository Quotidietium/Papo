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
 * 批次41 / 0168: 活塞碰撞实体扫描盒三次分配折叠为一次。
 * before（逐字复刻原版链）：moved = bounds.move(ox,oy,oz)；
 *        area = PistonMath.getMovementArea(moved, dir, delta)（六向分支）；
 *        scan = area.minmax(moved)（逐轴 Math.min/max 并集）。
 * after：papoMovementUnionBox —— 运动轴 lo = min(m.edge + min(d',0), m.min)、
 *        hi = max(m.edge + max(d',0), m.max)（负向 edge=min，正向 edge=max），
 *        其余轴直给 m.min/m.max；左结合加法和 Math.min/max 操作数序与链一致。
 * main 自检：6 方向 × delta ∈ {0, ±小, ±大} × 多种 bounds（细盒/偏置/大盒）矩阵逐位相等。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class PistonScanAabbBench {

    /** AABB 语义复刻（含 move 与 minmax）。 */
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

        Aabb move(double x, double y, double z) {
            return new Aabb(this.minX + x, this.minY + y, this.minZ + z, this.maxX + x, this.maxY + y, this.maxZ + z);
        }

        Aabb minmax(Aabb other) {
            return new Aabb(
                Math.min(this.minX, other.minX), Math.min(this.minY, other.minY), Math.min(this.minZ, other.minZ),
                Math.max(this.maxX, other.maxX), Math.max(this.maxY, other.maxY), Math.max(this.maxZ, other.maxZ)
            );
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

    /** 方向语义复刻：索引 0..5 = DOWN, UP, NORTH, SOUTH, WEST, EAST（与原版序一致无关，仅分支语义）。 */
    enum Dir {
        DOWN(0, -1, 0, -1),
        UP(0, 1, 0, 1),
        NORTH(0, 0, -1, -1),
        SOUTH(0, 0, 1, 1),
        WEST(-1, 0, 0, -1),
        EAST(1, 0, 0, 1);

        final int stepX;
        final int stepY;
        final int stepZ;
        final int axisStep; // AxisDirection.getStep()

        Dir(int stepX, int stepY, int stepZ, int axisStep) {
            this.stepX = stepX;
            this.stepY = stepY;
            this.stepZ = stepZ;
            this.axisStep = axisStep;
        }
    }

    // ---- before 链：逐字复刻 ----
    private static Aabb getMovementArea(Aabb bounds, Dir dir, double delta) {
        double d = delta * dir.axisStep;
        double min = Math.min(d, 0.0);
        double max = Math.max(d, 0.0);
        return switch (dir) {
            case WEST -> new Aabb(bounds.minX + min, bounds.minY, bounds.minZ, bounds.minX + max, bounds.maxY, bounds.maxZ);
            case EAST -> new Aabb(bounds.maxX + min, bounds.minY, bounds.minZ, bounds.maxX + max, bounds.maxY, bounds.maxZ);
            case DOWN -> new Aabb(bounds.minX, bounds.minY + min, bounds.minZ, bounds.maxX, bounds.minY + max, bounds.maxZ);
            case UP -> new Aabb(bounds.minX, bounds.maxY + min, bounds.minZ, bounds.maxX, bounds.maxY + max, bounds.maxZ);
            case NORTH -> new Aabb(bounds.minX, bounds.minY, bounds.minZ + min, bounds.maxX, bounds.maxY, bounds.minZ + max);
            case SOUTH -> new Aabb(bounds.minX, bounds.minY, bounds.maxZ + min, bounds.maxX, bounds.maxY, bounds.maxZ + max);
        };
    }

    private static Aabb before(Aabb bounds, double ox, double oy, double oz, Dir dir, double delta) {
        Aabb moved = bounds.move(ox, oy, oz);
        return getMovementArea(moved, dir, delta).minmax(moved);
    }

    // ---- after 折叠式 ----
    private static Aabb after(Aabb bounds, double ox, double oy, double oz, Dir dir, double delta) {
        double dMove = delta * dir.axisStep;
        double min = Math.min(dMove, 0.0);
        double max = Math.max(dMove, 0.0);
        return switch (dir) {
            case WEST -> new Aabb(
                Math.min(bounds.minX + ox + min, bounds.minX + ox), bounds.minY + oy, bounds.minZ + oz,
                Math.max(bounds.minX + ox + max, bounds.maxX + ox), bounds.maxY + oy, bounds.maxZ + oz
            );
            case EAST -> new Aabb(
                Math.min(bounds.maxX + ox + min, bounds.minX + ox), bounds.minY + oy, bounds.minZ + oz,
                Math.max(bounds.maxX + ox + max, bounds.maxX + ox), bounds.maxY + oy, bounds.maxZ + oz
            );
            case DOWN -> new Aabb(
                bounds.minX + ox, Math.min(bounds.minY + oy + min, bounds.minY + oy), bounds.minZ + oz,
                bounds.maxX + ox, Math.max(bounds.minY + oy + max, bounds.maxY + oy), bounds.maxZ + oz
            );
            case NORTH -> new Aabb(
                bounds.minX + ox, bounds.minY + oy, Math.min(bounds.minZ + oz + min, bounds.minZ + oz),
                bounds.maxX + ox, bounds.maxY + oy, Math.max(bounds.minZ + oz + max, bounds.maxZ + oz)
            );
            case SOUTH -> new Aabb(
                bounds.minX + ox, bounds.minY + oy, Math.min(bounds.maxZ + oz + min, bounds.minZ + oz),
                bounds.maxX + ox, bounds.maxY + oy, Math.max(bounds.maxZ + oz + max, bounds.maxZ + oz)
            );
            case UP -> new Aabb(
                bounds.minX + ox, Math.min(bounds.maxY + oy + min, bounds.minY + oy), bounds.minZ + oz,
                bounds.maxX + ox, Math.max(bounds.maxY + oy + max, bounds.maxY + oy), bounds.maxZ + oz
            );
        };
    }

    private Aabb bounds;
    private Dir dir;
    private double delta;
    private double ox;
    private double oy;
    private double oz;

    @Setup
    public void setup() {
        this.bounds = new Aabb(0.0, 0.0, 0.0, 1.0, 1.0, 1.0); // 活塞头碰撞形
        this.dir = Dir.EAST;
        this.delta = 0.5; // partialTick - progress
        this.ox = 100.0 + 0.5; // pos + progress*step（此处直接给出）
        this.oy = 64.0;
        this.oz = -200.0;
    }

    @Benchmark
    public void before_chain(Blackhole bh) {
        bh.consume(before(this.bounds, this.ox, this.oy, this.oz, this.dir, this.delta));
    }

    @Benchmark
    public void after_collapsed(Blackhole bh) {
        bh.consume(after(this.bounds, this.ox, this.oy, this.oz, this.dir, this.delta));
    }

    /** 逐位等价自检：全方向 × delta 符号 × bounds 变体矩阵。 */
    public static void main(String[] args) {
        Aabb[] boundsSet = {
            new Aabb(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
            new Aabb(0.25, 0.0, 0.25, 0.75, 0.5, 0.75), // 细盒
            new Aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5), // 跨零
            new Aabb(0.0, 0.0, 0.0, 0.001, 0.001, 0.001), // 极薄
        };
        double[] deltas = {0.0, 0.5, -0.5, 0.17, -0.999};
        double[][] offsets = {{100.5, 64.0, -200.0}, {-0.0, 0.0, -0.0}, {30000000.0, -64.0, 7.25}};
        for (Aabb b : boundsSet) {
            for (Dir dir : Dir.values()) {
                for (double delta : deltas) {
                    for (double[] off : offsets) {
                        if (!before(b, off[0], off[1], off[2], dir, delta).bitEquals(after(b, off[0], off[1], off[2], dir, delta))) {
                            System.out.println("MISMATCH dir=" + dir + " delta=" + delta + " off=" + off[0] + "," + off[1] + "," + off[2]);
                            System.exit(1);
                        }
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
