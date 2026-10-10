package papo.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次156（R16）：shape 族结构重复率普查。
 *
 * R10/R11 将 shape 族（AABB/ArrayVoxelShape/BitSetDiscreteVoxelShape/DoubleArrayList
 * ≈51MB）判为"注册表界恒定成本"——但那是**有界**判据，不是**不可约**判据：族内
 * 结构性相等（如全立方 (0,0,0)-(1,1,1) 在上千个 state 的缓存里各持一份）从未
 * 量化。若重复率高，批 147/149 的规范共享模式（不可变值共享，已验证非红线）
 * 可直接套用。
 *
 * 解析核心逐字复制自 ShellRefAttr（同一批转储上与直方图逐位对账验证过），
 * 仅在 DumpRec 回调上加业务：P2 取 AABB CLASS_DUMP 布局；P3 统计 AABB 六内联
 * double 去重 + 形状族类计数。
 *
 * 用法：java papo.bot.ShapeDupCensus <heap.hprof>
 */
public final class ShapeDupCensus {

    static final int TAG_STRING = 0x01, TAG_LOAD_CLASS = 0x02, TAG_CLASS_DUMP = 0x20;
    static final int TAG_INSTANCE = 0x21, TAG_OBJ_ARRAY = 0x22, TAG_PRIM_ARRAY = 0x23;
    static final int TAG_HEAP_DUMP = 0x0C, TAG_HEAP_DUMP_SEGMENT = 0x1C;

