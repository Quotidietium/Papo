package papo.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * 批次91：停机窗口竞态——真实专用服实弹验证（改动面 = PapoOrderedFileWrites 提交侧 ISE 内联回退）。
 *
 * 场景：登录突发进行中下发 stop。auth/config 阶段的读预取提交与 stopServer 尾部
 * MoonriseCommon.haltExecutors() 的 IO 池 shutdown(false) 排空并发——正是 HaltSemanticsProbe
 * 实证 isActive()=true 但 queueTask 抛 ISE 的窗口（修复前：写静默丢弃 / 读 result future 悬挂）。
 *
 * 序列：
 *   1) fresh 目录 boot（offline、seed papo91、view/sim 6）；
 *   2) 预热 join×2（产出合法 playerdata，稳态文件就绪）；
 *   3) 12 bot 错峰连接（0..3000ms）各自正常 join + 停留 5s；启动后 ~1200ms 主线程下发 stop
 *      ——关服时刻必然有若干 bot 处于 login/config（读预取在飞）与 play（quit 存档在飞）混合态；
 *   4) 关服核验：exit 0；日志 ERROR/Exception（扣除良性 bot 突断竞争）= 0；
 *      **playerdata 下每一个存在的 .dat 都必须通过 gzip+NBT 校验**（残留写入不得有截断/损坏）；
 *   5) 同目录重启：boot Done + 一次 join + stop + exit 0 —— 关服竞态不得破坏世界可读性。
 *
 * bot 侧突断（EOF/reset）是关服的预期行为，单独计数披露。
 * 用法：java papo.bot.ShutdownRaceVerify <jar> [rounds=1]
 */
public final class ShutdownRaceVerify {

