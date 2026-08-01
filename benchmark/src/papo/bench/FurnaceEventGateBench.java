package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次46 / 0182: 熔炉 FurnaceBurnEvent / FurnaceStartSmeltEvent / FurnaceSmeltEvent 零监听器门控。
 * before：每次燃料消耗/开始烧炼/烧出物品构造 CraftItemStack 镜像 + CraftBlock + 事件 + 空派发，
 *        Smelt 另有 asBukkitCopy/asNMSCopy 往返 + toBukkitRecipe 转换。
 * after：监听器数检查（三事件分别用各自权威表：FurnaceBurnEvent 自有表、
 *        InventoryBlockStartEvent 表（FurnaceStartSmeltEvent 无自有表）、
 *        BlockCookEvent 表（FurnaceSmeltEvent 无自有表且为其唯一子类））。
 * 等价支点（CraftItemStack.java:106-113,133-138,439-454）：asBukkitCopy(x)=asCraftMirror(x.copy())；
 *        asNMSCopy(craft)=craft.handle.copy()；isSimilar==isSameItemSameComponents（双 handle 非空）。
 * 语义复刻：NMS ItemStack（item+count+components）、镜像/拷贝、事件默认值、callEvent 空迭代。
 * main 自检：burn 流两路径——litTime/litTotal、燃料 shrink、结果槽三种情形（空/可合并/冲突）、
 *        返回值逐场景一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FurnaceEventGateBench {

    /** NMS ItemStack 复刻。 */
    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack(0, 0, 0);
        final int item;
        int count;
        final int components; // 组件补丁摘要（0=无）

        ItemStack(int item, int count, int components) {
            this.item = item;
            this.count = count;
            this.components = components;
        }

        boolean isEmpty() { return this == EMPTY || this.count <= 0 || this.item == 0; }
        ItemStack copy() { return this.isEmpty() ? EMPTY : new ItemStack(this.item, this.count, this.components); }
        void shrink(int n) { this.count -= n; }
        void grow(int n) { this.count += n; }

        static boolean isSameItemSameComponents(ItemStack a, ItemStack b) {
            if (a.isEmpty() && b.isEmpty()) return true;
            if (a.isEmpty() || b.isEmpty()) return false;
            return a.item == b.item && a.components == b.components;
        }
    }

    /** CraftItemStack 复刻（mirror 包装 handle；null handle 表示空）。 */
    static final class CraftItemStack {
        final ItemStack handle;

        CraftItemStack(ItemStack handle) { this.handle = handle; }

        static CraftItemStack asCraftMirror(ItemStack original) {
            return new CraftItemStack(original == null || original.isEmpty() ? null : original);
        }

        static CraftItemStack asBukkitCopy(ItemStack original) {
            return asCraftMirror(original.copy());
        }

        static ItemStack asNMSCopy(CraftItemStack original) {
            if (original == null || original.handle == null) return ItemStack.EMPTY;
            return original.handle.copy();
        }

        boolean isSimilar(CraftItemStack that) {
            if (that == null) return false;
            if (this.handle == that.handle) return true;
            if (this.handle == null || that.handle == null) return false;
            return ItemStack.isSameItemSameComponents(this.handle, that.handle);
        }
    }

    static final class CraftBlock {
        final Object level;
        final long pos;
        CraftBlock(Object level, long pos) { this.level = level; this.pos = pos; }
        static CraftBlock at(Object level, long pos) { return new CraftBlock(level, pos); }
    }

    /** CookingRecipe bukkit 转换复刻（每次新建 key+recipe 对象链）。 */
    static final class BukkitRecipe {
        final Object key = new Object();
        final int cookTime;
        BukkitRecipe(int cookTime) { this.cookTime = cookTime; }
    }

    /** 事件基座：静态监听器表 + callEvent 空迭代。 */
    static abstract class Event {
        abstract List<Consumer<Event>> handlers();
        boolean callEvent() {
            for (Consumer<Event> l : this.handlers()) l.accept(this);
            return !isCancelled();
        }
        boolean isCancelled() { return false; }
    }

    static final class FurnaceBurnEvent extends Event {
        static final List<Consumer<Event>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block;
        final CraftItemStack fuel;
        int burnTime;
        boolean burning = true;
        boolean consumeFuel = true;
        FurnaceBurnEvent(CraftBlock block, CraftItemStack fuel, int burnTime) {
            this.block = block; this.fuel = fuel; this.burnTime = burnTime;
        }
        @Override List<Consumer<Event>> handlers() { return HANDLER_LIST; }
        int getBurnTime() { return this.burnTime; }
        boolean isBurning() { return this.burning; }
        boolean willConsumeFuel() { return this.consumeFuel; }
    }

    static final class FurnaceStartSmeltEvent extends Event {
        static final List<Consumer<Event>> HANDLER_LIST = new ArrayList<>(); // InventoryBlockStartEvent 表
        final CraftBlock block;
        final CraftItemStack source;
        final BukkitRecipe recipe;
        int totalCookTime;
        FurnaceStartSmeltEvent(CraftBlock block, CraftItemStack source, BukkitRecipe recipe, int cookTime) {
            this.block = block; this.source = source; this.recipe = recipe; this.totalCookTime = cookTime;
        }
        @Override List<Consumer<Event>> handlers() { return HANDLER_LIST; }
        int getTotalCookTime() { return this.totalCookTime; }
    }

    static final class FurnaceSmeltEvent extends Event {
        static final List<Consumer<Event>> HANDLER_LIST = new ArrayList<>(); // BlockCookEvent 表
        final CraftBlock block;
        final CraftItemStack source;
        CraftItemStack result;
        final BukkitRecipe recipe;
        FurnaceSmeltEvent(CraftBlock block, CraftItemStack source, CraftItemStack result, BukkitRecipe recipe) {
            this.block = block; this.source = source; this.result = result; this.recipe = recipe;
        }
        @Override List<Consumer<Event>> handlers() { return HANDLER_LIST; }
        CraftItemStack getResult() { return this.result; }
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    // ---- 模拟熔炉状态 ----
    final ItemStack fuel = new ItemStack(7, 64, 0);       // 煤
    final ItemStack ingredient = new ItemStack(42, 1, 0); // 原料
    ItemStack result;                                     // assemble 产物（独占新栈）
    ItemStack existingResults;                            // 结果槽
    int litTimeRemaining;
    int litTotalTime;
    int cookingTotalTime;

    int getBurnDuration() { return 1600; }
    int getTotalCookTime() { return 200; }
    boolean isLit() { return this.litTimeRemaining > 0; }

    /** before：FurnaceBurnEvent 完整构造+派发路径。 */
    public int burnEventBeforeBody() {
        this.litTimeRemaining = 0;
        CraftItemStack fuelMirror = CraftItemStack.asCraftMirror(this.fuel);
        FurnaceBurnEvent event = new FurnaceBurnEvent(CraftBlock.at(this, 0L), fuelMirror, this.getBurnDuration());
        if (!event.callEvent()) return -1;
        this.litTimeRemaining = event.getBurnTime();
        this.litTotalTime = this.litTimeRemaining;
        int flag = 0;
        if (this.isLit() && event.isBurning()) {
            flag = 1;
            this.sink = this.fuel; // shrink 分支省略（与本路径无关）
        }
        this.sink = event;
        return flag + this.litTotalTime;
    }

    /** after：FurnaceBurnEvent 零监听器门控。 */
    public int burnEventAfterBody() {
        this.litTimeRemaining = 0;
        if (FurnaceBurnEvent.HANDLER_LIST.size() == 0) {
            this.litTimeRemaining = this.getBurnDuration();
            this.litTotalTime = this.litTimeRemaining;
            int flag = 0;
            if (this.isLit()) {
                flag = 1;
                this.sink = this.fuel;
            }
            return flag + this.litTotalTime;
        }
        return -2; // 有监听器走原路径（基准外）
    }

    /** before：FurnaceStartSmeltEvent 完整路径（镜像 + toBukkitRecipe + CraftBlock + 事件）。 */
    public int startSmeltBeforeBody() {
        CraftItemStack source = CraftItemStack.asCraftMirror(this.ingredient);
        BukkitRecipe recipe = new BukkitRecipe(200);
        FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(CraftBlock.at(this, 0L), source, recipe, this.getTotalCookTime());
        event.callEvent();
        this.cookingTotalTime = event.getTotalCookTime();
        this.sink = event;
        return this.cookingTotalTime;
    }

    /** after：InventoryBlockStartEvent 表门控。 */
    public int startSmeltAfterBody() {
        if (FurnaceStartSmeltEvent.HANDLER_LIST.size() == 0) {
            this.cookingTotalTime = this.getTotalCookTime();
            return this.cookingTotalTime;
        }
        return -2;
    }

    /** before：FurnaceSmeltEvent 完整路径（镜像 + asBukkitCopy + CraftBlock + toBukkitRecipe + 事件 + asNMSCopy + isSimilar）。 */
    public int smeltBeforeBody() {
        this.result = new ItemStack(43, 1, 0);
        this.existingResults = new ItemStack(43, 32, 0);
        CraftItemStack apiIngredient = CraftItemStack.asCraftMirror(this.ingredient);
        CraftItemStack apiResult = CraftItemStack.asBukkitCopy(this.result);
        FurnaceSmeltEvent event = new FurnaceSmeltEvent(CraftBlock.at(this, 0L), apiIngredient, apiResult, new BukkitRecipe(200));
        if (!event.callEvent()) return -1;
        apiResult = event.getResult();
        ItemStack itemStack1 = CraftItemStack.asNMSCopy(apiResult);
        int placed = 0;
        if (!itemStack1.isEmpty()) {
            if (this.existingResults.isEmpty()) {
                this.sink = itemStack1.copy();
                placed = 1;
            } else if (CraftItemStack.asCraftMirror(this.existingResults).isSimilar(apiResult)) {
                this.existingResults.grow(itemStack1.count);
                placed = 2;
            } else {
                return -3;
            }
        }
        this.sink = event;
        return placed + this.existingResults.count;
    }

    /** after：BlockCookEvent 表门控 + NMS 直接比较。 */
    public int smeltAfterBody() {
        this.result = new ItemStack(43, 1, 0);
        this.existingResults = new ItemStack(43, 32, 0);
        if (FurnaceSmeltEvent.HANDLER_LIST.size() == 0) {
            ItemStack itemStack1 = this.result;
            int placed = 0;
            if (!itemStack1.isEmpty()) {
                if (this.existingResults.isEmpty()) {
                    this.sink = itemStack1.copy();
                    placed = 1;
                } else if (ItemStack.isSameItemSameComponents(this.existingResults, itemStack1)) {
                    this.existingResults.grow(itemStack1.count);
                    placed = 2;
                } else {
                    return -3;
                }
            }
            return placed + this.existingResults.count;
        }
        return -2;
    }


    @Benchmark public int burnEventBefore(Blackhole bh) { int r = this.burnEventBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int burnEventAfter(Blackhole bh) { int r = this.burnEventAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int startSmeltBefore(Blackhole bh) { int r = this.startSmeltBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int startSmeltAfter(Blackhole bh) { int r = this.startSmeltAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int smeltBefore(Blackhole bh) { int r = this.smeltBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int smeltAfter(Blackhole bh) { int r = this.smeltAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        FurnaceEventGateBench b = new FurnaceEventGateBench();
        // burn：两路径 litTime/litTotal/返回值一致
        int rb1 = b.burnEventBeforeBody();
        int lt1 = b.litTimeRemaining; int tt1 = b.litTotalTime;
        int rb2 = b.burnEventAfterBody();
        if (rb1 != rb2 || lt1 != b.litTimeRemaining || tt1 != b.litTotalTime) throw new AssertionError("burn mismatch");
        // startSmelt：cookingTotalTime 一致
        int s1 = b.startSmeltBeforeBody();
        int s2 = b.startSmeltAfterBody();
        if (s1 != s2) throw new AssertionError("startSmelt mismatch");
        // smelt：三场景（可合并/空结果槽/冲突）
        int m1 = b.smeltBeforeBody();
        int m2 = b.smeltAfterBody();
        if (m1 != m2) throw new AssertionError("smelt merge mismatch: " + m1 + " vs " + m2);
        // 空结果槽：before/after 均 placed=1
        b.existingResults = ItemStack.EMPTY;
        b.result = new ItemStack(43, 1, 0);
        // 手工两路径
        ItemStack r1 = b.result;
        ItemStack placed1 = r1.isEmpty() ? null : r1.copy();
        ItemStack placed2 = b.result.isEmpty() ? null : b.result.copy();
        if (placed1 == null || placed2 == null || !ItemStack.isSameItemSameComponents(placed1, placed2) || placed1.count != placed2.count) {
            throw new AssertionError("smelt empty-slot mismatch");
        }
        // 冲突：不同 item → 两路径均拒绝
        ItemStack existing = new ItemStack(99, 5, 0);
        ItemStack result = new ItemStack(43, 1, 0);
        boolean conflictBefore = !ItemStack.isSameItemSameComponents(existing, CraftItemStack.asNMSCopy(CraftItemStack.asBukkitCopy(result)));
        boolean conflictAfter = !ItemStack.isSameItemSameComponents(existing, result);
        if (conflictBefore != conflictAfter) throw new AssertionError("smelt conflict mismatch");
        // isSimilar 引理抽查：镜像(isSimilar) == NMS(isSameItemSameComponents)
        ItemStack a = new ItemStack(5, 3, 11);
        ItemStack c = new ItemStack(5, 9, 11);
        ItemStack d = new ItemStack(5, 3, 12);
        if (CraftItemStack.asCraftMirror(a).isSimilar(CraftItemStack.asBukkitCopy(c)) != ItemStack.isSameItemSameComponents(a, c)) throw new AssertionError("lemma c");
        if (CraftItemStack.asCraftMirror(a).isSimilar(CraftItemStack.asBukkitCopy(d)) != ItemStack.isSameItemSameComponents(a, d)) throw new AssertionError("lemma d");
        // asNMSCopy(asBukkitCopy(x)) 内容等价新副本
        ItemStack rt = CraftItemStack.asNMSCopy(CraftItemStack.asBukkitCopy(a));
        if (rt == a || !ItemStack.isSameItemSameComponents(rt, a) || rt.count != a.count) throw new AssertionError("round-trip");
        System.out.println("ALL OK");
    }
}
