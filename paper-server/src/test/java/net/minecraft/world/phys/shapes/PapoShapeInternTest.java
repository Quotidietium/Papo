package net.minecraft.world.phys.shapes;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Papo start - batch 156 (R16): the canonical shared state-cached shape contract.
// State-held roots (Cache.collisionShape, occlusionShape) route through
// PapoShapeCache.papoCanonical: structurally equal bitset-backed array shapes
// collapse onto one instance, so all lazily materialised substructure (face
// shapes, toAabbs, Moonrise collision caches) is shared per distinct geometry.
// Shapes are immutable values - sharing changes identity only, never geometry,
// and the Shapes.empty()/block() singletons pass through untouched.
public class PapoShapeInternTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void equalGeometryJoinsCollapseToSameInstance() {
        // 非退化输入：全立方 OR 会被 join 快速路径短路成 Shapes.block() 单例
        final VoxelShape a = Shapes.join(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), Shapes.box(0.0, 0.5, 0.0, 1.0, 1.0, 0.5), BooleanOp.OR);
        final VoxelShape b = Shapes.join(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), Shapes.box(0.0, 0.5, 0.0, 1.0, 1.0, 0.5), BooleanOp.OR);
        Assertions.assertNotSame(a, b); // 原生构造就是两个实例
        Assertions.assertSame(PapoShapeCache.papoCanonical(a), PapoShapeCache.papoCanonical(b));
    }

    @Test
    void differentGeometryStaysDistinct() {
        final VoxelShape a = PapoShapeCache.papoCanonical(Shapes.join(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), Shapes.box(0.0, 0.5, 0.0, 1.0, 1.0, 0.5), BooleanOp.OR));
        final VoxelShape b = PapoShapeCache.papoCanonical(Shapes.join(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.25, 1.0), Shapes.box(0.0, 0.25, 0.0, 1.0, 1.0, 0.25), BooleanOp.OR));
        Assertions.assertNotSame(a, b);
    }

    @Test
    void canonicalIsIdempotent() {
        final VoxelShape a = Shapes.join(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0), Shapes.box(0.0, 0.5, 0.0, 1.0, 1.0, 0.5), BooleanOp.OR);
        final VoxelShape c1 = PapoShapeCache.papoCanonical(a);
        Assertions.assertSame(c1, PapoShapeCache.papoCanonical(c1));
    }

    @Test
    void cubeAndEmptySingletonsPassThrough() {
        Assertions.assertSame(Shapes.block(), PapoShapeCache.papoCanonical(Shapes.block()));
        Assertions.assertSame(PapoShapeCache.papoCanonical(Shapes.empty()), PapoShapeCache.papoCanonical(Shapes.empty()));
    }

    @Test
    void stairTypesShareCanonicalCollisionShape() {
        // StairBlock 每实例自建形状表：所有楼梯类型几何恒等、实例互异（改动前），
        // 规范化后跨类型共享同一实例——这是 intern 生效的强判别器
        final VoxelShape oak = Blocks.OAK_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final VoxelShape cobble = Blocks.COBBLESTONE_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertFalse(oak.isEmpty());
        Assertions.assertSame(oak, cobble);
    }

    @Test
    void stairFacingVariantsSharePerGeometry() {
        // 同类型楼梯的不同朝向几何不同 → 各自规范、互不混
        final VoxelShape north = Blocks.OAK_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final VoxelShape east = Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.EAST).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertNotSame(north, east);
    }

    @Test
    void differentGeometryStatesStayDistinct() {
        final VoxelShape stairs = Blocks.OAK_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final VoxelShape gate = Blocks.OAK_FENCE_GATE.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertNotSame(stairs, gate);
    }

    @Test
    void perStateShapeTablesShareAcrossBlockTypes() {
        // 批 157：getShapeForEachState 两重载（楼梯/栅栏/墙/挂牌等的每状态形状表）
        // 引导期一次性建造——跨块类型等几何坍缩。视觉形状（getShape）与碰撞形状
        //（批 156 Cache 根）分别路由，这里验视觉表。
        final var oak = Blocks.OAK_STAIRS.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final var cobble = Blocks.COBBLESTONE_STAIRS.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertSame(oak, cobble);
        final var oakFence = Blocks.OAK_FENCE.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final var spruceFence = Blocks.SPRUCE_FENCE.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertSame(oakFence, spruceFence);
    }

    @Test
    void perStateShapeTableVariantsStayDistinct() {
        final var bottom = Blocks.OAK_STAIRS.defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final var top = Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertNotSame(bottom, top);
    }

    @Test
    void sharedShapeGeometryIsByteIdentical() {
        final VoxelShape oak = Blocks.OAK_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        final VoxelShape cobble = Blocks.COBBLESTONE_STAIRS.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        Assertions.assertSame(oak, cobble);
        // 共享后的惰性派生也一致：bounds 与 AABB 列表
        Assertions.assertEquals(oak.bounds(), cobble.bounds());
        Assertions.assertEquals(oak.toAabbs(), cobble.toAabbs());
    }
}
// Papo end - batch 156 (R16)
