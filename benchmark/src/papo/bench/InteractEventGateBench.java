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
 * 批次36: 四类交互/合成事件的零监听器门控。语义复刻（无 Bukkit 依赖）：
 * (a) PlayerInteractEvent 门控：构造事件对象（字段按构造器语义：useItemInHand=DEFAULT、
 *     useClickedBlock=pos==null?DENY:ALLOW）+ HandlerList 遍历（0 监听器）→ 仅查
 *     getRegisteredListeners().length 即跳过。
 * (b) 触发器门控（ANY_BLOCK_USE/ITEM_USED_ON_BLOCK/ITEM_USED_ON_AIR）：stack.copy()
 *     + 触发器列表遍历 → papoHasListeners 检查（stack.copy 也一并省掉）。
 * (c) ItemCraftedEvent 门控：asBukkitCopy 包装分配 + 调用 → 门控跳过。
 * (d) CraftEventFactory.callPreCraftEvent 快路：构造 PreCraft 事件 + 调用 →
 *     零监听器时直接 return result.copy()。
 * main 自检：各事件副本字段与门控前后可观察结果一致（零监听器时事件无效果）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InteractEventGateBench {

    enum Result { ALLOW, DENY, DEFAULT }

    /** PlayerInteractEvent 语义复刻：构造器字段默认值。 */
    static final class PlayerInteractEvent {
        final Object player;
        final Object pos; // nullable
        Result useClickedBlock;
        Result useItemInHand;
        PlayerInteractEvent(Object player, Object pos) {
            this.player = player;
            this.pos = pos;
            this.useItemInHand = Result.DEFAULT;
            this.useClickedBlock = pos == null ? Result.DENY : Result.ALLOW;
        }
        boolean isCancelled() { return this.useClickedBlock == Result.DENY; }
    }

    /** ItemStack 语义复刻（copy = 新实例同字段）。 */
    static final class ItemStack {
        final int id;
        int count;
        ItemStack(int id, int count) { this.id = id; this.count = count; }
        ItemStack copy() { return new ItemStack(this.id, this.count); }
        boolean isEmpty() { return this.count <= 0; }
    }

    /** HandlerList 语义复刻：注册表 + 空数组。 */
    static final class HandlerList {
        static final Object[] EMPTY = new Object[0];
        Object[] getRegisteredListeners() { return EMPTY; }
    }

    /** 触发器语义复刻：遍历已注册触发器并尝试触发（此处 0 个）。 */
    static final class Trigger {
        final HandlerList handlers = new HandlerList();
        void trigger(Object player, Object pos, ItemStack stack) {
            for (Object listener : this.handlers.getRegisteredListeners()) {
                Blackhole.consumeCPU(listener.hashCode());
            }
        }
    }

    private final HandlerList handlerList = new HandlerList();
    private final Trigger trigger = new Trigger();
    private final Object player = new Object();
    private final Object blockPos = new Object();
    private final ItemStack stack = new ItemStack(7, 3);
    private final ItemStack craftResult = new ItemStack(42, 1);

    // (a) PlayerInteractEvent

    @Benchmark
    public Object before_constructAndCall() {
        PlayerInteractEvent event = new PlayerInteractEvent(this.player, this.blockPos);
        for (Object listener : this.handlerList.getRegisteredListeners()) {
            Blackhole.consumeCPU(listener.hashCode());
        }
        return event;
    }

    @Benchmark
    public Object after_gated() {
        if (this.handlerList.getRegisteredListeners().length > 0) {
            PlayerInteractEvent event = new PlayerInteractEvent(this.player, this.blockPos);
            for (Object listener : this.handlerList.getRegisteredListeners()) {
                Blackhole.consumeCPU(listener.hashCode());
            }
            return event;
        }
        return null;
    }

    // (b) 触发器 + stack.copy

    @Benchmark
    public int before_copyAndTrigger(Blackhole bh) {
        ItemStack copy = this.stack.copy();
        this.trigger.trigger(this.player, this.blockPos, copy);
        bh.consume(copy);
        return copy.count;
    }

    @Benchmark
    public int after_gatedTrigger(Blackhole bh) {
        ItemStack copy = this.trigger.handlers.getRegisteredListeners().length > 0 ? this.stack.copy() : null;
        if (copy != null) {
            this.trigger.trigger(this.player, this.blockPos, copy);
            bh.consume(copy);
            return copy.count;
        }
        return 0;
    }

    // (c) ItemCraftedEvent

    /** asBukkitCopy 语义复刻：包装对象分配。 */
    static final class BukkitCopy {
        final ItemStack handle;
        BukkitCopy(ItemStack handle) { this.handle = handle; }
    }

    @Benchmark
    public Object before_bukkitCopyAndCall() {
        BukkitCopy copy = new BukkitCopy(this.craftResult);
        for (Object listener : this.handlerList.getRegisteredListeners()) {
            Blackhole.consumeCPU(listener.hashCode());
        }
        return copy;
    }

    @Benchmark
    public Object after_craftGated() {
        if (!this.craftResult.isEmpty() && this.handlerList.getRegisteredListeners().length > 0) {
            BukkitCopy copy = new BukkitCopy(this.craftResult);
            for (Object listener : this.handlerList.getRegisteredListeners()) {
                Blackhole.consumeCPU(listener.hashCode());
            }
            return copy;
        }
        return null;
    }

    // (d) callPreCraftEvent

    /** PreCraft 事件语义复刻：事件对象 + 矩阵引用。 */
    static final class PreCraftEvent {
        final Object matrix;
        final ItemStack result;
        PreCraftEvent(Object matrix, ItemStack result) { this.matrix = matrix; this.result = result; }
    }

    private final Object craftMatrix = new Object();

    @Benchmark
    public Object before_preCraftEvent() {
        PreCraftEvent event = new PreCraftEvent(this.craftMatrix, this.craftResult);
        for (Object listener : this.handlerList.getRegisteredListeners()) {
            Blackhole.consumeCPU(listener.hashCode());
        }
        return event.result;
    }

    @Benchmark
    public Object after_preCraftFastPath() {
        if (this.handlerList.getRegisteredListeners().length == 0) {
            return this.craftResult;
        }
        PreCraftEvent event = new PreCraftEvent(this.craftMatrix, this.craftResult);
        for (Object listener : this.handlerList.getRegisteredListeners()) {
            Blackhole.consumeCPU(listener.hashCode());
        }
        return event.result;
    }

    /** 等价性自检：零监听器时两条路径可观察结果一致。 */
    public static void main(String[] args) {
        InteractEventGateBench bench = new InteractEventGateBench();
        // (a) 事件字段默认值语义
        PlayerInteractEvent withPos = new PlayerInteractEvent(bench.player, bench.blockPos);
        PlayerInteractEvent noPos = new PlayerInteractEvent(bench.player, null);
        if (withPos.useClickedBlock != Result.ALLOW || withPos.useItemInHand != Result.DEFAULT || withPos.isCancelled()) {
            System.out.println("MISMATCH interact defaults (pos)"); System.exit(1);
        }
        if (noPos.useClickedBlock != Result.DENY || !noPos.isCancelled()) {
            System.out.println("MISMATCH interact defaults (null)"); System.exit(1);
        }
        if (bench.before_constructAndCall() == null || bench.after_gated() != null) {
            System.out.println("MISMATCH interact gate"); System.exit(1);
        }
        // (b) copy 语义 + 门控
        ItemStack copy = bench.stack.copy();
        if (copy == bench.stack || copy.id != bench.stack.id || copy.count != bench.stack.count) {
            System.out.println("MISMATCH stack copy"); System.exit(1);
        }
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_copyAndTrigger(bh) != bench.stack.count || bench.after_gatedTrigger(bh) != 0) {
            System.out.println("MISMATCH trigger gate"); System.exit(1);
        }
        // (c)
        if (((BukkitCopy) bench.before_bukkitCopyAndCall()).handle != bench.craftResult || bench.after_craftGated() != null) {
            System.out.println("MISMATCH craft gate"); System.exit(1);
        }
        // (d)
        if (bench.before_preCraftEvent() != bench.craftResult || bench.after_preCraftFastPath() != bench.craftResult) {
            System.out.println("MISMATCH precraft fast path"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
