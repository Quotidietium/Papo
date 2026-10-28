package net.minecraft.world.entity.ai.sensing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

// Papo start - batch 203 (R62): the SecondaryPoiSensor mutable-cursor scan contract.
//
// doTick scans 9x5x9 = 405 positions around the villager; each vanilla iteration
// allocated an immutable BlockPos via offset that was only ever read by
// getBlockState. The cursor version replaces all 405 with one MutableBlockPos and
// copies with immutable() only on a hit (the value crosses into brain memory).
// The oracle below recomputes the same 405-position walk by hand and pins:
//   - identical hit set AND hit order (i1/i2/i3 loop order),
//   - the memory stores fresh immutable positions (cursor never leaks),
//   - the no-hit branch erases the memory.
public class PapoSecondaryPoiSensorCursorTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    private static final BlockPos ORIGIN = new BlockPos(1000, 64, -2000);

    /** Deterministic hit landscape: FARMLAND iff ((x + y*31 + z) & 7) == 0, else air. */
    private static BlockState stateAt(final int x, final int y, final int z) {
        return ((x + y * 31 + z) & 7) == 0 ? Blocks.FARMLAND.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = Mockito.mock(ServerLevel.class);
        Mockito.doReturn(Level.OVERWORLD).when(level).dimension();
        Mockito.doAnswer(inv -> {
            final BlockPos pos = inv.getArgument(0);
            return stateAt(pos.getX(), pos.getY(), pos.getZ());
        }).when(level).getBlockState(ArgumentMatchers.any(BlockPos.class));
        return level;
    }

    @SuppressWarnings("unchecked")
    private static Villager mockVillager() {
        final Villager villager = Mockito.mock(Villager.class);
        Mockito.doReturn(ORIGIN).when(villager).blockPosition();
        Mockito.doReturn(new VillagerData(
            BuiltInRegistries.VILLAGER_TYPE.getOrThrow(VillagerType.PLAINS),
            BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER),
            5
        )).when(villager).getVillagerData();
        final Brain<Villager> brain = new Brain<>(List.of(MemoryModuleType.SECONDARY_JOB_SITE), List.of(),
            com.google.common.collect.ImmutableList.of(), () -> null);
        Mockito.doReturn(brain).when(villager).getBrain();
        return villager;
    }

    private static List<GlobalPos> oracle() {
        final List<GlobalPos> expected = new ArrayList<>();
        for (int i1 = -4; i1 <= 4; i1++) {
            for (int i2 = -2; i2 <= 2; i2++) {
                for (int i3 = -4; i3 <= 4; i3++) {
                    final int x = ORIGIN.getX() + i1;
                    final int y = ORIGIN.getY() + i2;
                    final int z = ORIGIN.getZ() + i3;
                    if (stateAt(x, y, z).getBlock() == Blocks.FARMLAND) {
                        expected.add(GlobalPos.of(Level.OVERWORLD, new BlockPos(x, y, z)));
                    }
                }
            }
        }
        return expected;
    }

    @Test
    void testScanHitsAndOrderMatchVanillaWalk() {
        final ServerLevel level = mockLevel();
        final Villager villager = mockVillager();
        new SecondaryPoiSensor().doTick(level, villager);
        final Optional<List<GlobalPos>> memory = villager.getBrain().getMemory(MemoryModuleType.SECONDARY_JOB_SITE);
        Assertions.assertTrue(memory.isPresent(), "hits exist in this landscape - memory must be set");
        Assertions.assertEquals(this.oracle(), memory.get(),
            "hit set and hit order must match the vanilla 405-position walk exactly");
        // freshness: every stored position is its own immutable instance
        for (final GlobalPos pos : memory.get()) {
            Assertions.assertFalse(pos.pos() instanceof BlockPos.MutableBlockPos,
                "memory must hold immutable positions - the cursor never leaks");
        }
    }

    @Test
    void testNoHitLandscapeErasesMemory() {
        final ServerLevel level = mockLevel();
        final Villager villager = mockVillager();
        Mockito.doReturn(Blocks.AIR.defaultBlockState()).when(level).getBlockState(ArgumentMatchers.any(BlockPos.class));
        new SecondaryPoiSensor().doTick(level, villager);
        Assertions.assertFalse(villager.getBrain().getMemory(MemoryModuleType.SECONDARY_JOB_SITE).isPresent(),
            "no hits - memory must be erased (vanilla else-branch)");
    }
}
// Papo end - batch 203 (R62)
