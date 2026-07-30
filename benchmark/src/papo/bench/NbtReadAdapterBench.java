package papo.bench;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
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
 * 0096: FriendlyByteBuf.readNbt ThreadLocal 轻量 DataInput 适配器（0095 写侧镜像）。
 * before: 每次 readNbt 分配 new ByteBufInputStream(buffer)
 * after:  ThreadLocal 复用 PapoByteBufDataInput（save/restore buffer/endIndex 字段保证重入正确）
 * 基准模拟 NbtIo.readAnyTag 对一个典型携带 NBT 组件物品的 tag（20 条目混合类型）的树形读取。
 * 等价性自检见 main：对真实 Netty 4.2.7 ByteBufInputStream 逐方法（全部 DataInput 原语
 * + EOF/部分读/skipBytes 截断/readLine/mark-reset/异常消息）做行为比对。
 * 参数: entries=5/20（小/典型物品组件 tag 规模）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NbtReadAdapterBench {

    @Param({"5", "20"})
    int entries;

    private byte[] treeBytes;

    /** 与补丁中 FriendlyByteBuf.PapoByteBufDataInput 逐行一致。 */
    static final class PapoByteBufDataInput extends InputStream implements DataInput {
        ByteBuf buffer;
        int endIndex;
        private StringBuilder lineBuf;

        private int papoAvailable() {
            return this.endIndex - this.buffer.readerIndex();
        }

        private void checkAvailable(int fieldSize) throws IOException {
            if (fieldSize < 0) {
                throw new IndexOutOfBoundsException("fieldSize cannot be a negative number");
            }
            if (fieldSize > this.papoAvailable()) {
                throw new java.io.EOFException("fieldSize is too long! Length is " + fieldSize + ", but maximum is " + this.papoAvailable());
            }
        }

        @Override
        public int available() throws IOException {
            return this.papoAvailable();
        }

        @Override
        public int read() throws IOException {
            int available = this.papoAvailable();
            if (available == 0) {
                return -1;
            }
            return this.buffer.readByte() & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int available = this.papoAvailable();
            if (available == 0) {
                return -1;
            }
            len = Math.min(available, len);
            this.buffer.readBytes(b, off, len);
            return len;
        }

        @Override
        public long skip(long n) throws IOException {
            if (n > Integer.MAX_VALUE) {
                return this.skipBytes(Integer.MAX_VALUE);
            }
            return this.skipBytes((int) n);
        }

        @Override
        public int skipBytes(int n) throws IOException {
            n = Math.min(this.papoAvailable(), n);
            this.buffer.skipBytes(n);
            return n;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readlimit) {
            this.buffer.markReaderIndex();
        }

        @Override
        public void reset() throws IOException {
            this.buffer.resetReaderIndex();
        }

        @Override
        public boolean readBoolean() throws IOException {
            this.checkAvailable(1);
            return this.read() != 0;
        }

        @Override
        public byte readByte() throws IOException {
            this.checkAvailable(1);
            return this.buffer.readByte();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return this.readByte() & 255;
        }

        @Override
        public short readShort() throws IOException {
            this.checkAvailable(2);
            return this.buffer.readShort();
        }

        @Override
        public int readUnsignedShort() throws IOException {
            return this.readShort() & 65535;
        }

        @Override
        public char readChar() throws IOException {
            return (char) this.readShort();
        }

        @Override
        public int readInt() throws IOException {
            this.checkAvailable(4);
            return this.buffer.readInt();
        }

        @Override
        public long readLong() throws IOException {
            this.checkAvailable(8);
            return this.buffer.readLong();
        }

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(this.readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(this.readLong());
        }

        @Override
        public void readFully(byte[] b) throws IOException {
            this.readFully(b, 0, b.length);
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            this.checkAvailable(len);
            this.buffer.readBytes(b, off, len);
        }

        @Override
        public String readLine() throws IOException {
            int avail = this.papoAvailable();
            if (avail == 0) {
                return null;
            }
            if (this.lineBuf != null) {
                this.lineBuf.setLength(0);
            }
            loop: while (true) {
                int c = this.buffer.readUnsignedByte();
                avail--;
                switch (c) {
                    case '\n':
                        break loop;
                    case '\r':
                        if (avail > 0 && (char) this.buffer.getUnsignedByte(this.buffer.readerIndex()) == '\n') {
                            this.buffer.skipBytes(1);
                            avail--;
                        }
                        break loop;
                    default:
                        if (this.lineBuf == null) {
                            this.lineBuf = new StringBuilder();
                        }
                        this.lineBuf.append((char) c);
                }
                if (avail <= 0) {
                    break;
                }
            }
            return this.lineBuf != null && this.lineBuf.length() > 0 ? this.lineBuf.toString() : "";
        }

        @Override
        public String readUTF() throws IOException {
            return java.io.DataInputStream.readUTF(this);
        }

        @Override
        public void close() throws IOException {
            // no-op, mirrors ByteBufInputStream with releaseOnClose=false
        }
    }

    private static final ThreadLocal<PapoByteBufDataInput> ADAPTER = ThreadLocal.withInitial(PapoByteBufDataInput::new);

    @Setup
    public void setup() {
        // 构造一棵树形 tag 编码：compound id + entries 组 (typeId + key + payload) + end
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        try {
            out.writeByte(10); // compound
            for (int i = 0; i < entries; i++) {
                switch (i & 3) {
                    case 0 -> {
                        out.writeByte(3); // int
                        writeUtfRaw(out, "Items");
                        out.writeInt(0x12345678 + i);
                    }
                    case 1 -> {
                        out.writeByte(4); // long
                        writeUtfRaw(out, "Items");
                        out.writeLong(0x123456789abcdefL + i);
                    }
                    case 2 -> {
                        out.writeByte(8); // string
                        writeUtfRaw(out, "Items");
                        writeUtfRaw(out, "玩家数据");
                    }
                    default -> {
                        out.writeByte(7); // byte array
                        writeUtfRaw(out, "Items");
                        out.writeInt(8);
                        out.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                    }
                }
            }
            out.writeByte(0); // end
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.treeBytes = bos.toByteArray();
    }

    private static void writeUtfRaw(java.io.DataOutput out, String s) throws IOException {
        // modified-UTF-8 长度前缀 + JDK 编码（仅用于构造测试数据，非被测路径）
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(b).writeUTF(s);
        byte[] raw = b.toByteArray();
        out.write(raw);
    }

    /** 与 NbtIo.readAnyTag 同构的树形读取（id+key+payload 条目序列）。 */
    private static int readTree(DataInput in, Blackhole bh) throws IOException {
        int checksum = in.readByte(); // root id
        while (true) {
            int type = in.readByte();
            if (type == 0) {
                break;
            }
            int keyLen = in.readUnsignedShort();
            byte[] key = new byte[keyLen];
            in.readFully(key);
            checksum += keyLen;
            switch (type) {
                case 3 -> checksum += in.readInt();
                case 4 -> checksum += (int) in.readLong();
                case 8 -> {
                    int vLen = in.readUnsignedShort();
                    byte[] v = new byte[vLen];
                    in.readFully(v);
                    checksum += vLen;
                }
                case 7 -> {
                    int arrLen = in.readInt();
                    byte[] arr = new byte[arrLen];
                    in.readFully(arr);
                    checksum += arrLen;
                }
                default -> throw new IllegalStateException("bad type " + type);
            }
        }
        if (bh != null) {
            bh.consume(checksum);
        }
        return checksum;
    }

    @Benchmark
    public void before_allocWrapper(Blackhole bh) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(this.treeBytes);
        readTree(new ByteBufInputStream(buf), bh);
    }

    @Benchmark
    public void after_threadLocalAdapter(Blackhole bh) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(this.treeBytes);
        PapoByteBufDataInput in = ADAPTER.get();
        ByteBuf previousBuf = in.buffer;
        int previousEnd = in.endIndex;
        in.buffer = buf;
        in.endIndex = buf.readerIndex() + buf.readableBytes();
        try {
            readTree(in, bh);
        } finally {
            in.buffer = previousBuf;
            in.endIndex = previousEnd;
        }
    }

    /** 等价性自检（非基准）：对真实 Netty ByteBufInputStream 逐方法行为比对。 */
    public static void main(String[] args) throws Exception {
        boolean allOk = true;

        // 1) 全原语读取序列比对（含浮点位模式、无符号读取、readFully）
        allOk &= checkBoth("primitives", buf -> {
            buf.writeBoolean(true);
            buf.writeByte(0x80);
            buf.writeShort(0xBEEF);
            buf.writeInt(0xDEADBEEF);
            buf.writeLong(0x123456789ABCDEFL);
            buf.writeFloat(3.14f);
            buf.writeDouble(2.718281828459045);
            buf.writeBytes(new byte[]{9, 8, 7, 6, 5, 4, 3, 2});
        }, in -> {
            StringBuilder sb = new StringBuilder();
            sb.append(in.readBoolean()).append('|');
            sb.append(in.readByte()).append('|');
            sb.append(in.readUnsignedShort()).append('|');
            sb.append(in.readInt()).append('|');
            sb.append(in.readLong()).append('|');
            sb.append(Float.floatToRawIntBits(in.readFloat())).append('|');
            sb.append(Double.doubleToRawLongBits(in.readDouble())).append('|');
            byte[] eight = new byte[8];
            in.readFully(eight);
            sb.append(java.util.Arrays.toString(eight));
            return sb.toString();
        });

        // 2) EOF 语义：read() → -1；read(byte[],off,len) 部分读 → 实际长度，再读 → -1
        allOk &= checkBoth("eof-and-partial", buf -> buf.writeBytes(new byte[]{1, 2, 3}), in -> {
            InputStream s = (InputStream) in;
            byte[] dst = new byte[8];
            int first = s.read(dst, 0, 8); // 部分读，应返回 3
            int second = s.read(dst, 0, 8); // EOF，应返回 -1
            int single = s.read(); // EOF，应返回 -1
            return first + "/" + second + "/" + single + "/" + dst[0] + dst[1] + dst[2];
        });

        // 3) checkAvailable 异常类型与消息逐字符比对
        allOk &= checkThrows("eof-exception", buf -> buf.writeByte(1), in -> {
            in.readInt(); // 仅 1 字节可读，应抛 EOFException
            return "no-throw";
        });
        allOk &= checkThrows("readFully-eof", buf -> buf.writeBytes(new byte[]{1, 2}), in -> {
            in.readFully(new byte[4]);
            return "no-throw";
        });

        // 4) skipBytes 截断语义（不抛 EOF，返回实际跳过数）
        allOk &= checkBoth("skip-truncate", buf -> buf.writeBytes(new byte[]{1, 2, 3}), in -> {
            int skipped = in.skipBytes(10); // 应静默截断为 3
            return skipped + "/" + ((InputStream) in).read();
        });
        allOk &= checkBoth("skip-long", buf -> buf.writeBytes(new byte[]{1, 2, 3}), in -> {
            InputStream s = (InputStream) in;
            long skipped = s.skip(2L);
            return skipped + "/" + s.read();
        });

        // 5) readLine：\n、\r\n、裸 \r 结尾、无换行 EOF（不抛异常）
        allOk &= checkBoth("readline-lf", buf -> buf.writeBytes("hello\nworld".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), in -> in.readLine() + "/" + in.readLine());
        allOk &= checkBoth("readline-crlf", buf -> buf.writeBytes("a\r\nb".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), in -> in.readLine() + "/" + in.readLine());
        allOk &= checkBoth("readline-eof-no-newline", buf -> buf.writeBytes("tail".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)), in -> in.readLine() + "/" + (in.readLine() == null));
        allOk &= checkBoth("readline-empty", buf -> {}, in -> in.readLine() == null ? "null" : "notnull");

        // 6) mark/reset
        allOk &= checkBoth("mark-reset", buf -> buf.writeBytes(new byte[]{5, 6, 7}), in -> {
            InputStream s = (InputStream) in;
            int a = s.read();
            s.mark(0);
            int b = s.read();
            s.reset();
            int c = s.read();
            return a + "/" + b + "/" + c + "/" + s.markSupported();
        });

        // 7) readUTF（经 DataInputStream.readUTF 委托）
        allOk &= checkBoth("readUTF", buf -> {
            byte[] ascii = utf8("minecraft:stone");
            byte[] utf8 = utf8("玩家<小明>");
            byte[] nul = utf8("has nul");
            buf.writeBytes(ascii).writeBytes(utf8).writeBytes(nul);
        }, in -> in.readUTF() + "/" + in.readUTF() + "/" + in.readUTF());

        // 8) 树形读取整体比对
        NbtReadAdapterBench bench = new NbtReadAdapterBench();
        for (int n : new int[]{0, 1, 5, 20}) {
            bench.entries = n;
            bench.setup();
            ByteBuf a = Unpooled.wrappedBuffer(bench.treeBytes);
            int ra = readTree(new ByteBufInputStream(a), null);
            PapoByteBufDataInput in = ADAPTER.get();
            ByteBuf b = Unpooled.wrappedBuffer(bench.treeBytes);
            in.buffer = b;
            in.endIndex = b.readerIndex() + b.readableBytes();
            int rb = readTree(in, null);
            boolean ok = ra == rb;
            System.out.println("tree entries=" + n + " equal=" + ok + " checksum=" + ra);
            allOk &= ok;
        }

        System.out.println(allOk ? "ALL OK" : "MISMATCH");
        if (!allOk) {
            System.exit(1);
        }
    }

    private static byte[] utf8(String s) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(b).writeUTF(s);
        return b.toByteArray();
    }

    private interface BufWriter {
        void write(ByteBuf buf) throws IOException;
    }

    /** 读取序列。参数为 DataInput；需要 InputStream 独有方法（read()/read(byte[],off,len)/skip(long)/mark/reset）的 lambda 内部转型。 */
    private interface Reader {
        String run(DataInput in) throws IOException;
    }

    /** 同一输入下分别用 Netty 实现与 Papo 适配器读取，比对返回串与剩余字节。 */
    private static boolean checkBoth(String name, BufWriter writer, Reader reader) throws IOException {
        ByteBuf a = Unpooled.buffer();
        writer.write(a);
        ByteBufInputStream netty = new ByteBufInputStream(a);
        String ra;
        try {
            ra = reader.run(netty) + "|rem=" + netty.available();
        } catch (Exception e) {
            ra = e.getClass().getName() + ": " + e.getMessage();
        }

        ByteBuf b = Unpooled.buffer();
        writer.write(b);
        PapoByteBufDataInput papo = ADAPTER.get();
        papo.buffer = b;
        papo.endIndex = b.readerIndex() + b.readableBytes();
        String rb;
        try {
            rb = reader.run(papo) + "|rem=" + papo.available();
        } catch (Exception e) {
            rb = e.getClass().getName() + ": " + e.getMessage();
        } finally {
            papo.buffer = null;
        }
        boolean ok = ra.equals(rb);
        System.out.println(name + " equal=" + ok + (ok ? "" : " netty=[" + ra + "] papo=[" + rb + "]"));
        return ok;
    }

    /** 异常路径比对：异常类型 + 消息必须一致。 */
    private static boolean checkThrows(String name, BufWriter writer, Reader reader) throws IOException {
        return checkBoth(name, writer, reader);
    }
}
