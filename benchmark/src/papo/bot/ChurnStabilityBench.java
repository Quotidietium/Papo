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
 * 批次113：玩家 churn 稳定性 harness——TPS 波动/交互闪回的复现场景。
 *
 * 真实服与静态 soak 的关键差异是**玩家持续进出**：登录路径（prepare-spawn/事件化
 * 改造，批次87/88）+ chunk 发送管线 + 离开时的保存/卸载在时间轴上叠加，可能产生
 * 周期性 tick 尖峰（0263 直方图可见）。本 harness 以 S 个 bot 槽位循环
 * join→dwell→disconnect，churn 周期 P 秒，错开启动形成连续到达流。
 *
 * 在场门：窗口结束时全体槽位要么离线要么存活（无卡死连接）；churn 轮数 ≥ 预期
 * 的 80%（join 失败即门失败）。tickdist 输出由 -Dpapo.tickProfile=1 附带。
 *
 * 用法：java papo.bot.ChurnStabilityBench <jar> [slots=10] [dwellMs=30000]
 *       [windowMs=360000] [entities=0]
 * （entities>0 时叠加实体负载：清场后 summon N 头 NoAI 牛于出生平台——静态底盘）
 */
public final class ChurnStabilityBench {

    private static final int PORT = 25596;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int slots = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        final int dwellMs = args.length > 2 ? Integer.parseInt(args[2]) : 30_000;
        final long windowMs = args.length > 3 ? Long.parseLong(args[3]) : 360_000;
        final int entities = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        final Path dir = Files.createTempDirectory("papo-churn-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(slots * 2, 20),
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-churn",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
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

        try {
            server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            System.out.println("SERVER_DIED before gamerule: " + e);
        }
        Thread.sleep(2000);

        // 常驻锚点 bot（平台锚 + 计数）：整个窗口不离场
        final Thread anchor = new Thread(() -> {
            try {
                new OfflineJoinBot("127.0.0.1", PORT, "StandB00").joinWalkAndDisconnect(windowMs + 90_000, 0, 0);
            } catch (final Exception ignored) {
            }
        }, "bot-anchor");
        anchor.setDaemon(true);
        anchor.start();
        Thread.sleep(15_000); // 锚点进场稳定

        // 可选实体底盘（NoAI 静态，churn 之外的恒定负载）
        if (entities > 0) {
            final StringBuilder sb = new StringBuilder();
            final int side = (int) Math.ceil(Math.sqrt(entities));
            for (int i = 0; i < entities; i++) {
                final double gx = (i % side) - (side - 1) / 2.0;
                final double gz = (i / side) - (side - 1) / 2.0;
                sb.append("execute at StandB00 run summon cow ~")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", gx * 64.0 / side))
                    .append(" ~ ~")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", gz * 64.0 / side))
                    .append(" {NoAI:1b,Tags:[\"papoCow\"]}\n");
            }
            // 分块节流写入（批次112 stdin 判例）
            final String[] lines = sb.toString().split("\n", -1);
            for (int i = 0; i < lines.length; i += 50) {
                final StringBuilder chunkSb = new StringBuilder();
                for (int j = i; j < Math.min(i + 50, lines.length); j++) {
                    chunkSb.append(lines[j]).append('\n');
                }
                server.getOutputStream().write(chunkSb.toString().getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                Thread.sleep(40);
            }
            System.out.println("summoned " + entities + " NoAI cows (static base)");
            Thread.sleep(10_000);
        }

        // churn 循环：slots 个槽位，各错开 dwellMs/slots 启动，join→dwell→断开→重连
        final long cyclesTarget = Math.max(1, windowMs / (dwellMs + 2_000));
        final java.util.concurrent.atomic.AtomicLong cyclesDone = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong joinFails = new java.util.concurrent.atomic.AtomicLong();
        final CountDownLatch churners = new CountDownLatch(slots);
        for (int s = 0; s < slots; s++) {
            final int idx = s;
            final long offset = (long) (dwellMs / (double) slots) * s;
            final Thread t = new Thread(() -> {
                try {
                    Thread.sleep(offset);
                } catch (final InterruptedException ignored) {
                }
                final long deadline = System.currentTimeMillis() + windowMs - 20_000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        new OfflineJoinBot("127.0.0.1", PORT, String.format("ChurnB%02d", idx))
                            .joinAndDisconnect(dwellMs);
                        cyclesDone.incrementAndGet();
                    } catch (final Exception e) {
                        joinFails.incrementAndGet();
                    }
                }
                churners.countDown();
            }, "churn-" + s);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(windowMs);
        churners.await(60, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(3000);
        final long done = cyclesDone.get();
        final long fails = joinFails.get();
        System.out.println("churn cycles done=" + done + " (target~" + cyclesTarget * slots + ") joinFails=" + fails);
        final boolean churnOk = fails == 0 && done >= 0.8 * cyclesTarget * slots;
        System.out.println(churnOk
            ? "churn gate PASS"
            : "churn gate FAILED (done=" + done + " target=" + cyclesTarget * slots + " fails=" + fails + ")");

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
        if (!churnOk || errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }
}
