package papo.bot;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 批次83：批次82 join 读侧预取——真实服务器端到端冒烟 + A/B 对拍。
 *
 * 每个受测 jar：fresh 目录 boot（offline mode，固定 seed，view/sim 6）→ bot join 序列 →
 * stop → exit 0 + 日志零异常核验 → playerdata/stats/advancements 产物校验。
 * join 序列：
 *   #1 首次加入（空数据——批次82 全部回退路径）
 *   #2 关服即重连（socket 关闭后 0ms——批次79 异步 quit 存档在飞，读后写排序实战）
 *   #3..#10 稳态重连（真实 .dat/stats/adv 文件——批次82 预取命中路径）
 * 计时：connect → 首个 play 包（= placeNewPlayer 完成，含批次82 消费点）。
 *
 * 用法：java papo.bot.SmokeJoinVerify <jar1> <jar2> ...
 */
public final class SmokeJoinVerify {

    private static final int PORT = 25599;
    private static final int JOINS = 10;
    private static final String BOT_NAME = "PapoBot01";

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: SmokeJoinVerify <jar> [<jar>...]");
            System.exit(2);
        }
        final Path workRoot = Files.createTempDirectory("papo-smoke-");
        try {
            final List<Result> results = new ArrayList<>();
            for (final String jar : args) {
                results.add(runOne(workRoot, Path.of(jar)));
            }
            System.out.println();
            System.out.println("=== A/B summary (steady-state joins #3..#" + JOINS + ") ===");
            System.out.printf("%-12s %-12s %-12s %-12s%n", "jar", "mean(ms)", "p50(ms)", "max(ms)");
            for (final Result r : results) {
                System.out.printf("%-12s %-12.1f %-12.1f %-12.1f%n", r.label,
                    mean(r.steady), p50(r.steady), max(r.steady));
            }
        } finally {
            Files.walk(workRoot).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final IOException ignored) {}
            });
        }
    }

    private record Result(String label, List<Long> steady) {}

    private static Result runOne(final Path workRoot, final Path jar) throws Exception {
        final String label = jar.getFileName().toString().replace("Papo-1.21.11-", "").replace(".jar", "");
        final Path dir = workRoot.resolve(label);
        Files.createDirectories(dir);
        Files.copy(jar, dir.resolve(jar.getFileName()));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false",
            "server-port=" + PORT,
            "level-seed=papo82",
            "view-distance=6",
            "simulation-distance=6",
            "spawn-protection=0",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "motd=papo-smoke",
            "sync-chunk-writes=false",
            "enforce-secure-profile=false",
            ""), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("=== boot " + label + " (" + jar.getFileName() + ") ===");
        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8", "-jar", jar.getFileName().toString(), "nogui")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();

        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        boolean done = false;
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
            String line;
            while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
                logLines.add(line);
                if (line.contains("Done (")) {
                    done = true;
                    System.out.println("  boot: " + line.trim() + " (+" + (System.nanoTime() - bootStart) / 1_000_000 + "ms)");
                    break;
                }
                if (line.contains("ERROR") || line.contains("Exception")) {
                    System.out.println("  [boot] " + line.trim());
                }
            }
        } catch (final IOException e) {
            System.out.println("  log read: " + e);
        }
        if (!done) {
            server.destroyForcibly();
            throw new IllegalStateException("server did not reach Done: " + label);
        }

        // 后台继续收集日志（join 期间）
        final Thread logTail = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    logLines.add(line);
                }
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        logTail.setDaemon(true);
        logTail.start();

        // ---- join 序列 ----
        final List<Long> joins = new ArrayList<>();
        Exception botFailure = null;
        try {
            for (int i = 1; i <= JOINS; i++) {
                final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, BOT_NAME);
                final long[] t = bot.joinAndDisconnect(i == 1 ? 1500 : 500);
                joins.add(t[2]);
                System.out.printf("  join#%02d  connect=%dms loginAck=%dms spawn=%dms%n", i, t[0], t[1], t[2]);
                if (i == 1) {
                    // 即时重连：quit 存档（异步）在飞 → 读后写排序实战
                } else {
                    Thread.sleep(i == 2 ? 0 : 400);
                }
            }
        } catch (final Exception e) {
            botFailure = e;
        }

        // ---- 关服 ----
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        final boolean exited = server.waitFor(120, TimeUnit.SECONDS);
        final int exitCode = server.exitValue();
        logTail.join(5000);

        // ---- 核验 ----
        final UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + BOT_NAME).getBytes(StandardCharsets.UTF_8));
        final Path dat = dir.resolve("world/playerdata/" + uuid + ".dat");
        final Path stats = dir.resolve("world/stats/" + uuid + ".json");
        final Path adv = dir.resolve("world/advancements/" + uuid + ".json");
        final String datCheck = checkGzipNbt(dat);
        final String statsCheck = Files.exists(stats) ? "ok" : "MISSING";
        final String advCheck = Files.exists(adv) ? "ok" : "MISSING";

        final long errors = logLines.stream().filter(l -> l.contains("ERROR") || l.contains("Exception")).count();
        final boolean joinedAll = joins.size() == JOINS;

        System.out.println();
        System.out.println("  result " + label + ":");
        System.out.println("    joins=" + joins.size() + "/" + JOINS + (botFailure != null ? "  BOT FAILURE: " + botFailure : ""));
        System.out.println("    shutdown: exited=" + exited + " exitCode=" + exitCode + " logErrors=" + errors);
        System.out.println("    artifacts: dat=" + datCheck + " stats=" + statsCheck + " advancements=" + advCheck);
        if (errors > 0) {
            logLines.stream().filter(l -> l.contains("ERROR") || l.contains("Exception")).distinct().limit(8).forEach(l -> System.out.println("      " + l.trim()));
        }
        if (!exited || exitCode != 0 || errors > 0 || !joinedAll || botFailure != null
            || !"ok".equals(statsCheck) || !"ok".equals(advCheck) || !"ok".equals(datCheck)) {
            // 保底：把日志落盘供事后分析
            Files.write(workRoot.resolve(label + "-server.log"), logLines);
            throw new IllegalStateException("SMOKE FAILED for " + label
                + " (exited=" + exited + " exit=" + exitCode + " errors=" + errors + " joins=" + joins.size()
                + " dat=" + datCheck + " stats=" + statsCheck + " adv=" + advCheck
                + (botFailure != null ? " bot=" + botFailure : "") + ") — log saved next to temp dir");
        }

        return new Result(label, joins.subList(2, joins.size()));
    }

    private static String checkGzipNbt(final Path dat) {
        try {
            if (!Files.exists(dat)) {
                return "MISSING";
            }
            try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(dat), 8192)) {
                final byte[] head = gz.readNBytes(2);
                if (head.length == 2 && head[0] == 0x0A) {
                    return "ok";
                }
                return "BAD-NBT(magic=" + Integer.toHexString(head[0] & 0xFF) + ")";
            }
        } catch (final Exception e) {
            return "BAD(" + e.getClass().getSimpleName() + ")";
        }
    }

    private static double mean(final List<Long> v) {
        return v.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static double p50(final List<Long> v) {
        final List<Long> s = new ArrayList<>(v);
        s.sort(Comparator.naturalOrder());
        return s.isEmpty() ? 0 : s.get(s.size() / 2);
    }

    private static double max(final List<Long> v) {
        return v.stream().mapToLong(Long::longValue).max().orElse(0);
    }
}
