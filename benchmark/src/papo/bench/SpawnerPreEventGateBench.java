package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次40 / 0165: 刷怪笼 PreSpawnerSpawnEvent 零监听器快路。
 * before（原版每次刷怪尝试每实体）：CraftLocation.toBukkit(vec3) + minecraftToBukkit
 *        + CraftLocation.toBukkit(pos) + PreSpawnerSpawnEvent 构造 + callEvent 空派发。
 * after：共享静态 HandlerList（PreCreatureSpawnEvent 持有，PreSpawnerSpawnEvent 无独立表）
 *        监听器数检查，零监听器时整体跳过（callEvent 恒 true 且无副作用——等价论证见下）。
 * 语义复刻：事件取消/shouldAbortSpawn 双标志、flag 置位与 break/continue 流程逐字复刻；
 *        事件经 Blackhole 强制逃逸（真实路径 callEvent 发布）。
 * main 自检：零监听器/取消监听器/中止监听器/普通监听器 四种场景两路径
 *        flag、proceed 计数、break 行为完全一致（含快路在有监听器时不生效的守卫验证）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SpawnerPreEventGateBench {

    /** CraftLocation.toBukkit 复刻（Location 包装：3 double + world 引用）。 */
    static final class Location {
        final double x;
        final double y;
        final double z;
        final Object world;

        Location(double x, double y, double z, Object world) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.world = world;
        }
    }

    /** PreSpawnerSpawnEvent 复刻：共享静态监听器表（PreCreatureSpawnEvent.HandlerList 语义）。 */
    static final class PreSpawnerSpawnEvent {
        static final List<Consumer<PreSpawnerSpawnEvent>> HANDLER_LIST = new ArrayList<>();

        final Location location;
        final Object type;
        final Location spawnerLocation;
        boolean cancelled;
        boolean shouldAbortSpawn;

        PreSpawnerSpawnEvent(Location location, Object type, Location spawnerLocation) {
            this.location = location;
            this.type = type;
            this.spawnerLocation = spawnerLocation;
        }

        /** callEvent 复刻：派发后返回 !isCancelled()。 */
        boolean callEvent() {
            for (Consumer<PreSpawnerSpawnEvent> listener : HANDLER_LIST) {
                listener.accept(this);
            }
            return !this.cancelled;
        }

        boolean shouldAbortSpawn() {
            return this.shouldAbortSpawn;
        }
    }

    /** 一次刷怪循环的流程结果（before/after 对比用）。 */
    static final class FlowResult {
        boolean flag;
        int proceeded; // 通过事件检查继续生成的次数
        boolean broke; // 命中 shouldAbortSpawn break
    }

    private Object world;
    private Object entityType;

    @Setup
    public void setup() {
        this.world = new Object();
        this.entityType = new Object();
        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
    }

    /** before：原版事件路径（spawnCount=4 循环体的事件段）。 */
    private static void runBefore(FlowResult r, Object world, Object entityType, Blackhole bh) {
        r.flag = false;
        r.proceeded = 0;
        r.broke = false;
        for (int i = 0; i < 4; i++) {
            Location loc = new Location(100.5 + i, 65.0, -200.5, world); // CraftLocation.toBukkit(vec3)
            Location spawnerLoc = new Location(100.0, 64.0, -201.0, world); // CraftLocation.toBukkit(pos)
            PreSpawnerSpawnEvent event = new PreSpawnerSpawnEvent(loc, entityType, spawnerLoc);
            bh.consume(event); // callEvent 发布逃逸
            if (!event.callEvent()) {
                r.flag = true;
                if (event.shouldAbortSpawn()) {
                    r.broke = true;
                    break;
                }
                continue;
            }
            r.proceeded++;
        }
    }

    /** after：零监听器快路。 */
    private static void runAfter(FlowResult r, Object world, Object entityType, Blackhole bh) {
        r.flag = false;
        r.proceeded = 0;
        r.broke = false;
        for (int i = 0; i < 4; i++) {
            if (!PreSpawnerSpawnEvent.HANDLER_LIST.isEmpty()) {
                Location loc = new Location(100.5 + i, 65.0, -200.5, world);
                Location spawnerLoc = new Location(100.0, 64.0, -201.0, world);
                PreSpawnerSpawnEvent event = new PreSpawnerSpawnEvent(loc, entityType, spawnerLoc);
                bh.consume(event);
                if (!event.callEvent()) {
                    r.flag = true;
                    if (event.shouldAbortSpawn()) {
                        r.broke = true;
                        break;
                    }
                    continue;
                }
            }
            r.proceeded++;
        }
    }

    @Benchmark
    public int before_eventPath(Blackhole bh) {
        FlowResult r = new FlowResult();
        runBefore(r, this.world, this.entityType, bh);
        return (r.flag ? 100 : 0) + r.proceeded;
    }

    @Benchmark
    public int after_zeroListenerFastPath(Blackhole bh) {
        FlowResult r = new FlowResult();
        runAfter(r, this.world, this.entityType, bh);
        return (r.flag ? 100 : 0) + r.proceeded;
    }

    /** 等价性自检：四种监听器场景流程一致。 */
    public static void main(String[] args) {
        Object world = new Object();
        Object entityType = new Object();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");

        // 场景 1：零监听器
        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
        checkFlow("zero-listener", world, entityType, bh);

        // 场景 2：取消监听器（不中止）——快路不得生效，两路径 flag=true、proceeded=0、broke=false
        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
        PreSpawnerSpawnEvent.HANDLER_LIST.add(e -> e.cancelled = true);
        checkFlow("cancelling-listener", world, entityType, bh);

        // 场景 3：取消+中止监听器——首次迭代即 break
        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
        PreSpawnerSpawnEvent.HANDLER_LIST.add(e -> {
            e.cancelled = true;
            e.shouldAbortSpawn = true;
        });
        checkFlow("aborting-listener", world, entityType, bh);

        // 场景 4：普通监听器（不取消）——全量 proceed
        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
        PreSpawnerSpawnEvent.HANDLER_LIST.add(e -> {
        });
        checkFlow("plain-listener", world, entityType, bh);

        PreSpawnerSpawnEvent.HANDLER_LIST.clear();
        System.out.println("ALL OK");
    }

    private static void checkFlow(String name, Object world, Object entityType, Blackhole bh) {
        FlowResult before = new FlowResult();
        FlowResult after = new FlowResult();
        runBefore(before, world, entityType, bh);
        runAfter(after, world, entityType, bh);
        if (before.flag != after.flag || before.proceeded != after.proceeded || before.broke != after.broke) {
            System.out.println(
                "MISMATCH @" + name + ": flag " + before.flag + "/" + after.flag
                    + " proceeded " + before.proceeded + "/" + after.proceeded
                    + " broke " + before.broke + "/" + after.broke
            );
            System.exit(1);
        }
    }
}
