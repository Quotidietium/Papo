package net.minecraft.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 145 (R6): the shared-immutable contract of ZeroBitStorage.papoShared -
// same entry count yields the same interchangeable instance, behaviour of every accessor is
// size-derived only, and copy() returns the same reference (vanilla already treats it as shareable).
public class PapoZeroBitStorageSharedTest {

    @Test
    void sharedPerEntryCount() {
        final ZeroBitStorage a = ZeroBitStorage.papoShared(4096);
        final ZeroBitStorage b = ZeroBitStorage.papoShared(4096);
        final ZeroBitStorage c = ZeroBitStorage.papoShared(64);
        Assertions.assertSame(a, b);
        Assertions.assertNotSame(a, c);
        Assertions.assertSame(a, a.copy());       // copy() 早已返回 this —— 共享与 vanilla 语义一致
        Assertions.assertEquals(4096, a.getSize());
        Assertions.assertEquals(64, c.getSize());
    }

    @Test
    void accessorSemanticsUnchanged() {
        final ZeroBitStorage s = ZeroBitStorage.papoShared(64);
        Assertions.assertEquals(0, s.getBits());
        Assertions.assertEquals(0, s.get(0));
        Assertions.assertEquals(0, s.getAndSet(10, 3)); // set 系列是 no-op
        Assertions.assertEquals(0, s.get(10));
        Assertions.assertEquals(0, s.getRaw().length);  // 共享静态空数组
        final int[] out = new int[64];
        s.unpack(out);
        for (final int v : out) {
            Assertions.assertEquals(0, v);
        }
        final java.util.List<Integer> all = new java.util.ArrayList<>();
        s.getAll(all::add);
        Assertions.assertEquals(64, all.size());
    }

    @Test
    void independentFromConstructorInstances() {
        final ZeroBitStorage shared = ZeroBitStorage.papoShared(128);
        final ZeroBitStorage fresh = new ZeroBitStorage(128);
        // 共享实例与新建实例行为逐位等价（不可变性 ⇒ 可互换）
        Assertions.assertEquals(fresh.getSize(), shared.getSize());
        Assertions.assertEquals(fresh.get(1), shared.get(1));
        Assertions.assertSame(fresh, fresh.copy());
    }
}
// Papo end - batch 145 (R6)
