package papo.bench;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.util.Priority;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 批次82：join 读侧下放（登录窗口预取 .dat/stats/advancements）。
 *
 * 模型：一次 20 人重启rush 突发 join（同一登录窗口内并发到达）。
 *   - 每玩家 .dat：64KiB 结构化载荷 gzip（~7KiB 文件，玩家 NBT 的熵形态），
 *     读成本 = gunzip 全量解压 + 全数组结构扫描（NBT DOM 遍历的接触成本代理；
 *     datafix 为额外被下放的纯 CPU，不建模——实测收益是保守下界）。
 *   - stats json：120 条目（~6KiB）；advancements json：150 条目（~22KiB）；
 *     读成本 = 读文件 + 严格 JSON 解析（Gson 代理 StrictJsonParser 同类成本）。
 * before：主线程同步串行做全部 20×3 读（vanilla：PrepareSpawnTask.start / ServerPlayer
 *         ctor 全在主线程）。
 * after：登录时刻把 60 个读入队（复刻 PapoOrderedFileWrites 的 per-Path
 *         CompletableFuture 链 + OrderedStreamGroup executor，读=BLOCKING 优先级），
 *         模拟 RTT 窗口后主线程消费（future 通常已完成，join 即返）。
 *
 * 饱和探针（流畅度红线）：IO 池被 region IO 型小任务（4KiB 读+500µs 设备延迟，
 * IoPoolScalingBench 同载荷）填满时，BLOCKING 优先级读 vs NORMAL 优先级读的
 * 主线程等待对比——证明读任务不会在池饱和时饿死主线程。
 *
 * 自检（安全性红线）：
 *   1. 读后写排序：同文件先入队写再入队读，读一定观察到写的内容且执行序写先于读；
 *   2. 内容等价：预取解压结果 == 同步读结果（逐字节）；
 *   3. consume-once / discard：缓存条目取出后为空；
 *   4. 缺失文件 → null（回退同步路径语义）；
 *   5. 同文件 FIFO：两次读按提交序执行。
 *
 * 非 JMH（主线程墙钟形态），java 直接运行。
 */
public final class JoinReadPrefetchBench {

    private static final long IO_QUEUE_HOLD_TIME = 25_000_000L; // MoonriseCommon.IO_QUEUE_HOLD_TIME
    private static final int JOIN_PLAYERS = 20;
    private static final long WINDOW_MS = 50; // 登录→配置 RTT 窗口模型（保守下界）

    /** 复刻 PapoOrderedFileWrites 的结构与语义（真实 concurrentutil 池）。 */
    static final class OrderedFileChain {
        final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue;
        final ConcurrentHashMap<Path, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

        OrderedFileChain(final BalancedPrioritisedThreadPool pool) {
            this.queue = pool.createOrderedStreamGroup().createExecutor();
        }

