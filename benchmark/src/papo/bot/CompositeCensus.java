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
 * 批次193（R52 复合负载轴）：机械+实体+方块交互多域同窗叠加分配普查——
 * R24-R51 十域全部单域隔离窗，跨域交互面（多变更者并发打同一节的
 * SectionBlocksUpdate 批处理交错/光照并发重传播/chunk 脏标记跨域竞争/
 * 混合包型广播批处理）从未成像。
 *
 * 复合形态（每 bot，y=200 空中带，creative=R48 判定形态）：
 *   挖掘域（R48 原样）：石立方 11×15×11（bx±5, y[195,209], bz±5）内保守
 *     可达球 ~793 格 × 100ms/挖（latch 起 ~79s 连续挖掘）；
 *   机械+实体域（R47 几何整体 z+8 平移=移出挖掘可达域，|dz|≤5 候选永不触及
 *     z+8 农场，bot 无法破坏自己的农场）：
 *     漏斗排 6（bx-6..bx-1, y=200, bz+8）→双箱（bx, bz+8)/(bx, bz+9)；
 *     漏斗柱 150/列 ×6（y=201.3）~21 items/s 吸排 → ~45-75s drain 相；
 *     石台 10×10 召唤格 3 件（邻格异类防合并=永驻站立相）。
 *
 * 时间线：fill+summon（~14.5k 命令，B 腿）→ marker → +8s 探针①（ctrl stone+
 * 漏斗 Items 非空+实体在场）→ +12s loadWarmed（JFR 60s@1ms 锚点，同刻 latch：
 * 挖掘与 drain 同窗叠加全窗）→ +90s 探针②（首 3 挖=air+ctrl+chest 收物+
 * 实体在场）→ +150s 驻留测量。A 腿=自然驻立基线（零 fill 零挖掘，R47 A 形），
 * B−A=复合窗全谱，逐面归因既有十域清单，未归因面=交互面候选。
 *
 * 用法：java papo.bot.CompositeCensus <jar> [bots=12] [spreadMs=150000] [digMs=160000] [cmp=1] [gridN=10] [per=3]
 *
 * gridN/per=石台召唤格边长/每格件数（批次195 R52 原形 10/3=300 站立/bot；
 * 批次196 R55 极高密度轴 20/3=1,200 站立/bot=历史普查 4× 站立存活）。
 * 门：fill 标记 + bot 全成功 + B: digsTotal≥400/bot+探针正证据（air×3+ctrl×2+
 * 漏斗 Items+chest cobblestone+实体在场）+ 日志零门错误 + 服务器 exit 0。
 */
public final class CompositeCensus {

    private static final int PORT = 25599;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（MemSoakVerify 同）
    private static final int FILL_Y = 200;
    private static final int HOPPERS = 6;
    private static final int FARM_DZ = 8; // 农场 z 偏移：挖掘候选 |dz|≤5 → 永不触及
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-CMP-FILL-DONE";
    // 8 类轮转（R47 原样）：同格邻类不合并（站立相维持）+ 同类就地堆叠（合并相）
    private static final String[] TYPES = {
        "minecraft:cobblestone", "minecraft:stone", "minecraft:dirt", "minecraft:sand",
        "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:gravel",
    };

