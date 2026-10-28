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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次152（R12）：长时生长审计——内存主线时间轴收敛的证据腿。
 *
 * R11 收敛论证的时间轴柱只靠 5-6 分钟浸泡 + 无玩家稳态腿；本 harness 让 N 个
 * bot 沿各自径向**往返循环**（出腿把出发区域甩出视距触发卸载，回腿重访走盘载
 * 重载路径），每腿末采样一次（jcmd 双 Full GC + heap_info + 类直方图），跑
 * 数十分钟。分析取**同相位样本**（隔一比对：腿长=采样周期时样本交替处于远端
 * 分散/原点聚集两态）——若同相位堆量与关键类实例数在多周期内持平，则不存在
 * 随 加载→卸载→重载 周期累积的慢增长簇，时间轴收敛闭合。
 *
 * 用法：java [-Dmemsoak.dump=true] papo.bot.LongRunGrowth <jar> [bots=12]
 *       [totalMs=2400000] [legMs=240000]
 * 输出：stdout 结构化 LongRun.* 行（每采样点 heapUsedKb + 关注类实例数），
 * 原始 heap_info/class_histogram 落盘 benchmark/results/longrun-<stamp>/。
 * 门：bot 全成功 + 日志零门错误 + 服务器 exit 0（与 MemSoakVerify 同判）。
 */
public final class LongRunGrowth {

    private static final int PORT = 25596;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s，与 MemSoakVerify 同速

    // 生长敏感关注类：区块面（应随相位振荡而非单调）、批150 模板（应持续有界）、
    // 调度状态（应随 holder 生死）、网络共享 memo（应恒定小）
    private static final String[] WATCH = {
        "byte[]",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.server.level.ChunkHolder",
        "net.minecraft.network.PapoSharedWireMemo",
        "net.minecraft.world.level.chunk.PalettedContainer",
        "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
        "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder",
        "ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder$ChunkCompletion",
        "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate$StructureBlockInfo",
        "net.minecraft.world.level.chunk.storage.RegionFile",
        // R22：转码在途视图常驻性对抗面（R21 判负证据最薄处）——若随周期单调增长即为泄漏
        "com.mojang.serialization.Dynamic",
        "ca.spottedleaf.dataconverter.types.nbt.NBTMapType",
        "net.minecraft.nbt.CompoundTag",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long totalMs = args.length > 2 ? Long.parseLong(args[2]) : 2_400_000;
        final long legMs = args.length > 3 ? Long.parseLong(args[3]) : 240_000;

        final Path outDir = Path.of("results", "longrun-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-longrun-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-longrun", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G", "-Dfile.encoding=UTF-8",
            "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd));

        final Process server = new ProcessBuilder(cmd)
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.currentTimeMillis();
        String line;
        while (System.currentTimeMillis() - bootStart < 300_000 && (line = reader.readLine()) != null) {
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
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final String name = String.format("LngB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400)); // 错峰
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinWalkCyclesAndDisconnect(totalMs, dx, dz, legMs);
                        System.out.println("LongRun.botFinalPos " + name + " x=" + (int) bot.getPosX() + " z=" + (int) bot.getPosZ());
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }));
            }
            final long startWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("LongRun.phase started bots=" + bots + " totalMs=" + totalMs + " legMs=" + legMs);

            // 每腿末采样（所有 bot 保持在线）
            final int samples = (int) (totalMs / legMs);
            for (int s = 1; s <= samples; s++) {
                final long at = startWall + s * legMs;
                final long wait = at - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                measure(server.pid(), outDir, s);
            }
            if (Boolean.getBoolean("memsoak.dump")) {
                final long t0 = System.nanoTime();
                final String dumpOut = run(server.pid(), "GC.heap_dump " + outDir.toAbsolutePath().resolve("heap.hprof"));
                System.out.println("LongRun.heapDump " + dumpOut.trim() + " tookMs=" + (System.nanoTime() - t0) / 1_000_000);
            }

            for (final CompletableFuture<Void> f : futures) {
                f.get(120, java.util.concurrent.TimeUnit.SECONDS);
            }
            System.out.println("LongRun.botsDone");
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            tail.join(5000);
        }

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("LongRun.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    private static void measure(final long pid, final Path outDir, final int sample) throws Exception {
        run(pid, "GC.run");
        Thread.sleep(3000);
        run(pid, "GC.run");
        Thread.sleep(3000);
        final String heapInfo = run(pid, "GC.heap_info");
        Files.writeString(outDir.resolve("heap_info_" + sample + ".txt"), heapInfo, StandardCharsets.UTF_8);
        final String histogram = run(pid, "GC.class_histogram");
        Files.writeString(outDir.resolve("class_histogram_" + sample + ".txt"), histogram, StandardCharsets.UTF_8);

        long usedKb = -1;
        final Matcher m = Pattern.compile("used\\s+(\\d+)K").matcher(heapInfo);
        if (m.find()) {
            usedKb = Long.parseLong(m.group(1));
        }
        System.out.println("LongRun.sample#" + sample + " heapUsedKb=" + usedKb);

        final Map<String, long[]> rows = new LinkedHashMap<>();
        final Pattern hp = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
        for (final String l : histogram.split("\n")) {
            final Matcher hm = hp.matcher(l);
            if (hm.find()) {
                rows.put(hm.group(3), new long[]{Long.parseLong(hm.group(1)), Long.parseLong(hm.group(2))});
            }
        }
        for (final String w : WATCH) {
            final long[] v = rows.get(w);
            if (v != null) {
                System.out.println("LongRun.sample#" + sample + " cls " + w + " instances=" + v[0] + " bytes=" + v[1]);
            }
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
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("jcmd " + cmd + " timeout");
        }
        return sb.toString();
    }
}
