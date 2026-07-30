package papo.bench;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 0088: Ingredient.testOptionalIngredient map/orElseGet vs 三目。
 * before: ingredient.<Boolean>map(i -> i.test(stack)).orElseGet(stack::isEmpty)
 *         （Optional<Boolean> 装箱 + 捕获 lambda + 方法引用分配）
 * after:  ingredient.isPresent() ? ingredient.get().test(stack) : stack.isEmpty()
 * 模拟每个配方格匹配（3x3 网格 x N 候选配方）的调用形态。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IngredientOptionalBench {

    @Param({"present", "empty"})
    String kind;

    private Optional<Object> ingredient;
    private Object stack;

    @Setup
    public void setup() {
        this.ingredient = "present".equals(kind) ? Optional.of(new Object()) : Optional.empty();
        this.stack = new Object();
    }

    private boolean test(Object ing, Object stk) {
        return ing == stk; // 模拟 Ingredient.test 的廉价分支
    }

    private boolean isEmpty(Object stk) {
        return false; // 模拟 ItemStack.isEmpty
    }

    @Benchmark
    public boolean before_mapOrElseGet() {
        return ingredient.<Boolean>map(i -> test(i, stack)).orElseGet(() -> isEmpty(stack));
    }

    @Benchmark
    public boolean after_ternary() {
        return ingredient.isPresent() ? test(ingredient.get(), stack) : isEmpty(stack);
    }
}
