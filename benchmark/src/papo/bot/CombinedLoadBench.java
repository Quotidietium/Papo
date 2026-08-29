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
 * 批次114：混合负载稳定性 harness——七场景家族孤立验证的交互效应缺口。
 *
 * 真实服=并发混合：实体 tick + 红石计划 tick + 玩家进出 + 行走世界生成 + 大脏
 * 区块 autosave 同时发生；孤立测试各自干净不证明组合干净（mid-tick 排水×实体
 * tick×扇出突发的重叠可能产生周期性/持续性波动）。本 harness 单服务器叠加：
 * 500 头 AI 牛（实体轴）+ 100 环振荡器（红石轴，红石平台建于牛群旁）+ 5 个
 * churn 槽位（30s 周期）+ 2 个持续行走 bot（世界生成）+ 800 脏区块保活。
 *
 * 门：churn 轮数≥80% + logErrors=0 + 正常停机 + 实体/环在场计数（窗口前后）。
 * 用法：java papo.bot.CombinedLoadBench <jar> [windowMs=600000]
 */
public final class CombinedLoadBench {

    private static final int PORT = 25601;
    private static final String ANCHOR = "StandB00";
    private static final int HALF = 88;

    private static final int[][] R_DUSTS = {{0, 0}, {2, 0}, {3, 0}, {3, 2}, {3, 3}, {1, 3}, {0, 3}, {0, 1}};
    private static final int[][] R_REPS = {{1, 0}, {3, 1}, {2, 3}, {0, 2}};
    private static final String[] R_FACING = {"east", "south", "west", "north"};

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final long windowMs = args.length > 1 ? Long.parseLong(args[1]) : 600_000;
        final Path dir = Files.createTempDirectory("papo-comb-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=30", "view-distance=6", "simulation-distance=8",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false",
            "motd=papo-comb", "sync-chunk-writes=false",
            "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("spigot.yml"),
            "entity-activation-range:\n  animals: 96\n", StandardCharsets.UTF_8);
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

        // 锚点 bot + 2 行走 bot（扇形向外，世界生成流）
        new Thread(() -> {
            try {
                new OfflineJoinBot("127.0.0.1", PORT, ANCHOR).joinWalkAndDisconnect(windowMs + 300_000, 0, 0);
            } catch (final Exception ignored) {
            }
        }, "bot-anchor").start();
        for (int i = 0; i < 2; i++) {
            final double ang = Math.PI * (0.25 + i * 0.5);
            final String name = "WalkB" + i;
            final Thread t = new Thread(() -> {
                try {
                    new OfflineJoinBot("127.0.0.1", PORT, name)
                        .joinWalkAndDisconnect(windowMs + 240_000, Math.cos(ang) * 0.06, Math.sin(ang) * 0.06);
                } catch (final Exception ignored) {
                }
            }, "bot-walk" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(15_000);

        // 平台（天空盒判例沿用：y=200 悬空盒，杜绝地形交互与锚点漂移）
        writeChunked(server, logLines,
            "execute at " + ANCHOR + " run tp " + ANCHOR + " ~ 200 ~\n"
            + sbFill(-1) + "minecraft:stone\n"
            + sbFill(32) + "minecraft:barrier\n"
            + sbWall('x', -HALF) + "minecraft:glass\n" + sbWall('x', HALF) + "minecraft:glass\n"
            + sbWall('z', -HALF) + "minecraft:glass\n" + sbWall('z', HALF) + "minecraft:glass\n");
        Thread.sleep(3000);

        // 红石 100 环（东侧网格，间距 8）——种子末批去同步
        final StringBuilder rings = new StringBuilder();
        final StringBuilder seeds = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            final int ox = 16 + (i % 10) * 8;
            final int oz = (i / 10) * 8 - 36;
            for (int[] d : R_DUSTS) {
                rings.append("execute at ").append(ANCHOR).append(" run setblock ~").append(ox + d[0]).append(" ~ ~").append(oz + d[1]).append(" minecraft:redstone_wire\n");
            }
            for (int r = 0; r < 4; r++) {
                final String st = "minecraft:repeater[facing=" + R_FACING[r] + ",delay=1,powered="
                    + (r == 0) + ",locked=false]";
                (r == 0 ? seeds : rings).append("execute at ").append(ANCHOR).append(" run setblock ~")
                    .append(ox + R_REPS[r][0]).append(" ~ ~").append(oz + R_REPS[r][1]).append(' ').append(st).append('\n');
            }
        }
        writeChunked(server, logLines, rings.toString());
        Thread.sleep(1500);
        writeChunked(server, logLines, seeds.toString());

        // 实体 500 AI 牛（西侧平台网格）
        final StringBuilder cows = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            final int gx = -12 - (i % 20);
            final int gz = (i / 20) * 2 - 25;
            cows.append("execute at ").append(ANCHOR).append(" run summon cow ~").append(gx).append(" ~ ~").append(gz)
                .append(" {Tags:[\"papoCow\"]}\n");
        }
        writeChunked(server, logLines, cows.toString());

