package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 批次90 调试探针：boot → 行走 bot 30s → 停 → dump logs/latest.log（PapoTickProfile + 尾部）。 */
public final class ProfileProbe {
    public static void main(final String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("papo-profileprobe-");
        Files.copy(Path.of(args[0]), dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"),
            "online-mode=false\nserver-port=25593\nview-distance=6\nsimulation-distance=6\npause-when-empty-seconds=0\n",
            StandardCharsets.UTF_8);
        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx2G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final Thread tail = new Thread(() -> {
            try {
                while (reader.readLine() != null) { /* 排空 */ }
            } catch (final IOException ignored) {
            }
        }, "probe-tail");
        tail.setDaemon(true);
        tail.start();
        // 等 boot
        final long bootDeadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < bootDeadline) {
            Thread.sleep(1000);
            if (Files.exists(dir.resolve("logs/latest.log"))) {
                final List<String> l = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
                if (l.stream().anyMatch(x -> x.contains("Done ("))) {
                    break;
                }
            }
        }
        Thread.sleep(1000);
        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", 25593, "ProbeWalk01");
        final long[] t = bot.joinWalkAndDisconnect(30_000, 0, 0.25);
        System.out.println("bot spawn=" + t[2] + "ms, walked 30s");
        Thread.sleep(15_000);
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        server.waitFor();
        final List<String> log = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("logLines=" + log.size());
        log.stream().filter(l -> l.contains("PapoTickProfile") || l.contains("STDOUT")).limit(25).forEach(l -> System.out.println("  [P] " + l));
        System.out.println("---- last 12 log lines ----");
        log.stream().skip(Math.max(0, log.size() - 12)).forEach(l -> System.out.println("  " + l));
    }
}
