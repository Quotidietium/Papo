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
 * 批次112 世界直查驱动：boot 一个已存在的 harness 遗留世界目录，把 stdin 探针脚本
 * （每行一条命令）发给服务器，回显 dump 后停机——用于损伤 cell 占位方块取证
 * （latest.log 不含 say 回显，必须活捕获——批次112 判例）。
 *
 * 用法：java papo.bot.WorldInspect <worldDir> <probeScript.txt>
 * 探针惯例：命令以 say MARKER 回显，本驱动 dump 所有含 MARKER 的行。
 */
public final class WorldInspect {

    public static void main(final String[] args) throws Exception {
        final Path dir = Path.of(args[0]);
        final List<String> probes = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(240) && (line = reader.readLine()) != null) {
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
        Thread.sleep(3000);
        try {
            for (final String p : probes) {
                server.getOutputStream().write((p + "\n").getBytes(StandardCharsets.UTF_8));
            }
            server.getOutputStream().flush();
        } catch (final IOException e) {
            System.out.println("SERVER_DIED during probes: " + e);
        }
        Thread.sleep(25_000); // 命令执行（含 forceload 后的区块加载等待）+ 回显排空
        try {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);
        System.out.println("---- probe echoes ----");
        synchronized (logLines) {
            logLines.stream().filter(l -> l.contains("[Server]") || l.contains("Too many") || l.contains("Incorrect"))
                .forEach(l -> System.out.println(l.trim()));
        }
    }
}
