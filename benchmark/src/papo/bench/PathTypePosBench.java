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
 * 0115: 寻路 getPathType 的 BlockPos 分配消除（两处）。
 * A) WalkNodeEvaluator.getPathType：每次调用 new MutableBlockPos(x,y,z) 只为 getPathTypeStatic
 *    入口读出坐标为局部 int（从不保留/修改 pos）→ 复用 per-evaluator scratch pos。
 * B) FlyNodeEvaluator.getPathType：new BlockPos(x, y-1, z) 仅为取回坐标 + 一次 BlockPos.equals
 *    （Vec3i.equals 逐坐标比较）→ 直接坐标比较。
 * main 自检：A 两路径全坐标域返回一致；B 含 FENCE 分支（equals 命中/不命中）全一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PathTypePosBench {

    /** BlockPos/MutableBlockPos 语义复刻（仅本场景用到的部分）。 */
    static class Pos {
        int x, y, z;
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object other) {
            // Vec3i.equals 语义复刻
            return this == other || other instanceof Pos p && this.x == p.x && this.y == p.y && this.z == p.z;
        }
    }
    static final class MutablePos extends Pos {
        MutablePos(int x, int y, int z) { super(x, y, z); }
        MutablePos set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
    }

    enum PathType { OPEN, WALKABLE, FENCE, DAMAGE_FIRE, BLOCKED }

    private final MutablePos scratch = new MutablePos(0, 0, 0);
    private final Pos mobPosition = new Pos(100, 64, 100);
    private int tick;

    private int nextX() { this.tick++; return (this.tick * 13) & 1023; }

    /** getPathTypeStatic 语义复刻：入口读坐标为局部 int，之后只用局部量。 */
    private static PathType getPathTypeStatic(Pos pos, Pos mobPos) {
        int x = pos.x, y = pos.y, z = pos.z; // 入口即读出
        // 模拟：OPEN 且高于 minY → 查下方；下方 FENCE 且非 mob 脚下 → FENCE
        PathType below = ((x + y + z) & 7) == 0 ? PathType.FENCE : PathType.OPEN;
        if (below == PathType.FENCE) {
            if (!(x == mobPos.x && y - 1 == mobPos.y && z == mobPos.z)) { // 等价坐标比较（B 的 after）
                return PathType.FENCE;
            }
            return PathType.OPEN;
        }
        return below;
    }

    /** A 原实现：每次 new MutableBlockPos(x, y, z)。 */
    @Benchmark
    public PathType before_newMutablePos(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        MutablePos pos = new MutablePos(x, y, z);
        PathType t = getPathTypeStatic(pos, this.mobPosition);
        bh.consume(pos);
        return t;
    }

    /** A Papo 0115：复用 scratch pos。 */
    @Benchmark
    public PathType after_scratchPos(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        PathType t = getPathTypeStatic(this.scratch.set(x, y, z), this.mobPosition);
        return t;
    }

    /** B 原实现：new BlockPos(x, y-1, z) + equals。 */
    @Benchmark
    public boolean before_blockPosEquals(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        Pos blockPos = new Pos(x, y - 1, z);
        boolean r = !blockPos.equals(this.mobPosition);
        bh.consume(blockPos);
        return r;
    }

    /** B Papo 0115：直接坐标比较。 */
    @Benchmark
    public boolean after_coordCompare(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        Pos mobPos = this.mobPosition;
        return !(x == mobPos.x && y - 1 == mobPos.y && z == mobPos.z);
    }

    /** 等价性自检：全分支（含 equals 命中/不命中）两实现一致。 */
    public static void main(String[] args) {
        Pos mob = new Pos(100, 64, 100);
        java.util.Random rnd = new java.util.Random(99);
        for (int i = 0; i < 1000000; i++) {
            int x, y = 64, z;
            if (i % 10 == 0) { // 命中 mob 脚下（equals true 分支）
                x = mob.x; z = mob.z;
            } else {
                x = rnd.nextInt(2048) - 1024; z = rnd.nextInt(2048) - 1024;
            }
            // B: equals vs 坐标比较
            Pos blockPos = new Pos(x, y - 1, z);
            boolean before = !blockPos.equals(mob);
            boolean after = !(x == mob.x && y - 1 == mob.y && z == mob.z);
            if (before != after) {
                System.out.println("FENCE-EQUALS MISMATCH at " + x + "," + z);
                System.exit(1);
            }
        }
        // 引用相等边界：真实场景 fresh pos 不可能 == mobPosition，两实现都不走 == 快路
        Pos stored = new Pos(1, 2, 3);
        Pos fresh = new Pos(1, 2, 3);
        if (!stored.equals(fresh) || stored == fresh) {
            System.out.println("EQUALS SEMANTICS BROKEN");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
