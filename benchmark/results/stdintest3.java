import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;

public class stdintest3 {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("papo-stdin3-");
        Files.copy(Path.of("../paper-server/build/libs/Papo-1.21.11-0.65.0.jar"), dir.resolve("server.jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), "online-mode=false\nserver-port=25594\nlevel-seed=papo90\nview-distance=4\nsimulation-distance=4\nmotd=x\nsync-chunk-writes=false\nenforce-secure-profile=false\n");
        Process s = new ProcessBuilder("F:/Java/21/bin/java","-Xmx2G","-Dfile.encoding=UTF-8","-jar","server.jar","nogui")
            .directory(dir.toFile()).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        List<String> all = new ArrayList<>();
        Thread drain = new Thread(() -> { try { String l; while ((l = r.readLine()) != null) all.add(l); } catch (Exception ignored) {} });
        drain.setDaemon(true); drain.start();
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis()-t0 < 180000) {
            Thread.sleep(200);
            if (all.stream().anyMatch(l -> l.contains("Done ("))) break;
        }
        System.out.println("BOOT-OK");
        Thread.sleep(3000);
        OutputStream os = s.getOutputStream();
        String cmd = "summon cow 0.5 180.0 0.5 {NoAI:1b,PersistenceRequired:1b,ActiveEffects:[{id:\"minecraft:resistance\",amplifier:4,duration:9999999,show_particles:0b}]}";
        System.out.println("CMD=" + cmd);
        os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8)); os.flush();
        Thread.sleep(8000);
        List<String> snapshot = new ArrayList<>(all);
        System.out.println("AFTER-SUMMON total_lines=" + snapshot.size());
        snapshot.stream().skip(Math.max(0, snapshot.size()-6)).forEach(l -> System.out.println(">> " + l.trim()));
        os.write("stop\n".getBytes(StandardCharsets.UTF_8)); os.flush();
        boolean done = s.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("stopped=" + done);
    }
}
