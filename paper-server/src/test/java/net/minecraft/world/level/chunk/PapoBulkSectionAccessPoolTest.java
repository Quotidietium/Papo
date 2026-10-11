package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Papo start - batch 180 (R39): BulkSectionAccess borrows its acquired-sections map
// from a thread-local single-slot pool (OreFeature.doPlace is the only caller and its
// try-with-resources makes instances strictly sequential per thread; nested instances
// fall back to a fresh map). The contract asserted here: maps only ever enter the slot
// empty (sections released and map cleared on close), a borrower re-acquires from
// scratch exactly like a fresh map, two live instances on one thread track sections
// independently (the fatal cross-talk scenario), a double close releases exactly once
// and cannot re-store a borrowed map, the try-with-resources exception path returns
// the map, and the slot is thread-confined. Construction follows the batch 147/149
// synthetic-registry test pattern.
public class PapoBulkSectionAccessPoolTest {

    private static final Holder<Biome> PLAINS = Holder.direct(null);

    private static Strategy<BlockState> statesStrategy;
    private static Strategy<Holder<Biome>> biomesStrategy;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
        statesStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        biomesStrategy = Strategy.createForBiomes(new IdMap<Holder<Biome>>() {
            @Override
            public int getId(final Holder<Biome> value) {
                return value == PLAINS ? 0 : -1;
            }

            @Override
            public Holder<Biome> byId(final int id) {
                if (id != 0) {
                    throw new IllegalArgumentException("id=" + id);
                }
                return PLAINS;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<Holder<Biome>> iterator() {
                return List.of(PLAINS).iterator();
            }
        });
    }

    private static final class CountingSection extends LevelChunkSection {
        int acquires;
        int releases;

        CountingSection() {
            super(
                new PalettedContainer<>(Blocks.AIR.defaultBlockState(), statesStrategy, null),
                new PalettedContainer<>(PLAINS, biomesStrategy, null)
            );
        }

        @Override
        public void acquire() {
            this.acquires++;
            super.acquire();
        }

        @Override
        public void release() {
            this.releases++;
            super.release();
        }
    }

    /** a mocked LevelAccessor whose (0, 0) chunk exposes one counting section in slot 0 */
    private static final class Rig {
        final LevelAccessor level = Mockito.mock(LevelAccessor.class);
        final ChunkAccess chunk = Mockito.mock(ChunkAccess.class);
        final CountingSection section = new CountingSection();

        Rig() {
            Mockito.when(this.level.getSectionIndex(Mockito.anyInt())).thenReturn(0);
            Mockito.when(this.level.getSectionsCount()).thenReturn(1);
            Mockito.doReturn(this.chunk).when(this.level).getChunk(Mockito.anyInt(), Mockito.anyInt());
            Mockito.when(this.chunk.getSection(Mockito.anyInt())).thenReturn(this.section);
        }
    }

