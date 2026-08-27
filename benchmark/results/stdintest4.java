import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;

public class stdintest4 {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("papo-stdin4-");
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
        // 两个键名各测一头，落地后 25 秒用 execute-if 判存活（坠落 80 格，无抗性者必死）
        String c1 = "summon cow 2.5 180.0 0.5 {PersistenceRequired:1b,ActiveEffects:[{id:\"minecraft:resistance\",amplifier:4,duration:9999999,show_particles:0b}]}";
        String c2 = "summon cow -2.5 180.0 0.5 {PersistenceRequired:1b,active_effects:[{id:\"minecraft:resistance\",amplifier:4,duration:9999999,show_particles:0b}]}";
        System.out.println("CMD1=" + c1);
        System.out.println("CMD2=" + c2);
        os.write((c1 + "\n").getBytes(StandardCharsets.UTF_8)); os.flush();
        os.write((c2 + "\n").getBytes(StandardCharsets.UTF_8)); os.flush();
        Thread.sleep(25000);
        os.write("execute if entity @e[type=cow,x=0,y=80,dx=10] run say CAMEL_PRESENT\n".getBytes(StandardCharsets.UTF_8)); os.flush();
        Thread.sleep(2000);
        os.write("execute if entity @e[type=cow,x=-10,y=80,dx=10] run say SNAKE_PRESENT\n".getBytes(StandardCharsets.UTF_8)); os.flush();
        Thread.sleep(3000);
        List<String> snapshot = new ArrayList<>(all);
        boolean camel = snapshot.stream().anyMatch(l -> l.contains("CAMEL_PRESENT"));
        boolean snake = snapshot.stream().anyMatch(l -> l.contains("SNAKE_PRESENT"));
        System.out.println("ActiveEffects_alive=" + camel + " active_effects_alive=" + snake);
        snapshot.stream().skip(Math.max(0, snapshot.size()-5)).forEach(l -> System.out.println(">> " + l.trim()));
        os.write("stop\n".getBytes(StandardCharsets.UTF_8)); os.flush();
        boolean done = s.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("stopped=" + done);
    }
}
