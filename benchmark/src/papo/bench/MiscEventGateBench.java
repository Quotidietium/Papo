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
 * 批次46 / 0184 + 0185 + 0189: 三个同构事件门控（宝库展示 / 挥臂 / 药效 tick）。
 * 0184 VaultDisplayItemEvent：每 ACTIVE 宝库每 20 tick CraftBlock + asBukkitCopy + 事件 + asNMSCopy。
 * 0185 PlayerArmSwingEvent：每挥臂包 事件构造 + callEvent（PlayerAnimationEvent 表，无自有表、唯一子类）。
 * 0189 EntityEffectTickEvent：每药效应用 tick 事件 + minecraftHolderToBukkit 转换（自有表、无子类）。
 * after：各自权威表零监听器门控；零监听器时 callEvent 恒 true、默认值与构造参数一致。
 * main 自检：三事件零监听器场景两路径可观察结果（展示栈内容/取消标志/返回值）一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MiscEventGateBench {

    // ---- 0184 宝库 ----
    static final class ItemStack {
        final int item;
        final int count;
        ItemStack(int item, int count) { this.item = item; this.count = count; }
        ItemStack copy() { return new ItemStack(this.item, this.count); }
    }

    static final class CraftItemStack {
        final ItemStack handle;
        CraftItemStack(ItemStack handle) { this.handle = handle; }
        static CraftItemStack asBukkitCopy(ItemStack original) { return new CraftItemStack(original.copy()); }
        static ItemStack asNMSCopy(CraftItemStack original) { return original.handle.copy(); }
    }

    static final class CraftBlock {
        CraftBlock(Object level, long pos) {}
        static CraftBlock at(Object level, long pos) { return new CraftBlock(level, pos); }
    }

    static final class VaultDisplayItemEvent {
        static final List<Consumer<VaultDisplayItemEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block;
        final CraftItemStack displayItem;
        boolean cancelled;
        VaultDisplayItemEvent(CraftBlock block, CraftItemStack displayItem) {
            this.block = block; this.displayItem = displayItem;
        }
        boolean callEvent() {
            for (Consumer<VaultDisplayItemEvent> l : HANDLER_LIST) l.accept(this);
            return true;
        }
        boolean isCancelled() { return this.cancelled; }
        CraftItemStack getDisplayItem() { return this.displayItem; }
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    ItemStack rolled = new ItemStack(77, 1);

    public ItemStack vaultBeforeBody() {
        VaultDisplayItemEvent event = new VaultDisplayItemEvent(CraftBlock.at(this, 0L), CraftItemStack.asBukkitCopy(this.rolled));
        event.callEvent();
        ItemStack out = null;
        if (!event.isCancelled()) {
            out = CraftItemStack.asNMSCopy(event.getDisplayItem());
        }
        this.sink = event;
        return out;
    }

    public ItemStack vaultAfterBody() {
        if (VaultDisplayItemEvent.HANDLER_LIST.size() > 0) {
            return null; // 有监听器走原路径（基准外）
        }
        return this.rolled; // 独占新栈直传
    }

    // ---- 0185 挥臂 ----
    static final class PlayerArmSwingEvent {
        static final List<Consumer<PlayerArmSwingEvent>> HANDLER_LIST = new ArrayList<>(); // PlayerAnimationEvent 表
        final Object player;
        final Object hand;
        boolean cancelled;
        PlayerArmSwingEvent(Object player, Object hand) { this.player = player; this.hand = hand; }
        void callEvent() {
            for (Consumer<PlayerArmSwingEvent> l : HANDLER_LIST) l.accept(this);
        }
        boolean isCancelled() { return this.cancelled; }
    }

    enum Hand { MAIN_HAND, OFF_HAND }
    static final Object[] EQUIP_SLOT = {new Object(), new Object()};

    static Object getHand(Hand hand) { return EQUIP_SLOT[hand.ordinal()]; }

    Hand packetHand = Hand.MAIN_HAND;

    public int swingBeforeBody() {
        PlayerArmSwingEvent event = new PlayerArmSwingEvent(this, getHand(this.packetHand));
        event.callEvent();
        if (event.isCancelled()) return -1;
        this.sink = event;
        return this.packetHand.ordinal(); // swing(packet.getHand())
    }

    public int swingAfterBody() {
        if (PlayerArmSwingEvent.HANDLER_LIST.size() > 0) {
            return -2; // 原路径（基准外）
        }
        return this.packetHand.ordinal();
    }

    // ---- 0189 药效 tick ----
    static final class EntityEffectTickEvent {
        static final List<Consumer<EntityEffectTickEvent>> HANDLER_LIST = new ArrayList<>();
        final Object entity;
        final Object type;
        final int amplifier;
        boolean cancelled;
        EntityEffectTickEvent(Object entity, Object type, int amplifier) {
            this.entity = entity; this.type = type; this.amplifier = amplifier;
        }
        boolean callEvent() {
            for (Consumer<EntityEffectTickEvent> l : HANDLER_LIST) l.accept(this);
            return !this.cancelled;
        }
    }

    /** CraftPotionEffectType.minecraftHolderToBukkit 复刻（注册表双向查找 + 包装分配）。 */
    static Object minecraftHolderToBukkit(Object holder) {
        return new Object[] {holder};
    }

    Object effectHolder = new Object();

    /** applyEffectTick 复刻：返回 true（效果继续）。 */
    boolean applyEffectTick() {
        return true;
    }

    public boolean effectTickBeforeBody() {
        boolean cont = new EntityEffectTickEvent(this, minecraftHolderToBukkit(this.effectHolder), 1).callEvent() && this.applyEffectTick();
        return cont;
    }

    public boolean effectTickAfterBody() {
        boolean cont = (EntityEffectTickEvent.HANDLER_LIST.size() == 0
            || new EntityEffectTickEvent(this, minecraftHolderToBukkit(this.effectHolder), 1).callEvent())
            && this.applyEffectTick();
        return cont;
    }


    @Benchmark public ItemStack vaultBefore(Blackhole bh) { ItemStack r = this.vaultBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public ItemStack vaultAfter(Blackhole bh) { ItemStack r = this.vaultAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int swingBefore(Blackhole bh) { int r = this.swingBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int swingAfter(Blackhole bh) { int r = this.swingAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public boolean effectTickBefore(Blackhole bh) { boolean r = this.effectTickBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public boolean effectTickAfter(Blackhole bh) { boolean r = this.effectTickAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        MiscEventGateBench b = new MiscEventGateBench();
        // 0184：零监听器两路径产出的展示栈内容一致（round-trip 新副本 vs 独占原栈）
        ItemStack v1 = b.vaultBeforeBody();
        ItemStack v2 = b.vaultAfterBody();
        if (v1 == null || v2 == null || v1.item != v2.item || v1.count != v2.count) throw new AssertionError("vault mismatch");
        // 0185：零监听器两路径均到达 swing（返回 hand ordinal）
        if (b.swingBeforeBody() != b.swingAfterBody()) throw new AssertionError("swing mismatch");
        // 0189：零监听器两路径均到达 applyEffectTick
        if (b.effectTickBeforeBody() != b.effectTickAfterBody()) throw new AssertionError("effectTick mismatch");
        System.out.println("ALL OK");
    }
}
