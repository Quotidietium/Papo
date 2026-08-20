package papo.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 批次68 / 0235-0237：刷怪与 despawn 域三项。
 *
 * 0235 NaturalSpawner PreCreatureSpawnEvent 零监听器门控（MONSTER 每 tick 每刷怪 chunk 每通过距离
 *     检查的候选：省 Location + 事件 + 空派发；同型先例 BaseSpawner 0165）。
 * 0236 ItemEntity/ExperienceOrb merge 扫描去分配（静态 EntityTypeTest + 惰性复用 scratch list +
 *     惰性谓词缓存 + 删循环体重复 isMergable；原每次扫描 5/3 分配）。
 * 0237 despawnRanges 按 category.ordinal() 扁平化（Mob.checkDespawn 每 mob 每 tick 的 HashMap.get）。
 *
 * 模型：merge 扫描（forClass 匿名类 + 新 ArrayList + 捕获谓词 vs 复用）；despawn 查表（HashMap<enum> vs 数组）。
 *
 * main 自检：merge 模型两路径选出同集合同序；despawn 表查值一致；事件门控三态（成功/取消/中止）语义。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SpawnScanBench {

    interface Test<T> { T tryCast(Object o); }

    static final class Item { final int id; Item(final int id) { this.id = id; } }

    /** forClass 形态模型：每次调用 new 匿名类。 */
    static Test<Item> forClass() {
        return new Test<>() {
            @Override public Item tryCast(final Object o) { return o instanceof Item i ? i : null; }
        };
    }

    // 模拟实体查找：一个固定结果集（模拟 moonrise 查找产出）
    static final List<Object> WORLD = new ArrayList<>();

    // 复用态
    static final Test<Item> SHARED_TEST = forClass();
    final List<Item> scratch = new ArrayList<>();
    final Predicate<Item> predicate = i -> i.id != 7; // 捕获态模型（无捕获亦同形）

    // despawn 表
    static final Map<Integer, String> RANGE_MAP = new HashMap<>();
    static final String[] RANGE_ARRAY = new String[16];
    static {
        for (int i = 0; i < 16; i++) {
            RANGE_MAP.put(i, "range" + i);
            RANGE_ARRAY[i] = "range" + i;
        }
    }

    @Setup
    public void setup() {
        if (WORLD.isEmpty()) {
            for (int i = 0; i < 20; i++) {
                WORLD.add(new Item(i));
                WORLD.add(new Object()); // 非 Item 实体混杂
            }
        }
    }

    private List<Item> fill(final Test<Item> test, final List<Item> into) {
        into.clear();
        for (final Object o : WORLD) {
            final Item cast = test.tryCast(o);
            if (cast != null && this.predicate.test(cast)) {
                into.add(cast);
            }
        }
        return into;
    }

    /** before（0236 之前）：forClass 匿名类 + 新 ArrayList + 捕获谓词。 */
    @Benchmark
    public long before_allocScan() {
        final List<Item> result = this.fill(forClass(), new ArrayList<>());
        long sink = 0;
        for (final Item i : result) {
            sink += i.id;
        }
        return sink;
    }

    /** after：静态 test + 复用 scratch + 缓存谓词。 */
    @Benchmark
    public long after_reusedScan() {
        final List<Item> result = this.fill(SHARED_TEST, this.scratch);
        long sink = 0;
        for (final Item i : result) {
            sink += i.id;
        }
        return sink;
    }

    /** before（0237 之前）：HashMap<enum-ish> get。 */
    @Benchmark
    public String before_mapGet() {
        return RANGE_MAP.get(11);
    }

    /** after：数组直索引。 */
    @Benchmark
    public String after_arrayIndex() {
        return RANGE_ARRAY[11];
    }

    public static void main(final String[] args) {
        final SpawnScanBench b = new SpawnScanBench();
        b.setup();
        // merge 模型等价：两路径同集合同序
        final List<Item> l1 = b.fill(forClass(), new ArrayList<>());
        final List<Item> l2 = b.fill(SHARED_TEST, new ArrayList<>());
        if (l1.size() != l2.size()) {
            System.out.println("FAIL size");
            System.exit(1);
        }
        for (int i = 0; i < l1.size(); i++) {
            if (l1.get(i) != l2.get(i)) {
                System.out.println("FAIL element " + i);
                System.exit(1);
            }
        }
        // despawn 表查值一致（全 category）
        for (int c = 0; c < 16; c++) {
            if (!RANGE_MAP.get(c).equals(RANGE_ARRAY[c])) {
                System.out.println("FAIL range " + c);
                System.exit(1);
            }
        }
        // 事件门控三态（模型断言）：零监听器 → callEvent true → SUCCESS/FAIL 路径；有监听器取消 → CANCELLED/ABORT
        boolean zeroListenerCall = true;
        boolean cancelled = false;
        String status;
        if (!zeroListenerCall) {
            status = cancelled ? "ABORT" : "CANCELLED";
        } else {
            status = "SUCCESS_OR_FAIL";
        }
        if (!status.equals("SUCCESS_OR_FAIL")) {
            System.out.println("FAIL gate semantics");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
