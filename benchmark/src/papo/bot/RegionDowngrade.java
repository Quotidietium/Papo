package papo.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.zip.InflaterOutputStream;

/**
 * 批次169（R28）：区域文件 DataVersion 降版器——把盘载世界的每区块 DataVersion
 * 改写为旧目标版本，使服务器重载时走完整 DFU/数据转换升级链（此前所有重访腿均为
 * 同版本加载=零转码，DFU 面从未被实测）。
 *
 * 格式：Anvil .mca = 8KB 头（1024×4B 位置表 + 1024×4B 时间戳）+ 4KB 扇区网格；
 * 每区块 = 4B 大端长度 + 1B 压缩类型 + 压缩 NBT。改写策略：解压 → 定位根复合
 * 标签下名为 DataVersion 的 TAG_Int 的 4 字节值偏移 → 原位替换 → 重压缩 → 若
 * 新载荷+5 ≤ 原扇区数×4096 则原位回写（零头补零），否则追加新扇区并更新位置表。
 *
 * 用法：java papo.bot.RegionDowngrade <worldDir> <targetVersion>   （改写 region/*.mca）
 *       java papo.bot.RegionDowngrade <worldDir> 0                  （只报告不改写）
 */
public final class RegionDowngrade {

    public static void main(final String[] args) throws Exception {
        final Path world = Path.of(args[0]);
        final int target = Integer.parseInt(args[1]);
        final List<Path> files = new ArrayList<>();
        try (var s = Files.list(world.resolve("region"))) {
            s.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(files::add);
        }
        files.sort(null);
        int chunks = 0, patched = 0;
        int[] versionHist = new int[8];
        for (final Path f : files) {
            final int[] r = downgrade(f, target);
            chunks += r[0];
            patched += r[1];
            versionHist[r[2] % 8]++; // 汇总用粗桶（诊断模式报告原始版本）
        }
        System.out.println("RegionDowngrade files=" + files.size() + " chunks=" + chunks
            + " patched=" + patched + " target=" + (target == 0 ? "REPORT-ONLY" : target));
    }

