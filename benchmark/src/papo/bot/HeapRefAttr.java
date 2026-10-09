package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次144（R5 轮4）：HPROF 堆转储持有链归因器。
 *
 * 用途：对 MemSoakVerify -Dmemsoak.dump=true 产出的 heap.hprof 回答——
 *  (1) byte[] 的驻留由哪些类的实例字段持有（按引用类归类字节数 Top25）；
 *  (2) java.util.HashMap$Node 簇归谁：经 Node[] 表数组反查持有实例的类
 *      （Top25），并按 (key 类 × value 类) 对统计语义成分 Top25。
 *
 * 记录布局：STRING/LOAD_CLASS 是顶层记录；堆数据（CLASS_DUMP/INSTANCE/
 * OBJ_ARRAY/PRIM_ARRAY + GC 根 0x01-0x09）嵌在 HEAP_DUMP/HEAP_DUMP_SEGMENT
 * (0x0C/0x1C) 体内，子记录无 time/len 头，靠各自定长结构推进。
 *
 * 三遍流式扫描（不整堆载入）：
 *  P1 类元（STRING/LOAD_CLASS + 段内 CLASS_DUMP）；P2 数据（byte[] 清单、
 *  Node 实例 key/value 抽取、Node[] 表数组计数、key/value 目标类解析）；
 *  P3 引用者扫描（实例字段 blob 中查目标 id；Node 经表数组→表持有者归属，
 *  不直接扫节点——next 链会把前驱误当持有者）。
 *
 * 用法：java papo.bot.HeapRefAttr <heap.hprof>
 */