    private static final int PORT = 25598;
    private static final int RACE_BOTS = 12;
    private static final long STOP_DELAY_MS = 1200;
    private static final String WARM_BOT = "PapoWarm01";

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ShutdownRaceVerify <jar> [rounds]");
            System.exit(2);
        }
        final Path jar = Path.of(args[0]);
        final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        for (int r = 1; r <= rounds; r++) {
            runRound(jar, r);
        }
        System.out.println();
        System.out.println("SHUTDOWN-RACE ALL ROUNDS PASS");
    }

    private static void runRound(final Path jar, final int round) throws Exception {
        final Path dir = Files.createTempDirectory("papo-race91-");
        try {
            raceOnce(jar, dir, round);
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final IOException ignored) {}
            });
        }
    }

    private static void raceOnce(final Path jar, final Path dir, final int round) throws Exception {
        Files.copy(jar, dir.resolve(jar.getFileName()));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false",
            "server-port=" + PORT,
            "level-seed=papo91",
            "view-distance=6",
            "simulation-distance=6",
            "spawn-protection=0",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "motd=papo-race91",
            "sync-chunk-writes=false",
            "enforce-secure-profile=false",
            ""), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("=== round " + round + " boot ===");
        final Process server = startServer(dir, jar);
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        waitDone(server, reader, logLines);

        final Thread logTail = tailLogs(reader, logLines);

        // ---- 预热：2 次 join 产出稳态 playerdata ----
        for (int i = 1; i <= 2; i++) {
            final OfflineJoinBot warm = new OfflineJoinBot("127.0.0.1", PORT, WARM_BOT);
            final long[] t = warm.joinAndDisconnect(500);
            System.out.printf("  warm#%d spawn=%dms%n", i, t[2]);
            Thread.sleep(300);
        }

        // ---- 竞态：12 bot 错峰 + 1200ms 后 stop ----
        final AtomicInteger spawned = new AtomicInteger();
        final AtomicInteger raced = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(RACE_BOTS);
        for (int i = 0; i < RACE_BOTS; i++) {
            final String name = String.format("PapoRacer%02d", i);
            final long delayMs = i * 250L; // 0..2750ms 错峰
            final Thread t = new Thread(() -> {
                try {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", PORT, name);
                    bot.joinAndDisconnect(5000); // 停留 5s：多半会被关服截断
                    spawned.incrementAndGet();
                } catch (final Exception e) {
                    // 关服突断（EOF/reset/短读）是预期路径
                    if (isAbruptClose(e)) {
                        raced.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                        System.out.println("  [unexpected] " + name + ": " + e);
                    }
                } finally {
                    done.countDown();
                }
            }, "racer-" + name);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(STOP_DELAY_MS);
        System.out.println("  >>> stop issued mid-burst (t=+" + STOP_DELAY_MS + "ms)");
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();

        final boolean exited = server.waitFor(180, TimeUnit.SECONDS);
        final int exitCode = exited ? server.exitValue() : -1;
        done.await(30, TimeUnit.SECONDS); // bot 各自收尾（突断即刻返回）
        logTail.join(5000);

        // ---- 关服核验 ----
        long errors = logLines.stream().filter(ShutdownRaceVerify::isError).count();
        final long benign = logLines.stream().filter(BurstJoinVerify::isBenignCloseRace).count();
        final long gateErrors = errors - benign;

        String datVerdict = "ok";
        int datCount = 0;
        final Path playerdata = dir.resolve("world/playerdata");
        if (Files.isDirectory(playerdata)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(playerdata, "*.dat")) {
                for (final Path dat : ds) {
                    datCount++;
                    final String c = checkGzipNbt(dat);
                    if (!"ok".equals(c)) {
                        datVerdict = c + "(" + dat.getFileName() + ")";
                        break;
                    }
                }
            }
        }

        System.out.printf("  race: spawned=%d/%d abruptClosed=%d unexpectedFailures=%d%n",
            spawned.get(), RACE_BOTS, raced.get(), failed.get());
        System.out.printf("  shutdown: exited=%b exitCode=%d logErrors=%d (benign-close=%d)%n",
            exited, exitCode, gateErrors, benign);
        System.out.printf("  artifacts: %d .dat files, all-valid=%s%n", datCount, datVerdict);

        boolean raceOk = exited && exitCode == 0 && gateErrors == 0 && failed.get() == 0
            && "ok".equals(datVerdict) && datCount >= 1 && spawned.get() + raced.get() == RACE_BOTS;
        if (!raceOk) {
            logLines.stream().filter(ShutdownRaceVerify::isError).filter(l -> !BurstJoinVerify.isBenignCloseRace(l))
                .distinct().limit(8).forEach(l -> System.out.println("      " + l.trim()));
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "papo-race91-r" + round + "-gatefail.log"), logLines);
            throw new IllegalStateException("RACE FAILED r" + round + " (exited=" + exited + " exit=" + exitCode
                + " gateErrors=" + gateErrors + " unexpected=" + failed.get() + " dats=" + datVerdict + ")");
        }

        // ---- 重启核验：同世界目录 ----
        System.out.println("  reboot same world...");
        final Process reboot = startServer(dir, jar);
        final List<String> rebootLines = new ArrayList<>();
        final BufferedReader r2 = new BufferedReader(new InputStreamReader(reboot.getInputStream(), StandardCharsets.UTF_8));
        waitDone(reboot, r2, rebootLines);
        final Thread tail2 = tailLogs(r2, rebootLines);
        final long[] t = new OfflineJoinBot("127.0.0.1", PORT, WARM_BOT).joinAndDisconnect(500);
        System.out.printf("  reboot join spawn=%dms%n", t[2]);
        reboot.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        reboot.getOutputStream().flush();
        final boolean rexited = reboot.waitFor(120, TimeUnit.SECONDS);
        final int rexit = rexited ? reboot.exitValue() : -1;
        tail2.join(5000);
        final long rerrors = rebootLines.stream().filter(ShutdownRaceVerify::isError)
            .filter(l -> !BurstJoinVerify.isBenignCloseRace(l)).count();
        final String reDat = checkGzipNbt(dir.resolve("world/playerdata/" + warmUuid() + ".dat"));
        System.out.printf("  reboot: exited=%b exitCode=%d logErrors=%d warmDat=%s%n", rexited, rexit, rerrors, reDat);
        if (!rexited || rexit != 0 || rerrors > 0 || !"ok".equals(reDat)) {
            Files.write(Path.of(System.getProperty("java.io.tmpdir"), "papo-race91-r" + round + "-rebootfail.log"), rebootLines);
            throw new IllegalStateException("REBOOT CHECK FAILED r" + round);
        }
    }

    private static Process startServer(final Path dir, final Path jar) throws IOException {
        return new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx3G", "-Dfile.encoding=UTF-8", "-jar", jar.getFileName().toString(), "nogui")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
    }

    private static void waitDone(final Process server, final BufferedReader reader, final List<String> logLines) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            logLines.add(line);
            if (line.contains("Done (")) {
                System.out.println("  boot: " + line.trim());
                return;
            }
            if (line.contains("ERROR") || line.contains("Exception")) {
                System.out.println("  [boot] " + line.trim());
            }
        }
        server.destroyForcibly();
        throw new IllegalStateException("server did not reach Done");
    }

    private static Thread tailLogs(final BufferedReader reader, final List<String> logLines) {
        final Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    logLines.add(line);
                }
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static UUID warmUuid() {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + WARM_BOT).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isError(final String line) {
        return line.contains("ERROR") || line.contains("Exception");
    }

    /** bot 侧预期突断：EOF（含 config/play 阶段短帧）与连接重置。 */
    private static boolean isAbruptClose(final Exception e) {
        final String m = String.valueOf(e.getMessage());
        return e instanceof java.io.EOFException
            || m.contains("reset")
            || m.contains("关闭")
            || m.contains("Connection");
    }

    private static String checkGzipNbt(final Path dat) {
        try {
            if (!Files.exists(dat)) {
                return "MISSING";
            }
            try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(dat), 8192)) {
                final byte[] head = gz.readNBytes(2);
                if (head.length == 2 && head[0] == 0x0A) {
                    return "ok";
                }
                return "BAD-NBT(magic=" + Integer.toHexString(head[0] & 0xFF) + ")";
            }
        } catch (final Exception e) {
            return "BAD(" + e.getClass().getSimpleName() + ")";
        }
    }
}
