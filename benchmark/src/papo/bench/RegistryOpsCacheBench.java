package papo.bench;

import java.util.Map;
import java.util.Optional;
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
 * 批次35: ImmutableRegistryAccess 缓存 NBT RegistryOps（ByteBufCodecs.fromCodecWithRegistries 热路径）。
 * 原实现：每组件编解码 RegistryOps.create(NbtOps.INSTANCE, access)
 *         → new RegistryOps + new HolderLookupAdapter +（首个 lookup 时）ConcurrentHashMap 表。
 * 新实现：volatile 字段惰性缓存，读取即返。
 * 语义复刻 RegistryOps.create 的分配结构（ThreadSafety 实证见源码注释：lookups 为 ConcurrentHashMap）。
 * main 自检：缓存路径两次返回同一实例；新建路径两实例 equals 语义（delegate+lookupProvider 同源）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RegistryOpsCacheBench {

    /** HolderLookup.Provider 语义复刻（不可变注册表访问）。 */
    interface Provider {
        Optional<Object> lookup(Object key);
    }

    /** RegistryOps.HolderLookupAdapter 语义复刻（lookup 缓存 CHM）。 */
    static final class HolderLookupAdapter {
        final Provider lookupProvider;
        final Map<Object, Optional<Object>> lookups = new ConcurrentHashMap<>();
        HolderLookupAdapter(Provider p) { this.lookupProvider = p; }
    }

    /** RegistryOps 语义复刻（delegate + lookupProvider 两个字段）。 */
    static final class RegistryOps {
        final Object delegate;
        final HolderLookupAdapter lookupProvider;
        RegistryOps(Object delegate, HolderLookupAdapter lp) { this.delegate = delegate; this.lookupProvider = lp; }
    }

    static final Object NBT_OPS = new Object(); // NbtOps.INSTANCE 单例语义复刻

    private final Provider access = key -> Optional.empty();
    private volatile RegistryOps cached;

    /** 原实现：每次调用新建。 */
    @Benchmark
    public RegistryOps before_createPerCall(Blackhole bh) {
        RegistryOps ops = new RegistryOps(NBT_OPS, new HolderLookupAdapter(this.access));
        bh.consume(ops);
        return ops;
    }

    /** 批次35：volatile 缓存读取。 */
    @Benchmark
    public RegistryOps after_cachedRead(Blackhole bh) {
        RegistryOps ops = this.cached;
        if (ops == null) {
            ops = new RegistryOps(NBT_OPS, new HolderLookupAdapter(this.access));
            this.cached = ops;
        }
        bh.consume(ops);
        return ops;
    }

    /** 自检：缓存路径同实例；新建路径各实例 delegate 与 lookupProvider 同源（equals 语义）。 */
    public static void main(String[] args) {
        RegistryOpsCacheBench bench = new RegistryOpsCacheBench();
        RegistryOps a = bench.after_cachedRead(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        RegistryOps b = bench.after_cachedRead(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        if (a != b) { System.out.println("MISMATCH cache identity"); System.exit(1); }
        RegistryOps c = bench.before_createPerCall(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        RegistryOps d = bench.before_createPerCall(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        if (c == d || c.delegate != d.delegate || c.lookupProvider.lookupProvider != d.lookupProvider.lookupProvider) {
            System.out.println("MISMATCH fresh semantics"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
