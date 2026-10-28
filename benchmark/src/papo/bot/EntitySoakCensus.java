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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次166（R25）：实体密集面内存画像——R1-R24 全部场景为 peaceful+spectator，
 * 实体对象图（AI 状态/感知器/寻路/同步数据/生物群系适配）从未被画像。
 * 本 harness 与 MemSoakVerify 逐字同协议，仅改 server.properties：
 * difficulty=normal + spawn-monsters=true（行走进全新区块=洞穴/夜晚自然刷怪），
 * 驻留加长到 300s 让怪群成熟，驻留末 jcmd 双 GC + 直方图，按
 * net.minecraft.world.entity 前缀聚合并打印每实体类行。
 *
 * 用法：java [-Dentitysoak.dump=true] papo.bot.EntitySoakCensus <jar> [bots=12]
 *       [spreadMs=150000] [dwellMs=300000]
 * 门：bot 全成功 + 日志零门错误 + 服务器 exit 0（与 MemSoakVerify 同判）。
 */
public final class EntitySoakCensus {
    private static final int PORT = 25594;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";
    private static final double BLOCKS_PER_TICK = 0.25; // 5 b/s，与 MemSoakVerify 同速

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 300_000;

        final Path outDir = Path.of("results", "entitysoak-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-entitysoak-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=normal", "spawn-monsters=true", "gamemode=spectator",
            "motd=papo-entitysoak", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

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
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final String name = String.format("EntB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400)); // 错峰
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinWalkDwellAndDisconnect(spreadMs, dx, dz, dwellMs);
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);

            // 驻留末测量（怪群成熟后）
            final long measureWall = walksStartWall + spreadMs + dwellMs - 30_000;
            final long wait = measureWall - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            measure(server.pid(), outDir);
            if (Boolean.getBoolean("entitysoak.dump")) {
                final long t0 = System.nanoTime();
                final String dumpOut = run(server.pid(), "GC.heap_dump " + outDir.toAbsolutePath().resolve("heap.hprof"));
                System.out.println("EntitySoak.heapDump " + dumpOut.trim() + " tookMs=" + (System.nanoTime() - t0) / 1_000_000);
            }

            for (final CompletableFuture<Void> f : futures) {
                f.get(spreadMs + dwellMs + 120, TimeUnit.SECONDS);
            }
            System.out.println("bots done");
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
        System.out.println("EntitySoak.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
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
        System.out.println("EntitySoak.heapUsedKb=" + usedKb);

        // 实体面聚合：net.minecraft.world.entity 前缀（去掉模块后缀）
        final Map<String, long[]> ents = new LinkedHashMap<>();
        long entInst = 0, entBytes = 0;
        final Pattern hp = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(\\s+\\([^)]*\\))?");
        for (final String l : histogram.split("\n")) {
            final Matcher hm = hp.matcher(l);
            if (hm.find()) {
                final String cls = hm.group(3);
                if (cls.startsWith("net.minecraft.world.entity")) {
                    final long[] v = new long[]{Long.parseLong(hm.group(1)), Long.parseLong(hm.group(2))};
                    ents.put(cls, v);
                    entInst += v[0];
                    entBytes += v[1];
                }
            }
        }
        System.out.println("EntitySoak.entityTotal instances=" + entInst + " bytes=" + entBytes);
        ents.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
            .limit(40)
            .forEach(e -> System.out.println("EntitySoak.ent " + e.getKey()
                + " instances=" + e.getValue()[0] + " bytes=" + e.getValue()[1]));

        // 支撑结构关注类（AI/寻路/感知/同步数据——每实体一份的面）
        final String[] support = {
            "net.minecraft.world.entity.ai.Brain",
            "net.minecraft.world.entity.ai.memory.MemoryModuleType",
            "net.minecraft.world.entity.ai.goal.GoalSelector",
            "net.minecraft.world.entity.ai.navigation.PathNavigation",
            "net.minecraft.world.entity.ai.sensing.Sensing",
            "net.minecraft.network.syncher.SynchedEntityData",
            "net.minecraft.world.entity.ai.attributes.AttributeMap",
            "net.minecraft.world.entity.ai.attributes.AttributeInstance",
            "net.minecraft.world.level.pathfinder.Path",
            "net.minecraft.world.level.pathfinder.Node",
            "net.minecraft.world.entity.schedule.Schedule",
            "net.minecraft.world.entity.schedule.Activity",
            "net.minecraft.world.entity.monster.Phantom"
        };
        for (final String s : support) {
            for (final String l : histogram.split("\n")) {
                final Matcher hm = hp.matcher(l);
                if (hm.find() && hm.group(3).startsWith(s)) {
                    System.out.println("EntitySoak.cls " + hm.group(3) + " instances=" + hm.group(1) + " bytes=" + hm.group(2));
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
