package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.Random;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.ticks.TickContainerAccess;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 149 (R9): the canonical shared constant section contract of
// LevelChunkSection - a section whose whole content is one non-ticking,
// non-special-colliding block state with a uniform registered biome collapses onto one
// canonical instance per (blockState, biome) per level registry. Canonical instances
// are immutable by contract: every writer routes through ChunkAccess.papoWritableSection
// (copy-on-write into the array slot) and each section mutator carries a loud canary.
// Uses real block states (bootstrap) but a synthetic biome IdMap over direct holders,
// so no datapack/registry-access machinery is required (batch 147 test pattern).
public class PapoSharedConstantSectionTest {

    private static final Holder<Biome> PLAINS = Holder.direct(null);
    private static final Holder<Biome> DESERT = Holder.direct(null);

    private static Strategy<BlockState> statesStrategy;
    private static Strategy<Holder<Biome>> biomesStrategy;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
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

    private static LevelChunkSection privateSection(final BlockState state, final Holder<Biome> biome) {
        return new LevelChunkSection(
            new PalettedContainer<>(state, statesStrategy, null),
            new PalettedContainer<>(biome, biomesStrategy, null)
        );
    }

    private static LevelChunkSection collapse(final LevelChunkSection section) {
        return section.papoTryCanonicalise(statesStrategy, biomesStrategy);
    }

    @Test
    void uniformAirSectionCollapsesToSharedCanonical() {
        final LevelChunkSection first = collapse(privateSection(Blocks.AIR.defaultBlockState(), PLAINS));
        Assertions.assertTrue(first.papoIsSharedConstant());
        // an equal private section collapses onto the SAME instance (per-registry cache)
        final LevelChunkSection second = collapse(privateSection(Blocks.AIR.defaultBlockState(), PLAINS));
        Assertions.assertSame(first, second);
        // collapsing a canonical is the identity
        Assertions.assertSame(first, collapse(first));
        // reads are value-exact
        Assertions.assertTrue(first.hasOnlyAir());
        Assertions.assertEquals(Blocks.AIR.defaultBlockState(), first.getBlockState(0, 0, 0));
        Assertions.assertEquals(Blocks.AIR.defaultBlockState(), first.getBlockState(15, 15, 15));
        Assertions.assertEquals(PLAINS, first.getNoiseBiome(0, 0, 0));
        Assertions.assertEquals(PLAINS, first.getNoiseBiome(3, 3, 3));
    }

