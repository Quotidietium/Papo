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
 * 批次48 / 0193 + 0194: 交互类事件零监听器门控（与批次36 InteractEventGateBench 不同站点）。
 * 0193 SWAP_ITEM_WITH_OFFHAND：每次 F 键交换 2×asCraftMirror + 2×clone + 事件 + callEvent；
 *      after 门控直达两次 setItemInHand（等价支点：clone 与 mirror 的 equals 恒 true）。
 * 0194 handleInteract：onInteraction 每次构造 PlayerInteractEntityEvent（+getBukkitEntity+CraftEquipmentSlot）；
 *      after 门控跳过事件块直达 entityInteraction.run。
 * main 自检：两补丁零监听器场景两路径可观察结果（交换后槽位内容/交互返回值与触发序列）一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SwapInteractGateBench {

    // ---- 0193 交换 ----
    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack(0, 0);
        final int item;
        final int count;
        ItemStack(int item, int count) { this.item = item; this.count = count; }
        boolean isEmpty() { return this.item == 0; }
        ItemStack copy() { return new ItemStack(this.item, this.count); }
        boolean matches(ItemStack o) { return this.item == o.item; } // ItemStack.matches 复刻（物品+组件）
    }

    static final class CraftItemStack {
        final ItemStack handle;
        CraftItemStack(ItemStack handle) { this.handle = handle; }
        static CraftItemStack asCraftMirror(ItemStack original) {
            return new CraftItemStack(original.isEmpty() ? null : original);
        }
        CraftItemStack cloneStack() { return new CraftItemStack(this.handle == null ? null : this.handle.copy()); }
        @Override
        public boolean equals(Object obj) { // CraftItemStack.equals 复刻（null==null / 双空 / matches）
            if (!(obj instanceof CraftItemStack other)) return false;
            if (this.handle == other.handle) return true;
            if (this.handle == null || other.handle == null) return false;
            if (this.handle.isEmpty() && other.handle.isEmpty()) return true;
            return this.handle.matches(other.handle);
        }
    }

    static final class PlayerSwapHandItemsEvent {
        static final List<Consumer<PlayerSwapHandItemsEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftItemStack mainHandItem;
        final CraftItemStack offHandItem;
        boolean cancelled;
        PlayerSwapHandItemsEvent(CraftItemStack mainHandItem, CraftItemStack offHandItem) {
            this.mainHandItem = mainHandItem; this.offHandItem = offHandItem;
        }
        void callEvent() { for (Consumer<PlayerSwapHandItemsEvent> l : HANDLER_LIST) l.accept(this); }
        boolean isCancelled() { return this.cancelled; }
        CraftItemStack getMainHandItem() { return this.mainHandItem; }
        CraftItemStack getOffHandItem() { return this.offHandItem; }
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    ItemStack mainSlot = new ItemStack(7, 1);
    ItemStack offSlot = new ItemStack(9, 3);

    void setMain(ItemStack s) { this.mainSlot = s; }
    void setOff(ItemStack s) { this.offSlot = s; }

    /** before：完整事件路径（复刻 CraftBukkit 块默认流）。 */
    public int swapBeforeBody() {
        ItemStack itemInHand1 = this.offSlot;
        CraftItemStack mainHand = CraftItemStack.asCraftMirror(itemInHand1);
        CraftItemStack offHand = CraftItemStack.asCraftMirror(this.mainSlot);
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(mainHand.cloneStack(), offHand.cloneStack());
        event.callEvent();
        if (event.isCancelled()) return -1;
        if (event.getOffHandItem().equals(offHand)) {
            this.setOff(this.mainSlot);
        } else {
            this.setOff(event.getOffHandItem().handle.copy());
        }
        if (event.getMainHandItem().equals(mainHand)) {
            this.setMain(itemInHand1);
        } else {
            this.setMain(event.getMainHandItem().handle.copy());
        }
        this.sink = event;
        return 1;
    }

    /** after：门控直达交换。 */
    public int swapAfterBody() {
        if (PlayerSwapHandItemsEvent.HANDLER_LIST.size() == 0) {
            ItemStack offHandStack = this.offSlot;
            this.setOff(this.mainSlot);
            this.setMain(offHandStack);
            return 1;
        }
        return -2;
    }

    // ---- 0194 实体交互 ----
    static final class PlayerInteractEntityEvent {
        static final List<Consumer<PlayerInteractEntityEvent>> HANDLER_LIST = new ArrayList<>();
        final Object player;
        final Object entity;
        final Object hand;
        boolean cancelled;
        PlayerInteractEntityEvent(Object player, Object entity, Object hand) {
            this.player = player; this.entity = entity; this.hand = hand;
        }
        void callEvent() { for (Consumer<PlayerInteractEntityEvent> l : HANDLER_LIST) l.accept(this); }
        boolean isCancelled() { return this.cancelled; }
    }

    Object craftEntity; // getBukkitEntity() 复刻：惰性包装
    Object getBukkitEntity() {
        Object e = this.craftEntity;
        if (e == null) e = this.craftEntity = new Object();
        return e;
    }

    static final Object[] EQUIP_SLOT = {new Object(), new Object()};
    static Object getHandSlot(int ordinal) { return EQUIP_SLOT[ordinal]; }

    int interactRan;

    /** entityInteraction.run 复刻：返回 SUCCESS。 */
    int runInteraction() {
        this.interactRan++;
        return 1;
    }

    /** before：构造事件 + callEvent + 取消检查。 */
    public int interactBeforeBody() {
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(this, this.getBukkitEntity(), getHandSlot(0));
        event.callEvent();
        if (event.isCancelled()) return -1;
        this.sink = event;
        return this.runInteraction();
    }

    /** after：门控直达 run。 */
    public int interactAfterBody() {
        if (PlayerInteractEntityEvent.HANDLER_LIST.size() == 0) {
            return this.runInteraction();
        }
        return -2;
    }

    @Benchmark public int swapBefore(Blackhole bh) { int r = this.swapBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int swapAfter(Blackhole bh) { int r = this.swapAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int interactBefore(Blackhole bh) { int r = this.interactBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int interactAfter(Blackhole bh) { int r = this.interactAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        SwapInteractGateBench b = new SwapInteractGateBench();
        // 0193：两路径交换后槽位内容一致（主=原副、副=原主，按引用交换）
        ItemStack m0 = b.mainSlot, o0 = b.offSlot;
        if (b.swapBeforeBody() != 1) throw new AssertionError("swap before cancelled");
        if (b.mainSlot != o0 || b.offSlot != m0) throw new AssertionError("swap before slots");
        ItemStack m1 = b.mainSlot, o1 = b.offSlot; // 再换一次回到原状
        if (b.swapAfterBody() != 1) throw new AssertionError("swap after gated");
        if (b.mainSlot != o1 || b.offSlot != m1) throw new AssertionError("swap after slots");
        if (b.mainSlot != m0 || b.offSlot != o0) throw new AssertionError("swap round-trip");
        // 0193：门控默认路径等价支点——clone 与 mirror equals 恒 true（含 EMPTY/null handle）
        CraftItemStack mirror = CraftItemStack.asCraftMirror(new ItemStack(5, 2));
        if (!mirror.cloneStack().equals(mirror)) throw new AssertionError("clone!=mirror");
        CraftItemStack emptyMirror = CraftItemStack.asCraftMirror(ItemStack.EMPTY);
        if (!emptyMirror.cloneStack().equals(emptyMirror)) throw new AssertionError("empty clone!=mirror");
        // 0194：两路径均到达 run 且触发计数一致
        int before = b.interactRan;
        if (b.interactBeforeBody() != 1 || b.interactRan != before + 1) throw new AssertionError("interact before");
        if (b.interactAfterBody() != 1 || b.interactRan != before + 2) throw new AssertionError("interact after");
        System.out.println("ALL OK");
    }
}
