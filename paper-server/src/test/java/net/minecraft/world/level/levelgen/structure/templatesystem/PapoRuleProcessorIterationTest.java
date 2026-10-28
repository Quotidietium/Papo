package net.minecraft.world.level.levelgen.structure.templatesystem;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

// Papo start - batch 180 (R39): RuleProcessor.processBlock visits its immutable rule
// list by index instead of an enhanced-for (the ImmutableList iterator was one
// allocation per block per template placement). Asserted here: rules are consulted in
// list order, the first match wins and later rules are never consulted, the returned
// info keeps the relative position and carries the matching rule's output state, a
// no-match pass returns the relative info itself, and an empty rule list is a pass-
// through. The per-position RandomSource construction is deliberately untouched
// (seed-derivation red line).
public class PapoRuleProcessorIterationTest {

    /** records its name into a shared order log each time it is consulted */
    private static final class RecordingRuleTest extends RuleTest {
        final String name;
        final boolean matches;
        final List<String> order;

        RecordingRuleTest(String name, boolean matches, List<String> order) {
            this.name = name;
            this.matches = matches;
            this.order = order;
        }

        @Override
        public boolean test(BlockState state, RandomSource random) {
            this.order.add(this.name);
            return this.matches;
        }

        @Override
        protected RuleTestType<?> getType() {
            throw new UnsupportedOperationException("not needed in this test");
        }
    }

    private static ProcessorRule rule(String name, boolean matches, BlockState output, List<String> order) {
        return new ProcessorRule(new RecordingRuleTest(name, matches, order), AlwaysTrueTest.INSTANCE, PosAlwaysTrueTest.INSTANCE, output);
    }

    private static StructureBlockInfo info(BlockPos pos, BlockState state) {
        return new StructureBlockInfo(pos, state, null);
    }

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion(); // must precede bootStrap or registries poison the suite JVM
        Bootstrap.bootStrap();
    }

    @Test
    void firstMatchingRuleWinsAndLaterRulesAreNotConsulted() {
        List<String> order = new ArrayList<>();
        RuleProcessor processor = new RuleProcessor(List.of(
            rule("r1", false, Blocks.STONE.defaultBlockState(), order),
            rule("r2", true, Blocks.GOLD_BLOCK.defaultBlockState(), order),
            rule("r3", true, Blocks.DIAMOND_BLOCK.defaultBlockState(), order)
        ));
        LevelReader level = Mockito.mock(LevelReader.class);
        Mockito.when(level.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.defaultBlockState());

        StructureBlockInfo relative = info(new BlockPos(4, 5, 6), Blocks.DIRT.defaultBlockState());
        StructureBlockInfo out = processor.processBlock(
            level, BlockPos.ZERO, new BlockPos(7, 8, 9), info(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState()), relative, new StructurePlaceSettings()
        );

        Assertions.assertEquals(List.of("r1", "r2"), order, "list order, first match wins, r3 never consulted");
        Assertions.assertSame(relative.pos(), out.pos(), "output keeps the relative position");
        Assertions.assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), out.state(), "output carries the matching rule's state");
    }

    @Test
    void noMatchReturnsTheRelativeInfoUntouched() {
        List<String> order = new ArrayList<>();
        RuleProcessor processor = new RuleProcessor(List.of(
            rule("r1", false, Blocks.STONE.defaultBlockState(), order),
            rule("r2", false, Blocks.GOLD_BLOCK.defaultBlockState(), order)
        ));
        LevelReader level = Mockito.mock(LevelReader.class);
        Mockito.when(level.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.defaultBlockState());

        StructureBlockInfo relative = info(new BlockPos(4, 5, 6), Blocks.DIRT.defaultBlockState());
        StructureBlockInfo out = processor.processBlock(
            level, BlockPos.ZERO, BlockPos.ZERO, info(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState()), relative, new StructurePlaceSettings()
        );

        Assertions.assertEquals(List.of("r1", "r2"), order);
        Assertions.assertSame(relative, out, "vanilla returns the relative info itself on no match");
    }

    @Test
    void emptyRuleListIsAPassThrough() {
        RuleProcessor processor = new RuleProcessor(List.of());
        LevelReader level = Mockito.mock(LevelReader.class);
        Mockito.when(level.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.defaultBlockState());

        StructureBlockInfo relative = info(new BlockPos(4, 5, 6), Blocks.DIRT.defaultBlockState());
        StructureBlockInfo out = processor.processBlock(
            level, BlockPos.ZERO, BlockPos.ZERO, info(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState()), relative, new StructurePlaceSettings()
        );
        Assertions.assertSame(relative, out);
    }

    @Test
    void identicalCallsYieldIdenticalOutputs() {
        // Mth.getSeed(position) seeds the RNG identically for identical inputs; the
        // indexed loop must not disturb that determinism in any way
        RuleProcessor first = new RuleProcessor(List.of(rule("only", true, Blocks.GOLD_BLOCK.defaultBlockState(), new ArrayList<>())));
        RuleProcessor second = new RuleProcessor(List.of(rule("only", true, Blocks.GOLD_BLOCK.defaultBlockState(), new ArrayList<>())));
        LevelReader level = Mockito.mock(LevelReader.class);
        Mockito.when(level.getBlockState(Mockito.any())).thenReturn(Blocks.AIR.defaultBlockState());

        StructureBlockInfo relative = info(new BlockPos(-13, 64, 271), Blocks.DIRT.defaultBlockState());
        StructureBlockInfo outA = first.processBlock(level, BlockPos.ZERO, BlockPos.ZERO, info(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState()), relative, new StructurePlaceSettings());
        StructureBlockInfo outB = second.processBlock(level, BlockPos.ZERO, BlockPos.ZERO, info(new BlockPos(1, 2, 3), Blocks.STONE.defaultBlockState()), relative, new StructurePlaceSettings());
        Assertions.assertEquals(outA.pos(), outB.pos());
        Assertions.assertSame(outA.state(), outB.state());
    }
}
