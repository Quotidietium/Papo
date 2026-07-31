package papo.bench;

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
 * 批次42 / 0170: canHitEntity 谓词实例提升为 Projectile 字段（8 处每 tick 方法引用分配消除）。
 * before：每次求值 `this::canHitEntity` 新建捕获 lambda 后调用。
 * after：构造期一次缓存，每 tick 字段读取后调用。
 * 等价核心：方法引用指向虚方法，调用时虚分派——缓存实例与每次新建在分派语义上完全一致
 *        （子类覆盖在两者下都被调用）；消费方（实体检索过滤）不比较谓词身份。
 * main 自检：基类/子类两形态下缓存谓词与新建谓词判定一致，且缓存谓词确实触发子类覆盖（虚分派验证）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class CanHitPredicateCacheBench {

    /** Projectile 语义复刻。 */
    static class Projectile {
        protected final Predicate<Object> cachedPredicate = this::canHitEntity;
        protected boolean ownerIsPlayer = false;

        protected boolean canHitEntity(Object target) {
            return !this.ownerIsPlayer || target != this;
        }
    }

    /** AbstractArrow 语义复刻（覆盖 canHitEntity）。 */
    static class Arrow extends Projectile {
        int piercingIgnoreId = -1;

        @Override
        protected boolean canHitEntity(Object target) {
            return super.canHitEntity(target) && System.identityHashCode(target) != this.piercingIgnoreId;
        }
    }

    private Projectile arrow;

    @Setup
    public void setup() {
        this.arrow = new Arrow();
    }

    /** before：每 tick 新建方法引用。 */
    @Benchmark
    public void before_freshMethodRef(Blackhole bh) {
        Predicate<Object> filter = this.arrow::canHitEntity; // 捕获 lambda：每次分配
        bh.consume(filter);
        bh.consume(filter.test(this));
    }

    /** after：字段缓存。 */
    @Benchmark
    public void after_cachedField(Blackhole bh) {
        Predicate<Object> filter = this.arrow.cachedPredicate;
        bh.consume(filter);
        bh.consume(filter.test(this));
    }

    /** 等价性自检：虚分派 + 判定一致性。 */
    public static void main(String[] args) {
        Object target = new Object();
        // 基类形态
        Projectile base = new Projectile();
        base.ownerIsPlayer = true;
        Predicate<Object> freshBase = base::canHitEntity;
        if (freshBase.test(base) != base.cachedPredicate.test(base) || freshBase.test(target) != base.cachedPredicate.test(target)) {
            System.out.println("MISMATCH base dispatch");
            System.exit(1);
        }
        // 子类覆盖形态：缓存谓词必须调用 Arrow.canHitEntity（虚分派验证）
        Arrow arrow = new Arrow();
        arrow.ownerIsPlayer = true;
        arrow.piercingIgnoreId = System.identityHashCode(target);
        if (arrow.cachedPredicate.test(target)) {
            System.out.println("MISMATCH cached predicate did not dispatch to Arrow.canHitEntity (expected false)");
            System.exit(1);
        }
        Predicate<Object> freshArrow = arrow::canHitEntity;
        if (freshArrow.test(target) != arrow.cachedPredicate.test(target) || freshArrow.test(arrow) != arrow.cachedPredicate.test(arrow)) {
            System.out.println("MISMATCH arrow dispatch");
            System.exit(1);
        }
        // 覆盖字段变化后两侧仍一致（谓词不捕获字段快照，调用时读取）
        arrow.piercingIgnoreId = -1;
        if (freshArrow.test(target) != arrow.cachedPredicate.test(target)) {
            System.out.println("MISMATCH after field mutation");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
