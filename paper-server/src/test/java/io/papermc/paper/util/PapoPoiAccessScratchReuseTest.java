package io.papermc.paper.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

// Papo start - batch 203 (R62): the POI BFS workset reuse contract.
//
// PoiAccess.findClosestPoiDataRecords / findNearestPoiRecords BFS-walk chunk
// sections with a LongArrayFIFOQueue + LongOpenHashSet workset. Before R62 each
// query allocated both fresh; now a per-thread scratch pair is cleared at entry.
// The reuse contract pinned here:
//   - identical query repeated back-to-back returns the identical record list
//     (bit-for-bit, same order) — a leftover `seen` set from the previous call
//     would prune the second BFS's frontier and change results,
//   - the BFS actually reaches and returns the POI records registered in the
//     centre section (non-empty result, correct distance ordering),
//   - the capacity-keeping clear() has no visible state between queries.
// PoiManager is a Mockito stub: get(sectionKey) returns one populated real
// PoiSection for the centre section, null elsewhere (the BFS skips null);
// moonrise$getWorld() returns a stubbed ServerLevel whose section range covers
// the origin. PoiRecords are real (position + PoiType holder + no-op dirty
// runnable), so occupancy filtering and ordering run the production code path.
public class PapoPoiAccessScratchReuseTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    private static final BlockPos ORIGIN = new BlockPos(100, 8, 100);

    private static final Holder<PoiType> HOME_TYPE = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(
        net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE,
            net.minecraft.resources.Identifier.withDefaultNamespace("home")));

    private static PoiSection sectionWith(final int[][] positions) {
        final PoiSection section = new PoiSection(() -> {});
        for (final int[] p : positions) {
            section.add(new BlockPos(p[0], p[1], p[2]), HOME_TYPE);
        }
        return section;
    }

    @SuppressWarnings("unchecked")
    private static PoiManager managerWith(final PoiSection centre) {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.doReturn(0).when(level).getMinSectionY();
        Mockito.doReturn(0).when(level).getMaxSectionY();
        final PoiManager manager = Mockito.mock(PoiManager.class);
        Mockito.doReturn(level).when(manager).moonrise$getWorld();
        final long centreKey = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkSectionKey(6, 0, 6);
        Mockito.doReturn(null).when(manager).get(ArgumentMatchers.anyLong());
        Mockito.doReturn(Optional.of(centre)).when(manager).get(centreKey); // specific stub wins over anyLong
        return manager;
    }

    @Test
    void testRepeatedQueryReturnsIdenticalResults() {
        final PoiSection section = sectionWith(new int[][]{{96, 5, 96}, {103, 9, 100}, {100, 1, 107}});
        final PoiManager manager = managerWith(section);

        final List<PoiRecord> first = new ArrayList<>();
        PoiAccess.findClosestPoiDataRecords(manager, type -> true, (type, pos) -> true,
            ORIGIN, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, false, first);
        // findClosestPoiDataRecords returns the records at the (tied) minimum distance
        // only: (103,9,100) is nearest to ORIGIN (100,8,100) of the three planted.
        Assertions.assertEquals(1, first.size(), "closest-only semantics: nearest record wins");
        Assertions.assertEquals(new BlockPos(103, 9, 100), first.get(0).getPos());

        final List<PoiRecord> second = new ArrayList<>();
        PoiAccess.findClosestPoiDataRecords(manager, type -> true, (type, pos) -> true,
            ORIGIN, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, false, second);
        Assertions.assertEquals(first, second,
            "back-to-back identical queries must return the identical list — a leftover seen-set "
                + "from the previous call would prune the second BFS and change the result");
    }

    @Test
    void testNearestRecordsQueryAlsoReuseWorkset() {
        final PoiSection section = sectionWith(new int[][]{{100, 8, 100}, {98, 8, 98}});
        final PoiManager manager = managerWith(section);

        final List<PoiRecord> first = new ArrayList<>();
        PoiAccess.findNearestPoiRecords(manager, type -> true, null,
            ORIGIN, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, false, 5, first);
        Assertions.assertEquals(2, first.size());

        final List<PoiRecord> second = new ArrayList<>();
        PoiAccess.findNearestPoiRecords(manager, type -> true, null,
            ORIGIN, 48, 48.0 * 48.0, PoiManager.Occupancy.HAS_SPACE, false, 5, second);
        Assertions.assertEquals(first, second, "second nearest-query must be unaffected by scratch reuse");
    }
}
// Papo end - batch 203 (R62)
