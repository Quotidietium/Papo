package net.minecraft.world.level.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 173 (R32): the function-sourced repack (papoReencodedStorage +
// SimpleBitStorage(bits, size, IntUnaryOperator)) must be bit-identical to the legacy
// array pipeline (reencodeContents -> SimpleBitStorage(bits, size, int[])), which stays
// in the tree as the oracle. Covered: random storages across bit widths with interleaved
// runs, ZeroBitStorage source (uniform old storage on the pack() path), palette idFor
// call count equivalence (the run-length memo must fire exactly once per run on the new
// path), and a container-level codec round-trip that exercises pack() end to end.
public class PapoReencodeIdentityTest {

    private static Strategy<net.minecraft.world.level.block.state.BlockState> blockStrategy;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        blockStrategy = Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY);
    }

    @Test
    void packWireBytesMatchLegacyArrayPipeline() throws Exception {
        final PalettedContainer<net.minecraft.world.level.block.state.BlockState> container =
            new PalettedContainer<>(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), blockStrategy);
        container.set(0, 0, 0, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        container.set(1, 0, 0, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        container.set(2, 0, 0, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        container.set(3, 0, 0, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        container.set(15, 15, 15, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());

        // new path: the real pack()
        final PalettedContainerRO.PackedData<net.minecraft.world.level.block.state.BlockState> packed = container.pack(blockStrategy);
        Assertions.assertTrue(packed.storage().isPresent());

        // legacy oracle: the array pipeline over the same live data
        final java.lang.reflect.Field dataField = PalettedContainer.class.getDeclaredField("data");
        dataField.setAccessible(true);
        final Object data = dataField.get(container);
        final java.lang.reflect.Field storageField = data.getClass().getDeclaredField("storage");
        storageField.setAccessible(true);
        final BitStorage storage = (BitStorage) storageField.get(data);
        final java.lang.reflect.Field paletteField = data.getClass().getDeclaredField("palette");
        paletteField.setAccessible(true);
        final Palette<net.minecraft.world.level.block.state.BlockState> palette =
            (Palette<net.minecraft.world.level.block.state.BlockState>) paletteField.get(data);
        final HashMapPalette<net.minecraft.world.level.block.state.BlockState> legacy =
            new HashMapPalette<>(storage.getBits());
        final int[] ints = PalettedContainer.reencodeContents(storage, palette, legacy);
        final SimpleBitStorage legacyStorage = new SimpleBitStorage(
            Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY)
                .getConfigurationForPaletteSize(legacy.getSize()).bitsInStorage(), 4096, ints);

        Assertions.assertTrue(java.util.Arrays.equals(legacyStorage.getRaw(), packed.storage().get().toArray()), // Papo - compat: storage() is LongStream again (vanilla ABI restored)
            "pack() wire bytes must match the legacy array pipeline (palette id assignment is order-defined)");
        Assertions.assertEquals(legacy.getEntries(), packed.paletteEntries(),
            "palette entries (id order) must match the legacy ascending-first-occurrence assignment");
    }

    private static final class CountingPalette<T> implements Palette<T> {
        final Palette<T> delegate;
        int idForCalls;

        CountingPalette(final Palette<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int idFor(final T state, final PaletteResize<T> resizeHandler) {
            this.idForCalls++;
            return this.delegate.idFor(state, resizeHandler);
        }

        @Override
        public boolean maybeHas(final Predicate<T> filter) {
            return this.delegate.maybeHas(filter);
        }

        @Override
        public T valueFor(final int id) {
            return this.delegate.valueFor(id);
        }

        @Override
        public void read(final FriendlyByteBuf buffer, final IdMap<T> map) {
            this.delegate.read(buffer, map);
        }

        @Override
        public void write(final FriendlyByteBuf buffer, final IdMap<T> map) {
            this.delegate.write(buffer, map);
        }

        @Override
        public int getSerializedSize(final IdMap<T> map) {
            return this.delegate.getSerializedSize(map);
        }

        @Override
        public int getSize() {
            return this.delegate.getSize();
        }

        @Override
        public Palette<T> copy() {
            return this.delegate.copy();
        }
    }

    private static List<Integer> values(final int count) {
        final List<Integer> list = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            list.add(1000 + i);
        }
        return list;
    }

    @Test
    void functionSourcedRepackMatchesArrayOracle() {
        for (final int oldBits : new int[] {4, 5, 8}) {
            for (final int newBits : new int[] {5, 6, 9}) {
                final Random random = new Random(0xABCDEF + oldBits * 31 + newBits);
                final int size = 4096;
                final int distinctOld = Math.min(1 << oldBits, 24);
                final int[] ids = new int[size];
                int run = 0;
                int current = -1;
                for (int i = 0; i < size; ++i) {
                    if (run == 0) {
                        run = 1 + random.nextInt(9); // interleaved runs and singletons
                        current = random.nextInt(distinctOld);
                    }
                    run--;
                    ids[i] = current;
                }
                // expected memo fires = id changes along the PACKING loop's actual visit
                // order: longs ascend, cells within a long descend (SimpleBitStorage ctor)
                final SimpleBitStorage probe = new SimpleBitStorage(oldBits, size, ids);
                final int valuesPerLong = 64 / newBits; // visit order follows the NEW storage's packing loop
                int expectedRuns = 0;
                int lastSeen = -1;
                for (int base = 0; base + valuesPerLong <= size; base += valuesPerLong) {
                    for (int i2 = valuesPerLong - 1; i2 >= 0; i2--) {
                        if (probe.get(base + i2) != lastSeen) {
                            lastSeen = probe.get(base + i2);
                            expectedRuns++;
                        }
                    }
                }
                for (int i2 = size - 1; i2 >= (size / valuesPerLong) * valuesPerLong; i2--) {
                    if (probe.get(i2) != lastSeen) {
                        lastSeen = probe.get(i2);
                        expectedRuns++;
                    }
                }
                final SimpleBitStorage oldStorage = new SimpleBitStorage(oldBits, size, ids);
                final List<Integer> oldValues = values(distinctOld);
                // new palette holds a shuffled superset so the mapping is non-trivial
                final List<Integer> newValues = new ArrayList<>(oldValues);
                newValues.add(2000);
                java.util.Collections.shuffle(newValues, new Random(oldBits * 7 + newBits));

                final HashMapPalette<Integer> oracleFrom = new HashMapPalette<>(oldBits, oldValues);
                final HashMapPalette<Integer> oracleTo = new HashMapPalette<>(newBits, newValues);
                final int[] oracleInts = PalettedContainer.reencodeContents(oldStorage, oracleFrom, oracleTo);
                final SimpleBitStorage oracleStorage = new SimpleBitStorage(newBits, size, oracleInts);

                final CountingPalette<Integer> countedTo = new CountingPalette<>(new HashMapPalette<>(newBits, newValues));
                final SimpleBitStorage papoStorage = PalettedContainer.papoReencodedStorage(
                    oldStorage, new HashMapPalette<>(oldBits, oldValues), countedTo, newBits, size);

                Assertions.assertArrayEquals(oracleStorage.getRaw(), papoStorage.getRaw(), "repacked longs, oldBits=" + oldBits + " newBits=" + newBits);
                Assertions.assertEquals(expectedRuns, countedTo.idForCalls,
                    "run-length memo must call idFor exactly once per run, oldBits=" + oldBits);
            }
        }
    }

    @Test
    void zeroBitStorageSourceRepacks() {
        final ZeroBitStorage uniform = new ZeroBitStorage(4096);
        final List<Integer> oldValues = values(1);
        final List<Integer> newValues = values(3);
        final HashMapPalette<Integer> oracleFrom = new HashMapPalette<>(0, oldValues);
        final HashMapPalette<Integer> oracleTo = new HashMapPalette<>(5, newValues);
        final int[] oracleInts = PalettedContainer.reencodeContents(uniform, oracleFrom, oracleTo);
        final SimpleBitStorage oracleStorage = new SimpleBitStorage(5, 4096, oracleInts);

        final CountingPalette<Integer> countedTo = new CountingPalette<>(new HashMapPalette<>(5, newValues));
        final SimpleBitStorage papoStorage = PalettedContainer.papoReencodedStorage(
            uniform, new HashMapPalette<>(0, oldValues), countedTo, 5, 4096);
        Assertions.assertArrayEquals(oracleStorage.getRaw(), papoStorage.getRaw());
        Assertions.assertEquals(1, countedTo.idForCalls, "uniform source = a single run");
    }
}
