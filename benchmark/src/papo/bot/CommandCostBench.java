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
 * 批次114：菜单插件命令派发链成本量化 harness——头号假说的数值化。
 *
 * 用户问询锁定假说：波动与交互对齐 + 箱子菜单/GUI 插件 → 点击处理器主线程
 * dispatchCommand 链。本 harness 实测代表性菜单命令的单条主线程成本与批量
 * 每 tick 执行的 dur 影响：boot（tickProfile=1）→ 1 bot 进场 → 对 5 类典型
 * 命令（give/give+NBT组件/playsound/effect/title）各发 10/50/200 三档批量，
 * 从 tickdist 的 dur 尖峰除以批量得每命令成本（线性校验）。
 *
 * 用法：java papo.bot.CommandCostBench <jar>
 */
public final class CommandCostBench {

    private static final int PORT = 25602;

    public static final String[][] CMDS = {
        {"give", "give @p minecraft:stone 1"},
        {"giveNbt", "give @p minecraft:diamond_sword 1[custom_name='{\"text\":\"菜单物品\"}',lore=['{\"text\":\"x\"}']]"},
        {"playsound", "playsound minecraft:ui.button.click master @a"},
        {"effect", "effect give @p minecraft:speed 1 1 true"},
        {"title", "title @p title {\"text\":\"菜单\"}"},
    };
    public static final int[] BATCHES = {10, 50, 200};

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path dir = Files.createTempDirectory("papo-cmdcost-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false",
            "motd=papo-cmdcost", "sync-chunk-writes=false",
            "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

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

        final java.util.concurrent.atomic.AtomicBoolean botOk = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Thread bot = new Thread(() -> {
            try {
                new OfflineJoinBot("127.0.0.1", PORT, "StandB00").joinWalkAndDisconnect(420_000, 0, 0);
                botOk.set(true);
            } catch (final Exception ignored) {
            }
        }, "bot-anchor");
        bot.setDaemon(true);
        bot.start();
        Thread.sleep(15_000);

        // 基线窗 1（20s）
        Thread.sleep(20_000);
        System.out.println("---- baseline window 1 done ----");

        // 各命令 × 各批量：标记 → 批量（分块节流）→ 间隔 8s（两个完整 tickdist 窗内可辨）
        for (final String[] entry : CMDS) {
            final String name = entry[0];
            final String cmd = entry[1];
            for (final int n : BATCHES) {
                final String marker = "CMDCOST_" + name + "_" + n;
                server.getOutputStream().write(("say " + marker + "_START\n").getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append(cmd).append('\n');
                }
                // 分块写入（50 行/40ms——批次112 判例）
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
                server.getOutputStream().write(("say " + marker + "_END\n").getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                Thread.sleep(15_000); // 下一批前留 3/4 个窗恢复
            }
        }
        System.out.println("---- all batches sent ----");
        Thread.sleep(10_000);

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
        // 输出每个 marker 所在时刻前后最近的 tickdist 窗（含尖峰窗）
        System.out.println("---- tickdist windows ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.tickdist")) {
                System.out.println(l.substring(Math.max(0, l.indexOf("PapoTickProfile"))));
            }
        }
        System.out.println("---- markers ----");
        for (final String l : fileLog) {
            if (l.contains("CMDCOST_")) {
                System.out.println(l.trim());
            }
        }
        System.out.println("botOk=" + botOk.get() + " exited=" + (server.exitValue() == 0));
    }
}
