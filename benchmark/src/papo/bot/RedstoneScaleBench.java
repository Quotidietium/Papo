package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次112：红石轴勘察 harness v1（R3 实体轴闭合后的新前沿）。
 *
 * 负载模型：中继器环振荡器阵列。每 cell = 4×4 闭环：4 个 delay=1 中继器居边
 * （东南西北向各一，信号沿环单向传播）+ 8 个红石粉（含 4 个转角）。种子 = 最后
 * 放置的 (1,0) 位中继器以 {@code powered=true} 落位——其输入侧无源，会在自身
 * 延迟后跳回断开，形成一个宽度 2gt 的脉冲沿闭环永久循环：环周期 8gt、每中继器
 * 每 8gt 跳变 2 次 → **每 cell 恰 1 次/tick 计划方块 tick + ~2 次/tick 粉强度
 * 重算**（含邻居更新扇出）。无火把 = 无烧毁；纯中继器 = 稳态确定性。
 *
 * 在场门（批次106 计数式判例的方块版，三重探针）：
 * ① 结构探针 RING_i：{@code execute if block <cell(0,0) dust> run say RING_A_i}——环结构
 * 逐 cell 精确计数（A==B==N，带索引可取证缺块位置）；
 * ② 种子探针 REP_i：种子位 {@code minecraft:repeater} 存在性（区分"种子未落位"与"粉丢失"）；
 * ③ 计数器门：0262 探针 {@code rs.blockTickRuns} avg/tick ≥ 0.6×N（期望 ~1.0×N），
 * 取尾 1/4 窗中位数（批次106 尾窗惯例）——400-tick 窗均值对环相位不敏感，是振荡
 * 存活度的权威度量（冒烟判例：瞬时 powered 采样受相位/控制台聚批双重干扰，弃用）。
 *
 * 平台 ±88（177×177=31,329 ≤ 32,768 fill 上限/层）；cell 间距 8（互不连接）；
 * vd=6 → ±96 区块加载覆盖。10 站立 bot 锚定（{@code execute at StandB00} 相对
 * 建造，批次106 根治判例）。0262 探针输出 level.blockTicks/fluidTicks/blockEvents
 * 子相位 + 计数器（400-tick 窗）。
 *
 * 用法：java papo.bot.RedstoneScaleBench <jar> [cells=100] [windowMs=360000] [bots=10] [rsimpl=vanilla]
 * （第 5 参 ac/eigencraft = Paper 既有红石实现旋钮的运营量化腿，默认 vanilla 不写配置）
 */
public final class RedstoneScaleBench {

    private static final int PORT = 25595;
    private static final String ANCHOR = "StandB00"; // OfflineJoinBot 首个站立 bot（位置权威锚点）
    private static final int HALF = 88; // 平台半宽（177×177 = 31,329 ≤ 32,768 fill 上限）
    private static final int PITCH = 8; // cell 间距（4×4 环 + 4 gap，互不连接）

    /** 环内 4 个中继器（局部坐标 → 朝向，信号沿环单向）。 */
    private static final int[][] REPEATERS = {{1, 0}, {3, 1}, {2, 3}, {0, 2}};
    private static final String[] FACING = {"east", "south", "west", "north"};
    /** 环内 8 个红石粉（含 4 转角）。 */
    private static final int[][] DUSTS = {{0, 0}, {2, 0}, {3, 0}, {3, 2}, {3, 3}, {1, 3}, {0, 3}, {0, 1}};
    private static final int[] SEED = {1, 0}; // 种子中继器（最后放置，powered=true）