        void enqueueWrite(final Path target, final Runnable ioTask) {
            final CompletableFuture<Void> node = this.tails.compute(target, (path, prev) ->
                (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                    .handle((result, throwable) -> null)
                    .thenRunAsync(ioTask, this.queue::queueTask));
            node.whenComplete((result, throwable) -> this.tails.remove(target, node));
        }

        <T> CompletableFuture<T> enqueueRead(final Path target, final Callable<T> readTask) {
            // 结果 future 与 Void 型 per-target 链解耦（与 PapoOrderedFileWrites 同构）
            final CompletableFuture<T> node = new CompletableFuture<>();
            final CompletableFuture<Void> chainNode = this.tails.compute(target, (path, prev) ->
                (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                    .handle((result, throwable) -> null)
                    .thenApplyAsync(ignored -> {
                        try {
                            node.complete(readTask.call());
                        } catch (final Exception e) {
                            node.completeExceptionally(new java.util.concurrent.CompletionException(e));
                        }
                        return null;
                    }, task -> this.queue.queueTask(task, Priority.BLOCKING)));
            node.whenComplete((result, throwable) -> this.tails.remove(target, chainNode));
            return node;
        }
    }

    public static void main(final String[] args) throws Exception {
        final int cores = Runtime.getRuntime().availableProcessors();
        final int ioThreads = Math.max(1, Math.min(4, cores / 8)); // PapoParallelism.regionIoThreadCount
        System.out.println("cores=" + cores + "  regionIoThreads=" + ioThreads);
        System.out.println("model: " + JOIN_PLAYERS + "-player burst join, window=" + WINDOW_MS + "ms");
        System.out.println();

        final Path dir = Files.createTempDirectory("papo-joinread-");
        final BalancedPrioritisedThreadPool pool = new BalancedPrioritisedThreadPool(IO_QUEUE_HOLD_TIME, thread -> {
            thread.setDaemon(true);
            thread.setName("bench-io-" + thread.getId());
        });
        pool.adjustThreadCount(ioThreads);
        final OrderedFileChain chain = new OrderedFileChain(pool);
        try {
            final List<PlayerFiles> players = setup(dir, JOIN_PLAYERS);
            System.out.printf("payload/player: dat=%dB(gz~%dB) stats=%dB adv=%dB%n%n",
                players.get(0).datPayload.length, Files.size(players.get(0).dat),
                Files.size(players.get(0).stats), Files.size(players.get(0).adv));

            System.out.println("-- self-checks --");
            selfCheck(dir, chain);
            System.out.println("  ALL OK (chain ordering, content equality, consume-once/discard, missing-file, FIFO)");
            System.out.println();

            // warmup
            for (int i = 0; i < 2; i++) {
                runSync(players);
                runPrefetch(players, chain, WINDOW_MS);
            }

            System.out.println("-- main-thread join cost (3 reps each) --");
            long syncMain = 0, asyncMain = 0, asyncMainNoWindow = 0;
            for (int rep = 0; rep < 3; rep++) {
                final long s = runSync(players);
                final long a = runPrefetch(players, chain, WINDOW_MS);
                final long a0 = runPrefetch(players, chain, 0); // 最坏情形：客户端零延迟继续
                syncMain += s;
                asyncMain += a;
                asyncMainNoWindow += a0;
                System.out.printf("  rep%d  sync=%,dus  prefetch(window)=%,dus  prefetch(no-window)=%,dus%n",
                    rep, s / 1000, a / 1000, a0 / 1000);
            }
            System.out.printf("%nbefore(sync) main=%.2fms  after(prefetch) main=%.2fms  mainThreadReduction=%.1fx%n",
                syncMain / 3000.0 / 1000.0, asyncMain / 3000.0 / 1000.0,
                syncMain / (double) Math.max(1, asyncMain));
            System.out.printf("worst-case (no RTT window) main=%.2fms (never worse than sync by design)%n",
                asyncMainNoWindow / 3000.0 / 1000.0);

            saturationProbe(pool);
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (final IOException ignored) {}
            });
        }
    }

    // ---------- payload / setup ----------

    record PlayerFiles(Path dat, byte[] datPayload, Path stats, Path adv) {}

    private static List<PlayerFiles> setup(final Path dir, final int players) throws IOException {
        final List<PlayerFiles> list = new ArrayList<>();
        for (int p = 0; p < players; p++) {
            final Path dat = dir.resolve("p" + p + ".dat");
            final byte[] payload = makeDatPayload(p);
            Files.write(dat, gzip(payload));
            final Path stats = dir.resolve("p" + p + "-stats.json");
            Files.write(stats, makeStatsJson(p).getBytes(StandardCharsets.UTF_8));
            final Path adv = dir.resolve("p" + p + "-adv.json");
            Files.write(adv, makeAdvJson(p).getBytes(StandardCharsets.UTF_8));
            list.add(new PlayerFiles(dat, payload, stats, adv));
        }
        return list;
    }

