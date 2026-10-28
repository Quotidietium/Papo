package net.minecraft.world.ticks;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 145 (R6): differential test for the lazy-allocation semantics of
// PapoPosTypeSet against a HashSet model with (packedPos, identity-of-type) equality,
// covering the null-state (pre-first-add), empty-after-clear and resize paths.
public class PapoPosTypeSetLazyTest {

    static final class Pair {
        final long key;
        final Object type;

        Pair(final long key, final Object type) {
            this.key = key;
            this.type = type;
        }

        @Override
        public boolean equals(final Object o) {
            if (o instanceof Pair p) {
                return p.key == this.key && p.type == this.type;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int h = (int) (this.key ^ (this.key >>> 32));
            h = h * 31 + System.identityHashCode(this.type);
            h ^= h >>> 16;
            return h;
        }
    }

    @Test
    void nullStateSemantics() {
        final PapoPosTypeSet<Object> set = new PapoPosTypeSet<>();
        Assertions.assertTrue(set.isEmpty());
        Assertions.assertFalse(set.contains(1L, PapoPosTypeSetLazyTest.class));
        Assertions.assertFalse(set.remove(1L, PapoPosTypeSetLazyTest.class));
        set.clear(); // 空态 clear 必须是无害 no-op
        Assertions.assertTrue(set.isEmpty());
        Assertions.assertFalse(set.contains(1L, PapoPosTypeSetLazyTest.class));
    }

    @Test
    void firstAddAllocatesAndRoundTrips() {
        final PapoPosTypeSet<Object> set = new PapoPosTypeSet<>();
        final Object a = new Object();
        Assertions.assertFalse(set.add(7L, a));       // 首次 add 分配并插入
        Assertions.assertTrue(set.contains(7L, a));
        Assertions.assertTrue(set.add(7L, a));        // 重复 add 报已存在
        Assertions.assertFalse(set.contains(7L, new Object()));
        Assertions.assertTrue(set.remove(7L, a));
        Assertions.assertFalse(set.contains(7L, a));
        Assertions.assertFalse(set.remove(7L, a));    // 再删报无
        Assertions.assertTrue(set.isEmpty());          // 删空后可复用（数组保留）
        Assertions.assertFalse(set.add(7L, a));        // 复用已分配数组再插
        Assertions.assertTrue(set.contains(7L, a));
    }

    @Test
    void identityTypeSemantics() {
        final PapoPosTypeSet<String> set = new PapoPosTypeSet<>();
        final String t1 = new String("type");
        final String t2 = new String("type");         // equals 相同但引用不同
        Assertions.assertFalse(set.add(5L, t1));
        Assertions.assertFalse(set.contains(5L, t2)); // 语义 = UNIQUE_TICK_HASH 的引用相等
        Assertions.assertFalse(set.add(5L, t2));
        Assertions.assertTrue(set.contains(5L, t2));
        Assertions.assertTrue(set.remove(5L, t1));
        Assertions.assertFalse(set.contains(5L, t1));
        Assertions.assertTrue(set.contains(5L, t2));
    }

    @Test
    void randomDifferential() {
        final Random random = new Random(90145L);
        final PapoPosTypeSet<Integer> set = new PapoPosTypeSet<>();
        final Set<Pair> model = new HashSet<>();
        final Integer[] types = new Integer[16];
        for (int i = 0; i < types.length; i++) {
            types[i] = i; // Integer 缓存内的同一引用
        }
        for (int op = 0; op < 200_000; op++) {
            final long key = random.nextInt(3000);
            final Integer type = types[random.nextInt(types.length)];
            final int action = random.nextInt(10);
            if (action < 4) {
                // 断言前先对齐返回值约定：HashSet.add=新插入返回 true；
                // PapoPosTypeSet.add=已存在返回 true（batch 142 判例：对拍两侧约定必须显式对齐）
                Assertions.assertEquals(model.add(new Pair(key, type)), !set.add(key, type), "add " + key + "/" + type);
            } else if (action < 7) {
                Assertions.assertEquals(model.remove(new Pair(key, type)), set.remove(key, type), "remove " + key + "/" + type);
            } else {
                Assertions.assertEquals(model.contains(new Pair(key, type)), set.contains(key, type), "contains " + key + "/" + type);
            }
            Assertions.assertEquals(model.isEmpty(), set.isEmpty(), "isEmpty @" + op);
        }
        // 全清 + 复用
        set.clear();
        model.clear();
        Assertions.assertTrue(set.isEmpty());
        for (int i = 0; i < 100; i++) {
            Assertions.assertFalse(set.add(i, types[0]));
            model.add(new Pair(i, types[0]));
        }
        Assertions.assertEquals(model.size(), 100);
        for (int i = 0; i < 100; i++) {
            Assertions.assertTrue(set.contains(i, types[0]));
        }
    }

    @Test
    void resizeBeyondInitialCapacity() {
        final PapoPosTypeSet<Object> set = new PapoPosTypeSet<>();
        final Object t = new Object();
        for (long k = 0; k < 1000; k++) {   // 跨多次扩容（16→…→2048）
            Assertions.assertFalse(set.add(k, t));
        }
        for (long k = 0; k < 1000; k++) {
            Assertions.assertTrue(set.contains(k, t));
        }
        for (long k = 0; k < 1000; k += 2) { // backward-shift 删除交织
            Assertions.assertTrue(set.remove(k, t));
        }
        for (long k = 0; k < 1000; k++) {
            Assertions.assertEquals((k & 1) == 1, set.contains(k, t));
        }
    }
}
// Papo end - batch 145 (R6)
