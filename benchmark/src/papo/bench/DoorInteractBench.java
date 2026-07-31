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
 * 批次36: InteractWithDoor 两处优化。
 * (a) 每 tick（PATH 记忆存在时）previousNode.asBlockPos() + nextNode.asBlockPos()
 *     两个不可变 BlockPos 仅供 getBlockState 坐标读 → per-behavior scratch
 *     MutableBlockPos 复用（门分支才分配 immutable，此处测非门主流路径）。
 * (b) closeDoors/isMobComingThroughDoor 中 node.asBlockPos().equals(pos) →
 *     节点字段坐标直读比较（Vec3i.equals 即坐标比较）。
 * 复刻：Node（final int x/y/z）、BlockPos 分配、getBlockState 坐标散列读。
 * main 自检：(a) 两路径"方块查询"结果一致；(b) 等值比较布尔一致（含不等坐标）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DoorInteractBench {

    /** Node 语义复刻（坐标为 public final int）。 */
    static final class Node {
        final int x, y, z;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        BlockPos asBlockPos() { return new BlockPos(this.x, this.y, this.z); }
    }

    /** BlockPos 语义复刻（不可变，equals = 坐标比较）。 */
    static class BlockPos {
        final int x, y, z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof BlockPos other)) return false;
            return this.x == other.x && this.y == other.y && this.z == other.z;
        }
        @Override public int hashCode() { return (this.x * 31 + this.y) * 31 + this.z; }
    }

    /** MutableBlockPos 语义复刻。 */
    static final class MutableBlockPos extends BlockPos {
        MutableBlockPos() { super(0, 0, 0); }
        int mx, my, mz;
        MutableBlockPos set(int x, int y, int z) { this.mx = x; this.my = y; this.mz = z; return this; }
    }

    private final Node[] previousNodes = new Node[64];
    private final Node[] nextNodes = new Node[64];
    private final BlockPos[] doorPositions = new BlockPos[64];
    private final MutableBlockPos scratch = new MutableBlockPos();

    public DoorInteractBench() {
        for (int i = 0; i < 64; i++) {
            this.previousNodes[i] = new Node(i - 32, 64 + (i % 3), i);
            this.nextNodes[i] = new Node(i - 31, 64 + ((i + 1) % 3), i + 1);
            this.doorPositions[i] = new BlockPos(i - 32, 64 + (i % 3), i); // 半数与 previous 重合
        }
    }

    /** getBlockState 语义复刻：只读坐标。 */
    private static int blockAt(int x, int y, int z) {
        return (x * 31 + y * 17 + z) & 0xFF;
    }

    // (a) 每 tick 双 asBlockPos

    @Benchmark
    public int before_asBlockPosPerTick(Blackhole bh) {
        int acc = 0;
        for (int i = 0; i < 64; i++) {
            BlockPos prev = this.previousNodes[i].asBlockPos();
            BlockPos next = this.nextNodes[i].asBlockPos();
            acc += blockAt(prev.x, prev.y, prev.z) + blockAt(next.x, next.y, next.z);
            bh.consume(prev);
            bh.consume(next);
        }
        return acc;
    }

    @Benchmark
    public int after_scratchPos(Blackhole bh) {
        int acc = 0;
        for (int i = 0; i < 64; i++) {
            this.scratch.set(this.previousNodes[i].x, this.previousNodes[i].y, this.previousNodes[i].z);
            acc += blockAt(this.scratch.mx, this.scratch.my, this.scratch.mz);
            this.scratch.set(this.nextNodes[i].x, this.nextNodes[i].y, this.nextNodes[i].z);
            acc += blockAt(this.scratch.mx, this.scratch.my, this.scratch.mz);
        }
        bh.consume(this.scratch);
        return acc;
    }

    // (b) asBlockPos().equals(pos) 坐标直读

    @Benchmark
    public int before_allocEquals(Blackhole bh) {
        int equal = 0;
        for (int i = 0; i < 64; i++) {
            if (!this.previousNodes[i].asBlockPos().equals(this.doorPositions[i])
                && !this.nextNodes[i].asBlockPos().equals(this.doorPositions[i])) {
                equal++;
            }
        }
        bh.consume(equal);
        return equal;
    }

    @Benchmark
    public int after_coordCompare(Blackhole bh) {
        int equal = 0;
        for (int i = 0; i < 64; i++) {
            Node prev = this.previousNodes[i];
            Node next = this.nextNodes[i];
            BlockPos pos = this.doorPositions[i];
            if ((prev.x != pos.x || prev.y != pos.y || prev.z != pos.z)
                && (next.x != pos.x || next.y != pos.y || next.z != pos.z)) {
                equal++;
            }
        }
        bh.consume(equal);
        return equal;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        DoorInteractBench bench = new DoorInteractBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_asBlockPosPerTick(bh) != bench.after_scratchPos(bh)) {
            System.out.println("MISMATCH scratch path"); System.exit(1);
        }
        if (bench.before_allocEquals(bh) != bench.after_coordCompare(bh)) {
            System.out.println("MISMATCH coord compare"); System.exit(1);
        }
        // 逐点等值语义
        for (int i = 0; i < 64; i++) {
            Node n = bench.previousNodes[i];
            BlockPos p = bench.doorPositions[i];
            boolean allocEq = n.asBlockPos().equals(p);
            boolean coordEq = n.x == p.x && n.y == p.y && n.z == p.z;
            if (allocEq != coordEq) { System.out.println("MISMATCH equals @" + i); System.exit(1); }
        }
        System.out.println("ALL OK");
    }
}
