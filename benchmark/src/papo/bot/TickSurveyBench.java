package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 批次90：稳态 tick 主线程相位 survey。
 *
 * 服务器以 -Dpapo.tickProfile=1 启动（PapoTickProfile 每 400 ticks 打印各相位
 * total/avg/share）；10 个行走 bot（每 50ms 发位置包，5 blocks/s 向北）产生真实
 * 稳态负载（移动处理 + 区块发送 + 连接 tick）持续 45s；随后解析日志中的
 * PapoTickProfile 窗口输出相位占比。门：exit 0 + 零门错误。
 *
 * 用法：java papo.bot.TickSurveyBench <jar> [bots=10] [walkMs=45000]
 */
public final class TickSurveyBench {

    private static final int PORT = 25594;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final int bots = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        final long walkMs = args.length > 2 ? Long.parseLong(args[2]) : 45_000;
        final Path dir = Files.createTempDirectory("papo-ticksurvey-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "view-distance=6", "simulation-distance=6", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-ticksurvey",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            "-jar", "server.jar", "nogui")
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
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        tail.setDaemon(true);
        tail.start();
        Thread.sleep(2000);

        try {
            final CountDownLatch go = new CountDownLatch(1);
            final List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < bots; i++) {
                final String name = String.format("WalkB%02d", i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        Thread.sleep((long) (Math.random() * 500)); // 错峰
                        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                        bot.joinWalkAndDisconnect(walkMs, 0, 0.25); // 5 blocks/s 北
                    } catch (final Exception e) {
                        throw new RuntimeException("bot " + name + " failed: " + e, e);
                    }
                }));
            }
            go.countDown();
            for (final CompletableFuture<Void> f : futures) {
                f.get(walkMs + 120, TimeUnit.SECONDS);
            }
            System.out.println("walk window done (" + bots + " bots x " + walkMs + "ms)");
            Thread.sleep(3000); // 让最后一个 profile 窗口打印
        } finally {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            server.waitFor(120, TimeUnit.SECONDS);
            tail.join(5000);
        }

        // 解析改走服务器自身日志文件（管道捕获在部分环境下丢 post-boot 行，批次85 ◴ 同源）
        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- PapoTickProfile windows (from logs/latest.log) ----");
        for (final String l : fileLog) {
            if (l.contains("PapoTickProfile")) {
                System.out.println(l);
            }
        }
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(fileLog);
        final long errors = all.stream()
            .filter(l -> (l.contains("ERROR") || l.contains("Exception")) && !BurstJoinVerify.isBenignCloseRace(l))
            .count();
        System.out.println("logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (errors > 0 || server.exitValue() != 0) {
            all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception"))
                .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).distinct().limit(8)
                .forEach(l -> System.out.println("  " + l.trim()));
            System.exit(1);
        }
    }
}
