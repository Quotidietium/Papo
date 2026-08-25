package io.papermc.paper.util;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;

// Papo start - ordered off-thread file writes for player data (batch 79)
/**
 * Moves gzip/file-IO of player data saves (.dat / stats json / advancements json) off the
 * main thread onto the moonrise IO pool, preserving two invariants:
 *
 * 1. Per-target ordering: writes to the same file are chained (CompletableFuture per
 *    target), so a stale snapshot can never overwrite a newer one even though saves for
 *    one player can now overlap in time with saves of other players.
 * 2. Read-after-write visibility: load paths call {@link #awaitPending(Path)} so a quick
 *    re-join (or any read) always observes the latest enqueued save - exactly what the
 *    synchronous vanilla code guaranteed.
 *
 * Callers keep building the payload snapshot on their own thread (player NBT tree /
 * stats JsonObject / advancements JsonElement are freshly built per save and passed
 * through defensively-copied); only byte-writing work runs in the task.
 *
 * Full-save paths call {@link #awaitAll(long)} after scheduling, preserving the vanilla
 * "/save-all and shutdown complete their writes" contract. Incremental autosave is
 * fire-and-forget. Shutdown is additionally covered by MoonriseCommon.haltExecutors'
 * 60s IO pool drain (dedicated server).
 */
public final class PapoOrderedFileWrites {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    // Plain queueTask on a dedicated ordered-stream-group queue; the per-target chain below
    // (not the pool) provides serialisation, so any scheduling discipline is correct.
    private static final BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue QUEUE =
        MoonriseCommon.IO_POOL.createOrderedStreamGroup().createExecutor();

    private static final ConcurrentHashMap<Path, CompletableFuture<Void>> TAILS = new ConcurrentHashMap<>();

    // Await-all bookkeeping (monitor pattern; contention is irrelevant at save frequency).
    private static final Object LOCK = new Object();
    private static int pending;

    private PapoOrderedFileWrites() {}

    /**
     * Chains {@code ioTask} after any previously enqueued task for {@code target} and runs
     * it on the IO pool. If the pool is already halted (shutdown tail-end), runs the task
     * synchronously so durability never silently degrades.
     */
    public static void enqueue(final Path target, final Runnable ioTask) {
        if (!QUEUE.isActive()) {
            ioTask.run();
            return;
        }
        incrementPending();
        final CompletableFuture<Void> node = TAILS.compute(target, (path, prev) ->
            (prev == null ? CompletableFuture.<Void>completedFuture(null) : prev)
                // A failed predecessor must not drop later saves for this file: tasks catch
                // their own exceptions vanilla-style, this handle() is belt-and-braces.
                .handle((result, throwable) -> null)
                .thenRunAsync(ioTask, QUEUE::queueTask));
        node.whenComplete((result, throwable) -> {
            TAILS.remove(target, node); // keep the entry if a newer tail has since chained on
            decrementPending();
        });
    }

    /**
     * Blocks the caller (main thread at join/load) until all enqueued writes for
     * {@code target} have completed. Bounded; fast-path returns immediately when the
     * chain is empty.
     */
    public static void awaitPending(final Path target) {
        CompletableFuture<Void> tail;
        while ((tail = TAILS.get(target)) != null) {
            try {
                tail.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException ignored) {
                return; // give up reading rather than hang the main thread
            } catch (final Exception ignored) {
                return; // exceptional completion is already logged inside the task
            }
        }
    }

    /**
     * Blocks until every enqueued write has completed (full-save semantics: /save-all,
     * shutdown, emergency save). Bounded by {@code timeoutMs}.
     */
    public static void awaitAll(final long timeoutMs) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (LOCK) {
            long remaining;
            while (pending > 0
                && (remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())) > 0) {
                try {
                    LOCK.wait(Math.max(1L, remaining));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void incrementPending() {
        synchronized (LOCK) {
            pending++;
        }
    }

    private static void decrementPending() {
        synchronized (LOCK) {
            if (--pending == 0) {
                LOCK.notifyAll();
            }
        }
    }
}
// Papo end - ordered off-thread file writes for player data (batch 79)
