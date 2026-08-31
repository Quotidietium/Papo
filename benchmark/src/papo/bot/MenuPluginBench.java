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
 * 批次116：菜单插件刷新模式量化 harness——用户实例头号剩余假说（插件监听器工作
 * 层）的服内复现与成本测量。
 *
 * 服务器预装 MenuRefresh.jar（benchmark/menuplugin，典型 ChestCommands 式刷新：
 * 54×setItem(name+lore+CMD 组件) + updateInventory 全量重同步）。bot 进场后插件
 * 自动开菜单；harness 四相位（各 120s，console 切档）：
 *   P0 0Hz（基线）→ P1 10Hz → P2 30Hz → P3 heavy 30Hz（+三附魔的 NBT 组件物品）
 * tickdist 分相解析：稳态 dur 增量 = 菜单刷新链成本；对比用户症状量级判定。
 *
 * 用法：java papo.bot.MenuPluginBench <jar> <pluginJar>
 */
public final class MenuPluginBench {

    private static final int PORT = 25603;
    private static final long PHASE_MS = 120_000;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path pluginJar = Path.of(args[1]);
        final Path dir = Files.createTempDirectory("papo-menupl-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.createDirectories(dir.resolve("plugins"));
        Files.copy(pluginJar, dir.resolve("plugins/MenuRefresh.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false",
            "motd=papo-menupl", "sync-chunk-writes=false",
            "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx4G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        boolean pluginOn = false;
        while (System.nanoTime() - bootStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("MenuRefresh ready")) {
                pluginOn = true;
            }
            if (line.contains("Done (")) {
                System.out.println("boot: " + line.trim() + " pluginOn=" + pluginOn);
                break;
            }
        }
        if (!pluginOn) {
            System.out.println("PLUGIN NOT ENABLED — aborting");
            server.destroyForcibly();
            System.exit(1);
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

        final java.util.concurrent.atomic.AtomicBoolean botDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Thread bot = new Thread(() -> {
            try {
                new OfflineJoinBot("127.0.0.1", PORT, "StandB00").joinWalkAndDisconnect(560_000, 0, 0);
                botDone.set(true);
            } catch (final Exception ignored) {
            }
        }, "bot-anchor");
        bot.setDaemon(true);
        bot.start();
        Thread.sleep(15_000); // bot 进场 + 插件自动开菜单（+2s）

        // 四相位：每相位先打标记（say PHASE_x）再切档
        phase(server, "P0_base_0Hz", "menurefresh 0");
        phase(server, "P1_10Hz", "menurefresh 10");
        phase(server, "P2_30Hz", "menurefresh 30");
        phase(server, "P3_heavy30Hz", "menurefresh heavy 1\nmenurefresh 30");
        cmd(server, "menurefresh 0");

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
        System.out.println("---- phases (from server log) ----");
        for (final String l : fileLog) {
            if (l.contains("PHASE_") || l.contains("rate=") || l.contains("heavy=")) {
                System.out.println(l.trim());
            }
        }
        System.out.println("---- tickdist ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.tickdist") || l.contains("PapoTickProfile.stall")) {
                System.out.println(l.substring(Math.max(0, l.indexOf("PapoTickProfile"))));
            }
        }
        System.out.println("botDone=" + botDone.get() + " exited=" + (server.exitValue() == 0));
    }

    private static void phase(final Process server, final String marker, final String setting) throws Exception {
        cmd(server, "say " + marker + "_START");
        Thread.sleep(2000);
        cmd(server, setting);
        Thread.sleep(PHASE_MS);
        cmd(server, "say " + marker + "_END");
    }

    private static void cmd(final Process server, final String c) throws Exception {
        final String[] lines = c.split("\n");
        for (final String l : lines) {
            server.getOutputStream().write((l + "\n").getBytes(StandardCharsets.UTF_8));
        }
        server.getOutputStream().flush();
    }
}
