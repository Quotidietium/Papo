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
 * 批次186（R45 方块实体密集内容轴）：BE 密集场景的稳态堆驻留普查。
 *
 * 动机：R5-R22 全部驻留普查都在自然地形上进行（荒野容器类 BE≈0），真实服务器的
 * 基地/农场（箱子/木桶/熔炉/漏斗密集）内容面从未被画像。本工具在 MemSoakVerify
 * 同场景（12 bot 径向行走 + 驻留持票）基础上，于驻留窗内经服务器 stdin 注入 fill
 * 命令，在每个 bot 磁盘上放置条带状容器层（高空 y=200，不扰地形），随后 GC×2 +
 * 直方图——B(密集) vs A(自然) 的逐类差分即 BE 内容轴驻留表。
 *
 * fill 布局（每 bot，中心=fill 时刻 bot 实际坐标，免疫出生点偏移）：
 *   11 条 150×3 条带（z 间隔 13），类型 i%4：3 桶/3 箱/3 炉/2 漏斗
 *   = 每 bot 4,050 BE；12 bot 合计 48,600 BE（~14.6k/14.6k/14.6k/9.7k）。
 *
 * 用法：java papo.bot.BeDensifyCensus <jar> [bots=12] [spreadMs=150000] [dwellMs=300000]
 *       [fill=1] [extraJvmFlags(可选，;分隔)]
 *
 * 输出：stdout 结构化 BeCensus.* 行；原始 heap_info/class_histogram/fills.txt
 * 落盘 benchmark/results/becensus-<stamp>/。
 * 门：fill 标记到达 + bot 全成功 + 日志零门错误 + 服务器 exit 0。
 */
public final class BeDensifyCensus {

    private static final int PORT = 25597;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（MemSoakVerify 同）
    private static final int FILL_Y = 200; // 高空层：不触地形、不产生邻居级联
    private static final int STRIP_LEN = 150;
    private static final int STRIP_W = 3;
    private static final int STRIPS = 11;
    private static final int STRIP_PITCH = 13;
    private static final String[] STRIP_TYPES = {"barrel", "chest", "furnace", "hopper"};
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-FILL-DONE";

    // BE 内容轴直方图关注类（+ 自然基线锚点类）
    private static final String[] WATCH = {
        "net.minecraft.world.level.block.entity.BarrelBlockEntity",
        "net.minecraft.world.level.block.entity.ChestBlockEntity",
        "net.minecraft.world.level.block.entity.FurnaceBlockEntity",
        "net.minecraft.world.level.block.entity.HopperBlockEntity",
        "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity",
        "net.minecraft.core.NonNullList",
        "java.util.Arrays$ArrayList",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.world.level.block.entity.BlockEntity",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.world.level.chunk.PalettedContainer",
        "byte[]",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 300_000;
        final boolean fill = args.length > 4 && Integer.parseInt(args[4]) != 0;
        final String extraFlags = args.length > 5 ? args[5] : "";

        final Path outDir = Path.of("results", "becensus-" + System.currentTimeMillis() + (fill ? "-dense" : "-natural"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-becensus-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-becensus", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8"));
        if (!extraFlags.isBlank()) {
            for (final String f : extraFlags.split(";")) {
                if (!f.isBlank()) cmd.add(f.trim());
            }
        }
        cmd.addAll(List.of("-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  fill=" + fill);

        final Process server = new ProcessBuilder(cmd)
            .directory(dir.toFile()).redirectErrorStream(true).start();
        // fill 轮询与日志 tail 线程并发读写，必须线程安全（MemSoakVerify 是 join 后才读故无此需求）
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
                final String name = String.format("BeB%02d", i);
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
                        System.out.println("BeCensus.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("walk phase started (" + bots + " bots x " + spreadMs + "ms spread)");

            // 行走结束后等待 20s 稳定（区块票据就位），然后 fill（密集腿）
            sleepUntil(walksStartWall + spreadMs + 20_000);
            if (fill) {
                doFills(server, botRefs, outDir, logLines);
            }
            // fill 后稳定 45s（BE ticker 自旋/惰性结构初始化），再 GC×2 + 直方图
            Thread.sleep(45_000);
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
        System.out.println("BeCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- fill 阶段 ----------

    private static void doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final Path outDir, final List<String> logLines) throws Exception {
        final List<String> fills = new ArrayList<>();
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int cx = (int) bot.getPosX();
            final int cz = (int) bot.getPosZ();
            for (int s = 0; s < STRIPS; s++) {
                final String type = STRIP_TYPES[s % STRIP_TYPES.length];
                final int z0 = cz - (STRIPS * STRIP_PITCH) / 2 + s * STRIP_PITCH;
                fills.add(String.format("/fill %d %d %d %d %d %d minecraft:%s",
                    cx - STRIP_LEN / 2, FILL_Y, z0,
                    cx + STRIP_LEN / 2 - 1, FILL_Y, z0 + STRIP_W - 1, type));
            }
        }
        fills.add("/say " + MARKER);
        Files.write(outDir.resolve("fills.txt"), fills, StandardCharsets.UTF_8);

        final OutputStream stdin = server.getOutputStream();
        for (final String f : fills) {
            stdin.write((f + "\n").getBytes(StandardCharsets.UTF_8));
        }
        stdin.flush();
        System.out.println("BeCensus.fillsSent commands=" + (fills.size() - 1)
            + " expectedBEs=" + (botRefs.length * STRIPS * STRIP_LEN * STRIP_W));

        final long deadline = System.currentTimeMillis() + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                // say 的输出含 <Server> 前缀；日志线程并发 add，拷贝快照判读
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent"))) {
                    System.out.println("BeCensus.fillMarkerReached");
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
        System.out.println("BeCensus.heapUsedKb=" + usedKb);

        final Map<String, long[]> rows = new LinkedHashMap<>();
        final Pattern hp = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
        int rank = 0;
        final List<String> top = new ArrayList<>();
        for (final String l : histogram.split("\n")) {
            final Matcher hm = hp.matcher(l);
            if (hm.find()) {
                rank++;
                final String cls = hm.group(3);
                rows.put(cls, new long[]{Long.parseLong(hm.group(1)), Long.parseLong(hm.group(2))});
                if (top.size() < 25) {
                    top.add(l.trim());
                }
            }
        }
        System.out.println("BeCensus.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("BeCensus.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("BeCensus.top " + t);
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
