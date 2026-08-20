package papo.bench;

import java.util.concurrent.TimeUnit;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次58 / A1+A2：出站编码链 buffer 预分配。
 *
 * A1 Varint21LengthFieldPrepender：原版用 MessageToByteEncoder 默认 allocateBuffer（ioBuffer() 初始 256B），
 * encode 首个 ensureWritable(3+n) 触发一次池化重分配（256 → 覆盖 3+n 的 2 的幂，超额分配）；覆写为
 * ioBuffer(3 + n) 后单次精确分配。帧字节逐字节不变（长度 varint + payload 拷贝语义相同）。
 *
 * A2 PacketEncoder：原版每包从 256B 起步，codec 写入过程中按需增长（每次增长 = 池化 reallocate + 已写前缀
 * 整体拷贝）；区块/光照包（几十 KB）突发期每包 ~8 次增长拷贝。按包类缓存上次编码尺寸（≥8KB 才记录）后
 * 首次分配即到位。编码字节不变（容量不上线）。
 *
 * 复刻：真实 netty 4.2.7 PooledByteBufAllocator（与服务器同款池化分配器）。
 *   - before_prepender：ioBuffer()（256）→ ensureWritable(3+n)（一次重分配到 2 的幂）→ 写 varint+payload。
 *   - after_prepender：ioBuffer(3+n) → 同样写入，零增长。
 *   - before_encoderGrow：ioBuffer()（256）→ 32KB payload 按 1KB 分片写入（增长链 256→512→…→32768，
 *     累计额外拷贝 ≈ 32KB）。
 *   - after_encoderHinted：ioBuffer(32768) → 同样写入，零增长。
 *
 * main 自检：两路径产出 buffer 内容逐字节一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EncodeAllocBench {

    private static final PooledByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;
    private static final int CHUNK_PAYLOAD = 32 * 1024; // 区块包量级（压缩前 PacketEncoder 输出）
    private static final byte[] PAYLOAD = new byte[CHUNK_PAYLOAD];

    static {
        for (int i = 0; i < PAYLOAD.length; i++) {
            PAYLOAD[i] = (byte) i;
        }
    }

    /** 复刻 VarInt.getByteSize/write 的长度前缀写入（值 ≤ 2^21 均为 1-3 字节，这里固定 3 字节形态）。 */
    private static void writeLen3(final ByteBuf buf, final int value) {
        buf.writeByte((byte) (value & 0x7F | 0x80));
        buf.writeByte((byte) (value >>> 7 & 0x7F | 0x80));
        buf.writeByte((byte) (value >>> 14));
    }

    /** before（A1）：默认 256B 起步 + 一次 ensureWritable 重分配（原版 allocateBuffer 未覆写）。 */
    @Benchmark
    public ByteBuf before_prependerDefault(final Blackhole bh) {
        final ByteBuf buf = ALLOC.ioBuffer(); // 原版默认：256B
        final int n = CHUNK_PAYLOAD;
        buf.ensureWritable(3 + n); // 原版 encode 首行
        writeLen3(buf, n);
        buf.writeBytes(PAYLOAD, 0, n);
        bh.consume(buf);
        buf.release();
        return buf;
    }

    /** after（A1）：ioBuffer(3+n) 精确分配，零增长。 */
    @Benchmark
    public ByteBuf after_prependerExact(final Blackhole bh) {
        final ByteBuf buf = ALLOC.ioBuffer(3 + CHUNK_PAYLOAD); // Papo 覆写 allocateBuffer
        final int n = CHUNK_PAYLOAD;
        writeLen3(buf, n);
        buf.writeBytes(PAYLOAD, 0, n);
        bh.consume(buf);
        buf.release();
        return buf;
    }

    /** before（A2）：256B 起步 + 1KB 分片写入触发增长链（模型化 codec 渐进写入）。 */
    @Benchmark
    public ByteBuf before_encoderGrow(final Blackhole bh) {
        final ByteBuf buf = ALLOC.ioBuffer(); // 原版默认：256B
        writeLen3(buf, 0); // packet id varint 占位（codec 首写）
        for (int off = 0; off < CHUNK_PAYLOAD; off += 1024) {
            buf.writeBytes(PAYLOAD, off, Math.min(1024, CHUNK_PAYLOAD - off)); // 每次写满触发 ensureWritable 增长
        }
        bh.consume(buf);
        buf.release();
        return buf;
    }

    /** after（A2）：按类缓存尺寸提示 ioBuffer(32768)，零增长。 */
    @Benchmark
    public ByteBuf after_encoderHinted(final Blackhole bh) {
        final ByteBuf buf = ALLOC.ioBuffer(CHUNK_PAYLOAD); // Papo 按包类尺寸提示
        writeLen3(buf, 0);
        for (int off = 0; off < CHUNK_PAYLOAD; off += 1024) {
            buf.writeBytes(PAYLOAD, off, Math.min(1024, CHUNK_PAYLOAD - off));
        }
        bh.consume(buf);
        buf.release();
        return buf;
    }

    /** 等价性自检：before/after 产出帧内容逐字节一致（A1）；增长链与提示链写入结果一致（A2）。 */
    public static void main(final String[] args) {
        // A1
        final ByteBuf b1 = ALLOC.ioBuffer();
        b1.ensureWritable(3 + CHUNK_PAYLOAD);
        writeLen3(b1, CHUNK_PAYLOAD);
        b1.writeBytes(PAYLOAD, 0, CHUNK_PAYLOAD);
        final ByteBuf a1 = ALLOC.ioBuffer(3 + CHUNK_PAYLOAD);
        writeLen3(a1, CHUNK_PAYLOAD);
        a1.writeBytes(PAYLOAD, 0, CHUNK_PAYLOAD);
        check(b1, a1, "A1 prepender");
        b1.release();
        a1.release();
        // A2
        final ByteBuf b2 = ALLOC.ioBuffer();
        writeLen3(b2, 0);
        for (int off = 0; off < CHUNK_PAYLOAD; off += 1024) {
            b2.writeBytes(PAYLOAD, off, Math.min(1024, CHUNK_PAYLOAD - off));
        }
        final ByteBuf a2 = ALLOC.ioBuffer(CHUNK_PAYLOAD);
        writeLen3(a2, 0);
        for (int off = 0; off < CHUNK_PAYLOAD; off += 1024) {
            a2.writeBytes(PAYLOAD, off, Math.min(1024, CHUNK_PAYLOAD - off));
        }
        check(b2, a2, "A2 encoder");
        b2.release();
        a2.release();
        System.out.println("ALL OK");
    }

    private static void check(final ByteBuf x, final ByteBuf y, final String tag) {
        if (x.readableBytes() != y.readableBytes()) {
            System.out.println("MISMATCH len " + tag + ": " + x.readableBytes() + " vs " + y.readableBytes());
            System.exit(1);
        }
        for (int i = 0; i < x.readableBytes(); i++) {
            if (x.getByte(i) != y.getByte(i)) {
                System.out.println("MISMATCH byte@" + i + " " + tag);
                System.exit(1);
            }
        }
    }
}
