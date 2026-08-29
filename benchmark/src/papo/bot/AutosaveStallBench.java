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
 * 批次114：大脏世界 autosave 停摆勘察 harness——周期型停摆假说的真实形态复现。
 *
 * 批次113 的 autosave 无尖峰结论来自小世界（几百区块）；用户服是数千脏区块+
 * 实体的 autosave。本 harness：forceload 40×40=1600 区块 → 每区块 setblock 一块
 * 弄脏 → 保持加载 13 分钟（跨 2 个 bukkit autosave 周期，6000 tick=5min）→
 * tickdist+stall 行观察 5min/10min 边界是否周期停摆；jstack 采样器在预期
 * autosave 时刻（+4.5-6min、+9.5-11min）武装，命中即抓现场。
 *
 * 门：logErrors=0 + 正常停机 + 脏化命令全额执行。
 * 用法：java papo.bot.AutosaveStallBench <jar> [windowMs=780000] [chunksSide=40]
 */
public final class AutosaveStallBench {

    private static final int PORT = 25600;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final long windowMs = args.length > 1 ? Long.parseLong(args[1]) : 780_000;
        final int side = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        final Path dir = Files.createTempDirectory("papo-asv-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false",
            "motd=papo-asv", "sync-chunk-writes=false",
            "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
        // 显式 autosave 周期（bukkit 默认 6000 tick；写明保确定性）
        Files.writeString(dir.resolve("bukkit.yml"),
            "ticks-per:\n  autosave: 6000\n", StandardCharsets.UTF_8);

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

        for (int i = 0; i < 2; i++) {
            final String name = "StandB0" + i;
            final Thread t = new Thread(() -> {
                try {
                    new OfflineJoinBot("127.0.0.1", PORT, name).joinWalkAndDisconnect(windowMs + 240_000, 0, 0);
                } catch (final Exception ignored) {
                }
            }, "bot-" + name);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(15_000);

        // forceload side×side 区块 + 每区块脏化一块（writeChunked 50行/40ms——批次112 判例）
        final int span = side * 16;
        int dirtyDone = 0;
        try {
            server.getOutputStream().write(("forceload add 0 0 " + (span - 1) + " " + (span - 1) + "\n")
                .getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(8000); // 等区块生成/加载
            final StringBuilder sb = new StringBuilder();
            int inChunk = 0;
            for (int cx = 0; cx < side; cx++) {
                for (int cz = 0; cz < side; cz++) {
                    sb.append("execute positioned ").append(cx * 16 + 8).append(" 120 ").append(cz * 16 + 8)
                        .append(" run setblock ~ ~ ~ minecraft:stone\n");
                    dirtyDone++;
                    if (++inChunk >= 50) {
                        server.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        server.getOutputStream().flush();
                        sb.setLength(0);
                        inChunk = 0;
                        Thread.sleep(40);
                    }
                }
            }
            if (inChunk > 0) {
                server.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
            }
        } catch (final IOException e) {
            System.out.println("SERVER_DIED during dirty phase: " + e);
        }
        final long dirtyDoneAt = System.currentTimeMillis();
        System.out.println("dirtied " + dirtyDone + " chunks (forceload " + side + "x" + side + ")");

        // jstack 采样器：预期 autosave 时刻武装（脏化后 +4.5min 与 +9.5min 各 90s 窗）
        final List<String> stacks = java.util.Collections.synchronizedList(new ArrayList<>());
        final Thread sampler = new Thread(() -> {
            final long pid = server.pid();
            final long[] arms = {dirtyDoneAt + 270_000, dirtyDoneAt + 570_000};
            for (final long arm : arms) {
                try {
                    final long wait = arm - System.currentTimeMillis();
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    for (int i = 0; i < 60; i++) {
                        final Process js = new ProcessBuilder("F:/Java/21/bin/jstack", String.valueOf(pid))
                            .redirectErrorStream(true).start();
                        final StringBuilder sb2 = new StringBuilder();
                        String l;
                        try (BufferedReader jr = new BufferedReader(new InputStreamReader(js.getInputStream(), StandardCharsets.UTF_8))) {
                            while ((l = jr.readLine()) != null) {
                                sb2.append(l).append('\n');
                            }
                        }
                        js.waitFor();
                        final String dump = sb2.toString();
                        final int idx = dump.indexOf("\"Server thread\"");
                        if (idx >= 0) {
                            final int end = dump.indexOf("\n\n", idx);
                            stacks.add("=== arm+" + (System.currentTimeMillis() - arm) + "ms ===\n"
                                + dump.substring(idx, end > 0 ? end : Math.min(dump.length(), idx + 1100)));
                        }
                        Thread.sleep(60);
                    }
                } catch (final Exception ignored) {
                }
            }
        }, "jstack-sampler");
        sampler.setDaemon(true);
        sampler.start();

        Thread.sleep(windowMs);
        try {
            server.getOutputStream().write("forceload remove all\nstop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(150, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);
        sampler.join(10_000);

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- tickdist (steady tail) ----");
        int printed = 0;
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.tickdist") && printed++ < 40) {
                System.out.println(l.substring(l.indexOf("PapoTickProfile")));
            }
        }
        System.out.println("---- stalls ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.stall")) {
                System.out.println(l.substring(l.indexOf("PapoTickProfile.stall")));
            }
        }
        System.out.println("---- autosave markers ----");
        for (final String l : fileLog) {
            if (l.contains("Autosave") || l.contains("autosav") || l.contains("Saving the game")) {
                System.out.println(l.trim());
            }
        }
        System.out.println("---- jstack samples ----");
        for (final String s : stacks) {
            System.out.println(s);
        }
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("logErrors=" + errors + " exited=" + (server.exitValue() == 0)
            + " dirtyCmds=" + dirtyDone);
        if (errors > 0 || server.exitValue() != 0 || dirtyDone != side * side) {
            System.exit(1);
        }
    }
}
