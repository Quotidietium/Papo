package papo.bench;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.util.Priority;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批次91：concurrentutil OrderedStreamGroup.Queue 在池 shutdown/halt 之后的语义实证。
 *
 * 背景：PapoOrderedFileWrites.enqueue/enqueueRead 在调用 queueTask 前只做一次
 * QUEUE.isActive() 检查。authenticator 线程（登录读预取）与主线程 stopServer 尾部的
 * MoonriseCommon.haltExecutors() 并发，存在 check-then-act 窗口。本探针回答窗口命中的
 * 确切后果，不猜测：
 *
 *   Q1 shutdown(false) 后（尚未 join 完）isActive? queueTask 新任务运行吗?
 *   Q2 shutdown(false)+join 完成（池终止）后 queueTask：返回值 / 是否运行 / 是否抛异常?
 *   Q3 halt(false)（强制）后 queueTask 同上;
 *   Q4 halt(false) 对已入队未运行任务：丢弃还是执行?
 *   Q5 复刻链路：池终止后经 thenRunAsync(QUEUE::queueTask) 提交的 future 是否永久不完成
 *      （= per-target 链 poisoned，后续同路径 enqueue 全部悬挂）?
 *
 * 非 JMH，java 直接运行，输出 PASS/FAIL 判定行。
 */
public final class HaltSemanticsProbe {

    private static final long HOLD = 25_000_000L; // MoonriseCommon.IO_QUEUE_HOLD_TIME

    public static void main(final String[] args) throws Exception {
        q1ShutdownDraining();
        q2TerminatedQueueTask();
        q3HaltedQueueTask();
        q4HaltDropsQueued();
        q5ReplicaChainPoison();
        q6OneArgOverload();
        System.out.println();
        System.out.println("PROBE DONE (见上方各行事实)");
    }

    private static BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue freshPool(final Object[] out) {
        final BalancedPrioritisedThreadPool pool = new BalancedPrioritisedThreadPool(HOLD, t -> t.setDaemon(true));
        pool.adjustThreadCount(1);
        out[0] = pool;
        return pool.createOrderedStreamGroup().createExecutor();
    }

    /** Q1: graceful shutdown() 尚在排空时，新任务是否仍被接受执行。 */
    private static void q1ShutdownDraining() throws Exception {
        System.out.println("-- Q1: shutdown(false) 排空中 --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        final CountDownLatch blocker = new CountDownLatch(1);
        queue.queueTask(() -> await(blocker), Priority.BLOCKING); // 占住唯一 IO 线程
        pool.shutdown(false); // graceful：等在队/在跑任务
        System.out.println("   isActive after shutdown(false)=" + queue.isActive());
        final AtomicBoolean ran = new AtomicBoolean();
        final Object ret = tryQueue(queue, () -> ran.set(true));
        System.out.println("   queueTask during drain: ret=" + ret + " ranEventu=" + ran.get());
        blocker.countDown(); // 放行排空
        final boolean joined = pool.join(10_000L);
        sleep(200);
        System.out.println("   joined=" + joined + " ranAfterDrain=" + ran.get());
    }

    /** Q2: 完全终止后 queueTask 的行为（返回值/运行/异常）。 */
    private static void q2TerminatedQueueTask() throws Exception {
        System.out.println("-- Q2: shutdown(false)+join 终止后 --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        pool.shutdown(false);
        final boolean joined = pool.join(10_000L);
        System.out.println("   joined=" + joined + " isActive=" + queue.isActive());
        final AtomicBoolean ran = new AtomicBoolean();
        final Object ret = tryQueue(queue, () -> ran.set(true));
        sleep(200);
        System.out.println("   queueTask: ret=" + ret + " ran=" + ran.get());
    }

    /** Q3: halt(false) 强制停后 queueTask 的行为。 */
    private static void q3HaltedQueueTask() throws Exception {
        System.out.println("-- Q3: halt(false) 后 --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        pool.halt(false);
        final boolean joined = pool.join(10_000L);
        System.out.println("   joined=" + joined + " isActive=" + queue.isActive());
        final AtomicBoolean ran = new AtomicBoolean();
        final Object ret = tryQueue(queue, () -> ran.set(true));
        sleep(200);
        System.out.println("   queueTask: ret=" + ret + " ran=" + ran.get());
    }

    /** Q4: halt 对已入队未运行任务的影响。 */
    private static void q4HaltDropsQueued() throws Exception {
        System.out.println("-- Q4: halt 对已排队任务 --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        final CountDownLatch blocker = new CountDownLatch(1);
        queue.queueTask(() -> await(blocker), Priority.BLOCKING); // 占住线程，第二个任务只入队
        final AtomicBoolean queuedRan = new AtomicBoolean();
        final Object queued = queue.queueTask(() -> queuedRan.set(true), Priority.NORMAL);
        System.out.println("   queued task handle=" + queued);
        pool.halt(false);
        blocker.countDown();
        pool.join(10_000L);
        sleep(200);
        System.out.println("   after halt+join: queuedRan=" + queuedRan.get());
    }

    /** Q5: 复刻 PapoOrderedFileWrites 写链——终止池上经 thenRunAsync(QUEUE::queueTask) 的节点是否永不完成。 */
    private static void q5ReplicaChainPoison() throws Exception {
        System.out.println("-- Q5: 复刻链路 poison --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        pool.shutdown(false);
        pool.join(10_000L); // 终止

        final AtomicBoolean ran = new AtomicBoolean();
        // 与 enqueue 完全同构：isActive 检查后的提交（这里直接越过检查模拟窗口命中）
        final CompletableFuture<Void> node = CompletableFuture.<Void>completedFuture(null)
            .handle((r, t) -> null)
            .thenRunAsync(() -> ran.set(true), queue::queueTask);
        sleep(500);
        System.out.println("   node.isDone=" + node.isDone() + " ioTask.ran=" + ran.get());
        // 后续同 target 的链节点（挂在 poisoned tail 上）
        final CompletableFuture<Void> next = node.handle((r, t) -> null).thenRunAsync(() -> { }, queue::queueTask);
        sleep(200);
        System.out.println("   chained-next.isDone=" + next.isDone());
    }

    /** Q6: 一元 queueTask(Runnable)（写路径 thenRunAsync 实际引用的形态）在终止池上的行为。 */
    private static void q6OneArgOverload() throws Exception {
        System.out.println("-- Q6: 一元 queueTask(Runnable) 终止池 --");
        final Object[] out = new Object[1];
        final var queue = freshPool(out);
        final BalancedPrioritisedThreadPool pool = (BalancedPrioritisedThreadPool) out[0];
        pool.shutdown(false);
        pool.join(10_000L);
        final AtomicBoolean ran = new AtomicBoolean();
        try {
            queue.queueTask(() -> ran.set(true));
            System.out.println("   queueTask(Runnable): returned normally");
        } catch (final Throwable t) {
            System.out.println("   queueTask(Runnable): THREW " + t);
        }
        sleep(200);
        System.out.println("   ran=" + ran.get());
    }

    private static Object tryQueue(final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue queue, final Runnable task) {
        try {
            return queue.queueTask(task, Priority.NORMAL);
        } catch (final Throwable t) {
            return "THREW: " + t;
        }
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
