package ca.spottedleaf.moonrise.patches.starlight.light;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 158 (R18): the frozen-shell canonical sharing contract of
// SWMRNibbleArray. The two birth shapes that dominate the steady state (NULL-state and
// UNINIT-state shells with null storage, born by getFilledEmptyLight / SaveUtil
// null-data sections / fromVanilla) are immutable singletons. The read surface is
// value-identical to a fresh private birth, every mutator throws a loud identity
// canary instead of corrupting every chunk sharing the shell, and papoClonePrivate
// yields a freely mutable instance with exactly the fresh-birth semantics.
public class PapoSWMRSharedNibbleTest {

    private static final SWMRNibbleArray[] SHARED = {
        SWMRNibbleArray.PAPO_SHARED_NULL,
        SWMRNibbleArray.PAPO_SHARED_UNINIT,
        SWMRNibbleArray.PAPO_SHARED_INIT_FULL,
    };

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap(); // StarLightEngine's statics touch Blocks.AIR
    }

    @Test
    void sharedSingletonsMatchBirthSemantics() {
        // NULL shell reads exactly like new SWMRNibbleArray(null, true)
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_NULL.papoIsShared());
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_NULL.isNullNibbleUpdating());
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_NULL.isNullNibbleVisible());
        Assertions.assertNull(SWMRNibbleArray.PAPO_SHARED_NULL.getSaveState());
        Assertions.assertNull(SWMRNibbleArray.PAPO_SHARED_NULL.toVanillaNibble());

        // UNINIT shell reads exactly like new SWMRNibbleArray()
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_UNINIT.papoIsShared());
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_UNINIT.isUninitialisedUpdating());
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_UNINIT.isUninitialisedVisible());
        final SWMRNibbleArray.SaveState uninitState = SWMRNibbleArray.PAPO_SHARED_UNINIT.getSaveState();
        Assertions.assertNotNull(uninitState);
        Assertions.assertNull(uninitState.data);
        Assertions.assertEquals(1, uninitState.state); // INIT_STATE_UNINIT
        final DataLayer layer = SWMRNibbleArray.PAPO_SHARED_UNINIT.toVanillaNibble();
        Assertions.assertNotNull(layer);
        Assertions.assertTrue(layer.isEmpty());

        for (final SWMRNibbleArray shared : SHARED) {
            Assertions.assertFalse(shared.isDirty());
            Assertions.assertFalse(shared.updateVisible(), "clean shell publishes nothing");
        }
        for (final SWMRNibbleArray shared : new SWMRNibbleArray[]{SWMRNibbleArray.PAPO_SHARED_NULL, SWMRNibbleArray.PAPO_SHARED_UNINIT}) {
            Assertions.assertEquals(0, shared.getUpdating(123));
            Assertions.assertEquals(0, shared.getVisible(123));
            Assertions.assertEquals(0, shared.getUpdating(1, 2, 3));
            Assertions.assertEquals(0, shared.getVisible(1, 2, 3));
        }
    }

    @Test
    void mutatorsThrowLoudlyOnSharedShells() {
        for (final SWMRNibbleArray shared : SHARED) {
            Assertions.assertThrows(IllegalStateException.class, shared::setFull);
            Assertions.assertThrows(IllegalStateException.class, shared::setZero);
            Assertions.assertThrows(IllegalStateException.class, shared::setNonNull);
            Assertions.assertThrows(IllegalStateException.class, shared::setNull);
            Assertions.assertThrows(IllegalStateException.class, shared::setUninitialised);
            Assertions.assertThrows(IllegalStateException.class, shared::setHidden);
            Assertions.assertThrows(IllegalStateException.class, () -> shared.set(0, 1));
            Assertions.assertThrows(IllegalStateException.class, () -> shared.set(1, 2, 3, 4));
            Assertions.assertThrows(IllegalStateException.class, () -> shared.extrudeLower(new SWMRNibbleArray()));

            // the failed attempts left the singleton untouched
            Assertions.assertTrue(shared.papoIsShared());
            Assertions.assertFalse(shared.isDirty());
        }
        Assertions.assertEquals(0, SWMRNibbleArray.PAPO_SHARED_NULL.getUpdating(0));
        Assertions.assertEquals(0, SWMRNibbleArray.PAPO_SHARED_UNINIT.getUpdating(0));
        Assertions.assertEquals(15, SWMRNibbleArray.PAPO_SHARED_INIT_FULL.getUpdating(0));
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_NULL.isNullNibbleUpdating());
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_UNINIT.isUninitialisedUpdating());
    }

    @Test
    void clonePrivateIsBirthIdenticalAndSingletonsStayFrozen() {
        final SWMRNibbleArray nullCopy = SWMRNibbleArray.PAPO_SHARED_NULL.papoClonePrivate();
        Assertions.assertNotSame(SWMRNibbleArray.PAPO_SHARED_NULL, nullCopy);
        Assertions.assertFalse(nullCopy.papoIsShared());
        Assertions.assertTrue(nullCopy.isNullNibbleUpdating());
        // the clone takes the full mutation choreography the engine applies
        nullCopy.setNonNull();
        nullCopy.setFull();
        Assertions.assertTrue(nullCopy.isInitialisedUpdating());
        Assertions.assertEquals(15, nullCopy.getUpdating(5));
        Assertions.assertTrue(nullCopy.updateVisible());
        Assertions.assertEquals(15, nullCopy.getVisible(5));
        // ...while the singleton stays frozen
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_NULL.isNullNibbleUpdating());
        Assertions.assertNull(SWMRNibbleArray.PAPO_SHARED_NULL.getSaveState());

        final SWMRNibbleArray uninitCopy = SWMRNibbleArray.PAPO_SHARED_UNINIT.papoClonePrivate();
        Assertions.assertFalse(uninitCopy.papoIsShared());
        Assertions.assertTrue(uninitCopy.isUninitialisedUpdating());
        uninitCopy.setNonNull();
        uninitCopy.set(3, 7);
        Assertions.assertEquals(7, uninitCopy.getUpdating(3));
        Assertions.assertTrue(uninitCopy.updateVisible(), "mutation must publish");
        final SWMRNibbleArray.SaveState saved = uninitCopy.getSaveState();
        Assertions.assertNotNull(saved);
        Assertions.assertNotNull(saved.data);
        Assertions.assertEquals(2, saved.state); // INIT_STATE_INIT
        Assertions.assertTrue(SWMRNibbleArray.PAPO_SHARED_UNINIT.isUninitialisedUpdating());
        Assertions.assertNull(SWMRNibbleArray.PAPO_SHARED_UNINIT.getSaveState().data);
    }

    @Test
    void papoFromSaveMapsFrozenStatesAndKeepsValidation() {
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_NULL, SWMRNibbleArray.papoFromSave(null, 0));
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_UNINIT, SWMRNibbleArray.papoFromSave(null, 1));

        final byte[] payload = new byte[SWMRNibbleArray.ARRAY_SIZE];
        payload[0] = 0x25; // nibbles 5 | 2<<4
        final SWMRNibbleArray loaded = SWMRNibbleArray.papoFromSave(payload, 2);
        Assertions.assertFalse(loaded.papoIsShared());
        Assertions.assertTrue(loaded.isInitialisedUpdating());
        Assertions.assertEquals(5, loaded.getUpdating(0));
        Assertions.assertEquals(2, loaded.getUpdating(1));

        // save round-trip of the full-bright singleton: save materialises all-0xFF, load
        // maps it straight back to the shared shell instead of a private clone
        final byte[] full = new byte[SWMRNibbleArray.ARRAY_SIZE];
        java.util.Arrays.fill(full, (byte)-1);
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_INIT_FULL, SWMRNibbleArray.papoFromSave(full, 2));
        // state-mismatched all-full keeps constructor semantics (state preserved)
        final SWMRNibbleArray hidden = SWMRNibbleArray.papoFromSave(full, 3);
        Assertions.assertFalse(hidden.papoIsShared());
        Assertions.assertTrue(hidden.isHiddenUpdating());

        // malformed null-data INIT keeps today's loud constructor validation
        Assertions.assertThrows(IllegalArgumentException.class, () -> SWMRNibbleArray.papoFromSave(null, 2));
        Assertions.assertThrows(IllegalArgumentException.class, () -> SWMRNibbleArray.papoFromSave(null, 3));
    }

    @Test
    void fromVanillaFrozenPathsShare() {
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_NULL, SWMRNibbleArray.fromVanilla(null));
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_UNINIT, SWMRNibbleArray.fromVanilla(new DataLayer()));
        final DataLayer payload = new DataLayer(new byte[SWMRNibbleArray.ARRAY_SIZE]);
        Assertions.assertFalse(SWMRNibbleArray.fromVanilla(payload).papoIsShared());
    }

    @Test
    void getFilledEmptyLightBirthsSharedNullShells() {
        // overworld geometry: min -64, height 384 -> sections -4..19 -> light sections -5..20 = 26
        final LevelHeightAccessor world = LevelHeightAccessor.create(-64, 384);
        final SWMRNibbleArray[] nibbles = StarLightEngine.getFilledEmptyLight(world);
        Assertions.assertEquals(26, nibbles.length);
        for (final SWMRNibbleArray nibble : nibbles) {
            Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_NULL, nibble);
        }
    }

    @Test
    void settleSwapCollapsesPromotionOnlyShells() {
        // setNonNull promotion (NULL -> UNINIT, no payload) publishes to a shape that is
        // exactly the UNINIT singleton
        final SWMRNibbleArray nibble = new SWMRNibbleArray(null, true);
        nibble.setNonNull();
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_UNINIT, nibble.papoFrozenEquivalent());

        // setNull de-publication settles to the NULL singleton
        final SWMRNibbleArray uninit = new SWMRNibbleArray();
        uninit.setNonNull();
        uninit.setNull();
        Assertions.assertTrue(uninit.updateVisible());
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_NULL, uninit.papoFrozenEquivalent());
    }

    @Test
    void settleSwapCollapsesFullBrightAfterDeflate() {
        // setFull then publish deflates to uniform-15 INIT - exactly the shared singleton
        final SWMRNibbleArray nibble = new SWMRNibbleArray();
        nibble.setNonNull();
        nibble.setFull();
        Assertions.assertTrue(nibble.updateVisible());
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_INIT_FULL, nibble.papoFrozenEquivalent());
    }

    @Test
    void predicateDistinctAndPayloadStatesStayPrivate() {
        // INIT-uniform-0: value-identical to UNINIT for readers but isInitialised*() is
        // true - must NOT collapse to the UNINIT singleton
        final SWMRNibbleArray zero = new SWMRNibbleArray();
        zero.setNonNull();
        zero.setZero();
        Assertions.assertTrue(zero.updateVisible());
        Assertions.assertNull(zero.papoFrozenEquivalent());
        Assertions.assertTrue(zero.isInitialisedVisible());

        // real payload stays private
        final byte[] payload = new byte[SWMRNibbleArray.ARRAY_SIZE];
        payload[0] = 0x21;
        final SWMRNibbleArray loaded = new SWMRNibbleArray(payload, 2);
        Assertions.assertNull(loaded.papoFrozenEquivalent());

        // already-shared singletons are their own equivalence
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_NULL, SWMRNibbleArray.PAPO_SHARED_NULL.papoFrozenEquivalent());
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_UNINIT, SWMRNibbleArray.PAPO_SHARED_UNINIT.papoFrozenEquivalent());
        Assertions.assertSame(SWMRNibbleArray.PAPO_SHARED_INIT_FULL, SWMRNibbleArray.PAPO_SHARED_INIT_FULL.papoFrozenEquivalent());
    }

    @Test
    void initFullSingletonContract() {
        final SWMRNibbleArray full = SWMRNibbleArray.PAPO_SHARED_INIT_FULL;
        Assertions.assertTrue(full.papoIsShared());
        Assertions.assertTrue(full.isInitialisedUpdating());
        Assertions.assertTrue(full.isInitialisedVisible());
        Assertions.assertEquals(15, full.getUpdating(0));
        Assertions.assertEquals(15, full.getVisible(4095));
        final SWMRNibbleArray.SaveState saved = full.getSaveState();
        Assertions.assertNotNull(saved);
        Assertions.assertNotNull(saved.data, "save materialises the full payload");
        Assertions.assertEquals(2, saved.state);
        for (int i = 0; i < saved.data.length; ++i) {
            Assertions.assertEquals(-1, saved.data[i], "all-0xFF at " + i);
        }
        final DataLayer vanilla = full.toVanillaNibble();
        Assertions.assertNotNull(vanilla);
        Assertions.assertFalse(vanilla.isEmpty());

        // mutators throw, clone is a value-identical private
        Assertions.assertThrows(IllegalStateException.class, full::setZero);
        Assertions.assertThrows(IllegalStateException.class, () -> full.set(0, 1));
        final SWMRNibbleArray copy = full.papoClonePrivate();
        Assertions.assertFalse(copy.papoIsShared());
        Assertions.assertTrue(copy.isInitialisedUpdating());
        Assertions.assertEquals(15, copy.getUpdating(7));
        copy.set(7, 3); // first write materialises around the uniform
        Assertions.assertEquals(3, copy.getUpdating(7));
        Assertions.assertEquals(15, copy.getUpdating(8));
        Assertions.assertTrue(full.isInitialisedUpdating(), "singleton untouched");
    }
}
// Papo end - batch 158 (R18)
