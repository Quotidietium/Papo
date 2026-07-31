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
 * 批次36: 熔炉 serverTick 每 tick new SingleRecipeInput(inputStack)
 * → 按输入槽栈引用缓存（引用不变即复用；记录仅包装引用，栈内变更透过缓存可见）。
 * 复刻：SingleRecipeInput record、槽位数组、64 tick 中 60 次引用不变 + 4 次换栈
 * （贴合烧炼中 shrink 不改引用、燃尽/放入才换引用的实际）。
 * main 自检：逐 tick item() 引用与配方查询结果两路径一致（含换栈 tick）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class FurnaceInputBench {

    /** SingleRecipeInput 语义复刻（record：仅包装引用）。 */
    record SingleRecipeInput(ItemStack item) {}

    /** ItemStack 语义复刻。 */
    static final class ItemStack {
        final int id;
        int count;
        ItemStack(int id, int count) { this.id = id; this.count = count; }
        boolean isEmpty() { return this.count <= 0; }
    }

    private final ItemStack[] slotSequence = new ItemStack[64]; // 每 tick 输入槽引用
    private ItemStack lastStack;
    private SingleRecipeInput cachedInput;

    public FurnaceInputBench() {
        ItemStack current = new ItemStack(263, 64); // coal
        for (int i = 0; i < 64; i++) {
            if (i % 16 == 15) { // 每 16 tick 换一次栈引用
                current = new ItemStack(263 + (i % 3), 64);
            }
            this.slotSequence[i] = current;
        }
    }

    /** quickCheck.getRecipeFor 语义复刻：读 item 字段做查询。 */
    private static int recipeFor(SingleRecipeInput input) {
        return input.item().id * 31 + input.item().count;
    }

    @Benchmark
    public int before_newInputPerTick(Blackhole bh) {
        int acc = 0;
        for (int i = 0; i < 64; i++) {
            SingleRecipeInput input = new SingleRecipeInput(this.slotSequence[i]);
            acc += recipeFor(input);
            bh.consume(input);
        }
        return acc;
    }

    @Benchmark
    public int after_cachedByReference(Blackhole bh) {
        int acc = 0;
        this.lastStack = null;
        this.cachedInput = null;
        for (int i = 0; i < 64; i++) {
            ItemStack stack = this.slotSequence[i];
            SingleRecipeInput input;
            if (this.lastStack == stack) {
                input = this.cachedInput;
            } else {
                this.lastStack = stack;
                this.cachedInput = input = new SingleRecipeInput(stack);
            }
            acc += recipeFor(input);
        }
        bh.consume(this.cachedInput);
        return acc;
    }

    /** 等价性自检（含 shrink 改 count 不换引用的情形：缓存透过引用可见新 count）。 */
    public static void main(String[] args) {
        FurnaceInputBench bench = new FurnaceInputBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_newInputPerTick(bh) != bench.after_cachedByReference(bh)) {
            System.out.println("MISMATCH bulk"); System.exit(1);
        }
        // shrink 语义：缓存包装的引用与槽位是同一对象，count 变化立即可见
        ItemStack stack = bench.slotSequence[0];
        SingleRecipeInput cached = new SingleRecipeInput(stack);
        stack.count = 41;
        if (recipeFor(cached) != recipeFor(new SingleRecipeInput(stack))) {
            System.out.println("MISMATCH shrink visibility"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
