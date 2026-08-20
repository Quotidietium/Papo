package papo.bench;

import java.util.List;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 批次61 / 0223：Varint21FrameDecoder 帧提取 readBytes → retainedSlice。
 *
 * 原版 `out.add(in.readBytes(i))` 对**每一入站帧**分配新 buffer 并 memcpy 全部载荷——即全部入站
 * 流量在 splitter 处被完整拷贝一次。Papo 改为 `in.retainedSlice(readerIndex, i) + skipBytes(i)`
 * （netty LengthFieldBasedFrameDecoder.extractFrame 同型）：下游共享同一池化内存（引用计数持有），
 * 零拷贝零分配。下游对帧 buffer 全程只读（CompressionDecoder 仅索引/释放、PacketDecoder codec 只读），
 * 共享不可见；父 cumulation 经引用计数存活至同链最后一个 slice 释放（协议切换窗口 FlowControlHandler
 * 排队 slice 至多钉住一个读批次——与 LTFBD 语义一致）。
 *
 * 复刻：EmbeddedChannel + 双 splitter 复刻（before：readBytes；after：retainedSlice）+ 下游只读
 * 消费者（ByteToMessageDecoder 形态，读全部字节——释放由 BTMD 自身负责），输入 16 帧 × 300B。
 * varint 解析为 0155 内联版（两 splitter 一致，仅帧提取行不同）。
 *
 * main 自检：
 *   1) 全帧到达：两链帧数与内容校验和一致；
 *   2) 半帧累积（cumulation 留存）+ 后续补齐：帧完整到达、两链一致；
 *   3) 万帧引用计数压力无泄漏（finishAndReleaseAll + 关闭）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InboundFrameBench {

    static final int FRAMES = 16;
    static final int FRAME_PAYLOAD = 300;

    abstract static class Splitter extends ByteToMessageDecoder {
        @Override
        protected final void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
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
                    if (i == 0) {
                        throw new io.netty.handler.codec.CorruptedFrameException("Frame length cannot be zero");
                    } else if (in.readableBytes() < i) {
                        in.resetReaderIndex();
                        return;
                    }
                    this.extract(in, out, i);
                    return;
                }
            }
            throw new io.netty.handler.codec.CorruptedFrameException("length wider than 21-bit");
        }

        abstract void extract(ByteBuf in, List<Object> out, int i);
    }

    static final class BeforeSplitter extends Splitter {
        @Override
        void extract(final ByteBuf in, final List<Object> out, final int i) {
            out.add(in.readBytes(i));
        }
    }

    static final class AfterSplitter extends Splitter {
        @Override
        void extract(final ByteBuf in, final List<Object> out, final int i) {
            out.add(in.retainedSlice(in.readerIndex(), i));
            in.skipBytes(i);
        }
    }

    /** 下游只读消费者：读全帧字节算校验和（释放由 ByteToMessageDecoder 负责）。 */
    static final class Reader extends ByteToMessageDecoder {
        long checksum;
        @Override
        protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) {
            while (in.isReadable()) {
                this.checksum = this.checksum * 31 + in.readByte();
            }
            out.add(Boolean.TRUE);
        }
    }

    EmbeddedChannel beforeChannel;
    EmbeddedChannel afterChannel;
    Reader beforeReader;
    Reader afterReader;
    ByteBuf inbound;

    @Setup
    public void setup() {
        this.beforeReader = new Reader();
        this.afterReader = new Reader();
        // 入站处理顺序与构造参数序一致：splitter 先于 reader
        this.beforeChannel = new EmbeddedChannel(new BeforeSplitter(), this.beforeReader);
        this.afterChannel = new EmbeddedChannel(new AfterSplitter(), this.afterReader);
        final byte[] wire = new byte[FRAMES * (2 + FRAME_PAYLOAD)];
        final java.util.Random rnd = new java.util.Random(20260820L);
        int w = 0;
        for (int f = 0; f < FRAMES; f++) {
            wire[w++] = (byte) 0xAC; // 300 的 varint 编码（2 字节）
            wire[w++] = 0x02;
            for (int j = 0; j < FRAME_PAYLOAD; j++) {
                wire[w++] = (byte) rnd.nextInt();
            }
        }
        this.inbound = Unpooled.wrappedBuffer(wire);
    }

    @Benchmark
    public long before_readBytesCopy() {
        this.beforeChannel.writeInbound(this.inbound.retainedDuplicate());
        return drain(this.beforeChannel);
    }

    @Benchmark
    public long after_retainedSlice() {
        this.afterChannel.writeInbound(this.inbound.retainedDuplicate());
        return drain(this.afterChannel);
    }

    private static long drain(final EmbeddedChannel ch) {
        long n = 0;
        while (ch.readInbound() != null) {
            n++;
        }
        return n;
    }

    public static void main(final String[] args) {
        final InboundFrameBench b = new InboundFrameBench();
        b.setup();

        // 1) 全帧到达：帧数与校验和一致
        final long cb = b.before_readBytesCopy();
        final long ca = b.after_retainedSlice();
        if (cb != ca || cb != FRAMES) {
            System.out.println("FAIL frame count before=" + cb + " after=" + ca);
            System.exit(1);
        }
        if (b.beforeReader.checksum != b.afterReader.checksum) {
            System.out.println("FAIL checksum mismatch");
            System.exit(1);
        }

        // 2) 半帧累积 + 补齐：两链一致
        final byte[] frame = new byte[2 + FRAME_PAYLOAD];
        frame[0] = (byte) 0xAC;
        frame[1] = 0x02;
        for (int j = 0; j < FRAME_PAYLOAD; j++) {
            frame[2 + j] = (byte) (j * 7 + 1);
        }
        final Reader r1 = new Reader();
        final Reader r2 = new Reader();
        final EmbeddedChannel half1 = new EmbeddedChannel(new BeforeSplitter(), r1);
        final EmbeddedChannel half2 = new EmbeddedChannel(new AfterSplitter(), r2);
        final ByteBuf whole = Unpooled.wrappedBuffer(frame).retain();
        half1.writeInbound(whole.retainedSlice(0, frame.length / 2));
        half2.writeInbound(whole.retainedSlice(0, frame.length / 2));
        if (!half1.inboundMessages().isEmpty() || !half2.inboundMessages().isEmpty()) {
            System.out.println("FAIL half-frame produced output");
            System.exit(1);
        }
        half1.writeInbound(whole.retainedSlice(frame.length / 2, frame.length - frame.length / 2));
        half2.writeInbound(whole.retainedSlice(frame.length / 2, frame.length - frame.length / 2));
        whole.release();
        if (r1.checksum != r2.checksum || half1.inboundMessages().size() != 1 || half2.inboundMessages().size() != 1) {
            System.out.println("FAIL half-frame completion");
            System.exit(1);
        }
        half1.finishAndReleaseAll();
        half2.finishAndReleaseAll();

        // 3) 万帧引用计数压力
        final Reader sr = new Reader();
        final EmbeddedChannel stress = new EmbeddedChannel(new AfterSplitter(), sr);
        final ByteBuf one = Unpooled.wrappedBuffer(frame).retain();
        for (int i = 0; i < 10_000; i++) {
            stress.writeInbound(one.retainedDuplicate());
        }
        one.release();
        if (stress.inboundMessages().size() != 10_000) {
            System.out.println("FAIL stress count " + stress.outboundMessages().size());
            System.exit(1);
        }
        stress.finishAndReleaseAll();
        stress.close();

        System.out.println("ALL OK");
    }
}
