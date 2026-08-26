package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 批次85 调试探针：boot → 发 mspt → 尾线程收集 8s 全部控制台输出 → 落盘。 */
public final class MsptProbe {
    public static void main(final String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("papo-msptprobe-");
        Files.copy(Path.of(args[0]), dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"),
            "online-mode=false\nserver-port=25596\nview-distance=6\nsimulation-distance=6\n", StandardCharsets.UTF_8);
        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx2G", "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final Thread tail = new Thread(() -> {
            try {
                String l;
                while ((l = reader.readLine()) != null) {
                    lines.add(l);
                }
            } catch (final Exception ignored) {
            }
        }, "probe-tail");
        tail.setDaemon(true);
        tail.start();
        final long bootDeadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < bootDeadline) {
            Thread.sleep(500);
            boolean done = false;
            synchronized (lines) {
                for (final String l : lines) {
                    if (l.contains("Done (")) {
                        done = true;
                        break;
                    }
                }
            }
            if (done) {
                break;
            }
        }
        Thread.sleep(3000);
        server.getOutputStream().write("mspt\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(8000);
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        server.waitFor();
        final Path out = Path.of(System.getProperty("java.io.tmpdir"), "papo-msptprobe.log");
        Files.write(out, lines);
        System.out.println("saved " + out + " (" + lines.size() + " lines)");
        final String circle = String.valueOf((char) 0x25F4);
        synchronized (lines) {
            for (final String l : lines) {
                if (l.contains("tick") || l.contains(circle) || l.contains("rror") || l.contains("xception")) {
                    System.out.println("  " + l);
                }
            }
        }
    }
}