public final class HeapRefAttr {

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: HeapRefAttr <heap.hprof>");
            System.exit(2);
        }
        final long t0 = System.nanoTime();
        final Hprof h = new Hprof(args[0]);
        h.pass2();
        h.pass3();
        System.out.println("parse seconds=" + (System.nanoTime() - t0) / 1_000_000_000);
    }

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    @FunctionalInterface
    interface TopRec {
        /** 消费 len 字节体后返回 true；false = 通用 skip。 */
        boolean top(int tag, int len, DataInputStream in) throws IOException;
    }

    @FunctionalInterface
    interface DumpRec {
        /** 消费一个子记录体（不含 tag 字节）并返回体长；-1 = 未知子记录（报错）。 */
        long dump(int tag, DataInputStream in) throws IOException;
    }

    private static final class Hprof {
        final String path;
        final int idSize;
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classNameStr = new HashMap<>();
        final Map<Long, Cls> classById = new HashMap<>();
        final Map<Long, String> arrayClassNames = new HashMap<>(); // 数组类 id -> 名（[Ljava...; / [J）

        static final class Cls {
            long id, superId;
            String name = "?";
            final List<byte[]> fieldNames = new ArrayList<>();
            final List<Integer> fieldTypes = new ArrayList<>();
        }

        // Node 解析（仅 java.util.HashMap$Node 本体字段布局固定：hash(int) next key value）
        long nodeClsId = -1;
        int nodeKeyOff = -1, nodeValOff = -1;

        // P2 产物
        final LongSet byteIds;
        final LongLong byteLen;
        final LongSet tableIds;
        final LongLong tableCount;   // 表数组 id -> 非空元素数
        final LongLong idToClass;    // key/value 目标 id -> 类 id
        long[] nodeKeys = new long[1 << 20];
        long[] nodeVals = new long[1 << 20];
        int nodePairCount;
        long totalByteArrays, totalBytes, nodeTotal, nodesInTables;

        // P3 产物
        final Map<String, long[]> bytesByReferrer = new HashMap<>();
        final Map<String, long[]> nodesByOwner = new HashMap<>();
        final Map<String, long[]> keyValPairs = new HashMap<>();
        final Map<Long, String> tableOwner = new HashMap<>();
        long attributedBytes, attributedArrays, unattributedBytes, unattributedNodes;

        Hprof(final String path) throws IOException {
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
            this.byteIds = new LongSet(1 << 20);
            this.byteLen = new LongLong(1 << 20);
            this.tableIds = new LongSet(1 << 14);
            this.tableCount = new LongLong(1 << 14);
            this.idToClass = new LongLong(1 << 20);
            pass1();
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

        /** 顶层遍历；0x0C/0x1C 体内按子记录分派（体长精确对账，错位即刻报错）。 */
        void walk(final DataInputStream in, final TopRec top, final DumpRec dump) throws IOException {
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
                    while (left > 0) {
                        final int st = in.read();
                        if (st == -1) {
                            throw new EOFException("eof inside heap dump segment");
                        }
                        final long used = dump.dump(st, in);
                        if (used < 0) {
                            throw new IOException("unknown sub-record 0x" + Integer.toHexString(st) + " left=" + left);
                        }
                        left -= 1 + used;
                        if (left < 0) {
                            throw new IOException("sub-record 0x" + Integer.toHexString(st) + " overran segment: used=" + (used + 1));
                        }
                    }
                } else if (!top.top(tag, len, in)) {
                    skipFully(in, len);
                }
            }
        }

        /** CLASS_DUMP 解析（sink 可为 null 仅跳过）；返回体长（不含 tag 字节）。 */
        long parseClassDump(final DataInputStream in, final Cls c) throws IOException {
            long used = 0;
            final long cid = readId(in);
            used += this.idSize;
            in.readInt();
            used += 4;
            final long sup = readId(in);
            used += this.idSize;
            for (int k = 0; k < 5; k++) {
                readId(in); // loader/signer/prodDomain/reserved×2
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
            n = in.readUnsignedShort(); // 实例字段
            used += 2;
            if (c != null) {
                c.id = cid;
                c.superId = sup;
            }
            for (int k = 0; k < n; k++) {
                final byte[] nm = new byte[this.idSize];
                in.readFully(nm);
                final int t = in.read();
                used += this.idSize + 1L;
                if (c != null) {
                    c.fieldNames.add(nm);
                    c.fieldTypes.add(t);
                }
            }
            return used;
        }

        /** 已知尺寸子记录跳过（读完整个体）；返回体长，未知 tag 返回 -1。 */
        long skipSub(final int tag, final DataInputStream in) throws IOException {
            switch (tag) {
                case TAG_CLASS_DUMP -> {
                    return parseClassDump(in, null);
                }
                case TAG_INSTANCE -> { // id + serial + clsId + u4 n + n
                    readId(in);
                    in.readInt();
                    readId(in);
                    final int n = in.readInt();
                    skipFully(in, n);
                    return 2L * this.idSize + 8 + n;
                }
                case TAG_OBJ_ARRAY -> { // id + serial + u4 n + arrCls + n×id
                    readId(in);
                    in.readInt();
                    final int n = in.readInt();
                    readId(in);
                    skipFully(in, (long) n * this.idSize);
                    return 2L * this.idSize + 8 + (long) n * this.idSize;
                }
                case TAG_PRIM_ARRAY -> { // id + serial + u4 n + type + n×len
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
                case 0x05, 0xFF -> { // ROOT_STICKY_CLASS / ROOT_UNKNOWN：仅 id
                    skipFully(in, this.idSize);
                    return this.idSize;
                }
                case 0x01 -> { // ROOT_JNI_GLOBAL：id + jniRefId
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

        void pass1() throws IOException {
            try (DataInputStream in = open()) {
                walk(in,
                    (tag, len, i) -> {
                        if (tag == TAG_STRING) {
                            final long id = readId(i);
                            final byte[] b = new byte[len - this.idSize];
                            i.readFully(b);
                            this.strings.put(id, new String(b, StandardCharsets.UTF_8));
                            return true;
                        }
                        if (tag == TAG_LOAD_CLASS) {
                            i.readInt();
                            final long clsId = readId(i);
                            i.readInt();
                            final long nameStr = readId(i);
                            this.classNameStr.put(clsId, nameStr);
                            skipFully(i, len - (4 + this.idSize + 4 + this.idSize));
                            return true;
                        }
                        return false;
                    },
                    (tag, i) -> {
                        if (tag == TAG_CLASS_DUMP) {
                            final Cls c = new Cls();
                            final long used = parseClassDump(i, c);
                            this.classById.put(c.id, c);
                            return used;
                        }
                        return skipSub(tag, i);
                    });
            }
            for (final Cls c : this.classById.values()) {
                final Long s = this.classNameStr.get(c.id);
                if (s != null) {
                    final String n = this.strings.get(s);
                    if (n != null) {
                        c.name = n.replace('/', '.');
                    }
                }
            }
            // Node 本体与字段偏移
            for (final Cls c : this.classById.values()) {
                if ("java.util.HashMap$Node".equals(c.name)) {
                    this.nodeClsId = c.id;
                    int off = 0;
                    for (int i = 0; i < c.fieldTypes.size(); i++) {
                        final String fn = str(c.fieldNames.get(i));
                        if ("key".equals(fn)) {
                            this.nodeKeyOff = off;
                        } else if ("value".equals(fn)) {
                            this.nodeValOff = off;
                        }
                        off += typeLen(c.fieldTypes.get(i));
                    }
                }
            }
            if (this.nodeClsId == -1 || this.nodeKeyOff < 0 || this.nodeValOff < 0) {
                final StringBuilder dbg = new StringBuilder("HashMap$Node layout not found (cls=" + this.nodeClsId
                    + " keyOff=" + this.nodeKeyOff + " valOff=" + this.nodeValOff + ") classes:");
                for (final Cls c : this.classById.values()) {
                    if (c.name.contains("HashMap") || c.name.contains("Linked")) {
                        dbg.append(' ').append(c.name).append("[fields=").append(c.fieldTypes.size()).append(']');
                    }
                }
                dbg.append(" strings=").append(this.strings.size()).append(" loadClasses=").append(this.classNameStr.size());
                throw new IOException(dbg.toString());
            }
            for (final Map.Entry<Long, Long> e : this.classNameStr.entrySet()) {
                final String n = this.strings.get(e.getValue());
                if (n != null && !n.isEmpty() && n.charAt(0) == '[') {
                    this.arrayClassNames.put(e.getKey(), n.replace('/', '.'));
                }
            }
        }

        String str(final byte[] idBytes) {
            long id = 0;
            for (final byte bb : idBytes) {
                id = (id << 8) | (bb & 0xFFL);
            }
            final String s = this.strings.get(id);
            return s == null ? "?" : s;
        }

        String classNameOf(final long clsId) {
            final Cls c = this.classById.get(clsId);
            if (c != null) {
                return c.name;
            }
            final String arr = this.arrayClassNames.get(clsId);
            return arr != null ? arr : "?cls" + clsId;
        }

        /** P2：数据遍。 */
        void pass2() throws IOException {
            final LongSet keyValTargets = new LongSet(1 << 20);
            // 2a：byte[] / Node 实例 / Node[] 表数组
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag == TAG_PRIM_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final int t = i.read();
                        if (t == 8) {
                            if (this.byteIds.add(id)) {
                                this.byteLen.put(id, n);
                                this.totalByteArrays++;
                                this.totalBytes += n;
                            }
                        }
                        skipFully(i, (long) n * primLen(t));
                        return this.idSize + 9 + (long) n * primLen(t);
                    }
                    if (tag == TAG_INSTANCE) {
                        readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (clsId == this.nodeClsId) {
                            final byte[] b = new byte[n];
                            i.readFully(b);
                            this.nodeTotal++;
                            final long key = readIdAt(b, this.nodeKeyOff);
                            final long val = readIdAt(b, this.nodeValOff);
                            if (this.nodePairCount == this.nodeKeys.length) {
                                this.nodeKeys = java.util.Arrays.copyOf(this.nodeKeys, this.nodePairCount * 2);
                                this.nodeVals = java.util.Arrays.copyOf(this.nodeVals, this.nodePairCount * 2);
                            }
                            this.nodeKeys[this.nodePairCount] = key;
                            this.nodeVals[this.nodePairCount] = val;
                            this.nodePairCount++;
                            if (key != 0) {
                                keyValTargets.add(key);
                            }
                            if (val != 0) {
                                keyValTargets.add(val);
                            }
                        } else {
                            skipFully(i, n);
                        }
                        return 2L * this.idSize + 8 + n;
                    }
                    if (tag == TAG_OBJ_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final long arrCls = readId(i);
                        if ("[Ljava.util.HashMap$Node;".equals(classNameOf(arrCls))) {
                            long cnt = 0;
                            for (int k = 0; k < n; k++) {
                                if (readId(i) != 0) {
                                    cnt++;
                                }
                            }
                            this.tableIds.add(id);
                            this.tableCount.put(id, cnt);
                            this.nodesInTables += cnt;
                        } else {
                            skipFully(i, (long) n * this.idSize);
                        }
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    return skipSub(tag, i);
                });
            }
            // 2b：key/value 目标的类解析（必须第二遍——单遍内 target 集合尚未完备）
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag == TAG_INSTANCE) {
                        final long id = readId(i);
                        i.readInt();
                        final long clsId = readId(i);
                        final int n = i.readInt();
                        if (keyValTargets.contains(id)) {
                            this.idToClass.put(id, clsId);
                        }
                        skipFully(i, n);
                        return 2L * this.idSize + 8 + n;
                    }
                    if (tag == TAG_OBJ_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final long arrCls = readId(i);
                        if (keyValTargets.contains(id)) {
                            this.idToClass.put(id, -arrCls);
                        }
                        skipFully(i, (long) n * this.idSize);
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    if (tag == TAG_PRIM_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final int t = i.read();
                        if (keyValTargets.contains(id)) {
                            this.idToClass.put(id, ARRAY_PRIM_CLASS - t); // 原始数组按类型编码
                        }
                        skipFully(i, (long) n * primLen(t));
                        return this.idSize + 9 + (long) n * primLen(t);
                    }
                    return skipSub(tag, i);
                });
            }
            for (int i = 0; i < this.nodePairCount; i++) {
                final String k = classOfEncoded(this.nodeKeys[i]);
                final String v = classOfEncoded(this.nodeVals[i]);
                final long[] c = this.keyValPairs.computeIfAbsent(k + " -> " + v, x -> new long[1]);
                c[0]++;
            }
            System.out.println("byte[] arrays=" + this.totalByteArrays + " bytes=" + this.totalBytes);
            System.out.println("nodes=" + this.nodeTotal + " inTables=" + this.nodesInTables
                + " tables=" + this.tableIds.size() + " pairs=" + this.nodePairCount);
        }

        static final long ARRAY_PRIM_CLASS = -1_000_000_000L;

        String classOfEncoded(final long id) {
            if (id == 0) {
                return "null";
            }
            final long enc = this.idToClass.get(id, Long.MIN_VALUE);
            if (enc == Long.MIN_VALUE) {
                return "?";
            }
            if (enc <= ARRAY_PRIM_CLASS) {
                return primTypeName((int) (ARRAY_PRIM_CLASS - enc));
            }
            if (enc < 0) {
                return classNameOf(-enc);
            }
            return classNameOf(enc);
        }

        static String primTypeName(final int t) {
            return switch (t) {
                case 4 -> "[Z";
                case 5 -> "[C";
                case 6 -> "[F";
                case 7 -> "[D";
                case 8 -> "[B";
                case 9 -> "[S";
                case 10 -> "[I";
                case 11 -> "[J";
                default -> "[?";
            };
        }

        /** P3：引用者扫描 + 归属输出。 */
        void pass3() throws IOException {
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (tag, i) -> {
                    if (tag != TAG_INSTANCE) {
                        return skipSub(tag, i);
                    }
                    readId(i);
                    i.readInt();
                    final long clsId = readId(i);
                    final int n = i.readInt();
                    final String cn = classNameOf(clsId);
                    final byte[] b = n > 0 ? new byte[n] : null;
                    if (b != null) {
                        i.readFully(b);
                    }
                    long bytesHere = 0, arraysHere = 0;
                    if (b != null) {
                        for (int off = 0; off + this.idSize <= n; off++) {
                            final long id = readIdAt(b, off);
                            if (id == 0) {
                                continue;
                            }
                            if (this.byteIds.contains(id)) {
                                final long l = this.byteLen.get(id, -1);
                                if (l >= 0) {
                                    bytesHere += l;
                                    arraysHere++;
                                    this.byteIds.remove(id);
                                }
                            }
                            if (this.tableIds.contains(id) && !this.tableOwner.containsKey(id)) {
                                this.tableOwner.put(id, cn);
                            }
                        }
                    }
                    if (bytesHere > 0) {
                        final long[] v = this.bytesByReferrer.computeIfAbsent(cn, x -> new long[2]);
                        v[0] += bytesHere;
                        v[1] += arraysHere;
                        this.attributedBytes += bytesHere;
                        this.attributedArrays += arraysHere;
                    }
                    return 2L * this.idSize + 8 + n;
                });
            }
            // 未被实例字段引用的 byte[]（静态/局部/仅数组持有）
            this.unattributedBytes = 0;
            for (final long id : this.byteIds.toArray()) {
                this.unattributedBytes += this.byteLen.get(id, 0);
            }
            // Node 归属
            for (final Map.Entry<Long, String> e : this.tableOwner.entrySet()) {
                final long cnt = this.tableCount.get(e.getKey(), 0);
                final long[] v = this.nodesByOwner.computeIfAbsent(e.getValue(), x -> new long[1]);
                v[0] += cnt;
            }
            for (final long id : this.tableIds.toArray()) {
                if (!this.tableOwner.containsKey(id)) {
                    this.unattributedNodes += this.tableCount.get(id, 0);
                }
            }
            System.out.println("==== byte[] attributed=" + this.attributedBytes + " (" + this.attributedArrays
                + " arrays) unattributed=" + this.unattributedBytes + " ====");
            dumpTop(this.bytesByReferrer, true);
            System.out.println("==== HashMap$Node inTables=" + this.nodesInTables + " unattributed=" + this.unattributedNodes + " ====");
            dumpTop(this.nodesByOwner, false);
            System.out.println("---- nodeKeyValPairs top ----");
            final List<Map.Entry<String, long[]>> l = new ArrayList<>(this.keyValPairs.entrySet());
            l.sort((a, c) -> Long.compare(c.getValue()[0], a.getValue()[0]));
            for (int i = 0; i < Math.min(25, l.size()); i++) {
                System.out.printf("Attr %-72s count=%,d%n", l.get(i).getKey(), l.get(i).getValue()[0]);
            }
        }

        void dumpTop(final Map<String, long[]> m, final boolean bytesMode) {
            final List<Map.Entry<String, long[]>> l = new ArrayList<>(m.entrySet());
            l.sort((a, c) -> Long.compare(c.getValue()[0], a.getValue()[0]));
            System.out.println("---- " + (bytesMode ? "bytesByReferrer" : "nodesByOwner") + " top ----");
            for (int i = 0; i < Math.min(30, l.size()); i++) {
                if (bytesMode) {
                    System.out.printf("Attr %-62s bytes=%,14d arrays=%,9d%n", l.get(i).getKey(), l.get(i).getValue()[0], l.get(i).getValue()[1]);
                } else {
                    System.out.printf("Attr %-62s count=%,d%n", l.get(i).getKey(), l.get(i).getValue()[0]);
                }
            }
        }

        long readIdAt(final byte[] b, final int off) {
            long v = 0;
            final int n = this.idSize;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | (b[off + i] & 0xFFL);
            }
            return v;
        }

        int typeLen(final int t) {
            return switch (t) {
                case 4, 8 -> 1;   // boolean, byte
                case 5, 9 -> 2;   // char, short
                case 6, 10 -> 4;  // float, int
                case 7, 11 -> 8;  // double, long
                default -> this.idSize; // 2 = object
            };
        }

        static int primLen(final int t) {
            return switch (t) {
                case 4, 8 -> 1;
                case 5, 9 -> 2;
                case 6, 10 -> 4; // 10=int 漏写会按 default 1 少跳 3/4 字节造成段内错位
                case 7, 11 -> 8;
                default -> 1;
            };
        }
    }

    // ---------- long 开放寻址集合/映射 ----------

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
            if (this.size * 2 > this.mask) {
                grow();
            }
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

        boolean remove(final long k) {
            int i = mix(k) & this.mask;
            while (this.used[i]) {
                if (this.keys[i] == k) {
                    this.used[i] = false;
                    this.size--;
                    rehashFrom(i);
                    return true;
                }
                i = (i + 1) & this.mask;
            }
            return false;
        }

        void rehashFrom(final int start) {
            int i = (start + 1) & this.mask;
            while (this.used[i]) {
                final long k = this.keys[i];
                this.used[i] = false;
                this.size--;
                this.add(k);
                i = (i + 1) & this.mask;
            }
        }

        int size() {
            return this.size;
        }

        long[] toArray() {
            final long[] out = new long[this.size];
            int j = 0;
            for (int i = 0; i <= this.mask; i++) {
                if (this.used[i]) {
                    out[j++] = this.keys[i];
                }
            }
            return out;
        }

        void grow() {
            final long[] ok = this.keys;
            final boolean[] ou = this.used;
            final int n = (this.mask + 1) * 2;
            this.keys = new long[n];
            this.used = new boolean[n];
            this.mask = n - 1;
            this.size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    this.add(ok[i]);
                }
            }
        }

        static int mix(final long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) ((h ^ (h >>> 32)) & 0x7FFFFFFF);
        }
    }

    static final class LongLong {
        private long[] keys, vals;
        private boolean[] used;
        private int mask, size;

        LongLong(final int cap) {
            final int n = Integer.highestOneBit(Math.max(16, cap) * 2);
            this.keys = new long[n];
            this.vals = new long[n];
            this.used = new boolean[n];
            this.mask = n - 1;
        }

        void put(final long k, final long v) {
            int i = LongSet.mix(k) & this.mask;
            while (this.used[i]) {
                if (this.keys[i] == k) {
                    this.vals[i] = v;
                    return;
                }
                i = (i + 1) & this.mask;
            }
            this.used[i] = true;
            this.keys[i] = k;
            this.vals[i] = v;
            this.size++;
            if (this.size * 2 > this.mask) {
                grow();
            }
        }

        long get(final long k, final long dflt) {
            int i = LongSet.mix(k) & this.mask;
            while (this.used[i]) {
                if (this.keys[i] == k) {
                    return this.vals[i];
                }
                i = (i + 1) & this.mask;
            }
            return dflt;
        }

        boolean containsKey(final long k) {
            int i = LongSet.mix(k) & this.mask;
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

        void grow() {
            final long[] ok = this.keys, ov = this.vals;
            final boolean[] ou = this.used;
            final int n = (this.mask + 1) * 2;
            this.keys = new long[n];
            this.vals = new long[n];
            this.used = new boolean[n];
            this.mask = n - 1;
            this.size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ou[i]) {
                    this.put(ok[i], ov[i]);
                }
            }
        }
    }

    private HeapRefAttr() {
    }
}
