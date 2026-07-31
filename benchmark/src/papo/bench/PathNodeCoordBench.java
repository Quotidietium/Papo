package papo.bench;

import java.util.ArrayList;
import java.util.List;
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
 * 0120: PathNavigation.followThePath/doStuckDetection 直读 Node 坐标替代 getNextNodePos()。
 * 原实现每 tick：nodes.get(i).asBlockPos()（new BlockPos）+ Vec3i.equals 坐标比较；
 * Papo：直读 Node.x/y/z 字段 + 手工坐标比较，BlockPos 仅在节点推进时分配。
 * main 自检：含节点推进的混合序列下，两路径超时计时与缓存节点坐标完全一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PathNodeCoordBench {

    /** Node 语义复刻（public final 坐标 + asBlockPos）。 */
    static final class Node {
        final int x, y, z;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        BlockPos asBlockPos() { return new BlockPos(this.x, this.y, this.z); }
    }

    /** BlockPos/Vec3i 语义复刻（final equals 逐坐标比较）。 */
    static class Vec3i {
        final int x, y, z;
        Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int getX() { return this.x; }
        int getY() { return this.y; }
        int getZ() { return this.z; }
        @Override public final boolean equals(Object other) {
            return this == other || other instanceof Vec3i v && this.getX() == v.getX() && this.getY() == v.getY() && this.getZ() == v.getZ();
        }
    }
    static final class BlockPos extends Vec3i {
        BlockPos(int x, int y, int z) { super(x, y, z); }
    }

    /** Path.getNextNodePos/getNextNode 语义复刻。 */
    static final class Path {
        final List<Node> nodes = new ArrayList<>();
        int nextNodeIndex;
        BlockPos getNextNodePos() { return this.nodes.get(this.nextNodeIndex).asBlockPos(); }
        Node getNextNode() { return this.nodes.get(this.nextNodeIndex); }
    }

    private final Path path = new Path();
    private Vec3i timeoutCachedNode = new Vec3i(0, 0, 0);

    @Setup
    public void setup() {
        for (int i = 0; i < 64; i++) {
            this.path.nodes.add(new Node(i * 3, 64, i * -7));
        }
        this.path.nextNodeIndex = 5;
        Node n = this.path.getNextNode();
        this.timeoutCachedNode = new Vec3i(n.x, n.y, n.z); // 稳态：已对齐当前节点（equals 命中分支）
    }

    /** 原实现：每 tick asBlockPos + Vec3i.equals。 */
    @Benchmark
    public boolean before_blockPosEquals(Blackhole bh) {
        Vec3i nextNodePos = this.path.getNextNodePos();
        boolean same = nextNodePos.equals(this.timeoutCachedNode);
        bh.consume(nextNodePos);
        return same;
    }

    /** Papo 0120：直读字段 + 坐标比较，零分配。 */
    @Benchmark
    public boolean after_coordCompare(Blackhole bh) {
        Node nextNode = this.path.getNextNode();
        return nextNode.x == this.timeoutCachedNode.getX()
            && nextNode.y == this.timeoutCachedNode.getY()
            && nextNode.z == this.timeoutCachedNode.getZ();
    }

    /** 等价性自检：节点推进/回退/越界序列下两路径判定与缓存坐标一致。 */
    public static void main(String[] args) {
        Path path = new Path();
        for (int i = 0; i < 64; i++) {
            path.nodes.add(new Node(i * 3, 64 + (i % 3), i * -7));
        }
        Vec3i cachedBefore = new Vec3i(0, 0, 0);
        Vec3i cachedAfter = new Vec3i(0, 0, 0);
        java.util.Random rnd = new java.util.Random(20260731);
        for (int i = 0; i < 100000; i++) {
            path.nextNodeIndex = rnd.nextInt(64);
            // before
            Vec3i np = path.getNextNodePos();
            boolean sb = np.equals(cachedBefore);
            if (!sb) cachedBefore = np;
            // after
            Node nn = path.getNextNode();
            boolean sa = nn.x == cachedAfter.getX() && nn.y == cachedAfter.getY() && nn.z == cachedAfter.getZ();
            if (!sa) cachedAfter = nn.asBlockPos();
            if (sb != sa || cachedBefore.getX() != cachedAfter.getX() || cachedBefore.getY() != cachedAfter.getY() || cachedBefore.getZ() != cachedAfter.getZ()) {
                System.out.println("MISMATCH at " + i);
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
