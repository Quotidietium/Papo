package papo.bench;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 * 0111: 寻路缓存 computeIfAbsent 捕获 lambda → get+put 两段式。
 * fastutil computeIfAbsent 体积大不内联，捕获 lambda 即使缓存命中也真实分配。
 * 场景模拟 NodeEvaluator.getNode（Int2ObjectMap<Node>）与 getCachedPathType（Long2ObjectMap<枚举>）。
 * main 自检：两条路径缓存内容与命中结果完全一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TwoPhaseCacheBench {

    static final class Node {
        final int x, y, z;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    enum PathType { OPEN, BLOCKED, WALKABLE }

    private final Int2ObjectMap<Node> nodes = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectMap<PathType> pathTypes = new Long2ObjectOpenHashMap<>();
    private int tick;

    private static int createHash(int x, int y, int z) {
        // Node.createHash 语义复刻（y 低位 + x/z 哈希混合）
        return y & 0xFF | (x & 0x7FFF) << 8 | (z & 0x7FFF) << 24;
    }

    @Setup
    public void setup() {
        // 预填充：寻路缓存命中为主
        for (int i = 0; i < 256; i++) {
            this.nodes.put(createHash(i, 64, i), new Node(i, 64, i));
            this.pathTypes.put((long) i * 31, PathType.OPEN);
        }
    }

    private int nextX() { this.tick++; return (this.tick * 7) & 255; }

    /** 原实现：computeIfAbsent 捕获 lambda（命中也分配）。 */
    @Benchmark
    public Node before_computeIfAbsent(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        Node node = this.nodes.computeIfAbsent(createHash(x, y, z), key -> new Node(x, y, z));
        bh.consume(node);
        return node;
    }

    /** Papo 0111：get+put 两段式。 */
    @Benchmark
    public Node after_twoPhase(Blackhole bh) {
        int x = nextX(), y = 64, z = x;
        final int hash = createHash(x, y, z);
        Node node = this.nodes.get(hash);
        if (node == null) {
            node = new Node(x, y, z);
            this.nodes.put(hash, node);
        }
        bh.consume(node);
        return node;
    }

    /** 原实现（long 键枚举值变体）。 */
    @Benchmark
    public PathType before_computeIfAbsentLong(Blackhole bh) {
        int x = nextX();
        long key = (long) x * 31;
        PathType t = this.pathTypes.computeIfAbsent(key, l -> PathType.WALKABLE);
        bh.consume(t);
        return t;
    }

    /** Papo 0111（long 键枚举值变体）。 */
    @Benchmark
    public PathType after_twoPhaseLong(Blackhole bh) {
        int x = nextX();
        final long key = (long) x * 31;
        PathType t = this.pathTypes.get(key);
        if (t == null) {
            t = PathType.WALKABLE;
            this.pathTypes.put(key, t);
        }
        bh.consume(t);
        return t;
    }

    /** 等价性自检：混合命中/未命中序列下两条路径返回值与缓存内容一致。 */
    public static void main(String[] args) {
        Int2ObjectOpenHashMap<Node> a = new Int2ObjectOpenHashMap<>();
        Int2ObjectOpenHashMap<Node> b = new Int2ObjectOpenHashMap<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 100000; i++) {
            int x = rnd.nextInt(64), y = 64, z = x;
            int hash = createHash(x, y, z);
            Node na = a.computeIfAbsent(hash, key -> new Node(x, y, z));
            Node nb = b.get(hash);
            if (nb == null) { nb = new Node(x, y, z); b.put(hash, nb); }
            if (na.x != nb.x || na.y != nb.y || na.z != nb.z) {
                System.out.println("MISMATCH at " + i);
                System.exit(1);
            }
        }
        if (a.size() != b.size()) {
            System.out.println("CACHE SIZE MISMATCH");
            System.exit(1);
        }
        for (java.util.Map.Entry<Integer, Node> e : a.entrySet()) {
            Node nb = b.get(e.getKey().intValue());
            Node na = e.getValue();
            if (nb == null || na.x != nb.x || na.y != nb.y || na.z != nb.z) {
                System.out.println("CACHE CONTENT MISMATCH");
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
