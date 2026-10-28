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

// Papo start - compat fix (revert of batch 161/R20 lazy field): the public block-entities
// contract as of Paper 1.21.11. `ChunkAccess.blockEntities` and `getBlockEntities()` hand
// plugins a live, writable, always-non-null map - NMS-touching plugins iterate it and write
// through it directly. These tests pin that contract: fresh chunk => non-null empty mutable
// map; writes through getBlockEntities() are visible; the papo* accessors alias the same
// live map. Construction follows the batch 147/149 synthetic-registry test pattern.
public class PapoBlockEntitiesCompatTest {

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
    void freshChunkHasLiveEmptyMutableMap() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertNotNull(chunk.blockEntities, "plugin-visible field must never be null (Paper 1.21.11 contract)");
        Assertions.assertTrue(chunk.blockEntities.isEmpty());
        Assertions.assertTrue(chunk.getBlockEntities().isEmpty());
        Assertions.assertTrue(chunk.getBlockEntitiesPos().isEmpty());
        Assertions.assertNull(chunk.getBlockEntity(BlockPos.ZERO));
        // The map must be writable by plugins directly through the public field.
        final BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        Assertions.assertDoesNotThrow(() -> chunk.blockEntities.put(BlockPos.ZERO, be));
        Assertions.assertSame(be, chunk.getBlockEntity(BlockPos.ZERO));
    }

    @Test
    void getBlockEntitiesReturnsLiveWritableMap() {
        final ProtoChunk chunk = protoChunk();
        final BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);
        // Plugin pattern that the lazy-view scheme broke with UOE: write through the getter.
        Assertions.assertDoesNotThrow(() -> chunk.getBlockEntities().put(BlockPos.ZERO, be));
        Assertions.assertEquals(1, chunk.getBlockEntities().size());
        Assertions.assertSame(be, chunk.getBlockEntity(BlockPos.ZERO));
        Assertions.assertSame(chunk.blockEntities, chunk.getBlockEntities(), "getter must expose the same live map as the field");
    }

    @Test
    void papoAccessorsAliasTheLiveMap() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertSame(chunk.blockEntities, chunk.papoBlockEntitiesView());
        Assertions.assertSame(chunk.blockEntities, chunk.papoBlockEntitiesWritable());
        final BlockEntity first = org.mockito.Mockito.mock(BlockEntity.class);
        final BlockEntity second = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(first.getBlockPos()).thenReturn(BlockPos.ZERO);
        org.mockito.Mockito.when(second.getBlockPos()).thenReturn(new BlockPos(1, 0, 0));
        chunk.setBlockEntity(first);
        chunk.setBlockEntity(second);
        Assertions.assertEquals(2, chunk.blockEntities.size());
        Assertions.assertSame(first, chunk.getBlockEntity(BlockPos.ZERO));
        Assertions.assertSame(second, chunk.getBlockEntity(new BlockPos(1, 0, 0)));
        Assertions.assertEquals(2, chunk.papoBlockEntitiesView().size());
        Assertions.assertTrue(chunk.getBlockEntitiesPos().containsAll(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0))));
    }

    @Test
    void removeOnEmptyMapIsANoOp() {
        final ProtoChunk chunk = protoChunk();
        Assertions.assertDoesNotThrow(() -> chunk.removeBlockEntity(BlockPos.ZERO));
        Assertions.assertTrue(chunk.getBlockEntities().isEmpty());
    }

    @Test
    void removalKeepsTheMapLikeVanilla() {
        final ProtoChunk chunk = protoChunk();
        final BlockEntity be = org.mockito.Mockito.mock(BlockEntity.class);
        org.mockito.Mockito.when(be.getBlockPos()).thenReturn(BlockPos.ZERO);
        chunk.setBlockEntity(be);
        chunk.removeBlockEntity(BlockPos.ZERO);
        Assertions.assertTrue(chunk.getBlockEntities().isEmpty());
    }
}
