package net.minecraft.papo;

// Papo start - batch 145 (R6): the paper-server test task only picks up **/*TestSuite
// classes and the bukkit/paper suites select their own packages, so Papo regression
// tests outside those trees need their own aggregator suite.
import ca.spottedleaf.moonrise.patches.starlight.light.PapoSWMRSharedNibbleTest;
import ca.spottedleaf.moonrise.patches.starlight.light.PapoSWMRUniformNibbleTest;
import net.minecraft.util.PapoZeroBitStorageSharedTest;
import net.minecraft.world.level.chunk.PapoLazyBlockEntitiesTest;
import net.minecraft.world.level.chunk.PapoNoiseChunkParkReleaseTest;
import net.minecraft.world.level.chunk.PapoPalettedSharedDataTest;
import net.minecraft.world.level.chunk.PapoSharedConstantSectionTest;
import net.minecraft.world.level.levelgen.PapoSurfaceRulesAllocTest;
import net.minecraft.world.level.pathfinder.PapoBinaryHeapReleaseTest;
import net.minecraft.nbt.PapoReadUtfIdentityTest;
import net.minecraft.world.level.chunk.PapoPackedDataAliasTest;
import net.minecraft.world.level.chunk.PapoReencodeIdentityTest;
import net.minecraft.world.level.chunk.PapoBlockCountRecalcIdentityTest;
import net.minecraft.world.level.biome.PapoClimateScratchTest;
import net.minecraft.world.level.levelgen.PapoBeardifierIndexedLoopTest;
import net.minecraft.world.level.levelgen.placement.PapoPlacementDriverEquivalenceTest;
import net.minecraft.world.level.levelgen.placement.PapoModifierForEachTest;
import net.minecraft.world.level.levelgen.blockpredicates.PapoBlockPredicateScratchTest;
import net.minecraft.world.level.levelgen.PapoContextScratchTest;
import net.minecraft.world.level.levelgen.PapoAp2FillArrayTest;
import net.minecraft.world.level.chunk.PapoBulkSectionAccessPoolTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.PapoRuleProcessorIterationTest;
import net.minecraft.world.entity.PapoStepCollectorEmptyAddAllTest;
import net.minecraft.world.level.chunk.PapoLongArrayEncodeFastPathTest;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.PapoHopperEmptyQueryFastPathTest;
import net.minecraft.world.item.PapoLazyItemsListTest;
import org.bukkit.craftbukkit.persistence.PapoPersistentDataContainerLazyTest;
import net.minecraft.util.PapoCubicSplineMapAllTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.PapoStructureTemplateCacheTest;
import net.minecraft.world.phys.shapes.PapoShapeInternTest;
import net.minecraft.world.ticks.PapoPosTypeSetLazyTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Papo regression suites")
@SelectClasses({PapoNoiseChunkParkReleaseTest.class, PapoLazyBlockEntitiesTest.class, PapoPosTypeSetLazyTest.class, PapoZeroBitStorageSharedTest.class, PapoPalettedSharedDataTest.class, PapoSWMRSharedNibbleTest.class, PapoSWMRUniformNibbleTest.class, PapoSharedConstantSectionTest.class, PapoStructureTemplateCacheTest.class, PapoShapeInternTest.class, PapoSurfaceRulesAllocTest.class, PapoBinaryHeapReleaseTest.class,
    PapoBlockCountRecalcIdentityTest.class,
    PapoReadUtfIdentityTest.class,
    PapoPackedDataAliasTest.class,
    PapoReencodeIdentityTest.class,
    PapoClimateScratchTest.class,
    PapoBeardifierIndexedLoopTest.class,
    PapoCubicSplineMapAllTest.class,
    PapoPlacementDriverEquivalenceTest.class,
    PapoModifierForEachTest.class,
    PapoBlockPredicateScratchTest.class,
    PapoContextScratchTest.class,
    PapoAp2FillArrayTest.class,
    PapoBulkSectionAccessPoolTest.class,
    PapoRuleProcessorIterationTest.class,
    PapoStepCollectorEmptyAddAllTest.class,
    PapoLongArrayEncodeFastPathTest.class,
    PapoLazyItemsListTest.class,
    PapoPersistentDataContainerLazyTest.class,
    PapoHopperEmptyQueryFastPathTest.class})
public class PapoRegressionTestSuite {
}
// Papo end - batch 145 (R6)
