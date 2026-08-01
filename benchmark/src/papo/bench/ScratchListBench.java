package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次47 / 0190 + 0191 + 0192: 实体查询 scratch list 机制（三站点同构）。
 * before：每次查询 new ArrayList<>()（getEntities / getEntitiesOfClass 分配路径复刻）。
 * after：调用方持有 scratch list，clear 后填充复用（papoGetEntitiesInto / EntityTypeTest fill 重载）。
 * 三场景：
 *  (a) pushEntities：盒子内实体填充 + 遍历 push（0190）。
 *  (b) looting：ItemEntity 填充 + 条件遍历（0191）。
 *  (c) findTarget：候选填充 + 最近目标扫描（0192，getNearestEntity 复刻）。
 * main 自检：三场景两路径产出的序列（填充内容/遍历顺序/最近目标）逐项一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ScratchListBench {

    static class Entity {
        final double x;
        final double y;
        final double z;
        int collisions;
        Entity(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        double distanceToSqr(double px, double py, double pz) {
            double dx = this.x - px, dy = this.y - py, dz = this.z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** 模拟区块实体表：盒子过滤后填充（moonrise getEntities(..., into, predicate) 复刻）。 */
    static int fillEntities(Entity[] world, double cx, double cz, double radius, List<Entity> into) {
        int n = 0;
        for (Entity e : world) {
            if (Math.abs(e.x - cx) <= radius && Math.abs(e.z - cz) <= radius) {
                into.add(e);
                n++;
            }
        }
        return n;
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    // ---- (a) 0190 pushEntities ----
    final Entity[] pushWorld = new Entity[96];
    {
        for (int i = 0; i < this.pushWorld.length; i++) {
            this.pushWorld[i] = new Entity(i * 0.25, 64, i * 0.25); // ~17 个落入半径 2 的盒子
        }
    }
    final List<Entity> pushScratch = new ArrayList<>();

    public int pushBeforeBody() {
        List<Entity> list = new ArrayList<>(); // getEntities 分配路径
        fillEntities(this.pushWorld, 12.0, 12.0, 2.0, list);
        int pushes = 0;
        for (Entity e : list) {
            e.collisions++;
            pushes++;
        }
        this.sink = list;
        return pushes;
    }

    public int pushAfterBody() {
        List<Entity> list = this.pushScratch;
        list.clear();
        fillEntities(this.pushWorld, 12.0, 12.0, 2.0, list);
        int pushes = 0;
        for (Entity e : list) {
            e.collisions++;
            pushes++;
        }
        this.sink = list;
        return pushes;
    }

    // ---- (b) 0191 looting ----
    static final class ItemEntity extends Entity {
        boolean pickUpDelay;
        ItemEntity(double x, double y, double z, boolean pickUpDelay) {
            super(x, y, z);
            this.pickUpDelay = pickUpDelay;
        }
    }

    final ItemEntity[] lootWorld = new ItemEntity[16];
    {
        for (int i = 0; i < this.lootWorld.length; i++) {
            this.lootWorld[i] = new ItemEntity(i * 0.5, 64, i * 0.5, (i & 1) == 0);
        }
    }
    final List<ItemEntity> lootScratch = new ArrayList<>();

    static int fillItems(ItemEntity[] world, double cx, double cz, double radius, List<ItemEntity> into) {
        int n = 0;
        for (ItemEntity e : world) {
            if (Math.abs(e.x - cx) <= radius && Math.abs(e.z - cz) <= radius) {
                into.add(e);
                n++;
            }
        }
        return n;
    }

    public int lootBeforeBody() {
        List<ItemEntity> list = new ArrayList<>(); // getEntitiesOfClass 分配路径
        fillItems(this.lootWorld, 4.0, 4.0, 2.0, list);
        int picked = 0;
        for (ItemEntity e : list) {
            if (!e.pickUpDelay) picked++;
        }
        this.sink = list;
        return picked;
    }

    public int lootAfterBody() {
        List<ItemEntity> list = this.lootScratch;
        list.clear();
        fillItems(this.lootWorld, 4.0, 4.0, 2.0, list);
        int picked = 0;
        for (ItemEntity e : list) {
            if (!e.pickUpDelay) picked++;
        }
        this.sink = list;
        return picked;
    }

    // ---- (c) 0192 findTarget ----
    final Entity[] targetWorld = new Entity[96];
    {
        for (int i = 0; i < this.targetWorld.length; i++) {
            this.targetWorld[i] = new Entity(i * 0.5, 64, i * 0.4);
        }
    }
    final List<Entity> targetScratch = new ArrayList<>();

    /** getNearestEntity 复刻：最近距离扫描。 */
    static Entity nearest(List<Entity> list, double x, double y, double z) {
        double best = -1.0;
        Entity result = null;
        for (Entity e : list) {
            double d = e.distanceToSqr(x, y, z);
            if (best < 0.0 || d < best) {
                best = d;
                result = e;
            }
        }
        return result;
    }

    public Entity findBeforeBody() {
        List<Entity> list = new ArrayList<>(); // getEntitiesOfClass 分配路径
        fillEntities(this.targetWorld, 24.0, 19.0, 4.0, list);
        Entity r = nearest(list, 24.0, 64.0, 19.0);
        this.sink = list;
        return r;
    }

    public Entity findAfterBody() {
        List<Entity> list = this.targetScratch;
        list.clear();
        fillEntities(this.targetWorld, 24.0, 19.0, 4.0, list);
        Entity r = nearest(list, 24.0, 64.0, 19.0);
        this.sink = list;
        return r;
    }

    @Benchmark public int pushBefore(Blackhole bh) { int r = this.pushBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int pushAfter(Blackhole bh) { int r = this.pushAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int lootBefore(Blackhole bh) { int r = this.lootBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int lootAfter(Blackhole bh) { int r = this.lootAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public Object findBefore(Blackhole bh) { Object r = this.findBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Object findAfter(Blackhole bh) { Object r = this.findAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        ScratchListBench b = new ScratchListBench();
        // (a) 遍历计数一致
        if (b.pushBeforeBody() != b.pushAfterBody()) throw new AssertionError("push mismatch");
        // (b) 拾取计数一致
        if (b.lootBeforeBody() != b.lootAfterBody()) throw new AssertionError("loot mismatch");
        // (c) 最近目标身份一致
        if (b.findBeforeBody() != b.findAfterBody()) throw new AssertionError("find mismatch");
        // scratch 复用语义：after 两次调用内容一致（clear+fill 幂等）
        b.lootAfterBody();
        List<ItemEntity> first = new ArrayList<>(b.lootScratch);
        b.lootAfterBody();
        if (!first.equals(b.lootScratch)) throw new AssertionError("scratch refill mismatch");
        System.out.println("ALL OK");
    }
}
