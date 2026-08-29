package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 批次113：行走探索世界生成稳定性 harness——TPS 波动/闪回的最后一个未测真实服模式。
 *
 * 已排除面（批次113 矩阵）：静态实体/红石负载稳态干净、纯 churn 干净——剩余唯一
 * 未复现向量=**持续新区块生成**：行走玩家触发 worldgen（worker 线程）→ 完成回调
 * 经区块系统集成回主线程（mid-tick drain / tick 边界 poll），是真实服 TPS 突变的
 * 经典来源。本 harness 以 B 个 bot 从锚点向扇形方向持续行走（0.08 块/tick ≈ 1.6m/s
 * ×6min ≈ 900 块 ≈ 56 区块半径的新地形），vd=6 保证前方区块需求流。
 *
 * 门：bot 全活（行走断连即失败）+ logErrors=0 + 正常停机；tickdist 归因由
 * -Dpapo.tickProfile=1 输出。
 *
 * 用法：java papo.bot.WalkGenStabilityBench <jar> [bots=10] [windowMs=360000]
 */
public final class WalkGenStabilityBench {

    private static final int PORT = 25597;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        final long windowMs = args.length > 2 ? Long.parseLong(args[2]) : 360_000;
        final Path dir = Files.createTempDirectory("papo-walkgen-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20),
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-walkgen",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

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

        try {
            server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            System.out.println("SERVER_DIED before gamerule: " + e);
        }
        Thread.sleep(2000);

        // 扇形方向持续行走（0.08 块/tick ≈ 1.6m/s；6min ≈ 900 块新地形）
        final CountDownLatch done = new CountDownLatch(bots);
        final List<RuntimeException> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < bots; i++) {
            final double ang = 2 * Math.PI * i / bots;
            final double dx = Math.cos(ang) * 0.08;
            final double dz = Math.sin(ang) * 0.08;
            final String name = String.format("WalkB%02d", i);
            final Thread t = new Thread(() -> {
                try {
                    new OfflineJoinBot("127.0.0.1", PORT, name).joinWalkAndDisconnect(windowMs, dx, dz);
                } catch (final Exception e) {
                    failures.add(new RuntimeException("bot " + name + ": " + e, e));
                } finally {
                    done.countDown();
                }
            }, "bot-" + name);
            t.setDaemon(true);
            t.start();
            Thread.sleep(700); // 错开 join（churn 尖峰判例：同时 join 放大 boot 窗）
        }

        done.await();
        Thread.sleep(3000);
        final boolean botsOk = failures.isEmpty();
        if (!botsOk) {
            failures.forEach(f -> System.out.println("BOT-FAIL " + f));
        } else {
            System.out.println("all " + bots + " walking bots finished (" + windowMs + "ms, ~"
                + String.format(java.util.Locale.ROOT, "%.0f", 0.08 * windowMs / 1000.0 * 20 / 20) + " blocks traveled each)");
        }
        System.out.println(botsOk ? "bots gate PASS" : "bots gate FAILED");

        try {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- PapoTickProfile windows ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile")) {
                System.out.println(l);
            }
        }
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (!botsOk || errors > 0 || server.exitValue() != 0) {
            System.exit(1);
        }
    }
}
