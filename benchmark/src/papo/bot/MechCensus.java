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
 * 批次187（R46 机械负载 tick 窗分配轴）：漏斗转运+红石时钟场景的分配/驻留普查。
 *
 * 动机：R24-R42 分配普查的窗口全部是自然地形（流送/盘载/驻留/实体+保存四域），
 * R45 只对 BE 密集场景做了驻留（放置后不动的 BE），**放置的机械在 tick 中的分配流
 * 从未被成像**——而物流/分类/红石服此面常开（漏斗每 8gt 转移、中继器环持续振荡）。
 *
 * 机械负载布局（每 bot，中心=fill 时刻 bot 实际坐标，高空 y=200 不扰地形）：
 *   6 × 漏斗环（3×3 周长 8 漏斗逆时针流；N/E/S/W 四侧 /fill 顺序保证角漏斗朝向
 *      恰好续流；replaceitem 播种 64 圆石——免 NBT 语法面），物品永续环流，
 *      目标仓部分占用 → 持续 split 转移（ItemStack.copy 每次转移）；
 *   6 × 中继器-红石灰时钟环（5×4 矩形 wire 环 + 2 中继器嵌入逆流向 + 火把起振
 *      后移除；中继器环无燃尽），scheduledTick + 邻居 shape update 持续路径。
 *
 * 时间线：boot → bots join+spread → +20s fills（B 腿）→ fill+90s 打 loadWarmed
 * （脚本锚点：jcmd JFR.start 60s @1ms 阈在此起动，双腿同相位）→ fill+170s
 * GC×2+直方图（驻留）→ bots 驻留到期断开。
 *
 * 用法：java papo.bot.MechCensus <jar> [bots=12] [spreadMs=150000] [dwellMs=300000] [fill=1]
 *
 * 门：fill 标记到达（B 腿）+ bot 全成功 + 日志零门错误 + 服务器 exit 0。
 */
public final class MechCensus {

    private static final int PORT = 25598;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（MemSoakVerify 同）
    private static final int FILL_Y = 200;
    // 6 漏斗环：2 列 × 3 行，环 3×3 间距 2 → 网格 13×13；时钟环放东侧独立带
    private static final int HOPPER_RINGS = 6;
    private static final int RING_PITCH = 5;
    private static final int CLOCK_RINGS = 6;
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-MECH-FILL-DONE";