    @Test
    void sequentialLifecyclesReuseAClearedMap() {
        Rig rig = new Rig();
        BulkSectionAccess first = new BulkSectionAccess(rig.level);
        Assertions.assertNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get(), "an in-use instance must not sit in the pool slot");
        Assertions.assertSame(rig.section, first.getSection(new BlockPos(0, 0, 0)));
        Assertions.assertSame(rig.section, first.getSection(new BlockPos(1, 0, 1)), "same packed section must hit the lastSection cache");
        Assertions.assertEquals(1, rig.section.acquires, "one section acquired once");
        Mockito.verify(rig.level, Mockito.times(1)).getChunk(0, 0);
        first.close();
        Assertions.assertEquals(1, rig.section.releases);
        Assertions.assertNotNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get(), "close returns the emptied map to the slot");

        BulkSectionAccess second = new BulkSectionAccess(rig.level);
        Assertions.assertNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get(), "borrowing takes the map out of the slot");
        Assertions.assertSame(rig.section, second.getSection(new BlockPos(0, 0, 0)));
        Assertions.assertEquals(2, rig.section.acquires, "the borrowed map was empty - the section must be re-acquired");
        Mockito.verify(rig.level, Mockito.times(2)).getChunk(0, 0);
        second.close();
        Assertions.assertEquals(2, rig.section.releases);
    }

    @Test
    void nestedInstancesTrackSectionsIndependently() {
        Rig rig = new Rig();
        BulkSectionAccess primed = new BulkSectionAccess(rig.level);
        primed.close(); // slot now holds primed's map

        BulkSectionAccess outer = new BulkSectionAccess(rig.level); // borrows the pooled map
        Assertions.assertNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get());
        BulkSectionAccess nested = new BulkSectionAccess(rig.level); // slot empty - must fall back to a fresh map
        Assertions.assertSame(rig.section, outer.getSection(new BlockPos(0, 0, 0)));
        Assertions.assertSame(rig.section, nested.getSection(new BlockPos(0, 0, 0)));
        Assertions.assertEquals(2, rig.section.acquires, "each instance's map tracks its own acquisition - no cross-talk");

        nested.close(); // returns its own map to the slot
        Assertions.assertNotNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get());
        outer.close(); // slot occupied - outer's map is dropped, not stored
        Assertions.assertEquals(2, rig.section.releases, "both instances released exactly what they acquired");
    }

    @Test
    void doubleCloseReleasesExactlyOnce() {
        Rig rig = new Rig();
        BulkSectionAccess access = new BulkSectionAccess(rig.level);
        access.getSection(new BlockPos(0, 0, 0));
        access.close();
        access.close(); // guarded - must be a no-op
        Assertions.assertEquals(1, rig.section.releases, "a second close must not re-release sections");

        BulkSectionAccess next = new BulkSectionAccess(rig.level); // pool must still be serviceable
        Assertions.assertSame(rig.section, next.getSection(new BlockPos(0, 0, 0)));
        next.close();
        Assertions.assertEquals(2, rig.section.acquires, "sanity: two lifecycles acquired once each");
        Assertions.assertEquals(2, rig.section.releases);
    }

    @Test
    void exceptionPathReturnsTheMapToTheSlot() {
        Rig rig = new Rig();
        try (BulkSectionAccess access = new BulkSectionAccess(rig.level)) {
            access.getSection(new BlockPos(0, 0, 0));
            throw new IllegalStateException("boom");
        } catch (IllegalStateException expected) {
        }
        Assertions.assertEquals(1, rig.section.releases, "try-with-resources released the acquired section");
        Assertions.assertNotNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get(), "the emptied map returned to the slot despite the exception");

        BulkSectionAccess after = new BulkSectionAccess(rig.level);
        Assertions.assertSame(rig.section, after.getSection(new BlockPos(0, 0, 0)));
        after.close();
    }

    @Test
    void poolSlotIsThreadConfined() throws InterruptedException {
        Rig rig = new Rig();
        BulkSectionAccess primed = new BulkSectionAccess(rig.level);
        primed.close();
        Assertions.assertNotNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get());

        CountDownLatch done = new CountDownLatch(1);
        final boolean[] workerSawBorrow = new boolean[1];
        Thread worker = new Thread(() -> {
            try {
                Rig otherRig = new Rig();
                try (BulkSectionAccess access = new BulkSectionAccess(otherRig.level)) {
                    access.getSection(new BlockPos(0, 0, 0));
                }
                // the worker thread has its own (empty) slot; its map went there
                workerSawBorrow[0] = BulkSectionAccess.PAPO_SECTION_MAP_POOL.get() != null;
            } finally {
                done.countDown();
            }
        }, "papo-pool-test-worker");
        worker.start();
        boolean finished = done.await(5, TimeUnit.SECONDS);
        Assertions.assertTrue(finished, "worker must finish");
        worker.join();

        Assertions.assertTrue(workerSawBorrow[0], "worker thread must have its own pool slot");
        Assertions.assertNotNull(BulkSectionAccess.PAPO_SECTION_MAP_POOL.get(), "main-thread slot untouched by the worker");
        try (BulkSectionAccess next = new BulkSectionAccess(rig.level)) {
            Assertions.assertSame(rig.section, next.getSection(new BlockPos(0, 0, 0)));
        }
        Assertions.assertEquals(1, rig.section.acquires);
        Assertions.assertEquals(1, rig.section.releases);
    }
}
