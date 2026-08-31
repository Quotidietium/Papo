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
 * 批次118：容器线序对照 harness——「闪回未在 fork 复现」的线级硬闭环。
 *
 * 方法：服务器装 MenuRefreshPlugin（reopen 模式=GUI 插件 close+open 的闪回机制
 * 载体，每 N tick 一次）；bot 开帧日志（容器相关 clientbound 包 ID/时刻/字节：
 * CLOSE=0x11 SET_CONTENT=0x12 SET_SLOT=0x14 OPEN_SCREEN=0x39——GameProtocols 注册
 * 序提取，+1 偏移经 keepalive 0x2B/ping 0x3B 双实证校验）。两相位：基线 60s +
 * reopen(20 tick) 120s；输出 reopen 序列签名（每 reopen 窗的包型子序列）与
 * OPEN_SCREEN→SET_CONTENT 到达间隔分位。同法分别跑 fork 与官方 Paper jar 对照，
 * 序列签名一致 + 间隔同级 = fork 容器路径线级等价的直接实证。
 *
 * 用法：java papo.bot.WireSequenceBench <jar> <pluginJar> [label]
 */
public final class WireSequenceBench {

    private static final int PORT = 25605;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path pluginJar = Path.of(args[1]);
        final String label = args.length > 2 ? args[2] : jar.getFileName().toString();
        final Path dir = Files.createTempDirectory("papo-wireseq-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.createDirectories(dir.resolve("plugins"));
        Files.copy(pluginJar, dir.resolve("plugins/MenuRefresh.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-wireseq",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8",
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

        server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(2000);

        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, "StandB00");
        final Thread botThread = new Thread(() -> {
            try {
                bot.joinWalkAndDisconnect(200_000, 0, 0);
            } catch (final Exception ignored) {
            }
        }, "bot");
        botThread.setDaemon(true);
        botThread.start();
        Thread.sleep(15_000); // 进场 + 插件自动开菜单
        bot.startFrameLog();
        Thread.sleep(60_000); // 相位 A：基线
        server.getOutputStream().write("menurefresh reopen 20\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(120_000); // 相位 B：reopen 每 20 tick
        final List<String> frames;
        synchronized (bot.frameLog) {
            frames = new ArrayList<>(bot.frameLog);
        }
        System.out.println("frames captured: " + frames.size());

        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        if (!server.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
        tail.join(3000);

        // 分析：reopen 序列签名 + OPEN_SCREEN→SET_CONTENT 间隔
        System.out.println("---- " + label + " wire analysis ----");
        int close = 0, content = 0, slot = 0, open = 0;
        final List<Long> openToContentMs = new ArrayList<>();
        long lastOpenMs = -1;
        // 每个OPEN_SCREEN窗的包型序列
        final List<String> signatures = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (final String f : frames) {
            final String[] parts = f.split(",");
            final String id = parts[0];
            final long tMs = Long.parseLong(parts[1]);
            switch (id) {
                case "0x39" -> { // OPEN_SCREEN：新窗开始
                    open++;
                    if (cur.length() > 0) {
                        signatures.add(cur.toString());
                    }
                    cur = new StringBuilder("O");
                    lastOpenMs = tMs;
                }
                case "0x11" -> { close++; if (cur.length() == 0) { cur = new StringBuilder("C"); } else { cur.append('C'); } }
                case "0x12" -> {
                    content++;
                    cur.append('F');
                    if (lastOpenMs >= 0) {
                        openToContentMs.add(tMs - lastOpenMs);
                        lastOpenMs = -1;
                    }
                }
                case "0x14" -> { slot++; cur.append('S'); }
                default -> { }
            }
        }
        if (cur.length() > 0) {
            signatures.add(cur.toString());
        }
        System.out.println("counts: open=" + open + " close=" + close + " fullContent=" + content + " setSlot=" + slot);
        if (!openToContentMs.isEmpty()) {
            final List<Long> sorted = new ArrayList<>(openToContentMs);
            java.util.Collections.sort(sorted);
            System.out.println("openScreen->fullContent ms: p50=" + sorted.get(sorted.size() / 2)
                + " p95=" + sorted.get((int) (sorted.size() * 0.95))
                + " max=" + sorted.get(sorted.size() - 1) + " n=" + sorted.size());
        }
        // 签名分布（去重计数，前 5）
        final java.util.Map<String, Integer> sigCount = new java.util.LinkedHashMap<>();
        for (final String s : signatures) {
            sigCount.merge(s, 1, Integer::sum);
        }
        sigCount.entrySet().stream()
            .sorted((a, b) -> -a.getValue().compareTo(b.getValue()))
            .limit(5)
            .forEach(e -> System.out.println("  sig '" + e.getKey() + "' x" + e.getValue()));
        // 原始帧落盘（对照用）
        Files.write(Path.of("F:/TEMP/papo-b118-frames-" + label + ".txt"), frames, StandardCharsets.UTF_8);
        System.out.println("exited=" + (server.exitValue() == 0));
    }
}
