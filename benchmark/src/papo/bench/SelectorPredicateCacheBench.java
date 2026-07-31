package papo.bench;

import java.util.ArrayList;
import java.util.List;
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
 * 批次38 / 0156: EntitySelector.getPredicate 上下文无关路径（无 features/box/range）
 * 每次调用 Util.allOf(contextFreePredicates)（size>=2 时分配捕获 lambda）→ 惰性缓存。
 * 语义复刻：3 个无状态上下文谓词（类型/名称/计分风格）+ Util.allOf 尺寸分派复刻
 * （0/1 免分配，2-5 捕获 lambda）。谓词经 Blackhole 强制逃逸（真实路径传入
 * level.getEntities 扫描）。main 自检：缓存命中同实例、两路径对实体矩阵判定一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SelectorPredicateCacheBench {

    /** 实体语义复刻。 */
    record Entity(String type, String name, int score) {}

    /** Util.allOf(List) 语义复刻（仅 0-5 分派与默认 varargs 的分配行为一致）。 */
    @SuppressWarnings("unchecked")
    static <T> Predicate<T> allOf(List<? extends Predicate<? super T>> predicates) {
        return switch (predicates.size()) {
            case 0 -> input -> true;
            case 1 -> (Predicate<T>) predicates.get(0);
            case 2 -> {
                Predicate<? super T> p1 = predicates.get(0);
                Predicate<? super T> p2 = predicates.get(1);
                yield input -> p1.test(input) && p2.test(input);
            }
            case 3 -> {
                Predicate<? super T> p1 = predicates.get(0);
                Predicate<? super T> p2 = predicates.get(1);
                Predicate<? super T> p3 = predicates.get(2);
                yield input -> p1.test(input) && p2.test(input) && p3.test(input);
            }
            default -> {
                Predicate<? super T>[] arr = predicates.toArray(new Predicate[0]);
                yield input -> {
                    for (Predicate<? super T> p : arr) {
                        if (!p.test(input)) {
                            return false;
                        }
                    }
                    return true;
                };
            }
        };
    }

    private List<Predicate<Entity>> contextFreePredicates;
    private Predicate<Entity> cached;
    private Entity[] entities;

    @Setup
    public void setup() {
        this.contextFreePredicates = List.of(
            e -> e.type().equals("zombie"),
            e -> !e.name().isEmpty(),
            e -> e.score() >= 0
        );
        this.entities = new Entity[16];
        for (int i = 0; i < 16; i++) {
            this.entities[i] = new Entity(i % 3 == 0 ? "zombie" : "skeleton", i % 2 == 0 ? "bob" : "", i - 4);
        }
    }

    private int runAll(Predicate<Entity> predicate, Blackhole bh) {
        int hits = 0;
        for (Entity e : this.entities) {
            if (predicate.test(e)) {
                hits++;
            }
        }
        bh.consume(predicate); // 真实路径谓词逃逸进 getEntities 扫描
        return hits;
    }

    @Benchmark
    public int before_allOfPerCall(Blackhole bh) {
        Predicate<Entity> predicate = allOf(this.contextFreePredicates);
        return this.runAll(predicate, bh);
    }

    @Benchmark
    public int after_cached(Blackhole bh) {
        Predicate<Entity> predicate = this.cached;
        if (predicate == null) {
            predicate = allOf(this.contextFreePredicates);
            this.cached = predicate;
        }
        return this.runAll(predicate, bh);
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        SelectorPredicateCacheBench bench = new SelectorPredicateCacheBench();
        bench.setup();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        // 缓存两次同实例
        Predicate<Entity> c1 = allOf(bench.contextFreePredicates);
        bench.cached = c1;
        bench.after_cached(bh);
        if (bench.cached != c1) {
            System.out.println("MISMATCH identity");
            System.exit(1);
        }
        // 判定矩阵一致
        bench.cached = null;
        int a = bench.before_allOfPerCall(bh);
        int b = bench.after_cached(bh);
        if (a != b) {
            System.out.println("MISMATCH hits: " + a + " vs " + b);
            System.exit(1);
        }
        // 0/1 尺寸分派保持免分配语义（返回原实例/单例语义：0 -> 每次同实例非捕获 lambda）
        Predicate<Entity> single = e -> true;
        if (allOf(List.of(single)) != single) {
            System.out.println("MISMATCH size-1 passthrough");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
