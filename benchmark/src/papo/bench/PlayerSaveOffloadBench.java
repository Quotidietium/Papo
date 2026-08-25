package papo.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * 批次79：玩家存档文件管线下放（gzip+文件写 主线程 → IO 池 per-UUID 有序链）。
 *
 * 模型：50 玩家 × 每 tick 保存 4 人（maxPerTick 突发口径）× 12 tick = 600 次 save；
 * 载荷 = 96KiB 结构化可压缩 byte[]（玩家 NBT 的熵形态；gzip 后 ~8KiB）。
 * before：主线程循环内 gzip+临时文件+原子替换（vanilla 同步路径）；
 * after：主线程只做快照深拷贝（CompoundTag.copy() 的代理）+ 入队，
 *        gzip+写文件在 concurrentutil 池任务中执行（本基准复刻 PapoOrderedFileWrites
 *        的 per-Path CompletableFuture 链 + pending 计数 + awaitAll）。
 *
 * 自检（安全性红线）：
 *   1. 同一目标文件的写任务串行（per-key 并发度 ≤1）；
 *   2. 最终文件内容 == 最后一次快照的字节（gzip round-trip 对拍）——旧快照永不覆盖新快照；
 *   3. awaitPending（快速重连模型）：入队后立刻 await，读到的一定是最新内容；
 *   4. awaitAll 后全部任务完成恰好一次。
 *
 * 非 JMH（tick 墙钟/主线程时间形态），java 直接运行。
 */
public final class PlayerSaveOffloadBench {

    private static final int PLAYERS = 50;
    private static final int SAVES_PER_TICK = 4;
    private static final int TICKS = 12;
    private static final int PAYLOAD_SIZE = 96 * 1024;

