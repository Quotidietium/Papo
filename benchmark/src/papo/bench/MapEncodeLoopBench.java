package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0100-已撤销: ByteBufCodecs.map encode / FriendlyByteBuf.writeMap 的 forEach 捕获 lambda → entrySet 循环。
 * 本基准是撤销依据：entries=7（区块包高度图规模）实测回退 0.77×——HashMap.forEach 直接扫
 * 内部表无迭代器分配，捕获 lambda 被 JIT 逃逸分析消除，entrySet 循环反而引入 iterator。
 * 保留此类作为"消除 lambda 分配类候选必须先过微基准"的复评依据。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MapEncodeLoopBench {

    @Param({"3", "7"})
    int entries;

    private Map<Integer, long[]> heightmaps;
    private ByteBuf target;

    @Setup
    public void setup() {
        this.heightmaps = new HashMap<>();
        for (int i = 0; i < this.entries; i++) {
            this.heightmaps.put(i, new long[]{0x123456789abcdefL + i, 0xfedcba987654321L - i});
        }
        this.target = Unpooled.buffer(256, 256);
    }

    private static void encodeEntry(ByteBuf buf, int key, long[] value) {
        buf.writeByte(key);
        buf.writeInt(value.length);
        for (long l : value) {
            buf.writeLong(l);
        }
    }

    /** 原实现：forEach + 捕获 lambda。 */
    @Benchmark
    public void before_forEachLambda(Blackhole bh) {
        this.target.clear();
        ByteBuf buf = this.target;
        this.heightmaps.forEach((k, v) -> encodeEntry(buf, k, v));
        bh.consume(this.target);
    }

    /** Papo 0100：entrySet 循环。 */
    @Benchmark
    public void after_entrySetLoop(Blackhole bh) {
        this.target.clear();
        ByteBuf buf = this.target;
        for (Map.Entry<Integer, long[]> entry : this.heightmaps.entrySet()) {
            encodeEntry(buf, entry.getKey(), entry.getValue());
        }
        bh.consume(this.target);
    }

    /** 等价性自检：两种编码输出逐字节一致。 */
    public static void main(String[] args) {
        for (int n : new int[]{0, 1, 3, 7}) {
            MapEncodeLoopBench bench = new MapEncodeLoopBench();
            bench.entries = n;
            bench.setup();
            ByteBuf a = Unpooled.buffer();
            ByteBuf b = Unpooled.buffer();
            bench.heightmaps.forEach((k, v) -> encodeEntry(a, k, v));
            for (Map.Entry<Integer, long[]> entry : bench.heightmaps.entrySet()) {
                encodeEntry(b, entry.getKey(), entry.getValue());
            }
            byte[] ba = new byte[a.readableBytes()];
            a.readBytes(ba);
            byte[] bb = new byte[b.readableBytes()];
            b.readBytes(bb);
            boolean ok = java.util.Arrays.equals(ba, bb);
            System.out.println("entries=" + n + " equal=" + ok + " bytes=" + ba.length);
            if (!ok) {
                System.out.println("MISMATCH");
                System.exit(1);
            }
        }
        System.out.println("ALL OK");
    }
}