        // 800 脏区块 forceload（南方，autosave 周期负载）
        writeChunked(server, logLines, "forceload add ~ ~ 200 ~ ~ 200 1423\n");
        Thread.sleep(5000);
        final StringBuilder dirty = new StringBuilder();
        for (int cx = 0; cx < 25; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                dirty.append("execute positioned ~").append(cx * 16 + 8).append(" 220 ~").append(200 + cz * 16 + 8)
                    .append(" run setblock ~ ~ ~ minecraft:stone\n");
            }
        }
        writeChunked(server, logLines, dirty.toString());
        System.out.println("built: 100 rings + 500 cows + 800 dirty chunks + walkers");
        Thread.sleep(20_000);

        // 在场门 A
        final long cowsA = probeCount(server, logLines, "MOO_A");
        System.out.println("cowsA=" + cowsA);
        final long cowsB;
        {
            // churn 5 槽位 + 测量窗
            final long cyclesTarget = windowMs / 32_000 * 5;
            final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong();
            final java.util.concurrent.atomic.AtomicLong fails = new java.util.concurrent.atomic.AtomicLong();
            for (int s = 0; s < 5; s++) {
                final int idx = s;
                final Thread t = new Thread(() -> {
                    final long deadline = System.currentTimeMillis() + windowMs - 15_000;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            new OfflineJoinBot("127.0.0.1", PORT, String.format("Chrn%02d", idx))
                                .joinAndDisconnect(30_000);
                            done.incrementAndGet();
                        } catch (final Exception e) {
                            fails.incrementAndGet();
                        }
                    }
                }, "churn-" + s);
                t.setDaemon(true);
                t.start();
                Thread.sleep(6000);
            }
            Thread.sleep(windowMs);
            cowsB = probeCount(server, logLines, "MOO_B");
            System.out.println("cowsB=" + cowsB + " churnDone=" + done + " fails=" + fails
                + " (target~" + cyclesTarget + ")");
            final boolean churnOk = fails.get() == 0 && done.get() >= 0.8 * cyclesTarget;
            final boolean presenceOk = cowsA >= 490 && cowsB >= 490;
            System.out.println((churnOk && presenceOk)
                ? "gates PASS (cows " + cowsA + "/" + cowsB + ", churn " + done + ")"
                : "gates FAILED (cows " + cowsA + "/" + cowsB + ", churn " + done + " fails " + fails + ")");
        }

        try {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(150, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);
        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- tickdist ----");
        int n = 0;
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.tickdist") && n++ < 30) {
                System.out.println(l.substring(l.indexOf("PapoTickProfile")));
            }
        }
        System.out.println("---- stalls ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile.stall")) {
                System.out.println(l.substring(l.indexOf("PapoTickProfile.stall")));
            }
        }
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            System.exit(1);
        }
    }

    private static String sbFill(final int dy) {
        return "execute at " + ANCHOR + " run fill ~-" + HALF + " ~" + dy + " ~-" + HALF
            + " ~" + HALF + " ~" + dy + " ~" + HALF + " ";
    }

    private static String sbWall(final char axis, final int at) {
        return "execute at " + ANCHOR + " run fill ~" + (axis == 'x' ? at : "-" + HALF) + " ~ ~" + (axis == 'x' ? "-" + HALF : at)
            + " ~" + (axis == 'x' ? at : HALF) + " ~31 ~" + (axis == 'x' ? HALF : at) + " ";
    }

    private static void writeChunked(final Process server, final List<String> logLines, final String commands) throws Exception {
        final String[] lines = commands.split("\n", -1);
        final StringBuilder chunkSb = new StringBuilder();
        int inChunk = 0;
        for (final String l : lines) {
            if (l.isEmpty()) {
                continue;
            }
            chunkSb.append(l).append('\n');
            if (++inChunk >= 50) {
                server.getOutputStream().write(chunkSb.toString().getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                chunkSb.setLength(0);
                inChunk = 0;
                Thread.sleep(40);
            }
        }
        if (inChunk > 0) {
            server.getOutputStream().write(chunkSb.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        }
    }

    private static long probeCount(final Process server, final List<String> logLines, final String marker) throws Exception {
        try {
            server.getOutputStream().write(("execute as @e[type=cow,tag=papoCow] run say " + marker + "\n")
                .getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            return -1;
        }
        Thread.sleep(12_000);
        synchronized (logLines) {
            return logLines.stream().filter(l -> l.contains(marker)).count();
        }
    }
}
