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
import net.minecraft.world.level.chunk.PapoBlockCountRecalcIdentityTest;
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
    PapoReadUtfIdentityTest.class})
public class PapoRegressionTestSuite {
}
// Papo end - batch 145 (R6)
