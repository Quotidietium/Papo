package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * 批次87：join 管线主线程串行面成本分解。
 *
 * 单 bot 稳态重连（文件已存在、区块已加载）× N：打印包级相位时间戳（LOGIN/CONFIG/PLAY），
 * 归并出相位间隙分布——区分"tick 量化等待"（50ms 谐波间隙）与"实际处理时间"。
 * 再跑 20-bot 并发 burst 一次，对比串行放大。
 *
 * 用法：java papo.bot.JoinPhaseBench <jar> [steadyN=12]
 */
public final class JoinPhaseBench {

    private static final int PORT = 25595;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int steadyN = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final Path dir = Files.createTempDirectory("papo-phase-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo87",
            "view-distance=6", "simulation-distance=6", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-phase",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) {
                System.out.println("boot: " + line.trim());
                break;
            }
        }
        final Thread tail = new Thread(() -> {
            try {
                String l;
                while ((l = reader.readLine()) != null) {
                    logLines.add(l);
                }
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        tail.setDaemon(true);
        tail.start();
        Thread.sleep(2000);

        try {
            // join#1 建档 + 稳态重连 ×N（带 trace）
            final List<List<String>> traces = new ArrayList<>();
            final List<Long> spawns = new ArrayList<>();
            for (int i = 0; i <= steadyN; i++) {
                final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, "PhaseBot01");
                final long[] t = bot.joinAndDisconnect(i == 0 ? 1500 : 600, true);
                if (i > 0) { // 丢弃首连（建档+JIT 预热）
                    traces.add(new ArrayList<>(bot.getPhaseTrace()));
                    spawns.add(t[2]);
                }
                Thread.sleep(400);
            }
            spawns.sort(null);
            System.out.printf("%n-- steady rejoin x%d (spawn median=%dms) --%n", steadyN, spawns.get(spawns.size() / 2));
            final List<String> medianTrace = medianTrace(traces);
            long prev = 0;
            for (final String entry : medianTrace) {
                final int at = entry.lastIndexOf(':');
                final long ms = Long.parseLong(entry.substring(at + 1, entry.length() - 2));
                System.out.printf("  %-22s t=%4dms  gap=%4dms%n", entry, ms, ms - prev);
                prev = ms;
            }

            // 20-bot burst（带 trace，取最后完成的 bot）
            System.out.println("-- 20-bot burst (slowest bot trace) --");
            final List<java.util.concurrent.CompletableFuture<OfflineJoinBot>> bots = new ArrayList<>();
            final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 20; i++) {
                final String name = String.format("PhaseB%02d", i);
                bots.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinAndDisconnect(400, true);
                        return bot;
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            go.countDown();
            OfflineJoinBot slowest = null;
            long slowestSpawn = -1;
            final long burstStart = System.nanoTime();
            try {
                for (final var f : bots) {
                    final OfflineJoinBot b = f.get(120, TimeUnit.SECONDS);
                    final long spawn = parseSpawn(b.getPhaseTrace());
                    if (spawn > slowestSpawn) {
                        slowestSpawn = spawn;
                        slowest = b;
                    }
                }
            } catch (final Exception e) {
                final Path saved = Path.of(System.getProperty("java.io.tmpdir"), "papo-phase-botfail.log");
                Files.write(saved, logLines);
                System.out.println("BURST BOT FAILURE (log saved to " + saved + "): " + e);
                logLines.stream().filter(l -> l.contains("ERROR") || l.contains("Exception") || l.contains("Warn"))
                    .limit(25).forEach(l -> System.out.println("      " + l.trim()));
                throw e;
            }
            System.out.printf("burst lastSpawn=%dms; slowest bot trace:%n", (System.nanoTime() - burstStart) / 1_000_000);
            long prev2 = 0;
            for (final String entry : slowest.getPhaseTrace()) {
                final int at = entry.lastIndexOf(':');
                final long ms = Long.parseLong(entry.substring(at + 1, entry.length() - 2));
                System.out.printf("  %-22s t=%4dms  gap=%4dms%n", entry, ms, ms - prev2);
                prev2 = ms;
            }
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(60, TimeUnit.SECONDS);
        }
        final long errors = logLines.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("logErrors=" + errors);
    }

    private static long parseSpawn(final List<String> trace) {
        for (final String e : trace) {
            if (e.startsWith("PLAY:")) {
                final int at = e.lastIndexOf(':');
                return Long.parseLong(e.substring(at + 1, e.length() - 2));
            }
        }
        return -1;
    }

    /** 逐相位取中位（按 状态:id 键归并）。 */
    private static List<String> medianTrace(final List<List<String>> traces) {
        final Map<String, List<Long>> byPhase = new TreeMap<>();
        for (final List<String> trace : traces) {
            for (final String entry : trace) {
                final int at = entry.lastIndexOf(':');
                byPhase.computeIfAbsent(entry.substring(0, at), k -> new ArrayList<>())
                    .add(Long.parseLong(entry.substring(at + 1, entry.length() - 2)));
            }
        }
        // 保持单条 trace 的相位顺序：以第一条 trace 的顺序为模板
        final List<String> out = new ArrayList<>();
        for (final String entry : traces.get(traces.size() / 2)) {
            final int at = entry.lastIndexOf(':');
            final String key = entry.substring(0, at);
            final List<Long> v = byPhase.get(key);
            v.sort(null);
            out.add(key + ":" + v.get(v.size() / 2) + "ms");
        }
        return out;
    }
}
