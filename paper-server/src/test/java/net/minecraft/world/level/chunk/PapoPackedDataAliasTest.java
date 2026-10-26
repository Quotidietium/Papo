package net.minecraft.world.level.chunk;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 172 (R31): PackedData.storage carries the raw long[] and the NBT
// decode path aliases the LongArrayTag's backing array (single decode per tag on the
// parse path), replacing the mandatory LongStream.toArray() second materialization.
// Proofs: full-content round-trip through NbtOps (values identical, wire form identical
// to the pre-change codec's), alias identity (the retained SimpleBitStorage.getRaw()
// IS the tag's array - no copy), the uniform/ZeroBitStorage branch, and the non-NBT
// ops fallback (JsonOps) still decodes correctly through getLongStream+toArray.
public class PapoPackedDataAliasTest {

    private static Strategy<BlockState> strategy;
    private static com.mojang.serialization.Codec<PalettedContainer<BlockState>> codec;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        codec = PalettedContainer.codecRW(BlockState.CODEC, strategy, Blocks.AIR.defaultBlockState(), null);
    }

    private static PalettedContainer<BlockState> mixedContainer() {
        final PalettedContainer<BlockState> container = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy);
        container.set(0, 0, 0, Blocks.STONE.defaultBlockState());
        container.set(1, 0, 0, Blocks.DIRT.defaultBlockState());
        container.set(2, 0, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        container.set(3, 0, 0, Blocks.STONE.defaultBlockState());
        container.set(15, 15, 15, Blocks.WATER.defaultBlockState());
        return container;
    }

    private static Object storageOf(final PalettedContainer<BlockState> container) {
        try {
            final Field field = PalettedContainer.class.getDeclaredField("data");
            field.setAccessible(true);
            final Object data = field.get(container);
            final Field storageField = data.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            return storageField.get(data);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Papo - compat fix (batch 206): the R31 alias was removed. Vanilla's decode path
    // (getLongStream().toArray()) always produced an isolated copy; retaining the tag's own
    // array aliased the decoded container to tag storage, so an NMS plugin holding the tag
    // and the live chunk could corrupt each other. The contract is now value-identical AND
    // isolated - this test pins the restored copy semantics.
    @Test
    void nbtRoundTripIsValueIdenticalAndIsolatedFromTheTagArray() {
        final PalettedContainer<BlockState> source = mixedContainer();
        final Tag encoded = codec.encodeStart(NbtOps.INSTANCE, source).getOrThrow();
        final CompoundTag packed = (CompoundTag) encoded;
        Assertions.assertTrue(packed.get("data") instanceof LongArrayTag);
        final long[] tagArray = ((LongArrayTag) packed.get("data")).getAsLongArray();

        final PalettedContainer<BlockState> decoded = codec.parse(NbtOps.INSTANCE, packed).getOrThrow();

        for (int i = 0; i < 4096; ++i) {
            Assertions.assertEquals(source.get(i), decoded.get(i), "cell " + i);
        }
        // Papo - compat fix (batch 206): the decoded storage must NOT alias the tag's array.
        // Vanilla (getLongStream().toArray()) always isolated; the R31 alias let a retained
        // LongArrayTag and the live container corrupt each other through the shared long[].
        final Object storage = storageOf(decoded);
        Assertions.assertTrue(storage instanceof net.minecraft.util.SimpleBitStorage);
        Assertions.assertNotSame(tagArray, ((net.minecraft.util.SimpleBitStorage) storage).getRaw(), "NBT decode must not alias the tag array");
        Assertions.assertArrayEquals(tagArray, ((net.minecraft.util.SimpleBitStorage) storage).getRaw(), "value identity is preserved");
    }

    @Test
    void reEncodingProducesIdenticalWireForm() {
        final PalettedContainer<BlockState> source = mixedContainer();
        final Tag first = codec.encodeStart(NbtOps.INSTANCE, source).getOrThrow();
        final PalettedContainer<BlockState> decoded = codec.parse(NbtOps.INSTANCE, first).getOrThrow();
        final Tag second = codec.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow();
        Assertions.assertEquals(((CompoundTag) first).toString(), ((CompoundTag) second).toString(), "round-trip wire form must be identical");
    }

    @Test
    void uniformContainerDecodesToSharedZeroStorage() {
        final PalettedContainer<BlockState> uniform = new PalettedContainer<>(Blocks.STONE.defaultBlockState(), strategy);
        final Tag encoded = codec.encodeStart(NbtOps.INSTANCE, uniform).getOrThrow();
        final PalettedContainer<BlockState> decoded = codec.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), decoded.get(0));
        Assertions.assertEquals(Blocks.STONE.defaultBlockState(), decoded.get(4095));
        Assertions.assertTrue(storageOf(decoded) instanceof net.minecraft.util.ZeroBitStorage);
    }

    @Test
    void nonNbtOpsFallbackStillDecodes() {
        final PalettedContainer<BlockState> source = mixedContainer();
        final com.google.gson.JsonElement json = codec.encodeStart(JsonOps.INSTANCE, source).getOrThrow();
        final PalettedContainer<BlockState> decoded = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        for (int i = 0; i < 4096; ++i) {
            Assertions.assertEquals(source.get(i), decoded.get(i), "json cell " + i);
        }
    }
}
