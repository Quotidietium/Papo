package net.minecraft.world.level.levelgen.placement;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - compat regression test: the placement walk (papoForEachPosition) must keep a
// subclass's getPositions-only override driving placement. The loop twin (forEachPosition) may
// only run when getPositions and forEachPosition are overridden in lockstep; otherwise the
// driver falls back to the getPositions bridge so plugin overrides are never silently bypassed.
public class PapoPlacementLockstepTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    /** Overrides BOTH methods in lockstep (vanilla modifier shape). */
    public static class LockstepModifier extends PlacementModifier {
        final AtomicInteger forEachRuns = new AtomicInteger();
        final AtomicInteger getPositionsRuns = new AtomicInteger();

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            this.getPositionsRuns.incrementAndGet();
            return Stream.of(pos);
        }

        @Override
        public void forEachPosition(PlacementContext context, RandomSource random, BlockPos pos, Consumer<BlockPos> emitter) {
            this.forEachRuns.incrementAndGet();
            emitter.accept(pos);
        }

        @Override
        public PlacementModifierType<?> type() {
            return null;
        }
    }

    /** Overrides ONLY getPositions (plugin shape compiled against the pre-Papo surface). */
    public static class PluginGetPositionsOnly extends LockstepModifier {
        final AtomicInteger pluginOverrideRuns = new AtomicInteger();

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            this.pluginOverrideRuns.incrementAndGet();
            return Stream.of(pos);
        }
    }

    /** Overrides ONLY forEachPosition above the base bridge (unbalanced the other way). */
    public static class ForEachOnly extends PlacementModifier {
        final AtomicInteger forEachRuns = new AtomicInteger();

        @Override
        public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
            throw new AssertionError("getPositions must not run when forEachPosition is overridden without it");
        }

        @Override
        public void forEachPosition(PlacementContext context, RandomSource random, BlockPos pos, Consumer<BlockPos> emitter) {
            this.forEachRuns.incrementAndGet();
            emitter.accept(pos);
        }

        @Override
        public PlacementModifierType<?> type() {
            return null;
        }
    }

    private static final BlockPos POS = new BlockPos(1, 2, 3);

    @Test
    void lockstepSubclassRunsLoopTwin() {
        final LockstepModifier modifier = new LockstepModifier();
        final List<BlockPos> out = new java.util.ArrayList<>();
        modifier.papoForEachPosition(null, null, POS, out::add);
        Assertions.assertEquals(List.of(POS), out);
        Assertions.assertEquals(1, modifier.forEachRuns.get(), "lockstep shape must dispatch to forEachPosition");
        Assertions.assertEquals(0, modifier.getPositionsRuns.get());
    }

    @Test
    void getPositionsOnlyOverrideStillDrivesPlacement() {
        final PluginGetPositionsOnly modifier = new PluginGetPositionsOnly();
        final List<BlockPos> out = new java.util.ArrayList<>();
        modifier.papoForEachPosition(null, null, POS, out::add);
        Assertions.assertEquals(List.of(POS), out);
        Assertions.assertEquals(1, modifier.pluginOverrideRuns.get(), "the plugin's getPositions override must drive placement (not the inherited loop twin)");
        Assertions.assertEquals(0, modifier.forEachRuns.get(), "the inherited vanilla-shape loop twin must NOT run for a getPositions-only subclass");
    }

    @Test
    void forEachOnlyOverrideRunsTwinNotBridge() {
        final ForEachOnly modifier = new ForEachOnly();
        final List<BlockPos> out = new java.util.ArrayList<>();
        modifier.papoForEachPosition(null, null, POS, out::add);
        Assertions.assertEquals(List.of(POS), out);
        Assertions.assertEquals(1, modifier.forEachRuns.get());
    }
}
