package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次38 / 0155: Varint21FrameDecoder 消除 helperBuf 抄写 + VarInt.read 二次解析。
 * 语义复刻 ByteBuf 读者索引行为（mark/reset/consume）：before = copyVarint 逐字节抄入
 * 3 字节 helper 数组 + VarInt.read 复刻（含 Papo 0097 单字节剥离）解析后再读负载；
 * after = 读取同时内联累积 varint，完成即读负载。两者负载处理完全一致（分配等长
 * byte[] 并经 Blackhole 逃逸），测量差异仅限长度前缀处理。
 * main 自检：7 类输入矩阵（1/2/3 字节前缀、非最小编码、前缀不全、超宽、零长、负载不足）
 * 比对解析值、消耗字节数、异常类型与消息、重置后读者索引。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class VarintFrameBench {

    /** ByteBuf 语义复刻：字节窗口 + 读者索引 + mark/reset。 */
    static final class Buf {
        final byte[] data;
        int reader;
        int mark;

        Buf(byte[] data) {
            this.data = data;
        }

        boolean isReadable() {
            return this.reader < this.data.length;
        }

        int readableBytes() {
            return this.data.length - this.reader;
        }

        byte readByte() {
            return this.data[this.reader++];
        }

        void markReaderIndex() {
            this.mark = this.reader;
        }

        void resetReaderIndex() {
            this.reader = this.mark;
        }

        byte[] readBytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(this.data, this.reader, out, 0, n);
            this.reader += n;
            return out;
        }
    }

    static boolean hasContinuationBit(byte b) {
        return (b & 128) == 128;
    }

    static final class CorruptedFrame extends RuntimeException {
        CorruptedFrame(String msg) {
            super(msg);
        }
    }

    /** before: helper 缓冲抄写路径（0155 前）。忠实复刻：helperBuf 为每解码器持久
     *  缓冲（clear() 仅复位索引），抄写后经 VarInt.read 复刻二次读取解析。 */
    static final class Before {
        private final byte[] helper = new byte[3];

        byte[] decode(Buf in) {
            in.markReaderIndex();
            // copyVarint（helperBuf.clear() 无实际数据操作，仅复位索引，复刻略）
            boolean complete = false;
            for (int i = 0; i < 3; i++) {
                if (!in.isReadable()) {
                    break;
                }
                byte b = in.readByte();
                this.helper[i] = b;
                if (!hasContinuationBit(b)) {
                    complete = true;
                    break;
                } else if (i == 2) {
                    throw new CorruptedFrame("length wider than 21-bit");
                }
            }
            if (!complete) {
                in.resetReaderIndex();
                return null;
            }
            // VarInt.read(helperBuf)：copyVarint 保证末写入字节无延续位，循环必在其中终止
            int i = varIntRead(this.helper);
            if (i == 0) {
                throw new CorruptedFrame("Frame length cannot be zero");
            } else if (in.readableBytes() < i) {
                in.resetReaderIndex();
                return null;
            } else {
                return in.readBytes(i);
            }
        }
    }

    /** 带界 VarInt.read 复刻（helper 视图只读已写入的前 len 字节，调用方保证其中有终止字节）。 */
    static int varIntRead(byte[] data) {
        int pos = 0;
        final byte first = data[pos++];
        if (first >= 0) {
            return first;
        }
        int i = first & 127;
        int i1 = 1;
        byte b;
        do {
            b = data[pos++];
            i |= (b & 127) << i1++ * 7;
            if (i1 > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while (hasContinuationBit(b));
        return i;
    }

    /** after: 内联解析路径（0155 后）。 */
    static byte[] decodeAfter(Buf in) {
        in.markReaderIndex();
        int i = 0;
        for (int b = 0; b < 3; b++) {
            if (!in.isReadable()) {
                in.resetReaderIndex();
                return null;
            }
            byte _byte = in.readByte();
            i |= (_byte & 127) << b * 7;
            if (!hasContinuationBit(_byte)) {
                if (i == 0) {
                    throw new CorruptedFrame("Frame length cannot be zero");
                } else if (in.readableBytes() < i) {
                    in.resetReaderIndex();
                    return null;
                } else {
                    return in.readBytes(i);
                }
            }
        }
        throw new CorruptedFrame("length wider than 21-bit");
    }

    private Before before;
    private byte[] frame1; // 1 字节前缀 + 96 字节负载
    private byte[] frame3; // 3 字节前缀 + 300 字节负载

    @Setup
    public void setup() {
        this.before = new Before();
        this.frame1 = new byte[97];
        this.frame1[0] = 96;
        for (int i = 1; i < this.frame1.length; i++) {
            this.frame1[i] = (byte) i;
        }
        this.frame3 = new byte[3 + 300];
        this.frame3[0] = (byte) 0xAC; // 44 | cont
        this.frame3[1] = (byte) 0x82; // 2 | cont
        this.frame3[2] = 0x00; // -> 44 | 2<<7 = 300
        for (int i = 3; i < this.frame3.length; i++) {
            this.frame3[i] = (byte) i;
        }
    }

    @Benchmark
    public Object before_helperBuf(Blackhole bh) {
        byte[] payload = this.before.decode(new Buf(this.frame1));
        bh.consume(payload);
        return payload;
    }

    @Benchmark
    public Object after_inline(Blackhole bh) {
        byte[] payload = decodeAfter(new Buf(this.frame1));
        bh.consume(payload);
        return payload;
    }

    @Benchmark
    public Object before_helperBuf3(Blackhole bh) {
        byte[] payload = this.before.decode(new Buf(this.frame3));
        bh.consume(payload);
        return payload;
    }

    @Benchmark
    public Object after_inline3(Blackhole bh) {
        byte[] payload = decodeAfter(new Buf(this.frame3));
        bh.consume(payload);
        return payload;
    }

    /** 等价性自检：输入矩阵全比对。 */
    public static void main(String[] args) {
        byte[][] cases = {
            {5, 1, 2, 3, 4, 5}, // 1 字节前缀，负载 5
            {(byte) 0x85, 0x00, 9, 9, 9, 9, 9}, // 非最小编码 5，负载 5
            {(byte) 0x80, (byte) 0x80, 0x01}, // 3 字节前缀=16384，负载不足 -> reset
            {(byte) 0x80}, // 前缀不全 -> reset
            {(byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01}, // 超宽 -> throw
            {0x00, 1, 2, 3}, // 零长 -> throw
            {0x7F, 1, 2, 3}, // 前缀 127，负载不足 -> reset
            {0x03, 7, 8, 9}, // 1 字节前缀，负载刚好
        };
        for (int c = 0; c < cases.length; c++) {
            String ra = runBefore(cases[c]);
            String rb = runAfter(cases[c]);
            if (!ra.equals(rb)) {
                System.out.println("MISMATCH case " + c + ": before=" + ra + " after=" + rb);
                System.exit(1);
            }
        }
        // 3 字节前缀恰好负载足够
        byte[] exact = new byte[3 + 300];
        exact[0] = (byte) 0xAC;
        exact[1] = (byte) 0x82;
        exact[2] = 0x00;
        String ra = runBefore(exact);
        String rb = runAfter(exact);
        if (!ra.equals(rb)) {
            System.out.println("MISMATCH exact-300: before=" + ra + " after=" + rb);
            System.exit(1);
        }
        System.out.println("ALL OK");
    }

    private static String runBefore(byte[] input) {
        Buf buf = new Buf(input);
        try {
            byte[] out = new Before().decode(buf);
            return "ok len=" + (out == null ? -1 : out.length) + " reader=" + buf.reader;
        } catch (RuntimeException e) {
            return "throw " + e.getClass().getSimpleName() + ":" + e.getMessage() + " reader=" + buf.reader;
        }
    }

    private static String runAfter(byte[] input) {
        Buf buf = new Buf(input);
        try {
            byte[] out = decodeAfter(buf);
            return "ok len=" + (out == null ? -1 : out.length) + " reader=" + buf.reader;
        } catch (RuntimeException e) {
            return "throw " + e.getClass().getSimpleName() + ":" + e.getMessage() + " reader=" + buf.reader;
        }
    }
}
