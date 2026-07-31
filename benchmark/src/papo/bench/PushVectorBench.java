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
 * 0123: Entity.push(x,y,z,null) 路径消除无条件 org.bukkit.util.Vector 分配。
 * 原实现：pushingEntity==null 也先 new Vector(x,y,z)，再 add(delta.getX(), delta.getY(), delta.getZ())；
 * Vector 构造仅存字段、getX/Y/Z 原样读回 → add(x,y,z) 位级等价。
 * main 自检：随机输入（含非有限值边界由调用方 isFinite 门控，不在本模型内）两路径逐位一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PushVectorBench {

    /** Vector 语义复刻（构造存字段、getX/Y/Z 读回）。 */
    static final class Vector {
        final double x, y, z;
        Vector(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        double getX() { return this.x; }
        double getY() { return this.y; }
        double getZ() { return this.z; }
    }

    /** Vec3.add(double,double,double) 语义复刻（存储字段 += 分量）。 */
    static final class Vec3 {
        double x, y, z;
        Vec3 add(double ax, double ay, double az) { this.x += ax; this.y += ay; this.z += az; return this; }
    }

    private final Vec3 deltaMovement = new Vec3();
    private int tick;

    private double nextX() { this.tick++; return (this.tick & 1023) * 0.001 - 0.5; }

    /** 原实现：new Vector + 读回分量。 */
    @Benchmark
    public Vec3 before_vectorAlloc(Blackhole bh) {
        double x = nextX(), y = 0.08, z = -x;
        Vector delta = new Vector(x, y, z);
        this.deltaMovement.add(delta.getX(), delta.getY(), delta.getZ());
        bh.consume(delta);
        return this.deltaMovement;
    }

    /** Papo 0123：直接分量 add。 */
    @Benchmark
    public Vec3 after_scalarAdd(Blackhole bh) {
        double x = nextX(), y = 0.08, z = -x;
        this.deltaMovement.add(x, y, z);
        return this.deltaMovement;
    }

    /** 等价性自检：两路径累加结果逐位一致。 */
    public static void main(String[] args) {
        java.util.Random rnd = new java.util.Random(7);
        Vec3 a = new Vec3(), b = new Vec3();
        for (int i = 0; i < 1000000; i++) {
            double x = rnd.nextDouble() * 2 - 1, y = rnd.nextDouble() * 2 - 1, z = rnd.nextDouble() * 2 - 1;
            Vector delta = new Vector(x, y, z);
            a.add(delta.getX(), delta.getY(), delta.getZ());
            b.add(x, y, z);
        }
        if (Double.compare(a.x, b.x) != 0 || Double.compare(a.y, b.y) != 0 || Double.compare(a.z, b.z) != 0) {
            System.out.println("MISMATCH");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
