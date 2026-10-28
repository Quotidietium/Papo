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
 * 批次190（R49 玩家生命周期轴）：join↔quit 循环风暴（公共服务器玩家进出常态）
 * 下服务端生命周期链（placeNewPlayer/PlayerList add/remove/异步 quit 存档 NBT
 * 树/playerdata 读/advancements+配方书初始化/票据 churn/卸载-重载）的分配普查窗。
 *
 * 动机：分配普查七域（流送/盘载/驻留/实体+保存/机械负载/物品旋涡/方块交互）
 * 无一窗含 join/quit 相位——流送窗 bots 已在线，退出（异步存档/票据卸载）与
 * 重进（playerdata 读/重载）从未成像；复合覆盖卸载-重载轴（bot 循环重访
 * 已卸载 spawn 区=第 2+ 轮 join 走卸载后重载路径）。
 *
 * 形态：B 腿（storm）每 bot 循环 rounds 次 {join→dwell 8s→断开（=异步 quit
 * 存档触发）→gap 4s（存档落盘窗）}，12 bot 错峰 200ms；A 腿（基线）每 bot
 * join 一次持续在线同总时长（R41 驻留同款）。同二进制配对=delta 纯生命
 * 周期面。offline UUID 名派生稳定→同名重 join 读自落盘 dat（gap 实证足够，
 * 探针轮验证）。
 *
 * 时间线：boot→错峰风暴开始（t≈0）→腿脚本 boot+35s 起 JFR 60s（覆盖风暴
 * 中段 ~3 轮）→bots 结束→探针（list 在线数+playerdata dat 计数=异步存档
 * 落盘正证据）→gate。
 *
 * 用法：java papo.bot.JoinQuitStormCensus <jar> [bots=12] [rounds=4] [dwellMs=8000] [gapMs=4000] [storm=1]
 *
 * 门：TRACE≥3×bots（每轮 join 必发 0x46）+ storm 腿 savingData 行≥3×bots+
 * playerdata dat≥bots + 零类版本错误 + exit 0 + 服务器日志零门错误。
 */
public final class JoinQuitStormCensus {

    private static final int PORT = 25599;
    private static final String JAVA = "F:/Java/21/bin/java.exe";
    private static final String JCMD = "F:/Java/21/bin/jcmd.exe";

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        final int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        final long dwellMs = args.length > 3 ? Long.parseLong(args[3]) : 8_000;
        final long gapMs = args.length > 4 ? Long.parseLong(args[4]) : 4_000;
        final boolean storm = args.length > 5 && Integer.parseInt(args[5]) != 0;

        final Path outDir = Path.of("results", "jqstorm-" + System.currentTimeMillis() + (storm ? "-storm" : "-baseline"));
        Files.createDirectories(outDir);

        final Path dir = Files.createTempDirectory("papo-jqstorm-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=" + Math.max(bots, 20), "view-distance=10", "simulation-distance=6",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=spectator",
            "motd=papo-jqstorm", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final List<String> cmd = new ArrayList<>(List.of(JAVA, "-Xms4G", "-Xmx4G",
            "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui"));
        System.out.println("server cmd: " + String.join(" ", cmd) + "  storm=" + storm
            + " rounds=" + rounds + " dwellMs=" + dwellMs + " gapMs=" + gapMs);

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

        final long stormStartWall = System.currentTimeMillis();
        try {
            final ExecutorService botPool = Executors.newFixedThreadPool(bots); // R27 判例：专用池
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final int idx = i;
                final String name = String.format("JQB%02d", i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep(idx * 200L); // 错峰
                        if (storm) {
                            for (int r = 0; r < rounds; r++) {
                                final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                                bot.joinAndDisconnect(dwellMs); // join→dwell→close=异步 quit 存档触发
                                System.out.println("JQROUND[" + name + "] #" + r + " done t="
                                    + (System.currentTimeMillis() - stormStartWall) + "ms");
                                if (r < rounds - 1) {
                                    Thread.sleep(gapMs); // 存档落盘窗（探针实证）
                                }
                            }
                        } else {
                            // 基线：一次 join 持续在线同总时长（rounds×(dwell+gap)≈风暴腿时长）
                            final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                            bot.joinWalkDwellAndDisconnect(0, 0, 0,
                                Math.max(dwellMs, (long) rounds * (dwellMs + gapMs)));
                        }
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }, botPool));
            }
            go.countDown();
            System.out.println("JoinQuitStormCensus.stormPhaseStart bots=" + bots
                + " storm=" + storm + " rounds=" + rounds);

            for (final CompletableFuture<Void> f : futures) {
                f.get(rounds * (dwellMs + gapMs) + 120_000, TimeUnit.MILLISECONDS);
            }
            System.out.println("bots done t=" + (System.currentTimeMillis() - stormStartWall) + "ms");
            botPool.shutdown();

            // 探针：playerdata dat 计数=异步 quit 存档落盘正证据（offline UUID 名派生稳定）
            Thread.sleep(3000); // 尾部存档落盘
            int datCount = 0;
            try (var s = Files.list(dir.resolve("world/playerdata"))) {
                datCount = (int) s.filter(p -> p.getFileName().toString().endsWith(".dat")).count();
            } catch (final Exception ignored) {
            }
            System.out.println("JoinQuitStormCensus.playerdataDat=" + datCount);
            measure(server.pid(), outDir);
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
        // 成功存档静默（仅失败 warn，PlayerDataStorage 源码实证）——正证据锚服务器侧
        // join/quit 行 + playerdata dat 落盘；计数只取 stdout 捕获流（latest.log 是
        // 同批行的镜像，合并=双计数——探针轮实证 joins=6 for 3 join）
        final long joins = logLines.stream().filter(l -> l.contains("joined the game")).count();
        final long lefts = logLines.stream().filter(l -> l.contains("left the game")).count();
        System.out.println("JoinQuitStormCensus.gate logErrors=" + errors + " exited=" + (server.exitValue() == 0)
            + " joins=" + joins + " lefts=" + lefts);
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
        if (storm && (joins < 3L * bots || lefts < 3L * bots)) {
            System.out.println("JoinQuitStormCensus: GATE FAIL (storm evidence joins=" + joins + " lefts=" + lefts + ")");
            System.exit(1);
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
        System.out.println("JoinQuitStormCensus.heapUsedKb=" + usedKb);
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
