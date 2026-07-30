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
 * 0097: VarInt.read 单字节快速路径（包 id/集合长度/枚举序数绝大多数 < 128）。
 * before: 通用 do-while（移位+或+continuation 检查）
 * after:  首字节剥离，b >= 0 直接返回；否则以 i=b&127,i1=1 进入同一循环
 * 等价性自检见 main：0..300、2^14、2^21、Integer.MAX_VALUE、负数、5 字节上限、
 * 6 字节异常路径的解码值与消费字节数逐一比对。
 * 参数: size=1/3（varint 编码长度，1 字节命中快速路径）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class VarIntReadBench {

    @Param({"1", "3"})
    int size;

    private byte[] encoded;

    /** 原实现（与补丁前逐行一致）。 */
    static int readOld(ByteBuf buffer) {
        int i = 0;
        int i1 = 0;

        byte _byte;
        do {
            _byte = buffer.readByte();
            i |= (_byte & 127) << i1++ * 7;
            if (i1 > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((_byte & 128) == 128);

        return i;
    }

    /** Papo 0097（与补丁逐行一致）。 */
    static int readNew(ByteBuf buffer) {
        final byte first = buffer.readByte();
        if (first >= 0) {
            return first;
        }
        int i = first & 127;
        int i1 = 1;

        byte _byte;
        do {
            _byte = buffer.readByte();
            i |= (_byte & 127) << i1++ * 7;
            if (i1 > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((_byte & 128) == 128);

        return i;
    }

    @Setup
    public void setup() {
        int value = this.size == 1 ? 63 : 1000000; // 1 字节 vs 3 字节 varint
        ByteBuf buf = Unpooled.buffer(8);
        writeVarInt(buf, value);
        this.encoded = new byte[buf.readableBytes()];
        buf.readBytes(this.encoded);
        buf.release();
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    @Benchmark
    public void before_loop(Blackhole bh) {
        ByteBuf buf = Unpooled.wrappedBuffer(this.encoded);
        bh.consume(readOld(buf));
    }

    @Benchmark
    public void after_peeled(Blackhole bh) {
        ByteBuf buf = Unpooled.wrappedBuffer(this.encoded);
        bh.consume(readNew(buf));
    }

    /** 等价性自检：解码值 + 消费字节数 + 异常路径逐一比对。 */
    public static void main(String[] args) {
        boolean allOk = true;
        int[] values = new int[304];
        for (int i = 0; i <= 300; i++) {
            values[i] = i;
        }
        values[301] = 1 << 14;
        values[302] = 1 << 21;
        values[303] = Integer.MAX_VALUE;
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer(8);
            writeVarInt(buf, v);
            byte[] enc = new byte[buf.readableBytes()];
            buf.readBytes(enc);
            buf.release();
            int oldV = readOld(Unpooled.wrappedBuffer(enc));
            int newV = readNew(Unpooled.wrappedBuffer(enc));
            boolean ok = oldV == newV && oldV == v;
            if (!ok) {
                System.out.println("value " + v + " old=" + oldV + " new=" + newV);
            }
            allOk &= ok;
        }
        // 负数（5 字节 varint）
        for (int v : new int[]{-1, Integer.MIN_VALUE, -123456789}) {
            ByteBuf buf = Unpooled.buffer(8);
            writeVarInt(buf, v);
            byte[] enc = new byte[buf.readableBytes()];
            buf.readBytes(enc);
            buf.release();
            int oldV = readOld(Unpooled.wrappedBuffer(enc));
            int newV = readNew(Unpooled.wrappedBuffer(enc));
            boolean ok = oldV == newV && oldV == v;
            if (!ok) {
                System.out.println("neg value " + v + " old=" + oldV + " new=" + newV);
            }
            allOk &= ok;
        }
        // 异常路径：6 字节带 continuation，两实现必须同样抛 RuntimeException
        byte[] tooBig = new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
        allOk &= throwsSame(tooBig);
        // 消费字节数比对：1 字节 varint 后跟数据，read 后剩余字节必须一致
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{0x05, 0x55});
        readOld(buf);
        int remOld = buf.readableBytes();
        ByteBuf buf2 = Unpooled.wrappedBuffer(new byte[]{0x05, 0x55});
        readNew(buf2);
        int remNew = buf2.readableBytes();
        allOk &= remOld == remNew && remOld == 1;
        System.out.println(allOk ? "ALL OK" : "MISMATCH");
        if (!allOk) {
            System.exit(1);
        }
    }

    private static boolean throwsSame(byte[] input) {
        String oldResult;
        try {
            readOld(Unpooled.wrappedBuffer(input));
            oldResult = "no-throw";
        } catch (RuntimeException e) {
            oldResult = e.getClass().getName() + ": " + e.getMessage();
        }
        String newResult;
        try {
            readNew(Unpooled.wrappedBuffer(input));
            newResult = "no-throw";
        } catch (RuntimeException e) {
            newResult = e.getClass().getName() + ": " + e.getMessage();
        }
        boolean ok = oldResult.equals(newResult);
        if (!ok) {
            System.out.println("tooBig old=[" + oldResult + "] new=[" + newResult + "]");
        }
        return ok;
    }
}
