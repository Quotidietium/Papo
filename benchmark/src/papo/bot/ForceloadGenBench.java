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
 * 批次113：forceload 扫掠世界生成稳定性 harness。
 *
 * 行走 bot 向量判例：自然地形移动校验踢线（BOT-FAIL SocketException），覆盖不可控。
 * 本 harness 以 forceload 票务扫掠驱动等价负载：每 STEP_MS 移动一个 3×3 区块的
 * forceload 窗沿螺旋/直线外扩，持续触发未生成区块的 worldgen（worker 线程）与
 * 完成回调回主线程的集成（mid-tick drain / tick 边界 poll）——真实服"玩家探索
 * 新地形"的确定性复现。2 个站立 bot 保活连接（无移动风险）。
 *
 * 门：logErrors=0 + 正常停机 + 扫掠步数全额执行（stdin 死亡即门失败）。
 * 用法：java papo.bot.ForceloadGenBench <jar> [windowMs=360000] [stepMs=5000]
 */
public final class ForceloadGenBench {

    private static final int PORT = 25598;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final long windowMs = args.length > 1 ? Long.parseLong(args[1]) : 360_000;
        final int stepMs = args.length > 2 ? Integer.parseInt(args[2]) : 5_000;
        final Path dir = Files.createTempDirectory("papo-flgen-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=20",
            "view-distance=6", "simulation-distance=8", "spawn-protection=0",
            "difficulty=peaceful", "spawn-monsters=false", "motd=papo-flgen",
            "sync-chunk-writes=false", "enforce-secure-profile=false", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx4G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        final long bootStart = System.nanoTime();
        String line;
        while (System.nanoTime() - bootStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(300) && (line = reader.readLine()) != null) {
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

        try {
            server.getOutputStream().write("gamerule doMobSpawning false\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException e) {
            System.out.println("SERVER_DIED before gamerule: " + e);
        }
        Thread.sleep(2000);

        // 2 站立 bot 保活（无移动校验风险）
        for (int i = 0; i < 2; i++) {
            final String name = "StandB0" + i;
            final Thread t = new Thread(() -> {
                try {
                    new OfflineJoinBot("127.0.0.1", PORT, name).joinWalkAndDisconnect(windowMs + 120_000, 0, 0);
                } catch (final Exception ignored) {
                }
            }, "bot-" + name);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(15_000); // 进场稳定

        // forceload 扫掠：3×3 区块窗沿 +X 直线外扩（每步 add 新窗 remove 旧窗）
        final long steps = windowMs / stepMs;
        int stepsDone = 0;
        boolean stdinDied = false;
        for (int s = 0; s < steps; s++) {
            // 世界坐标：起点 (0,0) 区块，每步前进 2 区块（80 块），窗 3×3=48 块宽
            final int cx = s * 2;
            try {
                final String cmd = "forceload add " + (cx * 16) + " 0 " + ((cx + 2) * 16 - 1) + " 48\n"
                    + (s > 0 ? "forceload remove " + ((cx - 2) * 16) + " 0 " + ((cx - 1) * 16 - 1) + " 48\n" : "");
                server.getOutputStream().write(cmd.getBytes(StandardCharsets.UTF_8));
                server.getOutputStream().flush();
                stepsDone++;
            } catch (final IOException e) {
                System.out.println("SERVER_DIED during sweep step " + s + ": " + e);
                stdinDied = true;
                break;
            }
            Thread.sleep(stepMs);
        }
        System.out.println("sweep steps done=" + stepsDone + "/" + steps + (stdinDied ? " (stdin died)" : ""));
        try {
            server.getOutputStream().write("forceload remove all\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        Thread.sleep(3000);

        try {
            server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
        } catch (final IOException ignored) {
        }
        if (!server.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        tail.join(5000);

        final List<String> fileLog = Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8);
        System.out.println("---- PapoTickProfile windows ----");
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
        final boolean gateOk = stepsDone == steps && !stdinDied;
        System.out.println(gateOk ? "sweep gate PASS" : "sweep gate FAILED");
        if (!gateOk || errors > 0 || server.exitValue() != 0) {
            System.exit(1);
        }
    }
}
