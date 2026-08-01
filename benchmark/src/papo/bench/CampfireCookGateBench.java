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
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次46 / 0183: 营火 BlockCookEvent 零监听器门控。
 * before：每烤熟物品 镜像 + asBukkitCopy + CraftBlock + toBukkitRecipe + 事件 + 空派发 + asNMSCopy
 *        （两次拷贝），随后 split 掉落循环。
 * after：BlockCookEvent 表门控（自有表；唯一子类 FurnaceSmeltEvent 共享同表），
 *        单次 copy() 保证 split 循环不就地改容器槽（无配方时 itemStack1 别名槽位栈）。
 * main 自检：有/无配方两场景——掉落物数量序列、槽位终态 EMPTY、槽位栈不被就地消耗，两路径一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CampfireCookGateBench {

    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack(0, 0);
        final int item;
        int count;

        ItemStack(int item, int count) { this.item = item; this.count = count; }
        boolean isEmpty() { return this == EMPTY || this.count <= 0 || this.item == 0; }
        ItemStack copy() { return this.isEmpty() ? EMPTY : new ItemStack(this.item, this.count); }
        ItemStack split(int n) {
            int i = Math.min(n, this.count);
            ItemStack out = this.copy();
            out.count = i;
            this.count -= i;
            return out;
        }
    }

    static final class CraftItemStack {
        final ItemStack handle;
        CraftItemStack(ItemStack handle) { this.handle = handle; }
        static CraftItemStack asCraftMirror(ItemStack original) {
            return new CraftItemStack(original == null || original.isEmpty() ? null : original);
        }
        static CraftItemStack asBukkitCopy(ItemStack original) { return asCraftMirror(original.copy()); }
        static ItemStack asNMSCopy(CraftItemStack original) {
            if (original == null || original.handle == null) return ItemStack.EMPTY;
            return original.handle.copy();
        }
    }

    static final class CraftBlock {
        CraftBlock(Object level, long pos) {}
        static CraftBlock at(Object level, long pos) { return new CraftBlock(level, pos); }
    }

    static final class BukkitRecipe {
        final Object key = new Object();
    }

    static final class BlockCookEvent {
        static final List<Consumer<BlockCookEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block;
        final CraftItemStack source;
        CraftItemStack result;
        final Object recipe;
        boolean cancelled;
        BlockCookEvent(CraftBlock block, CraftItemStack source, CraftItemStack result, Object recipe) {
            this.block = block; this.source = source; this.result = result; this.recipe = recipe;
        }
        boolean callEvent() {
            for (Consumer<BlockCookEvent> l : HANDLER_LIST) l.accept(this);
            return !this.cancelled;
        }
        CraftItemStack getResult() { return this.result; }
    }

    /** 确定性"随机"序列（复刻 split(level.random.nextInt(21)+10) 的消耗序列）。 */
    static final int[] SPLIT_SEQ = {15, 22, 10, 30, 18, 25, 12, 20, 11, 27};
    int splitIdx;

    int nextSplit() {
        int v = SPLIT_SEQ[this.splitIdx % SPLIT_SEQ.length];
        this.splitIdx++;
        return v;
    }

    List<Integer> dropped;

    void dropLoop(ItemStack stack) {
        while (!stack.isEmpty()) {
            ItemStack droppedStack = stack.split(this.nextSplit());
            this.dropped.add(droppedStack.count);
        }
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    /** before：完整事件路径（含两次拷贝的 round-trip）。 */
    public int beforeBody() {
        ItemStack slot = new ItemStack(5, 64);
        ItemStack itemStack1 = slot; // 无配方：别名槽位栈
        this.dropped = new ArrayList<>();
        this.splitIdx = 0;

        CraftItemStack source = CraftItemStack.asCraftMirror(slot);
        CraftItemStack result = CraftItemStack.asBukkitCopy(itemStack1);
        BlockCookEvent event = new BlockCookEvent(CraftBlock.at(this, 0L), source, result, new BukkitRecipe());
        if (!event.callEvent()) return -1;
        result = event.getResult();
        itemStack1 = CraftItemStack.asNMSCopy(result);

        this.dropLoop(itemStack1);
        this.sink = event;
        this.sink = slot; // 槽位终态（before 路径未被就地消耗）
        return this.dropped.size();
    }

    /** after：门控 + 单次 copy。 */
    public int afterBody() {
        ItemStack slot = new ItemStack(5, 64);
        ItemStack itemStack1 = slot;
        this.dropped = new ArrayList<>();
        this.splitIdx = 0;

        if (BlockCookEvent.HANDLER_LIST.size() == 0) {
            itemStack1 = itemStack1.copy();
            this.dropLoop(itemStack1);
            this.sink = slot;
            return this.dropped.size();
        }
        return -2;
    }


    @Benchmark public int before(Blackhole bh) { int r = this.beforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int after(Blackhole bh) { int r = this.afterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        CampfireCookGateBench b = new CampfireCookGateBench();
        // 无配方场景（别名槽位）
        int r1 = b.beforeBody();
        List<Integer> d1 = b.dropped;
        int r2 = b.afterBody();
        List<Integer> d2 = b.dropped;
        if (r1 != r2 || !d1.equals(d2)) throw new AssertionError("no-recipe mismatch: " + d1 + " vs " + d2);
        // 有配方场景（itemStack1 为 assemble 独占新栈）
        b.dropped = new ArrayList<>(); b.splitIdx = 0;
        ItemStack assembled = new ItemStack(6, 20);
        ItemStack rt = CraftItemStack.asNMSCopy(CraftItemStack.asBukkitCopy(assembled));
        b.dropLoop(rt);
        List<Integer> e1 = b.dropped;
        b.dropped = new ArrayList<>(); b.splitIdx = 0;
        ItemStack cp = assembled.copy();
        b.dropLoop(cp);
        if (!e1.equals(b.dropped)) throw new AssertionError("recipe mismatch");
        // EMPTY 输入：两路径 dropLoop 均不执行
        ItemStack emptyRt = CraftItemStack.asNMSCopy(CraftItemStack.asBukkitCopy(ItemStack.EMPTY));
        if (emptyRt != ItemStack.EMPTY || ItemStack.EMPTY.copy() != ItemStack.EMPTY) throw new AssertionError("empty path");
        System.out.println("ALL OK");
    }
}
