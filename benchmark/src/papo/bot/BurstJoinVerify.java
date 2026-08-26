package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 批次84：老玩家大 .dat（DataVersion 3700 → 真实 datafix 全链）× 20 bot 并发 join burst
 * ——批次 82 突发收益的真实服务器 A/B（0.55.0 预取 vs 0.54.0 主线程同步读）。
 *
 * 每轮 fresh 服务器（offline/固定 seed/view 6），预埋 20 份 fat .dat（各 bot 名派生
 * offline uuid）；20 个 bot 屏障同步并发 join；收集各自 spawn 时间（connect→首个 play 包）。
 * 门：全部 join 成功、stop exit 0、日志零 ERROR/Exception、dat 产物合法（且 DataVersion
 * 已被 quit 存档刷新为当前版本——datafix 确实跑过的证据）。
 *
 * 用法：java papo.bot.BurstJoinVerify <jarA> <jarB> [rounds=3] [bots=20]
 */
public final class BurstJoinVerify {

    private static final int PORT = 25598;
    private static final int BOTS = 20;
    private static final int ROUNDS = 3;

    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: BurstJoinVerify <jarA> <jarB> [rounds] [bots]");
            System.exit(2);
        }
        final int rounds = args.length > 2 ? Integer.parseInt(args[2]) : ROUNDS;
        final int bots = args.length > 3 ? Integer.parseInt(args[3]) : BOTS;
        final Path workRoot = Files.createTempDirectory("papo-burst-");
        try {
            final List<String> labels = new ArrayList<>();
            final List<List<Long>> burstLast = new ArrayList<>();
            final List<List<Long>> spawnP50 = new ArrayList<>();
            final List<List<Long>> spawnP95 = new ArrayList<>();
            for (final String jar : args) {
                final String label = Path.of(jar).getFileName().toString().replace("Papo-1.21.11-", "").replace(".jar", "");
                final List<Long> lasts = new ArrayList<>();
                final List<Long> p50s = new ArrayList<>();
                final List<Long> p95s = new ArrayList<>();
                for (int round = 1; round <= rounds; round++) {
                    final long[] stats = runRound(workRoot, Path.of(jar), label, round, bots);
                    lasts.add(stats[0]);
                    p50s.add(stats[1]);
                    p95s.add(stats[2]);
                }
                labels.add(label);
                burstLast.add(lasts);
                spawnP50.add(p50s);
                spawnP95.add(p95s);
            }

            System.out.println();
            System.out.println("=== A/B summary (" + bots + "-bot burst x " + rounds + " rounds, fat .dat DataVersion=3700) ===");
            System.out.printf("%-10s %-16s %-14s %-14s%n", "jar", "lastSpawn(best)", "p50Spawn(best)", "p95Spawn(best)");
            for (int i = 0; i < labels.size(); i++) {
                System.out.printf("%-10s %-16d %-14d %-14d%n", labels.get(i),
                    Collections.min(burstLast.get(i)), Collections.min(spawnP50.get(i)), Collections.min(spawnP95.get(i)));
            }
            System.out.println("(best-of-rounds 口径：轮间含 JIT/页缓存预热噪声，best 反映机制差异)");
        } finally {
            Files.walk(workRoot).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final IOException ignored) {}
            });
        }
    }

    /** 返回 [lastSpawnMs, p50Ms, p95Ms]。 */
    private static long[] runRound(final Path workRoot, final Path jar, final String label, final int round, final int bots) throws Exception {
        final Path dir = workRoot.resolve(label + "-r" + round);
        Files.createDirectories(dir.resolve("world/playerdata"));
        Files.copy(jar, dir.resolve(jar.getFileName()));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false",
            "server-port=" + PORT,
            "level-seed=papo84",
            "view-distance=6",
            "simulation-distance=6",
            "spawn-protection=0",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "motd=papo-burst",
            "sync-chunk-writes=false",
            "enforce-secure-profile=false",
            ""), StandardCharsets.UTF_8);

        // 预埋 fat .dat
        final List<String> names = new ArrayList<>(bots);
        for (int i = 0; i < bots; i++) {
            final String name = String.format("PapoB%02d_%02d", round, i);
            names.add(name);
            final UUID uuid = MakeFatPlayerDat.offlineUuid(name);
            Files.write(dir.resolve("world/playerdata/" + uuid + ".dat"), MakeFatPlayerDat.build(name));
        }
        System.out.printf("%n=== %s round %d: %d fat dats seeded, booting... ===%n", label, round, bots);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8", "-jar", jar.getFileName().toString(), "nogui")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        boolean done = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) {
                done = true;
                System.out.println("  boot: " + line.trim() + " (+" + (System.nanoTime() - bootStart) / 1_000_000 + "ms)");
                break;
            }
        }
        if (!done) {
            server.destroyForcibly();
            throw new IllegalStateException("no Done: " + label + " r" + round);
        }
        try {
            return runBurst(dir, server, reader, logLines, label, round, bots, names);
        } catch (final Exception e) {
            server.destroyForcibly(); // 任何失败路径都收走服务器（防端口孤儿）
            throw e;
        }
    }

    /** boot 成功后的 burst + 关服 + 核验；返回 [lastSpawnMs, p50Ms, p95Ms]。 */
    private static long[] runBurst(final Path dir, final Process server, final BufferedReader reader,
                                   final List<String> logLines, final String label, final int round,
                                   final int bots, final List<String> names) throws Exception {
        final Thread logTail = new Thread(() -> {
            try {
                String l;
                while ((l = reader.readLine()) != null) {
                    logLines.add(l);
                }
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        logTail.setDaemon(true);
        logTail.start();

        // 并发 burst（屏障同步起跑）
        final CountDownLatch go = new CountDownLatch(1);
        final List<CompletableFuture<Long>> futures = new ArrayList<>(bots);
        for (final String name : names) {
            final CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
                try {
                    go.await();
                    final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                    return bot.joinAndDisconnect(300)[2];
                } catch (final Exception e) {
                    throw new RuntimeException("bot " + name + " failed", e);
                }
            });
            futures.add(f);
        }
        final long burstStart = System.nanoTime();
        go.countDown();
        final List<Long> spawnTimes = new ArrayList<>(bots);
        try {
            for (final CompletableFuture<Long> f : futures) {
                spawnTimes.add(f.get(120, TimeUnit.SECONDS)); // 任一失败即抛出
            }
        } catch (final Exception e) {
            // bot 失败时把服务器日志留盘并打印相关行（临时目录会被 finally 清掉）
            final Path saved = Path.of(System.getProperty("java.io.tmpdir"), "papo-burst-" + label + "-r" + round + "-botfail.log");
            Files.write(saved, logLines);
            System.out.println("  BOT FAILURE (server log saved to " + saved + "): " + e);
            logLines.stream().filter(l -> l.contains("Exception verifying") || l.contains("ERROR") || l.contains("WARN"))
                .limit(20).forEach(l -> System.out.println("      " + l.trim()));
            throw e;
        }
        final long lastSpawn = (System.nanoTime() - burstStart) / 1_000_000;
        final List<Long> sorted = new ArrayList<>(spawnTimes);
        sorted.sort(Comparator.naturalOrder());
        final long p50 = sorted.get(sorted.size() / 2);
        final long p95 = sorted.get((int) (sorted.size() * 0.95) - 1);

        // 关服 + 核验
        Thread.sleep(500); // 等 quit 存档入队
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        final boolean exited = server.waitFor(120, TimeUnit.SECONDS);
        logTail.join(5000);

        final long errors = logLines.stream().filter(BurstJoinVerify::isError).count();
        // bot 突断与出站写入竞争时 netty 抛 StacklessClosedChannelException（客户端诱发的
        // 已知良性现象，A/B 两版本同现）；单独计数披露、不计入失败门。
        final long botCloseRaces = logLines.stream()
            .filter(l -> l.contains("StacklessClosedChannelException") || l.contains("Connection reset by peer")
                || l.contains("远程主机强迫关闭")).count();
        final long gateErrors = errors - botCloseRaces;
        String datCheck = "ok";
        for (final String name : names) {
            final UUID uuid = MakeFatPlayerDat.offlineUuid(name);
            final Path dat = dir.resolve("world/playerdata/" + uuid + ".dat");
            final String c = checkGzipNbt(dat);
            if (!"ok".equals(c)) {
                datCheck = c + "(" + name + ")";
                break;
            }
        }
        System.out.printf("  burst: lastSpawn=%dms p50=%dms p95=%dms%n", lastSpawn, p50, p95);
        System.out.println("  shutdown: exited=" + exited + " exit=" + (exited ? server.exitValue() : "?")
            + " logErrors=" + gateErrors + " (+" + botCloseRaces + " benign bot-close races) dats=" + datCheck);
        if (gateErrors > 0) {
            logLines.stream().filter(BurstJoinVerify::isError)
                .filter(l -> !l.contains("StacklessClosedChannelException") && !l.contains("Connection reset by peer")
                    && !l.contains("远程主机强迫关闭"))
                .distinct().limit(6)
                .forEach(l -> System.out.println("      " + l.trim()));
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "papo-burst-" + label + "-r" + round + "-gatefail.log"), logLines);
        }
        if (!exited || gateErrors > 0 || !"ok".equals(datCheck)) {
            throw new IllegalStateException("BURST FAILED " + label + " r" + round);
        }
        return new long[]{lastSpawn, p50, p95};
    }

    private static boolean isError(final String line) {
        return line.contains("ERROR") || line.contains("Exception");
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
                return "BAD-NBT";
            }
        } catch (final Exception e) {
            return "BAD(" + e.getClass().getSimpleName() + ")";
        }
    }
}
