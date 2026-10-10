package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Papo 批次162（R21 残余归因）：O2O 状态普查 + [J 构成复核。
// 面(a)：CompoundTag 30,452 张（0.92.0 收官转储）——每张持一个 16 槽 O2O；
//   size 字段按 hprof 声明序 blob 偏移解码（批160 判例：1B 字段使后续字段错位，
//   偏移=前序声明字段宽度之和，非对齐）。偏移经 fastutil-8.5.18 控制转储实证：
//   O2OControl 构造 0/3/38 三尺寸（含扩容+2 删除）自转储，本工具在控制转储上
//   复核读出 {0,3,38} 才算解码器正确（控制探针=金标准，批160 判例）。
// 面(b)：[J 254,543/173.3MB 在 6,114 区块制式——长度直方图 +
//   SimpleBitStorage/LongArrayTag/BitSet/Heightmap 实例 blob 的 [J id 交集归因
//   （滑窗，无需偏移），对 R13 判负边界（heightmap 37 / palette 256 / biome 1-2）重验。
// 用法：java -Xmx3g papo.bot.PapoO2OStateCensus <heap.hprof>   （-Dcontrol=true 时按控制转储校验）
public final class PapoO2OStateCensus {

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    interface DumpRec { long dump(int tag, DataInputStream in) throws IOException; }

    static final class Cls { final long id; final String name; final List<long[]> fields = new ArrayList<>(); // (nameId, type)
        Cls(long i, String n) { this.id = i; this.name = n; } }

    static final class Hprof {
        final Path file;
        int idSize;
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classNameStr = new HashMap<>();
        final Map<Long, String> classNames = new HashMap<>();
        final Map<String, Cls> targetsByName = new HashMap<>();
        final Map<Long, Cls> targetsById = new HashMap<>();
        // 面数据
        final Map<Long, Integer> o2oSize = new HashMap<>();
        final Map<Long, Long> ctTags = new HashMap<>();        // CompoundTag id -> tags O2O id
        final Map<Long, int[]> oarr = new HashMap<>();         // [Object id -> [len, nonNull]
        final Map<Long, Integer> jLen = new HashMap<>();       // [J id -> length
        long jTotalBytes, jTotalCount;
        final long[] jLenHist = new long[257];
        long jLenGt256Bytes, jLenGt256Count;
        final Map<String, long[]> jAttr = new TreeMap<>();     // 持有类 -> [bytes, count]
        final java.util.HashSet<Long> jAttribIds = new java.util.HashSet<>();
        final Map<String, long[]> jRemainHolder = new TreeMap<>(); // 残余 [J 持有者 -> [bytes, count]
        final TreeMap<Integer, Long> jLenBig = new TreeMap<>();    // >256 长度明细
        final java.util.HashSet<Long> emptyCtIds = new java.util.HashSet<>();
        final java.util.HashSet<Long> emptyO2oIds = new java.util.HashSet<>();
        final Map<String, long[]> emptyHolder = new TreeMap<>();
        final Map<Integer, Integer> emptyCap = new TreeMap<>();

        Hprof(final Path f) { this.file = f; }

        long readId(final DataInputStream i) throws IOException {
            return this.idSize == 8 ? i.readLong() : (i.readInt() & 0xFFFF_FFFFL);
        }

        long idAt(final byte[] b, final int off) {
            if (this.idSize == 8) {
                long v = 0;
                for (int k = 0; k < 8; k++) { v = (v << 8) | (b[off + k] & 0xFFL); }
                return v;
            }
            return ((b[off] & 0xFFL)) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
        }

        void skipFully(final java.io.DataInput i, long n) throws IOException {
            while (n > 0) {
                final long s = i.skipBytes((int) Math.min(n, 1 << 20));
                if (s <= 0) {
                    if (i.readByte() == -1) throw new java.io.EOFException();
                    n--;
                } else {
                    n -= s;
                }
            }
        }

        static int primLen(final int t) {
            return switch (t) {
                case 4, 8 -> 1;
                case 5, 9 -> 2;
                case 6, 10 -> 4;
                case 7, 11 -> 8;
                default -> 1;
            };
        }

        int typeLen(final int t) { return t == 2 ? this.idSize : primLen(t); }

        void walk(final DataInputStream in, final DumpRec dump) throws IOException {
            while (in.read() != 0) { }
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
            while (true) {
                final int tag = in.read();
                if (tag == -1) { return; }
                in.readInt();
                final int len = in.readInt();
                if (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) {
                    long left = len;
                    while (left > 0) {
                        final int st = in.read();
                        final long used = dump.dump(st, in);
                        if (used < 0) { throw new IOException("unknown sub 0x" + Integer.toHexString(st)); }
                        left -= 1 + used;
                        if (left < 0) { throw new IOException("sub-record overran segment"); }
                    }
                } else {
                    skipFully(in, len);
                }
            }
        }

        long skipSub(final int tag, final DataInputStream in) throws IOException {
            switch (tag) {
                case TAG_CLASS_DUMP -> { return parseClassDump(in); }
                case TAG_INSTANCE -> {
                    readId(in); in.readInt(); readId(in);
                    final int n = in.readInt();
                    skipFully(in, n);
                    return 2L * this.idSize + 8 + n;
                }
                case TAG_OBJ_ARRAY -> {
                    readId(in); in.readInt();
                    final int n = in.readInt();
                    readId(in);
                    skipFully(in, (long) n * this.idSize);
                    return 2L * this.idSize + 8 + (long) n * this.idSize;
                }
                case TAG_PRIM_ARRAY -> {
                    readId(in); in.readInt();
                    final int n = in.readInt();
                    final int t = in.read();
                    skipFully(in, (long) n * primLen(t));
                    return this.idSize + 9 + (long) n * primLen(t);
                }
                case 0x05, 0xFF -> { skipFully(in, this.idSize); return this.idSize; }
                case 0x01 -> { skipFully(in, 2L * this.idSize); return 2L * this.idSize; }
                case 0x02, 0x03, 0x08 -> { skipFully(in, this.idSize + 8L); return this.idSize + 8L; }
                case 0x04, 0x06, 0x07 -> { skipFully(in, this.idSize + 4L); return this.idSize + 4L; }
                default -> { return -1; }
            }
        }

        long parseClassDump(final DataInputStream in) throws IOException {
            long used = 0;
            final long clsId = readId(in);
            used += this.idSize;
            in.readInt();
            used += 4;
            readId(in);
            used += this.idSize;
            for (int k = 0; k < 5; k++) { readId(in); }
            used += 5L * this.idSize;
            in.readInt();
            used += 4;
            int n = in.readUnsignedShort();
            used += 2;
            for (int k = 0; k < n; k++) {
                in.readUnsignedShort();
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += 3 + typeLen(t);
            }
            n = in.readUnsignedShort();
            used += 2;
            for (int k = 0; k < n; k++) {
                readId(in);
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += this.idSize + 1L + typeLen(t);
            }
            n = in.readUnsignedShort();
            used += 2;
            final Cls target = this.targetsById.get(clsId);
            for (int k = 0; k < n; k++) {
                final long nameId = readId(in);
                final int t = in.read();
                if (target != null) { target.fields.add(new long[]{nameId, t}); }
                used += this.idSize + 1L;
            }
            return used;
        }

        DataInputStream open() throws IOException {
            return new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file), 1 << 16));
        }

        void run() throws IOException {
            // pass 0：字符串 + 类名
            try (DataInputStream in = open()) {
                skipHeaderOf(in);
                while (true) {
                    final int tag = in.read();
                    if (tag == -1) { break; }
                    in.readInt();
                    final int len = in.readInt();
                    if (tag == TAG_STRING) {
                        final long id = readId(in);
                        final byte[] b = new byte[len - this.idSize];
                        in.readFully(b);
                        final char[] c = new char[b.length];
                        for (int k = 0; k < b.length; k++) { c[k] = (char) (b[k] & 0xFF); }
                        this.strings.put(id, new String(c));
                    } else if (tag == TAG_LOAD_CLASS) {
                        in.readInt();
                        final long clsId = readId(in);
                        in.readInt();
                        final long strId = readId(in);
                        this.classNameStr.put(clsId, strId);
                    } else {
                        skipFully(in, len);
                    }
                }
            }
            for (final Map.Entry<Long, Long> e : this.classNameStr.entrySet()) {
                final String raw = this.strings.get(e.getValue());
                if (raw != null) { this.classNames.put(e.getKey(), raw.replace('/', '.')); }
            }
            final String[] want = {
                "it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap",
                "net.minecraft.nbt.CompoundTag",
                "net.minecraft.util.SimpleBitStorage",
                "net.minecraft.nbt.LongArrayTag",
                "java.util.BitSet",
                "net.minecraft.world.level.levelgen.Heightmap",
            };
            for (final Map.Entry<Long, String> e : this.classNames.entrySet()) {
                for (final String w : want) {
                    if (w.equals(e.getValue())) {
                        final Cls c = new Cls(e.getKey(), w);
                        this.targetsByName.put(w, c);
                        this.targetsById.put(e.getKey(), c);
                    }
                }
            }
            final Cls o2oCls = mustTarget(want[0]);
            final Cls ctCls = this.targetsByName.get(want[1]); // 控制转储无此类，pass3 前置判空
            final String sampleClsProp = System.getProperty("sampleCls");
            if (sampleClsProp != null && !this.targetsByName.containsKey(sampleClsProp)) {
                for (final Map.Entry<Long, String> e : this.classNames.entrySet()) {
                    if (sampleClsProp.equals(e.getValue())) {
                        final Cls c = new Cls(e.getKey(), sampleClsProp);
                        this.targetsByName.put(sampleClsProp, c);
                        this.targetsById.put(e.getKey(), c);
                    }
                }
            }
            final Cls sbs = this.targetsByName.get(want[2]), lat = this.targetsByName.get(want[3]), bs = this.targetsByName.get(want[4]), hm = this.targetsByName.get(want[5]);
            // pass 1：目标类字段表 + [Object 元数据 + [J 长度表
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_CLASS_DUMP) { return parseClassDump(i); }
                    if (tag == TAG_OBJ_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final long outerCls = readId(i);
                        if ("[Ljava.lang.Object;".equals(this.classNames.get(outerCls))) {
                            int nonNull = 0;
                            for (int k = 0; k < n; k++) { if (readId(i) != 0) { nonNull++; } }
                            this.oarr.put(id, new int[]{n, nonNull});
                        } else {
                            skipFully(i, (long) n * this.idSize);
                        }
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    if (tag == TAG_PRIM_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final int t = i.read();
                        if (t == 11) {
                            this.jLen.put(id, n);
                            this.jTotalCount++;
                            final long bytes = 24L + 8L * n;
                            this.jTotalBytes += bytes;
                            if (n <= 256) { this.jLenHist[n]++; } else { this.jLenGt256Count++; this.jLenGt256Bytes += bytes; this.jLenBig.merge(n, 1L, Long::sum); }
                        }
                        skipFully(i, (long) n * primLen(t));
                        return this.idSize + 9 + (long) n * primLen(t);
                    }
                    return skipSub(tag, i);
                });
            }
            // 声明序 blob 偏移（hprof 无头、按声明序、1B 字段错位）
            final int sizeOff = blobOffset(o2oCls, "size");
            System.out.printf("O2O fields(decl): %s ; size@blobOff=%d%n", fieldNames(o2oCls), sizeOff);
            System.out.printf("[J total: %d arrays / %d bytes ; [Object tracked: %d%n", this.jTotalCount, this.jTotalBytes, this.oarr.size());
            // pass 2：O2O size 解码 + [J 持有者归因（SBS/LongArrayTag/BitSet/Heightmap）
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        final long id = readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        final Cls c = this.targetsById.get(clsId);
                        if (c == null) { skipFully(i, n); return 2L * this.idSize + 8 + n; }
                        final byte[] blob = new byte[n];
                        i.readFully(blob);
                        if (c == o2oCls) {
                            this.o2oSize.put(id, (int) uintAt(blob, sizeOff));
                        } else {
                            // [J 归因：blob 内任意字节偏移的 id ∩ jLen
                            for (int off = 0; off + this.idSize <= n; off++) {
                                final long cand = idAt(blob, off);
                                if (cand != 0) {
                                    final Integer len = this.jLen.get(cand);
                                    if (len != null) {
                                        final long[] v = this.jAttr.computeIfAbsent(c.name, x -> new long[2]);
                                        v[0] += 24L + 8L * len;
                                        v[1]++;
                                        this.jAttribIds.add(cand);
                                    }
                                }
                            }
                        }
                        return 2L * this.idSize + 8 + n;
                    }
                    return skipSub(tag, i);
                });
            }
            // 控制转储校验：恰 3 个 O2O，尺寸集合 {0,3,38}
            if (Boolean.getBoolean("control")) {
                final List<Integer> sizes = new ArrayList<>(this.o2oSize.size());
                for (final int s : this.o2oSize.values()) { sizes.add(s); }
                java.util.Collections.sort(sizes);
                System.out.println("CONTROL sizes=" + sizes);
                final boolean ok = sizes.size() == 3 && sizes.get(0) == 0 && sizes.get(1) == 3 && sizes.get(2) == 38;
                System.out.println(ok ? "CONTROL-OK" : "CONTROL-FAIL");
                if (!ok) { System.exit(2); }
                return; // 控制转储无 CompoundTag，后续 pass 不适用
            }
            // pass 3：CompoundTag -> tags（blob 单 oop 字段，滑窗取 o2oSize 命中者兜底）
            if (ctCls == null) {
                System.out.println("(no CompoundTag class in dump; skipping CT passes)");
                dumpJAttr();
                return;
            }
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        final long id = readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (clsId != ctCls.id) { skipFully(i, n); return 2L * this.idSize + 8 + n; }
                        final byte[] blob = new byte[n];
                        i.readFully(blob);
                        for (int off = 0; off + this.idSize <= n; off++) {
                            final long cand = idAt(blob, off);
                            if (cand != 0 && this.o2oSize.containsKey(cand)) { this.ctTags.put(id, cand); break; }
                        }
                        return 2L * this.idSize + 8 + n;
                    }
                    return skipSub(tag, i);
                });
            }
            final long[] buckets = new long[10];
            final String[] bucketNames = {"0", "1", "2-3", "4-7", "8-15", "16-31", "32-63", "64-127", "128-255", "256+"};
            long keyMismatch = 0, keyChecked = 0;
            for (final Map.Entry<Long, Long> e : this.ctTags.entrySet()) {
                final Integer sz = this.o2oSize.get(e.getValue());
                if (sz == null) { continue; }
                final int b = sz == 0 ? 0 : sz == 1 ? 1 : sz <= 3 ? 2 : sz <= 7 ? 3 : sz <= 15 ? 4 : sz <= 31 ? 5 : sz <= 63 ? 6 : sz <= 127 ? 7 : sz <= 255 ? 8 : 9;
                buckets[b]++;
                if (sz == 0) { this.emptyCtIds.add(e.getKey()); this.emptyO2oIds.add(e.getValue()); }
            }
            System.out.println("== CompoundTag map size histogram ==");
            long totalCt = 0;
            for (int k = 0; k < 10; k++) { totalCt += buckets[k]; System.out.printf("  %-6s %8d%n", bucketNames[k], buckets[k]); }
            System.out.printf("  empty fraction: %d/%d = %.1f%%%n", emptyCtIds.size(), totalCt, 100.0 * emptyCtIds.size() / Math.max(1, totalCt));
            // pass 4：空 O2O 的 key/value 背板数组长度（非空槽=0 的 [Object 引用）
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        final long id = readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (clsId != o2oCls.id || !this.emptyO2oIds.contains(id)) { skipFully(i, n); return 2L * this.idSize + 8 + n; }
                        final byte[] blob = new byte[n];
                        i.readFully(blob);
                        int cnt = 0;
                        for (int off = 0; off + this.idSize <= n && cnt < 2; off++) {
                            final long cand = idAt(blob, off);
                            final int[] meta = cand == 0 ? null : this.oarr.get(cand);
                            if (meta != null && meta[1] == 0) { this.emptyCap.merge(meta[0], 1, Integer::sum); cnt++; }
                        }
                        return 2L * this.idSize + 8 + n;
                    }
                    return skipSub(tag, i);
                });
            }
            long emptyArrBytes = 0;
            for (final Map.Entry<Integer, Integer> e : this.emptyCap.entrySet()) {
                System.out.printf("  empty-map backing [Object len=%d: %d refs (2/map)%n", e.getKey(), e.getValue());
                emptyArrBytes += (long) e.getValue() * (16L + 4L * e.getKey());
            }
            System.out.printf("empty-map cost: maps=%d shells(CT 16 + O2O 64)=%d B ; backing arrays=%d B ; total=%.2f MB%n",
                emptyCtIds.size(), emptyCtIds.size() * 80L, emptyArrBytes, (emptyCtIds.size() * 80L + emptyArrBytes) / 1048576.0);
            // pass 5：空 CompoundTag 持有者（滑窗，实例+对象数组）
            if (!this.emptyCtIds.isEmpty()) {
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_INSTANCE) {
                            readId(i); i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            if (n > 0) {
                                final byte[] blob = new byte[n];
                                i.readFully(blob);
                                long hits = 0;
                                for (int off = 0; off + this.idSize <= n; off++) {
                                    if (this.emptyCtIds.contains(idAt(blob, off))) { hits++; }
                                }
                                if (hits > 0) {
                                    this.emptyHolder.computeIfAbsent(this.classNames.getOrDefault(clsId, "?cls" + clsId), x -> new long[1])[0] += hits;
                                }
                            }
                            return 2L * this.idSize + 8 + n;
                        }
                        if (tag == TAG_OBJ_ARRAY) {
                            readId(i); i.readInt();
                            final int n = i.readInt();
                            final long outerCls = readId(i);
                            long hits = 0;
                            for (int k = 0; k < n; k++) {
                                if (this.emptyCtIds.contains(readId(i))) { hits++; }
                            }
                            if (hits > 0) {
                                this.emptyHolder.computeIfAbsent("arr:" + this.classNames.getOrDefault(outerCls, "?cls" + outerCls), x -> new long[1])[0] += hits;
                            }
                            return 2L * this.idSize + 8 + (long) n * this.idSize;
                        }
                        return skipSub(tag, i);
                    });
                }
                System.out.println("== empty CompoundTag holders (slide-scan) ==");
                final List<Map.Entry<String, long[]>> hs = new ArrayList<>(this.emptyHolder.entrySet());
                hs.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
                for (int k = 0; k < Math.min(25, hs.size()); k++) {
                    System.out.printf("%12d refs  %s%n", hs.get(k).getValue()[0], hs.get(k).getKey());
                }
            }
            // pass 6：未归因 [J 的持有者（滑窗扫实例 blob + 对象数组元素，按持有者类记账）
            final java.util.HashSet<Long> jRemainIds = new java.util.HashSet<>();
            for (final long id : this.jLen.keySet()) {
                if (!this.jAttribIds.contains(id)) { jRemainIds.add(id); }
            }
            // 采样模式：-DsampleLen=N 只归因该长度的 [J；-DsampleCls=C 只看该持有类
            final int sampleLen = Integer.getInteger("sampleLen", -1);
            final String sampleCls = System.getProperty("sampleCls");
            final java.util.HashSet<Long> sampleIds = new java.util.HashSet<>();
            final Map<String, java.util.List<String>> sampleHits = new TreeMap<>();
            if (sampleLen >= 0) {
                for (final Map.Entry<Long, Integer> e : this.jLen.entrySet()) {
                    if (e.getValue() == sampleLen) { sampleIds.add(e.getKey()); }
                }
                System.out.printf("sampleLen=%d: %d ids%n", sampleLen, sampleIds.size());
            }
            final Cls sampleTarget = sampleCls == null ? null : this.targetsByName.get(sampleCls);
            // pass 6b：采样；-DsbsLen=N 解码 SimpleBitStorage 的 bits/size（声明序 data@0 bits@4 mask@8 size@16）
            final int sbsLen = Integer.getInteger("sbsLen", -1);
            if (sbsLen >= 0) {
                final Cls sbsC = mustTarget("net.minecraft.util.SimpleBitStorage");
                System.out.println("SBS fields(decl/class-dump): " + fieldNames(sbsC));
                final java.util.TreeMap<String, Integer> sbsTally = new java.util.TreeMap<>();
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_INSTANCE) {
                            readId(i); i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            if (clsId != sbsC.id || n < 20) { skipFully(i, n); return 2L * this.idSize + 8 + n; }
                            final byte[] blob = new byte[n];
                            i.readFully(blob);
                            final Integer len = this.jLen.get(idAt(blob, 0));
                            if (len != null && len == sbsLen) {
                                if (sbsTally.isEmpty()) {
                                    final StringBuilder hx = new StringBuilder();
                                    for (int k = 0; k < Math.min(n, 48); k++) { hx.append(String.format("%02x ", blob[k])); }
                                    System.out.println("first-hit blob hex: " + hx);
                                }
                                final int bits = (int) uintAt(blob, 4);
                                final int size = (int) uintAt(blob, 16);
                                sbsTally.merge("bits=" + bits + " size=" + size, 1, Integer::sum);
                            }
                            return 2L * this.idSize + 8 + n;
                        }
                        return skipSub(tag, i);
                    });
                }
                System.out.println("== SBS decode for storage len=" + sbsLen + " ==");
                for (final Map.Entry<String, Integer> e : sbsTally.entrySet()) { System.out.printf("  %s : %d%n", e.getKey(), e.getValue()); }
            }
            if (!sampleIds.isEmpty() || sampleTarget != null) {
                final Map<String, String> fieldTable = sampleTarget == null ? null : declFieldTable(sampleTarget);
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_INSTANCE) {
                            final long iid = readId(i); i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            final boolean isSampleCls = sampleTarget != null && clsId == sampleTarget.id;
                            final boolean wantBlob = isSampleCls || !sampleIds.isEmpty();
                            if (!wantBlob || n <= 0) { skipFully(i, n); return 2L * this.idSize + 8 + n; }
                            final byte[] blob = new byte[n];
                            i.readFully(blob);
                            final String cn = this.classNames.getOrDefault(clsId, "?cls" + clsId);
                            for (int off = 0; off + this.idSize <= n; off++) {
                                final long cand = idAt(blob, off);
                                if (cand == 0) { continue; }
                                if (isSampleCls) {
                                    final Integer len = this.jLen.get(cand);
                                    if (len != null) {
                                        sampleHits.computeIfAbsent(cn, x -> new ArrayList<>()).add(
                                            "len=" + len + " @" + off + (fieldTable == null ? "" : " " + fieldAt(fieldTable, off)));
                                    }
                                } else if (sampleIds.contains(cand)) {
                                    sampleHits.computeIfAbsent(cn, x -> new ArrayList<>()).add("@" + off);
                                }
                            }
                            return 2L * this.idSize + 8 + n;
                        }
                        if (!sampleIds.isEmpty() && tag == TAG_OBJ_ARRAY) {
                            readId(i); i.readInt();
                            final int n = i.readInt();
                            final long outerCls = readId(i);
                            for (int k = 0; k < n; k++) {
                                if (sampleIds.contains(readId(i))) {
                                    sampleHits.computeIfAbsent("arr:" + this.classNames.getOrDefault(outerCls, "?cls" + outerCls), x -> new ArrayList<>()).add("elem");
                                }
                            }
                            return 2L * this.idSize + 8 + (long) n * this.idSize;
                        }
                        return skipSub(tag, i);
                    });
                }
                System.out.println("== sample hits ==");
                for (final Map.Entry<String, java.util.List<String>> e : sampleHits.entrySet()) {
                    final java.util.Map<String, Integer> tally = new TreeMap<>();
                    for (final String s : e.getValue()) { tally.merge(s, 1, Integer::sum); }
                    System.out.printf("  %s : total=%d distinct-refs=%d%n    %s%n", e.getKey(), e.getValue().size(), tally.size(), tally);
                }
            }
            if (!jRemainIds.isEmpty()) {
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_INSTANCE) {
                            readId(i); i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            if (n > 0) {
                                final byte[] blob = new byte[n];
                                i.readFully(blob);
                                long b = 0;
                                for (int off = 0; off + this.idSize <= n; off++) {
                                    final long cand = idAt(blob, off);
                                    final Integer len = cand == 0 ? null : jRemainIds.contains(cand) ? this.jLen.get(cand) : null;
                                    if (len != null) { final long[] v = this.jRemainHolder.computeIfAbsent(this.classNames.getOrDefault(clsId, "?cls" + clsId), x -> new long[2]); v[0] += 24L + 8L * len; v[1]++; }
                                }
                            }
                            return 2L * this.idSize + 8 + n;
                        }
                        if (tag == TAG_OBJ_ARRAY) {
                            readId(i); i.readInt();
                            final int n = i.readInt();
                            final long outerCls = readId(i);
                            long b = 0;
                            for (int k = 0; k < n; k++) {
                                final long cand = readId(i);
                                final Integer len = cand == 0 ? null : jRemainIds.contains(cand) ? this.jLen.get(cand) : null;
                                if (len != null) { final long[] v = this.jRemainHolder.computeIfAbsent("arr:" + this.classNames.getOrDefault(outerCls, "?cls" + outerCls), x -> new long[2]); v[0] += 24L + 8L * len; v[1]++; }
                            }
                            return 2L * this.idSize + 8 + (long) n * this.idSize;
                        }
                        return skipSub(tag, i);
                    });
                }
            }
            dumpJAttr();
        }

        void dumpJAttr() {
            System.out.println("== [J length histogram ==");
            for (int k = 0; k <= 256; k++) {
                if (this.jLenHist[k] > 0) { System.out.printf("  len=%3d: %8d arrays %10d bytes%n", k, this.jLenHist[k], this.jLenHist[k] * (24L + 8L * k)); }
            }
            System.out.printf("  len>256: %d arrays / %d bytes ; detail: %s%n", this.jLenGt256Count, this.jLenGt256Bytes, this.jLenBig);
            System.out.println("== [J attributed by holder (instance blob id intersection) ==");
            long attrB = 0, attrN = 0;
            for (final Map.Entry<String, long[]> e : this.jAttr.entrySet()) {
                attrB += e.getValue()[0]; attrN += e.getValue()[1];
                System.out.printf("  %-55s %12d bytes %9d arrays%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
            System.out.printf("  attributed: %d/%d bytes (%.1f%%) ; arrays %d ; remainder %d bytes%n",
                attrB, this.jTotalBytes, 100.0 * attrB / Math.max(1, this.jTotalBytes), attrN, this.jTotalBytes - attrB);
            System.out.println("== [J remainder holders (slide-scan) ==");
            final List<Map.Entry<String, long[]>> rh = new ArrayList<>(this.jRemainHolder.entrySet());
            rh.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (int k = 0; k < Math.min(20, rh.size()); k++) {
                System.out.printf("  %-70s %12d bytes %9d arrays%n", rh.get(k).getKey(), rh.get(k).getValue()[0], rh.get(k).getValue()[1]);
            }
        }

        Cls mustTarget(final String name) {
            final Cls c = this.targetsByName.get(name);
            if (c == null) { throw new IllegalStateException("missing target class " + name); }
            return c;
        }

        /** hprof 实例 blob 声明序偏移：前序字段宽度之和（oop=idSize，int/float=4，short/char=2，byte/bool=1，long/double=8）。 */
        int blobOffset(final Cls c, final String name) {
            int off = 0;
            for (final long[] f : c.fields) {
                final String n = this.strings.get(f[0]);
                if (name.equals(n)) { return off; }
                off += typeLen((int) f[1]);
            }
            throw new IllegalStateException("field " + name + " not in " + c.name);
        }

        List<String> fieldNames(final Cls c) {
            final List<String> out = new ArrayList<>();
            for (final long[] f : c.fields) { out.add(this.strings.get(f[0]) + ":" + f[1]); }
            return out;
        }

        long uintAt(final byte[] b, final int off) {
            long v = 0;
            for (int k = 0; k < 4; k++) { v = (v << 8) | (b[off + k] & 0xFFL); }
            return v;
        }

        /** 声明序字段表："start-end name" 前缀串，供采样命中定位字段名。 */
        Map<String, String> declFieldTable(final Cls c) {
            final Map<String, String> out = new java.util.LinkedHashMap<>();
            int off = 0;
            for (final long[] f : c.fields) {
                final int w = typeLen((int) f[1]);
                out.put(off + "-" + (off + w - 1), this.strings.get(f[0]) + ":" + f[1]);
                off += w;
            }
            return out;
        }

        static String fieldAt(final Map<String, String> table, final int off) {
            for (final Map.Entry<String, String> e : table.entrySet()) {
                final int dash = e.getKey().indexOf('-');
                final int s = Integer.parseInt(e.getKey().substring(0, dash));
                final int t = Integer.parseInt(e.getKey().substring(dash + 1));
                if (off >= s && off <= t) { return e.getValue(); }
            }
            return "?field";
        }

        void skipHeaderOf(final DataInputStream in) throws IOException {
            while (in.read() != 0) { }
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
        }
    }

    public static void main(final String[] args) throws Exception {
        new Hprof(Path.of(args[0])).run();
    }
}
