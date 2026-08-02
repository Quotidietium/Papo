import net.neoforged.art.api.Renamer;
import net.neoforged.art.api.Transformer;
import net.neoforged.art.internal.RenamerImpl;
import net.neoforged.srgutils.IMappingFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 独立计时验证：复现 ReobfServer.remap 的 ART 调用，对比 threads(1) vs threads(N)。
 *
 * 用法：java -cp "<run/versions.jar>;<run/libraries/**>" ReobfTiming <reobf.tiny> <input.jar>
 *
 * 复现要点（与 paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java 一致）：
 *   - Renamer.builder().threads(N).add(Transformer.renamerFactory(mappings, false)).build()
 *   - ((RenamerImpl) renamer).run(in, out, true)   // shade 版 3 参 run，与上游调用一致
 * 注意：运行时 ART internal 被 paper-server shade（较新版，含 3 参 run），
 *       故 classpath 必须把含 shade 版的 jar 放前，否则会加载独立 ART 依赖那份（2 参 run）。
 */
public final class ReobfTiming {

    private static long run(final int threads, final IMappingFile mappings, final Path in, final Path out) throws Exception {
        final long start = System.currentTimeMillis();
        try (RenamerImpl renamer = (RenamerImpl) Renamer.builder()
                .threads(threads)
                .add(Transformer.renamerFactory(mappings, false))
                .build()) {
            renamer.run(in.toFile(), out.toFile(), true);
        }
        return System.currentTimeMillis() - start;
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ReobfTiming <reobf.tiny> <input.jar> [rounds=3]");
            System.exit(2);
        }
        final Path mappingsFile = Paths.get(args[0]);
        final Path inputJar = Paths.get(args[1]);
        final int rounds = args.length >= 3 ? Integer.parseInt(args[2]) : 3;
        final int cores = Runtime.getRuntime().availableProcessors();
        final int multi = Math.min(cores, 8);

        System.out.println("cores=" + cores + "  multi(cap=8)=" + multi + "  rounds=" + rounds);
        System.out.println("input=" + inputJar + "  size=" + Files.size(inputJar) + "B");

        final IMappingFile mappings = IMappingFile.load(mappingsFile.toFile());

        // warmup：各一次（触发 JIT、ART setup、OS 文件页缓存）
        final Path w1 = Files.createTempFile("reobf-w1-", ".jar");
        final Path wN = Files.createTempFile("reobf-wN-", ".jar");
        System.out.println("warmup 1-thread=" + run(1, mappings, inputJar, w1) + "ms");
        System.out.println("warmup N-thread=" + run(multi, mappings, inputJar, wN) + "ms");

        // 正式计时：各 rounds 次取最小
        long best1 = Long.MAX_VALUE;
        final Path o1 = Files.createTempFile("reobf-o1-", ".jar");
        for (int i = 0; i < rounds; i++) {
            final long t = run(1, mappings, inputJar, o1);
            best1 = Math.min(best1, t);
            System.out.println("  1-thread round " + i + "=" + t + "ms");
        }
        long bestN = Long.MAX_VALUE;
        final Path oN = Files.createTempFile("reobf-oN-", ".jar");
        for (int i = 0; i < rounds; i++) {
            final long t = run(multi, mappings, inputJar, oN);
            bestN = Math.min(bestN, t);
            System.out.println("  N-thread round " + i + "=" + t + "ms");
        }

        System.out.println("----");
        System.out.printf("threads(1)    best=%d ms%n", best1);
        System.out.printf("threads(%d)   best=%d ms%n", multi, bestN);
        System.out.printf("speedup=%.2fx%n", best1 / (double) bestN);

        // 字节级正确性：threads(1) 产物 vs threads(N) 产物逐 entry sha256
        compareJars(o1, oN);

        for (final Path p : new Path[]{w1, wN, o1, oN}) Files.deleteIfExists(p);
    }

    private static void compareJars(final Path a, final Path b) throws Exception {
        final Map<String, byte[]> ma = digestAll(a);
        final Map<String, byte[]> mb = digestAll(b);
        int same = 0, diff = 0, onlyA = 0, onlyB = 0;
        final List<String> diffNames = new ArrayList<>();
        for (final String k : ma.keySet()) {
            if (!mb.containsKey(k)) { onlyA++; continue; }
            if (Arrays.equals(ma.get(k), mb.get(k))) same++;
            else { diff++; if (diffNames.size() < 15) diffNames.add(k); }
        }
        for (final String k : mb.keySet()) if (!ma.containsKey(k)) onlyB++;
        System.out.printf("[diff] entries same=%d diff=%d onlyIn1=%d onlyInN=%d%n", same, diff, onlyA, onlyB);
        for (final String d : diffNames) System.out.println("  DIFF: " + d);
        if (diff == 0 && onlyA == 0 && onlyB == 0) System.out.println("[correctness] ALL OK: 1-thread 与 N-thread 产物逐 entry 字节一致");
    }

    private static Map<String, byte[]> digestAll(final Path jar) throws Exception {
        final Map<String, byte[]> out = new TreeMap<>();
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            final Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                final ZipEntry ze = e.nextElement();
                if (ze.isDirectory()) continue;
                try (InputStream is = zf.getInputStream(ze)) {
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    final byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    out.put(ze.getName(), md.digest(bos.toByteArray()));
                }
            }
        }
        return out;
    }

    private ReobfTiming() {}
}
