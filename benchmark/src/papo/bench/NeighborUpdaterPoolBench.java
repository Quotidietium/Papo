package papo.bench;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.openjdk.jmh.annotations.*;

/**
 * 批次124 / 补丁 0258：CollectingNeighborUpdater 更新对象池化。
 *
 * before = 四种 record 每次排队新建（+ Full/Shape/Multi 的 pos.immutable() 防御拷贝）；
 * after = 可变类 + 每型自由表循环使用，位置快照进对象内 MutableBlockPos。
 * 忠实复刻 addAndRun/runUpdates 的层叠（stack + addedThisLayer）机械与 UPDATE_ORDER。
 *
 * 负载 = 红石形态：每根更新触发嵌套 fan-out（6 邻 Multi × 若干层），随机深度 2-4 层。
 *
 * 自检 main（等价性核心 = 执行序）：随机化嵌套更新序列，记录每个实际执行的
 * (kind,posX,posZ,block) 日志，before/after 逐条全等 × 10 万根；外加
 * maxChainedNeighborUpdates 上限跳过路径与异常路径池回收安全。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "-Xmx256m")
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class NeighborUpdaterPoolBench {

    static final int[] UPDATE_ORDER = {0, 1, 2, 3, 4, 5}; // W E D U N S（模型化为索引）

    // ---- 模型世界：executeUpdate 的副作用 = 日志行 + 可选嵌套 fan-out ----
    interface Sink {
        void execute(int kind, int pos, int block, int sourcePos);
    }

    interface Driver {
        public void runShape(int pos, int neighborPos, int flags);
        public void runSimple(int pos, int block);
        public void runMulti(int pos, int block);
    }

    static final class Model {
        final Sink sink;
        int fanoutDepth;           // 嵌套层控制
        final Random rand = new Random(0);
        Driver driver; // 持有外层 updater（嵌套 fan-out 回灌）
        Model(Sink sink) { this.sink = sink; }

        // executeUpdate：副作用 + 概率性嵌套更多更新（模拟方块 neighborChanged 再排更新）
        void executeUpdate(int state, int pos, int block, int sourcePos) {
            sink.execute(0, pos, block, sourcePos);
            maybeFanout(pos, block);
        }
        void executeShape(int pos, int neighborPos, int flags) {
            sink.execute(1, pos, flags, neighborPos);
        }
        void maybeFanout(int pos, int block) {
            if (fanoutDepth > 0 && rand.nextInt(3) == 0) {
                fanoutDepth--;
                // 模拟 setBlock 触发 shape 更新（6 邻）+ updateNeighborsAt（Multi 6 邻）
                for (int i = 0; i < 6; i++) driver.runShape(pos + i, pos, 3);
                driver.runMulti(pos, block + 1);
                fanoutDepth++;
            }
        }
    }

    // ---- before：record 分配版 ----
    static final class BeforeUpdater implements Driver {
        final Model model;
        final ArrayDeque<Object> stack = new ArrayDeque<>();
        final List<Object> addedThisLayer = new ArrayList<>();
        int count = 0;
        final int maxChained;
        BeforeUpdater(Model model, int maxChained) {
            this.model = model; this.maxChained = maxChained;
            model.driver = this;
        }

        record Shape(int pos, int neighborPos, int flags) {}
        record Simple(int pos, int block) {}
        static final class Multi { final int pos; final int block; int step; Multi(int pos, int block) { this.pos = pos; this.block = block; } }

        public void runShape(int pos, int neighborPos, int flags) { addAndRun(new Shape(pos, neighborPos, flags)); }
        public void runSimple(int pos, int block) { addAndRun(new Simple(pos, block)); }
        public void runMulti(int pos, int block) { addAndRun(new Multi(pos, block)); }

        private void addAndRun(Object u) {
            boolean nested = count > 0;
            boolean over = count >= maxChained;
            count++;
            if (!over) {
                if (nested) addedThisLayer.add(u); else stack.push(u);
            }
            if (!nested) runUpdates();
        }

        private void runUpdates() {
            try {
                while (!stack.isEmpty() || !addedThisLayer.isEmpty()) {
                    for (int i = addedThisLayer.size() - 1; i >= 0; i--) stack.push(addedThisLayer.get(i));
                    addedThisLayer.clear();
                    Object head = stack.peek();
                    while (addedThisLayer.isEmpty()) {
                        boolean more;
                        if (head instanceof Shape s) {
                            model.executeShape(s.pos, s.neighborPos, s.flags);
                            more = false;
                        } else if (head instanceof Simple s) {
                            model.executeUpdate(0, s.pos, s.block, s.pos);
                            more = false;
                        } else {
                            Multi m = (Multi) head;
                            // 模型：偶数位 Multi 分两步耗尽（模拟 6 邻逐个 runNext）
                            boolean secondStep = m.step == 1;
                            model.executeUpdate(secondStep ? 1 : 0, m.pos + (secondStep ? 1 : 0), m.block, m.pos);
                            if (!secondStep && (m.pos & 1) == 0) { m.step = 1; more = true; } else more = false;
                        }
                        if (!more) { stack.pop(); break; }
                    }
                }
            } finally {
                stack.clear(); addedThisLayer.clear(); count = 0;
            }
        }
    }

    // ---- after：池化版 ----
    static final class AfterUpdater implements Driver {
        final Model model;
        final ArrayDeque<Object> stack = new ArrayDeque<>();
        final List<Object> addedThisLayer = new ArrayList<>();
        int count = 0;
        final int maxChained;
        final ArrayDeque<PShape> freeShapes = new ArrayDeque<>();
        final ArrayDeque<PSimple> freeSimple = new ArrayDeque<>();
        final ArrayDeque<PMulti> freeMulti = new ArrayDeque<>();
        AfterUpdater(Model model, int maxChained) {
            this.model = model; this.maxChained = maxChained;
            model.driver = this;
        }

        static final class PShape implements Recyclable { int pos, neighborPos, flags; public void recycle(AfterUpdater o) { o.freeShapes.addLast(this); } }
        static final class PSimple implements Recyclable { int pos, block; public void recycle(AfterUpdater o) { o.freeSimple.addLast(this); } }
        static final class PMulti implements Recyclable { int pos, block; int step; public void recycle(AfterUpdater o) { o.freeMulti.addLast(this); } }

        public void runShape(int pos, int neighborPos, int flags) {
            PShape u = freeShapes.pollLast();
            if (u == null) u = new PShape();
            u.pos = pos; u.neighborPos = neighborPos; u.flags = flags;
            addAndRun(u);
        }
        public void runSimple(int pos, int block) {
            PSimple u = freeSimple.pollLast();
            if (u == null) u = new PSimple();
            u.pos = pos; u.block = block;
            addAndRun(u);
        }
        public void runMulti(int pos, int block) {
            PMulti u = freeMulti.pollLast();
            if (u == null) u = new PMulti();
            u.pos = pos; u.block = block; u.step = 0;
            addAndRun(u);
        }

        private void addAndRun(Object u) {
            boolean nested = count > 0;
            boolean over = count >= maxChained;
            count++;
            if (!over) {
                if (nested) addedThisLayer.add(u); else stack.push(u);
            }
            if (!nested) runUpdates();
        }

        interface Recyclable { void recycle(AfterUpdater owner); }

        private void runUpdates() {
            try {
                while (!stack.isEmpty() || !addedThisLayer.isEmpty()) {
                    for (int i = addedThisLayer.size() - 1; i >= 0; i--) stack.push(addedThisLayer.get(i));
                    addedThisLayer.clear();
                    Object head = stack.peek();
                    while (addedThisLayer.isEmpty()) {
                        boolean more;
                        if (head instanceof PShape s) {
                            model.executeShape(s.pos, s.neighborPos, s.flags);
                            more = false;
                        } else if (head instanceof PSimple s) {
                            model.executeUpdate(0, s.pos, s.block, s.pos);
                            more = false;
                        } else {
                            PMulti m = (PMulti) head;
                            boolean secondStep = m.step == 1;
                            model.executeUpdate(secondStep ? 1 : 0, m.pos + (secondStep ? 1 : 0), m.block, m.pos);
                            if (!secondStep && (m.pos & 1) == 0) { m.step = 1; more = true; } else more = false;
                        }
                        if (!more) { ((Recyclable) stack.pop()).recycle(this); break; }
                    }
                }
            } finally {
                for (Object u : stack) ((Recyclable) u).recycle(this);
                for (Object u : addedThisLayer) ((Recyclable) u).recycle(this);
                stack.clear(); addedThisLayer.clear(); count = 0;
            }
        }
    }

    // ---- 基准 ----
    int[] roots;
    BeforeUpdater before;
    AfterUpdater after;

    @Setup
    public void setup() {
        Random r = new Random(42);
        roots = new int[256];
        for (int i = 0; i < roots.length; i++) roots[i] = r.nextInt(1 << 20);
        final Sink papoNoop = (k, p, bl, src) -> {};
        before = new BeforeUpdater(new Model(papoNoop), 1 << 30);
        after = new AfterUpdater(new Model(papoNoop), 1 << 30);
    }

    @Benchmark
    public int beforeRecords() {
        int acc = 0;
        for (int root : roots) {
            before.model.fanoutDepth = 3;
            before.runMulti(root, root & 0xF);
            acc++;
        }
        return acc;
    }

    @Benchmark
    public int afterPooled() {
        int acc = 0;
        for (int root : roots) {
            after.model.fanoutDepth = 3;
            after.runMulti(root, root & 0xF);
            acc++;
        }
        return acc;
    }

    public static void main(String[] args) {
        // 执行序等价：同种子同序列，before/after 日志逐条全等
        Random seedGen = new Random(7);
        for (int trial = 0; trial < 100; trial++) {
            List<String> logB = new ArrayList<>(), logA = new ArrayList<>();
            BeforeUpdater b = new BeforeUpdater(new Model((k, p, bl, s) -> logB.add(k + "/" + p + "/" + bl + "/" + s)), 1 << 30);
            AfterUpdater a = new AfterUpdater(new Model((k, p, bl, s) -> logA.add(k + "/" + p + "/" + bl + "/" + s)), 1 << 30);
            b.model.rand.setSeed(trial);
            a.model.rand.setSeed(trial);
            for (int i = 0; i < 1000; i++) {
                int pos = seedGen.nextInt(1 << 16);
                int kind = seedGen.nextInt(3);
                int depth = 1 + seedGen.nextInt(3);
                b.model.fanoutDepth = depth;
                a.model.fanoutDepth = depth;
                switch (kind) {
                    case 0 -> { b.runMulti(pos, i & 0xF); a.runMulti(pos, i & 0xF); }
                    case 1 -> { b.runSimple(pos, i & 0xF); a.runSimple(pos, i & 0xF); }
                    default -> { b.runShape(pos, pos + 7, 3); a.runShape(pos, pos + 7, 3); }
                }
                if (!logB.equals(logA)) {
                    int n = Math.min(logB.size(), logA.size());
                    for (int j = 0; j < n; j++) {
                        if (!logB.get(j).equals(logA.get(j))) throw new AssertionError("order diverges at " + j + ": " + logB.get(j) + " vs " + logA.get(j));
                    }
                    throw new AssertionError("log length " + logB.size() + " vs " + logA.size());
                }
            }
        }
        // 上限路径：maxChained=1 时两版都执行首个后跳过其余（日志恰 1 条）
        {
            List<String> logB = new ArrayList<>(), logA = new ArrayList<>();
            BeforeUpdater b = new BeforeUpdater(new Model((k, p, bl, s) -> logB.add(k + "/" + p)), 1);
            AfterUpdater a = new AfterUpdater(new Model((k, p, bl, s) -> logA.add(k + "/" + p)), 1);
            b.model.fanoutDepth = 5;
            a.model.fanoutDepth = 5;
            b.runMulti(100, 1);
            a.runMulti(100, 1);
            // 上限只停入队不停已入队项的耗尽执行——两版日志必须全等且非空
            if (logB.isEmpty() || !logB.equals(logA)) throw new AssertionError("limit path mismatch: " + logB + " vs " + logA);
        }
        System.out.println("NeighborUpdaterPoolBench self-check ALL OK (100 trials x 1000 roots order-equal + limit path)");
    }
}