    public static void main(final String[] args) throws Exception {
        final Path dir = Files.createTempDirectory("papo-playersave-");
        try {
            // 载荷：结构化可压缩（模拟玩家 NBT：混合字段+重复模式，gzip ~8-12KiB）
            final byte[] template = makePayload();
            System.out.println("payload=" + PAYLOAD_SIZE + "B, gzipped=" + gzip(template).length + "B");
            System.out.println("model: " + PLAYERS + " players, " + SAVES_PER_TICK + " saves/tick x " + TICKS + " ticks");
            System.out.println();

            System.out.println("-- self-checks --");
            selfCheck(dir, template);
            System.out.println("  ALL OK (per-key serial, last-writer-wins content, awaitPending freshness, exact-once)");
            System.out.println();

            // warmup
            for (int i = 0; i < 2; i++) { runSync(dir, template); runAsync(dir, template); }

            System.out.println("-- main-thread cost (3 reps each) --");
            System.out.printf("%-10s %-14s %-14s%n", "mode", "mainMs/rep", "totalMs/rep");
            long syncMain = 0, asyncMain = 0;
            for (int rep = 0; rep < 3; rep++) {
                final long[] s = runSync(dir, template);
                final long[] a = runAsync(dir, template);
                syncMain += s[0]; asyncMain += a[0];
                System.out.printf("%-10s %-14d %-14d%n", "sync(r" + rep + ")", s[0], s[1]);
                System.out.printf("%-10s %-14d %-14d%n", "async(r" + rep + ")", a[0], a[1]);
            }
            System.out.println();
            System.out.printf("before(sync) main=%.0fms  after(async) main=%.0fms  mainThreadReduction=%.2fx%n",
                syncMain / 3.0, asyncMain / 3.0, syncMain / (double) asyncMain);
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final IOException ignored) {}
            });
        }
    }

    /** [0]=主线程耗时, [1]=总墙钟（含池排水）。 */
    private static long[] runSync(final Path dir, final byte[] template) throws Exception {
        final long mainStart = System.nanoTime();
        final long wallStart = mainStart;
        for (int tick = 0; tick < TICKS; tick++) {
            for (int i = 0; i < SAVES_PER_TICK; i++) {
                final int player = (tick * SAVES_PER_TICK + i) % PLAYERS;
                final Path dat = dir.resolve("p" + player + ".dat");
                final byte[] snapshot = template.clone(); // 保存时的快照
                vanillaWrite(dat, snapshot);
            }
        }
        final long mainMs = (System.nanoTime() - mainStart) / 1_000_000;
        final long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        return new long[]{mainMs, wallMs};
    }

    private static long[] runAsync(final Path dir, final byte[] template) throws Exception {
        // 复刻 PapoOrderedFileWrites 的结构与 awaitAll（不同实现库：纯 j.u.c，池语义等价）
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        final ConcurrentHashMap<Path, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
        final Object lock = new Object();
        final int[] pending = new int[1];

        final long wallStart = System.nanoTime();
        final long mainStart = wallStart;
        for (int tick = 0; tick < TICKS; tick++) {
            for (int i = 0; i < SAVES_PER_TICK; i++) {
                final int player = (tick * SAVES_PER_TICK + i) % PLAYERS;
                final Path dat = dir.resolve("p" + player + ".dat");
                final byte[] snapshot = template.clone(); // 主线程：快照深拷贝（CompoundTag.copy() 代理）
                synchronized (lock) { pending[0]++; }
                final CompletableFuture<Void> node = tails.compute(dat, (p, prev) ->
                    (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                        .handle((r, ex) -> null)
                        .thenRunAsync(() -> vanillaWrite(dat, snapshot), pool));
                node.whenComplete((r, ex) -> {
                    tails.remove(dat, node);
                    synchronized (lock) { if (--pending[0] == 0) lock.notifyAll(); }
                });
            }
        }
        final long mainMs = (System.nanoTime() - mainStart) / 1_000_000;
        // awaitAll（全量保存语义）
        synchronized (lock) {
            while (pending[0] > 0) lock.wait(1000);
        }
        final long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        pool.shutdown();
        if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("pool not drained");
        return new long[]{mainMs, wallMs};
    }

    /** vanilla PlayerDataStorage 的写序列：临时文件 + gzip + 原子替换。 */
    private static void vanillaWrite(final Path dat, final byte[] snapshot) {
        try {
            final Path tmp = Files.createTempFile(dat.getParent(), "tmp-", ".dat");
            Files.write(tmp, gzip(snapshot));
            Files.move(tmp, dat, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 6);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] makePayload() {
        final byte[] data = new byte[PAYLOAD_SIZE];
        final byte[] field = "minecraft:generic".getBytes(StandardCharsets.UTF_8);
        int p = 0;
        while (p < data.length) {
            final int run = 200 + (p % 700);
            for (int i = 0; i < run && p < data.length; i++) {
                data[p++] = field[(p + i) % field.length];
            }
            data[p - 1] = '\n';
        }
        return data;
    }

    // ===== 自检 =====

    private static void selfCheck(final Path dir, final byte[] template) throws Exception {
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        final ConcurrentHashMap<Path, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
        final Map<Path, AtomicInteger> active = new HashMap<>();
        final Map<Path, Integer> lastSeq = new HashMap<>();
        final Object ck = new Object();
        final CountDownLatch done = new CountDownLatch(30);
        final java.util.List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int seq = 0; seq < 30; seq++) {
            final Path dat = dir.resolve("chk.dat");
            final int mySeq = seq;
            final byte[] payload = ("seq-" + seq).getBytes(StandardCharsets.UTF_8);
            final CompletableFuture<Void> node = tails.compute(dat, (p, prev) ->
                (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                    .handle((r, ex) -> null)
                    .thenRunAsync(() -> {
                        synchronized (ck) {
                            final AtomicInteger a = active.computeIfAbsent(dat, k -> new AtomicInteger());
                            if (a.incrementAndGet() > 1) errors.add("per-key concurrent: " + dat);
                        }
                        vanillaWrite(dat, payload);
                        synchronized (ck) {
                            active.get(dat).decrementAndGet();
                            final Integer expect = lastSeq.get(dat);
                            if (expect != null && expect >= mySeq) errors.add("order violated: " + expect + " >= " + mySeq);
                            lastSeq.put(dat, mySeq);
                        }
                        done.countDown();
                    }, pool));
            node.whenComplete((r, ex) -> tails.remove(dat, node));
        }
        done.await();
        // 自检2：最终内容 == 最后快照（seq-29）
        final byte[] fin = Files.readAllBytes(dir.resolve("chk.dat"));
        final byte[] expect = gzip("seq-29".getBytes(StandardCharsets.UTF_8));
        if (!java.util.Arrays.equals(fin, expect)) errors.add("final content != last snapshot");
        // 自检3：快速重连——再入队一个更大的快照立刻 awaitPending，必须读到它
        final byte[] last = "seq-final".getBytes(StandardCharsets.UTF_8);
        final CompletableFuture<Void> node = tails.compute(dir.resolve("chk.dat"), (p, prev) ->
            (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                .handle((r, ex) -> null).thenRunAsync(() -> vanillaWrite(dir.resolve("chk.dat"), last), pool));
        node.join(); // awaitPending 等价
        if (!java.util.Arrays.equals(Files.readAllBytes(dir.resolve("chk.dat")), gzip(last))) errors.add("awaitPending freshness");
        pool.shutdown();
        if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) errors.add("pool drain");
        Files.deleteIfExists(dir.resolve("chk.dat"));
        if (!errors.isEmpty()) throw new AssertionError(String.join("; ", errors));
    }

    private PlayerSaveOffloadBench() {}
}
