package ca.spottedleaf.moonrise.patches.chunk_system.level.entity;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.dfl.DefaultEntityLookup;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Papo start - batch 187 (R46): the hopper empty-query fast path contract.
//
// EntityLookup#papoHasNoEntitiesIn mirrors the chunk-iteration skeleton of the real
// getEntities(Class, Entity, AABB, List, Predicate) walk and answers "true = the real
// query would append nothing". The oracle differential below pins the load-bearing
// invariants directly on the storage the predicate reads (ChunkEntitySlices.allEntities
// per-section lists, with the same minY-2/maxY+2 clamped section range the query uses):
//   - predicate true  <=>  the chunk-level query leaves the output list empty,
//   - the predicate only flips when an entity is registered in a touched, FULL-status
//     chunk AND section range (margin isolation + non-FULL chunks + section colocation).
// Section granularity is the load-bearing case: players/mobs routinely share a hopper
// farm's chunk column at other heights (the R46 verification leg caught exactly that).
// Entities are Mockito mocks: add/removeEntity only touches ChunkSystemEntity interface
// setters (default stubs) and getId()/getType()/getBoundingBox() (stubbed). Mocking Entity
// itself still requires its statics (EntityDataSerializers -> ParticleTypes -> registries)
// to initialize, hence the @BeforeAll bootstrap - the suite must not depend on some other
// test class having bootstrapped first.
public class PapoHopperEmptyQueryFastPathTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    private static int nextEntityId = 0;

    private static ChunkEntitySlices freshSlices(final FullChunkStatus status) {
        return new ChunkEntitySlices(null, 0, 0, status, null, 0, 15);
    }

    private static Entity mockEntityAt(final double x, final double y, final double z) {
        final Entity entity = Mockito.mock(Entity.class);
        // EntityList keys on entity id - distinct ids keep every mock independently addable
        Mockito.doReturn(nextEntityId++).when(entity).getId();
        Mockito.doReturn(new AABB(x, y, z, x + 0.5, y + 0.5, z + 0.5)).when(entity).getBoundingBox();
        Mockito.doReturn(Mockito.mock(EntityType.class)).when(entity).getType();
        return entity;
    }

    private static int sectionOf(final double y) {
        return Mth.floor(y) >> 4;
    }

    @Test
    void chunkPredicateMatchesQueryOracle() {
        final ChunkEntitySlices slices = freshSlices(FullChunkStatus.FULL);
        final AABB box = new AABB(7.9, 7.9, 7.9, 8.6, 8.6, 8.6);

        // empty: predicate true and the real chunk-level query appends nothing
        Assertions.assertTrue(slices.papoHasNoEntitiesInSections(box));
        final List<Entity> out = new ArrayList<>();
        slices.getEntities((Entity) null, box, out, null);
        Assertions.assertTrue(out.isEmpty(), "empty chunk must yield an empty query result");

        // one registered entity in a touched section: predicate false, oracle finds it
        final Entity entity = mockEntityAt(8.0, 8.0, 8.0);
        Assertions.assertTrue(slices.addEntity(entity, sectionOf(8.0)));
        Assertions.assertFalse(slices.papoHasNoEntitiesInSections(box), "registered entity must flip the predicate");
        out.clear();
        slices.getEntities((Entity) null, box, out, null);
        Assertions.assertEquals(1, out.size(), "oracle query must find the registered entity");
        Assertions.assertSame(entity, out.get(0));

        // entity outside the box but in a touched SECTION keeps the predicate false
        // (conservative direction) while the oracle still restricts to the box - the fast
        // path may only skip when provably empty
        final Entity farAwaySameSection = mockEntityAt(64.0, 8.0, 8.0);
        Assertions.assertTrue(slices.addEntity(farAwaySameSection, sectionOf(8.0)));
        Assertions.assertFalse(slices.papoHasNoEntitiesInSections(box));
        out.clear();
        slices.getEntities((Entity) null, box, out, null);
        Assertions.assertEquals(1, out.size(), "box must still exclude the far-away entity");

        // removal restores emptiness for every path
        Assertions.assertTrue(slices.removeEntity(entity, sectionOf(8.0)));
        Assertions.assertTrue(slices.removeEntity(farAwaySameSection, sectionOf(8.0)));
        Assertions.assertTrue(slices.papoHasNoEntitiesInSections(box), "removals must restore the empty predicate");
        out.clear();
        slices.getEntities((Entity) null, box, out, null);
        Assertions.assertTrue(out.isEmpty());
    }

    @Test
    void sameChunkDifferentSectionStaysSkippable() {
        final ChunkEntitySlices slices = freshSlices(FullChunkStatus.FULL);
        // an entity at y~75 (section 4) in the SAME chunk column as a y~200 (section 12)
        // hopper box (x/z brackets stay at the entity; only the height varies) - the exact
        // colocation of a player above their farm
        final Entity colocated = mockEntityAt(8.0, 75.0, 8.0);
        Assertions.assertTrue(slices.addEntity(colocated, sectionOf(75.0)));
        final AABB hopperBox = new AABB(7.9, 199.9, 7.9, 8.6, 200.6, 8.6);
        Assertions.assertTrue(slices.papoHasNoEntitiesInSections(hopperBox),
            "entity in a different section of the same chunk must stay skippable");
        final List<Entity> out = new ArrayList<>();
        slices.getEntities((Entity) null, hopperBox, out, null);
        Assertions.assertTrue(out.isEmpty(), "oracle query must also find nothing at the hopper's height");

        // moving the box down to the entity's section flips the predicate and the oracle
        // finds the entity (per-axis boxes: x/z bracket the entity, y bracket is separate)
        final AABB lowBox = new AABB(7.9, 74.9, 7.9, 8.6, 75.6, 8.6);
        Assertions.assertFalse(slices.papoHasNoEntitiesInSections(lowBox));
        out.clear();
        slices.getEntities((Entity) null, lowBox, out, null);
        Assertions.assertEquals(1, out.size());
        Assertions.assertSame(colocated, out.get(0));
    }

    @Test
    void lookupPredicateOnlySeesTouchedFullChunks() {
        final DefaultEntityLookup lookup = new DefaultEntityLookup(null);
        final ChunkEntitySlices chunk0 = freshSlices(FullChunkStatus.FULL);
        lookup.addChunk(0, 0, chunk0);

        // box centred in chunk 0 with the ±2 chunk margin never reaches chunk 3
        final AABB boxInChunk0 = new AABB(7.9, 7.9, 7.9, 8.6, 8.6, 8.6);
        Assertions.assertTrue(lookup.papoHasNoEntitiesIn(boxInChunk0));

        // an entity in an untouched chunk must not flip the predicate for our box
        final ChunkEntitySlices chunk3 = new ChunkEntitySlices(null, 3, 0, FullChunkStatus.FULL, null, 0, 15);
        lookup.addChunk(3, 0, chunk3);
        Assertions.assertTrue(chunk3.addEntity(mockEntityAt(56.0, 8.0, 8.0), 0));
        Assertions.assertTrue(lookup.papoHasNoEntitiesIn(boxInChunk0), "entity outside the margin must be invisible");

        // registering an entity in the touched chunk AND section flips it
        Assertions.assertTrue(chunk0.addEntity(mockEntityAt(8.0, 8.0, 8.0), 0));
        Assertions.assertFalse(lookup.papoHasNoEntitiesIn(boxInChunk0));

        // an INACCESSIBLE chunk is skipped by the predicate exactly like the real query
        // (enum order: INACCESSIBLE is the only status strictly below FULL)
        final ChunkEntitySlices below = new ChunkEntitySlices(null, -2, 2, FullChunkStatus.INACCESSIBLE, null, 0, 15);
        final DefaultEntityLookup lookup2 = new DefaultEntityLookup(null);
        lookup2.addChunk(-2, 2, below);
        Assertions.assertTrue(below.addEntity(mockEntityAt(-31.0, 39.0, 39.0), sectionOf(39.0)));
        Assertions.assertTrue(lookup2.papoHasNoEntitiesIn(new AABB(-31.2, 38.8, 38.8, -30.4, 39.6, 39.6)),
            "INACCESSIBLE chunk with an entity must be ignored, mirroring the real query's status filter");
    }
}
