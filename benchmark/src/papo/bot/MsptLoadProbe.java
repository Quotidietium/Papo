package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批次155（R15 tick 性能守护轮）：浸泡负载态 MSPT/TPS 采样。
 *
 * 背景：内存主线（批141-150）改动了多条热路径（容器读共享/光照 uniform 表示/
 * 节访问分支/区块包缓存 TTL 重编码），但全程未量化 tick 性能是否被换走——
 * "交互体验一致"红线包含延迟。本探针在 MemSoakVerify 同场景（同
 * server.properties，保证与内存数字可比）下，于流送期（bot 行走、区块/光照/
 * 包编码承压）与驻留期（在线持有区块的静息态）多点采样 Paper 控制台 mspt
 * 输出，用于优化前后配对比较。
 *
 * 用法：java papo.bot.MsptLoadProbe <jar> [bots=12] [spreadMs=150000] [dwellMs=180000]
 *
 * 输出：stdout 结构化 MsptLoad.* 行（样本原样 + 解析数字），原始控制台落盘
 * benchmark/results/msptload-<stamp>/console.log。
 * 门：bot 全成功 + 日志零门错误 + 服务器 exit 0（同 MemSoakVerify）。
 */
public final class MsptLoadProbe {

    private static final int PORT = 25596;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final double BLOCKS_PER_TICK = 0.25;
    private static final Pattern NUMS = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?),"
            + "\\s*(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?),"
            + "\\s*(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)");

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final long spreadMs = args.length > 2 ? Long.parseLong(args[2]) : 150_000;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 180_000;

        final Path outDir = Path.of("results", "msptload-" + System.currentTimeMillis());
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-msptload-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        // 与 MemSoakVerify 逐字相同（场景可比性是本轮结论的前提）
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-msptload", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) {
                System.out.println("MsptLoad.boot " + line.trim());
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
                final String name = String.format("MlpB%02d", i);
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
                }));
            }
            final long walksStartWall = System.currentTimeMillis();
            go.countDown();
            Thread.sleep(bots * 60);
            System.out.println("MsptLoad.walkPhaseStart bots=" + bots + " spreadMs=" + spreadMs + " dwellMs=" + dwellMs);

            // 采样时刻（相对行走起点）：流送期 4 点 + 驻留期 3 点（含静息后段）
            final long[] sampleAt = {
                30_000, 75_000, 120_000,          // 流送期（bot 行进、区块/光照/包编码承压）
                spreadMs + 15_000,                 // 驻留初期
                spreadMs + dwellMs / 2,            // 驻留中期
                spreadMs + dwellMs - 15_000,       // 驻留末期
            };
            int sampleIdx = 0;
            for (final long at : sampleAt) {
                final long wait = walksStartWall + at - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                final int idx = ++sampleIdx;
                final long linesBefore = logLines.size();
                server.getOutputStream().write("mspt\n".getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                Thread.sleep(2500); // 等 mspt 多行输出完成
                final List<String> block = new ArrayList<>(logLines.subList(
                    (int) Math.min(linesBefore, logLines.size()), logLines.size()));
                final String phase = at < spreadMs ? "stream" : "dwell";
                System.out.println("MsptLoad.sample[" + idx + "] phase=" + phase + " atMs=" + at);
                for (final String l : block) {
                    if (!l.isBlank()) {
                        System.out.println("MsptLoad.raw[" + idx + "] " + l.trim());
                    }
                    // "a/b/c, d/e/f, g/h/i"（ANSI 剥离后）→ 5s/10s/1m 三窗 avg/min/max
                    final String clean = l.replaceAll("\u001B\\[[0-9;]*m", "");
                    final Matcher mm = NUMS.matcher(clean);
                    if (mm.find()) {
                        System.out.println("MsptLoad.mspt[" + idx + "] phase=" + phase
                            + " win5s=" + mm.group(1) + "/" + mm.group(2) + "/" + mm.group(3)
                            + " win10s=" + mm.group(4) + "/" + mm.group(5) + "/" + mm.group(6)
                            + " win1m=" + mm.group(7) + "/" + mm.group(8) + "/" + mm.group(9));
                    }
                }
            }

            for (final CompletableFuture<Void> f : futures) {
                f.get(spreadMs + dwellMs + 120, TimeUnit.SECONDS);
            }
            System.out.println("MsptLoad.botsDone");
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
        System.out.println("MsptLoad.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }
}
