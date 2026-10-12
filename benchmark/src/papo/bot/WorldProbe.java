package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批次200（R59）：既有世界目录的地面真值探针。腿 B（place=1）面格假设检验——
 * BlockPlaceContext.replaceClicked 语义：点击格=空气（被挖掘半段挖空的面格）时
 * 石头放进支撑格自身，而非探针③检查的外贴目标格。forceload 加载目标区块防
 * R48「未加载 execute if block 静默假」陷阱。
 */
public final class WorldProbe {
    public static void main(final String[] args) throws Exception {
        final String dir = args[0];
        final Process server = new ProcessBuilder("F:/Java/21/bin/java.exe",
            "-Xms2G", "-Xmx2G", "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(new java.io.File(dir)).redirectErrorStream(true).start();
        final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
        final BufferedReader reader = new BufferedReader(
            new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < 240_000_000_000L && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) { System.out.println("boot: " + line.trim()); break; }
        }
        final Thread tail = new Thread(() -> {
            try { String l; while ((l = reader.readLine()) != null) logLines.add(l); } catch (final Exception ignored) { }
        }, "log-tail");
        tail.setDaemon(true);
        tail.start();

        final OutputStream console = server.getOutputStream();
        Thread.sleep(2_000);
        // CSB00 立方 bx=9,bz=840（botFinalPos x=9 z=840）：北面支撑格 z=835、外贴目标 z=834。
        // forceload 波①（R57 两波制）：触发生成/加载，等待后波②探针。
        console.write("forceload add 0 835 32 867\n".getBytes(StandardCharsets.UTF_8));
        console.flush();
        Thread.sleep(12_000);
        final String[] probes = {
            "execute if block 9 201 835 minecraft:stone run say WP-FACE-N0",
            "execute if block 9 202 835 minecraft:stone run say WP-FACE-N1",
            "execute if block 10 201 835 minecraft:stone run say WP-FACE-N2",
            "execute if block 9 201 834 minecraft:stone run say WP-OUT-N0",
            "execute if block 8 201 840 minecraft:stone run say WP-FACE-W0",
            "execute if block 9 201 845 minecraft:stone run say WP-FACE-S0",
            "execute if block 14 201 840 minecraft:stone run say WP-FACE-E0",
        };
        for (final String p : probes) {
            console.write((p + "\n").getBytes(StandardCharsets.UTF_8));
        }
        console.flush();
        Thread.sleep(5_000);
        synchronized (logLines) {
            for (final String l : logLines) {
                if (l.contains("WP-FACE") || l.contains("WP-OUT") || l.contains("forceload")
                    || l.contains("Unloaded") || l.contains("That position")) {
                    System.out.println(l.trim());
                }
            }
        }
        console.write("forceload remove all\nstop\n".getBytes(StandardCharsets.UTF_8));
        console.flush();
        server.waitFor();
        System.out.println("probe server exit=" + server.exitValue());
    }
}