    @Test
    void uniformStoneSectionCollapsesWithAnalyticCounts() {
        final LevelChunkSection canonical = collapse(privateSection(Blocks.STONE.defaultBlockState(), PLAINS));
        Assertions.assertTrue(canonical.papoIsSharedConstant());
        // non-air uniforms carry the analytic nonEmpty count (4096)
        Assertions.assertFalse(canonical.hasOnlyAir());
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), canonical.getBlockState(1, 2, 3));
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), canonical.getBlockState(15, 0, 7));
        // a different biome key is a different canonical
        final LevelChunkSection desertStone = collapse(privateSection(Blocks.STONE.defaultBlockState(), DESERT));
        Assertions.assertNotSame(canonical, desertStone);
    }

    @Test
    void cacheIsPerStrategyInstance() {
        // two registries (two levels) never share canonical instances even for equal ids -
        // the cache hangs on the per-registry biome strategy (batch 147 judgment #3)
        final Strategy<Holder<Biome>> otherBiomesStrategy = Strategy.createForBiomes(biomesStrategy.globalMap());
        final LevelChunkSection a = privateSection(Blocks.AIR.defaultBlockState(), PLAINS)
            .papoTryCanonicalise(statesStrategy, biomesStrategy);
        final LevelChunkSection b = privateSection(Blocks.AIR.defaultBlockState(), PLAINS)
            .papoTryCanonicalise(statesStrategy, otherBiomesStrategy);
        Assertions.assertTrue(a.papoIsSharedConstant());
        Assertions.assertTrue(b.papoIsSharedConstant());
        Assertions.assertNotSame(a, b);
    }

    @Test
    void randomlyTickingUniformStaysPrivate() {
        // grass block randomly ticks - its per-position bookkeeping cannot be shared
        final LevelChunkSection section = privateSection(Blocks.GRASS_BLOCK.defaultBlockState(), PLAINS);
        Assertions.assertSame(section, collapse(section));
        Assertions.assertFalse(section.papoIsSharedConstant());
    }

    @Test
    void mixedSectionStaysPrivate() {
        final LevelChunkSection section = privateSection(Blocks.STONE.defaultBlockState(), PLAINS);
        section.setBlockState(0, 0, 0, Blocks.DIRT.defaultBlockState());
        Assertions.assertSame(section, collapse(section));
    }

    @Test
    void uniformWrittenSectionsCollapse() {
        // worldgen builds uniform sections through the WRITE path: grown palettes whose
        // cells all hold one id (an all-stone section written cell by cell; a biome
        // container filled with a non-default biome). Uniformity is about the CELLS,
        // not the palette type - these must collapse exactly like never-written ones.
        final LevelChunkSection stoneSection = privateSection(Blocks.AIR.defaultBlockState(), PLAINS);
        for (int i = 0; i < 4096; i++) {
            stoneSection.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, Blocks.STONE.defaultBlockState());
        }
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), stoneSection.getBlockState(5, 5, 5));
        final LevelChunkSection stoneCanonical = collapse(stoneSection);
        Assertions.assertNotSame(stoneSection, stoneCanonical);
        Assertions.assertTrue(stoneCanonical.papoIsSharedConstant());
        Assertions.assertFalse(stoneCanonical.hasOnlyAir());
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), stoneCanonical.getBlockState(5, 5, 5));

        // biome side: fill every quart with a non-default biome (grown one-entry palette)
        final LevelChunkSection desertSection = privateSection(Blocks.AIR.defaultBlockState(), PLAINS);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    desertSection.setBiome(x, y, z, DESERT);
                }
            }
        }
        final LevelChunkSection desertCanonical = collapse(desertSection);
        Assertions.assertTrue(desertCanonical.papoIsSharedConstant());
        Assertions.assertEquals(DESERT, desertCanonical.getNoiseBiome(3, 3, 3));
    }

    @Test
    void unregisteredBiomeStaysPrivate() {
        // a direct (unregistered) biome holder never enters a shared set (batch 147 judgment)
        final Holder<Biome> direct = Holder.direct(null);
        final LevelChunkSection section = privateSection(Blocks.AIR.defaultBlockState(), direct);
        Assertions.assertSame(section, collapse(section));
    }

    @Test
    void copyForWritePreservesExactValues() {
        final LevelChunkSection canonical = collapse(privateSection(Blocks.STONE.defaultBlockState(), PLAINS));
        final LevelChunkSection copy = canonical.papoCopyForWrite();
        Assertions.assertFalse(copy.papoIsSharedConstant());
        Assertions.assertFalse(copy.hasOnlyAir());
        for (final int[] pos : new int[][]{{0, 0, 0}, {7, 7, 7}, {15, 15, 15}}) {
            Assertions.assertEquals(canonical.getBlockState(pos[0], pos[1], pos[2]), copy.getBlockState(pos[0], pos[1], pos[2]));
        }
        Assertions.assertEquals(canonical.getNoiseBiome(1, 1, 1), copy.getNoiseBiome(1, 1, 1));
        // writes land on the copy only; the canonical and its shares are untouched
        copy.setBlockState(7, 7, 7, Blocks.GRASS_BLOCK.defaultBlockState());
        Assertions.assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), copy.getBlockState(7, 7, 7));
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), canonical.getBlockState(7, 7, 7));
        // and a same-value write keeps everything stable
        copy.setBlockState(8, 8, 8, Blocks.STONE.defaultBlockState());
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), copy.getBlockState(8, 8, 8));
    }

    @Test
    void canariesFireOnDirectMutation() {
        final LevelChunkSection canonical = collapse(privateSection(Blocks.AIR.defaultBlockState(), PLAINS));
        Assertions.assertThrows(IllegalStateException.class, () -> canonical.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState()));
        Assertions.assertThrows(IllegalStateException.class, () -> canonical.setBiome(0, 0, 0, DESERT));
        Assertions.assertThrows(IllegalStateException.class, canonical::recalcBlockCounts);
        Assertions.assertThrows(IllegalStateException.class, () -> canonical.fillBiomesFromNoise(null, null, 0, 0, 0));
        Assertions.assertThrows(IllegalStateException.class, () -> canonical.read(null));
        Assertions.assertThrows(IllegalStateException.class, () -> canonical.readBiomes(null));
    }

    @Test
    void writableGateReplacesArraySlot() {
        final LevelChunkSection canonicalAir = collapse(privateSection(Blocks.AIR.defaultBlockState(), PLAINS));
        final LevelChunkSection privateMixed = privateSection(Blocks.STONE.defaultBlockState(), PLAINS);
        privateMixed.setBlockState(1, 1, 1, Blocks.DIRT.defaultBlockState());
        final LevelChunkSection[] slots = {canonicalAir, privateMixed};
        final ChunkAccess chunk = fakeChunk(slots);

        // canonical slot: gate materialises a private copy into the array and returns it
        final LevelChunkSection first = chunk.papoWritableSection(0);
        Assertions.assertNotSame(canonicalAir, first);
        Assertions.assertFalse(first.papoIsSharedConstant());
        Assertions.assertSame(first, chunk.getSection(0));
        Assertions.assertEquals(Blocks.AIR.defaultBlockState(), first.getBlockState(0, 0, 0));
        // the canonical itself is undamaged and remains shareable elsewhere
        Assertions.assertTrue(canonicalAir.papoIsSharedConstant());

        // second gate on the same slot is the identity (no re-copy)
        Assertions.assertSame(first, chunk.papoWritableSection(0));

        // private slots pass through untouched
        Assertions.assertSame(privateMixed, chunk.papoWritableSection(1));
    }

    @Test
    void randomizedDifferentialAgainstPrivateMirrors() {
        final Random random = new Random(0xBADC0DE);
        final BlockState[] pool = {
            Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState()
        };
        for (int round = 0; round < 300; round++) {
            // slot 0 starts canonical-air, slot 1 canonical-stone; mirrors are fresh
            // private copies of the same content - after every gated write the two must
            // stay value-identical everywhere
            final LevelChunkSection[] slots = {
                collapse(privateSection(Blocks.AIR.defaultBlockState(), PLAINS)),
                collapse(privateSection(Blocks.STONE.defaultBlockState(), PLAINS))
            };
            final ChunkAccess chunk = fakeChunk(slots);
            final LevelChunkSection[] mirrors = {slots[0].papoCopyForWrite(), slots[1].papoCopyForWrite()};
            for (int op = 0; op < 120; op++) {
                final int slot = random.nextInt(2);
                final int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
                final BlockState state = pool[random.nextInt(pool.length)];
                chunk.papoWritableSection(slot).setBlockState(x, y, z, state);
                mirrors[slot].setBlockState(x, y, z, state);
            }
            for (int slot = 0; slot < 2; slot++) {
                Assertions.assertEquals(mirrors[slot].hasOnlyAir(), chunk.getSection(slot).hasOnlyAir(), "counts round " + round + " slot " + slot);
                for (int i = 0; i < 4096; i += 37) {
                    final int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
                    Assertions.assertEquals(mirrors[slot].getBlockState(x, y, z), chunk.getSection(slot).getBlockState(x, y, z), "round " + round + " slot " + slot + " @" + i);
                }
            }
        }
    }

    private static ChunkAccess fakeChunk(final LevelChunkSection[] sections) {
        final LevelHeightAccessor height = new LevelHeightAccessor() {
            @Override
            public int getHeight() {
                return 32;
            }

            @Override
            public int getMinY() {
                return 0;
            }
        };
        // record constructor with null codecs: nothing in the tested paths touches them,
        // and the fully-populated sections array keeps replaceMissingSections inert
        final PalettedContainerFactory factory = new PalettedContainerFactory(
            statesStrategy, Blocks.AIR.defaultBlockState(), null,
            biomesStrategy, PLAINS, null, null
        );
        return new ChunkAccess(new ChunkPos(0, 0), UpgradeData.EMPTY, height, factory, 0L, sections, null) {
            @Override
            public BlockState getBlockState(final int x, final int y, final int z) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BlockState getBlockStateIfLoaded(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BlockState getBlockState(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BlockState setBlockState(final BlockPos pos, final BlockState state, final int flags) {
                throw new UnsupportedOperationException();
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidIfLoaded(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setBlockEntity(final net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addEntity(final net.minecraft.world.entity.Entity entity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChunkStatus getPersistedStatus() {
                return ChunkStatus.FULL;
            }

            @Override
            public void removeBlockEntity(final BlockPos pos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public net.minecraft.nbt.CompoundTag getBlockEntityNbtForSaving(final BlockPos pos, final net.minecraft.core.HolderLookup.Provider registries) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TickContainerAccess<Block> getBlockTicks() {
                throw new UnsupportedOperationException();
            }

            @Override
            public TickContainerAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChunkAccess.PackedTicks getTicksForSerialization(final long gameTime) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
// Papo end - batch 149 (R9)
