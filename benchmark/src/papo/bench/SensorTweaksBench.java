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
 * 批次35: 传感器两处微调。
 * (a) TemptingSensor：每 tick TEMPT_TARGETING.copy().range(v)（1 次分配+全字段复制）
 *     → 缓存实例 field.range(v)（仅改 range 字段返回 this，行为一致）。
 * (b) PlayerSensor：流过滤中逐玩家 getFollowDistance(entity)（每次属性取值计算）
 *     → 单次 doTick 外提（单线程内属性值恒定）。
 * TargetingConditions/属性取值语义复刻。
 * main 自检：(a) 两路径 conditions 各字段一致（含 range 运行时可变）；(b) 过滤结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SensorTweaksBench {

    /** TargetingConditions 语义复刻（range 可变返回 this；copy 复制全部字段）。 */
    static final class TargetingConditions {
        double range;
        boolean checkLineOfSight;
        boolean isCombat;
        TargetingConditions copy() {
            TargetingConditions c = new TargetingConditions();
            c.range = this.range;
            c.checkLineOfSight = this.checkLineOfSight;
            c.isCombat = this.isCombat;
            return c;
        }
        TargetingConditions range(double r) { this.range = r; return this; }
        /** test 只读：距离平方 <= range^2。 */
        boolean test(double distSqr) { return distSqr <= this.range * this.range; }
    }

    /** AttributeInstance.getValue 语义复刻（base + modifier 遍历）。 */
    static final class AttributeInstance {
        final double base;
        final double[] modifiers;
        AttributeInstance(double base, double... modifiers) { this.base = base; this.modifiers = modifiers; }
        double getValue() {
            double v = this.base;
            for (double m : this.modifiers) v += m;
            return v;
        }
    }

    private static final TargetingConditions TEMPT_TARGETING = new TargetingConditions();
    private final TargetingConditions cachedConditions = TEMPT_TARGETING.copy();
    private final AttributeInstance temptRange = new AttributeInstance(10.0, 0.5, -0.25, 1.0);
    private final AttributeInstance followRange = new AttributeInstance(16.0, 2.0, -1.0);
    private final double[] playerDistSqr = new double[20];

    public SensorTweaksBench() {
        for (int i = 0; i < this.playerDistSqr.length; i++) this.playerDistSqr[i] = i * i * 1.7;
    }

    // (a) TemptingSensor

    @Benchmark
    public int before_copyPerTick(Blackhole bh) {
        TargetingConditions conditions = TEMPT_TARGETING.copy().range(this.temptRange.getValue());
        int count = 0;
        for (double d : this.playerDistSqr) if (conditions.test(d)) count++;
        bh.consume(conditions);
        return count;
    }

    @Benchmark
    public int after_fieldReuse(Blackhole bh) {
        TargetingConditions conditions = this.cachedConditions.range(this.temptRange.getValue());
        int count = 0;
        for (double d : this.playerDistSqr) if (conditions.test(d)) count++;
        return count;
    }

    // (b) PlayerSensor

    @Benchmark
    public int before_perPlayerAttributeRead(Blackhole bh) {
        int count = 0;
        for (double d : this.playerDistSqr) {
            double followDistance = this.followRange.getValue(); // 逐玩家属性取值
            if (d < followDistance * followDistance) count++;
        }
        bh.consume(count);
        return count;
    }

    @Benchmark
    public int after_hoistedAttributeRead(Blackhole bh) {
        double followDistance = this.followRange.getValue(); // 外提一次
        int count = 0;
        for (double d : this.playerDistSqr) {
            if (d < followDistance * followDistance) count++;
        }
        return count;
    }

    /** 等价性自检。 */
    public static void main(String[] args) {
        SensorTweaksBench bench = new SensorTweaksBench();
        // (a) 两路径字段一致 + range 运行时可变均生效
        double[] ranges = {10.0, 3.5, 0.0, 1024.25};
        for (double r : ranges) {
            TargetingConditions a = TEMPT_TARGETING.copy().range(r);
            TargetingConditions b = bench.cachedConditions.range(r);
            if (a.range != b.range || a.checkLineOfSight != b.checkLineOfSight || a.isCombat != b.isCombat) {
                System.out.println("MISMATCH conditions @" + r); System.exit(1);
            }
            for (double d : bench.playerDistSqr) {
                if (a.test(d) != b.test(d)) { System.out.println("MISMATCH test @" + r); System.exit(1); }
            }
        }
        // (b) 外提一致
        int c1 = bench.before_perPlayerAttributeRead(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        int c2 = bench.after_hoistedAttributeRead(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        if (c1 != c2) { System.out.println("MISMATCH hoist"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
