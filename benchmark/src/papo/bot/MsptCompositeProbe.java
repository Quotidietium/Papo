package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次195（R54 复合负载 MSPT 版本配对守护）：R52 复合形态（挖掘+漏斗 drain+
 * 站立实体同窗叠加）下的 Paper 控制台 mspt 多点采样——0298 收割的延迟维守护
 * 在 R47 当时只在旋涡单域形态跑过（msptload-r47legs），复合高压格未测。
 *
 * 时间线与 CompositeCensus 逐字同制（bot 零改动 joinClimbSpreadDig
 * AndDisconnect；14,461 命令 fill+summon；marker+12 latch 起挖）；采样点：
 * 流送相 1 点（spread 120s）+ 复合窗 3 点（marker+20/40/60：挖掘 10/s/bot+
 * drain 全热）+ 后段 2 点（marker+100/130：站立相+漏斗静息）。门同
 * CompositeCensus（digsTotal/air/ctrl/hopper/chest/present）——MSPT 腿的
 * 负载真实性由同门保证（批次186 判例：MSPT 腿须 bot 在线正证据）。
 *
 * 用法：java papo.bot.MsptCompositeProbe <jar> [bots=12] [spreadMs=150000] [digMs=160000]
 */
public final class MsptCompositeProbe {

    private static final int PORT = 25597;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final double BLOCKS_PER_TICK = 0.25;
    private static final int FILL_Y = 200;
    private static final int HOPPERS = 6;
    private static final int FARM_DZ = 8;
    private static final long FILL_MARKER_TIMEOUT_MS = 180_000;
    private static final String MARKER = "PAP0-MCMP-FILL-DONE";
    private static final Pattern NUMS = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?),"
            + "\\s*(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?),"
            + "\\s*(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)");
    private static final String[] TYPES = {
        "minecraft:cobblestone", "minecraft:stone", "minecraft:dirt", "minecraft:sand",
        "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:gravel",
    };

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long digMs = args.length > 3 ? Long.parseLong(args[3]) : 160_000;

        final Path outDir = Path.of("results", "msptcmp-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-msptcmp-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=creative",
            "motd=papo-msptcmp", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) {
                System.out.println("MsptCmp.boot " + line.trim());
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
                final String name = String.format("MCB%02d", i);
                final double angle = 2.0 * Math.PI * i / bots;
                final double dx = BLOCKS_PER_TICK * Math.sin(angle);
                final double dz = BLOCKS_PER_TICK * Math.cos(angle);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 400));
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        botRefs[idx] = bot;
                        bot.joinClimbSpreadDigAndDisconnect(spreadMs, dx, dz, FILL_Y, fillDone, digMs, true);
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("MsptCmp.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " digMs=" + digMs);

            // 流送相基线采样 1 点（配对腿同相可比锚）
            sample(server, logLines, 1, "stream", walksStartWall + 120_000);

            sleepUntil(walksStartWall + spreadMs + 20_000);
            final OfflineJoinBot b0 = botRefs[0];
            if (b0 == null) {
                throw new IllegalStateException("bot 0 not joined at fill time");
            }
            final int b0x = (int) Math.floor(b0.getPosX());
            final int b0z = (int) Math.floor(b0.getPosZ());
            final int ctrlX = b0x + 7;
            final long markerWall = doFills(server, botRefs, logLines, outDir, ctrlX, b0z);

            // latch=marker+12（与 CompositeCensus 同刻）→ 挖掘+drain 同窗叠加
            sleepUntil(markerWall + 12_000);
            System.out.println("MsptCmp.latch t=" + (System.currentTimeMillis() - walksStartWall) + "ms");
            fillDone.countDown();

            // 复合窗 3 点 + 后段 2 点（相对 marker）
            sample(server, logLines, 2, "cmp", markerWall + 20_000);
            sample(server, logLines, 3, "cmp", markerWall + 40_000);
            sample(server, logLines, 4, "cmp", markerWall + 60_000);
            sample(server, logLines, 5, "post", markerWall + 100_000);
            sample(server, logLines, 6, "post", markerWall + 130_000);
            // 槽 6 后物品普查（R39 分开记账：同号槽组成通道裁决——站立物品数归一）。
            // GC.run×2+class_histogram 的 safepoint 停摆发生在全部采样窗之后，只影响
            // 后续状态探针（状态查询不受 GC 影响），不扰动任何已采窗口。
            itemCensus(server.pid(), logLines);

            // 探针（marker+90 已过：直接验首 3 挖 air+ctrl+chest）——负载真实性门
            final int[] tgt = b0.firstDigTargets;
            if (tgt != null) {
                for (int k = 0; k < 3; k++) {
                    probe(server, logLines, String.format("execute if block %d %d %d minecraft:air run say MCMP-DUG-AIR%d", tgt[k * 3], tgt[k * 3 + 1], tgt[k * 3 + 2], k));
                }
            } else {
                System.out.println("MsptCmp.probe MISSING firstDigTargets");
            }
            probe(server, logLines, String.format("execute if block %d %d %d minecraft:stone run say MCMP-CTRL-STONE", ctrlX, FILL_Y, b0z));
            probe(server, logLines, String.format("data get block %d %d %d Items", b0x, FILL_Y, b0z + FARM_DZ));
            probe(server, logLines, "execute if entity @e[type=minecraft:item,limit=1] run say MCMP-ITEMS-PRESENT");

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
            System.out.println("MsptCmp.digsTotal=" + digs);
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(120, TimeUnit.SECONDS);
            tail.join(5000);
        }

        Files.write(outDir.resolve("console.log"), logLines, StandardCharsets.UTF_8);
        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("MsptCmp.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }

    // ---------- mspt 采样（MsptLoadProbe 同制式） ----------

    private static void sample(final Process server, final List<String> logLines,
                               final int idx, final String phase, final long atWall) throws Exception {
        sleepUntil(atWall);
        final long linesBefore = logLines.size();
        server.getOutputStream().write("mspt\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(2500);
        final List<String> snap = new ArrayList<>(logLines);
        System.out.println("MsptCmp.sample[" + idx + "] phase=" + phase);
        for (final String l : snap.subList((int) Math.min(linesBefore, snap.size()), snap.size())) {
            if (!l.isBlank()) {
                System.out.println("MsptCmp.raw[" + idx + "] " + l.trim());
            }
            final String clean = l.replaceAll("\u001B\\[[0-9;]*m", "");
            final Matcher mm = NUMS.matcher(clean);
            if (mm.find()) {
                System.out.println("MsptCmp.mspt[" + idx + "] phase=" + phase
                    + " win5s=" + mm.group(1) + "/" + mm.group(2) + "/" + mm.group(3)
                    + " win10s=" + mm.group(4) + "/" + mm.group(5) + "/" + mm.group(6)
                    + " win1m=" + mm.group(7) + "/" + mm.group(8) + "/" + mm.group(9));
            }
        }
    }

    // ---------- 槽 6 后物品普查（站立物品数归一裁决） ----------

    private static void itemCensus(final long pid, final List<String> logLines) {
        try {
            run(pid, "GC.run");
            Thread.sleep(3000);
            run(pid, "GC.run");
            Thread.sleep(3000);
            final String hist = run(pid, "GC.class_histogram");
            for (final String l : hist.split("\n")) {
                if (l.contains("net.minecraft.world.entity.item.ItemEntity")) {
                    final java.util.regex.Matcher hm = java.util.regex.Pattern
                        .compile("^\\s*\\d+:\\s+(\\d+)\\s+").matcher(l);
                    if (hm.find()) {
                        System.out.println("MsptCmp.itemCensus ItemEntity=" + hm.group(1) + " (slot6 后 GC×2 活数)");
                    }
                }
            }
        } catch (final Exception e) {
            System.out.println("MsptCmp.itemCensus FAILED " + e);
        }
    }

    private static String run(final long pid, final String cmd) throws Exception {
        final Process p = new ProcessBuilder("F:/Java/21/bin/jcmd.exe", String.valueOf(pid), cmd)
            .redirectErrorStream(true).start();
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

    // ---------- fill（CompositeCensus 同几何） ----------

    private static long doFills(final Process server, final OfflineJoinBot[] botRefs,
                                final List<String> logLines, final Path outDir,
                                final int ctrlX, final int ctrlZ) throws Exception {
        final List<String> fills = new ArrayList<>();
        for (int i = 0; i < botRefs.length; i++) {
            final OfflineJoinBot bot = botRefs[i];
            if (bot == null) {
                throw new IllegalStateException("bot " + i + " not joined at fill time");
            }
            final int bx = (int) Math.floor(bot.getPosX());
            final int bz = (int) Math.floor(bot.getPosZ());
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                bx - 5, FILL_Y - 5, bz - 5, bx + 5, FILL_Y + 9, bz + 5));
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:hopper[facing=east]",
                bx - 6, FILL_Y, bz + FARM_DZ, bx - 1, FILL_Y, bz + FARM_DZ));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", bx, FILL_Y, bz + FARM_DZ));
            fills.add(String.format("/setblock %d %d %d minecraft:chest[facing=west]", bx, FILL_Y, bz + FARM_DZ + 1));
            fills.add(String.format("/fill %d %d %d %d %d %d minecraft:stone",
                bx - 12, FILL_Y, bz + FARM_DZ - 2, bx - 8, FILL_Y, bz + FARM_DZ + 2));
            for (int x = 0; x < HOPPERS; x++) {
                final int hx = bx - 6 + x;
                for (int n = 0; n < 150; n++) {
                    final String t = TYPES[n % TYPES.length];
                    fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                        hx, FILL_Y + 1, bz + FARM_DZ, t));
                }
            }
            for (int gx = 0; gx < 10; gx++) {
                for (int gz = 0; gz < 10; gz++) {
                    for (int k = 0; k < 3; k++) {
                        final String t = TYPES[(gx * 10 + gz * 3 + k) % TYPES.length];
                        fills.add(String.format("summon minecraft:item %d.5 %d.3 %d.5 {Item:{id:\"%s\",count:1}}",
                            bx - 12 + gx, FILL_Y + 1, bz + FARM_DZ - 2 + gz, t));
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
        System.out.println("MsptCmp.fillsSent commands=" + (fills.size() - 1));

        final long fillStart = System.currentTimeMillis();
        final long deadline = fillStart + FILL_MARKER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (logLines) {
                if (new ArrayList<>(logLines).stream().anyMatch(l -> l.contains(MARKER) && !l.contains("fillsSent")
                    && !l.contains("--[HERE"))) {
                    System.out.println("MsptCmp.fillMarkerReached burstMs=" + (System.currentTimeMillis() - fillStart));
                    return System.currentTimeMillis();
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("fill marker not seen within " + FILL_MARKER_TIMEOUT_MS + "ms");
    }

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
            if (l.contains("has the following block data") || l.contains("MCMP-CTRL-STONE")
                || l.contains("MCMP-DUG-AIR") || l.contains("MCMP-ITEMS-PRESENT")) {
                System.out.println("MsptCmp.probe " + l.trim());
            }
        }
    }

    private static void sleepUntil(final long wall) throws InterruptedException {
        final long wait = wall - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }
}
