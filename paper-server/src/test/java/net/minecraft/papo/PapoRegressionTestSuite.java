package net.minecraft.papo;

// Papo start - batch 145 (R6): the paper-server test task only picks up **/*TestSuite
// classes and the bukkit/paper suites select their own packages, so Papo regression
// tests outside those trees need their own aggregator suite.
import ca.spottedleaf.moonrise.patches.starlight.light.PapoSWMRUniformNibbleTest;
import net.minecraft.util.PapoZeroBitStorageSharedTest;
import net.minecraft.world.level.chunk.PapoPalettedSharedDataTest;
import net.minecraft.world.ticks.PapoPosTypeSetLazyTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Papo regression suites")
@SelectClasses({PapoPosTypeSetLazyTest.class, PapoZeroBitStorageSharedTest.class, PapoPalettedSharedDataTest.class, PapoSWMRUniformNibbleTest.class})
public class PapoRegressionTestSuite {
}
// Papo end - batch 145 (R6)
