package papo.bench;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批次91：停机窗口竞态——修复前后对拍（真实 concurrentutil 池，HaltSemanticsProbe 语义实证之上）。
 *
 * 背景（事实来自 HaltSemanticsProbe，concurrentutil 0.0.8）：
 *   - shutdown(false) 排空期间 isActive()=true，但 queueTask 抛 IllegalStateException；
 *   - 终止后 isActive()=false，queueTask 仍抛 ISE；
 *   - halt(false) 后 isActive()=true 且 queueTask 返回永不调度的 Task（残余接受面）。
 * PapoOrderedFileWrites 的 isActive() 预检只覆盖第二种。本基准用两个复刻链对拍：
 *
 *   Legacy（修复前）：thenRunAsync(QUEUE::queueTask) / 无包装读 executor；
 *   Fixed（修复后）：executor 捕获 ISE → 内联降级执行（写=就地保住耐久性，读=同步路径语义）。
 *
 * 门（安全性红线，全部断言）：
 *   G1 终止池·写：legacy ioTask 未运行（丢档）vs fixed ioTask 运行且 future 正常完成；
 *   G2 终止池·读：legacy result future 500ms 未完成（消费方 60s 悬挂）vs fixed 完成且值正确；
 *   G3 排空池·写（真实竞态窗口：auth 线程 vs stopServer 尾部）：同 G1；
 *   G4 链后续毒：终止窗口命中后，同 target 再 enqueue——legacy 悬挂 vs fixed 正常执行；
 *   G5 中断卫生：awaitPending 复刻在 get 中被中断——legacy 吞中断 vs fixed 恢复标志。
 *
 * 非 JMH，java 直接运行；退出码非 0 = 有门失败。
 */
public final class HaltRaceBench {

    private static final long HOLD = 25_000_000L;

    /** 修复前复刻（与 0.58.0 PapoOrderedFileWrites 同构）。 */
    static final class LegacyChain {
        final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue;
        final ConcurrentHashMap<Path, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
        final AtomicInteger leakedPending = new AtomicInteger();

        LegacyChain(final BalancedPrioritisedThreadPool pool) {
            this.queue = pool.createOrderedStreamGroup().createExecutor();
        }

