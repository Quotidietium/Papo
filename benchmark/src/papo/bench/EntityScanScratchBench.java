package papo.bench;

import java.util.ArrayList;
import java.util.Comparator;
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
 * 批次49 / 0200 + 0201 + 0202: 实体扫描 scratch-list 三站点（与批次47 ScratchListBench 同机制，不同站点规模/谓词）。
 * 0200 AvoidEntityGoal：getEntitiesOfClass + getNearestEntity（最近目标扫描）。
 * 0201 FollowParentGoal：getEntitiesOfClass + 线性最近成年个体扫描。
 * 0202 NearestItemSensor：getEntitiesOfClass(带谓词) + sort + 视线最近。
 * before：每次 new ArrayList（getEntitiesOfClass 分配路径）。after：scratch clear+fill。
 * main 自检：三站点两路径产出的目标身份/选择一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class EntityScanScratchBench {

    static class Entity {
        final int id;
        final double x;
        final double y;
        final double z;
        Entity(int id, double x, double y, double z) { this.id = id; this.x = x; this.y = y; this.z = z; }
        double distanceToSqr(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz; return dx * dx + dy * dy + dz * dz;
        }
        double distanceToSqr(Entity o) { return distanceToSqr(o.x, o.y, o.z); }
    }

    /** 盒过滤 fill（moonrise getEntities(into, predicate) 复刻）。 */
    static int fill(List<Entity> world, double cx, double cz, double radius, List<Entity> into) {
        int n = 0;
        for (Entity e : world) {
            if (Math.abs(e.x - cx) <= radius && Math.abs(e.z - cz) <= radius) { into.add(e); n++; }
        }
        return n;
    }

    static int fillItem(List<Entity> world, double cx, double cz, double radius, List<Entity> into) {
        return fill(world, cx, cz, radius, into); // 谓词内联进 fill（同 before 路径）
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    // ---- 0200 AvoidEntityGoal（getNearestEntity 最近目标）----
    final List<Entity> avoidWorld = new ArrayList<>();
    final List<Entity> avoidScratch = new ArrayList<>();
    {
        for (int i = 0; i < 60; i++) avoidWorld.add(new Entity(i, i * 0.4, 64, i * 0.4));
    }

    static Entity nearest(List<Entity> list, double x, double y, double z) {
        Entity best = null; double bd = Double.MAX_VALUE;
        for (Entity e : list) { double d = e.distanceToSqr(x, y, z); if (d < bd) { bd = d; best = e; } }
        return best;
    }

    public Entity avoidBeforeBody() {
        List<Entity> list = new ArrayList<>();
        fill(avoidWorld, 12.0, 12.0, 6.0, list);
        Entity r = nearest(list, 12.0, 64.0, 12.0);
        this.sink = list;
        return r;
    }

    public Entity avoidAfterBody() {
        avoidScratch.clear();
        fill(avoidWorld, 12.0, 12.0, 6.0, avoidScratch);
        Entity r = nearest(avoidScratch, 12.0, 64.0, 12.0);
        this.sink = avoidScratch;
        return r;
    }

    // ---- 0201 FollowParentGoal（线性最近）----
    final List<Entity> parentWorld = new ArrayList<>();
    final List<Entity> parentScratch = new ArrayList<>();
    {
        for (int i = 0; i < 40; i++) parentWorld.add(new Entity(100 + i, i * 0.5, 64, i * 0.5));
    }

    public Entity parentBeforeBody() {
        List<Entity> list = new ArrayList<>();
        fill(parentWorld, 10.0, 10.0, 8.0, list);
        Entity best = null; double bd = Double.MAX_VALUE;
        for (Entity e : list) { double d = e.distanceToSqr(10.0, 64.0, 10.0); if (d < bd) { bd = d; best = e; } }
        this.sink = list;
        return best;
    }

    public Entity parentAfterBody() {
        parentScratch.clear();
        fill(parentWorld, 10.0, 10.0, 8.0, parentScratch);
        Entity best = null; double bd = Double.MAX_VALUE;
        for (Entity e : parentScratch) { double d = e.distanceToSqr(10.0, 64.0, 10.0); if (d < bd) { bd = d; best = e; } }
        this.sink = parentScratch;
        return best;
    }

    // ---- 0202 NearestItemSensor（sort + 视线最近）----
    final List<Entity> itemWorld = new ArrayList<>();
    final List<Entity> itemScratch = new ArrayList<>();
    {
        for (int i = 0; i < 50; i++) itemWorld.add(new Entity(200 + i, i * 0.3, 64, i * 0.3));
    }

    public Entity itemBeforeBody() {
        List<Entity> list = new ArrayList<>();
        fillItem(itemWorld, 8.0, 8.0, 5.0, list);
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(8.0, 64.0, 8.0)));
        Entity r = null;
        for (Entity e : list) { r = e; break; } // hasLineOfSight 复刻：首个可见
        this.sink = list;
        return r;
    }

    public Entity itemAfterBody() {
        itemScratch.clear();
        fillItem(itemWorld, 8.0, 8.0, 5.0, itemScratch);
        itemScratch.sort(Comparator.comparingDouble(e -> e.distanceToSqr(8.0, 64.0, 8.0)));
        Entity r = null;
        for (Entity e : itemScratch) { r = e; break; }
        this.sink = itemScratch;
        return r;
    }

    @Benchmark public Entity avoidBefore(Blackhole bh) { Entity r = this.avoidBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Entity avoidAfter(Blackhole bh) { Entity r = this.avoidAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public Entity parentBefore(Blackhole bh) { Entity r = this.parentBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Entity parentAfter(Blackhole bh) { Entity r = this.parentAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public Entity itemBefore(Blackhole bh) { Entity r = this.itemBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Entity itemAfter(Blackhole bh) { Entity r = this.itemAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        EntityScanScratchBench b = new EntityScanScratchBench();
        if (b.avoidBeforeBody() != b.avoidAfterBody()) throw new AssertionError("avoid mismatch");
        if (b.parentBeforeBody() != b.parentAfterBody()) throw new AssertionError("parent mismatch");
        if (b.itemBeforeBody() != b.itemAfterBody()) throw new AssertionError("item mismatch");
        System.out.println("ALL OK");
    }
}