    private static final Pattern AVG_PER_TICK = Pattern.compile("avg/tick=\\s*([0-9.]+)");

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int cells = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        final long windowMs = args.length > 2 ? Long.parseLong(args[2]) : 360_000;
        final int bots = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        // 第 5 参：红石实现运营量化腿（vanilla 默认=不写配置文件，基线与历史可比；
        // ac=ALTERNATE_CURRENT / eigencraft=EIGENCRAFT——Paper 既有运营旋钮，非 Papo 改默认）
        final String rsimpl = args.length > 4 ? args[4].toLowerCase(java.util.Locale.ROOT) : "vanilla";
        final Path dir = Files.createTempDirectory("papo-rsscale-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20),
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-rsscale",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);
        if ("ac".equals(rsimpl) || "eigencraft".equals(rsimpl)) {
            Files.createDirectories(dir.resolve("config"));
            Files.writeString(dir.resolve("config/paper-world-defaults.yml"),
                "misc:\n  redstone-implementation: "
                    + ("ac".equals(rsimpl) ? "ALTERNATE_CURRENT" : "EIGENCRAFT") + "\n",
                StandardCharsets.UTF_8);
            System.out.println("rsimpl=" + rsimpl + " (prewritten paper-world-defaults.yml)");
        }

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

        // 禁自然刷新（红石负载不依赖实体；清杂散只为降噪）
        server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(2000);

        // 相位预算
        final int joinSettleMs = 25_000;
        final int buildMs = 15_000;
        final int settleMs = 25_000;
        final int probeMs = 12_000;
        final long botDwellMs = joinSettleMs + buildMs + settleMs + probeMs + windowMs + probeMs + 10_000;

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
            Thread.sleep(joinSettleMs); // 等全体 bot 进场稳定（join 窗口外；vd=6 区块全加载）

            // 清场：bot 已在场（type=!player 不伤 bot），此后不再有新区块加载
            server.getOutputStream().write("kill @e[type=!minecraft:player]\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(4000);
            server.getOutputStream().write("kill @e[type=minecraft:item]\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(2000);

            // ① 清空脚平面以上 32 层（fill 单命令 32768 块上限 → 4 层/条 ×8）。
            // 32 层 + 建环前沉降清扫 = 重力方块判例的双重根治：清场边界上方（y+32+）的
            // 沙/沙砾柱受邻居更新触发坠落，2×3 足迹可连毁环上 6 槽位（cell 取证实证）
            final StringBuilder cmd = new StringBuilder();
            for (int layer = 0; layer < 32; layer += 4) {
                final int top = Math.min(31, layer + 3);
                cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF)
                    .append(" ~").append(layer).append(" ~-").append(HALF)
                    .append(" ~").append(HALF).append(" ~").append(top).append(" ~").append(HALF)
                    .append(" minecraft:air\n");
            }
            // ② y-1 石平台（红石粉/中继器的承重与信号绝缘底座）
            cmd.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF)
                .append(" ~-1 ~-").append(HALF)
                .append(" ~").append(HALF).append(" ~-1 ~").append(HALF)
                .append(" minecraft:stone\n");
            server.getOutputStream().write(cmd.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(4000); // 等清场边界外的重力方块坠落落地（下落 ~30 层需 1-2s）

            // ③ 沉降清扫：清除已坠落到脚平面的碎屑（沙/沙砾/原木），随后才建环
            final StringBuilder sweep = new StringBuilder();
            for (int layer = 0; layer < 3; layer++) {
                sweep.append("execute at ").append(ANCHOR).append(" run fill ~-").append(HALF)
                    .append(" ~").append(layer).append(" ~-").append(HALF)
                    .append(" ~").append(HALF).append(" ~").append(layer).append(" ~").append(HALF)
                    .append(" minecraft:air\n");
            }
            server.getOutputStream().write(sweep.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(2000);
            cmd.setLength(0); // 复用构建器：以下追加环阵列命令

            // ④ 环振荡器阵列：等间距网格居中（side×side ≥ cells）。
            // 种子分 8 批、批间 ~1gt 间隔放置（冒烟判例：同批落位=全环同相位，采样全有/全无
            // 且每 8gt 集中尖峰——去同步后负载形态才具代表性）
            final int side = cells > 0 ? (int) Math.ceil(Math.sqrt(cells)) : 0;
            final int seedBatches = 8;
            final List<StringBuilder> seedCmds = new ArrayList<>();
            for (int b = 0; b < seedBatches; b++) {
                seedCmds.add(new StringBuilder());
            }
            int placed = 0;
            for (int i = 0; i < cells; i++) {
                final int originX = (i % side) * PITCH - (side - 1) * PITCH / 2;
                final int originZ = (i / side) * PITCH - (side - 1) * PITCH / 2;
                for (int d = 0; d < DUSTS.length; d++) {
                    cmd.append(setblockAt(originX + DUSTS[d][0], originZ + DUSTS[d][1], "minecraft:redstone_wire"));
                }
                for (int r = 0; r < REPEATERS.length; r++) {
                    final boolean isSeed = REPEATERS[r][0] == SEED[0] && REPEATERS[r][1] == SEED[1];
                    if (isSeed) {
                        seedCmds.get(i % seedBatches).append(setblockAt(originX + REPEATERS[r][0], originZ + REPEATERS[r][1],
                            "minecraft:repeater[facing=" + FACING[r] + ",delay=1,powered=true,locked=false]"));
                    } else {
                        cmd.append(setblockAt(originX + REPEATERS[r][0], originZ + REPEATERS[r][1],
                            "minecraft:repeater[facing=" + FACING[r] + ",delay=1,powered=false,locked=false]"));
                    }
                }
                placed++;
            }
            server.getOutputStream().write(cmd.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            for (final StringBuilder seedBatch : seedCmds) {
                server.getOutputStream().write(seedBatch.toString().getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                Thread.sleep(60); // ~1.2gt 批间隔 → 8 批铺满 8gt 周期的相位谱
            }
            System.out.println("built platform (half=" + HALF + ") + " + placed + " ring cells (side="
                + side + ", pitch=" + PITCH + ", blocks/cell=12)");
            Thread.sleep(buildMs + settleMs);

            // ④ 在场探针 A：结构（RING_i，精确=N）+ 种子存在性（REP_i，精确=N）+ 缺失取证
            final java.util.TreeSet<Integer> seenRingA = probeMarker(server, logLines, cells, "RING_A");
            final java.util.TreeSet<Integer> seenRepA = probeMarker(server, logLines, cells, "REP_A");
            final long ringA = seenRingA.size();
            final long repA = seenRepA.size();
            System.out.println("ringStructuralA=" + ringA + " seedRepeaterA=" + repA + " expectedCells=" + cells);
            final java.util.TreeSet<Integer> missingA = new java.util.TreeSet<>();
            for (int i = 0; i < cells; i++) {
                if (!seenRingA.contains(i) || !seenRepA.contains(i)) {
                    missingA.add(i);
                }
            }
            forensics(server, logLines, cells, missingA);
            long ringB = -1;
            long repB = -1;
            if (ringA >= 0) {
                Thread.sleep(windowMs); // 测量窗（bot 全程站立在场，环持续振荡）
                ringB = probeMarker(server, logLines, cells, "RING_B").size();
                repB = probeMarker(server, logLines, cells, "REP_B").size();
                System.out.println("ringStructuralB=" + ringB + " seedRepeaterB=" + repB);
            }

            for (final Thread t : botThreads) {
                t.join(120_000);
                if (t.isAlive()) {
                    failures.add(new RuntimeException("bot thread did not finish: " + t.getName()));
                }
            }
            if (!failures.isEmpty()) {
                failures.forEach(f -> System.out.println("BOT-FAIL " + f)); // 服务器死亡时 bot 断连失败属预期
            } else {
                System.out.println("window done (" + bots + " bots x " + windowMs + "ms, cells=" + cells + ")");
            }
            Thread.sleep(3000);
            // 在场门：结构/种子双精确探针（A==B==N）+ 计数器门（另判）+ bot 全活
            presenceOk = !failures.isEmpty() ? false
                : cells == 0 ? true
                : ringA == cells && ringB == cells && repA == cells && repB == cells;
            System.out.println(presenceOk
                ? "presence gate PASS (RING A=B=" + ringA + " REP A=B=" + repA + " == N=" + cells + ")"
                : "presence gate FAILED: RING A=" + ringA + " B=" + ringB + " REP A=" + repA + " B=" + repB
                    + " expected=" + cells
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
        // 全量 say 回显落盘（缺失 cell 取证用——latest.log 不含 say，只在进程 stdout）
        try {
            Files.write(dir.resolve("echoes.txt"), logLines, StandardCharsets.UTF_8);
            System.out.println("echoes dumped: " + dir.resolve("echoes.txt"));
        } catch (final IOException e) {
            System.out.println("echo dump failed: " + e);
        }
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

        // ⑤ 计数器门：rs.blockTickRuns 尾 1/4 窗中位数 ≥ 0.6×N（期望 ~1.0×N：每 cell
        // 每 tick 恰 1 次中继器跳变；计划 tick 计数是振荡建立的权威度量）
        boolean activityOk = true;
        if (cells > 0) {
            final List<Double> avgs = new ArrayList<>();
            for (final String l : all) {
                if (l.contains("PapoTickProfile.count rs.blockTickRuns")) {
                    final Matcher m = AVG_PER_TICK.matcher(l);
                    if (m.find()) {
                        avgs.add(Double.parseDouble(m.group(1)));
                    }
                }
            }
            if (avgs.isEmpty()) {
                activityOk = false;
                System.out.println("activity gate FAILED: no rs.blockTickRuns windows found");
            } else {
                final List<Double> tailWindows = avgs.subList(avgs.size() * 3 / 4, avgs.size());
                final double steady = tailWindows.stream().sorted().toList().get(tailWindows.size() / 2);
                activityOk = steady >= 0.6 * cells;
                System.out.println("activity gate " + (activityOk ? "PASS" : "FAILED")
                    + ": rs.blockTickRuns steady(tail-median)=" + String.format(java.util.Locale.ROOT, "%.1f", steady)
                    + "/tick vs expected ~" + cells + " (windows=" + avgs.size() + ")");
            }
        }
        if (!presenceOk || !activityOk || errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    private static String setblockAt(final int dx, final int dz, final String block) {
        return "execute at " + ANCHOR + " run setblock ~" + dx + " ~ ~" + dz + " " + block + "\n";
    }

    /**
     * 逐 cell 结构探针（带索引标记，可取证缺失 cell）：RING_* = 粉转角 (0,0) 存在；
     * REP_* = 种子中继器位存在（minecraft:repeater 任意状态）。单批执行——只读方块
     * 态，对相位不敏感。
     */
    private static java.util.TreeSet<Integer> probeMarker(final Process server, final List<String> logLines, final int cells, final String marker) throws Exception {
        final boolean structural = marker.startsWith("RING");
        try {
            final StringBuilder sb = new StringBuilder();
            final int side = (int) Math.ceil(Math.sqrt(cells));
            for (int i = 0; i < cells; i++) {
                final int originX = (i % side) * PITCH - (side - 1) * PITCH / 2;
                final int originZ = (i / side) * PITCH - (side - 1) * PITCH / 2;
                final String predicate = structural
                    ? "minecraft:redstone_wire"
                    : "minecraft:repeater";
                final int px = structural ? originX + 0 : originX + SEED[0];
                final int pz = structural ? originZ + 0 : originZ + SEED[1];
                sb.append("execute at ").append(ANCHOR).append(" if block ~").append(px).append(" ~ ~").append(pz)
                    .append(" ").append(predicate).append(" run say ").append(marker).append('_').append(i).append('\n');
            }
            server.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            // 共享机 java 清扫判例：服务器进程被外部杀死 → stdin 管道关闭
            System.out.println("SERVER_DIED during " + marker + ": " + e);
            return new java.util.TreeSet<>(java.util.List.of(-1));
        }
        Thread.sleep(cells > 2000 ? 12_000 : 6_000); // 回显排空
        synchronized (logLines) {
            final java.util.TreeSet<Integer> seen = new java.util.TreeSet<>();
            final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(marker + "_") + "(\\d+)");
            for (final String l : logLines) {
                final java.util.regex.Matcher m = p.matcher(l);
                while (m.find()) {
                    seen.add(Integer.parseInt(m.group(1)));
                }
            }
            if (seen.size() < cells) {
                final java.util.List<Integer> missingIdx = new ArrayList<>();
                for (int i = 0; i < cells; i++) {
                    if (!seen.contains(i)) {
                        missingIdx.add(i);
                    }
                }
                System.out.println("probe " + marker + " missing indices (" + missingIdx.size() + "): " + missingIdx);
            }
            return seen;
        }
    }

    /** 缺失 cell 的全 12 槽位取证探针（CELLF 标记，回显直打——定位"缺哪块/是否整体位移"）。 */
    private static void forensics(final Process server, final List<String> logLines, final int cells, final java.util.Set<Integer> missing) throws Exception {
        System.out.println("forensics entry: missing=" + missing);
        if (missing.isEmpty()) {
            return;
        }
        final int side = (int) Math.ceil(Math.sqrt(cells));
        final StringBuilder sb = new StringBuilder();
        for (final int i : missing) {
            final int originX = (i % side) * PITCH - (side - 1) * PITCH / 2;
            final int originZ = (i / side) * PITCH - (side - 1) * PITCH / 2;
            for (int d = 0; d < DUSTS.length; d++) {
                sb.append("execute at ").append(ANCHOR).append(" if block ~").append(originX + DUSTS[d][0])
                    .append(" ~ ~").append(originZ + DUSTS[d][1])
                    .append(" minecraft:redstone_wire run say CELLF_D_").append(i).append('_').append(d).append('\n');
            }
            for (int r = 0; r < REPEATERS.length; r++) {
                sb.append("execute at ").append(ANCHOR).append(" if block ~").append(originX + REPEATERS[r][0])
                    .append(" ~ ~").append(originZ + REPEATERS[r][1])
                    .append(" minecraft:repeater run say CELLF_R_").append(i).append('_').append(r).append('\n');
            }
        }
        try {
            server.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            System.out.println("SERVER_DIED during forensics: " + e);
            return;
        }
        Thread.sleep(6000);
        synchronized (logLines) {
            System.out.println("---- CELLF forensics (present slots of missing cells) ----");
            logLines.stream().filter(l -> l.contains("CELLF_")).forEach(l -> System.out.println("  " + l.trim()));
        }
    }
}
