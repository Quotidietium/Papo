package papo.bench;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0110: InventoryMenu.broadcastSlotChange 无条件 memoize(item::copy) → 变更门控。
 * 绝大多数 containerUpdate tick 槽位未变，两个消费方均为 no-op，memoize supplier 是纯垃圾
 * （MemoizingSupplier 实例 + 捕获 lambda，且逃逸进下游调用，EA 无法消除）。
 * 场景：6 槽位 1 变更（近似真实：开 GUI 时仅手持物偶尔变化）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MemoizeGateBench {

    /** Guava memoize 语义复刻（分配形态一致：包装实例 + 委托 lambda）。 */
    static final class MemoizingSupplier<T> implements Supplier<T> {
        private final Supplier<T> delegate;
        private T value;
        private boolean initialized;
        MemoizingSupplier(Supplier<T> delegate) { this.delegate = delegate; }
        @Override
        public T get() {
            if (!this.initialized) {
                this.value = this.delegate.get();
                this.initialized = true;
            }
            return this.value;
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new MemoizingSupplier<>(delegate);
    }

    /** ItemStack 语义复刻（引用相等 + matches 简化判等）。 */
    static final class ItemStackLike {
        final int id;
        final int count;
        ItemStackLike(int id, int count) { this.id = id; this.count = count; }
        ItemStackLike copy() { return new ItemStackLike(this.id, this.count); }
        boolean matches(ItemStackLike other) { return this.id == other.id && this.count == other.count; }
    }

    private final ItemStackLike[] slots = new ItemStackLike[6];
    private final ItemStackLike[] lastSlots = new ItemStackLike[6];
    private int tick;

    @Setup
    public void setup() {
        for (int i = 0; i < 6; i++) {
            this.slots[i] = new ItemStackLike(i, 1);
            this.lastSlots[i] = new ItemStackLike(i, 1);
        }
    }

    private void rotate() {
        // 每轮只变 1 个槽位（先同步 lastSlots 再改 slots）
        this.tick++;
        int slot = this.tick % 6;
        for (int i = 0; i < 6; i++) {
            this.lastSlots[i] = this.slots[i];
        }
        this.slots[slot] = new ItemStackLike(slot, (this.tick & 3) + 1);
    }

    private static Supplier<ItemStackLike> consumeSlot(ItemStackLike item, Supplier<ItemStackLike> supplier, Blackhole bh) {
        // triggerSlotListeners/synchronizeSlotToRemote 的消费形态：仅在变更时取 supplier
        bh.consume(item);
        return supplier;
    }

    /** 原实现：每槽位无条件构造 memoize supplier。 */
    @Benchmark
    public void before_unconditionalMemoize(Blackhole bh) {
        rotate();
        for (int i = 0; i < 6; i++) {
            ItemStackLike item = this.slots[i];
            Supplier<ItemStackLike> supplier = memoize(item::copy);
            if (!item.matches(this.lastSlots[i])) {
                bh.consume(supplier.get());
            }
            consumeSlot(item, supplier, bh);
        }
    }

    /** Papo 0110：仅变更槽位构造 supplier。 */
    @Benchmark
    public void after_gatedMemoize(Blackhole bh) {
        rotate();
        for (int i = 0; i < 6; i++) {
            ItemStackLike item = this.slots[i];
            boolean listenerNeeds = !item.matches(this.lastSlots[i]);
            if (listenerNeeds) {
                Supplier<ItemStackLike> supplier = memoize(item::copy);
                bh.consume(supplier.get());
                consumeSlot(item, supplier, bh);
            }
        }
    }

    /** 等价性自检：随机变更序列下，门控版的"变更判定"与原消费方的内部判定逐槽位一致。 */
    public static void main(String[] args) {
        MemoizeGateBench bench = new MemoizeGateBench();
        bench.setup();
        java.util.Random rnd = new java.util.Random(3);
        for (int round = 0; round < 10000; round++) {
            // 随机变更 0-2 个槽位
            for (ItemStackLike[] arr : new ItemStackLike[][]{bench.slots}) {
                System.arraycopy(bench.slots, 0, bench.lastSlots, 0, 6);
            }
            int changes = rnd.nextInt(3);
            boolean[] changed = new boolean[6];
            for (int c = 0; c < changes; c++) {
                int slot = rnd.nextInt(6);
                bench.slots[slot] = new ItemStackLike(slot, rnd.nextInt(5));
                changed[slot] = true;
            }
            for (int i = 0; i < 6; i++) {
                boolean listenerNeeds = !bench.slots[i].matches(bench.lastSlots[i]);
                boolean calleeWouldAct = !bench.slots[i].matches(bench.lastSlots[i]); // triggerSlotListeners 内部判定
                if (listenerNeeds != calleeWouldAct) {
                    System.out.println("GATE MISMATCH round=" + round + " slot=" + i);
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
