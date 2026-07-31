package papo.bench;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次37: ItemStack.validatedStreamCodec 解码每个非空入站物品栈
 * createSerializationContext(CountingOps.INSTANCE)（RegistryOps + HolderLookupAdapter
 * + ConcurrentHashMap 三连分配）→ ImmutableRegistryAccess volatile 缓存（0133 同构，
 * CountingOps.INSTANCE 为不可变无状态单例：final maxDepth、每操作新建 builder）。
 * 复刻与 0133 RegistryOpsCacheBench 相同的分配组，仅 ops 类型不同。
 * main 自检：缓存实例与新建实例的 ops/注册表引用一致、两次返回同一实例。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CountingOpsCacheBench {

    /** RegistryOps 语义复刻（ops 委托 + lookup 适配器 + CHM 缓存）。 */
    static final class RegistryOps {
        final Object ops;
        final HolderLookupAdapter lookup;
        RegistryOps(Object ops, HolderLookupAdapter lookup) {
            this.ops = ops;
            this.lookup = lookup;
        }
    }

    static final class HolderLookupAdapter {
        final ConcurrentHashMap<Object, Object> lookups = new ConcurrentHashMap<>();
        final Object registryAccess;
        HolderLookupAdapter(Object registryAccess) {
            this.registryAccess = registryAccess;
        }
    }

    /** ImmutableRegistryAccess 语义复刻（含 volatile 缓存字段）。 */
    static final class ImmutableRegistryAccess {
        final Object registries = new Object();
        volatile RegistryOps cachedOps;

        RegistryOps createSerializationContext(Object ops) {
            return new RegistryOps(ops, new HolderLookupAdapter(this.registries));
        }

        RegistryOps papoCountingSerializationContext(Object countingOps) {
            RegistryOps ops = this.cachedOps;
            if (ops == null) {
                ops = this.createSerializationContext(countingOps);
                this.cachedOps = ops;
            }
            return ops;
        }
    }

    private static final Object COUNTING_OPS = new Object(); // CountingOps.INSTANCE 语义
    private final ImmutableRegistryAccess access = new ImmutableRegistryAccess();

    @Benchmark
    public Object before_createPerDecode(Blackhole bh) {
        RegistryOps ops = this.access.createSerializationContext(COUNTING_OPS);
        bh.consume(ops);
        return ops;
    }

    @Benchmark
    public Object after_volatileCached(Blackhole bh) {
        RegistryOps ops = this.access.papoCountingSerializationContext(COUNTING_OPS);
        bh.consume(ops);
        return ops;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        CountingOpsCacheBench bench = new CountingOpsCacheBench();
        RegistryOps fresh = bench.access.createSerializationContext(COUNTING_OPS);
        RegistryOps cached1 = bench.access.papoCountingSerializationContext(COUNTING_OPS);
        RegistryOps cached2 = bench.access.papoCountingSerializationContext(COUNTING_OPS);
        if (cached1 != cached2) { System.out.println("MISMATCH identity"); System.exit(1); }
        if (cached1.ops != fresh.ops || cached1.lookup.registryAccess != fresh.lookup.registryAccess) {
            System.out.println("MISMATCH content"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
