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
 * 批次41 / 0166: 漏斗吸取路径缓存。
 * (a) 方块漏斗 suck BlockPos：before 每次 BlockPos.containing(x+0.5, y+1.5, z+0.5)；
 *     after 缓存 worldPosition.above()（(x, y+1, z)，逐位等价——int+0.5/+1.0 均为精确 double）。
 * (b) 矿车 suck pos / suck AABB：before 每次 containing + SUCK_AABB.move；after 位置键控缓存。
 * (c) 矿车后备拾取 AABB：before 每次 bb.inflate(0.25,0,0.25)；after bb 引用键控缓存。
 * 语义复刻：静止矿车场景（农场收集车主流形态）——缓存每调用命中。
 * main 自检：多轮调用值一致；位置变化后键控失效并重算与 before 一致；above() 与
 *        containing(x+0.5,y+1.5,z+0.5) 坐标矩阵逐位一致；bb 更换后拾取缓存重算一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HopperSuckCacheBench {

    /** BlockPos 语义复刻。 */
    static final class BlockPos {
        final int x;
        final int y;
        final int z;

        BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        BlockPos above() {
            return new BlockPos(this.x, this.y + 1, this.z);
        }

        static BlockPos containing(double x, double y, double z) {
            return new BlockPos(floor(x), floor(y), floor(z));
        }

        static int floor(double v) {
            int i = (int) v;
            return v < i ? i - 1 : i;
        }
    }

    /** AABB 语义复刻。 */
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

        Aabb inflate(double x, double y, double z) {
            return new Aabb(this.minX - x, this.minY - y, this.minZ - z, this.maxX + x, this.maxY + y, this.maxZ + z);
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

    /** 矿车漏斗语义复刻（键控缓存 only；suckInItems 两阶段的查询语义与本基准无关）。 */
    static final class MinecartHopper {
        double x; // getLevelX() = getX()
        double y; // getLevelY() = getY() + 0.5（此处直接存放 getLevelY 值）
        double z;
        Aabb bb = new Aabb(0, 0, 0, 0.98, 0.7, 0.98);

        static final Aabb SUCK_AABB = new Aabb(0.0, 0.0, 0.0, 1.0, 1.3125, 1.0); // Block.column(16, 11, 32) 近似

        double keyX = Double.NaN;
        double keyY = Double.NaN;
        double keyZ = Double.NaN;
        BlockPos suckPos;
        Aabb suckAabb;
        Aabb pickupAabb;
        Aabb pickupAabbSource;

        boolean keyMatches(double x, double y, double z) {
            return this.suckPos != null && this.keyX == x && this.keyY == y && this.keyZ == z;
        }

        BlockPos papoSuckPos() {
            double x = this.x, y = this.y, z = this.z;
            if (!this.keyMatches(x, y, z)) {
                this.suckPos = BlockPos.containing(x, y + 1.0, z);
                this.suckAabb = null;
                this.keyX = x;
                this.keyY = y;
                this.keyZ = z;
            }
            return this.suckPos;
        }

        Aabb papoSuckAabb() {
            double x = this.x, y = this.y, z = this.z;
            if (!this.keyMatches(x, y, z)) {
                this.suckPos = BlockPos.containing(x, y + 1.0, z);
                this.keyX = x;
                this.keyY = y;
                this.keyZ = z;
            }
            Aabb aabb = this.suckAabb;
            if (aabb == null) {
                aabb = this.suckAabb = SUCK_AABB.move(x - 0.5, y - 0.5, z - 0.5);
            }
            return aabb;
        }

        Aabb papoPickupAabb() {
            Aabb bb = this.bb;
            if (this.pickupAabb == null || this.pickupAabbSource != bb) {
                this.pickupAabb = bb.inflate(0.25, 0.0, 0.25);
                this.pickupAabbSource = bb;
            }
            return this.pickupAabb;
        }
    }

    private MinecartHopper cart;
    private BlockPos bePos;

    @Setup
    public void setup() {
        this.cart = new MinecartHopper();
        this.cart.x = 100.5;
        this.cart.y = 64.5;
        this.cart.z = -200.5;
        this.bePos = new BlockPos(100, 64, -201);
        // 预热缓存（静止场景：基准测量命中路径）
        this.cart.papoSuckPos();
        this.cart.papoSuckAabb();
        this.cart.papoPickupAabb();
    }

    /** before：方块漏斗 suck pos 每次构造 + 矿车 pos/AABB/拾取 AABB 每次构造。 */
    @Benchmark
    public void before_allocPath(Blackhole bh) {
        BlockPos beSuck = BlockPos.containing(this.bePos.x + 0.5, this.bePos.y + 0.5 + 1.0, this.bePos.z + 0.5);
        BlockPos cartSuck = BlockPos.containing(this.cart.x, this.cart.y + 1.0, this.cart.z);
        Aabb suckAabb = MinecartHopper.SUCK_AABB.move(this.cart.x - 0.5, this.cart.y - 0.5, this.cart.z - 0.5);
        Aabb pickup = this.cart.bb.inflate(0.25, 0.0, 0.25);
        bh.consume(beSuck);
        bh.consume(cartSuck);
        bh.consume(suckAabb);
        bh.consume(pickup);
    }

    /** after：缓存命中路径。 */
    @Benchmark
    public void after_cachedPath(Blackhole bh) {
        bh.consume(this.bePos.above()); // 方块漏斗：每 BE 一次（此处近似为常量折叠，见等价论证）
        bh.consume(this.cart.papoSuckPos());
        bh.consume(this.cart.papoSuckAabb());
        bh.consume(this.cart.papoPickupAabb());
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        // (a) above() == containing(x+0.5, y+1.5, z+0.5) 坐标矩阵
        int[] coords = {0, 1, -1, 100, -300, 30000000, -30000000};
        for (int x : coords) {
            for (int y : new int[]{-64, 0, 64, 320, 2032}) {
                for (int z : coords) {
                    BlockPos viaAbove = new BlockPos(x, y, z).above();
                    BlockPos viaContaining = BlockPos.containing(x + 0.5, y + 0.5 + 1.0, z + 0.5);
                    if (viaAbove.x != viaContaining.x || viaAbove.y != viaContaining.y || viaAbove.z != viaContaining.z) {
                        System.out.println("MISMATCH above @" + x + "," + y + "," + z);
                        System.exit(1);
                    }
                }
            }
        }
        // (b) 键控缓存：多轮命中值一致；位置变化失效重算与 before 一致
        MinecartHopper cart = new MinecartHopper();
        cart.x = 100.5;
        cart.y = 64.5;
        cart.z = -200.5;
        for (int round = 0; round < 3; round++) {
            BlockPos cached = cart.papoSuckPos();
            BlockPos fresh = BlockPos.containing(cart.x, cart.y + 1.0, cart.z);
            if (cached.x != fresh.x || cached.y != fresh.y || cached.z != fresh.z) {
                System.out.println("MISMATCH suckPos round " + round);
                System.exit(1);
            }
            if (!cart.papoSuckAabb().bitEquals(MinecartHopper.SUCK_AABB.move(cart.x - 0.5, cart.y - 0.5, cart.z - 0.5))) {
                System.out.println("MISMATCH suckAabb round " + round);
                System.exit(1);
            }
            if (!cart.papoPickupAabb().bitEquals(cart.bb.inflate(0.25, 0.0, 0.25))) {
                System.out.println("MISMATCH pickupAabb round " + round);
                System.exit(1);
            }
        }
        // 位置变化（含 -0.0/NaN 键行为）
        double[][] moves = {{100.5, 64.5, -200.5}, {101.5, 64.5, -200.5}, {-0.0, 64.5, 0.0}, {Double.NaN, 64.5, -200.5}};
        for (double[] mv : moves) {
            cart.x = mv[0];
            cart.y = mv[1];
            cart.z = mv[2];
            cart.bb = new Aabb(mv[0], mv[1], mv[2], mv[0] + 0.98, mv[1] + 0.7, mv[2] + 0.98); // 移动替换 bb 对象
            BlockPos cached = cart.papoSuckPos();
            BlockPos fresh = BlockPos.containing(cart.x, cart.y + 1.0, cart.z);
            if (cached.x != fresh.x || cached.y != fresh.y || cached.z != fresh.z) {
                System.out.println("MISMATCH suckPos after move");
                System.exit(1);
            }
            if (!cart.papoSuckAabb().bitEquals(MinecartHopper.SUCK_AABB.move(cart.x - 0.5, cart.y - 0.5, cart.z - 0.5))) {
                System.out.println("MISMATCH suckAabb after move");
                System.exit(1);
            }
            if (!cart.papoPickupAabb().bitEquals(cart.bb.inflate(0.25, 0.0, 0.25))) {
                System.out.println("MISMATCH pickupAabb after move");
                System.exit(1);
            }
            // NaN 键：连续两次调用必须各自重算（NaN != NaN）且值仍一致
            if (Double.isNaN(mv[0])) {
                BlockPos again = cart.papoSuckPos();
                if (again.x != fresh.x || again.y != fresh.y || again.z != fresh.z) {
                    System.out.println("MISMATCH suckPos NaN recompute");
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
