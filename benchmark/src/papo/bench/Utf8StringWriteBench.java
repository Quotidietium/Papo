package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
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
 * 0078: Utf8String.write 免临时 ByteBuf。
 * before: utf8MaxBytes 预估 -> alloc().buffer() 临时 buf -> writeUtf8 -> VarInt -> writeBytes 拷贝 -> release
 * after:  utf8Bytes 零分配精确长度 -> VarInt -> writeUtf8 直接写目标 buffer
 * 线上字节完全一致（同孤代理项 '?' 替换语义）。
 * 参数: ascii=纯 ASCII（聊天/Identifier 主流），utf8=含 CJK（编码长度 != 字符数）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class Utf8StringWriteBench {

    @Param({"ascii", "utf8"})
    String kind;

    private String string;
    private ByteBuf target;

    @Setup
    public void setup() {
        // 模拟典型网络字符串：Identifier/聊天片段级别长度
        this.string = "ascii".equals(kind)
            ? "minecraft:overworld/some_really_long_identifier_path"
            : "玩家<小明>在服务器里说：你好世界，这是聊天消息！";
        this.target = Unpooled.buffer(256, 256);
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & -128) != 0) {
            buffer.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    @Benchmark
    public void before_tempBuffer(Blackhole bh) {
        target.clear();
        int i = ByteBufUtil.utf8MaxBytes(string);
        ByteBuf byteBuf = target.alloc().buffer(i);
        try {
            int i1 = ByteBufUtil.writeUtf8(byteBuf, string);
            writeVarInt(target, i1);
            target.writeBytes(byteBuf);
        } finally {
            byteBuf.release();
        }
        bh.consume(target);
    }

    @Benchmark
    public void after_direct(Blackhole bh) {
        target.clear();
        int i1 = ByteBufUtil.utf8Bytes(string);
        writeVarInt(target, i1);
        ByteBufUtil.writeUtf8(target, string);
        bh.consume(target);
    }

    /** 等价性自检（非基准）：两种写法的线上字节必须一致。 */
    public static void main(String[] args) {
        Utf8StringWriteBench b = new Utf8StringWriteBench();
        for (String k : new String[]{"ascii", "utf8"}) {
            b.kind = k;
            b.setup();
            ByteBuf a = Unpooled.buffer(), c = Unpooled.buffer();
            int i = ByteBufUtil.utf8MaxBytes(b.string);
            ByteBuf tmp = a.alloc().buffer(i);
            int i1 = ByteBufUtil.writeUtf8(tmp, b.string);
            writeVarInt(a, i1);
            a.writeBytes(tmp);
            tmp.release();
            int i2 = ByteBufUtil.utf8Bytes(b.string);
            writeVarInt(c, i2);
            ByteBufUtil.writeUtf8(c, b.string);
            String sa = a.toString(StandardCharsets.ISO_8859_1), sc = c.toString(StandardCharsets.ISO_8859_1);
            System.out.println(k + " equal=" + sa.equals(sc) + " bytes=" + a.readableBytes());
        }
    }
}
