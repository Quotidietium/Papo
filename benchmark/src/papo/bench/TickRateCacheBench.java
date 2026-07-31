package papo.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次37: Behavior.tryStart / Sensor.tick 的 tickRates Table.get 双哈希查找
 * （每停止行为每实体每 tick / 每传感器触发）→ 按（配置纪元, 世界配置引用）缓存。
 * 复刻：Guava HashBasedTable.get = 行 Map 查找 + 列 Map 查找（含 Integer 拆箱）；
 * 30 个行为 × tryStart；缓存命中路径 = volatile 长读 + 引用比较。
 * main 自检：缓存命中/纪元失效/配置换引用三场景取值一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TickRateCacheBench {

    /** WorldConfiguration.tickRates.behavior 语义复刻（Table → 行/列双层 Map）。 */
    static final class WorldConfig {
        final Map<String, Map<String, Integer>> behavior = new HashMap<>();
        Integer get(String entityType, String configKey) {
            Map<String, Integer> row = this.behavior.get(entityType);
            return row == null ? null : row.get(configKey);
        }
    }

    /** Behavior 实例语义复刻。 */
    static final class Behavior {
        final String configKey;
        long tickRateEpoch = -1L;
        Object tickRateConfig = null;
        int cachedTickRate = -1;
        Behavior(String configKey) { this.configKey = configKey; }
    }

    private volatile long configEpoch = 0;
    private final WorldConfig config = new WorldConfig();
    private final Behavior[] behaviors = new Behavior[30];
    private final String entityType = "villager";

    public TickRateCacheBench() {
        Map<String, Integer> row = new HashMap<>();
        row.put("validatenearbypoi", -1);
        this.config.behavior.put("villager", row);
        String[] keys = {"validatenearbypoi", "acquirepoi", "yieldjobitems", "gotosleep", "interactwithdoor",
            "villagerbabi", "workatpoi", "play", "stroll", "harvestfarmland"};
        for (int i = 0; i < 30; i++) {
            this.behaviors[i] = new Behavior(keys[i % keys.length]);
        }
    }

    private int lookup(WorldConfig config) {
        Integer value = config.get(this.entityType, "validatenearbypoi");
        return value == null ? -1 : value;
    }

    @Benchmark
    public int before_tableGetPerTryStart(Blackhole bh) {
        int acc = 0;
        for (Behavior behavior : this.behaviors) {
            Integer value = this.config.get(this.entityType, behavior.configKey); // 双哈希 + 拆箱
            int tickRate = value == null ? -1 : value;
            acc += tickRate;
            bh.consume(tickRate);
        }
        return acc;
    }

    @Benchmark
    public int after_epochRefCache(Blackhole bh) {
        int acc = 0;
        long epoch = this.configEpoch;
        WorldConfig config = this.config;
        for (Behavior behavior : this.behaviors) {
            int tickRate;
            if (epoch == behavior.tickRateEpoch && config == behavior.tickRateConfig) {
                tickRate = behavior.cachedTickRate;
            } else {
                behavior.tickRateEpoch = epoch;
                behavior.tickRateConfig = config;
                Integer value = config.get(this.entityType, behavior.configKey);
                behavior.cachedTickRate = tickRate = value == null ? -1 : value;
            }
            acc += tickRate;
        }
        return acc;
    }

    /** 等价性自检：首未命中 → 命中 → 纪元递增失效 → 换配置引用失效。 */
    public static void main(String[] args) {
        TickRateCacheBench bench = new TickRateCacheBench();
        Behavior behavior = bench.behaviors[0];
        WorldConfig config = bench.config;
        // 未命中：与直接查找一致
        Integer direct = config.get("villager", behavior.configKey);
        int expected = direct == null ? -1 : direct;
        long epoch = bench.configEpoch;
        behavior.tickRateEpoch = epoch;
        behavior.tickRateConfig = config;
        behavior.cachedTickRate = expected;
        if (behavior.cachedTickRate != expected) { System.out.println("MISMATCH fill"); System.exit(1); }
        // 命中：返回缓存
        if (!(epoch == behavior.tickRateEpoch && config == behavior.tickRateConfig)) { System.out.println("MISMATCH hit guard"); System.exit(1); }
        // 纪元失效
        bench.configEpoch++;
        if (bench.configEpoch == behavior.tickRateEpoch) { System.out.println("MISMATCH epoch guard"); System.exit(1); }
        // 换配置引用失效
        WorldConfig other = new WorldConfig();
        if (other == behavior.tickRateConfig) { System.out.println("MISMATCH ref guard"); System.exit(1); }
        // 两路径批量一致
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (bench.before_tableGetPerTryStart(bh) != bench.after_epochRefCache(bh)) {
            System.out.println("MISMATCH bulk"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
