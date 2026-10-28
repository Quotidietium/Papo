package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 批次141（R5 内存主题）：多玩家多区块加载的稳态堆驻留测量。
 *
 * 场景：N 个 bot 以固定种子向 N 个方向匀速行走扩散（窗口分离），随后原地驻留——
 * 每个常驻玩家持有 (2·vd+1)² 个已加载且已发送的区块（区块包缓存全量驻留的
 * 最大形态）。驻留窗口内用 jcmd 做两次 Full GC 后取 heap_info used + 类直方图，
 * 用于对比优化前后的活集（live set）与缓存对象实例数。
 *
 * 用法：java papo.bot.MemSoakVerify <jar> [bots=12] [spreadMs=150000] [dwellMs=180000]
 *       [measureAtMs=120000] [extraJvmFlags(可选，;分隔，如 -Dpapo.chunkPacketCacheTtlTicks=600)]
 *
 * 输出：stdout 结构化 MemSoak.* 行（heapUsedKb / 直方图关注类实例与字节），
 * 原始 heap_info/class_histogram 落盘 benchmark/results/memsoak-<stamp>/。
 * 门：bot 全成功 + 日志零门错误 + 服务器 exit 0。
 */
public final class MemSoakVerify {

    private static final int PORT = 25596;
    // Papo - batch 209: follow the harness JVM (java.home) instead of a pinned absolute path
    // (the F:/Java/21 install vanished; harness is expected to run on JDK 25 post-migration).
    private static final String JAVA = new java.io.File(new java.io.File(System.getProperty("java.home"), "bin"),
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").getAbsolutePath();
    private static final String JCMD = new java.io.File(new java.io.File(System.getProperty("java.home"), "bin"),
        System.getProperty("os.name", "").toLowerCase().contains("win") ? "jcmd.exe" : "jcmd").getAbsolutePath();
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s（TickSurveyBench 实证速度）

    // 直方图关注类（内存驻留画像 + 缓存归因）
    private static final String[] WATCH = {
        "byte[]",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.server.level.ChunkHolder",
        "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",
        "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData",
        "net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData",
        "net.minecraft.network.PapoSharedWireMemo",
        "net.minecraft.world.level.chunk.PalettedContainer",
        "net.minecraft.world.level.levelgen.NoiseChunk", // 批次143（R5 轮3）
        "net.minecraft.world.level.levelgen.DensityFunctions$HolderHolder",
        "net.minecraft.core.Holder$Direct",
        "net.minecraft.util.CubicSpline$Multipoint",
        "net.minecraft.world.level.chunk.status.ChunkStepBudget", // 可能不存在，容错
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 180_000;
        final long measureAtMs = args.length > 4 ? Long.parseLong(args[4]) : 120_000;
        final String extraFlags = args.length > 5 ? args[5] : "";

        final Path outDir = Path.of("results", "memsoak-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-memsoak-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-memsoak", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8"));
        if (!extraFlags.isBlank()) {
            for (final String f : extraFlags.split(";")) {
                if (!f.isBlank()) cmd.add(f.trim());
            }
        }
        cmd.addAll(List.of("-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd));

        final Process server = new ProcessBuilder(cmd)
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
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
            // 批次168（R27）：runAsync 走 commonPool（并行度≈核数-1），bots 超过它时任务
            // 分批排队（60 bot 实测裂成 31+29 两批，第二批在首批全部断开后才启动）——
            // 测量点在线数与 bots 参数不符。专用定长线程池使 bots 数即并发在线数；
            // bots≤并行度时与 commonPool 行为等价（12 bot 历史全并发）。
            final ExecutorService botPool = Executors.newFixedThreadPool(bots);
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final String name = String.format("MemB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400)); // 错峰
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinWalkDwellAndDisconnect(spreadMs, dx, dz, dwellMs);
                        System.out.println("MemSoak.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60); // 让错峰 join 完成，行走进度近似对齐
            System.out.println("walk phase started (" + bots + " bots x " + spreadMs + "ms spread)");

            // 驻留窗口内测量（所有 bot 仍在线持有区块票据）
            final long measureWall = walksStartWall + spreadMs + measureAtMs;
            final long wait = measureWall - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            measure(server.pid(), outDir);
            // 批次144（R5 轮4）：可选堆转储（-Dmemsoak.dump=true），供 HeapRefAttr 持有链归因
            if (Boolean.getBoolean("memsoak.dump")) {
                final long t0 = System.nanoTime();
                final String dumpOut = run(server.pid(), "GC.heap_dump " + outDir.toAbsolutePath().resolve("heap.hprof"));
                System.out.println("MemSoak.heapDump " + dumpOut.trim() + " tookMs=" + (System.nanoTime() - t0) / 1_000_000);
            }

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
        System.out.println("MemSoak.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- jcmd 测量 ----------

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
        System.out.println("MemSoak.heapUsedKb=" + usedKb);

        // 解析直方图：#instances / #bytes / class name
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
        System.out.println("MemSoak.histogramRows=" + rank);
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("MemSoak.cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
        }
        System.out.println("---- class_histogram top 25 ----");
        for (final String t : top) {
            System.out.println("MemSoak.top " + t);
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
