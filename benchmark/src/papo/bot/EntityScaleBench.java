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
 * 批次106：实体规模轴勘察 harness v5。
 *
 * 批次105 判例链的根治版。真因已闭环：OfflineJoinBot.walk() 起始位置硬编码为绝对
 * (0.5, 100.0, 0.5)——站立 bot 实际站在那里，而 v4 牛群召唤在 y=104 绝对坐标，地形
 * 低于 100 时坠落摔死、高于 104 时山体窒息，"在场"从未成立。
 *
 * v5 方案：全部命令以 {@code execute at StandB00}（bot 真实位置的权威锚点）相对执行——
 * ① 清空 bot 脚平面 ±40 XZ、y+0..y+24 共 25 层（7 条 fill，每条 ≤32768 块限制内）；
 * ② y-1 层铺 stone 平台（stone 不触发草方块动物自然刷新；地形起伏被抹平）；
 * ③ 平台边缘 y 层橡木围栏一圈（牛跳不过 1.5 高，杜绝平台外坠落）；
 * ④ 牛群以等面积网格（±32 足印恒定）召唤于 bot 脚平面（零坠落零窒息）；
 * ⑤ 在场门 = 窗口前后两次 {@code execute as @e[type=cow,tag=papoCow] run say} 计数全等
 * （MOO_A==N 且 MOO_B==N，缺失即环境失败 exit 1，不再以平坦数据冒充基线；tag 只数
 * 召唤群，worldgen 种群杂散不污染计数——批次109 soak 判例）。
 *
 * spigot.yml 预写 entity-activation-range.animals=96：±32 足印对中心站立 bot 恒在
 * 激活半径内（EAR 判例：AABB 的 Y 向按全高度膨胀，垂直距离不影响激活）。
 * 服务器以 -Dpapo.tickProfile=1 启动，输出与 TickSurveyBench 同格式（400-tick 窗）。
 *
 * 用法：java papo.bot.EntityScaleBench <jar> [entities=1000] [windowMs=360000] [bots=10] [noai]
 * （第 4 参传 "noai" = NoAI 变体：牛保留物理/push/EAR/追踪，跳过 serverAiStep——批次110
 * 实体链 AI/非 AI 分量分解仪器，零服务器代码改动）
 */
public final class EntityScaleBench {

