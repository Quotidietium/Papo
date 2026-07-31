package papo.bench;

import java.util.ArrayList;
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
 * 批次36: ChunkHolder.broadcast players.forEach(player -> connection.send(packet))
 * （捕获 lambda：每次 broadcast 一次分配 + invokedynamic 调用）→ 索引循环。
 * players 为 moonrise$getPlayers 每次新建的局部 ArrayList，迭代语义一致。
 * 复刻：ArrayList<连接>（邻近玩家典型 1~8，取 5）、send 消费包引用。
 * main 自检：send 调用序列一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BroadcastLoopBench {

    /** ServerPlayer.connection 语义复刻（send 不堆积：覆盖式存储 + 计数，避免基准自身列表无限增长）。 */
    static final class Connection {
        Object last;
        int sends;
        void send(Object packet) { this.last = packet; this.sends++; }
    }

    static final class Player {
        final Connection connection = new Connection();
    }

    private final List<Player> players = new ArrayList<>();
    private final Object packet = new Object();

    public BroadcastLoopBench() {
        for (int i = 0; i < 5; i++) {
            this.players.add(new Player());
        }
    }

    @Benchmark
    public int before_forEachLambda(Blackhole bh) {
        Object packet = this.packet;
        this.players.forEach(player -> player.connection.send(packet));
        int total = 0;
        for (Player p : this.players) {
            total += p.connection.sends;
            bh.consume(p.connection.last);
        }
        return total;
    }

    @Benchmark
    public int after_indexedLoop(Blackhole bh) {
        Object packet = this.packet;
        List<Player> players = this.players;
        for (int i = 0, size = players.size(); i < size; i++) {
            players.get(i).connection.send(packet);
        }
        int total = 0;
        for (Player p : players) {
            total += p.connection.sends;
            bh.consume(p.connection.last);
        }
        return total;
    }

    /** 等价性自检：发送计数与最后包一致。 */
    public static void main(String[] args) {
        BroadcastLoopBench benchA = new BroadcastLoopBench();
        BroadcastLoopBench benchB = new BroadcastLoopBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        int totalA = benchA.before_forEachLambda(bh);
        int totalB = benchB.after_indexedLoop(bh);
        if (totalA != 5 || totalB != 5) { System.out.println("MISMATCH count"); System.exit(1); }
        for (int i = 0; i < 5; i++) {
            if (benchA.players.get(i).connection.last != benchA.packet
                || benchB.players.get(i).connection.last != benchB.packet) {
                System.out.println("MISMATCH send @" + i); System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
