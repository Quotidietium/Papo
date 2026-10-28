package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
 * 批次188（R47 物品实体旋涡轴）：掉落物生命周期（生成/下落/堆叠合并/漏斗吸取
 * 非空查询/入容器）场景的分配/驻留普查。
 *
 * 动机：R24-R46 分配普查六域（流送/盘载/驻留/实体密集+保存/机械空查询/BE 驻留）
 * 中，实体密集窗的实体是"在场不动"的自然刷怪，R46 机械窗的漏斗对实体查询的答案
 * 永远是"空"——**物品实体旋涡（持续生成→飞行→着地→堆叠合并→被吸取）从未成像**，
 * 而这恰是真实农场服（刷怪塔/树场/矿山）的常开负载形态。
 *
 * 农场单元（每 bot 一座，y=200 高空带；summon 批式=一次性命令、窗内零命令伪影，
 * 探针 #4 全链实证：漏斗 TransferCooldown 活跃 + 箱内逐秒累积 + 实体 NBT 正确）：
 *   y=200  6 × hopper[facing=east] 链（cx-6..cx-1）→ 双箱（cx,cz)/(cx,cz+1)；
 *          石台 10×10（cx-12..cx-8, cz-2..cz+2）；
 *   y=201.3 漏斗柱每列 150 件（8 类轮转防跨列合并；同列同类就地堆叠=合并相负载）
 *          ≈21 items/s 吸排 → ~45-75s 持续吸取相（JFR 窗覆盖）；
 *          石台每格 3 件（邻格异类 1 格距>0.75 合并盒 → 300 件永驻站立相：
 *          tick/合并扫描/驻留面，despawn 6000t>测量轴）。
 *
 * 时间线（锚 fill 标记=全部命令处理完，非发送时刻——14.4k 召唤命令的服务器侧
 * 处理耗时不可假设）：marker+8s 流转探针①（漏斗 Items + @e 存在，JFR 窗前）
 * → marker+12s loadWarmed（JFR 60s @1ms 锚点，覆盖 drain 相）→ marker+90s
 * 流转探针②（箱 Items=cobblestone，全链完成证据，窗后）→ marker+150s 驻留测量。
 *
 * 用法：java papo.bot.ItemChurnCensus <jar> [bots=12] [spreadMs=150000] [dwellMs=300000] [fill=1]
 *
 * 门：fill 标记 + bot 全成功 + 日志零门错误 + 服务器 exit 0（脚本另验探针正证据）。
 */
public final class ItemChurnCensus {

    private static final int PORT = 25599;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（MemSoakVerify 同）
    private static final int FILL_Y = 200;
    private static final int HOPPERS = 6;
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-CHURN-FILL-DONE";
    // 8 类轮转：同格相邻互异类 → 不合并（站立相维持实体数）；同类距 0 → 就地堆叠（合并相）
    private static final String[] TYPES = {
        "minecraft:cobblestone", "minecraft:stone", "minecraft:dirt", "minecraft:sand",
        "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:gravel",
    };

