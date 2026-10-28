package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次189（R48 方块交互/挖掘风暴轴）：creative 挖掘负载（真实玩家头号动作——
 * 挖掘/建筑服常态）下服务端方块状态变更链（destroyAndAck→setBlock→光照增量+
 * 邻居通知+BLOCK_UPDATE 广播+ACK）的分配普查窗。
 *
 * 动机：R24-R47 分配普查六域（流送/盘载/驻留/实体+保存/机械空查询/物品旋涡）
 * 没有任何窗含方块交互——方块状态变更面从未成像；creative 即时挖掘=零工具依赖、
 * 零掉落物实体（R47 已覆盖域）、确定性高（instabuild 直通 destroyAndAck）。
 *
 * 形态（每 bot，y=200 空中带=纯空气无 clip 纠偏，creative 无 spectator noPhysics
 * 豁免故以不遇山的高度飞；批次141 判例的 creative 等价形态）：
 *   爬升+径向撒布 150s 定点 → harness /fill 石立方 11×15×11（覆盖保守可达球
 *   r=5.0，两腿同发=delta 削去 fill 面）+ reach 外 control 石格 → /say 标记 →
 *   latch 放行 → B 腿按（距离,y,x,z）确定序 100ms/挖全球 ~650 格/bot
 *   （~7,800 挖/窗）；A 腿同节拍驻留零挖掘包。
 *
 * 时间线：marker+8s 探针①（control=stone，fill 落地）→ marker+20s latch（挖掘
 * 开始；腿脚本 boot+185s 起 JFR 60s@1ms≈marker+15..75 覆盖挖掘主体）→
 * marker+90s 探针②（B 腿 bot0 首 3 挖目标=air=破坏真实生效正证据+control=stone；
 * A 腿立方内格=stone）→ marker+150s 驻留测量（bot 全程在线=区块票据）。
 *
 * 用法：java papo.bot.DigStormCensus <jar> [bots=12] [spreadMs=150000] [digMs=160000] [dig=1]
 *
 * 门：fill 标记 + bot 全成功 + B 腿 digsSent≥400/bot（A 腿=0）+ 探针②正证据 +
 * 日志零门错误 + 服务器 exit 0。
 */
public final class DigStormCensus {

    private static final int PORT = 25599;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（MemSoakVerify 同）
    private static final int FILL_Y = 200;
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-DIG-FILL-DONE";

    // 挖掘风暴轴直方图关注类（+ 自然基线锚点）
    private static final String[] WATCH = {
        "net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket",
        "net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket",
        "net.minecraft.core.BlockPos",
        "net.minecraft.world.phys.shapes.VoxelShape",
        "net.minecraft.world.level.block.state.BlockState$",
        "net.minecraft.world.level.chunk.LevelChunk",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long digMs = args.length > 3 ? Long.parseLong(args[3]) : 160_000;
        final boolean dig = args.length > 4 && Integer.parseInt(args[4]) != 0;

        final Path outDir = Path.of("results", "digstorm-" + System.currentTimeMillis() + (dig ? "-dig" : "-natural"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-digstorm-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=creative",
            "motd=papo-digstorm", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  dig=" + dig);

        final Process server = new ProcessBuilder(cmd)
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
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
            } catch (final Exception ignored) {
            }
        }, "log-tail");
        tail.setDaemon(true);
        tail.start();
        Thread.sleep(2000);

