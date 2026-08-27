package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 批次105：实体规模轴勘察 harness。
 *
 * 控制台批量 summon 带 PersistenceRequired+高抗性（免疫摔落伤害）的牛于出生点上空，
 * 落地后 spreadplayers 散布（间距≥4 防聚堆 push 干扰，范围 48 格内保持 EAR 激活）；
 * 10 个行走 bot 维持区块加载与追踪观察者。服务器以 -Dpapo.tickProfile=1 启动，
 * 输出与 TickSurveyBench 同格式（batch97_parse.py 可直接解析）。
 *
 * 用法：java papo.bot.EntityScaleBench <jar> [entities=500] [walkMs=360000] [bots=10]
 */
public final class EntityScaleBench {

    private static final int PORT = 25594;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int entities = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        final long walkMs = args.length > 2 ? Long.parseLong(args[2]) : 360_000;
        final int bots = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        final Path dir = Files.createTempDirectory("papo-entscale-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20),
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-entscale",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx4G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
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

        // 批量 summon：高抗性（免疫摔落）+ 持久化（不消失）+ 网格坐标直接散布
        // （首版 spreadplayers 在 500 实体时主线程阻塞 ~5 分钟，弃用；二版 ±46 网格超出
        // 站立 bot 的 EAR 激活半径 32 致实体不 tick，本版 NoAI + ±20 网格全体激活）
        final StringBuilder cmd = new StringBuilder();
        final int side = (int) Math.ceil(Math.sqrt(entities));
        final int spacing = Math.max(1, (40 / Math.max(1, side)));
        for (int i = 0; i < entities; i++) {
            final int gx = (i % side) - side / 2;
            final int gz = (i / side) - side / 2;
            final double x = 0.5 + gx * spacing + (i % 2) * 0.4;
            final double z = 0.5 + gz * spacing + ((i / 2) % 2) * 0.4;
            // v4（终版）：裸召唤零 NBT（1.21.x 实体 NBT 键名兼容性反复坑：ActiveEffects/NoAI 均可能
            // 被静默丢弃）；y=104 贴中心地形低空（批次内 3-min 窗口内 despawn 不可达——常驻激活半径内）
            cmd.append("summon cow ").append(x).append(" 104.0 ").append(z).append("\n");
        }
        server.getOutputStream().write(cmd.toString().getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        System.out.println("summoned " + entities + " cows on grid (side=" + side + ", spacing=" + spacing + ")");
        Thread.sleep(20_000); // 等落地+首 tick 稳定（join 窗口外）
        // 批次105 验证：控制台反馈计数（Summoned 成功数 / 解析错误数）——控制台回显不进 stdout 门
        synchronized (logLines) {
            long ok = logLines.stream().filter(l -> l.contains("Summoned new Cow")).count();
            long bad = logLines.stream().filter(l -> l.contains("Expected") || l.contains("Couldn't parse") || l.contains("Unknown or incomplete command")).count();
            System.out.println("summonFeedback ok=" + ok + " bad=" + bad);
        }
        // 批次105 在场判定：execute-if 探针（控制台回显确认牛群真实存在且被选择器命中）
        server.getOutputStream().write("execute if entity @e[type=cow] run say COWS_PRESENT\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(3000);
        synchronized (logLines) {
            long present = logLines.stream().filter(l -> l.contains("COWS_PRESENT")).count();
            System.out.println("cowsPresent=" + (present > 0));
        }

        try {
            final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            final List<Thread> botThreads = new ArrayList<>();
            final List<RuntimeException> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < bots; i++) {
                final String name = String.format("WalkB%02d", i);
                final Thread t = new Thread(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 500));
                        new OfflineJoinBot("127.0.0.1", PORT, name).joinWalkAndDisconnect(walkMs, 0, 0);
                    } catch (final Throwable e) {
                        failures.add(new RuntimeException("bot " + name + " failed: " + e, e));
                    }
                }, "bot-" + name);
                t.setDaemon(true);
                botThreads.add(t);
                t.start();
            }
            go.countDown();
            for (final Thread t : botThreads) {
                t.join(walkMs + 180_000);
                if (t.isAlive()) {
                    failures.add(new RuntimeException("bot thread did not finish: " + t.getName()));
                }
            }
            if (!failures.isEmpty()) {
                failures.forEach(f -> System.out.println("BOT-FAIL " + f));
                throw new IllegalStateException(failures.size() + " bot failures");
            }
            System.out.println("walk window done (" + bots + " bots x " + walkMs + "ms, entities=" + entities + ")");
            Thread.sleep(3000);
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            tail.join(5000);
        }

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- PapoTickProfile windows (from logs/latest.log) ----");
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
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }
}
