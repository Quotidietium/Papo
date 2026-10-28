package net.minecraft.world.item;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 186 (R45): the lazy fixed-size items-list contract. Oracle is the exact
// vanilla expression being replaced at the container-BE construction sites:
// NonNullList.withSize(n, ItemStack.EMPTY). Every observable (size/get/iteration/equals both
// directions/UOE family/clear-roundtrip/sort-materialization) must be bit-identical between
// the lazy list and the oracle across empty, materialized and re-cleared states.
public class PapoLazyItemsListTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    private static NonNullList<ItemStack> oracle(final int size) {
        return NonNullList.withSize(size, ItemStack.EMPTY);
    }

    private static ItemStack stack(final int count) {
        return new ItemStack(Items.STONE, count);
    }

    private static final int[] SIZES = {1, 3, 5, 9, 27, 64};

    // Papo start - compat: NonNullList.set rejects null with NPE (Objects.requireNonNull before
    // the backing delegation); the lazy list must keep that fast-fail contract instead of
    // silently storing nulls that explode far away from the write site.
    @org.junit.jupiter.api.Test
    void setNullThrowsNpeLikeNonNullList() {
        final net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> list =
            net.minecraft.world.item.PapoLazyItemsList.papoOf(3);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> list.set(0, null));
        // and a materialized list keeps the same contract
        list.set(0, net.minecraft.world.item.ItemStack.EMPTY);
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> list.set(1, null));
    }
    // Papo end

    @Test
    void emptyStateMatchesOracle() {
        for (final int n : SIZES) {
            final NonNullList<ItemStack> subject = PapoLazyItemsList.papoOf(n);
            final NonNullList<ItemStack> ref = oracle(n);
            Assertions.assertEquals(n, subject.size(), "size n=" + n);
            Assertions.assertFalse(subject.isEmpty(), "fixed-size view is never empty n=" + n);
            for (int i = 0; i < n; i++) {
                Assertions.assertSame(ItemStack.EMPTY, subject.get(i), "slot EMPTY identity n=" + n + " i=" + i);
            }
            Assertions.assertEquals(ref, subject, "subject equals oracle n=" + n);
            Assertions.assertEquals(subject, ref, "oracle equals subject n=" + n);
            Assertions.assertEquals(ref.hashCode(), subject.hashCode(), "hashCode n=" + n);
            Assertions.assertArrayEquals(ref.toArray(), subject.toArray(), "toArray n=" + n);
            int seen = 0;
            for (final ItemStack s : subject) {
                Assertions.assertSame(ItemStack.EMPTY, s);
                seen++;
            }
            Assertions.assertEquals(n, seen, "iteration count n=" + n);
        }
    }

    @Test
    void writeThenReadMatchesOracle() {
        final NonNullList<ItemStack> subject = PapoLazyItemsList.papoOf(27);
        final NonNullList<ItemStack> ref = oracle(27);
        final ItemStack a = stack(7);
        final ItemStack b = stack(2);
        Assertions.assertSame(ItemStack.EMPTY, subject.set(3, a), "set returns prior EMPTY");
        Assertions.assertSame(ItemStack.EMPTY, ref.set(3, a));
        Assertions.assertSame(a, subject.get(3));
        Assertions.assertSame(a, subject.set(3, b), "second set returns prior stack");
        ref.set(3, b);
        Assertions.assertSame(b, subject.get(3));
        Assertions.assertEquals(ref, subject, "materialized subject equals oracle");
        // 其他槽位保持 EMPTY 身份
        for (int i = 0; i < 27; i++) {
            if (i != 3) {
                Assertions.assertSame(ItemStack.EMPTY, subject.get(i));
            }
        }
        // set 越界两态同异常
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> subject.set(27, a));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> ref.set(27, a));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> subject.get(-1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> ref.get(-1));
    }

    @Test
    void unsupportedOperationsMatchOracle() {
        final NonNullList<ItemStack> subject = PapoLazyItemsList.papoOf(9);
        final NonNullList<ItemStack> ref = oracle(9);
        final ItemStack a = stack(1);
        // 未物化态
        Assertions.assertThrows(UnsupportedOperationException.class, () -> subject.add(a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> subject.add(0, a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> subject.remove(0));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ref.add(a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ref.add(0, a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ref.remove(0));
        // 物化态（vanilla Arrays.asList 固定尺寸背板同样恒抛）
        subject.set(4, a);
        ref.set(4, a);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> subject.add(a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> subject.remove(4));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ref.add(a));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> ref.remove(4));
    }

    @Test
    void clearRoundTripReturnsToEmptyState() {
        final NonNullList<ItemStack> subject = PapoLazyItemsList.papoOf(27);
        subject.set(0, stack(5));
        subject.set(26, stack(6));
        Assertions.assertFalse(subject.equals(oracle(27)), "non-empty differs from fresh oracle");
        subject.clear(); // vanilla: set-loop back to EMPTY; lazy: drop backing
        Assertions.assertEquals(oracle(27), subject, "clear returns bit-identical empty state");
        Assertions.assertEquals(27, subject.size());
        for (int i = 0; i < 27; i++) {
            Assertions.assertSame(ItemStack.EMPTY, subject.get(i));
        }
        // clear 后可再次写入
        final ItemStack a = stack(9);
        Assertions.assertSame(ItemStack.EMPTY, subject.set(13, a));
        Assertions.assertSame(a, subject.get(13));
        // 空态 clear 为 no-op
        final NonNullList<ItemStack> neverWritten = PapoLazyItemsList.papoOf(5);
        neverWritten.clear();
        Assertions.assertEquals(oracle(5), neverWritten);
    }

    @Test
    void sortAndIteratorSemanticsMatchOracle() {
        final NonNullList<ItemStack> subject = PapoLazyItemsList.papoOf(5);
        final NonNullList<ItemStack> ref = oracle(5);
        final Comparator<ItemStack> byCount = Comparator.comparingInt(ItemStack::getCount);
        for (int i = 0; i < 5; i++) {
            final ItemStack s = stack((i * 3) % 5);
            subject.set(i, s);
            ref.set(i, s);
        }
        subject.sort(byCount); // 经 toArray+set 循环，物化路径
        ref.sort(byCount);
        Assertions.assertEquals(ref, subject, "sorted order matches oracle");
        // ListIterator 写面
        final Iterator<ItemStack> it = subject.iterator();
        Assertions.assertTrue(it.hasNext());
        it.next();
        Assertions.assertThrows(UnsupportedOperationException.class, it::remove);
    }
}
// Papo end - batch 186 (R45)