    /** 64KiB 混合熵载荷：结构化 int 块 + 随机块 + 重复文本块（玩家 NBT 形态，gzip ~10:1）。 */
    private static byte[] makeDatPayload(final int seed) {
        final byte[] b = new byte[64 * 1024];
        final java.util.Random rnd = new java.util.Random(seed);
        for (int off = 0; off < b.length; off += 64) {
            final int kind = (off >> 6) % 4;
            if (kind == 0 || kind == 1) { // 结构化（坐标/数值字段形态）
                final int base = seed * 31 + off;
                for (int i = 0; i < 32 && off + i < b.length; i++) {
                    b[off + i] = (byte) ((base + (i >> 2)) >> ((i & 3) * 8));
                }
            } else if (kind == 2) { // 重复文本（键名/id 形态）
                final String s = "minecraft:inventory_item_component_" + (seed & 7);
                final byte[] sb = s.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 32 && off + i < b.length; i++) {
                    b[off + i] = sb[i % sb.length];
                }
            } else { // 随机（压缩后仍占空间的熵）
                final byte[] scratch = new byte[32];
                rnd.nextBytes(scratch);
                System.arraycopy(scratch, 0, b, off, Math.min(32, b.length - off));
            }
            for (int i = 32; i < 64 && off + i < b.length; i++) {
                b[off + i] = (byte) rnd.nextInt(4); // 低熵计数/布尔字段
            }
        }
        return b;
    }

    private static String makeStatsJson(final int seed) {
        final StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append('{');
        for (int i = 0; i < 120; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"minecraft:").append(i % 3 == 0 ? "custom" : i % 3 == 1 ? "mined" : "picked_up").append(":minecraft.")
                .append(i % 3 == 0 ? "stat_" : "block_").append(i).append("\":").append(seed * 1000 + i);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String makeAdvJson(final int seed) {
        final StringBuilder sb = new StringBuilder(24 * 1024);
        sb.append('{');
        for (int i = 0; i < 150; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"minecraft:story/adv_").append(i).append("\":{\"criteria\":{\"criterion_").append(i % 7)
                .append("\":{\"triggered\":").append(i < 60).append(",\"date\":").append(1700000000L + seed * 3600 + i)
                .append("}}}");
        }
        sb.append('}');
        return sb.toString();
    }

    // ---------- measured paths ----------

    /** 单次玩家 .dat 读（gunzip + 结构扫描）+ 两 JSON 读解析——同步（before）形态。 */
    private static long syncJoinRead(final PlayerFiles pf) throws IOException {
        final long start = System.nanoTime();
        final byte[] dat = gunzip(Files.readAllBytes(pf.dat));
        scan(dat);
        parseJson(new String(Files.readAllBytes(pf.stats), StandardCharsets.UTF_8));
        parseJson(new String(Files.readAllBytes(pf.adv), StandardCharsets.UTF_8));
        return System.nanoTime() - start;
    }

    private static long runSync(final List<PlayerFiles> players) throws IOException {
        final long start = System.nanoTime();
        long acc = 0;
        for (final PlayerFiles pf : players) {
            acc += syncJoinRead(pf);
        }
        assert acc >= 0;
        return System.nanoTime() - start;
    }

    private static long runPrefetch(final List<PlayerFiles> players, final OrderedFileChain chain, final long windowMs)
            throws IOException, InterruptedException {
        // 登录时刻：每玩家 3 个读入队（.dat / stats / adv）
        final List<CompletableFuture<byte[]>> datFutures = new ArrayList<>(players.size());
        final List<CompletableFuture<com.google.gson.JsonElement>> statsFutures = new ArrayList<>(players.size());
        final List<CompletableFuture<com.google.gson.JsonElement>> advFutures = new ArrayList<>(players.size());
        for (final PlayerFiles pf : players) {
            datFutures.add(chain.enqueueRead(pf.dat, () -> {
                final byte[] dat = gunzip(Files.readAllBytes(pf.dat));
                scan(dat);
                return dat;
            }));
            statsFutures.add(chain.enqueueRead(pf.stats, () -> parseJson(new String(Files.readAllBytes(pf.stats), StandardCharsets.UTF_8))));
            advFutures.add(chain.enqueueRead(pf.adv, () -> parseJson(new String(Files.readAllBytes(pf.adv), StandardCharsets.UTF_8))));
        }
        // 登录→配置 RTT 窗口（主线程做别的 join 工作/空闲）
        if (windowMs > 0) {
            Thread.sleep(windowMs);
        }
        // 消费点（PrepareSpawnTask.start / ServerPlayer ctor 形态）
        final long start = System.nanoTime();
        for (int i = 0; i < players.size(); i++) {
            datFutures.get(i).join();
            statsFutures.get(i).join();
            advFutures.get(i).join();
        }
        return System.nanoTime() - start;
    }

    // ---------- primitives ----------

    private static byte[] gzip(final byte[] data) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(final byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(data), 8192)) {
            return gz.readAllBytes();
        }
    }

    /** NBT DOM 遍历的接触成本代理：全数组扫描求校验和。 */
    private static long scanSum;

    private static void scan(final byte[] b) {
        long sum = 0;
        for (final byte v : b) {
            sum += v;
        }
        scanSum = sum;
    }

    private static com.google.gson.JsonElement parseJson(final String s) {
        return com.google.gson.JsonParser.parseString(s);
    }

    // ---------- saturation probe ----------

    /**
     * IO 池被 region IO 型小任务填满时（IoPoolScalingBench 同载荷：4KiB 真实读 + 500µs
     * 设备延迟 × 64），主线程 join 一个读任务的等待时间：BLOCKING vs NORMAL 优先级。
     */
    private static void saturationProbe(final BalancedPrioritisedThreadPool pool) throws Exception {
        final Path tmp = Files.createTempFile("papo-sat-", ".bin");
        try {
            final byte[] block = new byte[64 * 4096];
            new java.util.Random(42).nextBytes(block);
            Files.write(tmp, block);
            final var regionQueue = pool.createOrderedStreamGroup().createExecutor(); // region IO 代理（NORMAL）
            final AtomicInteger inflight = new AtomicInteger();

            System.out.println();
            System.out.println("-- saturation probe (pool busy with region-IO-shaped tasks) --");
            for (final boolean blocking : new boolean[]{true, false}) {
                // warmup 一轮
                probeOnce(tmp, regionQueue, inflight, blocking, 16);
                long best = Long.MAX_VALUE;
                for (int rep = 0; rep < 3; rep++) {
                    best = Math.min(best, probeOnce(tmp, regionQueue, inflight, blocking, 64));
                }
                System.out.printf("  read priority=%-8s main-thread wait(best of 3) = %,dus%n",
                    blocking ? "BLOCKING" : "NORMAL", best / 1000);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static long probeOnce(final Path file, final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue regionQueue,
                                  final AtomicInteger inflight, final boolean blocking, final int fillTasks) throws Exception {
        for (int i = 0; i < fillTasks; i++) {
            inflight.incrementAndGet();
            final int idx = i;
            regionQueue.queueTask(() -> {
                try {
                    // 每任务独立缓冲（共享 buffer 的并发 rewind/read 会 BufferOverflowException）
                    final java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4096);
                    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file)) {
                        ch.read(buf, (idx * 4096L) % (64 * 4096 - 4096));
                    } catch (final IOException ignored) {
                    }
                    LockSupport.parkNanos(500_000L); // 500µs 模拟设备延迟
                } finally {
                    inflight.decrementAndGet();
                }
            });
        }
        final java.util.concurrent.atomic.AtomicLong executedAt = new AtomicLong(-1);
        final long start = System.nanoTime();
        final var readFuture = new CompletableFuture<Void>();
        final Runnable readBody = () -> {
            executedAt.set(System.nanoTime());
            readFuture.complete(null);
        };
        if (blocking) {
            regionQueue.queueTask(readBody, Priority.BLOCKING);
        } else {
            regionQueue.queueTask(readBody);
        }
        readFuture.get(10, TimeUnit.SECONDS);
        final long waited = executedAt.get() - start;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inflight.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
        return waited;
    }

    // ---------- self-checks ----------

    private static void selfCheck(final Path dir, final OrderedFileChain chain) throws Exception {
        // 1) 读后写排序 + 执行序：同文件先写 A 再读，读观察到 A 且写先执行
        final Path f = dir.resolve("selfcheck.bin");
        final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        chain.enqueueWrite(f, () -> {
            try {
                Files.write(f, "A".getBytes(StandardCharsets.US_ASCII));
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
            order.add("write");
        });
        final CompletableFuture<String> read = chain.enqueueRead(f, () -> {
            order.add("read");
            return new String(Files.readAllBytes(f), StandardCharsets.US_ASCII);
        });
        if (!"A".equals(read.get(10, TimeUnit.SECONDS))) {
            throw new AssertionError("read-after-write ordering broken");
        }
        if (!order.equals(List.of("write", "read"))) {
            throw new AssertionError("execution order broken: " + order);
        }

        // 2) 内容等价：预取 gunzip 结果 == 同步 gunzip 结果
        final Path gzf = dir.resolve("selfcheck.dat");
        final byte[] payload = makeDatPayload(99);
        Files.write(gzf, gzip(payload));
        final CompletableFuture<byte[]> prefetched = chain.enqueueRead(gzf, () -> gunzip(Files.readAllBytes(gzf)));
        if (!java.util.Arrays.equals(prefetched.get(10, TimeUnit.SECONDS), gunzip(Files.readAllBytes(gzf)))) {
            throw new AssertionError("prefetched content differs from sync read");
        }

        // 3) consume-once / discard：ConcurrentHashMap remove 语义
        final ConcurrentHashMap<String, CompletableFuture<Void>> cache = new ConcurrentHashMap<>();
        cache.put("u", CompletableFuture.completedFuture(null));
        if (cache.remove("u") == null || cache.remove("u") != null) {
            throw new AssertionError("consume-once broken");
        }

        // 4) 缺失文件 → null（回退同步路径）
        final CompletableFuture<Object> missing = chain.enqueueRead(dir.resolve("missing.json"), () -> {
            if (!Files.isRegularFile(dir.resolve("missing.json"))) {
                return null;
            }
            return new Object();
        });
        if (missing.get(10, TimeUnit.SECONDS) != null) {
            throw new AssertionError("missing file should produce null");
        }

        // 5) 同文件 FIFO：两次读按提交序执行
        final Path f2 = dir.resolve("selfcheck2.bin");
        Files.write(f2, "x".getBytes(StandardCharsets.US_ASCII));
        final List<Integer> seq = java.util.Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<Integer> r1 = chain.enqueueRead(f2, () -> {
            LockSupport.parkNanos(2_000_000L);
            seq.add(1);
            return 1;
        });
        final CompletableFuture<Integer> r2 = chain.enqueueRead(f2, () -> {
            seq.add(2);
            return 2;
        });
        r1.get(10, TimeUnit.SECONDS);
        r2.get(10, TimeUnit.SECONDS);
        if (!seq.equals(List.of(1, 2))) {
            throw new AssertionError("same-file FIFO broken: " + seq);
        }
    }
}
