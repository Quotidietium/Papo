package net.minecraft.world.level.chunk;

import ca.spottedleaf.moonrise.common.list.ShortList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 170 (R29): the recalcBlockCounts rewrite - flat int[paletteSize]
// counting via moonrise$countByIndex plus deferred coordinate collection via
// moonrise$collectIndices replaces the per-palette-id coordinate ShortArrayList
// materialization of moonrise$countEntries (the dominant allocation site of the
// disk-load burst). The legacy walk remains in the tree and serves as the oracle:
// per-id counts and per-id coordinate sequences must match it exactly, and a mixed
// section's recalculated counters plus tick list must match the incremental
// setBlockState bookkeeping (two independent engines over the same content).
// Synthetic biome strategy per the batch 149 test pattern.
public class PapoBlockCountRecalcIdentityTest {

    private static final Holder<Biome> PLAINS = Holder.direct(null);

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

    private static LevelChunkSection emptySection() {
        return new LevelChunkSection(
            new PalettedContainer<>(Blocks.AIR.defaultBlockState(), statesStrategy, null),
            new PalettedContainer<>(PLAINS, biomesStrategy, null)
        );
    }

    private static short counter(final LevelChunkSection section, final String name) {
        try {
            final Field field = LevelChunkSection.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getShort(section);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object tickListMap(final ShortList list) {
        try {
            final Field field = ShortList.class.getDeclaredField("map");
            field.setAccessible(true);
            return field.get(list);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Set<Short> tickListContents(final ShortList list) {
        final Set<Short> contents = new HashSet<>();
        for (int i = 0, n = list.size(); i < n; ++i) {
            contents.add(list.getRaw(i));
        }
        return contents;
    }

    @Test
    void simpleStorageCountingMatchesLegacyOracle() {
        for (final int bits : new int[] {4, 5, 6, 8, 13}) {
            final int size = 4096;
            final int usedIds = Math.min(5, (1 << bits) - 1) + 1; // sparse: most ids never appear
            final int[] values = new int[size];
            final Random random = new Random(0xC0FFEE + bits);
            for (int i = 0; i < size; ++i) {
                values[i] = i < 512 ? random.nextInt(usedIds) : 0;
            }
            final SimpleBitStorage storage = new SimpleBitStorage(bits, size, values);

            final Int2ObjectOpenHashMap<ShortArrayList> legacy = storage.moonrise$countEntries();
            final int[] counts = new int[usedIds];
            storage.moonrise$countByIndex(counts);

            int legacyIds = 0;
            int countedCells = 0;
            for (int id = 0; id < usedIds; ++id) {
                countedCells += counts[id];
                if (counts[id] != 0) {
                    legacyIds++;
                }
            }
            Assertions.assertEquals(size, countedCells, "cells counted, bits=" + bits);
            Assertions.assertEquals(legacy.size(), legacyIds, "distinct ids, bits=" + bits);
            for (final Int2ObjectMap.Entry<ShortArrayList> entry : legacy.int2ObjectEntrySet()) {
                Assertions.assertEquals(entry.getValue().size(), counts[entry.getIntKey()], "per-id count, bits=" + bits);
            }

            // coordinate identity per id: legacy lists are appended in ascending cell order,
            // so the collected sequence must equal them element-for-element
            for (final Int2ObjectMap.Entry<ShortArrayList> entry : legacy.int2ObjectEntrySet()) {
                final ShortList collected = new ShortList();
                storage.moonrise$collectIndices(entry.getIntKey(), collected);
                Assertions.assertEquals(entry.getValue().size(), collected.size(), "collected size, bits=" + bits);
                for (int i = 0, n = collected.size(); i < n; ++i) {
                    Assertions.assertEquals(entry.getValue().getShort(i), collected.getRaw(i), "coord[" + i + "], bits=" + bits);
                }
            }

            // absent ids (when the sparse fill leaves one) collect nothing
            for (int id = 0; id < usedIds; ++id) {
                if (counts[id] == 0) {
                    final ShortList empty = new ShortList();
                    storage.moonrise$collectIndices(id, empty);
                    Assertions.assertEquals(0, empty.size(), "absent id collects nothing, bits=" + bits);
                    break;
                }
            }
        }
    }

    @Test
    void zeroStorageCountingMatchesLegacyOracle() {
        final ZeroBitStorage zero = new ZeroBitStorage(64);

        final Int2ObjectOpenHashMap<ShortArrayList> legacy = zero.moonrise$countEntries();
        Assertions.assertEquals(1, legacy.size());
        Assertions.assertEquals(64, legacy.get(0).size());

        final int[] counts = new int[1];
        zero.moonrise$countByIndex(counts);
        Assertions.assertEquals(64, counts[0]);

        final ShortList wrongId = new ShortList();
        zero.moonrise$collectIndices(1, wrongId);
        Assertions.assertEquals(0, wrongId.size());
        final ShortList zeroId = new ShortList();
        zero.moonrise$collectIndices(0, zeroId);
        Assertions.assertEquals(64, zeroId.size());
        Assertions.assertEquals(legacy.get(0).getShort(63), zeroId.getRaw(63));
    }

    @Test
    void sectionRecalcMatchesIncrementalBookkeeping() {
        final LevelChunkSection section = emptySection();

        // deterministic distinct placements - no overwrite bookkeeping needed
        for (int i = 0; i < 4096; ++i) {
            final BlockState state;
            if (i < 1500) {
                state = Blocks.STONE.defaultBlockState();
            } else if (i < 1700) {
                state = Blocks.GRASS_BLOCK.defaultBlockState(); // randomly ticking
            } else if (i < 1800) {
                state = Blocks.WATER.defaultBlockState(); // fluid, not randomly ticking
            } else if (i < 1900) {
                state = Blocks.LAVA.defaultBlockState(); // randomly ticking fluid
            } else if (i < 2100) {
                state = Blocks.POWDER_SNOW.defaultBlockState(); // candidate special-colliding
            } else {
                state = Blocks.AIR.defaultBlockState();
            }
            if (state.isAir()) {
                continue;
            }
            section.setBlockState(i & 15, (i >>> 8) & 15, (i >>> 4) & 15, state, false);
        }

        // oracle A: the incremental setBlockState bookkeeping
        final short oracleNonEmpty = counter(section, "nonEmptyBlockCount");
        final short oracleTicking = counter(section, "tickingBlockCount");
        final short oracleTickingFluid = counter(section, "tickingFluidCount");
        final short oracleSpecial = counter(section, "specialCollidingBlocks");
        final Set<Short> oracleTickCoords = tickListContents(((ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection)section).moonrise$getTickingBlockList());

        Assertions.assertEquals(2100, oracleNonEmpty); // stone+grass+water+lava+powder
        Assertions.assertEquals(300, oracleTicking);   // grass + lava (LavaBlock random-ticks too)
        Assertions.assertEquals(100, oracleTickingFluid); // lava fluid
        Assertions.assertEquals(300, oracleTickCoords.size());

        // the rewrite must reproduce oracle A exactly
        section.recalcBlockCounts();

        Assertions.assertEquals(oracleNonEmpty, counter(section, "nonEmptyBlockCount"), "nonEmpty");
        Assertions.assertEquals(oracleTicking, counter(section, "tickingBlockCount"), "ticking");
        Assertions.assertEquals(oracleTickingFluid, counter(section, "tickingFluidCount"), "tickingFluid");
        Assertions.assertEquals(oracleSpecial, counter(section, "specialCollidingBlocks"), "special");
        Assertions.assertEquals(oracleTickCoords, tickListContents(((ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection)section).moonrise$getTickingBlockList()), "tick coords");
    }

    @Test
    void zeroTickingSectionKeepsTickListLazy() {
        final LevelChunkSection section = emptySection();
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
        section.setBlockState(15, 15, 15, Blocks.DIRT.defaultBlockState(), false);

        section.recalcBlockCounts();

        final ShortList tickList = ((ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection)section).moonrise$getTickingBlockList();
        Assertions.assertEquals(0, tickList.size());
        Assertions.assertNull(tickListMap(tickList), "no ticking ids -> the lazy ShortList map must stay unallocated");
    }
}
