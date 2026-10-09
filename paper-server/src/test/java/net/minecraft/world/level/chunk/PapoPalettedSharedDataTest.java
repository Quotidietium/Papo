package net.minecraft.world.level.chunk;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import net.minecraft.core.IdMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.ZeroBitStorage;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 147 (R7): the shared-single-value-Data contract of PalettedContainer
// (a preset-free constant container's data field IS the per-strategy canonical instance;
// growth detaches into private data without touching any sharer; the in-place-mutating
// buffer-read path is forced onto private state; unregistered values never share).
// Uses a synthetic IdMap strategy so no vanilla bootstrap is required.
public class PapoPalettedSharedDataTest {

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

    private static Strategy<String> strategy() {
        return Strategy.createForBiomes(IDS); // bitsPerAxis 2 -> entryCount 64
    }

    @Test
    void generationPathSharesCanonicalData() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> c1 = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer<String> c2 = new PalettedContainer<>("a", strategy, null);
        Assertions.assertEquals("a", c1.get(0, 0, 0));
        Assertions.assertEquals("a", c2.get(3, 3, 3));
        Assertions.assertSame(c1.data, c2.data, "constant containers must share the canonical Data");
        Assertions.assertTrue(c1.data.papoShared);
        Assertions.assertSame(c1.data.storage(), ZeroBitStorage.papoShared(64));
        // a distinct value gets its own canonical instance; a distinct strategy its own cache
        final PalettedContainer<String> cb = new PalettedContainer<>("b", strategy, null);
        Assertions.assertNotSame(c1.data, cb.data);
        Assertions.assertEquals("b", cb.get(0, 0, 0));
        final PalettedContainer<String> c3 = new PalettedContainer<>("a", strategy(), null);
        Assertions.assertNotSame(c1.data, c3.data, "per-strategy cache must not cross strategies");
    }

    @Test
    void sameValueWriteKeepsSharedData() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> c1 = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer.Data<String> before = c1.data;
        c1.set(1, 2, 3, "a"); // reference-idempotent idFor + ZeroBitStorage no-op set
        Assertions.assertSame(before, c1.data, "idempotent write must not detach or mutate");
        Assertions.assertEquals("a", c1.get(1, 2, 3));
    }

    @Test
    void growthDetachesWithoutTouchingSharers() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> c1 = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer<String> c2 = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer.Data<String> shared = c1.data;
        c1.set(1, 2, 3, "b");
        Assertions.assertNotSame(shared, c1.data, "growth replaces the owning container's data");
        Assertions.assertFalse(c1.data.papoShared);
        Assertions.assertEquals("b", c1.get(1, 2, 3));
        Assertions.assertEquals("a", c1.get(0, 0, 0));
        Assertions.assertSame(shared, c2.data, "sharers keep the canonical data");
        Assertions.assertEquals("a", c2.get(1, 2, 3));
        // the canonical instance stays healthy after the detach
        final PalettedContainer<String> c3 = new PalettedContainer<>("a", strategy, null);
        Assertions.assertSame(shared, c3.data);
        Assertions.assertEquals("a", c3.get(1, 2, 3));
    }

    @Test
    void codecDecodeCanonicalizesSingleValue() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> generated = new PalettedContainer<>("a", strategy, null);
        final CompoundTag tag = new CompoundTag();
        final ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("a"));
        tag.put("palette", palette);
        final Codec<PalettedContainer<String>> codec = PalettedContainer.codecRW(Codec.STRING, strategy, "a", null);
        final PalettedContainer<String> decoded = codec.parse(NbtOps.INSTANCE, tag)
            .getOrThrow(s -> new AssertionError(s));
        Assertions.assertEquals("a", decoded.get(2, 1, 0));
        Assertions.assertSame(generated.data, decoded.data, "disk-decoded single value must share the canonical Data");
        // byte-identical serialization is unaffected: pack both and compare
        Assertions.assertEquals(generated.pack(strategy), decoded.pack(strategy));
    }

    @Test
    void copiesAreIndependent() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> source = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer.Data<String> shared = source.data;
        final PalettedContainer<String> copy = source.copy();
        copy.set(0, 0, 0, "b");
        Assertions.assertEquals("b", copy.get(0, 0, 0));
        Assertions.assertEquals("a", source.get(0, 0, 0));
        Assertions.assertSame(shared, source.data);
        // recreate() keeps sharing the canonical instance
        Assertions.assertSame(shared, source.recreate().data);
    }

    @Test
    void unregisteredValuesNeverShare() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> u1 = new PalettedContainer<>("zzz", strategy, null);
        final PalettedContainer<String> u2 = new PalettedContainer<>("zzz", strategy, null);
        Assertions.assertNotSame(u1.data, u2.data, "values outside the registry map stay per-container");
        Assertions.assertFalse(u1.data.papoShared);
        Assertions.assertEquals("zzz", u1.get(0, 0, 0));
    }

    @Test
    void bufferReadForcesPrivateData() {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> c1 = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer.Data<String> shared = c1.data;
        // a copy carries the shared palette inside private data - the guard must fire for both shapes
        final PalettedContainer<String> copyOfShared = new PalettedContainer<>("a", strategy, null).copy();
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(0); // single-value configuration
        buf.writeVarInt(IDS.getId("b"));
        c1.read(buf);
        Assertions.assertNotSame(shared, c1.data, "buffer read must not mutate shared data in place");
        Assertions.assertEquals("b", c1.get(0, 0, 0));
        // sharers of the old canonical instance are unaffected
        final PalettedContainer<String> c2 = new PalettedContainer<>("a", strategy, null);
        Assertions.assertSame(shared, c2.data);
        Assertions.assertEquals("a", c2.get(0, 0, 0));
        // the copy-with-shared-palette shape detaches too (palette-level guard)
        final FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        buf2.writeByte(0);
        buf2.writeVarInt(IDS.getId("a"));
        Assertions.assertTrue(copyOfShared.data.palette() instanceof SingleValuePalette<?> paletteBefore
            && paletteBefore.papoShared);
        copyOfShared.read(buf2);
        Assertions.assertFalse(copyOfShared.data.papoShared);
        Assertions.assertFalse(copyOfShared.data.palette() instanceof SingleValuePalette<?> paletteAfter
            && paletteAfter.papoShared, "read must not rewrite the shared palette in place");
        Assertions.assertEquals("a", copyOfShared.get(0, 0, 0));
        Assertions.assertEquals("a", c2.get(0, 0, 0));
    }

    @Test
    void concurrentCreationConverges() throws InterruptedException {
        final Strategy<String> strategy = strategy();
        final PalettedContainer<String> warm = new PalettedContainer<>("a", strategy, null);
        final PalettedContainer.Data<String> expected = warm.data;
        final int threads = 8;
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        final List<PalettedContainer<String>> out = new java.util.ArrayList<>(threads);
        final List<Thread> ts = new java.util.ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            final Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                out.add(new PalettedContainer<>("a", strategy, null));
            }, "papo-pcd-" + i);
            ts.add(t);
            t.start();
        }
        ready.await();
        go.countDown();
        for (final Thread t : ts) {
            t.join(10_000);
        }
        Assertions.assertEquals(threads, out.size());
        for (final PalettedContainer<String> c : out) {
            Assertions.assertSame(expected, c.data);
        }
    }
}
// Papo end - batch 147 (R7)
