package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次151（R11）：HPROF 壳实例持有链归因器。
 *
 * HeapRefAttr 只归因基本类型数组（byte[]/long[]/Object[]）与 HashMap$Node；
 * 轮11 需要回答"某个类的 N 个存活实例各由谁持有"（DoubleArrayList 291,843 /
 * Object2ObjectOpenHashMap 29,813 / BitSet 混合成分等——无玩家腿证明它们是永
 * 久集，须按持有者归类判定是否注册表界）。
 *
 * 两遍流式扫描（HPROF 无拓扑序，收集与扫描必须分遍）：P1 类名（STRING/
 * LOAD_CLASS）；P2 目标类（含数组类）实例 id 收集；P3 全实例字段 blob + 全对
 * 象数组元素扫描，目标 id 命中即计入 (目标类 × 引用者类)，输出归属数 Top25。
 * 引用计数≠归属实例数（同一实例可被多处引用）——按命中次数输出，直方图级结
 * 论不受扰。id 在 blob 内按 idSize 对齐扫描，与 HeapRefAttr 同容忍度（长字段
 * 值伪命中影响与既有工具一致）。
 *
 * 用法：java papo.bot.ShellRefAttr <heap.hprof> <完全限定类名>...
 */
public final class ShellRefAttr {

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: ShellRefAttr <heap.hprof> <TargetClass>...");
            System.exit(2);
        }
        final long t0 = System.nanoTime();
        final Hprof h = new Hprof(args[0], List.of(args).subList(1, args.length));
        h.pass2();
        h.pass3();
        System.out.println("parse seconds=" + (System.nanoTime() - t0) / 1_000_000_000);
    }

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    private static final class Target {
        final String clsName;
        final LongSet classIds = new LongSet(8);
        LongSet instanceIds = new LongSet(1 << 19);
        final Map<String, long[]> byReferrer = new HashMap<>(); // 引用者类 -> 命中次数
        long instances;
        long refTotal;

        Target(final String clsName) {
            this.clsName = clsName;
        }
    }

    private static final class Hprof {
        final String path;
        final int idSize;
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classNameStr = new HashMap<>();
        final Map<Long, String> classNames = new HashMap<>(); // 类 id -> 名（含数组类）
        final Target[] targets;

        Hprof(final String path, final List<String> targetNames) throws IOException {
            this.path = path;
            try (DataInputStream in = open()) {
                final StringBuilder ver = new StringBuilder();
                int b;
                while ((b = in.read()) != 0 && b != -1) {
                    ver.append((char) b);
                }
                this.idSize = in.readInt();
                in.readInt();
                in.readInt();
                if (!ver.toString().startsWith("JAVA PROFILE")) {
                    throw new IOException("not an hprof dump: " + ver);
                }
            }
            final List<Target> ts = new ArrayList<>();
            for (final String n : targetNames) {
                ts.add(new Target(n));
            }
            this.targets = ts.toArray(new Target[0]);
            pass1();
            for (final Target t : this.targets) {
                if (t.classIds.size() == 0) {
                    throw new IOException("class not found in dump: " + t.clsName);
                }
            }
        }

        DataInputStream open() throws IOException {
            return new DataInputStream(new BufferedInputStream(new FileInputStream(this.path), 8 << 20));
        }

        long readId(final DataInputStream in) throws IOException {
            return this.idSize == 8 ? in.readLong() : (in.readInt() & 0xFFFFFFFFL);
        }

        void skipFully(final DataInputStream in, final long n) throws IOException {
            long left = n;
            while (left > 0) {
                final long s = in.skip(left);
                if (s <= 0) {
                    if (in.read() == -1) {
                        throw new EOFException("eof in skip");
                    }
                    left--;
                } else {
                    left -= s;
                }
            }
        }

        void skipHeader(final DataInputStream in) throws IOException {
            while (in.read() != 0) { /* 版本串 */ }
            in.readInt();
            in.readInt();
            in.readInt();
        }

        String classNameOf(final long clsId) {
            final String n = this.classNames.get(clsId);
            return n != null ? n : "?cls" + clsId;
        }

        long recCount;

        void walk(final DataInputStream in, final TopRec top, final DumpRec dump) throws IOException {
            this.recCount = 0;
            skipHeader(in);
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

        /** 字段值长度：type 2=object → idSize，其余 primLen（CLASS_DUMP 常量池/静态字段可含对象型）。 */
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
            int n = in.readUnsignedShort(); // 常量池
            used += 2;
            for (int k = 0; k < n; k++) {
                in.readUnsignedShort();
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += 3 + typeLen(t);
            }
            n = in.readUnsignedShort(); // 静态字段
            used += 2;
            for (int k = 0; k < n; k++) {
                readId(in);
                final int t = in.read();
                skipFully(in, typeLen(t));
                used += this.idSize + 1 + typeLen(t);
            }
            n = in.readUnsignedShort(); // 实例字段：name id + type u1（无值字节）
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
                        i.readInt(); // serial u4
                        final long clsId = readId(i);
                        i.readInt(); // stack trace serial u4
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
                    for (final Target t : this.targets) {
                        if (n.equals(t.clsName) || n.equals("[L" + t.clsName + ";")) {
                            t.classIds.add(e.getKey());
                        }
                    }
                }
            }
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
                // 根子记录编号取 HotSpot heapDumper.cpp 实际写入值（较旧规格文本整体偏移）：
                // JNI_GLOBAL=0x01 JNI_LOCAL=0x02 JAVA_FRAME=0x03 NATIVE_STACK=0x04
                // STICKY_CLASS=0x05 THREAD_BLOCK=0x06 MONITOR_USED=0x07 THREAD_OBJECT=0x08 UNKNOWN=0xFF
                case 0x05, 0xFF -> { // 仅 id
                    skipFully(in, this.idSize);
                    return this.idSize;
                }
                case 0x01 -> { // JNI_GLOBAL：id + jniRefId
                    skipFully(in, 2L * this.idSize);
                    return 2L * this.idSize;
                }
                case 0x02, 0x03, 0x08 -> { // JNI_LOCAL / JAVA_FRAME / THREAD_OBJECT：id + u4 + u4
                    skipFully(in, this.idSize + 8L);
                    return this.idSize + 8L;
                }
                case 0x04, 0x06, 0x07 -> { // NATIVE_STACK / THREAD_BLOCK / MONITOR_USED：id + u4
                    skipFully(in, this.idSize + 4L);
                    return this.idSize + 4L;
                }
                default -> {
                    return -1;
                }
            }
        }

        long readIdAt(final byte[] b, final int off) {
            if (this.idSize == 8) {
                long v = 0;
                for (int k = 0; k < 8; k++) { // HPROF 大端
                    v = (v << 8) | (b[off + k] & 0xFFL);
                }
                return v;
            }
            return ((b[off] & 0xFFL)) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
        }

        /** P2：目标类实例 id 收集（HPROF 无拓扑序，必须先全量收集再扫描）。 */
        void pass2() throws IOException {
            for (final Target t : this.targets) {
                t.instanceIds = new LongSet(1 << 19);
                t.instances = 0;
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
                    skipFully(i, n);
                    for (final Target t : this.targets) {
                        if (t.classIds.contains(clsId)) {
                            t.instanceIds.add(id);
                            t.instances++;
                        }
                    }
                    return 2L * this.idSize + 8 + n;
                });
            }
        }

        /** P3：全实例字段 blob + 全对象数组元素扫描，命中计入引用者。 */
        void pass3() throws IOException {
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (n > 0) {
                            final byte[] b = new byte[n];
                            i.readFully(b);
                            scanBlob(b, classNameOf(clsId));
                            return 2L * this.idSize + 8 + n;
                        }
                        return 2L * this.idSize + 8;
                    }
                    if (tag == TAG_OBJ_ARRAY) {
                        readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final long arrCls = readId(i);
                        if (n > 0) {
                            final byte[] b = new byte[n * this.idSize];
                            i.readFully(b);
                            scanBlob(b, classNameOf(arrCls));
                            return 2L * this.idSize + 8 + (long) b.length;
                        }
                        return 2L * this.idSize + 8;
                    }
                    return skipSub(tag, i);
                });
            }
            report();
        }

        void scanBlob(final byte[] b, final String holder) {
            for (int off = 0; off + this.idSize <= b.length; off++) {
                final long id = readIdAt(b, off);
                if (id == 0) {
                    continue;
                }
                for (final Target t : this.targets) {
                    if (t.instanceIds.contains(id)) {
                        t.byReferrer.computeIfAbsent(holder, x -> new long[1])[0]++;
                        t.refTotal++;
                    }
                }
            }
        }

        void report() {
            for (final Target t : this.targets) {
                System.out.println("==== " + t.clsName + " instances=" + t.instances + " refsHit=" + t.refTotal + " ====");
                final List<Map.Entry<String, long[]>> es = new ArrayList<>(t.byReferrer.entrySet());
                es.sort((a, b2) -> Long.compare(b2.getValue()[0], a.getValue()[0]));
                long sum = 0;
                for (int k = 0; k < Math.min(25, es.size()); k++) {
                    System.out.printf("  %9d  %s%n", es.get(k).getValue()[0], es.get(k).getKey());
                    sum += es.get(k).getValue()[0];
                }
                System.out.println("  (top25-sum=" + sum + ")");
            }
        }
    }

    @FunctionalInterface
    interface TopRec {
        boolean top(int tag, int len, DataInputStream in) throws IOException;
    }

    @FunctionalInterface
    interface DumpRec {
        long dump(int tag, DataInputStream in) throws IOException;
    }

    // long 开放寻址集合（与 HeapRefAttr 同实现策略）
    static final class LongSet {
        private long[] keys;
        private boolean[] used;
        private int mask, size;

        LongSet(final int cap) {
            final int n = Integer.highestOneBit(Math.max(16, cap) * 2);
            this.keys = new long[n];
            this.used = new boolean[n];
            this.mask = n - 1;
        }

        boolean add(final long k) {
            int i = mix(k) & this.mask;
            while (this.used[i]) {
                if (this.keys[i] == k) {
                    return false;
                }
                i = (i + 1) & this.mask;
            }
            this.used[i] = true;
            this.keys[i] = k;
            this.size++;
            return true;
        }

        boolean contains(final long k) {
            int i = mix(k) & this.mask;
            while (this.used[i]) {
                if (this.keys[i] == k) {
                    return true;
                }
                i = (i + 1) & this.mask;
            }
            return false;
        }

        int size() {
            return this.size;
        }

        static int mix(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            return (int) (k ^ (k >>> 32));
        }
    }
}
