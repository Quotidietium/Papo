package net.minecraft.world.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Papo start - batch 182 (R41): flushStep() guards its two addAll calls with isEmpty()
// because ArrayList.addAll unconditionally calls source.toArray() - allocating a fresh
// (almost always zero-length) array per call, 14 types x 2 tables = 28 empty-array
// allocations per entity move segment even when no inside-block effect was triggered.
// The guard is observationally identical: an empty addAll mutates nothing and its
// return value is discarded, and clear() still runs unconditionally so modCount
// semantics are untouched. These tests pin the collector contract the guard must
// preserve: before-then-recorded-then-after order within a type, declaration order
// (APPLY_ORDER) across types within one flushed step, step-boundary flushes emit
// strictly in arrival order, empty cycles are no-ops that leave the collector usable,
// and apply() still records the vanilla applier through Paper's position tracking.
public class PapoStepCollectorEmptyAddAllTest {

    private static Entity liveEntity() {
        final Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.isAlive()).thenReturn(true);
        return entity;
    }

    @Test
    void emptyCyclesAreNoOpAndLeaveCollectorUsable() {
        final InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        final Entity entity = liveEntity();

        // nothing recorded: flush + consume must be a silent no-op, repeatedly
        for (int i = 0; i < 3; i++) {
            collector.advanceStep(i, BlockPos.ZERO);
            Assertions.assertDoesNotThrow(() -> collector.applyAndClear(entity));
        }

        // ...and the collector stays fully usable afterwards (the regression surface
        // of the isEmpty guards: after empty cycles the tables must still merge)
        final List<String> log = new ArrayList<>();
        collector.advanceStep(7, BlockPos.ZERO);
        collector.runBefore(InsideBlockEffectType.FREEZE, e -> log.add("before"));
        collector.runAfter(InsideBlockEffectType.FREEZE, e -> log.add("after"));
        collector.applyAndClear(entity);
        collector.applyAndClear(entity); // second consume sees the cleared state
        Assertions.assertEquals(List.of("before", "after"), log);
    }

    @Test
    void sameStepKeepsBeforeRecordedAfterOrder() {
        final InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        final Entity entity = liveEntity();
        final List<String> log = new ArrayList<>();

        collector.advanceStep(0, new BlockPos(1, 2, 3));
        collector.runBefore(InsideBlockEffectType.FREEZE, e -> log.add("before"));
        collector.apply(InsideBlockEffectType.FREEZE); // records the vanilla applier
        collector.runAfter(InsideBlockEffectType.FREEZE, e -> log.add("after"));
        collector.advanceStep(0, new BlockPos(4, 5, 6)); // same step: no boundary flush
        collector.applyAndClear(entity);

        Assertions.assertEquals(List.of("before", "after"), log);
        Mockito.verify(entity).setIsInPowderSnow(true); // recorded FREEZE applier ran exactly once
    }

    @Test
    void stepBoundaryFlushesInArrivalOrderNotDeclarationOrder() {
        final InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        final Entity entity = liveEntity();
        final List<String> log = new ArrayList<>();

        // CLEAR_FREEZE is declared after FREEZE, so a correct implementation must NOT
        // reorder them into APPLY_ORDER once they were flushed by different steps
        collector.advanceStep(0, BlockPos.ZERO);
        collector.runBefore(InsideBlockEffectType.CLEAR_FREEZE, e -> log.add("step0-clear"));
        collector.advanceStep(1, BlockPos.ZERO); // boundary: flushes step 0
        collector.runBefore(InsideBlockEffectType.FREEZE, e -> log.add("step1-freeze"));
        collector.applyAndClear(entity);

        Assertions.assertEquals(List.of("step0-clear", "step1-freeze"), log);
    }

    @Test
    void flushedStepMergesTypesInDeclarationOrder() {
        final InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        final Entity entity = liveEntity();
        final List<String> log = new ArrayList<>();

        // register against declaration order to prove the merge order, with untouched
        // (empty) types interleaved between them exercising the guarded addAll path
        collector.advanceStep(3, BlockPos.ZERO);
        collector.runBefore(InsideBlockEffectType.EXTINGUISH, e -> log.add("ext-before"));
        collector.runBefore(InsideBlockEffectType.FREEZE, e -> log.add("freeze-before"));
        collector.runAfter(InsideBlockEffectType.FREEZE, e -> log.add("freeze-after"));
        collector.runAfter(InsideBlockEffectType.CLEAR_FREEZE, e -> log.add("clear-after"));
        collector.applyAndClear(entity);

        Assertions.assertEquals(
            List.of("freeze-before", "freeze-after", "clear-after", "ext-before"), log);
    }
}