        try {
            final ExecutorService botPool = Executors.newFixedThreadPool(bots);
            final CountDownLatch go = new CountDownLatch(1);
            final CountDownLatch fillDone = new CountDownLatch(1);
            final OfflineJoinBot[] botRefs = new OfflineJoinBot[bots];
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final int idx = i;
                final String name = String.format("DSB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400));
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        botRefs[idx] = bot;
                        bot.joinClimbSpreadDigAndDisconnect(spreadMs, dx, dz, FILL_Y, fillDone, digMs, dig);
                        System.out.println("DigStormCensus.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("DigStormCensus.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " digMs=" + digMs + " dig=" + dig);

            sleepUntil(walksStartWall + spreadMs + 20_000);
            final OfflineJoinBot b0 = botRefs[0];
            if (b0 == null) {
                throw new IllegalStateException("bot 0 not joined at fill time");
            }
            final int ctrlX = (int) Math.floor(b0.getPosX()) + 7; // reach 外 control（>5.5 立方距）
            final int ctrlZ = (int) Math.floor(b0.getPosZ());
            final long markerWall = doFills(server, botRefs, outDir, logLines, ctrlX, ctrlZ);
            // 探针①（marker+8s）：control=stone（fill 落地 + 立方未被挖）
            sleepUntil(markerWall + 8_000);
            probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say DIG-CTRL-STONE", ctrlX, FILL_Y, ctrlZ));
            // latch=marker+20s：挖掘开始（JFR 窗由腿脚本 boot+185s 起 60s 覆盖主体）
            sleepUntil(markerWall + 20_000);
            System.out.println("DigStormCensus.loadWarmed dig=" + dig + " t=" + (System.currentTimeMillis() - walksStartWall) + "ms");
            fillDone.countDown();
            // 探针②（marker+90s，JFR 窗尾后）：B 腿 bot0 首 3 挖目标=air（破坏真实生效）+control=stone
            sleepUntil(markerWall + 90_000);
            if (dig) {
                final int[] tgt = b0.firstDigTargets;
                if (tgt != null) {
                    for (int k = 0; k < 3; k++) {
                        probe(server, logLines, String.format("execute if block %d %d %d minecraft:air run say DIG-DUG-AIR%d", tgt[k * 3], tgt[k * 3 + 1], tgt[k * 3 + 2], k));
                    }
                } else {
                    System.out.println("DigStormCensus.probe MISSING firstDigTargets（bot0 未起挖？）");
                }
                probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say DIG-CTRL-STONE", ctrlX, FILL_Y, ctrlZ));
            } else {
                // A 腿：立方内格仍=stone（无人挖掘的对称性证据）
                probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say DIG-CTRL-STONE", ctrlX, FILL_Y, ctrlZ));
            }
            sleepUntil(markerWall + 150_000);
            measure(server.pid(), outDir);

            for (final CompletableFuture<Void> f : futures) {
                f.get(spreadMs + digMs + 120, TimeUnit.SECONDS);
            }
            long digs = 0;
            for (final OfflineJoinBot bot : botRefs) {
                if (bot != null) {
                    digs += bot.getDigsSent();
                }
            }
            System.out.println("bots done");
            botPool.shutdown();
            System.out.println("DigStormCensus.digsTotal=" + digs);
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(120, TimeUnit.SECONDS);
            tail.join(5000);
        }

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("DigStormCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- fill 阶段 ----------

    /**
     * 每 bot 石立方 11×15×11（(bx±5)×y[195,209]×(bz±5)，覆盖保守可达球 r=5.0 全部候选格）
     * + bot0 的 reach 外 control 石格（(bx+7, 200, bz)，立方距 >5.5=服务端真门外），
     * /say 标记=全部命令处理完。两腿同发：fill 面在 A/B delta 中相消。
     */
    private static long doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final Path outDir, final List<String> logLines,
                                final int ctrlX, final int ctrlZ) throws Exception {
        final List<String> fills = new ArrayList<>();
        int blocks = 0;
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int bx = (int) Math.floor(bot.getPosX());
            final int bz = (int) Math.floor(bot.getPosZ());
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                bx - 5, FILL_Y - 5, bz - 5, bx + 5, FILL_Y + 9, bz + 5));
            blocks += 11 * 15 * 11;
        }
        fills.add(String.format("/setblock %d %d %d minecraft:stone", ctrlX, FILL_Y, ctrlZ));
        fills.add("/say " + MARKER);
        Files.write(outDir.resolve("fills.txt"), fills, StandardCharsets.UTF_8);

        final java.io.OutputStream stdin = server.getOutputStream();
        for (final String f : fills) {
            stdin.write((f + "\n").getBytes(StandardCharsets.UTF_8));
        }
        stdin.flush();
        System.out.println("DigStormCensus.fillsSent commands=" + (fills.size() - 1)
            + " stoneBlocks=" + blocks + " bots=" + botRefs.length);

        final long fillStart = System.currentTimeMillis();
        final long deadline = fillStart + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent")
                    && !l.contains("--[HERE"))) {
                    System.out.println("DigStormCensus.fillMarkerReached burstMs=" + (System.currentTimeMillis() - fillStart));
                    return System.currentTimeMillis();
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("fill marker not seen within " + FILL_MARKER_TIMEOUT_MS + "ms");
    }

    /**
     * 发一条控制台命令（自动补 "/"），1.5s 后回读窗口内的新日志行并打印命中探针行。
     * 命中集：say 正证据（DIG-*）与错误回显排除（"--[HERE"，双斜杠事故判例）。
     */
    private static void probe(final Process server, final List<String> logLines, final String cmd) throws Exception {
        final long before = logLines.size();
        server.getOutputStream().write(("/" + cmd + "\n").getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(1500);
        final List<String> snap = new ArrayList<>(logLines);
        for (final String l : snap.subList((int) Math.min(before, snap.size()), snap.size())) {
            if (l.contains("--[HERE")) {
                continue;
            }
            if (l.contains("DIG-DUG-AIR") || l.contains("DIG-CTRL-STONE") || l.contains("Unknown or incomplete")) {
                System.out.println("DigStormCensus.probe " + l.trim());
            }
        }
    }

    private static void sleepUntil(final long wall) throws InterruptedException {
        final long wait = wall - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    // ---------- jcmd 测量（MemSoakVerify 同制式） ----------

    private static void measure(final long pid, final Path outDir) throws Exception {
        run(pid, "GC.run");
        Thread.sleep(3000);
        run(pid, "GC.run");
        Thread.sleep(3000);
        final String heapInfo = run(pid, "GC.heap_info");
        Files.writeString(outDir.resolve("heap_info.txt"), heapInfo, StandardCharsets.UTF_8);
        final String histogram = run(pid, "GC.class_histogram");
        Files.writeString(outDir.resolve("class_histogram.txt"), histogram, StandardCharsets.UTF_8);

        long usedKb = -1;
        final Matcher m = Pattern.compile("used\\s+(\\d+)K").matcher(heapInfo);
        if (m.find()) {
            usedKb = Long.parseLong(m.group(1));
        }
        System.out.println("DigStormCensus.heapUsedKb=" + usedKb);

        final Map<String, long[]> rows = new LinkedHashMap<>();
        final Pattern hp = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
        int rank = 0;
        final List<String> top = new ArrayList<>();
        for (final String l : histogram.split("\n")) {
            final Matcher hm = hp.matcher(l);
            if (hm.find()) {
                rank++;
                rows.put(hm.group(3), new long[]{Long.parseLong(hm.group(1)), Long.parseLong(hm.group(2))});
                if (top.size() < 25) {
                    top.add(l.trim());
                }
            }
        }
        System.out.println("DigStormCensus.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("DigStormCensus.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("DigStormCensus.top " + t);
        }
    }

    private static String run(final long pid, final String cmd) throws Exception {
        final Process p = new ProcessBuilder(JCMD, String.valueOf(pid), cmd).redirectErrorStream(true).start();
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append('\n');
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("jcmd " + cmd + " timeout");
        }
        return sb.toString();
    }
}