    static final String AABB = "net.minecraft.world.phys.AABB";
    static final String[] COUNT_ONLY = {
        "net.minecraft.world.phys.shapes.ArrayVoxelShape",
        "net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape",
        "net.minecraft.world.phys.shapes.CubeVoxelShape",
        "it.unimi.dsi.fastutil.doubles.DoubleArrayList",
    };

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: ShapeDupCensus <heap.hprof>");
            System.exit(2);
        }
        final long t0 = System.nanoTime();
        final Hprof h = new Hprof(args[0]);
        h.pass1();
        h.pass2Layout();
        h.pass3Census();
        System.out.println("parse seconds=" + (System.nanoTime() - t0) / 1_000_000_000);
    }

    record BoxKey(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    private static final class Hprof {
        final String path;
        final int idSize;
        final Map<Long, String> strings = new HashMap<>();
        final Map<Long, Long> classNameStr = new HashMap<>();
        final Map<Long, String> classNames = new HashMap<>();
        final List<Long> aabbClassIds = new ArrayList<>();
        final Map<Long, String> countIds = new HashMap<>();
        final Map<String, long[]> counts = new HashMap<>();
        final int[] aabbOff = new int[6];
        int aabbInstanceSize = -1;
        final Map<BoxKey, long[]> boxes = new HashMap<>(1 << 19);
        long aabbTotal;
        long classDumpsSeen;

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
            for (final String co : COUNT_ONLY) {
                this.counts.put(co, new long[1]);
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

        // ===== walk/skipSub/skipClassDump/primLen/typeLen：逐字复制自 ShellRefAttr =====

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

        // ===== 业务 =====

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
                    if (n.equals(AABB)) {
                        this.aabbClassIds.add(e.getKey());
                    }
                    for (final String co : COUNT_ONLY) {
                        if (n.equals(co)) {
                            this.countIds.put(e.getKey(), co);
                        }
                    }
                }
            }
            if (this.aabbClassIds.isEmpty()) {
                throw new IOException("AABB class not found; classNames=" + this.classNames.size());
            }
        }

        /** P2：全量走查中解析每个 CLASS_DUMP，命中 AABB 时抽取字段布局。 */
        void pass2Layout() throws IOException {
            final String[] want = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (st, i) -> {
                    if (st != TAG_CLASS_DUMP) {
                        return skipSub(st, i);
                    }
                    // —— 与 skipClassDump 同构，但保留实例字段的 nameId/type 供 AABB 抽取 ——
                    long used = 0;
                    final long clsId = readId(i);
                    used += this.idSize;
                    i.readInt();
                    used += 4;
                    readId(i);
                    used += this.idSize;
                    for (int k = 0; k < 5; k++) {
                        readId(i);
                    }
                    used += 5L * this.idSize;
                    final int instSize = i.readInt();
                    used += 4;
                    int n = i.readUnsignedShort();
                    used += 2;
                    for (int k = 0; k < n; k++) {
                        i.readUnsignedShort();
                        final int t = i.read();
                        skipFully(i, typeLen(t));
                        used += 3 + typeLen(t);
                    }
                    n = i.readUnsignedShort();
                    used += 2;
                    for (int k = 0; k < n; k++) {
                        readId(i);
                        final int t = i.read();
                        skipFully(i, typeLen(t));
                        used += this.idSize + 1 + typeLen(t);
                    }
                    n = i.readUnsignedShort();
                    used += 2;
                    final long[] nameIds = new long[n];
                    final int[] types = new int[n];
                    for (int k = 0; k < n; k++) {
                        nameIds[k] = readId(i);
                        types[k] = i.read();
                    }
                    used += (long) n * (this.idSize + 1L);
                    this.classDumpsSeen++;
                    if (this.aabbClassIds.contains(clsId) && this.aabbInstanceSize < 0) {
                        Arrays.fill(this.aabbOff, -1);
                        int off = 0;
                        for (int k = 0; k < n; k++) {
                            final String fn = this.strings.get(nameIds[k]);
                            for (int w = 0; w < 6; w++) {
                                if (want[w].equals(fn)) {
                                    if (types[k] != 7) {
                                        throw new IOException("AABB." + fn + " not double: type=" + types[k]);
                                    }
                                    this.aabbOff[w] = off;
                                }
                            }
                            off += typeLen(types[k]);
                        }
                        this.aabbInstanceSize = instSize;
                    }
                    return used;
                });
            }
            if (this.aabbInstanceSize < 0) {
                throw new IOException("AABB CLASS_DUMP not found: classDumpsSeen=" + this.classDumpsSeen);
            }
            System.out.println("ShapeDup.layout instanceSize=" + this.aabbInstanceSize
                + " offsets=" + Arrays.toString(this.aabbOff) + " classDumps=" + this.classDumpsSeen);
            for (final int o : this.aabbOff) {
                if (o < 0) {
                    throw new IOException("AABB field missing");
                }
            }
        }

        void pass3Census() throws IOException {
            try (DataInputStream in = open()) {
                walk(in, (tag, len, i) -> false, (st, i) -> {
                    if (st != TAG_INSTANCE) {
                        return skipSub(st, i);
                    }
                    readId(i);
                    i.readInt();
                    final long clsId = readId(i);
                    final int n = i.readInt();
                    final boolean isAabb = this.aabbClassIds.contains(clsId);
                    final String cn = this.countIds.get(clsId);
                    if (isAabb || cn != null) {
                        final byte[] b = new byte[n];
                        i.readFully(b);
                        if (isAabb) {
                            tally(b);
                        }
                        if (cn != null) {
                            this.counts.get(cn)[0]++;
                        }
                    } else {
                        skipFully(i, n);
                    }
                    return 2L * this.idSize + 8 + n;
                });
            }
            report();
        }

        double dblAt(final byte[] b, final int off) {
            long v = 0;
            for (int k = 0; k < 8; k++) {
                v = (v << 8) | (b[off + k] & 0xFFL);
            }
            return Double.longBitsToDouble(v);
        }

        void tally(final byte[] blob) {
            this.aabbTotal++;
            this.boxes.computeIfAbsent(new BoxKey(
                dblAt(blob, this.aabbOff[0]), dblAt(blob, this.aabbOff[1]), dblAt(blob, this.aabbOff[2]),
                dblAt(blob, this.aabbOff[3]), dblAt(blob, this.aabbOff[4]), dblAt(blob, this.aabbOff[5])), x -> new long[1])[0]++;
        }

        void report() {
            long dupInstances = 0;
            final List<Map.Entry<BoxKey, long[]>> es = new ArrayList<>(this.boxes.entrySet());
            es.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            for (final Map.Entry<BoxKey, long[]> e : es) {
                dupInstances += e.getValue()[0] - 1;
            }
            final long perBytes = this.aabbInstanceSize > 0 ? this.aabbInstanceSize : 64;
            System.out.println("ShapeDup.aabb total=" + this.aabbTotal
                + " distinct=" + this.boxes.size()
                + " dupInstances=" + dupInstances
                + " dupRate=" + String.format("%.1f%%", 100.0 * dupInstances / Math.max(1, this.aabbTotal))
                + " perBytes=" + perBytes
                + " dupBytes=" + dupInstances * perBytes);
            System.out.println("---- top 20 tuples ----");
            for (int k = 0; k < Math.min(20, es.size()); k++) {
                final BoxKey x = es.get(k).getKey();
                System.out.printf("ShapeDup.top %7dx  (%s, %s, %s)-(%s, %s, %s)%n",
                    es.get(k).getValue()[0], x.minX, x.minY, x.minZ, x.maxX, x.maxY, x.maxZ);
            }
            for (final Map.Entry<String, long[]> e : this.counts.entrySet()) {
                System.out.println("ShapeDup.cls " + e.getKey() + " instances=" + e.getValue()[0]);
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
}
