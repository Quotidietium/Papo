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
 * 批次36: ClientboundLightUpdatePacketData 两个更新列表默认容量（10）按区块截面数
 * （1.21 主世界 24 节）预分配，消除 10→15→22→33 三次扩容拷贝。
 * 复刻：向列表加入 24 个 2048 字节 DataLayer 引用（byte[] 载荷共享，只度量列表增长）。
 * 容量不经 List API 可观察，行为一致。
 * main 自检：两列表内容与迭代结果一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LightUpdatePresizeBench {

    private static final int SECTION_COUNT = 24;
    private final byte[][] layers = new byte[SECTION_COUNT][];

    public LightUpdatePresizeBench() {
        for (int i = 0; i < SECTION_COUNT; i++) {
            this.layers[i] = new byte[2048];
            this.layers[i][0] = (byte) i;
        }
    }

    @Benchmark
    public Object before_defaultCapacity(Blackhole bh) {
        List<byte[]> updates = new ArrayList<>();
        for (int i = 0; i < SECTION_COUNT; i++) {
            updates.add(this.layers[i]);
        }
        bh.consume(updates);
        return updates;
    }

    @Benchmark
    public Object after_presized(Blackhole bh) {
        List<byte[]> updates = new ArrayList<>(SECTION_COUNT);
        for (int i = 0; i < SECTION_COUNT; i++) {
            updates.add(this.layers[i]);
        }
        bh.consume(updates);
        return updates;
    }

    /** 等价性自检。 */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        LightUpdatePresizeBench bench = new LightUpdatePresizeBench();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        List<byte[]> a = (List<byte[]>) bench.before_defaultCapacity(bh);
        List<byte[]> b = (List<byte[]>) bench.after_presized(bh);
        if (a.size() != SECTION_COUNT || b.size() != SECTION_COUNT) { System.out.println("MISMATCH size"); System.exit(1); }
        for (int i = 0; i < SECTION_COUNT; i++) {
            if (a.get(i) != b.get(i) || a.get(i) != bench.layers[i]) {
                System.out.println("MISMATCH @" + i); System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
