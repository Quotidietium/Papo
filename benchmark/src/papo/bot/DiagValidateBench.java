package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 批次117：PapoDiag 插件验证——强制停摆（大批 fill）→ 断言报告文件生成且含主线程栈。
 * 用法：java papo.bot.DiagValidateBench <jar> <papoDiagJar>
 */
public final class DiagValidateBench {

    private static final int PORT = 25604;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path pluginJar = Path.of(args[1]);
        final Path dir = Files.createTempDirectory("papo-diagv-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.createDirectories(dir.resolve("plugins"));
        Files.copy(pluginJar, dir.resolve("plugins/PapoDiag.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20", "view-distance=6", "simulation-distance=8",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-diagv",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        boolean pluginOn = false;
        while (System.nanoTime() - bootStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("PapoDiag active")) {
                pluginOn = true;
            }
            if (line.contains("Done (")) {
                System.out.println("boot ok, pluginOn=" + pluginOn);
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

        // 强制停摆：数条大 fill（区块生成+方块更新=已知 100ms+ 主线程尖峰源）
        Thread.sleep(5000);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append("fill ").append(i * 200).append(" 100 0 ").append(i * 200 + 150).append(" 110 150 minecraft:stone\n");
        }
        server.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(20_000); // 等停摆被看门狗捕获+去抖窗

        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        if (!server.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
        tail.join(3000);

        final Path report = dir.resolve("plugins/PapoDiag/stall-report.txt");
        System.out.println("report exists=" + Files.exists(report));
        if (Files.exists(report)) {
            final List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
            System.out.println("report lines=" + lines.size());
            lines.stream().limit(20).forEach(l -> System.out.println("  " + l));
            final boolean hasStack = lines.stream().anyMatch(l -> l.contains("at net.minecraft") || l.contains("at java.") || l.contains("at org.bukkit"));
            final boolean hasPlugins = lines.stream().anyMatch(l -> l.contains("plugins=PapoDiag"));
            System.out.println("VALIDATION " + (hasStack && hasPlugins ? "PASS (stack+plugins captured)"
                : "FAILED hasStack=" + hasStack + " hasPlugins=" + hasPlugins));
            if (!(hasStack && hasPlugins)) {
                System.exit(1);
            }
        } else {
            System.out.println("VALIDATION FAILED (no report file)");
            System.exit(1);
        }
    }
}
