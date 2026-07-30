package papo.bench;

import java.util.ArrayList;
import java.util.List;
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
 * 0089: SleepStatus.areEnoughDeepSleeping 双 stream 合单遍。
 * before: stream().filter(longEnough || faux).count() + stream().anyMatch(longEnough)
 * after:  单遍 for 循环同时计数与记录 anyDeepSleep
 * 模拟夜晚睡觉期间每 tick 每世界对世界玩家列表的调用形态。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SleepStatusBench {

    @Param({"8", "64"})
    int players;

    private List<Player> list;

    static final class Player {
        final boolean sleepingLongEnough;
        final boolean fauxSleeping;

        Player(boolean sleepingLongEnough, boolean fauxSleeping) {
            this.sleepingLongEnough = sleepingLongEnough;
            this.fauxSleeping = fauxSleeping;
        }

        boolean isSleepingLongEnough() {
            return sleepingLongEnough;
        }
    }

    @Setup
    public void setup() {
        this.list = new ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            // 混合：1/4 深睡，1/4 faux，其余清醒
            list.add(new Player(i % 4 == 0, i % 4 == 1));
        }
    }

    @Benchmark
    public boolean before_twoStreams() {
        int i = (int) list.stream().filter(p -> p.isSleepingLongEnough() || p.fauxSleeping).count();
        boolean anyDeepSleep = list.stream().anyMatch(Player::isSleepingLongEnough);
        return anyDeepSleep && i >= 2;
    }

    @Benchmark
    public boolean after_singlePass() {
        int i = 0;
        boolean anyDeepSleep = false;
        for (Player p : list) {
            if (p.isSleepingLongEnough()) {
                i++;
                anyDeepSleep = true;
            } else if (p.fauxSleeping) {
                i++;
            }
        }
        return anyDeepSleep && i >= 2;
    }
}
