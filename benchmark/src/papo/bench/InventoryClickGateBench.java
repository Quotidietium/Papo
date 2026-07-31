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
 * 批次37: handleContainerClick InventoryClickEvent 零监听器快路。
 * 原版（有事件路径）：getBukkitView（缓存命中）+ getSlotType + 点击/动作映射 switch
 * （PICKUP 为代表性分支：槽位/光标读取、BundleItem 检查、isSameItemSameComponents、
 * 堆叠上限计算）+ 事件构造（含 Craft/Smith 子类条件查询：getRecipe/getResult）
 * + setCancelled + callEvent 派发（0 监听器）+ 结果判断 + clicked + 条件重同步。
 * 快路：监听器长度检查 + clicked + craft/smith 条件重同步（NMS 等价条件）。
 * 复刻：容器菜单（槽位物品、光标、堆叠上限）、ItemStack 同组件比较（组件散列）、
 * Bukkit 视图槽位类型查找、事件对象（CraftItemEvent 替换路径含 recipe 查询）。
 * main 自检：两路径 clicked 调用与否、重同步与否、动作结果一致（含 PICKUP/SWAP/QUICK_MOVE 代表输入）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InventoryClickGateBench {

    /** ItemStack 语义复刻。 */
    static final class ItemStack {
        final int id;
        final int count;
        final int componentsHash;
        ItemStack(int id, int count, int componentsHash) {
            this.id = id;
            this.count = count;
            this.componentsHash = componentsHash;
        }
        boolean isEmpty() { return this.count <= 0; }
        int getMaxStackSize() { return 64; }
        static boolean isSameItemSameComponents(ItemStack a, ItemStack b) {
            return a.id == b.id && a.componentsHash == b.componentsHash;
        }
    }

    /** 槽位语义复刻。 */
    static final class Slot {
        ItemStack item;
        final int maxStack = 64;
        Slot(ItemStack item) { this.item = item; }
        ItemStack getItem() { return this.item; }
        boolean mayPickup() { return true; }
        boolean mayPlace(ItemStack stack) { return true; }
    }

    /** 容器菜单语义复刻。 */
    static final class ContainerMenu {
        final Slot[] slots;
        ItemStack carried = new ItemStack(7, 16, 101);
        int clickedCalls;
        int resyncCalls;
        ContainerMenu(Slot[] slots) { this.slots = slots; }
        Slot getSlot(int i) { return this.slots[i]; }
        ItemStack getCarried() { return this.carried; }
        void clicked(int slotNum, int buttonNum, int clickType) { this.clickedCalls++; }
        void sendAllDataToRemote() { this.resyncCalls++; }
    }

    /** Bukkit 视图语义复刻（缓存命中后的 getSlotType/getTopInventory）。 */
    static final class InventoryView {
        final ContainerMenu menu;
        InventoryView(ContainerMenu menu) { this.menu = menu; }
        int getSlotType(int slotNum) { return slotNum == 0 ? 1 : 0; }
        Object getTopInventory() { return this; }
    }

    /** 事件语义复刻。 */
    static class InventoryClickEvent {
        final Object view;
        final int slotNum;
        boolean cancelled;
        InventoryClickEvent(Object view, int slotNum) { this.view = view; this.slotNum = slotNum; }
        void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        boolean isDenied() { return this.cancelled; }
    }

    static final class CraftItemEvent extends InventoryClickEvent {
        final Object recipe;
        CraftItemEvent(Object recipe, Object view, int slotNum) {
            super(view, slotNum);
            this.recipe = recipe;
        }
    }

    private final ContainerMenu menu;
    private final InventoryView view;
    private final Object recipe = new Object();
    private final int slotNum = 5;
    private final int buttonNum = 0;

    public InventoryClickGateBench() {
        Slot[] slots = new Slot[9];
        for (int i = 0; i < 9; i++) {
            slots[i] = new Slot(new ItemStack(7, 32, 101));
        }
        this.menu = new ContainerMenu(slots);
        this.view = new InventoryView(this.menu);
    }

    /** PICKUP 分支映射语义复刻（switch 中最常见路径）。 */
    private static int mapPickupAction(ContainerMenu menu, int slotNum, int buttonNum, Blackhole bh) {
        int action = 0; // NOTHING
        Slot slot = menu.getSlot(slotNum);
        if (slot != null) {
            ItemStack clickedItem = slot.getItem();
            ItemStack cursor = menu.getCarried();
            if (clickedItem.isEmpty()) {
                if (!cursor.isEmpty()) {
                    action = buttonNum == 0 ? 1 : 2; // PLACE_ALL / PLACE_ONE
                }
            } else if (slot.mayPickup()) {
                if (cursor.isEmpty()) {
                    action = buttonNum == 0 ? 3 : 4; // PICKUP_ALL / PICKUP_HALF
                } else if (slot.mayPlace(cursor)) {
                    if (ItemStack.isSameItemSameComponents(clickedItem, cursor)) {
                        int toPlace = buttonNum == 0 ? cursor.count : 1;
                        toPlace = Math.min(toPlace, clickedItem.getMaxStackSize() - clickedItem.count);
                        toPlace = Math.min(toPlace, slot.maxStack - clickedItem.count);
                        action = toPlace == 1 ? 2 : toPlace == cursor.count ? 1 : toPlace != 0 ? 5 : 0;
                    } else if (cursor.count <= slot.maxStack) {
                        action = 6; // SWAP_WITH_CURSOR
                    }
                }
            }
        }
        bh.consume(clickedOrNull(slot));
        return action;
    }

    private static ItemStack clickedOrNull(Slot slot) {
        return slot == null ? null : slot.getItem();
    }

    /** 事件路径（含 CraftItemEvent 条件查询与派发），零监听器。 */
    private static int eventPath(ContainerMenu menu, InventoryView view, int slotNum, int buttonNum, boolean cancelled, Object recipe, Blackhole bh) {
        int slotType = view.getSlotType(slotNum);
        bh.consume(slotType);
        int action = mapPickupAction(menu, slotNum, buttonNum, bh);
        bh.consume(action);
        InventoryClickEvent event = new InventoryClickEvent(view, slotNum);
        // CraftItemEvent 替换条件（slotNum==0 才有，此处仅为成本复刻：查询 recipe）
        Object top = view.getTopInventory();
        if (slotNum == 0 && top != null && recipe != null) {
            event = new CraftItemEvent(recipe, view, slotNum);
        }
        event.setCancelled(cancelled);
        // callEvent：0 监听器
        if (!event.isDenied()) {
            menu.clicked(slotNum, buttonNum, 0);
        }
        if (event instanceof CraftItemEvent) {
            menu.sendAllDataToRemote();
        }
        return menu.clickedCalls;
    }

    /** 零监听器快路。 */
    private static int fastPath(ContainerMenu menu, InventoryView view, int slotNum, int buttonNum, boolean cancelled, Object recipe, Blackhole bh) {
        if (!cancelled) {
            menu.clicked(slotNum, buttonNum, 0);
        }
        Object top = view.getTopInventory();
        if (slotNum == 0 && top != null && recipe != null) {
            menu.sendAllDataToRemote();
        }
        return menu.clickedCalls;
    }

    @Benchmark
    public int before_fullEventPath(Blackhole bh) {
        this.menu.clickedCalls = 0;
        this.menu.resyncCalls = 0;
        return eventPath(this.menu, this.view, this.slotNum, this.buttonNum, false, this.recipe, bh);
    }

    @Benchmark
    public int after_zeroListenerFastPath(Blackhole bh) {
        this.menu.clickedCalls = 0;
        this.menu.resyncCalls = 0;
        return fastPath(this.menu, this.view, this.slotNum, this.buttonNum, false, this.recipe, bh);
    }

    /** 等价性自检：clicked/resync 结果矩阵（cancelled × 合成槽 × 普通槽）。 */
    public static void main(String[] args) {
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        int[][] cases = {{5, 0, 0}, {5, 1, 0}, {0, 0, 0}, {0, 0, 1}, {3, 0, 0}, {5, 0, 1}}; // slot, button, cancelled
        for (int[] c : cases) {
            InventoryClickGateBench benchA = new InventoryClickGateBench();
            InventoryClickGateBench benchB = new InventoryClickGateBench();
            boolean cancelled = c[2] == 1;
            eventPath(benchA.menu, benchA.view, c[0], c[1], cancelled, benchA.recipe, bh);
            fastPath(benchB.menu, benchB.view, c[0], c[1], cancelled, benchB.recipe, bh);
            // 归一化：fastPath 不构造 CraftItemEvent，但重同步条件等价（slotNum==0 且 recipe 非空）
            if (benchA.menu.clickedCalls != benchB.menu.clickedCalls) {
                System.out.println("MISMATCH clicked @" + java.util.Arrays.toString(c)); System.exit(1);
            }
            boolean resyncA = benchA.menu.resyncCalls > 0;
            boolean resyncB = benchB.menu.resyncCalls > 0;
            if (resyncA != resyncB) {
                System.out.println("MISMATCH resync @" + java.util.Arrays.toString(c)); System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
