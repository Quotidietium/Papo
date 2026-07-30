package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
 * 0099: ByteBufCodecs.registry() 每次调用分配匿名 StreamCodec → 静态单例（3 个包类）。
 * before: 每次 encode/decode 都 new 一个捕获 registryKey 的匿名 codec
 * after:  静态 final 单例（codec 无状态，注册表每次从 buffer 解析，行为逐字节一致）
 * 基准模型化复刻 codec 的 encode 路径（getId + VarInt write）。
 * 参数: ids=1/8（每次基准编码的注册表项数）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RegistryCodecHoistBench {

    @Param({"1", "8"})
    int ids;

    private ByteBuf target;

    /** 模型化注册表：id 双向映射。 */
    static final class FakeRegistry {
        int getId(Object value) {
            return ((Integer) value) & 0xFF;
        }
    }

    interface Codec {
        void encode(ByteBuf buffer, Object value);
    }

    private final FakeRegistry registry = new FakeRegistry();

    /** 与 ByteBufCodecs.registry() 同构的匿名 codec 工厂：每次调用 new 一个实例。 */
    private Codec createCodecPerCall() {
        final FakeRegistry reg = this.registry;
        return new Codec() {
            @Override
            public void encode(ByteBuf buffer, Object value) {
                int id = reg.getId(value);
                writeVarInt(buffer, id);
            }
        };
    }

    /** Papo 0099：静态单例。 */
    private final Codec singletonCodec = createCodecPerCall();

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    @Setup
    public void setup() {
        this.target = Unpooled.buffer(64, 64);
    }

    @Benchmark
    public void before_allocCodecPerCall(Blackhole bh) {
        this.target.clear();
        for (int i = 0; i < this.ids; i++) {
            createCodecPerCall().encode(this.target, i + 1);
        }
        bh.consume(this.target);
    }

    @Benchmark
    public void after_singletonCodec(Blackhole bh) {
        this.target.clear();
        for (int i = 0; i < this.ids; i++) {
            this.singletonCodec.encode(this.target, i + 1);
        }
        bh.consume(this.target);
    }

    /** 等价性自检：两种方式的编码输出逐字节一致。 */
    public static void main(String[] args) {
        RegistryCodecHoistBench bench = new RegistryCodecHoistBench();
        bench.setup();
        ByteBuf a = Unpooled.buffer();
        ByteBuf b = Unpooled.buffer();
        for (int i = 0; i < 16; i++) {
            bench.createCodecPerCall().encode(a, i * 17 + 1);
            bench.singletonCodec.encode(b, i * 17 + 1);
        }
        byte[] ba = new byte[a.readableBytes()];
        a.readBytes(ba);
        byte[] bb = new byte[b.readableBytes()];
        b.readBytes(bb);
        boolean ok = java.util.Arrays.equals(ba, bb);
        System.out.println("bytes=" + ba.length + " equal=" + ok);
        System.out.println(ok ? "ALL OK" : "MISMATCH");
        if (!ok) {
            System.exit(1);
        }
    }
}