    // 复合轴直方图关注类（R47+R48 watch 并集）
    private static final String[] WATCH = {
        "net.minecraft.world.entity.item.ItemEntity",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.world.level.block.entity.HopperBlockEntity",
        "net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket",
        "net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket",
        "net.minecraft.core.BlockPos",
        "net.minecraft.world.phys.Vec3",
        "net.minecraft.world.level.chunk.LevelChunk",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long digMs = args.length > 3 ? Long.parseLong(args[3]) : 160_000;
        final boolean cmp = args.length > 4 && Integer.parseInt(args[4]) != 0;
        final int gridN = args.length > 5 ? Integer.parseInt(args[5]) : 10;
        final int per = args.length > 6 ? Integer.parseInt(args[6]) : 3;

        final Path outDir = Path.of("results", "composite-" + System.currentTimeMillis() + (cmp ? "-cmp" : "-natural"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-composite-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=creative",
            "motd=papo-composite", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  cmp=" + cmp);

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
                final String name = String.format("CSB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400));
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        botRefs[idx] = bot;
                        bot.joinClimbSpreadDigAndDisconnect(spreadMs, dx, dz, FILL_Y, fillDone, digMs, cmp);
                        System.out.println("CompositeCensus.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("CompositeCensus.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " digMs=" + digMs + " cmp=" + cmp);

            sleepUntil(walksStartWall + spreadMs + 20_000);
            final OfflineJoinBot b0 = botRefs[0];
            if (b0 == null) {
                throw new IllegalStateException("bot 0 not joined at fill time");
            }
            final int b0x = (int) Math.floor(b0.getPosX());
            final int b0z = (int) Math.floor(b0.getPosZ());
            final int ctrlX = b0x + 7; // reach 外 control（>5.5 立方距，R48 同式）
            long markerWall;
            if (cmp) {
                markerWall = doFills(server, botRefs, outDir, logLines, ctrlX, b0z, gridN, per);
                // 探针①（marker+8s，JFR 窗前）：control=stone + 漏斗 Items 非空（drain 进行中）+ 实体在场
                sleepUntil(markerWall + 8_000);
                probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say CMP-CTRL-STONE", ctrlX, FILL_Y, b0z));
                probe(server, logLines, String.format("data get block %d %d %d Items", b0x - 4, FILL_Y, b0z + FARM_DZ));
                probe(server, logLines, "execute if entity @e[type=minecraft:item,limit=1] run say CMP-ITEMS-PRESENT");
            } else {
                markerWall = System.currentTimeMillis();
            }
            // loadWarmed=JFR 锚点（marker+12s 起 60s 窗覆盖挖掘主体+drain 相叠加），
            // 同刻 latch 放行：B 腿起挖、A 腿同节拍驻留
            sleepUntil(markerWall + 12_000);
            System.out.println("CompositeCensus.loadWarmed cmp=" + cmp + " t=" + (System.currentTimeMillis() - walksStartWall) + "ms");
            fillDone.countDown();
            if (cmp) {
                // 探针②（marker+90s，JFR 窗 marker+72s 尾后）：首 3 挖=air（破坏真实生效）+
                // ctrl=stone + chest 收物（cobblestone=全链完成）+ 实体在场（石台站立相）
                sleepUntil(markerWall + 90_000);
                final int[] tgt = b0.firstDigTargets;
                if (tgt != null) {
                    for (int k = 0; k < 3; k++) {
                        probe(server, logLines, String.format("execute if block %d %d %d minecraft:air run say CMP-DUG-AIR%d", tgt[k * 3], tgt[k * 3 + 1], tgt[k * 3 + 2], k));
                    }
                } else {
                    System.out.println("CompositeCensus.probe MISSING firstDigTargets（bot0 未起挖？）");
                }
                probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say CMP-CTRL-STONE", ctrlX, FILL_Y, b0z));
                probe(server, logLines, String.format("data get block %d %d %d Items", b0x, FILL_Y, b0z + FARM_DZ));
                probe(server, logLines, "execute if entity @e[type=minecraft:item,limit=1] run say CMP-ITEMS-PRESENT");
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
            System.out.println("CompositeCensus.digsTotal=" + digs);
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
        System.out.println("CompositeCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- fill 阶段（R48 挖掘立方 + R47 农场几何 z+8 平移） ----------

    /**
     * 每 bot：挖掘石立方 11×15×11（R48 原样，覆盖保守可达球）+ 农场（R47 几何
     * z+8 平移：漏斗排→双箱+漏斗柱 150/列×6+石台 10×10 召唤格；|dz|≤5 挖掘候选
     * 永不触及 z+8 农场=bot 无法破坏自己的农场）+ bot0 reach 外 control 石格。
     * /say 标记=全部命令服务器侧处理完（14.5k 命令处理耗时不可假设）。
     */
    private static long doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final Path outDir, final List<String> logLines,
                                final int ctrlX, final int ctrlZ,
                                final int gridN, final int per) throws Exception {
        final List<String> fills = new ArrayList<>();
        int blocks = 0;
        int summons = 0;
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int bx = (int) Math.floor(bot.getPosX());
            final int bz = (int) Math.floor(bot.getPosZ());
            // 挖掘域：石立方（R48 原样）
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                bx - 5, FILL_Y - 5, bz - 5, bx + 5, FILL_Y + 9, bz + 5));
            blocks += 11 * 15 * 11;
            // 机械+实体域：漏斗排→双箱（z+8）
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=east]",
                bx - 6, FILL_Y, bz + FARM_DZ, bx - 1, FILL_Y, bz + FARM_DZ));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", bx, FILL_Y, bz + FARM_DZ));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", bx, FILL_Y, bz + FARM_DZ + 1));
            // 石台平台 gridN×gridN（gridN=10=R47 原形 5×5 平台+带外落体；gridN>10
            // =全格站立（批次196 极端密度轴）。z 从 bz+FARM_DZ+2 起=漏斗排（bz+8）
            // 与双箱（bz+8/9）之后，与漏斗排/箱/挖掘立方（z≤bz+5）零重叠。
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                bx - 12, FILL_Y, bz + FARM_DZ + 2, bx - 12 + gridN - 1, FILL_Y, bz + FARM_DZ + 2 + gridN - 1));
            // 漏斗柱堆积：150/列 × 6 = 900（8 类轮转，~21/s 吸排 → 45-75s drain 相）
            for (int x = 0; x < HOPPERS; x++) {
                final int hx = bx - 6 + x;
                for (int n = 0; n < 150; n++) {
                    final String t = TYPES[n % TYPES.length];
                    fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                        hx, FILL_Y + 1, bz + FARM_DZ, t));
                    summons++;
                }
            }
            // 石台召唤格：gridN×gridN ×per（邻格异类防合并=永驻站立相，R47 轮转式）
            for (int gx = 0; gx < gridN; gx++) {
                for (int gz = 0; gz < gridN; gz++) {
                    for (int k = 0; k < per; k++) {
                        final String t = TYPES[(gx * 10 + gz * 3 + k) % TYPES.length];
                        fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                            bx - 12 + gx, FILL_Y + 1, bz + FARM_DZ + 2 + gz, t));
                        summons++;
                    }
                }
            }
        }
        fills.add(String.format("/setblock %d %d %d minecraft:stone", ctrlX, FILL_Y, ctrlZ));
        fills.add("/say " + MARKER);
        Files.write(outDir.resolve("fills.txt"), fills, StandardCharsets.UTF_8);

        final java.io.OutputStream stdin = server.getOutputStream();
        for (final String f : fills) {
            stdin.write((f + "\n").getBytes(StandardCharsets.UTF_8));
        }
        stdin.flush();
        System.out.println("CompositeCensus.fillsSent commands=" + (fills.size() - 1)
            + " stoneBlocks=" + blocks + " summons=" + summons + " hoppers=" + botRefs.length * HOPPERS);

        final long fillStart = System.currentTimeMillis();
        final long deadline = fillStart + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent")
                    && !l.contains("--[HERE"))) {
                    System.out.println("CompositeCensus.fillMarkerReached burstMs=" + (System.currentTimeMillis() - fillStart));
                    return System.currentTimeMillis();
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("fill marker not seen within " + FILL_MARKER_TIMEOUT_MS + "ms");
    }

    /**
     * 发一条控制台命令（自动补 "/"），1.5s 后回读窗口内的新日志行并打印命中探针行。
     * 命中集：say 正证据（CMP-*）与 data 响应（"has the following block data"）；
     * 错误回显排除（"--[HERE"，双斜杠事故判例）。
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
            if (l.contains("has the following block data") || l.contains("CMP-CTRL-STONE")
                || l.contains("CMP-DUG-AIR") || l.contains("CMP-ITEMS-PRESENT")
                || l.contains("no items were found") || l.contains("Unknown or incomplete")) {
                System.out.println("CompositeCensus.probe " + l.trim());
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
        System.out.println("CompositeCensus.heapUsedKb=" + usedKb);

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
        System.out.println("CompositeCensus.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("CompositeCensus.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("CompositeCensus.top " + t);
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