    // 旋涡轴直方图关注类（+ 自然基线锚点）
    private static final String[] WATCH = {
        "net.minecraft.world.entity.item.ItemEntity",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.world.level.block.entity.HopperBlockEntity",
        "net.minecraft.world.phys.Vec3",
        "net.minecraft.core.NonNullList",
        "net.minecraft.world.level.chunk.LevelChunk",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 300_000;
        final boolean fill = args.length > 4 && Integer.parseInt(args[4]) != 0;

        final Path outDir = Path.of("results", "itemchurn-" + System.currentTimeMillis() + (fill ? "-churn" : "-natural"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-itemchurn-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-itemchurn", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  fill=" + fill);

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
            final OfflineJoinBot[] botRefs = new OfflineJoinBot[bots];
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final int idx = i;
                final String name = String.format("ICB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400));
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        botRefs[idx] = bot;
                        bot.joinWalkDwellAndDisconnect(spreadMs, dx, dz, dwellMs);
                        System.out.println("ItemChurnCensus.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("ItemChurnCensus.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " dwellMs=" + dwellMs);

            sleepUntil(walksStartWall + spreadMs + 20_000);
            final long fillWall = System.currentTimeMillis();
            final OfflineJoinBot b0 = botRefs[0];
            final int ox = (b0 == null ? 0 : (int) b0.getPosX()) - 6;
            final int oz = b0 == null ? 0 : (int) b0.getPosZ();
            long markerWall = fillWall;
            if (fill) {
                markerWall = doFills(server, botRefs, outDir, logLines);
                // 探针①（marker+8s，JFR 窗前）：漏斗 Items 非空=吸取进行中 + @e 在场。防空转假负载。
                sleepUntil(markerWall + 8_000);
                probe(server, logLines, String.format("data get block %d %d %d Items", ox + 2, FILL_Y, oz));
                probe(server, logLines, "execute if entity @e[type=minecraft:item,limit=1] run say CHURN-ITEMS-PRESENT");
                // JFR 窗锚点（marker+12s 起 60s）：覆盖 drain 相——吸取/合并/tick 全热
                sleepUntil(markerWall + 12_000);
            } else {
                sleepUntil(fillWall + 12_000);
            }
            System.out.println("ItemChurnCensus.loadWarmed fill=" + fill + " t=" + (System.currentTimeMillis() - walksStartWall) + "ms");
            if (fill) {
                // 探针②（marker+90s，JFR 窗 marker+72s 结束后）：箱 Items 含 cobblestone=全链完成证据
                sleepUntil(markerWall + 90_000);
                probe(server, logLines, String.format("data get block %d %d %d Items", ox + HOPPERS, FILL_Y, oz));
                sleepUntil(markerWall + 150_000);
            } else {
                sleepUntil(fillWall + 150_000);
            }
            measure(server.pid(), outDir);

            for (final CompletableFuture<Void> f : futures) {
                f.get(spreadMs + dwellMs + 120, TimeUnit.SECONDS);
            }
            System.out.println("bots done");
            botPool.shutdown();
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
        System.out.println("ItemChurnCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- 农场 fill 阶段 ----------

    /** 发送全部构建/召唤命令并等待 /say 标记回显（=服务器侧全部处理完），返回标记时刻。 */
    private static long doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final Path outDir, final List<String> logLines) throws Exception {
        final List<String> fills = new ArrayList<>();
        int summons = 0;
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int cx = (int) bot.getPosX();
            final int cz = (int) bot.getPosZ();
            // 漏斗排 6（x=cx-6..cx-1）→ 双箱（cx, cz)/(cx, cz+1) 沿 z 配对=54 槽容量，
            // 吸排吞吐持续整窗（探针实证：漏斗 TransferCooldown 活跃+箱内逐秒累积）
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=east]",
                cx - 6, FILL_Y, cz, cx - 1, FILL_Y, cz));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", cx, FILL_Y, cz));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", cx, FILL_Y, cz + 1));
            // 石台 10×10（x=cx-12..cx-3? 与漏斗排无重叠：cx-12..cx-8，z=cz-2..cz+2）
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                cx - 12, FILL_Y, cz - 2, cx - 8, FILL_Y, cz + 2));
            // 漏斗柱堆积：150/列 × 6 = 900（8 类轮转防堆叠合并；~21/s 吸排 → ~45s 持续吸取相）
            for (int x = 0; x < HOPPERS; x++) {
                final int hx = cx - 6 + x;
                for (int n = 0; n < 150; n++) {
                    final String t = TYPES[n % TYPES.length];
                    fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                        hx, FILL_Y + 1, cz, t));
                    summons++;
                }
            }
            // 石台堆积：10×10 格 ×3 = 300（永驻站立相——tick/合并扫描/驻留面，5min 消耗 > 测量轴）
            for (int gx = 0; gx < 10; gx++) {
                for (int gz = 0; gz < 10; gz++) {
                    for (int k = 0; k < 3; k++) {
                        final String t = TYPES[(gx * 10 + gz * 3 + k) % TYPES.length];
                        fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                            cx - 12 + gx, FILL_Y + 1, cz - 2 + gz, t));
                        summons++;
                    }
                }
            }
        }
        fills.add("/say " + MARKER);
        Files.write(outDir.resolve("fills.txt"), fills, StandardCharsets.UTF_8);

        final OutputStream stdin = server.getOutputStream();
        for (final String f : fills) {
            stdin.write((f + "\n").getBytes(StandardCharsets.UTF_8));
        }
        stdin.flush();
        System.out.println("ItemChurnCensus.fillsSent commands=" + (fills.size() - 1)
            + " summons=" + summons + " hoppers=" + botRefs.length * HOPPERS);

        final long fillStart = System.currentTimeMillis();
        final long deadline = fillStart + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent")
                    && !l.contains("--[HERE"))) {
                    System.out.println("ItemChurnCensus.fillMarkerReached burstMs=" + (System.currentTimeMillis() - fillStart));
                    return System.currentTimeMillis();
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("fill marker not seen within " + FILL_MARKER_TIMEOUT_MS + "ms");
    }

    /**
     * 发一条控制台命令（自动补 "/"），1.5s 后回读窗口内的新日志行并打印命中探针行。
     * 注意：成功的 {@code data get ... <path>} 响应只回显路径值（不含 "Items" 键名），
     * 故以 "has the following block data" 命中；错误回显（"--[HERE"）排除，防把解析失败
     * 误当正证据（双斜杠事故判例：cmd 已带斜杠再叠加前缀 → "/cmd" 未知命令）。
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
            if (l.contains("has the following block data") || l.contains("CHURN-ITEMS-PRESENT")
                || l.contains("no items were found") || l.contains("Unknown or incomplete")) {
                System.out.println("ItemChurnCensus.probe " + l.trim());
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
        System.out.println("ItemChurnCensus.heapUsedKb=" + usedKb);

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
        System.out.println("ItemChurnCensus.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("ItemChurnCensus.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("ItemChurnCensus.top " + t);
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
