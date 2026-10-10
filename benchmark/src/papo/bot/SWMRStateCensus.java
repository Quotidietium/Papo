package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Papo 批次158（R18）：SWMRNibbleArray 私有壳状态普查——定居交换是否覆盖、
// 剩余私有壳卡在哪些谓词独占态。HPROF 行走/子记录跳过/CLASS_DUMP 解析复制自
// ShellRefAttr（判例：解析器复制不重写）。用法：java papo.bot.SWMRStateCensus <heap.hprof>
public final class SWMRStateCensus {

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    static final String TARGET = "ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray";

    interface TopRec { boolean top(int tag, int len, DataInputStream in) throws IOException; }
    interface DumpRec { long dump(int tag, DataInputStream in) throws IOException; }
    static final class LongSet {
        final long[] a; int n;
        LongSet(int cap) { this.a = new long[cap]; }
        boolean add(long v) {
            if (this.contains(v)) return false;
            if (this.n == this.a.length) throw new IllegalStateException("LongSet full");
            this.a[this.n++] = v;
            return true;
        }
        boolean contains(long v) { for (int i = 0; i < this.n; i++) { if (this.a[i] == v) return true; } return false; }
    }

    static final class FieldLayout { String name; int type; int offset; }

    static final class Hprof {
        final Path file;
        final DataInputStream in;
        int idSize;
        final Map<Long, String> strings = new LinkedHashMap<>();
        final Map<Long, Long> classNameStr = new LinkedHashMap<>();
        final Map<Long, String> classNames = new LinkedHashMap<>();
        final List<Long> targetClassIds = new ArrayList<>();
        final Map<Long, List<FieldLayout>> targetLayouts = new LinkedHashMap<>(); // classId -> fields (含超类前置)
        final Map<Long, Long> superOf = new LinkedHashMap<>();
        final Map<String, Long> comboCounts = new TreeMap<>();
        long instances, withStorage;

