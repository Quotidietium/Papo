package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 批次114：首 join 停摆复现与现场抓取 harness。
 *
 * 批次113 判定：churn0/churn2 两次复现 boot 后首个 join 时刻的主线程冻结
 * （stall tick=75 gap=599.6ms dur=39.0 + tick=76 gap=388.9 dur=171.4），而
 * JoinPhaseBench2 不复现——本 harness 做 ①可靠复现（同 churn 结构：Done+2s 写
 * gamerule、+4s 首 bot 连接）②**jstack 采样抓现场**（Done 后 +2.5s..+6s 每 60ms
 * 一次，抓停摆窗口内主线程栈——600ms 停摆对 60ms 采样至少命中 10 帧）。
 *
 * 输出：stall 行（0263）+ 命中停摆时刻的 jstack 主线程栈帧（"Server thread" 段）。
 * 门：无（诊断 harness——复现即输出，不复现同样输出零 stall 供判读）。
 *
 * 用法：java papo.bot.FirstJoinStallBench <jar> [runs=1]
 */
public final class FirstJoinStallBench {

    private static final int PORT = 25599;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int runs = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        for (int run = 0; run < runs; run++) {
            System.out.println("======== run " + run + " ========");
            singleRun(jar);
        }
    }

    private static void singleRun(final Path jar) throws Exception {
        final Path dir = Files.createTempDirectory("papo-fjstall-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false",
            "motd=papo-fjstall", "sync-chunk-writes=false",
            "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("spigot.yml"),
            "entity-activation-range:\n  animals: 96\n", StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx4G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
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

        final long doneAt = System.currentTimeMillis();

        // 与 churn 结构一致：Done+2s 写 gamerule；+4s 首 bot 连接
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
            } catch (final Exception ignored) {
            }
        }, "gamerule-writer").start();

        // jstack 采样器：Done+2500ms 起，每 60ms 一次 ×60 帧（覆盖 +2.5s..+6.1s）
        final List<String> stacks = java.util.Collections.synchronizedList(new ArrayList<>());
        final Thread sampler = new Thread(() -> {
            final long pid = server.pid();
            try {
                Thread.sleep(2500);
                for (int i = 0; i < 60; i++) {
                    final Process js = new ProcessBuilder("F:/Java/21/bin/jstack", String.valueOf(pid))
                        .redirectErrorStream(true).start();
                    final StringBuilder sb = new StringBuilder();
                    String l;
                    try (BufferedReader jr = new BufferedReader(new InputStreamReader(js.getInputStream(), StandardCharsets.UTF_8))) {
                        while ((l = jr.readLine()) != null) {
                            sb.append(l).append('\n');
                        }
                    }
                    js.waitFor();
                    // 只保留 Server thread 段（缩样）
                    final String dump = sb.toString();
                    int idx = dump.indexOf("\"Server thread\"");
                    if (idx >= 0) {
                        int end = dump.indexOf("\n\n", idx);
                        stacks.add("=== sample +" + (System.currentTimeMillis() - doneAt) + "ms ===\n"
                            + dump.substring(idx, end > 0 ? end : Math.min(dump.length(), idx + 900)));
                    }
                    Thread.sleep(60);
                }
            } catch (final Exception ignored) {
            }
        }, "jstack-sampler");
        sampler.setDaemon(true);
        sampler.start();

        Thread.sleep(4000); // 首 bot 在 Done+4s 连接（churn 复现时序）
        final List<String> failures = new ArrayList<>();
        final Thread bot = new Thread(() -> {
            try {
                new OfflineJoinBot("127.0.0.1", PORT, "StandB00").joinWalkAndDisconnect(25_000, 0, 0);
            } catch (final Exception e) {
                failures.add(String.valueOf(e));
            }
        }, "bot-anchor");
        bot.setDaemon(true);
        bot.start();

        bot.join(60_000);
        Thread.sleep(3000);
        sampler.join(30_000);

        try {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- stalls ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.stall")) {
                System.out.println(l.substring(l.indexOf("PapoTickProfile.stall")));
            }
        }
        System.out.println("---- jstack Server-thread samples ----");
        for (final String s : stacks) {
            System.out.println(s);
        }
        System.out.println("botFailures=" + failures + " exited=" + (server.exitValue() == 0));
    }
}