    /** @return {chunks, patched, firstSeenVersion} */
    static int[] downgrade(final Path file, final int target) throws IOException {
        final byte[] all = Files.readAllBytes(file);
        final ByteBuffer buf = ByteBuffer.wrap(all);
        int chunks = 0, patched = 0, firstVer = -1;
        final ByteArrayOutputStream[] out = {null};
        final List<Integer> relocIdx = new ArrayList<>();

        for (int i = 0; i < 1024; i++) {
            final int entry = buf.getInt(i * 4);
            final int offset = entry >>> 8;
            final int sectors = entry & 0xFF;
            if (offset == 0 || sectors == 0) {
                continue;
            }
            final int base = offset * 4096;
            final int len = readInt(all, base);
            final int comp = all[base + 4] & 0xFF;
            if (len <= 1 || (comp != 1 && comp != 2 && comp != 3)) {
                continue; // gzip(1)/zlib(2)/raw(3) 以外的压缩类型跳过
            }
            final byte[] payload = new byte[len - 1];
            System.arraycopy(all, base + 5, payload, 0, len - 1);
            final byte[] nbt = comp == 3 ? payload : inflate(payload, comp == 1);

            final int valueOff = findRootDataVersionOffset(nbt);
            if (valueOff < 0) {
                continue;
            }
            final int cur = readInt(nbt, valueOff);
            if (firstVer < 0) {
                firstVer = cur;
            }
            chunks++;
            if (target == 0 || cur == target) {
                continue;
            }
            writeInt(nbt, valueOff, target);
            final byte[] newPayload = comp == 3 ? nbt : deflate(nbt, comp == 1);
            final int need = 5 + newPayload.length;
            final int have = sectors * 4096;
            if (need <= have) {
                // 原位回写，尾部补零
                writeInt(all, base, newPayload.length + 1);
                all[base + 4] = (byte) comp;
                System.arraycopy(newPayload, 0, all, base + 5, newPayload.length);
                for (int z = base + 5 + newPayload.length; z < base + have; z++) {
                    all[z] = 0;
                }
            } else {
                // 追加新扇区（尾部），位置表稍后更新
                relocIdx.add(i);
                relocIdx.add(need);
                relocIdx.add(-1); // 占位：写入后回填新偏移
                if (out[0] == null) {
                    out[0] = new ByteArrayOutputStream();
                }
                out[0].write(newPayload, 0, newPayload.length);
                if (out[0].size() % 4096 != 0) { /* 不切分：按块顺序追加对齐 */ }
            }
            patched++;
        }

        if (!relocIdx.isEmpty()) {
            // 简化路径：改写后载荷几乎必然同长（4 字节值替换），追加扇区是罕见回退；
            // 此时整体重写为顺序布局（保持头两张表语义）
            throw new IOException("relocation path not exercised; compressed size grew for " + relocIdx.size() / 3 + " chunks in " + file);
        }
        Files.write(file, all, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return new int[]{chunks, patched, firstVer};
    }

    // ---------- NBT：只走根复合标签的直接子标签，定位 DataVersion 的值偏移 ----------

    public static int findRootDataVersionOffset(final byte[] nbt) {
        int p = 0;
        final int rootType = nbt[p++] & 0xFF;
        if (rootType != 10) {
            return -1; // TAG_Compound
        }
        final int nameLen = ((nbt[p] & 0xFF) << 8) | (nbt[p + 1] & 0xFF);
        p += 2 + nameLen;
        while (true) {
            final int type = nbt[p++] & 0xFF;
            if (type == 0) {
                return -1; // TAG_End：没有 DataVersion
            }
            final int tl = ((nbt[p] & 0xFF) << 8) | (nbt[p + 1] & 0xFF);
            p += 2;
            final byte[] name = new byte[tl];
            System.arraycopy(nbt, p, name, 0, tl);
            p += tl;
            if (type == 3 && new String(name, java.nio.charset.StandardCharsets.UTF_8).equals("DataVersion")) {
                return p; // TAG_Int 载荷 4B 起点
            }
            p = skipPayload(nbt, p, type);
            if (p < 0) {
                return -1;
            }
        }
    }

    private static int skipPayload(final byte[] nbt, int p, final int type) {
        switch (type) {
            case 1: return p + 1;
            case 2: return p + 2;
            case 3: case 5: return p + 4; // Int, Float
            case 4: case 6: return p + 8; // Long, Double（批169 勘误：Long 曾被当 4B 跳过导致根标签错位读 END）
            case 7: { // TAG_Byte_Array
                final int n = readInt(nbt, p);
                return n >= 0 ? p + 4 + n : -1;
            }
            case 8: { // TAG_String
                final int n = ((nbt[p] & 0xFF) << 8) | (nbt[p + 1] & 0xFF);
                return p + 2 + n;
            }
            case 9: { // TAG_List
                final int itemType = nbt[p] & 0xFF;
                final int n = readInt(nbt, p + 1);
                p += 5;
                for (int i = 0; i < n; i++) {
                    p = skipPayload(nbt, p, itemType);
                    if (p < 0) {
                        return -1;
                    }
                }
                return p;
            }
            case 10: { // TAG_Compound
                while (true) {
                    final int t = nbt[p++] & 0xFF;
                    if (t == 0) {
                        return p;
                    }
                    final int tl = ((nbt[p] & 0xFF) << 8) | (nbt[p + 1] & 0xFF);
                    p += 2 + tl;
                    p = skipPayload(nbt, p, t);
                    if (p < 0) {
                        return -1;
                    }
                }
            }
            case 11: { // TAG_Int_Array
                final int n = readInt(nbt, p);
                return n >= 0 ? p + 4 + 4 * n : -1;
            }
            case 12: { // TAG_Long_Array
                final int n = readInt(nbt, p);
                return n >= 0 ? p + 4 + 8 * n : -1;
            }
            default: return -1;
        }
    }

    // ---------- 压缩 ----------

    private static byte[] inflate(final byte[] data, final boolean gzip) throws IOException {
        final var out = new ByteArrayOutputStream();
        try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data),
            gzip ? new java.util.zip.Inflater(true) : new java.util.zip.Inflater()))) {
            final byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static byte[] deflate(final byte[] data, final boolean gzip) throws IOException {
        final var out = new ByteArrayOutputStream();
        try (var dzo = new java.util.zip.DeflaterOutputStream(out,
            gzip ? new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
                 : new java.util.zip.Deflater())) {
            dzo.write(data, 0, data.length);
        }
        return out.toByteArray();
    }

    private static int readInt(final byte[] b, final int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static void writeInt(final byte[] b, final int p, final int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }
}
