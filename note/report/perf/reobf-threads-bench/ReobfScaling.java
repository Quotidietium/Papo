import net.neoforged.art.api.Renamer;
import net.neoforged.art.api.Transformer;
import net.neoforged.art.internal.RenamerImpl;
import net.neoforged.srgutils.IMappingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** threads scaling：固定输入与 mappings，循环测不同 threads 值，定位瓶颈是否可并行。 */
public final class ReobfScaling {

    private static long run(final int threads, final IMappingFile m, final Path in, final Path out) throws Exception {
        final long s = System.currentTimeMillis();
        try (RenamerImpl r = (RenamerImpl) Renamer.builder()
                .threads(threads)
                .logger(s -> {})
                .debug(s -> {})
                .add(Transformer.renamerFactory(m, false))
                .build()) {
            r.run(in.toFile(), out.toFile(), true);
        }
        return System.currentTimeMillis() - s;
    }

    public static void main(final String[] a) throws Exception {
        final Path mf = Paths.get(a[0]);
        final Path in = Paths.get(a[1]);
        final IMappingFile m = IMappingFile.load(mf.toFile());
        final int cores = Runtime.getRuntime().availableProcessors();
        System.out.println("cores=" + cores + "  input=" + Files.size(in) + "B");

        final Path tmp = Files.createTempFile("reobf-scale-", ".jar");
        // warmup：threads=1 跑 3 次稳定 JIT + OS 文件缓存
        for (int i = 0; i < 3; i++) System.out.println("warmup " + i + "=" + run(1, m, in, tmp) + "ms");

        final int[] ts = {1, 2, 4, 8, 16, 32};
        final int R = 3;
        System.out.printf("%-9s %-9s %-9s%n", "threads", "best(ms)", "mean(ms)");
        for (final int t : ts) {
            long best = Long.MAX_VALUE, sum = 0;
            for (int i = 0; i < R; i++) {
                final long x = run(t, m, in, tmp);
                best = Math.min(best, x);
                sum += x;
            }
            System.out.printf("%-9d %-9d %-9d%n", t, best, sum / R);
        }
        Files.deleteIfExists(tmp);
    }

    private ReobfScaling() {}
}
