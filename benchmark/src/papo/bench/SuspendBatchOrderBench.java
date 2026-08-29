package papo.bench;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 批次113：0260 挂起窗发送批量化——线上序 FIFO 等价性自检（正确性证明，非性能）。
 *
 * 用户报告"箱子菜单等交互闪回"，乱序假说的硬闭合：忠实复刻 Connection.send 的三
 * 路径（批量 append / 直发+守卫排水 / 入队+守卫排水 / flushChannel 排水+flush），
 * 在 FIFO 执行器模型（netty NioEventLoop.execute 的上游保证 = MPSC 严格 FIFO 单
 * 消费者；EmbeddedChannel 实测 4.2 存在非 FIFO 交错 quirk，不能作模型——批次113
 * 判例）上随机交错主线程操作序列（万级种子），断言 write 执行序 === 发出序。
 *
 * 披露例外（上游语义，vanilla 同构）：异步白名单发送可越序（pong 类与追踪流量无
 * 序契约）——不在主线程 FIFO 断言范围。
 *
 * 自检 main：10,000 随机序列 × 四种压力形态（纯批量/批量+直发/批量+入队/全混合
 * 含 flush），全部通过输出 ALL OK；任一失配打印首个分歧点。
 */
public final class SuspendBatchOrderBench {

    /** 忠实复刻 0260 的批量/守卫/排水逻辑（主线程视角；write 用递增序号记录）。 */
    static final class ModelConnection {
        final List<Integer> wireOrder = new ArrayList<>();
        // netty NioEventLoop.execute 的 FIFO 模型：MPSC 队列 + 单消费者按提交序执行
        final Deque<Runnable> eventLoop = new ArrayDeque<>();
        private Runnable[] batch = new Runnable[64];
        private int batchSize;
        final List<Integer> issued = new ArrayList<>(); // 期望线上序（发出序）
        final List<Path> issuedPaths = new ArrayList<>(); // 失败诊断用

        void send(final int seq, final Path path) {
            if (seq >= 0) {
                issued.add(seq);
                issuedPaths.add(path);
            }
            final Runnable write = () -> wireOrder.add(seq);
            switch (path) {
                case BATCHED -> appendSuspended(write);
                case DIRECT -> {
                    // 直发路径：守卫——批非空先排水，再直发
                    drainSuspendedBatch();
                    eventLoop.addLast(write);
                }
                case QUEUE -> {
                    // 入队路径（packet 未就绪/带 extras）：守卫排水后入 pendingActions，
                    // 由任务队列处理——模型化为 execute
                    drainSuspendedBatch();
                    eventLoop.addLast(write);
                }
                case FLUSH -> {
                    // flushChannel：排水 + flush（flush 不写消息，序断言无关）
                    drainSuspendedBatch();
                    eventLoop.addLast(() -> {});
                }
            }
        }

        private void appendSuspended(final Runnable write) {
            if (batchSize == batch.length) {
                batch = java.util.Arrays.copyOf(batch, batch.length * 2);
            }
            batch[batchSize++] = write;
            if (batchSize >= 256) {
                drainSuspendedBatch();
            }
        }

        private void drainSuspendedBatch() {
            if (batchSize == 0) {
                return;
            }
            final int n = batchSize;
            final Runnable[] drained = batch;
            batch = new Runnable[Math.max(64, batch.length)];
            batchSize = 0;
            eventLoop.addLast(() -> {
                for (int i = 0; i < n; ++i) {
                    drained[i].run();
                    drained[i] = null;
                }
            });
        }

        /** 按 FIFO 排空任务队列，返回 write 执行序。 */
        List<Integer> drainWire() {
            while (!eventLoop.isEmpty()) {
                eventLoop.pollFirst().run();
            }
            return new ArrayList<>(wireOrder);
        }
    }

    enum Path { BATCHED, DIRECT, QUEUE, FLUSH }

    private static boolean runSequence(final long seed, final int ops, final Random rng) {
        final ModelConnection conn = new ModelConnection();
        int seq = 0; // FLUSH 不占 write 序号（flush 只冲刷不写消息）
        for (int i = 0; i < ops; i++) {
            final int r = rng.nextInt(100);
            final Path p;
            if (seed % 4 == 0) {
                p = Path.BATCHED; // 纯批量（>256 触发阈值排水）
            } else if (seed % 4 == 1) {
                p = r < 70 ? Path.BATCHED : Path.DIRECT;
            } else if (seed % 4 == 2) {
                p = r < 70 ? Path.BATCHED : Path.QUEUE;
            } else {
                p = r < 55 ? Path.BATCHED : (r < 75 ? Path.DIRECT : (r < 90 ? Path.QUEUE : Path.FLUSH));
            }
            conn.send(p == Path.FLUSH ? -1 : seq++, p);
        }
        // 终局 flushChannel（真实 tick 尾必经）
        conn.send(-1, Path.FLUSH);
        final List<Integer> wire = conn.drainWire();
        if (!wire.equals(conn.issued)) {
            System.out.println("ORDER MISMATCH seed=" + seed + " mode=" + (seed % 4)
                + " wireSize=" + wire.size() + " issuedSize=" + conn.issued.size());
            System.out.println("  first ops paths: " + conn.issuedPaths.subList(0, Math.min(16, conn.issuedPaths.size())));
            System.out.println("  first ops issued: " + conn.issued.subList(0, Math.min(16, conn.issued.size())));
            System.out.println("  first ops wire:   " + wire.subList(0, Math.min(16, wire.size())));
            for (int i = 0; i < Math.min(wire.size(), conn.issued.size()); i++) {
                if (!wire.get(i).equals(conn.issued.get(i))) {
                    System.out.println("  first divergence at " + i + ": wire=" + wire.get(i)
                        + " issued=" + conn.issued.get(i));
                    break;
                }
            }
            return false;
        }
        return true;
    }

    public static void main(final String[] args) {
        final int sequences = 10_000;
        final Random rng = new Random(113);
        for (long seed = 0; seed < sequences; seed++) {
            final int ops = 1 + rng.nextInt(600);
            if (!runSequence(seed, ops, rng)) {
                System.out.println("FAILED at seed " + seed);
                System.exit(1);
            }
        }
        System.out.println("ALL OK: " + sequences + " random interleaves, wire order == issue order "
            + "(modes: pure-batched/batch+direct/batch+queue/full-mix-with-flush)");
    }
}
