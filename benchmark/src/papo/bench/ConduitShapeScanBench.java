package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次40 / 0163: 潮涌核心 updateShape 框架扫描 scratch pos。
 * before（原版每 40 tick 每潮涌核心）：内层 3×3×3 水判定每位置 new BlockPos（27 次），
 *        外层框架扫描每命中条件位置 new BlockPos（至多 42 次，无论是否框架方块）。
 * after：全程 scratch MutableBlockPos 坐标读取（isWaterAt/getBlockState 不逃逸），
 *        仅确认框架方块时 new BlockPos(scratch) 入表（effectBlocks 持久表语义：清空重建）。
 * 语义复刻：VALID_BLOCKS 4 块循环以 4 个方块 id 复刻；positions 为持久表字段（同 effectBlocks）。
 * 注意：before 路径 loop1 的 27 次分配与 loop2 非框架位置的分配在复刻浅栈中可能被 EA 抹除
 *        （0159 同款复刻限制），复刻中性则以 -prof gc 实证并按 0140/0157/0159 先例保留。
 * main 自检：完整/部分/稀疏/无框架/含干方块 五种场景两路径返回值与逐位置坐标矩阵一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ConduitShapeScanBench {

    /** BlockPos 语义复刻（不可变值）。 */
    static final class BlockPos {
        final int x;
        final int y;
        final int z;

        BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** MutableBlockPos 语义复刻。 */
    static final class MutableBlockPos {
        int x;
        int y;
        int z;

        MutableBlockPos set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }
    }

    /** Level 语义复刻：3×3×3 内层水判定 + 框架环位置方块查询（O(1) 哈希，与真实 getBlockState 调色板索引同量级）。VALID_BLOCKS = {1,2,3,4}。 */
    static class Level {
        final int frameCount; // 框架环中前 frameCount 个位置为框架方块
        final long dryCell; // 非水位置（packed），-1 表示无
        final java.util.HashSet<Long> frameSet = new java.util.HashSet<>(); // 预计算框架位置（packed 坐标）

        Level(int cx, int cy, int cz, int frameCount, long dryCell) {
            this.frameCount = frameCount;
            this.dryCell = dryCell;
            int idx = 0;
            for (int a = -2; a <= 2 && idx < frameCount; a++) {
                for (int b = -2; b <= 2 && idx < frameCount; b++) {
                    for (int c = -2; c <= 2 && idx < frameCount; c++) {
                        int abs = Math.abs(a);
                        int abs1 = Math.abs(b);
                        int abs2 = Math.abs(c);
                        if ((abs > 1 || abs1 > 1 || abs2 > 1)
                            && (a == 0 && (abs1 == 2 || abs2 == 2) || b == 0 && (abs == 2 || abs2 == 2) || c == 0 && (abs == 2 || abs1 == 2))) {
                            this.frameSet.add(pack(cx + a, cy + b, cz + c));
                            idx++;
                        }
                    }
                }
            }
        }

        static long pack(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
        }

        boolean isWaterAt(int x, int y, int z) {
            return pack(x, y, z) != this.dryCell;
        }

        /** 复刻 getBlockState(...).is(block)：返回方块 id，框架位置恒为 1（PRISMARINE 为 VALID_BLOCKS 首元，首次迭代即命中——与真实一致）。 */
        int getBlockStateId(int x, int y, int z) {
            return this.frameSet.contains(pack(x, y, z)) ? 1 : 0;
        }

        /** 与扫描函数同序枚举 42 个框架环位置，返回序号（非环位置 -1）。仅供自检。 */
        static int ringIndex(int i, int i1, int i2) {
            int idx = 0;
            for (int a = -2; a <= 2; a++) {
                for (int b = -2; b <= 2; b++) {
                    for (int c = -2; c <= 2; c++) {
                        int abs = Math.abs(a);
                        int abs1 = Math.abs(b);
                        int abs2 = Math.abs(c);
                        if ((abs > 1 || abs1 > 1 || abs2 > 1)
                            && (a == 0 && (abs1 == 2 || abs2 == 2) || b == 0 && (abs == 2 || abs2 == 2) || c == 0 && (abs == 2 || abs1 == 2))) {
                            if (a == i && b == i1 && c == i2) {
                                return idx;
                            }
                            idx++;
                        }
                    }
                }
            }
            return -1;
        }
    }

    static final int[] VALID_BLOCK_IDS = {1, 2, 3, 4};

    private Level level;
    private final List<BlockPos> positions = new ArrayList<>(); // effectBlocks 持久表复刻

    @Setup
    public void setup() {
        this.level = new Level(500, 60, -300, 42, -1L); // 完整框架，全水
    }

    private static boolean updateShapeBefore(Level level, int cx, int cy, int cz, List<BlockPos> positions) {
        positions.clear();

        for (int i = -1; i <= 1; i++) {
            for (int i1 = -1; i1 <= 1; i1++) {
                for (int i2 = -1; i2 <= 1; i2++) {
                    BlockPos blockPos = new BlockPos(cx + i, cy + i1, cz + i2); // before: 每位置分配
                    if (!level.isWaterAt(blockPos.x, blockPos.y, blockPos.z)) {
                        return false;
                    }
                }
            }
        }

        for (int i = -2; i <= 2; i++) {
            for (int i1 = -2; i1 <= 2; i1++) {
                for (int i2x = -2; i2x <= 2; i2x++) {
                    int abs = Math.abs(i);
                    int abs1 = Math.abs(i1);
                    int abs2 = Math.abs(i2x);
                    if ((abs > 1 || abs1 > 1 || abs2 > 1)
                        && (i == 0 && (abs1 == 2 || abs2 == 2) || i1 == 0 && (abs == 2 || abs2 == 2) || i2x == 0 && (abs == 2 || abs1 == 2))) {
                        BlockPos blockPos1 = new BlockPos(cx + i, cy + i1, cz + i2x); // before: 无论是否框架都分配
                        int blockState = level.getBlockStateId(blockPos1.x, blockPos1.y, blockPos1.z);

                        for (int block : VALID_BLOCK_IDS) {
                            if (blockState == block) {
                                positions.add(blockPos1);
                            }
                        }
                    }
                }
            }
        }

        return positions.size() >= 16;
    }

    private static boolean updateShapeAfter(Level level, int cx, int cy, int cz, List<BlockPos> positions) {
        positions.clear();
        MutableBlockPos scratch = new MutableBlockPos();

        for (int i = -1; i <= 1; i++) {
            for (int i1 = -1; i1 <= 1; i1++) {
                for (int i2 = -1; i2 <= 1; i2++) {
                    if (!level.isWaterAt(scratch.set(cx + i, cy + i1, cz + i2).x, scratch.y, scratch.z)) { // after: scratch
                        return false;
                    }
                }
            }
        }

        for (int i = -2; i <= 2; i++) {
            for (int i1 = -2; i1 <= 2; i1++) {
                for (int i2x = -2; i2x <= 2; i2x++) {
                    int abs = Math.abs(i);
                    int abs1 = Math.abs(i1);
                    int abs2 = Math.abs(i2x);
                    if ((abs > 1 || abs1 > 1 || abs2 > 1)
                        && (i == 0 && (abs1 == 2 || abs2 == 2) || i1 == 0 && (abs == 2 || abs2 == 2) || i2x == 0 && (abs == 2 || abs1 == 2))) {
                        int blockState = level.getBlockStateId(scratch.set(cx + i, cy + i1, cz + i2x).x, scratch.y, scratch.z); // after: scratch

                        for (int block : VALID_BLOCK_IDS) {
                            if (blockState == block) {
                                positions.add(new BlockPos(scratch.x, scratch.y, scratch.z)); // after: 仅命中时分配
                            }
                        }
                    }
                }
            }
        }

        return positions.size() >= 16;
    }

    @Benchmark
    public int before_shapeScanAlloc(Blackhole bh) {
        boolean active = updateShapeBefore(this.level, 500, 60, -300, this.positions);
        bh.consume(this.positions);
        return active ? this.positions.size() : -this.positions.size() - 1;
    }

    @Benchmark
    public int after_shapeScanScratch(Blackhole bh) {
        boolean active = updateShapeAfter(this.level, 500, 60, -300, this.positions);
        bh.consume(this.positions);
        return active ? this.positions.size() : -this.positions.size() - 1;
    }

    /** 等价性自检：五种场景返回值 + 逐位置坐标矩阵。 */
    public static void main(String[] args) {
        Level[] scenarios = {
            new Level(500, 60, -300, 42, -1L), // 完整框架
            new Level(500, 60, -300, 20, -1L), // 部分框架（仍激活：20 >= 16）
            new Level(500, 60, -300, 10, -1L), // 稀疏框架（不激活）
            new Level(500, 60, -300, 0, -1L), // 无框架
            new Level(500, 60, -300, 42, Level.pack(501, 60, -300)), // 内层含干方块（早退）
        };
        String[] names = {"full", "partial20", "sparse10", "none", "dryCell"};
        for (int s = 0; s < scenarios.length; s++) {
            List<BlockPos> beforeList = new ArrayList<>();
            List<BlockPos> afterList = new ArrayList<>();
            boolean beforeRet = updateShapeBefore(scenarios[s], 500, 60, -300, beforeList);
            boolean afterRet = updateShapeAfter(scenarios[s], 500, 60, -300, afterList);
            if (beforeRet != afterRet) {
                System.out.println("MISMATCH ret @" + names[s] + ": " + beforeRet + " vs " + afterRet);
                System.exit(1);
            }
            if (beforeList.size() != afterList.size()) {
                System.out.println("MISMATCH size @" + names[s] + ": " + beforeList.size() + " vs " + afterList.size());
                System.exit(1);
            }
            for (int i = 0; i < beforeList.size(); i++) {
                BlockPos b = beforeList.get(i);
                BlockPos a = afterList.get(i);
                if (b.x != a.x || b.y != a.y || b.z != a.z) {
                    System.out.println("MISMATCH pos @" + names[s] + " idx " + i);
                    System.exit(1);
                }
            }
        }
        // 环位置计数恒等检查：复刻环枚举必须恰好 42 个
        int ringCount = 0;
        for (int a = -2; a <= 2; a++) {
            for (int b = -2; b <= 2; b++) {
                for (int c = -2; c <= 2; c++) {
                    if (Level.ringIndex(a, b, c) >= 0) {
                        ringCount++;
                    }
                }
            }
        }
        if (ringCount != 42) {
            System.out.println("MISMATCH ringCount: " + ringCount);
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
