package papo.bench;

import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 批次99：LivingEntity.pushEntities 有界早停扫描（papoGetEntitiesBounded）vs 无界填充。
 *
 * 模型：k 个 section 数组（模拟 ChunkEntitySlices.BasicEntityList.storage），每 section n 个
 * 候选（可 pushable 过滤、可 passenger 标记），消费者两端：
 * (a) push 循环——按序最多 MEC 个；(b) cramming——size>i-1 布尔 + 随机门 + 非乘骑计数>i-1 布尔。
 *
 * 等价自检（main）：随机配置（MEC∈{0,8,100}, i∈{0,24}, 密度, 乘骑率, 多 section）×20000 次，
 * 对拍 push 序列 / cramming 布尔 / nextInt 消耗奇偶——三项必须逐位一致。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class PushScanBench {

    // --- 模型实体：id + passenger 标志（pushable 已由过滤步建模为"是否入选"）---
    private static final int SEC = 8;          // section 数（16 块高全列）
    private static final int PER_SEC = 20;     // 每 section 候选密度（160 聚堆口径）

    private int[][] sections = new int[SEC][]; // 低 16 位=命中序号高位编码省略：直接存 passenger 位
    private boolean[][] passengers = new boolean[SEC][];
    private int mec = 8;
    private int cramming = 24;

    @Setup
    public void setup() {
        final Random r = new Random(99);
        for (int s = 0; s < SEC; s++) {
            final int[] sec = new int[PER_SEC];
            final boolean[] pas = new boolean[PER_SEC];
            for (int j = 0; j < PER_SEC; j++) {
                sec[j] = s * PER_SEC + j;   // 全部可 pushable（最坏情形：无过滤剪枝）
                pas[j] = false;
            }
            sections[s] = sec;
            passengers[s] = pas;
        }
    }

    // ---- before：无界填充 + 消费者 ----
    public final List<Integer> sink = new ArrayList<>(256);

    void fillUnbounded(final List<Integer> into) {
        for (int s = 0; s < SEC; s++) {
            final int[] sec = sections[s];
            for (int j = 0; j < sec.length; j++) {
                into.add(sec[j]);
            }
        }
    }

    int fillBounded(final List<Integer> into, final int listTarget, final int npTarget) {
        int np = 0;
        outer:
        for (int s = 0; s < SEC; s++) {
            final int[] sec = sections[s];
            final boolean[] pas = passengers[s];
            for (int j = 0; j < sec.length; j++) {
                into.add(sec[j]);
                if (!pas[j]) {
                    ++np;
                }
                if (into.size() >= listTarget && np >= npTarget) {
                    break outer;
                }
            }
        }
        return np;
    }

    // 消费者模型：返回 [pushedCount, crammingFired, randomConsumed]
    static int[] consume(final List<Integer> list, final boolean[] pas, final int mec, final int i, final Random rnd) {
        int pushed = 0;
        // push 循环（numCollisions 起点为 0 的常态路径）
        for (int k = 0; k < list.size() && pushed < mec; k++) {
            pushed++;
        }
        int randomConsumed = 0;
        int crammingFired = 0;
        if (i > 0 && list.size() > i - 1) {
            randomConsumed = 1;
            if (rnd.nextInt(4) == 0) {
                int np = 0;
                for (int k = 0; k < list.size(); k++) {
                    if (!pas[list.get(k)]) {
                        np++;
                    }
                }
                if (np > i - 1) {
                    crammingFired = 1;
                }
            }
        }
        return new int[]{pushed, crammingFired, randomConsumed};
    }

    @Benchmark
    public int[] beforeUnbounded() {
        sink.clear();
        fillUnbounded(sink);
        return consume(sink, FLAT_FALSE, mec, cramming, RND);
    }

    @Benchmark
    public int[] afterBounded() {
        sink.clear();
        fillBounded(sink, Math.max(mec, cramming), cramming > 0 ? cramming : 0);
        return consume(sink, FLAT_FALSE, mec, cramming, RND);
    }

    private static final boolean[] FLAT_FALSE = new boolean[SEC * PER_SEC];
    private static final Random RND = new Random(7);

    // ---- 等价自检 ----
    public static void main(final String[] args) {
        final Random r = new Random(1234);
        int cases = 0;
        for (int t = 0; t < 20000; t++) {
            final int secN = 1 + r.nextInt(6);
            final int per = r.nextInt(40);
            final boolean[] pas = new boolean[secN * per];
            int[][] secs = new int[secN][];
            boolean[][] pasSec = new boolean[secN][];
            int idx = 0;
            for (int s = 0; s < secN; s++) {
                secs[s] = new int[per];
                pasSec[s] = new boolean[per];
                for (int j = 0; j < per; j++) {
                    secs[s][j] = idx;
                    pasSec[s][j] = r.nextInt(100) < r.nextInt(60); // 乘骑率 0~60%
                    pas[idx] = pasSec[s][j];
                    idx++;
                }
            }
            final int mec = r.nextBoolean() ? 8 : (r.nextBoolean() ? 0 : 1 + r.nextInt(100));
            final int i = r.nextBoolean() ? 24 : (r.nextBoolean() ? 0 : 1 + r.nextInt(30));

            // before
            final List<Integer> full = new ArrayList<>();
            for (int s = 0; s < secN; s++) {
                for (int j = 0; j < per; j++) {
                    full.add(secs[s][j]);
                }
            }
            // after（与服务器实现同构的停止条件；跨 section np 累计在闭包里简化为单层展开）
            final List<Integer> bounded = new ArrayList<>();
            int np = 0;
            final int listTarget = Math.max(mec, i);
            final int npTarget = i > 0 ? i : 0;
            outer:
            for (int s = 0; s < secN; s++) {
                for (int j = 0; j < per; j++) {
                    bounded.add(secs[s][j]);
                    if (!pasSec[s][j]) {
                        ++np;
                    }
                    if (bounded.size() >= listTarget && np >= npTarget) {
                        break outer;
                    }
                }
            }

            // 消费者对拍：before/after 各持同种子独立随机流（对齐"同一随机输入"——真实服务端的
            // nextInt 取自实体自身序列，对拍的是行为对相同抽取的响应，而非共享流的连续抽取）
            final int[] a = consumeRef(full, pas, mec, i, new Random(555 + t));
            final int[] b = consumeRef(bounded, pas, mec, i, new Random(555 + t));
            if (!Arrays.equals(a, b)) {
                throw new IllegalStateException("DIVERGENCE case=" + t + " before=" + Arrays.toString(a)
                    + " after=" + Arrays.toString(b) + " mec=" + mec + " i=" + i + " per=" + per + " secN=" + secN);
            }
            // push 序列本身（不只是数量）
            for (int k = 0; k < Math.min(mec, Integer.MAX_VALUE); k++) {
                final int va = k < full.size() ? full.get(k) : -1;
                final int vb = k < bounded.size() ? bounded.get(k) : -1;
                if (k < mec && va != vb) {
                    throw new IllegalStateException("PUSH ORDER DIVERGENCE case=" + t + " k=" + k);
                }
                if (k >= mec) {
                    break;
                }
            }
            cases++;
        }
        System.out.println("PushScanBench equivalence self-check: " + cases + " random configs PASS"
            + " (push sequence, cramming boolean, random-consumption parity)");
    }

    static int[] consumeRef(final List<Integer> list, final boolean[] pas, final int mec, final int i, final Random rnd) {
        int pushed = 0;
        for (int k = 0; k < list.size(); k++) {
            if (pushed >= mec) {
                break;
            }
            pushed++;
        }
        int randomConsumed = 0;
        int crammingFired = 0;
        if (i > 0 && list.size() > i - 1) {
            randomConsumed = 1;
            if (rnd.nextInt(4) == 0) {
                int np = 0;
                for (int k = 0; k < list.size(); k++) {
                    if (!pas[list.get(k)]) {
                        np++;
                    }
                }
                if (np > i - 1) {
                    crammingFired = 1;
                }
            }
        }
        return new int[]{pushed, crammingFired, randomConsumed};
    }
}