        Hprof(final Path f) throws IOException {
            this.file = f;
            this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f), 1 << 16));
        }

        long readId(final DataInputStream i) throws IOException {
            if (this.idSize == 8) {
                return i.readLong();
            }
            return i.readInt() & 0xFFFF_FFFFL;
        }

        void skipFully(final java.io.DataInput i, long n) throws IOException {
            while (n > 0) {
                final long s = i.skipBytes((int) Math.min(n, 1 << 20));
                if (s <= 0) {
                    if (i.readByte() == -1) throw new EOFException();
                    n--;
                } else {
                    n -= s;
                }
            }
        }

        String classNameOf(final long clsId) {
            final String n = this.classNames.get(clsId);
            return n != null ? n : "?cls" + clsId;
        }

        long recCount;

        void walk(final DataInputStream in, final TopRec top, final DumpRec dump) throws IOException {
            this.recCount = 0;
            // header: 版本串 + idSize + 高4时间戳
            while (in.read() != 0) { }
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
            while (true) {
                final int tag = in.read();
                if (tag == -1) {
                    return;
                }
                in.readInt();
                final int len = in.readInt();
                if (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) {
                    long left = len;
                    int lastSub = -1;
                    long lastUsed = 0;
                    while (left > 0) {
                        final int st = in.read();
                        final long used = dump.dump(st, in);
                        if (used < 0) {
                            throw new IOException("unknown sub-record 0x" + Integer.toHexString(st) + " left=" + left
                                + " lastSub=0x" + Integer.toHexString(lastSub) + " lastUsed=" + lastUsed + " topRec#" + recCount);
                        }
                        lastSub = st;
                        lastUsed = used;
                        left -= 1 + used;
                        if (left < 0) {
                            throw new IOException("sub-record overran segment");
                        }
                    }
                } else if (!top.top(tag, len, in)) {
                    skipFully(in, len);
                }
                recCount++;
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

        int typeLen(final int t) {
            return t == 2 ? this.idSize : primLen(t);
        }

        long skipClassDump(final DataInputStream in) throws IOException {
            long used = 0;
            readId(in);
            used += this.idSize;
            in.readInt();
            used += 4;
            readId(in);
            used += this.idSize;
            for (int k = 0; k < 5; k++) {
                readId(in);
            }
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
                used += this.idSize + 1 + typeLen(t);
            }
            n = in.readUnsignedShort();
            used += 2;
            final byte[] scratch = new byte[this.idSize];
            for (int k = 0; k < n; k++) {
                in.readFully(scratch);
                in.read();
                used += this.idSize + 1L;
            }
            return used;
        }

        void pass1() throws IOException {
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> {
                    if (tag == TAG_STRING) {
                        final long id = readId(i);
                        final byte[] b = new byte[len - this.idSize];
                        i.readFully(b);
                        final char[] c = new char[b.length];
                        for (int k = 0; k < b.length; k++) {
                            c[k] = (char) (b[k] & 0xFF);
                        }
                        this.strings.put(id, new String(c));
                        return true;
                    }
                    if (tag == TAG_LOAD_CLASS) {
                        i.readInt();
                        final long clsId = readId(i);
                        i.readInt();
                        final long strId = readId(i);
                        this.classNameStr.put(clsId, strId);
                        return true;
                    }
                    return false;
                }, (tag, i) -> skipSub(tag, i));
            }
            for (final Map.Entry<Long, Long> e : this.classNameStr.entrySet()) {
                final String raw = this.strings.get(e.getValue());
                if (raw != null) {
                    final String n = raw.replace('/', '.');
                    this.classNames.put(e.getKey(), n);
                    if (n.equals(TARGET)) {
                        this.targetClassIds.add(e.getKey());
                    }
                }
            }
            System.out.println("pass1: classes=" + this.classNames.size() + " targetClassIds=" + this.targetClassIds.size());
        }

        long skipSub(final int tag, final DataInputStream in) throws IOException {
            switch (tag) {
                case TAG_CLASS_DUMP -> {
                    return skipClassDump(in);
                }
                case TAG_INSTANCE -> {
                    readId(in);
                    in.readInt();
                    readId(in);
                    final int n = in.readInt();
                    skipFully(in, n);
                    return 2L * this.idSize + 8 + n;
                }
                case TAG_OBJ_ARRAY -> {
                    readId(in);
                    in.readInt();
                    final int n = in.readInt();
                    readId(in);
                    skipFully(in, (long) n * this.idSize);
                    return 2L * this.idSize + 8 + (long) n * this.idSize;
                }
                case TAG_PRIM_ARRAY -> {
                    readId(in);
                    in.readInt();
                    final int n = in.readInt();
                    final int t = in.read();
                    skipFully(in, (long) n * primLen(t));
                    return this.idSize + 9 + (long) n * primLen(t);
                }
                case 0x05, 0xFF -> {
                    skipFully(in, this.idSize);
                    return this.idSize;
                }
                case 0x01 -> {
                    skipFully(in, 2L * this.idSize);
                    return 2L * this.idSize;
                }
                case 0x02, 0x03, 0x08 -> {
                    skipFully(in, this.idSize + 8L);
                    return this.idSize + 8L;
                }
                case 0x04, 0x06, 0x07 -> {
                    skipFully(in, this.idSize + 4L);
                    return this.idSize + 4L;
                }
                default -> {
                    return -1;
                }
            }
        }

        /** pass1.5：解析目标类（及其超类链）的 CLASS_DUMP 实例字段布局。 */
        void pass1_5() throws IOException {
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag != TAG_CLASS_DUMP) {
                        return skipSub(tag, i);
                    }
                    // 读 class dump 并在目标类时捕获字段
                    final long clsId = readId(i);
                    // id 已读：used 必须含 idSize（walk 以 used 推进 left）
                    return this.idSize + parseFullClassDump(i, clsId, this.targetClassIds.contains(clsId));
                });
            }
            for (final Map.Entry<Long, List<FieldLayout>> e : this.targetLayouts.entrySet()) {
                System.out.println("layout " + classNameOf(e.getKey()) + ": " + e.getValue().size() + " inst fields");
                for (final FieldLayout f : e.getValue()) {
                    System.out.println("  " + f.name + " type=" + f.type + " off=" + f.offset);
                }
            }
        }

        long parseFullClassDump(final DataInputStream in, final long clsId, final boolean capture) throws IOException {
            long used = 0;
            in.readInt(); // serial
            used += 4;
            final long sup = readId(in);
            used += this.idSize;
            for (int k = 0; k < 5; k++) {
                readId(in);
            }
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
                used += this.idSize + 1 + typeLen(t);
            }
            n = in.readUnsignedShort();
            used += 2;
            final List<FieldLayout> fields = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                final long nameId = readId(in);
                final int t = in.read();
                used += this.idSize + 1L;
                final FieldLayout f = new FieldLayout();
                f.name = this.strings.get(nameId);
                f.type = t;
                fields.add(f);
            }
            if (capture) {
                this.superOf.put(clsId, sup);
                this.targetLayouts.put(clsId, fields);
            }
            return used;
        }

        /** pass2：目标类实例逐个解码状态组合。 */
        void pass2() throws IOException {
            // 计算字段偏移：超类字段前置（SWMRNibbleArray 的超类是 Object，零实例字段——断言之）
            for (final Map.Entry<Long, List<FieldLayout>> e : this.targetLayouts.entrySet()) {
                final Long sup = this.superOf.get(e.getKey());
                if (sup != null) {
                    final String supName = classNameOf(sup);
                    if (!"java.lang.Object".equals(supName)) {
                        throw new IllegalStateException("unexpected superclass " + supName + " - 前置偏移未实现");
                    }
                }
                int off = 0;
                for (final FieldLayout f : e.getValue()) {
                    f.offset = off;
                    off += typeLen(f.type);
                }
            }
            final Map<String, FieldLayout> byName = new LinkedHashMap<>();
            for (final List<FieldLayout> l : this.targetLayouts.values()) {
                for (final FieldLayout f : l) {
                    byName.put(f.name, f);
                }
            }
            final FieldLayout fSU = byName.get("stateUpdating");
            final FieldLayout fSV = byName.get("stateVisible");
            final FieldLayout fStU = byName.get("storageUpdating");
            final FieldLayout fStV = byName.get("storageVisible");
            final FieldLayout fUD = byName.get("updatingDirty");
            final FieldLayout fUU = byName.get("updatingUniform");
            final FieldLayout fVU = byName.get("visibleUniform");
            if (fSU == null || fSV == null || fStU == null || fStV == null || fUD == null || fUU == null || fVU == null) {
                throw new IllegalStateException("field capture incomplete: " + byName.keySet());
            }
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag != TAG_INSTANCE) {
                        return skipSub(tag, i);
                    }
                    final long id = readId(i);
                    i.readInt();
                    final long clsId = readId(i);
                    final int n = i.readInt();
                    if (!this.targetClassIds.contains(clsId)) {
                        skipFully(i, n);
                        return 2L * this.idSize + 8 + n;
                    }
                    final byte[] b = new byte[n];
                    i.readFully(b);
                    this.instances++;
                    final int su = (int) intAt(b, fSU.offset, fSU.type);
                    final int sv = (int) intAt(b, fSV.offset, fSV.type);
                    final boolean stU = refAt(b, fStU.offset) != 0;
                    final boolean stV = refAt(b, fStV.offset) != 0;
                    final boolean dirty = byteAt(b, fUD.offset) != 0;
                    final int uu = byteAt(b, fUU.offset);
                    final int vu = byteAt(b, fVU.offset);
                    if (stU || stV) {
                        this.withStorage++;
                    }
                    final String key = "state(up=" + su + ",vis=" + sv + ") storage(up=" + (stU ? "Y" : "n")
                        + ",vis=" + (stV ? "Y" : "n") + ") uniform(up=" + uu + ",vis=" + vu + ") dirty=" + (dirty ? "Y" : "n");
                    this.comboCounts.merge(key, 1L, Long::sum);
                    return 2L * this.idSize + 8 + n;
                });
            }
            System.out.println("instances=" + this.instances + " withStorage=" + this.withStorage);
            for (final Map.Entry<String, Long> e : this.comboCounts.entrySet()) {
                System.out.println(String.format("%9d  %s", e.getValue(), e.getKey()));
            }
        }

        long intAt(final byte[] b, final int off, final int type) {
            if (type == 10) { // int 大端
                return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
            }
            throw new IllegalStateException("not int type " + type);
        }

        long refAt(final byte[] b, final int off) {
            long v = 0;
            for (int k = 0; k < this.idSize; k++) {
                v = (v << 8) | (b[off + k] & 0xFFL);
            }
            return v;
        }

        int byteAt(final byte[] b, final int off) {
            return b[off] & 0xFF;
        }

        DataInputStream open() throws IOException {
            return new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file), 1 << 16));
        }
    }

    public static void main(final String[] args) throws Exception {
        final Hprof h = new Hprof(Path.of(args[0]));
        h.pass1();
        h.pass1_5();
        h.pass2();
    }
}
