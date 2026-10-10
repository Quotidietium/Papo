package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Papo 批次163（R22 对抗复核）：通用持有者探针——收集目标类（含数组类，如
// "[Lca.spottedleaf.dataconverter.types.MapType;"）的全部实例 id，滑窗扫描
// 实例 blob 与对象数组元素，输出引用者类直方图。补 ShellRefAttr 不能探数组类
// 的洞（批160 判例）。HPROF 行走复制自 PapoStaticRefCensus。
// 用法：java -Xmx3g papo.bot.HolderProbe <heap.hprof> <类名>...
public final class HolderProbe {

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    interface DumpRec { long dump(int tag, DataInputStream in) throws IOException; }

    static final class Hprof {
        final Path file;
        int idSize;
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classNameStr = new HashMap<>();
        final Map<Long, String> classNames = new HashMap<>();
        final List<Long> targetCls = new ArrayList<>();
        final HashSet<Long> targetIds = new HashSet<>();
        final Map<String, long[]> holders = new TreeMap<>();
        long targets;

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
            readId(in); used += this.idSize;
            in.readInt(); used += 4;
            readId(in); used += this.idSize;
            for (int k = 0; k < 5; k++) { readId(in); }
            used += 5L * this.idSize;
            in.readInt(); used += 4;
            int n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                in.readUnsignedShort();
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += 3 + typeLen(t);
            }
            n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                readId(in);
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += this.idSize + 1L + typeLen(t);
            }
            n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                readId(in); in.read();
                used += this.idSize + 1L;
            }
            return used;
        }

        static boolean isRoot(final int tag) {
            return tag == 0xFF || (tag >= 0x01 && tag <= 0x08);
        }

        long parseRoot(final int tag, final DataInputStream in) throws IOException {
            final long id = readId(in);
            long rest;
            switch (tag) {
                case 0xFF, 0x05 -> rest = 0;
                case 0x01 -> rest = this.idSize;
                case 0x02, 0x03, 0x08 -> rest = 8;
                case 0x04, 0x06, 0x07 -> rest = 4;
                default -> throw new IOException("bad root tag 0x" + Integer.toHexString(tag));
            }
            if (this.targetIds.contains(id)) {
                this.holders.computeIfAbsent("root:0x" + Integer.toHexString(tag), x -> new long[1])[0]++;
            }
            skipFully(in, rest);
            return this.idSize + rest;
        }

        long parseClassDumpStatics(final DataInputStream in) throws IOException {
            long used = 0;
            final long clsId = readId(in); used += this.idSize;
            in.readInt(); used += 4;
            readId(in); used += this.idSize;
            for (int k = 0; k < 5; k++) { readId(in); }
            used += 5L * this.idSize;
            in.readInt(); used += 4;
            int n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                in.readUnsignedShort();
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += 3 + typeLen(t);
            }
            n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                final long nameId = readId(in);
                final int t = in.read();
                if (t == 2) {
                    final long ref = readId(in);
                    if (this.targetIds.contains(ref)) {
                        this.holders.computeIfAbsent("static:" + this.classNames.getOrDefault(clsId, "?cls" + clsId), x -> new long[1])[0]++;
                    }
                } else {
                    skipFully(in, typeLen(t));
                }
                used += this.idSize + 1L + typeLen(t);
            }
            n = in.readUnsignedShort(); used += 2;
            for (int k = 0; k < n; k++) {
                readId(in); in.read();
                used += this.idSize + 1L;
            }
            return used;
        }

        DataInputStream open() throws IOException {
            return new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file), 1 << 16));
        }

        void run(final String[] wants) throws IOException {
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
            final List<String> wantList = List.of(wants);
            for (final Map.Entry<Long, String> e : this.classNames.entrySet()) {
                if (wantList.contains(e.getValue())) { this.targetCls.add(e.getKey()); }
            }
            System.out.printf("target classes: %s%n", this.targetCls);
            if (this.targetCls.isEmpty()) { throw new IllegalStateException("no target class found"); }
            // pass 2：收集目标实例（INSTANCE + OBJ_ARRAY；数组类只有 OBJ_ARRAY）
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        final long id = readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        boolean isTarget = false;
                        for (final long t : this.targetCls) { if (t == clsId) { isTarget = true; break; } }
                        final int n = i.readInt();
                        if (isTarget) { this.targetIds.add(id); this.targets++; }
                        skipFully(i, n);
                        return 2L * this.idSize + 8 + n;
                    }
                    if (tag == TAG_OBJ_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();          // numElements 先于 classOID
                        final long clsId = readId(i);
                        boolean isTarget = false;
                        for (final long t : this.targetCls) { if (t == clsId) { isTarget = true; break; } }
                        if (isTarget) { this.targetIds.add(id); this.targets++; }
                        skipFully(i, (long) n * this.idSize);
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    return skipSub(tag, i);
                });
            }
            System.out.printf("target instances: %d%n", this.targets);
            // pass 2b：静态字段（CLASS_DUMP object 型值）与根记录也计入持有者
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_CLASS_DUMP) { return parseClassDumpStatics(i); }
                    if (isRoot(tag)) { return parseRoot(tag, i); }
                    return skipSub(tag, i);
                });
            }
            // pass 3：滑窗找引用者
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        readId(i); i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (n > 0 && !this.targetIds.isEmpty()) {
                            final byte[] blob = new byte[n];
                            i.readFully(blob);
                            long hits = 0;
                            for (int off = 0; off + this.idSize <= n; off++) {
                                if (this.targetIds.contains(idAt(blob, off))) { hits++; }
                            }
                            if (hits > 0) {
                                this.holders.computeIfAbsent(this.classNames.getOrDefault(clsId, "?cls" + clsId), x -> new long[1])[0] += hits;
                            }
                        } else {
                            skipFully(i, n);
                        }
                        return 2L * this.idSize + 8 + n;
                    }
                    if (tag == TAG_OBJ_ARRAY) {
                        readId(i); i.readInt();
                        final int n = i.readInt();
                        final long outerCls = readId(i);
                        long hits = 0;
                        for (int k = 0; k < n; k++) {
                            if (this.targetIds.contains(readId(i))) { hits++; }
                        }
                        if (hits > 0) {
                            this.holders.computeIfAbsent("arr:" + this.classNames.getOrDefault(outerCls, "?cls" + outerCls), x -> new long[1])[0] += hits;
                        }
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    return skipSub(tag, i);
                });
            }
            final List<Map.Entry<String, long[]>> hs = new ArrayList<>(this.holders.entrySet());
            hs.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (int k = 0; k < Math.min(25, hs.size()); k++) {
                System.out.printf("%12d refs  %s%n", hs.get(k).getValue()[0], hs.get(k).getKey());
            }
        }

        void skipHeaderOf(final DataInputStream in) throws IOException {
            while (in.read() != 0) { }
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
        }
    }

    public static void main(final String[] args) throws Exception {
        new Hprof(Path.of(args[0])).run(java.util.Arrays.copyOfRange(args, 1, args.length));
    }
}
