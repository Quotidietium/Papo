package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 批次169（R28）：DFU 盘载旧数据转码面实测——此前所有重访腿均为同版本加载=零转码，
 * DFU/数据转换链从未被内存/分配画像。本 harness 取既有 12-bot 浸泡世界（径向走廊
 * 与本 harness 12 bot 的 30° 射线逐一对齐→全量盘载），先用 RegionDowngrade 把每区块
 * DataVersion 改写为旧目标（默认 3465=1.20.1），再起服复走走廊：每次区块进入视距
 * 都走完整升级链。流送峰窗 JFR 分配采样 + 驻留末双 GC 直方图（DFU 类滞留 vs R22
 * 同版本基线：NBTMapType 6,956/Dynamic 7,183=boot 常量表）。
 *
 * 用法：java [-Ddfusoak.jfr=false] papo.bot.DfuSoakCensus <jar> <sourceWorldDir>
 *       [bots=12] [spreadMs=150000] [dwellMs=180000] [targetVersion=3465]
 * 门：bot 全成功 + 日志零门错误 + 服务器 exit 0（与 MemSoakVerify 同判）。
 */
public final class DfuSoakCensus {
    private static final int PORT = 25593;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s，与源世界生成时同速同走廊

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path sourceWorld = Path.of(args[1]);
        final int bots = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        final long spreadMs = args.length > 3 ? Long.parseLong(args[3]) : 150_000;
        final long dwellMs = args.length > 4 ? Long.parseLong(args[4]) : 180_000;
        final int targetVersion = args.length > 5 ? Integer.parseInt(args[5]) : 3465;

        final Path outDir = Path.of("results", "dfusoak-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-dfusoak-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-dfusoak", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        // 复制源世界（只取 world 子树）
        final Path worldDst = dir.resolve("world");
        try (Stream<Path> s = Files.walk(sourceWorld)) {
            for (final Path src : (Iterable<Path>) s::iterator) {
                final Path dst = worldDst.resolve(sourceWorld.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        System.out.println("DfuSoak.worldCopied " + sourceWorld);

        // 降版：region/*.mca 全部区块 DataVersion → target
        final List<Path> regions = new ArrayList<>();
        try (Stream<Path> s = Files.list(worldDst.resolve("region"))) {
            s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().forEach(regions::add);
        }
        int chunks = 0, patched = 0;
        for (final Path f : regions) {
            final int[] r = RegionDowngrade.downgrade(f, targetVersion);
            chunks += r[0];
            patched += r[1];
        }
        System.out.println("DfuSoak.downgraded files=" + regions.size() + " chunks=" + chunks
            + " patched=" + patched + " target=" + targetVersion);
        if (patched == 0) {
            System.out.println("DfuSoak.gate downgrade=FAILED");
            System.exit(1);
        }

        final Process server = new ProcessBuilder(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        System.out.println("server pid=" + server.pid());
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
            final ExecutorService botPool = Executors.newFixedThreadPool(bots);
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final String name = String.format("DfuB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400));
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinWalkDwellAndDisconnect(spreadMs, dx, dz, dwellMs);
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);

            if (Boolean.getBoolean("dfusoak.jfr")) {
                Thread.sleep(25_000);
                run(server.pid(), "JFR.start name=burst settings=profile duration=60s filename="
                    + outDir.toAbsolutePath().resolve("burst.jfr") + " jdk.ObjectAllocationSample#threshold=1ms");
                System.out.println("DfuSoak.jfr started");
            }

            final long measureWall = walksStartWall + spreadMs + dwellMs - 30_000;
            final long wait = measureWall - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
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
        System.out.println("DfuSoak.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

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
        System.out.println("DfuSoak.heapUsedKb=" + usedKb);

        final String[] watch = {
            "ca.spottedleaf.dataconverter.types.nbt.NBTMapType",
            "ca.spottedleaf.dataconverter.types.MapType",
            "com.mojang.serialization.Dynamic",
            "com.mojang.datafixers",
            "net.minecraft.util.datafix",
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.chunk.LevelChunkSection",
            "net.minecraft.nbt.CompoundTag",
        };
        final Pattern hp = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
        for (final String l : histogram.split("\n")) {
            final Matcher hm = hp.matcher(l);
            if (!hm.find()) {
                continue;
            }
            for (final String w : watch) {
                if (hm.group(3).startsWith(w)) {
                    System.out.println("DfuSoak.cls " + hm.group(3) + " instances=" + hm.group(1) + " bytes=" + hm.group(2));
                }
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
        p.waitFor(60, TimeUnit.SECONDS);
        return sb.toString();
    }
}
