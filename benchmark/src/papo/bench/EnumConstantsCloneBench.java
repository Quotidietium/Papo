package papo.bench;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0098: FriendlyByteBuf.readEnum/writeEnumSet/readEnumSet 枚举常量数组缓存。
 * before: Class.getEnumConstants() 每次调用克隆整个常量数组（JDK 行为）
 * after:  ClassValue 缓存，同一 Class 只取一次，调用点只读索引
 * 等价性：getEnumConstants() 返回共享数组的克隆，内容（单例、声明顺序）JVM 生命周期内不变；
 * 三个调用点均只读索引/迭代，数组不逃逸。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EnumConstantsCloneBench {

    /** 模拟 InteractionHand/Direction 规模的枚举（6 常量与 2 常量之间取 6）。 */
    enum SampleEnum {
        A, B, C, D, E, F
    }

    /** 与补丁中 FriendlyByteBuf.PAPO_ENUM_CONSTANTS 逐行一致。 */
    private static final ClassValue<Object[]> ENUM_CONSTANTS = new ClassValue<>() {
        @Override
        protected Object[] computeValue(Class<?> type) {
            return type.getEnumConstants();
        }
    };

    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> T[] cachedConstants(Class<T> enumClass) {
        return (T[]) ENUM_CONSTANTS.get(enumClass);
    }

    /** 原实现：readEnum 主体。 */
    @Benchmark
    public void before_getEnumConstants(Blackhole bh) {
        SampleEnum e = SampleEnum.class.getEnumConstants()[3];
        bh.consume(e);
    }

    /** Papo 0098：缓存数组。 */
    @Benchmark
    public void after_classValueCache(Blackhole bh) {
        SampleEnum e = cachedConstants(SampleEnum.class)[3];
        bh.consume(e);
    }

    /** 原实现：writeEnumSet 主体（数组 + 遍历）。 */
    @Benchmark
    public void before_enumSetWrite(Blackhole bh) {
        EnumSet<SampleEnum> set = EnumSet.of(SampleEnum.B, SampleEnum.E);
        SampleEnum[] enums = SampleEnum.class.getEnumConstants();
        long bits = 0;
        for (int i = 0; i < enums.length; i++) {
            if (set.contains(enums[i])) {
                bits |= 1L << i;
            }
        }
        bh.consume(bits);
    }

    /** Papo 0098：缓存数组版 writeEnumSet。 */
    @Benchmark
    public void after_enumSetWrite(Blackhole bh) {
        EnumSet<SampleEnum> set = EnumSet.of(SampleEnum.B, SampleEnum.E);
        SampleEnum[] enums = cachedConstants(SampleEnum.class);
        long bits = 0;
        for (int i = 0; i < enums.length; i++) {
            if (set.contains(enums[i])) {
                bits |= 1L << i;
            }
        }
        bh.consume(bits);
    }

    /** 等价性自检：缓存数组与每次克隆逐元素相同、顺序相同。 */
    public static void main(String[] args) {
        SampleEnum[] fresh = SampleEnum.class.getEnumConstants();
        SampleEnum[] cached = cachedConstants(SampleEnum.class);
        boolean ok = fresh.length == cached.length;
        for (int i = 0; ok && i < fresh.length; i++) {
            ok = fresh[i] == cached[i]; // 单例身份一致
        }
        // 缓存数组跨调用为同一实例（不重复克隆）
        ok &= cachedConstants(SampleEnum.class) == cached;
        // 新鲜克隆每次是不同实例（证明 before 确实每次分配）
        ok &= SampleEnum.class.getEnumConstants() != fresh;
        System.out.println(ok ? "ALL OK" : "MISMATCH");
        if (!ok) {
            System.exit(1);
        }
    }
}
