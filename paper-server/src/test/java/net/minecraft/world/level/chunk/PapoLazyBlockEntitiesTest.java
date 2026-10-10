package net.minecraft.world.level.chunk;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 161 (R20): the lazy block-entities map contract. ChunkAccess.blockEntities
// is null until the first mutation; null is observably identical to the old eagerly-allocated
// empty map for every audited read (size/keySet/values/containsKey/get/getBlockEntitiesPos)
// and the two put-sites allocate through papoBlockEntitiesWritable(). The empty view is the
// immutable Collections.emptyMap() - a write through it fails loudly, which pins the audited
// read-only contract of getBlockEntities() callers (any future write-through site surfaces as
// an immediate UnsupportedOperationException instead of a silent lost update).
// Construction follows the batch 147/149 synthetic-registry test pattern.
public class PapoLazyBlockEntitiesTest {

    private static final Holder<Biome> PLAINS = Holder.direct(null);
    private static final Holder<Biome> DESERT = Holder.direct(null);

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
                if (value == PLAINS) {
                    return 0;
                }
                return value == DESERT ? 1 : -1;
            }

            @Override
            public Holder<Biome> byId(final int id) {
                return id == 0 ? PLAINS : DESERT;
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public java.util.Iterator<Holder<Biome>> iterator() {
                return List.of(PLAINS, DESERT).iterator();
            }
        });
    }

    private static ProtoChunk protoChunk() {
        final LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        final PalettedContainerFactory factory = new PalettedContainerFactory(
            statesStrategy, Blocks.AIR.defaultBlockState(), null,
            biomesStrategy, PLAINS, null, null
        );
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, height, factory, null);
    }

    @Test
    void lazyFieldStaysNullUntilFirstMutation() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertNull(chunk.blockEntities, "fresh chunk must not allocate the map");
        Assertions.assertTrue(chunk.papoBlockEntitiesView().isEmpty());
        Assertions.assertTrue(chunk.getBlockEntities().isEmpty());
        Assertions.assertTrue(chunk.getBlockEntitiesPos().isEmpty());
        Assertions.assertNull(chunk.getBlockEntity(BlockPos.ZERO));
    }

    @Test
    void emptyViewIsImmutableByContract() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertThrows(UnsupportedOperationException.class,
            () -> chunk.getBlockEntities().put(BlockPos.ZERO, org.mockito.Mockito.mock(BlockEntity.class)),
            "empty view must fail loudly on write-through (audited read-only contract)");
    }

    @Test
    void writableAllocatesOnceAndServesLiveView() {
        final ProtoChunk chunk = protoChunk();
        final BlockEntity first = org.mockito.Mockito.mock(BlockEntity.class);
        final BlockEntity second = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(first.getBlockPos()).thenReturn(BlockPos.ZERO);
        org.mockito.Mockito.when(second.getBlockPos()).thenReturn(new BlockPos(1, 0, 0));
        chunk.setBlockEntity(first);
        chunk.setBlockEntity(second);
        Assertions.assertNotNull(chunk.blockEntities, "first mutation must allocate");
        Assertions.assertEquals(2, chunk.blockEntities.size());
        Assertions.assertSame(first, chunk.getBlockEntity(BlockPos.ZERO));
        Assertions.assertSame(second, chunk.getBlockEntity(new BlockPos(1, 0, 0)));
        Assertions.assertEquals(2, chunk.papoBlockEntitiesView().size());
        Assertions.assertTrue(chunk.getBlockEntitiesPos().containsAll(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0))));
    }

    @Test
    void removeOnUnallocatedMapIsANoOp() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertDoesNotThrow(() -> chunk.removeBlockEntity(BlockPos.ZERO));
        Assertions.assertNull(chunk.blockEntities, "removal on the null state must not allocate");
    }

    @Test
    void removalKeepsTheMapAllocatedLikeTheOldEmptyMap() {
        final ProtoChunk chunk = protoChunk();
        final BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);
        chunk.setBlockEntity(be);
        chunk.removeBlockEntity(BlockPos.ZERO);
        Assertions.assertNotNull(chunk.blockEntities, "post-mutation map persists (same as the old empty map)");
        Assertions.assertTrue(chunk.getBlockEntities().isEmpty());
    }
}
