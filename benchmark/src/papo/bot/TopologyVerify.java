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
 * 批次126 拓扑验收 harness：直接验证 wire input-dirty 跳过机制在两类"非常规"
 * 输入变化面下的行为等价——
 *
 * ① 直通强充能（Chebyshev-2 闭包）：石块后的中继器翻转，石块旁的粉读取其
 *   直通信号。粉距中继器 Chebyshev-2，闭包缺失时到达 clean 被跳过 → power 停摆。
 *   序列：repeater powered=false→true→false，每次后探针粉 power（15→0）。
 * ② 比较器模拟输出刷新（无 transition 类）：比较器读箱子，/data merge 改变
 *   箱内物品数 → BE 输出 1→5（POWERED 恒 true，无 setBlock）→ updateNeighborsInFront
 *   通知前方粉。粉经强充能石块读取模拟值，距比较器 Chebyshev-2。
 *
 * 两腿同 jar：-Dpapo.wireSkip 未设（默认 skip 开）vs PAPO_JVM_EXTRA=-Dpapo.wireSkip=0
 * （vanilla 行为）——验收 = 两腿探针输出逐行全等。
 *
 * 用法：java papo.bot.TopologyVerify <jar>
 */
public final class TopologyVerify {
    private static final int PORT = 25596;

    public static void main(final String[] args) throws Exception {
        final Path jar = Path.of(args[0]);
        final Path dir = Files.createTempDirectory("papo-topo-");
        Files.copy(jar, dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
            "online-mode=false", "server-port=" + PORT, "level-seed=papo90",
            "max-players=5", "view-distance=4", "simulation-distance=6", "spawn-protection=0",
            "gamemode=survival", "spawn-monsters=false", "generate-structures=false",
            "motd=topo", ""), StandardCharsets.UTF_8);

