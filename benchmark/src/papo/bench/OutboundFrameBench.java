package papo.bench;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.AttributeKey;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次59 / 0217：零拷贝出站帧化（headroom 前缀 + 身份标记直通）。
 *
 * 原版出站三阶段每包全量拷贝：
 *   PacketEncoder（256B 起步增长，0214 已预分配）→ CompressionEncoder（低于阈值整包拷入新 direct buffer）
 *   → Varint21LengthFieldPrepender（再整包拷入帧 buffer + 池化分配）。
 * Papo 0217：PacketEncoder 预留 6 字节 headroom 并经 channel attr 发布身份 → 低于阈值路径原地写
 * 数据长度 varint（1 字节 0）直通 → prepender 向 headroom 回填帧长 varint 后直通；压缩路径输出自带
 * headroom。三阶段全部在 channel 单 event loop 的同一次 write 遍历内，attr 无并发；身份不匹配
 * （插件注入的外来 buffer / 陈旧标记）全量回退旧拷贝路径。
 *
 * 复刻：EmbeddedChannel + 真实 netty MessageToByteEncoder/write 语义（含引用计数），
 * 压缩后端用 JavaVelocityCompressor.deflate 的 ByteBuffer 语义复刻（本机生产路径即 JDK 回退）。
 *   - before：原版三段（增长分配 + 阈值下拷贝 + 帧拷贝）。
 *   - after：headroom 分配 + 直通（阈值下 0 拷贝 0 额外分配；阈值上仅压缩必需的 1 次输出写）。
 *
 * main 自检：
 *   1) 尺寸×{可压/随机}矩阵下 before/after 出站字节逐字节一致（含阈值边界 255/256/257）；
 *   2) 帧结构可解析（frameLen varint + dataLen varint 正确）；
 *   3) writeVarIntBackwards 与前向 VarInt.write 对 0..2^22 全档位往返一致；
 *   4) 引用计数：多次写后无泄漏（读出即释放，EmbeddedChannel 队列清空）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class OutboundFrameBench {

    static final AttributeKey<ByteBuf> HEADROOM_BUF = AttributeKey.valueOf("bench.headroomBuf");
    static final int HEADROOM = 6;
    static final int THRESHOLD = 256;

    /** 模型化 Packet 对象（载荷字节）。 */
    static final class Payload {
        final byte[] data;
        Payload(final byte[] data) { this.data = data; }
    }

    /** JavaVelocityCompressor.deflate 语义复刻：nioBuffer 视图 + ensureWritable(8192) 续压 + 推进输入 readerIndex + 复位。 */
    static void stubDeflate(final Deflater deflater, final ByteBuf in, final ByteBuf out) {
        deflater.setInput(in.nioBuffer());
        deflater.finish();
        final int readerIndex = in.readerIndex();
        while (!deflater.finished()) {
            if (!out.isWritable()) {
                out.ensureWritable(8192);
            }
            final int n = deflater.deflate(out.nioBuffer(out.writerIndex(), out.writableBytes()));
            out.writerIndex(out.writerIndex() + n);
        }
        in.readerIndex(readerIndex + deflater.getTotalIn());
        deflater.reset();
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

    static void writeVarintBackwards(final ByteBuf buf, final int v) {
        final int size = varintSize(v);
        final int end = buf.readerIndex();
        int index = end - size;
        int shift = 0;
        final int last = size * 7;
        while (shift < last) {
            final int b = v >>> shift & 0x7F;
            shift += 7;
            buf.setByte(index++, shift < last ? b | 0x80 : b);
        }
        buf.readerIndex(end - size);
    }

    // ===== before 链 =====

    static final class BeforeEncoder extends MessageToByteEncoder<Payload> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final Payload msg, final ByteBuf out) {
            out.writeBytes(msg.data);
        }
    }

    static final class BeforeCompress extends MessageToByteEncoder<ByteBuf> {
        private final Deflater deflater = new Deflater();
        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf in, final ByteBuf out) {
            final int i = in.readableBytes();
            if (i < THRESHOLD) {
                writeVarint(out, 0);
                out.writeBytes(in);
            } else {
                writeVarint(out, i);
                stubDeflate(this.deflater, in, out);
            }
        }
    }

    static final class BeforePrepender extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf in, final ByteBuf out) {
            final int i = in.readableBytes();
            final int byteSize = varintSize(i);
            out.ensureWritable(byteSize + i);
            writeVarint(out, i);
            out.writeBytes(in, in.readerIndex(), i);
        }
    }

    // ===== after 链（0217 复刻） =====

    static final class AfterEncoder extends MessageToByteEncoder<Payload> {
        @Override
        protected ByteBuf allocateBuffer(final ChannelHandlerContext ctx, final Payload msg, final boolean preferDirect) throws Exception {
            final ByteBuf buf = ctx.alloc().ioBuffer(msg.data.length + HEADROOM);
            buf.setIndex(HEADROOM, HEADROOM);
            return buf;
        }

        @Override
        protected void encode(final ChannelHandlerContext ctx, final Payload msg, final ByteBuf out) {
            out.writeBytes(msg.data);
            ctx.channel().attr(HEADROOM_BUF).set(out);
        }
    }

    static final class AfterCompress extends MessageToByteEncoder<ByteBuf> {
        private final Deflater deflater = new Deflater();
        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf buf && buf == ctx.channel().attr(HEADROOM_BUF).get()) {
                ctx.channel().attr(HEADROOM_BUF).set(null);
                final int i = buf.readableBytes();
                if (i < THRESHOLD) {
                    buf.setByte(HEADROOM - 1, 0);
                    buf.readerIndex(HEADROOM - 1);
                    ctx.channel().attr(HEADROOM_BUF).set(buf);
                    ctx.write(buf, promise);
                    return;
                }
                final ByteBuf out = ctx.alloc().directBuffer(HEADROOM + i + (i >>> 11) + 32);
                out.setIndex(HEADROOM, HEADROOM);
                writeVarint(out, i);
                try {
                    stubDeflate(this.deflater, buf, out);
                } catch (final Exception e) {
                    out.release();
                    throw e;
                }
                buf.release();
                ctx.channel().attr(HEADROOM_BUF).set(out);
                ctx.write(out, promise);
                return;
            }
            ctx.channel().attr(HEADROOM_BUF).set(null);
            super.write(ctx, msg, promise);
        }

        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf in, final ByteBuf out) {
            final int i = in.readableBytes();
            if (i < THRESHOLD) {
                writeVarint(out, 0);
                out.writeBytes(in);
            } else {
                writeVarint(out, i);
                stubDeflate(this.deflater, in, out);
            }
        }
    }

    static final class AfterPrepender extends MessageToByteEncoder<ByteBuf> {
        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf buf && buf == ctx.channel().attr(HEADROOM_BUF).get()) {
                ctx.channel().attr(HEADROOM_BUF).set(null);
                final int i = buf.readableBytes();
                writeVarintBackwards(buf, i);
                ctx.write(buf, promise);
                return;
            }
            ctx.channel().attr(HEADROOM_BUF).set(null);
            super.write(ctx, msg, promise);
        }

        @Override
        protected void encode(final ChannelHandlerContext ctx, final ByteBuf in, final ByteBuf out) {
            final int i = in.readableBytes();
            final int byteSize = varintSize(i);
            out.ensureWritable(byteSize + i);
            writeVarint(out, i);
            out.writeBytes(in, in.readerIndex(), i);
        }
    }

    // ===== JMH =====

    EmbeddedChannel beforeChannel;
    EmbeddedChannel afterChannel;
    Payload small;  // 100B：低于阈值（绝大多数包：实体移动/音效等）
    Payload large;  // 16KB 随机：高于阈值（不可压，走压缩路径）

    @Setup
    public void setup() {
        this.beforeChannel = new EmbeddedChannel(new BeforePrepender(), new BeforeCompress(), new BeforeEncoder());
        this.afterChannel = new EmbeddedChannel(new AfterPrepender(), new AfterCompress(), new AfterEncoder());
        this.small = new Payload(new byte[100]);
        final byte[] big = new byte[16 * 1024];
        new java.util.Random(42L).nextBytes(big);
        this.large = new Payload(big);
    }

    private static ByteBuf drain(final EmbeddedChannel ch) {
        final ByteBuf out = ch.readOutbound();
        out.release();
        return out;
    }

    @Benchmark
    public ByteBuf before_belowThreshold() {
        this.beforeChannel.writeOutbound(new Payload(this.small.data));
        return drain(this.beforeChannel);
    }

    @Benchmark
    public ByteBuf after_belowThreshold() {
        this.afterChannel.writeOutbound(new Payload(this.small.data));
        return drain(this.afterChannel);
    }

    @Benchmark
    public ByteBuf before_aboveThreshold() {
        this.beforeChannel.writeOutbound(new Payload(this.large.data));
        return drain(this.beforeChannel);
    }

    @Benchmark
    public ByteBuf after_aboveThreshold() {
        this.afterChannel.writeOutbound(new Payload(this.large.data));
        return drain(this.afterChannel);
    }

    // ===== 自检 =====

    public static void main(final String[] args) {
        // 1) varint 反向写与前向写逐值等价（0..2^22 全档位 + 边界）
        for (int v = 0; v <= 1 << 22; v = v == 0 ? 1 : v * 2) {
            for (final int probe : new int[]{v, v + 1, Math.max(0, v - 1)}) {
                final ByteBuf fwd = io.netty.buffer.Unpooled.buffer(5);
                writeVarint(fwd, probe);
                final ByteBuf back = io.netty.buffer.Unpooled.buffer(HEADROOM + 8);
                back.setIndex(HEADROOM, HEADROOM);
                back.writeByte(0x42); // 模拟载荷首字节
                writeVarintBackwards(back, probe);
                if (fwd.readableBytes() + 1 != back.readableBytes()) {
                    System.out.println("MISMATCH varint len v=" + probe);
                    System.exit(1);
                }
                for (int k = 0; k < fwd.readableBytes(); k++) {
                    if (fwd.getByte(k) != back.getByte(back.readerIndex() + k)) {
                        System.out.println("MISMATCH varint byte v=" + probe);
                        System.exit(1);
                    }
                }
                if (back.getByte(back.writerIndex() - 1) != 0x42) {
                    System.out.println("MISMATCH varint payload v=" + probe);
                    System.exit(1);
                }
                fwd.release();
                back.release();
            }
        }

        // 2) 尺寸 × 可压性矩阵：before/after 出站字节逐字节一致 + 帧结构正确
        final java.util.Random rnd = new java.util.Random(7L);
        final int[] sizes = {1, 100, 255, 256, 257, 1024, 4096, 16 * 1024, 100 * 1024};
        for (final int n : sizes) {
            for (final boolean zeros : new boolean[]{false, true}) {
                final byte[] data = new byte[n];
                if (!zeros) {
                    rnd.nextBytes(data);
                }
                final EmbeddedChannel before = new EmbeddedChannel(new BeforePrepender(), new BeforeCompress(), new BeforeEncoder());
                final EmbeddedChannel after = new EmbeddedChannel(new AfterPrepender(), new AfterCompress(), new AfterEncoder());
                before.writeOutbound(new Payload(data));
                after.writeOutbound(new Payload(data));
                final ByteBuf b = before.readOutbound();
                final ByteBuf a = after.readOutbound();
                if (b.readableBytes() != a.readableBytes()) {
                    System.out.println("MISMATCH len n=" + n + " zeros=" + zeros + ": " + b.readableBytes() + " vs " + a.readableBytes());
                    System.exit(1);
                }
                for (int k = 0; k < b.readableBytes(); k++) {
                    if (b.getByte(b.readerIndex() + k) != a.getByte(a.readerIndex() + k)) {
                        System.out.println("MISMATCH byte@" + k + " n=" + n + " zeros=" + zeros);
                        System.exit(1);
                    }
                }
                // 帧结构：frameLen varint + dataLen varint
                int idx = 0;
                int frameLen = 0;
                int shift = 0;
                int bb;
                do {
                    bb = b.getByte(idx++);
                    frameLen |= (bb & 0x7F) << shift;
                    shift += 7;
                } while ((bb & 0x80) != 0);
                if (frameLen != b.readableBytes() - idx) {
                    System.out.println("BAD frameLen n=" + n + " zeros=" + zeros + ": " + frameLen + " vs " + (b.readableBytes() - idx));
                    System.exit(1);
                }
                int dataLen = 0;
                shift = 0;
                do {
                    bb = b.getByte(idx++);
                    dataLen |= (bb & 0x7F) << shift;
                    shift += 7;
                } while ((bb & 0x80) != 0);
                if (dataLen != 0 && dataLen != n) {
                    System.out.println("BAD dataLen n=" + n + " zeros=" + zeros + ": " + dataLen);
                    System.exit(1);
                }
                b.release();
                a.release();
                before.close();
                after.close();
            }
        }

        // 3) 引用计数压力：反复写读释放，EmbeddedChannel 关闭无泄漏异常
        final EmbeddedChannel stress = new EmbeddedChannel(new AfterPrepender(), new AfterCompress(), new AfterEncoder());
        for (int i = 0; i < 10_000; i++) {
            stress.writeOutbound(new Payload(new byte[Math.min(i % 1024, 300)]));
            final ByteBuf o = stress.readOutbound();
            if (o == null || o.refCnt() != 1) {
                System.out.println("BAD refcnt iter=" + i);
                System.exit(1);
            }
            o.release();
        }
        stress.checkException();
        stress.close();

        System.out.println("ALL OK");
    }
}
