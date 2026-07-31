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
 * 0116: HopperBlockEntity.getEntityContainer 实体容器搜索 AABB 缓存。
 * 原实现漏斗 suck（getSourceContainer）与 eject（getAttachedContainer→getContainerAt 3-arg）
 * 每条搬运周期各构造一个 new AABB(x-0.5, y-0.5, z-0.5, x+0.5, y+0.5, z+0.5)。
 * Papo：suck 侧挂 BE 惰性字段（worldPosition final 故常量）；eject 侧按 searchPosition 键控。
 * main 自检：缓存 AABB 与逐次构造逐分量 bit 级一致（含 eject 键控重算）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class HopperAabbCacheBench {

    static final class Aabb {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    /** BlockPos 语义复刻（int 坐标 + Vec3i.equals）。 */
    static final class Pos {
        final int x, y, z;
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Pos p && this.x == p.x && this.y == p.y && this.z == p.z;
        }
    }

    /** 模拟漏斗 BE：worldPosition final，getLevelX/Y/Z = 坐标 + 0.5。 */
    static final class HopperBe {
        final int wx, wy, wz;
        Aabb suckAabb;          // Papo suck 侧缓存
        Pos ejectSearchPos;     // Papo eject 侧键控
        Aabb ejectAabb;
        HopperBe(int x, int y, int z) { this.wx = x; this.wy = y; this.wz = z; }
        double levelX() { return this.wx + 0.5; }
        double levelY() { return this.wy + 0.5; }
        double levelZ() { return this.wz + 0.5; }
        Aabb suckAabb() {
            Aabb a = this.suckAabb;
            if (a == null) {
                double x = levelX(), y = levelY() + 1.0, z = levelZ();
                a = this.suckAabb = new Aabb(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
            }
            return a;
        }
    }

    private final HopperBe hopper = new HopperBe(128, 64, -256);
    private final Pos ejectTarget = new Pos(128, 63, -256);

    /** 原实现（suck）：每次构造 AABB。 */
    @Benchmark
    public Aabb before_suckAlloc(Blackhole bh) {
        double x = this.hopper.levelX(), y = this.hopper.levelY() + 1.0, z = this.hopper.levelZ();
        Aabb aabb = new Aabb(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
        bh.consume(aabb);
        return aabb;
    }

    /** Papo 0116（suck）：BE 缓存。 */
    @Benchmark
    public Aabb after_suckCached(Blackhole bh) {
        return this.hopper.suckAabb();
    }

    /** 原实现（eject）：每次构造 AABB。 */
    @Benchmark
    public Aabb before_ejectAlloc(Blackhole bh) {
        Pos p = this.ejectTarget;
        double x = p.x + 0.5, y = p.y + 0.5, z = p.z + 0.5;
        Aabb aabb = new Aabb(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
        bh.consume(aabb);
        return aabb;
    }

    /** Papo 0116（eject）：按 searchPosition 键控缓存。 */
    @Benchmark
    public Aabb after_ejectCached(Blackhole bh) {
        Pos p = this.ejectTarget;
        Aabb aabb;
        if (p.equals(this.hopper.ejectSearchPos)) {
            aabb = this.hopper.ejectAabb;
        } else {
            double x = p.x + 0.5, y = p.y + 0.5, z = p.z + 0.5;
            aabb = new Aabb(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
            this.hopper.ejectSearchPos = p;
            this.hopper.ejectAabb = aabb;
        }
        return aabb;
    }

    /** 等价性自检：缓存值与逐次构造逐分量 bit 级一致；eject 键控在目标变化时正确重算。 */
    public static void main(String[] args) {
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 100000; i++) {
            HopperBe h = new HopperBe(rnd.nextInt(60000) - 30000, rnd.nextInt(384) - 64, rnd.nextInt(60000) - 30000);
            double x = h.levelX(), y = h.levelY() + 1.0, z = h.levelZ();
            Aabb fresh = new Aabb(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
            Aabb cached = h.suckAabb();
            Aabb cached2 = h.suckAabb(); // 二次调用同一实例
            if (cached != cached2 || !same(fresh, cached)) {
                System.out.println("SUCK MISMATCH at " + i);
                System.exit(1);
            }

            // eject：两个交替目标，键控必须各自重算且值正确
            Pos p1 = new Pos(h.wx, h.wy - 1, h.wz);
            Pos p2 = new Pos(h.wx + 1, h.wy, h.wz);
            for (Pos p : new Pos[]{p1, p2, p1, p2, p1}) {
                double ex = p.x + 0.5, ey = p.y + 0.5, ez = p.z + 0.5;
                Aabb expected = new Aabb(ex - 0.5, ey - 0.5, ez - 0.5, ex + 0.5, ey + 0.5, ez + 0.5);
                Aabb got;
                if (p.equals(h.ejectSearchPos)) {
                    got = h.ejectAabb;
                } else {
                    double gx = p.x + 0.5, gy = p.y + 0.5, gz = p.z + 0.5;
                    got = new Aabb(gx - 0.5, gy - 0.5, gz - 0.5, gx + 0.5, gy + 0.5, gz + 0.5);
                    h.ejectSearchPos = p;
                    h.ejectAabb = got;
                }
                if (!same(expected, got)) {
                    System.out.println("EJECT MISMATCH at " + i);
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }

    private static boolean same(Aabb a, Aabb b) {
        return Double.compare(a.minX, b.minX) == 0 && Double.compare(a.minY, b.minY) == 0 && Double.compare(a.minZ, b.minZ) == 0
            && Double.compare(a.maxX, b.maxX) == 0 && Double.compare(a.maxY, b.maxY) == 0 && Double.compare(a.maxZ, b.maxZ) == 0;
    }
}