    private static final int PORT = 25594;
    private static final String ANCHOR = "StandB00"; // OfflineJoinBot 首个站立 bot（位置权威锚点）
    private static final int HALF = 40; // 平台半宽（fence at ±40，inner ±39）

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int entities = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        final long windowMs = args.length > 2 ? Long.parseLong(args[2]) : 360_000;
        final int bots = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        // 批次110：NoAI 变体（第 4 参为 "noai"）——NoAI 牛跳过 serverAiStep（goal/nav）但保留
        // 物理/push/EAR/追踪，与 AI 版对照分解实体链的 AI 与非 AI 分量（零服务器代码）
        final boolean noai = args.length > 4 ? "noai".equals(args[4]) : false;
        final Path dir = Files.createTempDirectory("papo-entscale-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20),
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-entscale",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
        // EAR 预写：animals 96 使 ±32 足印全激活（SpigotConfig copyDefaults(true) 支持部分文件合并）
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

        // 禁周期性自然刷新（peaceful 只禁怪物，动物照刷；gamerule 不影响 summon 召唤物，
        // 也不影响 chunk population 的 worldgen 种群——后者由 join 后清场兜底，见下）
        server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(2000);

        // 相位预算（bot 在场总时长 = 各段之和 + 余量）
        final int joinSettleMs = 25_000;
        final int buildMs = 15_000;
        final int summonSettleMs = 25_000;
        final int probeMs = 12_000;
        final long botDwellMs = joinSettleMs + buildMs + summonSettleMs + probeMs + windowMs + probeMs + 10_000;

        final List<Thread> botThreads = new ArrayList<>();
        boolean presenceOk = false;
        try {
            final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            final List<RuntimeException> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < bots; i++) {
                final String name = String.format("StandB%02d", i);
                final Thread t = new Thread(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 500));
                        new OfflineJoinBot("127.0.0.1", PORT, name).joinWalkAndDisconnect(botDwellMs, 0, 0);
                    } catch (final Throwable e) {
                        failures.add(new RuntimeException("bot " + name + " failed: " + e, e));
                    }
                }, "bot-" + name);
                t.setDaemon(true);
                botThreads.add(t);
                t.start();
            }
            go.countDown();
            Thread.sleep(joinSettleMs); // 等全体 bot 进场稳定（join 窗口外；vd=6 区块全加载，
            // chunk population 的 worldgen 种群刷新也在此发生——doMobSpawning 管不到它）

            // 清场：bot 已在场（type=!player 不伤 bot），此后不再有新区块加载 → 无新增种群
            server.getOutputStream().write("kill @e[type=!minecraft:player]\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(4000);
            server.getOutputStream().write("kill @e[type=minecraft:item]\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(2000);

            // ① 清空脚平面以上 25 层（fill 单命令 32768 块上限 → 4 层/条 ×6 + 1 层 ×1）
            final StringBuilder cmd = new StringBuilder();
            for (int layer = 0; layer < 25; layer += 4) {
                final int top = Math.min(24, layer + 3);
                cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF)
                    .append(" ~").append(layer).append(" ~-").append(HALF)
                    .append(" ~").append(HALF).append(" ~").append(top).append(" ~").append(HALF)
                    .append(" minecraft:air\n");
            }
            // ② y-1 平台（stone：非草方块，不触发动物自然刷新；地形起伏整平）
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF)
                .append(" ~-1 ~-").append(HALF)
                .append(" ~").append(HALF).append(" ~-1 ~").append(HALF)
                .append(" minecraft:stone\n");
            // ③ y 层围栏圈（4 条，角部重叠无害）
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF).append(" ~ ~-").append(HALF)
                .append(" ~").append(HALF).append(" ~ ~-").append(HALF).append(" minecraft:oak_fence\n");
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF).append(" ~ ~").append(HALF)
                .append(" ~").append(HALF).append(" ~ ~").append(HALF).append(" minecraft:oak_fence\n");
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF).append(" ~ ~").append(-(HALF - 1))
                .append(" ~-").append(HALF).append(" ~ ~").append(HALF - 1).append(" minecraft:oak_fence\n");
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~").append(HALF).append(" ~ ~").append(-(HALF - 1))
                .append(" ~").append(HALF).append(" ~ ~").append(HALF - 1).append(" minecraft:oak_fence\n");
            // ④ 等面积网格召唤（±32 足印恒定，density 随 N 缩放——捕获线性+密度超线性两轴）
            final int footprint = 64; // ±32
            final int side = (int) Math.ceil(Math.sqrt(entities));
            final double spacing = entities > 0 ? (double) footprint / side : 0;
            for (int i = 0; i < entities; i++) {
                final double gx = (i % side) - (side - 1) / 2.0;
                final double gz = (i / side) - (side - 1) / 2.0;
                final double x = gx * spacing + (i % 7) * 0.05 - 0.15;
                final double z = gz * spacing + ((i / 7) % 7) * 0.05 - 0.15;
                // 召唤即打标签：在场门只数本批次召唤群（tag=papoCow）——批次109 soak 判例：
                // 全局 @e[type=cow] 计数会把 worldgen 种群杂散牛（doMobSpawning 管不到、
                // 全局 kill 之后晚期 population 又刷出）混进 A/B，504/505 vs 500 假失败
                cmd.append("execute at ").append(ANCHOR).append(" run summon cow ")
                    .append(String.format(java.util.Locale.ROOT, "~%.2f ~ ~%.2f", x, z))
                    .append(noai ? " {NoAI:1b,Tags:[\"papoCow\"]}" : " {Tags:[\"papoCow\"]}").append('\n');
            }
            server.getOutputStream().write(cmd.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            System.out.println("built platform (half=" + HALF + ") + summoned " + entities
                + " cows (side=" + side + ", spacing=" + String.format(java.util.Locale.ROOT, "%.2f", spacing) + ")");
            Thread.sleep(buildMs + summonSettleMs);

            // ⑤ 在场探针 A（计数式：say 逐实体回显，缺失即门失败；-1=服务器已被外部杀死）
            final long presentA = probeCount(server, logLines, "MOO_A");
            System.out.println("cowsPresentA=" + presentA + " expected=" + entities);
            long presentB = -1;
            if (presentA >= 0) {
                Thread.sleep(windowMs); // 测量窗（bot 全程站立在场）
                presentB = probeCount(server, logLines, "MOO_B");
                System.out.println("cowsPresentB=" + presentB);
            }

            for (final Thread t : botThreads) {
                t.join(120_000);
                if (t.isAlive()) {
                    failures.add(new RuntimeException("bot thread did not finish: " + t.getName()));
                }
            }
            if (!failures.isEmpty()) {
                failures.forEach(f -> System.out.println("BOT-FAIL " + f)); // 服务器死亡时 bot 断连失败属预期，标志位收口
            } else {
                System.out.println("window done (" + bots + " bots x " + windowMs + "ms, entities=" + entities + ")");
            }
            Thread.sleep(3000);
            // 在场门：A==B（窗口零死亡）且 A>=N（召唤牛全活；tag=papoCow 只数召唤群，
            // worldgen 杂散/自然刷新一律不计——批次109 soak 判例的根治）
            presenceOk = !failures.isEmpty() ? false : presentA == presentB && presentA >= entities;
            System.out.println(presenceOk
                ? "presence gate PASS (A=B=" + presentA + " >= N=" + entities + ")"
                : "presence gate FAILED: A=" + presentA + " B=" + presentB + " expected=" + entities
                    + (failures.isEmpty() ? "" : " botFailures=" + failures.size()));
        } finally {
            try {
                server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
            } catch (final IOException ignored) {
            }
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
        if (!presenceOk || errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    /** 逐实体 say 计数在场探针：全量写 stdin，等回显落日志后按标记计数。 */
    private static long probeCount(final Process server, final List<String> logLines, final String marker) throws Exception {
        try {
            server.getOutputStream().write(("execute as @e[type=cow,tag=papoCow] run say " + marker + "\n").getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            // 共享机 java 清扫判例：服务器进程被外部杀死 → stdin 管道关闭。计数返回 -1，
            // 由调用方标记 presence 失败；窗口数据仍会从 logs/latest.log dump（finally 链保证）
            System.out.println("SERVER_DIED during " + marker + ": " + e);
            return -1;
        }
        Thread.sleep(12_000); // 4000 实体 × say 回显（含 bot 广播）需要数秒排空
        synchronized (logLines) {
            return logLines.stream().filter(l -> l.contains(marker)).count();
        }
    }
}