    // 机械轴直方图关注类（+ 自然基线锚点）
    private static final String[] WATCH = {
        "net.minecraft.world.level.block.entity.HopperBlockEntity",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.core.NonNullList",
        "net.minecraft.world.level.block.state.BlockState",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.world.level.chunk.PalettedContainer",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 300_000;
        final boolean fill = args.length > 4 && Integer.parseInt(args[4]) != 0;

        final Path outDir = Path.of("results", "mechcensus-" + System.currentTimeMillis() + (fill ? "-mech" : "-natural"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-mechcensus-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-mechcensus", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  fill=" + fill);

        final Process server = new ProcessBuilder(cmd)
            .directory(dir.toFile()).redirectErrorStream(true).start();
        // fill 轮询与日志 tail 线程并发读写，必须线程安全
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
            // 批次168（R27）判例：专用定长线程池使 bots 数即并发在线数。
            final ExecutorService botPool = Executors.newFixedThreadPool(bots);
            final CountDownLatch go = new CountDownLatch(1);
            final OfflineJoinBot[] botRefs = new OfflineJoinBot[bots];
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final int idx = i;
                final String name = String.format("McB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400)); // 错峰
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        botRefs[idx] = bot;
                        bot.joinWalkDwellAndDisconnect(spreadMs, dx, dz, dwellMs);
                        System.out.println("MechCensus.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("MechCensus.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " dwellMs=" + dwellMs);

            // 行走结束后等 20s 稳定（区块票据就位），然后 fill（机械腿）
            sleepUntil(walksStartWall + spreadMs + 20_000);
            final long fillWall = System.currentTimeMillis();
            if (fill) {
                doFills(server, botRefs, outDir, logLines);
                // +45s 探针：读首个播种漏斗的 Items——若 slot0 已非 64 圆石（或已空）
                // 即物品在环内真实流转（防空转假负载）；输出原样带 MechCensus.probe 前缀
                sleepUntil(fillWall + 45_000);
                final OfflineJoinBot b0 = botRefs[0];
                final int px = (int) b0.getPosX() - 6;
                final int pz = (int) b0.getPosZ() - 6;
                final long before = logLines.size();
                server.getOutputStream().write(String.format("/data get block %d %d %d Items\n", px, FILL_Y, pz).getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                Thread.sleep(3000);
                final List<String> snap = new ArrayList<>(logLines);
                for (final String l : snap.subList((int) Math.min(before, snap.size()), snap.size())) {
                    if (l.contains("Items") || l.contains("has the following")) {
                        System.out.println("MechCensus.probe " + l.trim());
                    }
                }
                // 振荡探针：观察者 powered 两次采样——passed/failed 交替（或任一 passed）
                // =时钟活着；观察者无 BE 故 data get 不可用，走 execute if block
                final int rx = (int) b0.getPosX() + 6;
                final int rz = (int) b0.getPosZ() - 6;
                final long rb = logLines.size();
                int anyPass = 0;
                for (int s = 0; s < 10; s++) {
                    server.getOutputStream().write(String.format("/execute if block %d %d %d minecraft:observer[powered=true]\n", rx, FILL_Y, rz).getBytes(StandardCharsets.UTF_8));
                    server.getOutputStream().flush();
                    Thread.sleep(200);
                }
                Thread.sleep(2000);
                final List<String> snap2 = new ArrayList<>(logLines);
                for (final String l : snap2.subList((int) Math.min(rb, snap2.size()), snap2.size())) {
                    if (l.contains("Test ")) {
                        System.out.println("MechCensus.clockProbe " + l.trim());
                    }
                }
            }
            // +90s：物品在环内分布收敛/时钟起振稳定——脚本在此锚点起 JFR（双腿同相位）
            sleepUntil(fillWall + 90_000);
            System.out.println("MechCensus.loadWarmed fill=" + fill + " t=" + (System.currentTimeMillis() - walksStartWall) + "ms");
            // JFR 60s 窗 + 余量，随后驻留测量
            sleepUntil(fillWall + 170_000);
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

        // 门错误判定走服务器自身日志（管道捕获丢行判例，批次85）
        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("MechCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- 机械 fill 阶段 ----------

    private static void doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final Path outDir, final List<String> logLines) throws Exception {
        final List<String> fills = new ArrayList<>();
        int hoppers = 0;
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int cx = (int) bot.getPosX();
            final int cz = (int) bot.getPosZ();
            // ---- 6 × 漏斗环（2 列 × 3 行，网格 pitch 5 → 13×13 占地）----
            for (int r = 0; r < HOPPER_RINGS; r++) {
                final int ox = cx - 6 + (r % 2) * RING_PITCH;
                final int oz = cz - 6 + (r / 2) * RING_PITCH;
                // N/E/S/W 顺序：角块终朝向=NE 南、SE 西、SW 北、NW 东（N 先写 W 后写避开 NW 覆盖），逆时针续流
                fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=east]",
                    ox, FILL_Y, oz, ox + 2, FILL_Y, oz));
                fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=south]",
                    ox + 2, FILL_Y, oz, ox + 2, FILL_Y, oz + 2));
                fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=west]",
                    ox + 2, FILL_Y, oz + 2, ox, FILL_Y, oz + 2));
                fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=north]",
                    ox, FILL_Y, oz + 2, ox, FILL_Y, oz + 1));
                // 播种：NW 漏斗一格 64 圆石（replaceitem 免 NBT 语法面），环流后分裂持续
                fills.add(String.format("/replaceitem block %d %d %d container.0 minecraft:cobblestone 64",
                    ox, FILL_Y, oz));
                hoppers += 8;
            }
            // ---- 6 × 观察者对时钟（东侧独立带；相互对望=经典 2gt 自启永续振荡，
            //      观察者=完整固体方块无支撑要求，setblock 邻居更新即触发，无燃尽）----
            for (int c = 0; c < CLOCK_RINGS; c++) {
                final int ox = cx + 6;
                final int oz = cz - 6 + c * 5;
                fills.add(String.format("/setblock %d %d %d minecraft:observer[facing=east]",
                    ox, FILL_Y, oz));
                fills.add(String.format("/setblock %d %d %d minecraft:observer[facing=west]",
                    ox + 1, FILL_Y, oz));
            }
        }
        fills.add("/say " + MARKER);
        Files.write(outDir.resolve("fills.txt"), fills, StandardCharsets.UTF_8);

        final OutputStream stdin = server.getOutputStream();
        for (final String f : fills) {
            stdin.write((f + "\n").getBytes(StandardCharsets.UTF_8));
        }
        stdin.flush();
        System.out.println("MechCensus.fillsSent commands=" + (fills.size() - 1)
            + " hoppers=" + hoppers + " clocks=" + botRefs.length * CLOCK_RINGS);

        final long deadline = System.currentTimeMillis() + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent"))) {
                    System.out.println("MechCensus.fillMarkerReached");
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("fill marker not seen within " + FILL_MARKER_TIMEOUT_MS + "ms");
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
        System.out.println("MechCensus.heapUsedKb=" + usedKb);

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
        System.out.println("MechCensus.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("MechCensus.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("MechCensus.top " + t);
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
