package net.minecraft.world.entity.ai;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Papo start - batch 203 (R62): the brain behavior-snapshot traversal contract.
//
// Brain.startEachNonRunningBehavior/tickEachRunningBehavior iterate a flattened
// (activity, behavior) snapshot instead of the TreeMap x HashMap x LinkedHashSet
// nested iterators. The snapshot is invalidated by every structure mutation
// (addActivity family, removeAllBehaviors — e.g. Villager.refreshBrain) and
// rebuilt lazily on the next tick. The order-identity argument: while the
// structure is unmutated, every one of the three collection orders is
// deterministic, so the flattened snapshot order equals the order the vanilla
// nested loops produce on EVERY tick. These tests pin the load-bearing
// invariants without hard-coding HashMap bucket order:
//   - insertion order within an activity (LinkedHashSet) is preserved into
//     tryStart order — the arbitration order for competing behaviors,
//   - priority order is ascending (TreeMap),
//   - two consecutive ticks produce the identical call sequence (snapshot
//     stability — same brain, same iteration order every tick),
//   - an addActivity AFTER ticks is visible on the next tick (dirty rebuild)
//     and preserves the prior prefix order,
//   - removeAllBehaviors silences all calls.
public class PapoBrainSnapshotTraversalTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    /** Recording fake: tryStart flips STOPPED->RUNNING, tickOrStop records the call. */
    static final class FakeBehavior implements BehaviorControl<LivingEntity> {
        final String name;
        final List<String> log;
        Behavior.Status status = Behavior.Status.STOPPED;

        FakeBehavior(final String name, final List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public Behavior.Status getStatus() {
            return this.status;
        }

        @Override
        public boolean tryStart(final ServerLevel level, final LivingEntity entity, final long gameTime) {
            this.log.add("start:" + this.name);
            this.status = Behavior.Status.RUNNING;
            return true;
        }

        @Override
        public void tickOrStop(final ServerLevel level, final LivingEntity entity, final long gameTime) {
            this.log.add("tick:" + this.name);
        }

        @Override
        public void doStop(final ServerLevel level, final LivingEntity entity, final long gameTime) {
            this.log.add("stop:" + this.name);
            this.status = Behavior.Status.STOPPED;
        }

        @Override
        public String debugString() {
            return this.name;
        }
    }

    private static Brain<LivingEntity> freshBrain() {
        return new Brain<>(List.of(), List.of(), ImmutableList.of(), () -> null);
    }

    @SuppressWarnings("unchecked")
    private static void tick(final Brain<LivingEntity> brain) {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.doReturn(100L).when(level).getGameTime();
        final LivingEntity entity = Mockito.mock(LivingEntity.class);
        brain.tick(level, entity);
    }

    private static void activate(final Brain<LivingEntity> brain, final Activity activity) {
        // schedule == null -> updateActivityFromSchedule picks Activity.IDLE; but we can
        // activate any registered activity directly (addActivity registered empty
        // requirements -> activityRequirementsAreMet passes).
        brain.setActiveActivityIfPossible(activity);
    }

    @Test
    void testInsertionOrderWithinActivityIsTryStartOrder() {
        final Brain<LivingEntity> brain = this.freshBrain();
        final List<String> log = new ArrayList<>();
        final FakeBehavior b1 = new FakeBehavior("b1", log);
        final FakeBehavior b2 = new FakeBehavior("b2", log);
        final FakeBehavior b3 = new FakeBehavior("b3", log);
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, b1), Pair.of(0, b2), Pair.of(0, b3)));
        this.activate(brain, Activity.IDLE);
        this.tick(brain);
        // vanilla Brain.tick runs startEach... then tickEach... in the same tick: freshly
        // started behaviors are ticked immediately, so both halves appear per tick.
        Assertions.assertEquals(List.of("start:b1", "start:b2", "start:b3", "tick:b1", "tick:b2", "tick:b3"), log,
            "LinkedHashSet insertion order must be the arbitration order in BOTH halves of the tick");
    }

    @Test
    void testPriorityOrderIsAscendingAcrossTicks() {
        final Brain<LivingEntity> brain = this.freshBrain();
        final List<String> log = new ArrayList<>();
        // inserted deliberately out of priority order: TreeMap must order 0 < 5 < 10
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(5, new FakeBehavior("mid", log))));
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(10, new FakeBehavior("high", log))));
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, new FakeBehavior("low", log))));
        this.activate(brain, Activity.IDLE);
        // first tick: starts all three in traversal order
        this.tick(brain);
        Assertions.assertEquals(List.of("start:low", "start:mid", "start:high", "tick:low", "tick:mid", "tick:high"), log);
        // second tick: all RUNNING now -> tickOrStop in the same order (snapshot stability)
        log.clear();
        this.tick(brain);
        Assertions.assertEquals(List.of("tick:low", "tick:mid", "tick:high"), log,
            "consecutive ticks must produce the identical call sequence");
    }

    @Test
    void testTwoActivitiesAllBehaviorsTickInStableOrder() {
        final Brain<LivingEntity> brain = this.freshBrain();
        final List<String> log = new ArrayList<>();
        final FakeBehavior c1 = new FakeBehavior("c1", log);
        final FakeBehavior c2 = new FakeBehavior("c2", log);
        final FakeBehavior i1 = new FakeBehavior("i1", log);
        final FakeBehavior i2 = new FakeBehavior("i2", log);
        brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(0, c1), Pair.of(0, c2)));
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, i1), Pair.of(0, i2)));
        brain.setCoreActivities(java.util.Set.of(Activity.CORE));
        this.activate(brain, Activity.IDLE);
        // core stays active alongside the active activity -> all four start
        this.tick(brain);
        Assertions.assertEquals(8, log.size(), "4 starts + 4 immediate ticks (startEach then tickEach)");
        final List<String> starts = log.stream().filter(l -> l.startsWith("start:")).toList();
        Assertions.assertEquals(java.util.Set.of("start:c1", "start:c2", "start:i1", "start:i2"),
            new java.util.HashSet<>(starts), "every behavior across both activities must be visited");
        // order between the two activities is HashMap bucket order — not hard-coded,
        // but it MUST be identical on the next tick (deterministic while unmutated)
        final List<String> first = new ArrayList<>(log);
        log.clear();
        this.tick(brain);
        final List<String> firstTicks = first.stream().filter(l -> l.startsWith("tick:")).toList();
        Assertions.assertEquals(firstTicks, log,
            "second tick re-ticks the same four RUNNING behaviors in the identical order");
    }

    @Test
    void testAddActivityAfterTicksRebuildsSnapshot() {
        final Brain<LivingEntity> brain = this.freshBrain();
        final List<String> log = new ArrayList<>();
        final FakeBehavior old1 = new FakeBehavior("old1", log);
        final FakeBehavior old2 = new FakeBehavior("old2", log);
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, old1), Pair.of(0, old2)));
        this.activate(brain, Activity.IDLE);
        this.tick(brain);
        Assertions.assertEquals(List.of("start:old1", "start:old2", "tick:old1", "tick:old2"), log);
        // runtime mutation (the Villager.refreshBrain profession-change scenario):
        // a new behavior joins the same activity+priority after ticks have already run
        final FakeBehavior added = new FakeBehavior("added", log);
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, added)));
        log.clear();
        this.tick(brain);
        // startEach runs first: only `added` is STOPPED, so it starts; then tickEach
        // ticks all three RUNNING behaviors in traversal order (old1, old2, added).
        Assertions.assertEquals(List.of("start:added", "tick:old1", "tick:old2", "tick:added"), log,
            "dirty rebuild must surface the added behavior while preserving the prior order prefix");
    }

    @Test
    void testRemoveAllBehaviorsSilencesTraversal() {
        final Brain<LivingEntity> brain = this.freshBrain();
        final List<String> log = new ArrayList<>();
        brain.addActivity(Activity.IDLE, ImmutableList.of(Pair.of(0, new FakeBehavior("x", log))));
        this.activate(brain, Activity.IDLE);
        brain.removeAllBehaviors();
        this.tick(brain);
        Assertions.assertEquals(List.of(), log, "removeAllBehaviors must invalidate the snapshot to empty");
    }
}
// Papo end - batch 203 (R62)
