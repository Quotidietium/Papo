package papo.bench;

import org.openjdk.jmh.annotations.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 批次101：tracker purge 探测 O(1) 化（TrackedChunk.papoContainsInViewDistance）。
 *
 * 模型：一个 chunk 的 VIEW_DISTANCE 玩家表（P 人），T 个 tracker 各持 seenBy（S 人），
 * updateCount 变化触发全部 tracker 的 purge。before：每个 (tracker × seenBy) 做
 * ReferenceList.contains（线性身份扫描）；after：每 (chunk, updateCount) 建一次
 * ReferenceOpenHashSet 语义的探测集（等价模型 HashSet<IdentityWrapper> 用 LongHashMap
 * 替代——自检部分直接用 identity 语义的数组对拍），此后 O(1) 探测。
 *
 * 等价自检（main）：随机玩家集/seenBy 子集/多次 updateCount 代际 ×5000，
 * 对拍每代每 tracker 的移除决策序列一致 + 代际失效（改集后重建生效）。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class PurgeProbeBench {

    private static final int PLAYERS = 160;   // chunk 视距表规模（160 聚堆）
    private static final int TRACKERS = 160;  // 每 chunk 的 tracker 数
    private static final int SEENBY = 160;    // 聚堆时 seenBy ≈ 全表

    private ServerPlayerM[] viewList = new ServerPlayerM[PLAYERS];
    private ServerPlayerM[][] seenBy = new ServerPlayerM[TRACKERS][];
    // after：共享探测集（identity 语义——对象唯一，HashSet 直接引用即可）
    private HashSet<ServerPlayerM> probeSet = new HashSet<>();

    static final class ServerPlayerM {
        final int id;
        ServerPlayerM(final int id) {
            this.id = id;
        }
    }

    @Setup
    public void setup() {
        final Random r = new Random(101);
        for (int i = 0; i < PLAYERS; i++) {
            viewList[i] = new ServerPlayerM(i);
        }
        for (int t = 0; t < TRACKERS; t++) {
            // 聚堆稳态：seenBy ⊆ viewList（绝大多数全量）
            final ServerPlayerM[] s = new ServerPlayerM[SEENBY];
            System.arraycopy(viewList, 0, s, 0, SEENBY);
            seenBy[t] = s;
        }
    }

    static boolean linearContains(final ServerPlayerM[] list, final int size, final ServerPlayerM p) {
        for (int i = 0; i < size; i++) {
            if (list[i] == p) {
                return true;
            }
        }
        return false;
    }

    /** before：全部 tracker 线性扫（一次 updateCount 变化的 purge 风暴）。返回移除总数。 */
    @Benchmark
    public int beforeLinearStorm() {
        int removed = 0;
        for (int t = 0; t < TRACKERS; t++) {
            final ServerPlayerM[] s = seenBy[t];
            for (int i = 0; i < s.length; i++) {
                if (!linearContains(viewList, PLAYERS, s[i])) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /** after：一次建集 + 全部 tracker O(1) 探测。返回移除总数。 */
    @Benchmark
    public int afterSetStorm() {
        probeSet.clear();
        for (int i = 0; i < PLAYERS; i++) {
            probeSet.add(viewList[i]);
        }
        int removed = 0;
        for (int t = 0; t < TRACKERS; t++) {
            final ServerPlayerM[] s = seenBy[t];
            for (int i = 0; i < s.length; i++) {
                if (!probeSet.contains(s[i])) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public static void main(final String[] args) {
        final Random r = new Random(4242);
        for (int iter = 0; iter < 5000; iter++) {
            final int p = 1 + r.nextInt(200);
            final ServerPlayerM[] world = new ServerPlayerM[400];
            final Map<Integer, ServerPlayerM> byId = new HashMap<>();
            for (int i = 0; i < 400; i++) {
                world[i] = new ServerPlayerM(i);
                byId.put(i, world[i]);
            }
            // 代际 1 视图集
            List<ServerPlayerM> view1 = new ArrayList<>();
            final Set<Integer> ids1 = new HashSet<>();
            for (int i = 0; i < p; i++) {
                ids1.add(i);
            }
            for (final int id : ids1) {
                view1.add(byId.get(id));
            }
            // tracker seenBy 随机子集（可能含代际外成员）
            final int trackers = 1 + r.nextInt(50);
            final List<List<ServerPlayerM>> seen = new ArrayList<>();
            for (int t = 0; t < trackers; t++) {
                final List<ServerPlayerM> s = new ArrayList<>();
                for (int i = 0; i < 400; i++) {
                    if (r.nextInt(100) < 40) {
                        s.add(world[i]);
                    }
                }
                seen.add(s);
            }
            // before 代际 1
            final List<Integer> remBefore = purgeLinear(view1, seen);
            // after 代际 1（建集）
            HashSet<ServerPlayerM> set = new HashSet<>(view1);
            final List<Integer> remAfter = purgeSet(set, seen);
            if (!remBefore.equals(remAfter)) {
                throw new IllegalStateException("DIVERGENCE gen1 iter=" + iter);
            }
            // 代际 2：视图集变化（模拟 updateCount bump）——重建后生效
            List<ServerPlayerM> view2 = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                if (ids1.contains(i) != r.nextBoolean()) { // 翻转一半左右
                    view2.add(world[i]);
                }
            }
            final List<Integer> remBefore2 = purgeLinear(view2, seen);
            set = new HashSet<>(view2); // updateCount 失效 → 重建
            final List<Integer> remAfter2 = purgeSet(set, seen);
            if (!remBefore2.equals(remAfter2)) {
                throw new IllegalStateException("DIVERGENCE gen2 iter=" + iter);
            }
        }
        System.out.println("PurgeProbeBench equivalence self-check: 5000 random generations x "
            + "purge decisions PASS (linear scan vs shared set, incl. updateCount invalidation)");
    }

    static List<Integer> purgeLinear(final List<ServerPlayerM> view, final List<List<ServerPlayerM>> seen) {
        final List<Integer> out = new ArrayList<>();
        for (int t = 0; t < seen.size(); t++) {
            for (final ServerPlayerM p : seen.get(t)) {
                if (!view.contains(p)) {
                    out.add(p.id);
                }
            }
        }
        return out;
    }

    static List<Integer> purgeSet(final HashSet<ServerPlayerM> set, final List<List<ServerPlayerM>> seen) {
        final List<Integer> out = new ArrayList<>();
        for (int t = 0; t < seen.size(); t++) {
            for (final ServerPlayerM p : seen.get(t)) {
                if (!set.contains(p)) {
                    out.add(p.id);
                }
            }
        }
        return out;
    }
}
