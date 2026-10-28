package papo.bot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批次200（R59 放置轴）协议外科验证器：单 bot 隔离验证 SET_CREATIVE_MODE_SLOT(0x37)
 * 武装手持 + USE_ITEM_ON(0x3f) 放置生效全链。腿 B 症状=零踢零错零石头——静态链
 * （handleUseItemOn→useItemOn→useOn）逐门全通，剩余活口只有手持武装未证。
 *
 * 取证双探针：①data get entity Inventory=手持真值 ②execute if block 目标格=放置真值。
 * 支撑格 (4,73,419) 位于出生点 (4.5,72,418.5) 头侧，WEST(4) 贴放目标 (3,73,419)。
 */
public final class PlaceVerify {
    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args.length > 0 ? args[0]
            : "F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.110.0.jar");
        final int port = 25599;
        final Path dir = Files.createTempDirectory("papo-placeverify-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + port, "level-seed=papo90",
            "max-players=20", "view-distance=4", "simulation-distance=4",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=creative",
            "motd=papo-pv", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
            StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder("F:/Java/21/bin/java.exe",
            "-Xms2G", "-Xmx2G", "-Dfile.encoding=UTF-8", "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
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
        console.write("setblock 4 73 419 minecraft:stone\n".getBytes(StandardCharsets.UTF_8));
        console.flush();
        System.out.println("PVY-SUPPORT-SET");

        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", port, "PVY00");
        final Thread bt = new Thread(() -> {
            try {
                bot.joinWalkDwellAndDisconnect(0, 0, 0, 30_000);
            } catch (final Exception e) {
                System.out.println("PVY bot failed: " + e);
            }
        }, "bot");
        bt.setDaemon(true);
        bt.start();

        Thread.sleep(9_000); // join + position sync
        final int sx = (int) Math.floor(bot.getPosX()), sz = (int) Math.floor(bot.getPosZ());
        final int sy = (int) Math.floor(bot.getPosY()) + 1; // 头侧格（AABB 上段）
        final int supX = sx + 2; // 东侧两格=支撑；WEST 贴放目标 (sx+1)=bot 与支撑间空格
        System.out.println("PVY bot pos x=" + bot.getPosX() + " y=" + bot.getPosY() + " z=" + bot.getPosZ()
            + " support=(" + supX + "," + sy + "," + sz + ") target=(" + (supX - 1) + "," + sy + "," + sz + ")");

        console.write(("setblock " + supX + " " + sy + " " + sz + " minecraft:stone\n").getBytes(StandardCharsets.UTF_8));
        console.flush();
        Thread.sleep(1_500); // bot 在线持有票据=区块已加载，setblock 生效（R48 探针判例）

        bot.setHeldStone();
        Thread.sleep(1_500);
        bot.placeOnFace(supX, sy, sz, 4); // WEST → target (supX-1, sy, sz)
        Thread.sleep(1_500);

        console.write(("execute if block " + (supX - 1) + " " + sy + " " + sz
            + " minecraft:stone run say PVY-PLACED\n").getBytes(StandardCharsets.UTF_8));
        console.write("data get entity PVY00 Inventory\n".getBytes(StandardCharsets.UTF_8));
        console.write(("execute if block " + supX + " " + sy + " " + sz
            + " minecraft:stone run say PVY-SUPPORT-PRESENT\n").getBytes(StandardCharsets.UTF_8));
        console.flush();
        Thread.sleep(4_000);

        int placed = 0, support = 0, inv = 0;
        synchronized (logLines) {
            for (final String l : logLines) {
                if (l.contains("PVY-PLACED")) { placed++; System.out.println("PVY-PLACED-HIT: " + l.trim()); }
                if (l.contains("PVY-SUPPORT-PRESENT")) { support++; System.out.println("PVY-SUPPORT-HIT: " + l.trim()); }
                if (l.contains("PVY00 has the following entity data")) { inv++; System.out.println("PVY-INV: " + l.trim()); }
                if (l.contains("PVY00 lost connection") || l.contains("DecoderException")) { System.out.println("PVY-DISCONNECT: " + l.trim()); }
            }
        }
        System.out.println("PVY verdict: botAt=" + sx + "," + sz + " placed=" + placed + " support=" + support
            + " invEcho=" + inv + " placesSent=" + bot.getPlacesSent());
        console.write("stop\n".getBytes(StandardCharsets.UTF_8));
        console.flush();
        server.waitFor();
        System.out.println("PVY server exit=" + server.exitValue());
    }
}
