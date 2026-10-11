package net.minecraft.world.level.chunk;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import java.util.List;
import net.minecraft.core.IdMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 183 (R42): PAP0_LONG_ARRAY.encode routes the NbtOps singleton
// directly to a cloned LongArrayTag instead of Arrays.stream(input) ->
// createLongList -> toArray(). The contract asserted here: the fast path is
// content-identical to the exact vanilla stream path (differential oracle = a
// DynamicOps proxy that is != NbtOps.INSTANCE but delegates every method to it,
// so its encode runs the generic createLongList branch), round-trips through the
// codec preserve every cell, the encoded tag is a snapshot immune to later
// container mutation, and single-value containers still omit the data field.
// Synthetic IdMap strategy so no vanilla bootstrap is required (batch 147 pattern).
public class PapoLongArrayEncodeFastPathTest {

    private static final IdMap<String> IDS = new IdMap<>() {
        @Override
        public int getId(final String value) {
            return switch (value) {
                case "a" -> 0;
                case "b" -> 1;
                default -> -1;
            };
        }

        @Override
        public String byId(final int id) {
            return id == 0 ? "a" : "b";
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return List.of("a", "b").iterator();
        }
    };

    private static final Codec<PalettedContainer<String>> CODEC =
        PalettedContainer.codecRW(Codec.STRING, Strategy.createForBiomes(IDS), "a");

    private static PalettedContainer<String> mixedContainer() {
        final PalettedContainer<String> container = new PalettedContainer<>("a", Strategy.createForBiomes(IDS), null);
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                container.set(x, 1, z, "b"); // half the cells -> hash palette + packed data
            }
        }
        Assertions.assertEquals(16, Assertions.assertDoesNotThrow(() -> countB(container)));
        return container;
    }

    private static int countB(final PalettedContainer<String> container) {
        int n = 0;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    if (container.get(x, y, z).equals("b")) n++;
                }
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static DynamicOps<Tag> delegatingOps() {
        return (DynamicOps<Tag>) java.lang.reflect.Proxy.newProxyInstance(
            DynamicOps.class.getClassLoader(),
            new Class<?>[]{DynamicOps.class},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> "delegating-ops-proxy";
                    };
                }
                return method.invoke(NbtOps.INSTANCE, args);
            });
    }

    @Test
    void fastPathMatchesVanillaStreamPath() {
        final Tag fast = CODEC.encodeStart(NbtOps.INSTANCE, mixedContainer()).getOrThrow();
        final Tag oracle = CODEC.encodeStart(delegatingOps(), mixedContainer()).getOrThrow();

        Assertions.assertInstanceOf(CompoundTag.class, fast);
        Assertions.assertEquals(oracle, fast, "fast path must be content-identical to the vanilla stream path");
        Assertions.assertInstanceOf(LongArrayTag.class, ((CompoundTag)fast).get("data"));
    }

    @Test
    void roundTripPreservesEveryCell() {
        final PalettedContainer<String> original = mixedContainer();
        final Tag encoded = CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        final PalettedContainer<String> decoded =
            CODEC.decode(NbtOps.INSTANCE, encoded).getOrThrow().getFirst();

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    Assertions.assertEquals(original.get(x, y, z), decoded.get(x, y, z));
                }
            }
        }
    }

    @Test
    void encodedTagIsSnapshotOfContainer() {
        final PalettedContainer<String> container = mixedContainer();
        final CompoundTag encoded = (CompoundTag)CODEC.encodeStart(NbtOps.INSTANCE, container).getOrThrow();
        final long[] snapshot = ((LongArrayTag)encoded.get("data")).getAsLongArray().clone();

        container.set(0, 0, 0, "b"); // mutate after encoding
        Assertions.assertArrayEquals(snapshot, ((LongArrayTag)encoded.get("data")).getAsLongArray(),
            "encoded tag must be immune to post-encode container mutation");

        final CompoundTag reencoded = (CompoundTag)CODEC.encodeStart(NbtOps.INSTANCE, container).getOrThrow();
        Assertions.assertInstanceOf(LongArrayTag.class, reencoded.get("data"));
    }

    @Test
    void singleValueContainerStillOmitsDataField() {
        final PalettedContainer<String> constant = new PalettedContainer<>("a", Strategy.createForBiomes(IDS), null);
        final CompoundTag encoded = (CompoundTag)CODEC.encodeStart(NbtOps.INSTANCE, constant).getOrThrow();

        Assertions.assertFalse(encoded.contains("data"), "constant containers pack no storage array");
        Assertions.assertTrue(encoded.contains("palette"));
    }
}
