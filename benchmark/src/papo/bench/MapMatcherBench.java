package papo.bench;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次38 / 0158: MapItemSavedData.tickCarriedBy 地图匹配谓词内联。
 * before（原版）：mapMatcher(mapStack) 每次调用分配捕获 lambda（捕获 stack + mapId），
 * 经 Inventory.contains(Predicate) 迭代测试；after：静态助手内联同一逐项测试直接扫描。
 * 语义复刻：物品栈（引用相等 / is(item) / MAP_ID 组件 equals 短路序逐字），背包为
 * 41 槽数组迭代（与 Inventory 迭代器同序）。main 自检：地图在首/中/末槽、缺席、
 * MAP_ID 为 null、同物品不同 id 六场景两路径判定一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MapMatcherBench {

    /** ItemStack 语义复刻。 */
    static final class ItemStack {
        final String item;
        final Integer mapId; // DataComponents.MAP_ID，null 表示无组件

        ItemStack(String item, Integer mapId) {
            this.item = item;
            this.mapId = mapId;
        }

        boolean is(String other) {
            return this.item.equals(other);
        }

        Integer getMapId() {
            return this.mapId;
        }
    }

    /** before：mapMatcher lambda 工厂复刻。 */
    static Predicate<ItemStack> mapMatcher(ItemStack stack) {
        Integer mapId = stack.getMapId();
        return itemStack -> itemStack == stack || itemStack.is(stack.item) && Objects.equals(mapId, itemStack.getMapId());
    }

    /** before：Inventory.contains(Predicate) 复刻。 */
    static boolean contains(ItemStack[] inventory, Predicate<ItemStack> predicate) {
        for (ItemStack itemStack : inventory) {
            if (predicate.test(itemStack)) {
                return true;
            }
        }
        return false;
    }

    /** after：0158 内联助手复刻。 */
    static boolean papoInventoryHasMap(ItemStack[] inventory, ItemStack stack) {
        Integer mapId = stack.getMapId();
        for (ItemStack itemStack : inventory) {
            if (itemStack == stack || itemStack.is(stack.item) && Objects.equals(mapId, itemStack.getMapId())) {
                return true;
            }
        }
        return false;
    }

    private ItemStack[] inventory;
    private ItemStack mapStack;

    @Setup
    public void setup() {
        this.mapStack = new ItemStack("minecraft:filled_map", 42);
        this.inventory = new ItemStack[41];
        for (int i = 0; i < 41; i++) {
            this.inventory[i] = new ItemStack("minecraft:stone", null);
        }
        this.inventory[36] = this.mapStack; // 地图在靠后槽位，扫描约 37 项
    }

    @Benchmark
    public boolean before_lambdaPerCall(Blackhole bh) {
        Predicate<ItemStack> predicate = mapMatcher(this.mapStack);
        boolean result = contains(this.inventory, predicate);
        bh.consume(predicate);
        return result;
    }

    @Benchmark
    public boolean after_inlined(Blackhole bh) {
        boolean result = papoInventoryHasMap(this.inventory, this.mapStack);
        bh.consume(result);
        return result;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        ItemStack map = new ItemStack("minecraft:filled_map", 42);
        ItemStack otherId = new ItemStack("minecraft:filled_map", 43);
        ItemStack nullId = new ItemStack("minecraft:filled_map", null);
        ItemStack stone = new ItemStack("minecraft:stone", null);
        ItemStack[][] cases = {
            {map, stone, stone},
            {stone, map, stone},
            {stone, stone, map},
            {stone, stone, stone},
            {otherId, stone, stone},
            {nullId, stone, stone},
        };
        ItemStack[] stacks = {map, otherId, nullId};
        for (int c = 0; c < cases.length; c++) {
            for (ItemStack target : stacks) {
                boolean a = contains(cases[c], mapMatcher(target));
                boolean b = papoInventoryHasMap(cases[c], target);
                if (a != b) {
                    System.out.println("MISMATCH case " + c + " target=" + target.mapId + ": " + a + " vs " + b);
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
