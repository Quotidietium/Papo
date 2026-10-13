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
 * 批次205（R64 农业生长轴）协议外科验证器：单 bot 隔离验证骨粉催熟全链——
 * SET_CREATIVE_MODE_SLOT(0x37) bone_meal=1066 武装手持 + USE_ITEM_ON(0x3f)
 * 于 bot 脚下 wheat 顶面生效。腿 B 症状=uses 3,456 全发+farmland 在场但
 * 零龄推进——活口三选：手持 id 错（held 变异物）/reach 拒绝/交互语义错。
 *
 * 取证四探针：①data get entity Inventory=手持真值（id 对错一锤定音）
 * ②wheat 前/后龄状态探针 ③farmland 在场 ④多方向×多位复试。
 */
public final class GrowVerify {
    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args.length > 0 ? args[0]
            : "F:/Github/repo/Papo/paper-server/build/libs/Papo-1.21.11-0.113.0.jar");
        final int port = 25598;
        final Path dir = Files.createTempDirectory("papo-growverify-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + port, "level-seed=papo90",
            "max-players=20", "view-distance=4", "simulation-distance=4",
            "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "gamemode=creative",
            "motd=papo-gvy", "sync-chunk-writes=false", "enforce-secure-profile=false", ""),
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
        final OfflineJoinBot bot = new OfflineJoinBot("127.0.0.1", port, "GVY00");
        final Thread bt = new Thread(() -> {
            try {
                bot.joinWalkDwellAndDisconnect(0, 0, 0, 60_000);
            } catch (final Exception e) {
                System.out.println("GVY bot failed: " + e);
            }
        }, "bot");
        bt.setDaemon(true);
        bt.start();

        Thread.sleep(9_000);
        final int sx = (int) Math.floor(bot.getPosX()), sz = (int) Math.floor(bot.getPosZ());
        final int sy = (int) Math.floor(bot.getPosY());
        System.out.println("GVY bot pos x=" + bot.getPosX() + " y=" + bot.getPosY() + " z=" + bot.getPosZ());
        // 田=bot 脚下一格：farmland sy-2 + wheat sy-1（feet sy → wheat 盒 [sy-1,sy]）
        final int wy = sy - 1;
        console.write(("fill " + (sx - 2) + " " + (sy - 2) + " " + (sz - 2) + " " + (sx + 2) + " " + (sy - 2) + " " + (sz + 2)
            + " minecraft:farmland[moisture=7]\n").getBytes(StandardCharsets.UTF_8));
        console.write(("fill " + (sx - 2) + " " + wy + " " + (sz - 2) + " " + (sx + 2) + " " + wy + " " + (sz + 2)
            + " minecraft:wheat[age=0]\n").getBytes(StandardCharsets.UTF_8));
        console.write(("data get block " + sx + " " + wy + " " + sz + "\n").getBytes(StandardCharsets.UTF_8));
        console.write(("execute if block " + sx + " " + wy + " " + sz + " minecraft:wheat run say GVY-WHEAT-ANY-BEFORE\n").getBytes(StandardCharsets.UTF_8));
        console.write(("execute if block " + sx + " " + wy + " " + sz + " minecraft:wheat[age=0] run say GVY-WHEAT-AGE0-BEFORE\n").getBytes(StandardCharsets.UTF_8));
        console.flush();
        Thread.sleep(2_000);

        bot.setCreativeSlotItem(36, 1082, 1);  // 候选：源码序 1066+dye 族漂移 16 外推
        bot.setCreativeSlotItem(37, 1081, 1);  // 邻位夹逼
        bot.setCreativeSlotItem(38, 1083, 1);
        bot.setCreativeSlotItem(39, 1084, 1);
        Thread.sleep(1_500);
        // 位①脚下直击+位②东侧 2 格（d2 递增复试）
        bot.placeOnFace(sx, wy, sz, 1);
        bot.placeOnFace(sx + 2, wy, sz, 1);
        Thread.sleep(2_000);

        for (int age = 1; age <= 7; age++) {
            console.write(("execute if block " + sx + " " + wy + " " + sz + " minecraft:wheat[age=" + age + "] run say GVY-CENTER-AGE" + age + "\n").getBytes(StandardCharsets.UTF_8));
            console.write(("execute if block " + (sx + 2) + " " + wy + " " + sz + " minecraft:wheat[age=" + age + "] run say GVY-EAST-AGE" + age + "\n").getBytes(StandardCharsets.UTF_8));
        }
        console.write("data get entity GVY00 Inventory\n".getBytes(StandardCharsets.UTF_8));
        console.write(("execute if block " + sx + " " + (sy - 2) + " " + sz + " minecraft:farmland run say GVY-FARMLAND\n").getBytes(StandardCharsets.UTF_8));
        console.write(("execute if block " + sx + " " + wy + " " + sz + " minecraft:wheat[age=0] run say GVY-WHEAT-AGE0-AFTER\n").getBytes(StandardCharsets.UTF_8));
        console.flush();
        Thread.sleep(4_000);

        synchronized (logLines) {
            for (final String l : logLines) {
                if (l.contains("GVY-") || l.contains("following entity data") || l.contains("following block data") || l.contains("Changed the block") || l.contains("Too many blocks") || l.contains("out of the world") || l.contains("GVY00 lost") || l.contains("Exception") || l.contains("has the following block data")
                    || l.contains("GVY00 lost connection") || l.contains("DecoderException")) {
                    System.out.println("GVY: " + l.trim());
                }
            }
        }
        System.out.println("GVY verdict: botAt=" + sx + "," + sy + "," + sz + " placesSent=" + bot.getPlacesSent());
        console.write("stop\n".getBytes(StandardCharsets.UTF_8));
        console.flush();
        server.waitFor();
        System.out.println("GVY server exit=" + server.exitValue());
    }
}
