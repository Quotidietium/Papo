package papo.bench;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: ChunkMap.TrackedEntity.getEffectiveRange() 在 moonrise$tick / updatePlayers
 * 扫描循环外提取（原版每 (实体,玩家) 对都重算：乘客列表检查 + 乘客范围遍历 + 服务器
 * 配置查询；扫描期间三者均不变）。
 * 复刻：无乘客实体（最常见）range 读取 + 配置查询 ×20 玩家 vs 外提一次。
 * main 自检：两路径每玩家 min(effectiveRange, viewDistance*16) 一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EffectiveRangeBench {

    /** 实体语义复刻：乘客列表 + clientTrackingRange。 */
    static final class Entity {
        final int range;
        final List<Entity> passengers = new java.util.ArrayList<>(); // 默认为空（无乘客，最常见）
        Entity(int range) { this.range = range; }
        List<Entity> getIndirectPassengers() { return this.passengers; }
    }

    /** 服务器配置语义复刻（getScaledTrackingDistance：原版恒等，保留调用层级）。 */
    static final class Server {
        int getScaledTrackingDistance(int trackingDistance) { return trackingDistance; }
    }

    private final Entity entity = new Entity(160);
    private final Server server = new Server();
    private final int[] playerViewDistances = new int[20];

    public EffectiveRangeBench() {
        for (int i = 0; i < 20; i++) this.playerViewDistances[i] = 6 + (i % 5);
    }

    /** getEffectiveRange 语义复刻。 */
    private int getEffectiveRange() {
        int range = this.entity.range;
        if (this.entity.passengers.isEmpty()) {
            return this.server.getScaledTrackingDistance(range);
        }
        for (Entity passenger : this.entity.getIndirectPassengers()) {
            range = Math.max(range, passenger.range);
        }
        return this.server.getScaledTrackingDistance(range);
    }

    @Benchmark
    public double before_recomputePerPlayer(Blackhole bh) {
        double acc = 0;
        for (int viewDistance : this.playerViewDistances) {
            double d = Math.min(this.getEffectiveRange(), viewDistance * 16);
            acc += d;
        }
        bh.consume(acc);
        return acc;
    }

    @Benchmark
    public double after_hoisted(Blackhole bh) {
        int effectiveRange = this.getEffectiveRange();
        double acc = 0;
        for (int viewDistance : this.playerViewDistances) {
            double d = Math.min(effectiveRange, viewDistance * 16);
            acc += d;
        }
        bh.consume(acc);
        return acc;
    }

    /** 等价性自检（含带乘客实体）。 */
    public static void main(String[] args) {
        EffectiveRangeBench bench = new EffectiveRangeBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (Double.doubleToLongBits(bench.before_recomputePerPlayer(bh)) != Double.doubleToLongBits(bench.after_hoisted(bh))) {
            System.out.println("MISMATCH empty passengers"); System.exit(1);
        }
        // 带乘客：getEffectiveRange 本身逻辑一致
        EffectiveRangeBench ridden = new EffectiveRangeBench();
        ridden.entity.passengers.add(new Entity(96));
        if (ridden.getEffectiveRange() != 160) { // max(160, 96)
            System.out.println("MISMATCH passenger range"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
