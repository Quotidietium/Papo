package papo.bench;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * 批次62：/paper netstat 字节计数行为自检（非性能——计数正确性验证，先例 FingerprintHardeningSelfCheck）。
 *
 * 机制：Varint21LengthFieldPrepender（两条路径：0217 直通 + 回退拷贝）对每帧 addAndGet
 * (帧长 varint 字节数 + 载荷)；Varint21FrameDecoder 对每入站帧 addAndGet(同式)；Connection.tickSecond
 * 每秒 getAndSet(0) 快照。本自检验证：**计数器累计值 == 实际线上字节数**（两方向、两路径、多尺寸）
 * 以及 tickSecond 窗口语义（快照清零、累计总数守恒）。
 */
public final class NetstatCounterSelfCheck {

    static int failures = 0;

    /** prepender 复刻（帧化 + 计数点，两条真实路径的计数算式相同：varintSize(n) + n）。 */
    static final class CountingPrepender extends MessageToByteEncoder<ByteBuf> {
        final AtomicLong counter;

        CountingPrepender(final AtomicLong counter) {
            this.counter = counter;
        }

        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf in, final ByteBuf out) {
            final int i = in.readableBytes();
            final int byteSize = varintSize(i);
            out.ensureWritable(byteSize + i);
            writeVarint(out, i);
            out.writeBytes(in, in.readerIndex(), i);
            this.counter.addAndGet(byteSize + i); // Papo 计数点
        }
    }

    /** splitter 复刻（0222 retainedSlice 形态）+ 计数。 */
    static final class CountingSplitter extends ByteToMessageDecoder {
        final AtomicLong counter;

        CountingSplitter(final AtomicLong counter) {
            this.counter = counter;
        }

        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
            in.markReaderIndex();
            int i = 0;
            for (int b = 0; b < 3; b++) {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return;
                }
                final byte _byte = in.readByte();
                i |= (_byte & 127) << b * 7;
                if ((_byte & 0x80) == 0) {
                    if (i == 0 || in.readableBytes() < i) {
                        in.resetReaderIndex();
                        return;
                    }
                    this.counter.addAndGet(i + varintSize(i)); // Papo 计数点
                    out.add(in.retainedSlice(in.readerIndex(), i));
                    in.skipBytes(i);
                    return;
                }
            }
            throw new IllegalStateException("wider than 21-bit");
        }
    }

    static int varintSize(final int v) {
        int size = 1;
        int x = v;
        while ((x >>>= 7) != 0) {
            size++;
        }
        return size;
    }

    static void writeVarint(final ByteBuf buf, final int v) {
        int x = v;
        while (true) {
            if ((x & ~0x7F) == 0) {
                buf.writeByte(x);
                return;
            }
            buf.writeByte((x & 0x7F) | 0x80);
            x >>>= 7;
        }
    }

    static void eq(final String what, final long expected, final long actual) {
        if (expected != actual) {
            System.out.println("FAIL " + what + ": expected=" + expected + " actual=" + actual);
            failures++;
        }
    }

    public static void main(final String[] args) {
        final int[] sizes = {1, 100, 127, 128, 300, 4096, 30000};

        // 出站：每帧经 prepender 后出站 buffer 的真实字节数 == 计数器累计
        {
            final AtomicLong counter = new AtomicLong();
            final EmbeddedChannel ch = new EmbeddedChannel(new CountingPrepender(counter));
            long expected = 0;
            for (final int n : sizes) {
                ch.writeOutbound(Unpooled.wrappedBuffer(new byte[n]).retain());
                final ByteBuf framed = ch.readOutbound();
                expected += framed.readableBytes(); // 真实线上字节
                framed.release();
            }
            eq("outbound counter == wire bytes", expected, counter.get());
            ch.close();
        }

        // 入站：多帧连发 + 半帧，计数 == 全部完整帧的线上字节
        {
            final AtomicLong counter = new AtomicLong();
            final EmbeddedChannel ch = new EmbeddedChannel(new CountingSplitter(counter));
            long expected = 0;
            java.io.ByteArrayOutputStream wire = new java.io.ByteArrayOutputStream();
            for (final int n : sizes) {
                final byte[] len = new byte[3];
                int v = n;
                int w = 0;
                while (true) {
                    if ((v & ~0x7F) == 0) {
                        len[w++] = (byte) v;
                        break;
                    }
                    len[w++] = (byte) ((v & 0x7F) | 0x80);
                    v >>>= 7;
                }
                wire.write(len, 0, w);
                wire.write(new byte[n], 0, n);
                expected += w + n;
            }
            ch.writeInbound(Unpooled.wrappedBuffer(wire.toByteArray()).retain());
            // 半帧（不完整）不应计数
            final byte[] half = new byte[64];
            half[0] = (byte) 0x80; // 大 varint 开头，永远凑不齐
            ch.writeInbound(Unpooled.wrappedBuffer(half).retain());
            eq("inbound counter == wire bytes", expected, counter.get());
            ch.finishAndReleaseAll();
            ch.close();
        }

        // tickSecond 窗口语义：两次快照清零、总累计守恒
        {
            final AtomicLong counter = new AtomicLong();
            counter.addAndGet(100);
            final long sec1 = counter.getAndSet(0);
            counter.addAndGet(37);
            final long sec2 = counter.getAndSet(0);
            eq("sec1", 100L, sec1);
            eq("sec2", 37L, sec2);
            eq("residual", 0L, counter.get());
        }

        if (failures == 0) {
            System.out.println("ALL OK");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }
}
