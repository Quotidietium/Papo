package papo.bench;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
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
 * 0067: NBT 字符串读取。
 * before: JDK DataInputStream.readUTF（内部 per-call byte[]/char[] scratch + modified-UTF-8 解码）。
 * after:  CompoundTag.papoReadUtf —— readUnsignedShort + readFully + ASCII 扫描，
 *         全 ASCII 时 new String(ISO_8859_1) 一次解码；含非 ASCII 时回退 JDK readUTF。
 * 数据: 典型 NBT 字符串（资源名、JSON 片段、玩家名），以及非 ASCII（中文）对照组。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NbtStringReadBench {

    @Param({"ascii", "nonascii"})
    String kind;

    /** 每轮读取的字符串个数。 */
    @Param({"128"})
    int count;

    private byte[][] payloads; // 每个元素 = 2字节长度前缀 + UTF 字节

    @Setup
    public void setup() throws IOException {
        String[] samples;
        if ("ascii".equals(kind)) {
            samples = new String[]{
                "minecraft:stone", "minecraft:diamond_sword", "Hoglins repel warped fungus",
                "{\"text\":\"hello world\",\"color\":\"red\"}", "Zurker_123",
                "minecraft:the_nether", "textures/entity/player/wide/steve.png",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            };
        } else {
            samples = new String[]{
                "我的世界服务器", "玩家【墨白】进入了游戏", "こんにちは世界",
                "âme soeur déjà vu", "欢迎来到帕波服务器——稳定优先"
            };
        }
        payloads = new byte[count][];
        for (int i = 0; i < count; i++) {
            byte[] utf = samples[i % samples.length].getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[2 + utf.length];
            p[0] = (byte) (utf.length >>> 8);
            p[1] = (byte) utf.length;
            System.arraycopy(utf, 0, p, 2, utf.length);
            payloads[i] = p;
        }
    }

    @Benchmark
    public void before_readUTF(Blackhole bh) throws IOException {
        for (byte[] p : payloads) {
            bh.consume(new DataInputStream(new ByteArrayInputStream(p)).readUTF());
        }
    }

    @Benchmark
    public void after_papoReadUtf(Blackhole bh) throws IOException {
        for (byte[] p : payloads) {
            bh.consume(papoReadUtf(new DataInputStream(new ByteArrayInputStream(p))));
        }
    }

    /** 与 net.minecraft.nbt.CompoundTag#papoReadUtf 逐行一致。 */
    static String papoReadUtf(java.io.DataInput input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes, 0, length);
        for (int i = 0; i < length; i++) {
            if (bytes[i] < 0) {
                byte[] prefixed = new byte[2 + length];
                prefixed[0] = (byte) (length >>> 8);
                prefixed[1] = (byte) length;
                System.arraycopy(bytes, 0, prefixed, 2, length);
                return new DataInputStream(new ByteArrayInputStream(prefixed)).readUTF();
            }
        }
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }
}
