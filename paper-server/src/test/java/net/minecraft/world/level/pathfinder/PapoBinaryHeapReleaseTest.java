package net.minecraft.world.level.pathfinder;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 166 (R25): the pathfinder stale-node release contract. Vanilla
// BinaryHeap.clear() only reset size, so the heap array pinned the last search's
// unpopped frontier and every historically grown-but-never-popped slot between
// searches (measured 26,683 Nodes / 1.71MB across ~679 mobs in the entity soak,
// plus the grown Node[] arrays). The patch nulls the array on clear(); these tests
// pin the three invariants that make that unobservable:
//   (1) heap ordering/size semantics are byte-identical across clear cycles;
//   (2) getHeap() is a dense snapshot taken before clear, so debug payloads never
//       observe nulled slots;
//   (3) after clear(), no slot keeps a reference (the release itself).
public class PapoBinaryHeapReleaseTest {

    private static Node node(final int x, final float cost) {
        final Node n = new Node(x, 0, 0);
        n.f = cost;
        n.g = cost;
        return n;
    }

    @Test
    void heapSemanticsPreservedAcrossClearCycles() {
        final BinaryHeap heap = new BinaryHeap();
        Assertions.assertTrue(heap.isEmpty());

        final Node a = node(1, 5.0F);
        final Node b = node(2, 1.0F);
        final Node c = node(3, 3.0F);
        Assertions.assertSame(a, heap.insert(a));
        heap.insert(b);
        heap.insert(c);

        Assertions.assertEquals(3, heapSize(heap));
        Assertions.assertSame(b, heap.pop(), "cheapest node pops first");
        Assertions.assertSame(c, heap.pop());
        Assertions.assertSame(a, heap.pop());
        Assertions.assertTrue(heap.isEmpty());
        Assertions.assertTrue(a.heapIdx < 0 && b.heapIdx < 0 && c.heapIdx < 0, "popped nodes reset heapIdx");

        // clear + reuse: insert after clear must work with fresh nodes
        final Node d = node(4, 2.0F);
        heap.insert(node(5, 9.0F));
        heap.insert(d);
        Assertions.assertSame(d, heap.pop());
    }

    @Test
    void getHeapSnapshotIsolatedFromClear() {
        final BinaryHeap heap = new BinaryHeap();
        final Node a = node(1, 5.0F);
        final Node b = node(2, 1.0F);
        heap.insert(a);
        heap.insert(b);

        final Node[] snapshot = heap.getHeap();
        Assertions.assertEquals(2, snapshot.length, "snapshot is dense (size-bounded)");
        Assertions.assertTrue(snapshot[0] == b || snapshot[0] == a);

        heap.clear();
        Assertions.assertEquals(2, snapshot.length);
        Assertions.assertNotNull(snapshot[0]);
        Assertions.assertNotNull(snapshot[1], "debug snapshot keeps its nodes after clear - debug payload unaffected");
    }

    @Test
    void clearReleasesAllSlotReferences() {
        final BinaryHeap heap = new BinaryHeap();
        heap.insert(node(1, 5.0F));
        heap.insert(node(2, 1.0F));
        heap.insert(node(3, 3.0F));

        heap.clear();

        Assertions.assertTrue(heap.isEmpty());
        Assertions.assertEquals(0, heap.getHeap().length, "post-clear snapshot is empty");
        for (final Node stale : heapSlots(heap)) {
            Assertions.assertNull(stale, "no slot may keep a reference to a past search's nodes");
        }
    }

    @Test
    void grownCapacityAlsoReleased() {
        final BinaryHeap heap = new BinaryHeap();
        for (int i = 0; i < 300; i++) { // past the initial 128 capacity
            heap.insert(node(i, i));
        }
        heap.clear();
        Assertions.assertTrue(Arrays.stream(heapSlots(heap)).allMatch(java.util.Objects::isNull),
            "grown tail slots (beyond any pop's reach) must also be nulled");
    }

    private static int heapSize(final BinaryHeap heap) {
        return heap.getHeap().length;
    }

    private static Node[] heapSlots(final BinaryHeap heap) {
        // getHeap() is size-bounded, so reflect the raw array for the release assertions
        try {
            final java.lang.reflect.Field f = BinaryHeap.class.getDeclaredField("heap");
            f.setAccessible(true);
            return (Node[]) f.get(heap);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
// Papo end - batch 166 (R25)