        CompletableFuture<Void> enqueueWrite(final Path target, final Runnable ioTask) {
            // 预检在此场景必然通过（isActive=true），只模拟窗口命中的提交
            this.leakedPending.incrementAndGet();
            final CompletableFuture<Void> node = this.tails.compute(target, (path, prev) ->
                (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                    .handle((result, throwable) -> null)
                    .thenRunAsync(ioTask, this.queue::queueTask));
            node.whenComplete((result, throwable) -> {
                this.tails.remove(target, node);
                this.leakedPending.decrementAndGet();
            });
            return node;
        }

        <T> CompletableFuture<T> enqueueRead(final Path target, final java.util.concurrent.Callable<T> readTask) {
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
                    }, task -> taskRefSet(this.queue, task)));
            chainNode.whenComplete((result, throwable) -> this.tails.remove(target, chainNode));
            return node;
        }

        private static void taskRefSet(final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue, final Runnable task) {
            queue.queueTask(task, Priority.NORMAL);
        }
    }

    /** 修复后复刻（与补丁后 PapoOrderedFileWrites 同构：ISE → 内联降级）。 */
    static final class FixedChain {
        final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue;
        final ConcurrentHashMap<Path, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
        final AtomicInteger pending = new AtomicInteger();

        FixedChain(final BalancedPrioritisedThreadPool pool) {
            this.queue = pool.createOrderedStreamGroup().createExecutor();
        }

        CompletableFuture<Void> enqueueWrite(final Path target, final Runnable ioTask) {
            this.pending.incrementAndGet();
            final CompletableFuture<Void> node = this.tails.compute(target, (path, prev) ->
                (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                    .handle((result, throwable) -> null)
                    .thenRunAsync(ioTask, task -> {
                        try {
                            this.queue.queueTask(task);
                        } catch (final IllegalStateException queueShutdown) {
                            task.run();
                        }
                    }));
            node.whenComplete((result, throwable) -> {
                this.tails.remove(target, node);
                this.pending.decrementAndGet();
            });
            return node;
        }

        <T> CompletableFuture<T> enqueueRead(final Path target, final java.util.concurrent.Callable<T> readTask) {
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
                    }, task -> {
                        try {
                            this.queue.queueTask(task, Priority.NORMAL);
                        } catch (final IllegalStateException queueShutdown) {
                            task.run();
                        }
                    }));
            chainNode.whenComplete((result, throwable) -> this.tails.remove(target, chainNode));
            return node;
        }
    }

    public static void main(final String[] args) throws Exception {
        int failures = 0;
        failures += g1TerminatedWrite();
        failures += g2TerminatedRead();
        failures += g3DrainingWrite();
        failures += g4ChainAfterWindow();
        failures += g5InterruptHygiene();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL GATES PASS (G1-G5)");
        } else {
            System.out.println(failures + " GATE(S) FAILED");
            System.exit(1);
        }
    }

    private static BalancedPrioritisedThreadPool newPool() {
        final BalancedPrioritisedThreadPool pool = new BalancedPrioritisedThreadPool(HOLD, t -> t.setDaemon(true));
        pool.adjustThreadCount(1);
        return pool;
    }

    /** G1: 终止池上的写——legacy 丢任务，fixed 内联运行。 */
    private static int g1TerminatedWrite() {
        System.out.println("-- G1 终止池·写 --");
        int bad = 0;
        final Path p = Path.of("g1.dat");

        final BalancedPrioritisedThreadPool pool1 = newPool();
        final LegacyChain legacy = new LegacyChain(pool1); // 先建链（真实服务器类加载期），后停池
        pool1.shutdown(false);
        if (!pool1.join(10_000L)) { System.out.println("   pool1 未终止"); return 1; }
        final AtomicBoolean legacyRan = new AtomicBoolean();
        final CompletableFuture<Void> legacyNode = legacy.enqueueWrite(p, () -> legacyRan.set(true));
        sleep(300);
        System.out.printf("   legacy : done=%b exceptional=%b ioTask.ran=%b%n",
            legacyNode.isDone(), legacyNode.isCompletedExceptionally(), legacyRan.get());
        if (legacyRan.get() || !legacyNode.isCompletedExceptionally()) {
            System.out.println("   [前提变化] legacy 基线与探针事实不符"); bad++;
        }

        final BalancedPrioritisedThreadPool pool2 = newPool();
        final FixedChain fixed = new FixedChain(pool2);
        pool2.shutdown(false);
        pool2.join(10_000L);
        final AtomicBoolean fixedRan = new AtomicBoolean();
        final CompletableFuture<Void> fixedNode = fixed.enqueueWrite(p, () -> fixedRan.set(true));
        sleep(300);
        System.out.printf("   fixed  : done=%b exceptional=%b ioTask.ran=%b pending=%d%n",
            fixedNode.isDone(), fixedNode.isCompletedExceptionally(), fixedRan.get(), fixed.pending.get());
        if (!fixedRan.get() || !fixedNode.isDone() || fixedNode.isCompletedExceptionally() || fixed.pending.get() != 0) {
            System.out.println("   FAIL: fixed 写未内联降级/未清理"); bad++;
        } else {
            System.out.println("   PASS");
        }
        return bad;
    }

    /** G2: 终止池上的读——legacy result future 悬挂，fixed 同步完成且值正确。 */
    private static int g2TerminatedRead() {
        System.out.println("-- G2 终止池·读 --");
        int bad = 0;
        final Path p = Path.of("g2.dat");

        final BalancedPrioritisedThreadPool pool1 = newPool();
        final LegacyChain legacy = new LegacyChain(pool1);
        pool1.shutdown(false);
        pool1.join(10_000L);
        final CompletableFuture<String> legacyNode = legacy.enqueueRead(p, () -> "value");
        sleep(500);
        System.out.printf("   legacy : resultFuture.done=%b (预期 false=悬挂)%n", legacyNode.isDone());
        if (legacyNode.isDone()) { System.out.println("   [前提变化] legacy 读未悬挂"); bad++; }

        final BalancedPrioritisedThreadPool pool2 = newPool();
        final FixedChain fixed = new FixedChain(pool2);
        pool2.shutdown(false);
        pool2.join(10_000L);
        final CompletableFuture<String> fixedNode = fixed.enqueueRead(p, () -> "value");
        sleep(300);
        final String v = fixedNode.isDone() && !fixedNode.isCompletedExceptionally() ? fixedNode.join() : null;
        System.out.printf("   fixed  : done=%b exceptional=%b value=%s%n",
            fixedNode.isDone(), fixedNode.isCompletedExceptionally(), v);
        if (!"value".equals(v)) { System.out.println("   FAIL: fixed 读未同步降级"); bad++; }
        else { System.out.println("   PASS"); }
        return bad;
    }

    /** G3: 排空池（isActive=true 的真实竞态窗口）上的写。 */
    private static int g3DrainingWrite() throws Exception {
        System.out.println("-- G3 排空池·写（isActive=true 窗口）--");
        int bad = 0;
        final Path p = Path.of("g3.dat");

        final BalancedPrioritisedThreadPool pool1 = newPool();
        final LegacyChain legacy = new LegacyChain(pool1);
        final CountDownLatch blocker1 = new CountDownLatch(1);
        legacy.queue.queueTask(() -> await(blocker1), Priority.BLOCKING);
        pool1.shutdown(false);
        System.out.println("   isActive(drain)=" + legacy.queue.isActive());
        final AtomicBoolean legacyRan = new AtomicBoolean();
        CompletableFuture<Void> legacyNode = null;
        try {
            legacyNode = legacy.enqueueWrite(p, () -> legacyRan.set(true));
        } catch (final Throwable t) {
            System.out.println("   legacy : enqueue 直接抛出 " + t.getClass().getSimpleName());
        }
        blocker1.countDown();
        pool1.join(10_000L);
        sleep(300);
        System.out.printf("   legacy : nodeDone=%b ioTask.ran=%b%n",
            legacyNode != null && legacyNode.isDone(), legacyRan.get());
        if (legacyRan.get()) { System.out.println("   [前提变化] legacy 排空窗口未丢任务"); bad++; }

        final BalancedPrioritisedThreadPool pool2 = newPool();
        final FixedChain fixed = new FixedChain(pool2);
        final CountDownLatch blocker2 = new CountDownLatch(1);
        fixed.queue.queueTask(() -> await(blocker2), Priority.BLOCKING);
        pool2.shutdown(false);
        final AtomicBoolean fixedRan = new AtomicBoolean();
        final CompletableFuture<Void> fixedNode = fixed.enqueueWrite(p, () -> fixedRan.set(true));
        blocker2.countDown();
        pool2.join(10_000L);
        sleep(300);
        System.out.printf("   fixed  : nodeDone=%b exceptional=%b ioTask.ran=%b pending=%d%n",
            fixedNode.isDone(), fixedNode.isCompletedExceptionally(), fixedRan.get(), fixed.pending.get());
        if (!fixedRan.get() || !fixedNode.isDone() || fixedNode.isCompletedExceptionally() || fixed.pending.get() != 0) {
            System.out.println("   FAIL: fixed 排空窗口未降级"); bad++;
        } else {
            System.out.println("   PASS");
        }
        return bad;
    }

    /** G4: 窗口命中后同 target 再提交——legacy 链悬挂（poison），fixed 正常。 */
    private static int g4ChainAfterWindow() {
        System.out.println("-- G4 窗口后同路径再提交 --");
        int bad = 0;
        final Path p = Path.of("g4.dat");

        final BalancedPrioritisedThreadPool pool1 = newPool();
        final LegacyChain legacy = new LegacyChain(pool1);
        pool1.shutdown(false);
        pool1.join(10_000L);
        legacy.enqueueWrite(p, () -> { }); // 窗口命中：异常完成
        final AtomicBoolean legacySecondRan = new AtomicBoolean();
        final CompletableFuture<Void> legacySecond = legacy.enqueueWrite(p, () -> legacySecondRan.set(true));
        sleep(300);
        System.out.printf("   legacy : second.done=%b second.ran=%b tailsEmpty=%b%n",
            legacySecond.isDone(), legacySecondRan.get(), legacy.tails.isEmpty());
        // 注：异常完成的首节点被 whenComplete 清理，second 也会以 ISE 异常完成——同样丢任务
        if (legacySecondRan.get()) { System.out.println("   [前提变化]"); bad++; }

        final BalancedPrioritisedThreadPool pool2 = newPool();
        final FixedChain fixed = new FixedChain(pool2);
        pool2.shutdown(false);
        pool2.join(10_000L);
        fixed.enqueueWrite(p, () -> { }); // 窗口命中：内联降级，链健康
        final AtomicBoolean fixedSecondRan = new AtomicBoolean();
        final CompletableFuture<Void> fixedSecond = fixed.enqueueWrite(p, () -> fixedSecondRan.set(true));
        sleep(300);
        System.out.printf("   fixed  : second.done=%b second.ran=%b tailsEmpty=%b pending=%d%n",
            fixedSecond.isDone(), fixedSecondRan.get(), fixed.tails.isEmpty(), fixed.pending.get());
        if (!fixedSecondRan.get() || !fixedSecond.isDone() || fixed.pending.get() != 0) {
            System.out.println("   FAIL: fixed 窗口后链受损"); bad++;
        } else {
            System.out.println("   PASS");
        }
        return bad;
    }

    /** G5: awaitPending 复刻的中断卫生——legacy 吞中断，fixed 恢复标志。 */
    private static int g5InterruptHygiene() throws Exception {
        System.out.println("-- G5 awaitPending 中断卫生 --");
        final CompletableFuture<Void> neverDone = new CompletableFuture<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean legacyFlag = new AtomicBoolean();
        final Thread legacyThread = new Thread(() -> {
            try {
                neverDone.get(60_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                // legacy：吞掉不恢复（修复前代码路径）
            } catch (final Exception ignored) {
            }
            entered.countDown();
            legacyFlag.set(Thread.currentThread().isInterrupted());
        }, "g5-legacy");
        legacyThread.start();
        sleep(100);
        legacyThread.interrupt();
        legacyThread.join(5_000);
        // 中断后标志被 get 清除且未恢复 → legacyFlag=false（演示丢失）
        System.out.println("   legacy : interrupt flag preserved=" + legacyFlag.get() + "（预期 false=丢失）");

        final AtomicBoolean fixedFlag = new AtomicBoolean();
        final Thread fixedThread = new Thread(() -> {
            try {
                neverDone.get(60_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt(); // fixed：恢复（修复后代码路径）
            } catch (final Exception ignored) {
            }
            entered.countDown();
            fixedFlag.set(Thread.currentThread().isInterrupted());
        }, "g5-fixed");
        fixedThread.start();
        sleep(100);
        fixedThread.interrupt();
        fixedThread.join(5_000);
        System.out.println("   fixed  : interrupt flag preserved=" + fixedFlag.get() + "（预期 true=恢复）");
        if (!fixedFlag.get()) {
            System.out.println("   FAIL: fixed 未恢复中断标志");
            return 1;
        }
        System.out.println("   PASS");
        return 0;
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
