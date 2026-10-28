package ca.spottedleaf.moonrise.patches.starlight.light;

import java.util.Random;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Papo start - batch 148 (R8): the compact uniform representation contract of
// SWMRNibbleArray - a nibble whose whole payload is 0 or 15 keeps both storage arrays
// null and carries the value in uniform fields following the same SWMR discipline.
// Value-equality everywhere (readers, vanilla conversion, save state materialises back
// so the on-disk format is byte-identical), first writes materialise around the
// uniform value, and publish deflates settled arrays. Verified by contract tests plus
// a randomized differential against a materialised-array mirror model.
public class PapoSWMRUniformNibbleTest {

    private static int nibbleOf(final byte[] data, final int index) {
        return (data[index >>> 1] >>> ((index & 1) << 2)) & 0xF;
    }

    @Test
    void setFullIsUniformUntilWritten() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        Assertions.assertTrue(nibble.isInitialisedUpdating());
        for (final int i : new int[]{0, 1, 123, 2047, 2048, 4095}) {
            Assertions.assertEquals(15, nibble.getUpdating(i), "updating@" + i);
        }
        Assertions.assertTrue(nibble.updateVisible());
        for (final int i : new int[]{0, 5, 4095}) {
            Assertions.assertEquals(15, nibble.getVisible(i), "visible@" + i);
        }
        // first write materialises AROUND the uniform value
        nibble.set(100, 3);
        Assertions.assertEquals(3, nibble.getUpdating(100));
        Assertions.assertEquals(15, nibble.getUpdating(99));
        Assertions.assertEquals(15, nibble.getUpdating(101));
        Assertions.assertEquals(15, nibble.getUpdating(0));
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(3, nibble.getVisible(100));
        Assertions.assertEquals(15, nibble.getVisible(101));
    }

    @Test
    void uniformFlipPublishesWithoutStateChange() {
        // setFull then setZero on a clean uniform nibble must publish (state alone does
        // not change on a flip - the dirty flag carries the publication)
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(15, nibble.getVisible(7));
        nibble.setZero();
        Assertions.assertTrue(nibble.isDirty(), "value flip must mark dirty");
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(0, nibble.getVisible(7));
    }

    @Test
    void setZeroUniformMatchesSaveSemantics() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setZero();
        Assertions.assertTrue(nibble.isInitialisedUpdating());
        Assertions.assertEquals(0, nibble.getUpdating(4095));
        Assertions.assertTrue(nibble.updateVisible());
        final SWMRNibbleArray.SaveState state = nibble.getSaveState();
        Assertions.assertNotNull(state);
        Assertions.assertNull(state.data, "all-zero maps to the existing UNINIT save rule");
        Assertions.assertEquals(1, state.state); // INIT_STATE_UNINIT
    }

    @Test
    void saveFormatMaterialisesUniformFull() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        Assertions.assertTrue(nibble.updateVisible());
        final SWMRNibbleArray.SaveState state = nibble.getSaveState();
        Assertions.assertNotNull(state);
        Assertions.assertNotNull(state.data, "uniform-full materialises on save (format unchanged)");
        Assertions.assertEquals(SWMRNibbleArray.ARRAY_SIZE, state.data.length);
        for (final byte b : state.data) {
            Assertions.assertEquals((byte)-1, b);
        }
        Assertions.assertEquals(2, state.state); // INIT_STATE_INIT
    }

    @Test
    void loadCanonicalisesUniformFull() {
        final byte[] full = new byte[SWMRNibbleArray.ARRAY_SIZE];
        java.util.Arrays.fill(full, (byte)-1);
        // disk-load shape (SaveUtil) and fromVanilla shape both canonicalise
        final SWMRNibbleArray a = new SWMRNibbleArray(full.clone(), 2);
        Assertions.assertEquals(15, a.getUpdating(0));
        Assertions.assertEquals(15, a.getVisible(4095));
        final SWMRNibbleArray b = SWMRNibbleArray.fromVanilla(new DataLayer(full.clone()));
        Assertions.assertEquals(15, b.getUpdating(2048));
        // ...and both then behave exactly like a materialised full nibble on write
        b.set(9, 1);
        Assertions.assertEquals(1, b.getUpdating(9));
        Assertions.assertEquals(15, b.getUpdating(10));
        // save round-trip stays byte-identical
        Assertions.assertTrue(b.updateVisible());
        final SWMRNibbleArray.SaveState state = b.getSaveState();
        Assertions.assertNotNull(state.data);
        Assertions.assertEquals(nibbleOf(state.data, 9), 1);
        Assertions.assertEquals(nibbleOf(state.data, 10), 15);
    }

    @Test
    void extrudeLowerFromUniformSources() {
        // uniform-full source extrudes to full
        final SWMRNibbleArray src = new SWMRNibbleArray();
        src.setNonNull();
        src.setFull();
        final SWMRNibbleArray dst = new SWMRNibbleArray();
        dst.setNonNull();
        dst.extrudeLower(src);
        Assertions.assertTrue(dst.isInitialisedUpdating());
        Assertions.assertEquals(15, dst.getUpdating(0));
        Assertions.assertEquals(15, dst.getUpdating(4095));
        // UNINIT source extrudes to uninitialised
        final SWMRNibbleArray uninitSrc = new SWMRNibbleArray();
        final SWMRNibbleArray dst2 = new SWMRNibbleArray();
        dst2.setNonNull();
        dst2.extrudeLower(uninitSrc);
        Assertions.assertTrue(dst2.isUninitialisedUpdating());
        // uniform-zero initialised source extrudes to initialised zero
        final SWMRNibbleArray zeroSrc = new SWMRNibbleArray();
        zeroSrc.setNonNull();
        zeroSrc.setZero();
        final SWMRNibbleArray dst3 = new SWMRNibbleArray();
        dst3.setNonNull();
        dst3.extrudeLower(zeroSrc);
        Assertions.assertTrue(dst3.isInitialisedUpdating());
        Assertions.assertEquals(0, dst3.getUpdating(123));
    }

    @Test
    void setAfterUnpublishedUniformFlipMaterialises() {
        // a uniform flip leaves updatingDirty set with null storage - set() and
        // extrudeLower() must materialise around the uniform value instead of
        // writing through a null array
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        nibble.set(100, 3);
        Assertions.assertEquals(3, nibble.getUpdating(100));
        Assertions.assertEquals(15, nibble.getUpdating(99));
        Assertions.assertEquals(15, nibble.getUpdating(101));
        Assertions.assertEquals(15, nibble.getUpdating(4095));
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(3, nibble.getVisible(100));
        Assertions.assertEquals(15, nibble.getVisible(101));
        // dirty zero-flip (full -> zero without publish) materialises zeros
        final SWMRNibbleArray flip = new SWMRNibbleArray();
        flip.setNonNull();
        flip.setFull();
        flip.setZero();
        flip.set(7, 5);
        Assertions.assertEquals(5, flip.getUpdating(7));
        Assertions.assertEquals(0, flip.getUpdating(8));
        Assertions.assertEquals(0, flip.getUpdating(4095));
        // extrude into a nibble sitting on an unpublished flip
        final SWMRNibbleArray src = new SWMRNibbleArray();
        src.setNonNull();
        src.set(1, 9);
        final SWMRNibbleArray dst = new SWMRNibbleArray();
        dst.setNonNull();
        dst.setFull();
        dst.extrudeLower(src);
        Assertions.assertEquals(9, dst.getUpdating(1));
        Assertions.assertEquals(9, dst.getUpdating(1 + 256)); // replicated one layer up
        Assertions.assertEquals(0, dst.getUpdating(0));
    }

    @Test
    void hiddenUniformRoundTrip() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        nibble.setHidden();
        Assertions.assertTrue(nibble.isHiddenUpdating());
        // setHidden only touches the updating side - the visible view still reflects
        // the last published state (here UNINIT -> empty layer), exactly like the
        // array form
        final DataLayer staleVisible = nibble.toVanillaNibble();
        Assertions.assertNotNull(staleVisible);
        Assertions.assertEquals(0, nibbleOf(staleVisible.getData(), 0));
        Assertions.assertEquals(0, nibbleOf(staleVisible.getData(), 4095));
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertTrue(nibble.isHiddenVisible());
        Assertions.assertNull(nibble.toVanillaNibble());
        // hidden + non-zero payload keeps its data in the save format (state stays HIDDEN)
        final SWMRNibbleArray.SaveState hiddenState = nibble.getSaveState();
        Assertions.assertNotNull(hiddenState);
        Assertions.assertNotNull(hiddenState.data);
        Assertions.assertEquals(3, hiddenState.state); // INIT_STATE_HIDDEN
        for (final byte b : hiddenState.data) {
            Assertions.assertEquals((byte)-1, b);
        }
        // unhide and check value survival
        nibble.setNonNull();
        Assertions.assertEquals(15, nibble.getUpdating(77));
    }

    @Test
    void vanillaConversionMaterialisesUniform() {
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        Assertions.assertTrue(nibble.updateVisible());
        final DataLayer layer = nibble.toVanillaNibble();
        Assertions.assertNotNull(layer);
        final byte[] data = layer.getData();
        for (int i = 0; i < 4096; i++) {
            Assertions.assertEquals(15, nibbleOf(data, i), "@" + i);
        }
    }

    @Test
    void publishDeflatesSettledArrays() {
        // materialise real mixed data, then clear it back to zero: the publish must
        // deflate the settled payload (observable through save semantics and reads)
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.set(5, 9);
        nibble.set(2000, 7);
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(9, nibble.getVisible(5));
        // now write zeros everywhere via the set() path
        for (int i = 0; i < 4096; i++) {
            nibble.set(i, 0);
        }
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertEquals(0, nibble.getVisible(5));
        final SWMRNibbleArray.SaveState state = nibble.getSaveState();
        Assertions.assertNull(state.data, "settled-to-zero payload deflates to the UNINIT save rule");
        // and writes still work after deflation
        nibble.set(300, 4);
        Assertions.assertEquals(4, nibble.getUpdating(300));
        Assertions.assertEquals(0, nibble.getUpdating(301));
    }

    @Test
    void randomizedDifferentialAgainstMaterialisedModel() {
        final Random random = new Random(0xC0FFEE);
        for (int round = 0; round < 200; round++) {
            final SWMRNibbleArray nibble = new SWMRNibbleArray();
            // model = materialised mirror: null array = all zero, per today's semantics
            byte[] updating = null;
            byte[] visible = null;
            for (int op = 0; op < 500; op++) {
                final int kind = random.nextInt(100);
                if (kind < 45) {
                    final int index = random.nextInt(4096);
                    final int value = random.nextInt(16);
                    nibble.set(index, value);
                    if (updating == null) {
                        updating = new byte[SWMRNibbleArray.ARRAY_SIZE];
                    }
                    final int shift = (index & 1) << 2;
                    updating[index >>> 1] = (byte)((updating[index >>> 1] & (0xF0 >>> shift)) | (value << shift));
                } else if (kind < 60) {
                    nibble.setFull();
                    updating = new byte[SWMRNibbleArray.ARRAY_SIZE];
                    java.util.Arrays.fill(updating, (byte)-1);
                } else if (kind < 70) {
                    nibble.setZero();
                    updating = new byte[SWMRNibbleArray.ARRAY_SIZE];
                } else if (kind < 85) {
                    if (nibble.updateVisible()) {
                        visible = updating == null ? null : updating.clone();
                    }
                } else {
                    // reads: updating view and last-published visible view
                    final int index = random.nextInt(4096);
                    Assertions.assertEquals(
                        updating == null ? 0 : nibbleOf(updating, index),
                        nibble.getUpdating(index),
                        "updating round " + round + " op " + op + " @" + index);
                    Assertions.assertEquals(
                        visible == null ? 0 : nibbleOf(visible, index),
                        nibble.getVisible(index),
                        "visible round " + round + " op " + op + " @" + index);
                }
            }
            // final full sweep on both views
            for (int index = 0; index < 4096; index += 17) {
                Assertions.assertEquals(updating == null ? 0 : nibbleOf(updating, index), nibble.getUpdating(index));
                Assertions.assertEquals(visible == null ? 0 : nibbleOf(visible, index), nibble.getVisible(index));
            }
        }
    }
}
// Papo end - batch 148 (R8)
