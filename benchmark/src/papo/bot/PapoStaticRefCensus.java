package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Papo 批次159（R19 对抗复核）：静态字段引用普查——[D 12.3MB 中仅 1.5MB 实例可达，
// 其余可能由 CLASS_DUMP 静态字段持有（判常量）或藏收割面。工具收集全部 [D/[B/[J
// 数组 id 与全部类的静态字段引用，交叉归因。HPROF 行走复制自 SWMRStateCensus/ShellRefAttr。
// 用法：java papo.bot.PapoStaticRefCensus <heap.hprof>
public final class PapoStaticRefCensus {

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    interface DumpRec { long dump(int tag, DataInputStream in) throws IOException; }

    // 数组 id -> (类型描述, 字节数)
    static final class ArrInfo { final String kind; final long bytes; ArrInfo(String k, long b) { this.kind = k; this.bytes = b; } }

    static final class Hprof {
        final Path file;
        int idSize;
        final Map<Long, String> strings = new LinkedHashMap<>();
        final Map<Long, Long> classNameStr = new LinkedHashMap<>();
        final Map<Long, String> classNames = new LinkedHashMap<>();
        final Map<Long, ArrInfo> arrTargets = new LinkedHashMap<>(); // [D/[B/[J 数组
        final Map<Long, List<long[]>> staticRefsByClass = new LinkedHashMap<>(); // classId -> [(nameId, refId)...]
        final Map<String, long[]> byStaticHolder = new TreeMap<>(); // 类名 -> [bytes, count]
        final long[] rootTotalByType = new long[256]; // 根类型 -> 总数
        final long[] rootDByType = new long[256]; // 根类型直指 [D 的数量
        final long[] rootDBytesByType = new long[256];
        final long[] rootBByType = new long[256];
        final long[] rootJByType = new long[256];
        // walk2：对象数组元素边——外层数组类 -> [D bytes, [D n, [B bytes, [B n, [J bytes, [J n
        final Map<Long, long[]> arrElemByOuter = new LinkedHashMap<>();
        long elemDBytes, elemDCount, elemBBytes, elemBCount, elemJBytes, elemJCount;
        // walk3/4：目标 id 集与持有者归因（[[D 外层 + 指定实例类探针）
        final java.util.HashSet<Long> ddOuterIds = new java.util.HashSet<>();
        final java.util.HashSet<Long> probeIds = new java.util.HashSet<>();
        final Map<String, long[]> ddHolder = new LinkedHashMap<>(); // 持有者类 -> [外层引用次数]
        final java.util.ArrayList<long[]> rootIds = new java.util.ArrayList<>(); // (tag, id)
        long ddOuterCount, ddOuterBytes, probeCount;
        long statTotalBytes, statTotalCount;

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

        int typeLen(final int t) {
            return t == 2 ? this.idSize : primLen(t);
        }

        void walk(final DataInputStream in, final DumpRec dump) throws IOException {
            while (in.read() != 0) { } // 版本串
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
            long recCount = 0;
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
                        if (used < 0) { throw new IOException("unknown sub 0x" + Integer.toHexString(st) + " rec#" + recCount); }
                        left -= 1 + used;
                        if (left < 0) { throw new IOException("sub-record overran segment"); }
                    }
                } else {
                    // 只关心 STRING/LOAD_CLASS 之外的跳过；STRING/LOAD_CLASS 在子处理器外单独 pass
                    skipFully(in, len);
                }
                recCount++;
            }
        }

        long skipSub(final int tag, final DataInputStream in) throws IOException {
            switch (tag) {
                case TAG_CLASS_DUMP -> { return parseClassDump(in, false); }
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

        /** CLASS_DUMP：capture=true 时捕获静态字段的引用值（id 或静态原始值跳过）。 */
        long parseClassDump(final DataInputStream in, final boolean capture) throws IOException {
            long used = 0;
            final long clsId = readId(in);
            used += this.idSize;
            in.readInt(); // serial
            used += 4;
            readId(in); // superclass
            used += this.idSize;
            for (int k = 0; k < 5; k++) { readId(in); }
            used += 5L * this.idSize;
            in.readInt(); // instance size
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
            final List<long[]> statics = capture ? new ArrayList<>(n) : null;
            for (int k = 0; k < n; k++) {
                final long nameId = readId(in);
                final int t = in.read();
                final int len = typeLen(t);
                long refId = 0;
                if (t == 2) { // 对象型静态：值是 id
                    refId = readId(in);
                } else {
                    skipFully(in, len);
                }
                used += this.idSize + 1L + len;
                if (capture && refId != 0) {
                    statics.add(new long[]{nameId, refId});
                }
            }
            if (capture && !statics.isEmpty()) {
                this.staticRefsByClass.put(clsId, statics);
            }
            n = in.readUnsignedShort(); // 实例字段
            used += 2;
            for (int k = 0; k < n; k++) {
                readId(in);
                in.read();
                used += this.idSize + 1L;
            }
            return used;
        }

        void run() throws IOException {
            // pass A：字符串 + 类名 + [D/[B/[J 目标数组 + 静态引用（一趟两记录型）
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_CLASS_DUMP) {
                        return parseClassDump(i, true);
                    }
                    if (tag == TAG_PRIM_ARRAY) {
                        final long id = readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final int t = i.read();
                        final long bytes = 24L + (long) n * primLen(t);
                        if (t == 7) { // double
                            this.arrTargets.put(id, new ArrInfo("[D", bytes));
                        } else if (t == 8) {
                            this.arrTargets.put(id, new ArrInfo("[B", bytes));
                        } else if (t == 11) {
                            this.arrTargets.put(id, new ArrInfo("[J", bytes));
                        }
                        skipFully(i, (long) n * primLen(t));
                        return this.idSize + 9 + (long) n * primLen(t);
                    }
                    if (isRoot(tag)) {
                        return parseRoot(tag, i);
                    }
                    return skipSub(tag, i);
                });
            }
            // walk2：对象数组元素边（HeapRefAttr 只读 Object[]/HashMap$Node[] 两类——
            // 类型化引用数组（含 [[D、ArrayList elementData 以外的 V[] 等）的边是系统性盲区）。
            // arrTargets 已完备，此趟逐元素 id 交叉，按外层数组类记账。
            try (DataInputStream in = open()) {
                walk(in, (tag, i) -> {
                    if (tag == TAG_OBJ_ARRAY) {
                        readId(i);
                        i.readInt();
                        final int n = i.readInt();
                        final long outerCls = readId(i);
                        long[] tally = null;
                        for (int k = 0; k < n; k++) {
                            final long el = readId(i);
                            if (el == 0) { continue; }
                            final ArrInfo info = this.arrTargets.get(el);
                            if (info == null) { continue; }
                            if (tally == null) {
                                tally = this.arrElemByOuter.computeIfAbsent(outerCls, x -> new long[6]);
                            }
                            switch (info.kind) {
                                case "[D" -> { tally[0] += info.bytes; tally[1]++; this.elemDBytes += info.bytes; this.elemDCount++; }
                                case "[B" -> { tally[2] += info.bytes; tally[3]++; this.elemBBytes += info.bytes; this.elemBCount++; }
                                case "[J" -> { tally[4] += info.bytes; tally[5]++; this.elemJBytes += info.bytes; this.elemJCount++; }
                            }
                        }
                        return 2L * this.idSize + 8 + (long) n * this.idSize;
                    }
                    return skipSub(tag, i);
                });
            }
            // pass B：字符串表与类名（parseClassDump 的 nameId 解析需要）
            // 注：HPROF 无拓扑序，字符串可能晚于类转储出现——两遍：先跑上面的收集（静态值只存 id），
            // 再扫字符串，最后交叉。
            try (DataInputStream in = open()) {
                long[] header = skipHeaderOf(in);
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
                if (raw != null) {
                    this.classNames.put(e.getKey(), raw.replace('/', '.'));
                }
            }
            // walk3：收集 [[D 外层数组 id + 探针类实例 id（类名经 pass B 已可解析）
            Long ddClsBox = null, probeClsBox = null;
            final String probeName = System.getProperty("probe", "net.minecraft.world.level.levelgen.NoiseChunk");
            for (final Map.Entry<Long, String> e : this.classNames.entrySet()) {
                if ("[[D".equals(e.getValue()) && ddClsBox == null) { ddClsBox = e.getKey(); }
                if (probeName.equals(e.getValue()) && probeClsBox == null) { probeClsBox = e.getKey(); }
            }
            if (ddClsBox != null) {
                final long ddCls = ddClsBox;
                final Long probeCls = probeClsBox;
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_OBJ_ARRAY) {
                            final long id = readId(i);
                            i.readInt();
                            final int n = i.readInt();
                            final long outerCls = readId(i);
                            if (outerCls == ddCls) {
                                this.ddOuterIds.add(id);
                                this.ddOuterCount++;
                                this.ddOuterBytes += 24L + 4L * n + 8L; // 头+元素(压缩指针)+长度域
                            }
                            skipFully(i, (long) n * this.idSize);
                            return 2L * this.idSize + 8 + (long) n * this.idSize;
                        }
                        if (tag == TAG_INSTANCE && probeCls != null) {
                            final long id = readId(i);
                            i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            if (clsId == probeCls) {
                                this.probeIds.add(id);
                                this.probeCount++;
                            }
                            skipFully(i, n);
                            return 2L * this.idSize + 8 + n;
                        }
                        return skipSub(tag, i);
                    });
                }
                // walk4：逐字节滑动窗口扫实例 blob（hprof 按声明序序列化，1B 字段可使引用错位，
                // 对齐窗口会漏）与对象数组元素，找目标集合的引用者
                try (DataInputStream in = open()) {
                    walk(in, (tag, i) -> {
                        if (tag == TAG_INSTANCE) {
                            readId(i);
                            i.readInt();
                            final long clsId = readId(i);
                            final int n = i.readInt();
                            if (n > 0) {
                                final byte[] blob = new byte[n];
                                i.readFully(blob);
                                long hitsD = 0, hitsP = 0;
                                for (int off = 0; off + this.idSize <= n; off++) {
                                    final long v = idAt(blob, off);
                                    if (v == 0) { continue; }
                                    if (this.ddOuterIds.contains(v)) { hitsD++; }
                                    if (this.probeIds.contains(v)) { hitsP++; }
                                }
                                if (hitsD > 0 || hitsP > 0) {
                                    final String cn = this.classNames.getOrDefault(clsId, "?cls" + clsId);
                                    if (hitsD > 0) {
                                        this.ddHolder.computeIfAbsent("[[D]<-" + cn, x -> new long[1])[0] += hitsD;
                                    }
                                    if (hitsP > 0) {
                                        this.ddHolder.computeIfAbsent("Probe<-" + cn, x -> new long[1])[0] += hitsP;
                                    }
                                }
                            }
                            return 2L * this.idSize + 8 + n;
                        }
                        if (tag == TAG_OBJ_ARRAY) {
                            readId(i);
                            i.readInt();
                            final int n = i.readInt();
                            final long outerCls = readId(i);
                            long hitsD = 0, hitsP = 0;
                            for (int k = 0; k < n; k++) {
                                final long v = readId(i);
                                if (v == 0) { continue; }
                                if (this.ddOuterIds.contains(v)) { hitsD++; }
                                if (this.probeIds.contains(v)) { hitsP++; }
                            }
                            if (hitsD > 0 || hitsP > 0) {
                                final String cn = this.classNames.getOrDefault(outerCls, "?cls" + outerCls);
                                if (hitsD > 0) {
                                    this.ddHolder.computeIfAbsent("[[D]<-arr:" + cn, x -> new long[1])[0] += hitsD;
                                }
                                if (hitsP > 0) {
                                    this.ddHolder.computeIfAbsent("Probe<-arr:" + cn, x -> new long[1])[0] += hitsP;
                                }
                            }
                            return 2L * this.idSize + 8 + (long) n * this.idSize;
                        }
                        return skipSub(tag, i);
                    });
                }
            }
            // 交叉：静态引用 -> 目标数组
            for (final Map.Entry<Long, List<long[]>> e : this.staticRefsByClass.entrySet()) {
                final String cls = this.classNames.getOrDefault(e.getKey(), "?cls" + e.getKey());
                long bytes = 0, count = 0;
                for (final long[] nv : e.getValue()) {
                    final ArrInfo info = this.arrTargets.get(nv[1]);
                    if (info != null) {
                        bytes += info.bytes;
                        count++;
                    }
                }
                if (count > 0) {
                    final long[] v = this.byStaticHolder.computeIfAbsent(cls, x -> new long[3]);
                    v[0] += bytes; v[1] += count; v[2] += e.getValue().size();
                    this.statTotalBytes += bytes;
                    this.statTotalCount += count;
                }
            }
            long dBytes = 0, bBytes = 0, jBytes = 0; long dN = 0, bN = 0, jN = 0;
            for (final ArrInfo info : this.arrTargets.values()) {
                switch (info.kind) {
                    case "[D" -> { dBytes += info.bytes; dN++; }
                    case "[B" -> { bBytes += info.bytes; bN++; }
                    case "[J" -> { jBytes += info.bytes; jN++; }
                }
            }
            System.out.printf("targets: [D %d/%dB  [B %d/%dB  [J %d/%dB%n", dN, dBytes, bN, bBytes, jN, jBytes);
            System.out.printf("static-attributed total: %d arrays / %d bytes%n", this.statTotalCount, this.statTotalBytes);
            long rootD = 0, rootDB = 0, rootB = 0, rootJ = 0;
            for (int t = 0; t < 256; t++) {
                if (this.rootTotalByType[t] == 0 && this.rootDByType[t] == 0) { continue; }
                System.out.printf("root 0x%02X: total=%d  ->D %d/%dB  ->B %d  ->J %d%n", t, this.rootTotalByType[t],
                    this.rootDByType[t], this.rootDBytesByType[t], this.rootBByType[t], this.rootJByType[t]);
                rootD += this.rootDByType[t]; rootDB += this.rootDBytesByType[t];
                rootB += this.rootBByType[t]; rootJ += this.rootJByType[t];
            }
            System.out.printf("root-direct total: ->D %d/%dB  ->B %d  ->J %d%n", rootD, rootDB, rootB, rootJ);
            // walk2 输出：对象数组元素边，按外层数组类，[D 字节降序
            final List<Map.Entry<Long, long[]>> elems = new ArrayList<>(this.arrElemByOuter.entrySet());
            elems.sort((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[2] + b.getValue()[4], a.getValue()[0] + a.getValue()[2] + a.getValue()[4]));
            System.out.printf("objarray-elem total: ->D %d/%dB  ->B %d/%dB  ->J %d/%dB%n",
                this.elemDCount, this.elemDBytes, this.elemBCount, this.elemBBytes, this.elemJCount, this.elemJBytes);
            for (int k = 0; k < Math.min(20, elems.size()); k++) {
                final Map.Entry<Long, long[]> e = elems.get(k);
                final long[] v = e.getValue();
                if (v[0] + v[2] + v[4] < 65536) { break; }
                System.out.printf("%12d D(%dB/%d) B(%dB/%d) J(%dB/%d)  outer=%s%n",
                    v[0] + v[2] + v[4], v[0], v[1], v[2], v[3], v[4], v[5],
                    this.classNames.getOrDefault(e.getKey(), "?cls" + e.getKey()));
            }
            long statD = 0;
            for (final long[] v : this.byStaticHolder.values()) { statD += v[0]; }
            System.out.printf("[D remainder after statics+roots+objarray-elems: %dB of %dB total%n",
                dBytes - statD - rootDB - this.elemDBytes, dBytes);
            System.out.printf("[[D outer arrays: %d / ~%dB; probe instances: %d; holder attribution (slide-scan):%n",
                this.ddOuterCount, this.ddOuterBytes, this.probeCount);
            // 静态字段直指 [[D 外层（此前静态交叉只查 [D/[B/[J 内层——外层是洞）
            for (final Map.Entry<Long, List<long[]>> e : this.staticRefsByClass.entrySet()) {
                long hits = 0;
                for (final long[] nv : e.getValue()) {
                    if (this.ddOuterIds.contains(nv[1])) { hits++; }
                }
                if (hits > 0) {
                    this.ddHolder.computeIfAbsent("[[D]<-static:" + this.classNames.getOrDefault(e.getKey(), "?cls" + e.getKey()), x -> new long[1])[0] += hits;
                }
            }
            for (final long[] ri : this.rootIds) {
                if (this.ddOuterIds.contains(ri[1])) {
                    this.ddHolder.computeIfAbsent("[[D]<-root:0x" + Long.toHexString(ri[0]), x -> new long[1])[0]++;
                }
                if (this.probeIds.contains(ri[1])) {
                    this.ddHolder.computeIfAbsent("Probe<-root:0x" + Long.toHexString(ri[0]), x -> new long[1])[0]++;
                }
            }
            final List<Map.Entry<String, long[]>> hs = new ArrayList<>(this.ddHolder.entrySet());
            hs.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (int k = 0; k < Math.min(20, hs.size()); k++) {
                System.out.printf("%12d refs  %s%n", hs.get(k).getValue()[0], hs.get(k).getKey());
            }
            for (final Map.Entry<String, long[]> e : this.byStaticHolder.entrySet()) {
                if (e.getValue()[0] >= 65536) {
                    System.out.printf("%12d bytes %6d arrs  %s (statics=%d)%n", e.getValue()[0], e.getValue()[1], e.getKey(), e.getValue()[2]);
                }
            }
        }

        private long[] skipHeaderOf(final DataInputStream in) throws IOException {
            while (in.read() != 0) { }
            this.idSize = in.readInt();
            in.readInt();
            in.readInt();
            return new long[]{this.idSize};
        }

        static boolean isRoot(final int tag) {
            return tag == 0xFF || (tag >= 0x01 && tag <= 0x08);
        }

        /** 根记录：首 id 是被根持有的对象；尾随布局与 skipSub 尺寸表一致（经全量行走验证）。 */
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
            skipFully(in, rest);
            this.rootTotalByType[tag]++;
            this.rootIds.add(new long[]{tag, id});
            final ArrInfo info = this.arrTargets.get(id);
            if (info != null) {
                switch (info.kind) {
                    case "[D" -> { this.rootDByType[tag]++; this.rootDBytesByType[tag] += info.bytes; }
                    case "[B" -> this.rootBByType[tag]++;
                    case "[J" -> this.rootJByType[tag]++;
                }
            }
            return this.idSize + rest;
        }

        DataInputStream open() throws IOException {
            return new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file), 1 << 16));
        }
    }

    public static void main(final String[] args) throws Exception {
        new Hprof(Path.of(args[0])).run();
    }
}