        final Process server = new ProcessBuilder(
            "F:/Java/21/bin/java", "-Xmx2G", "-Dfile.encoding=UTF-8", "-Dpapo.tickProfile=1",
            TopologyVerify.envJvmExtra(),
            "-jar", "server.jar", "nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        final List<String> logLines = new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
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
                    synchronized (logLines) { logLines.add(l); }
                }
            } catch (final IOException ignored) {
            }
        }, "log-tail");
        tail.setDaemon(true);
        tail.start();

        // 建造区常驻加载（无 bot 锚点，用 forceload）
        writeChunked(server, "forceload add 4 4 14 14\n");
        Thread.sleep(2500);

        // y=200 建造层 + y=199 支撑层（粉/中继器/比较器都需要下方实体方块）
        final int Y = 200;
        final StringBuilder cmds = new StringBuilder();
        cmds.append("fill 6 199 6 12 199 14 stone\n");
        // ① 直通：dust W(8,Y,8) 石 C(9,Y,8) 中继器 R(10,Y,8, facing west → 输出指向 C)
        //    R 的供电源：其后的红石块 RB(11,Y,8)（保持常供——R 落位即 powered）
        cmds.append("setblock 8 " + Y + " 8 redstone_wire\n");           // W
        cmds.append("setblock 9 " + Y + " 8 stone\n");                   // C（导体）
        cmds.append("setblock 10 " + Y + " 8 repeater[facing=east]\n");  // R（未供）
        cmds.append("setblock 11 " + Y + " 8 redstone_block\n");         // 常供源
        // ② 比较器模拟：chest(7,Y,11) comparator(8,Y,11,facing east→输出向 C2) 石 C2(9,Y,11) dust W2(10,Y,11)
        cmds.append("setblock 7 " + Y + " 11 chest\n");
        cmds.append("setblock 8 " + Y + " 11 comparator[facing=west,mode=compare]\n");
        cmds.append("setblock 9 " + Y + " 11 stone\n");
        cmds.append("setblock 10 " + Y + " 11 redstone_wire\n");         // W2
        writeChunked(server, cmds.toString());
        Thread.sleep(3000);

        // ① 激活 R（setblock 换 powered=true = transition）
        writeChunked(server, "setblock 10 " + Y + " 8 repeater[facing=east,powered=true]\n");
        Thread.sleep(1500);
        probe(server, "T1a_dust15", "8 " + Y + " 8", 15);
        // ① 移除供电源（R 延迟后跳断 = transition；直接换 unpowered 会被输入侧重新点亮）
        writeChunked(server, "setblock 11 " + Y + " 8 air\n");
        Thread.sleep(2000);
        probe(server, "T1b_dust0", "8 " + Y + " 8", 0);

        // ② 箱子放 1 个石头（comparator 输出 = floor(1+14·(1/1728)) = 1）
        writeChunked(server, "data merge block 7 " + Y + " 11 {Items:[{Slot:0b,id:\"minecraft:stone\",count:1}]}\n");
        Thread.sleep(2500);
        probe(server, "T2a_dust1", "10 " + Y + " 11", 1);
        // ② 全 27 格满 stack（输出 = floor(1+14·1.0) = 15；POWERED 恒 true——纯 BE 刷新路径）
        final StringBuilder full = new StringBuilder("data merge block 7 " + Y + " 11 {Items:[");
        for (int s = 0; s < 27; s++) {
            if (s > 0) full.append(',');
            full.append("{Slot:").append(s).append("b,id:\"minecraft:stone\",count:64}");
        }
        full.append("]}\n");
        writeChunked(server, full.toString());
        Thread.sleep(2500);
        probe(server, "T2b_dust15", "10 " + Y + " 11", 15);
        // ② 清空（输出 0 → POWERED 翻转=transition）
        writeChunked(server, "data merge block 7 " + Y + " 11 {Items:[]}\n");
        Thread.sleep(2500);
        probe(server, "T2c_dust0", "10 " + Y + " 11", 0);

        // 清场并停机
        writeChunked(server, "fill 6 199 6 12 200 14 air\nforceload remove all\n");
        Thread.sleep(1000);
        server.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        if (!server.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
        tail.join(3000);
        synchronized (logLines) {
            Files.write(dir.resolve("echoes.txt"), logLines, StandardCharsets.UTF_8);
        }
        System.out.println("echoes dumped: " + dir.resolve("echoes.txt"));
        // 探针结果摘要（say 回显只在 stdout）
        final List<String> results = new ArrayList<>();
        synchronized (logLines) {
            for (final String l : logLines) {
                if (l.contains("_OK") || l.contains("_FAIL")) {
                    results.add(l.trim());
                }
            }
        }
        results.forEach(System.out::println);
        final long fails = results.stream().filter(l -> l.contains("_FAIL")).count();
        System.out.println("TOPOLOGY " + (fails == 0 && results.size() >= 5 ? "PASS" : "FAILED") + " (" + results.size() + " probes, " + fails + " fail)");
        final List<String> all = new ArrayList<>(logLines);
        all.addAll(Files.readAllLines(dir.resolve("logs/latest.log"), StandardCharsets.UTF_8));
        final long errors = all.stream().filter(l -> l.contains("ERROR") || l.contains("Exception")).count();
        System.out.println("logErrors=" + errors + " exited=" + (server.exitValue() == 0));
        if (fails > 0 || results.size() < 5) {
            System.exit(1);
        }
    }

    private static void probe(final Process server, final String marker, final String pos, final int expectPower) throws Exception {
        server.getOutputStream().write(("execute if block " + pos + " redstone_wire[power=" + expectPower + "] run say " + marker + "_OK\n").getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().write(("execute unless block " + pos + " redstone_wire[power=" + expectPower + "] run say " + marker + "_FAIL\n").getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
        Thread.sleep(800);
    }

    private static String envJvmExtra() {
        final String extra = System.getenv("PAPO_JVM_EXTRA");
        return extra == null || extra.isBlank() ? "-Dpapo.noop=1" : extra.trim();
    }

    /** 小命令块直写（命令量小，无需 RedstoneScaleBench 的分块节流规模）。 */
    private static void writeChunked(final Process server, final String commands) throws Exception {
        server.getOutputStream().write(commands.getBytes(StandardCharsets.UTF_8));
        server.getOutputStream().flush();
    }
}
