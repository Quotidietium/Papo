package papo.bench;

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
 * 批次39 / 0159+0160: 信标位置复用。
 * (0159) updateBase 基座扫描：before 每方块 new BlockPos（至多 164 次/80tick），
 *        after 共享 scratch MutableBlockPos。循环结构逐字复刻（4 层 (2i+1)^2 扫描），
 *        getBlockState 复刻为坐标哈希查表（方块是否基座方块）。
 * (0160) tick 光柱扫描：before 每 tick new MutableBlockPos，after 实体字段跨 tick 复用。
 *        复刻 10 步光柱循环（高度表 + 染色块命中）。
 * 注意：两路径 pos 均不逃逸，EA 可能抹平 before 分配（复刻内中性则载明机制，0140/0157 先例）。
 * main 自检：两路径基座等级判定矩阵（完整/缺角/分层缺失/非基座）与光柱扫描轨迹一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class BeaconPosScratchBench {

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

        int getY() {
            return this.y;
        }

        void setY(int y) {
            this.y = y;
        }
    }

    /** Level 基座查询语义复刻：信标正下方 (y-1..y-4) 为基座方块，余者非。 */
    static class Level {
        final int beaconY;
        final int minY = 0;

        Level(int beaconY) {
            this.beaconY = beaconY;
        }

        boolean isBaseBlock(int x, int y, int z) {
            return y < this.beaconY && y >= this.beaconY - 4;
        }

        int getMinY() {
            return this.minY;
        }
    }

    private Level level;
    private int bx;
    private int by;
    private int bz;
    private final MutableBlockPos fieldScratch = new MutableBlockPos(); // 0160 实体字段复刻

    @Setup
    public void setup() {
        this.level = new Level(100);
        this.bx = 500;
        this.by = 100;
        this.bz = -300;
    }

    // ---- 0159 ----
    private static int updateBaseBefore(Level level, int x, int y, int z) {
        int i = 0;
        for (int i1 = 1; i1 <= 4; i = i1++) {
            int i2 = y - i1;
            if (i2 < level.getMinY()) {
                break;
            }
            boolean flag = true;
            for (int i3 = x - i1; i3 <= x + i1 && flag; i3++) {
                for (int i4 = z - i1; i4 <= z + i1; i4++) {
                    BlockPos pos = new BlockPos(i3, i2, i4); // before: 每方块分配
                    if (!level.isBaseBlock(pos.x, pos.y, pos.z)) {
                        flag = false;
                        break;
                    }
                }
            }
            if (!flag) {
                break;
            }
        }
        return i;
    }

    private static int updateBaseAfter(Level level, int x, int y, int z) {
        int i = 0;
        MutableBlockPos scratch = new MutableBlockPos();
        for (int i1 = 1; i1 <= 4; i = i1++) {
            int i2 = y - i1;
            if (i2 < level.getMinY()) {
                break;
            }
            boolean flag = true;
            for (int i3 = x - i1; i3 <= x + i1 && flag; i3++) {
                for (int i4 = z - i1; i4 <= z + i1; i4++) {
                    scratch.set(i3, i2, i4); // after: scratch 复用
                    if (!level.isBaseBlock(scratch.x, scratch.y, scratch.z)) {
                        flag = false;
                        break;
                    }
                }
            }
            if (!flag) {
                break;
            }
        }
        return i;
    }

    @Benchmark
    public int before_baseScanAlloc(Blackhole bh) {
        int levels = updateBaseBefore(this.level, this.bx, this.by, this.bz);
        bh.consume(levels);
        return levels;
    }

    @Benchmark
    public int after_baseScanScratch(Blackhole bh) {
        int levels = updateBaseAfter(this.level, this.bx, this.by, this.bz);
        bh.consume(levels);
        return levels;
    }

    // ---- 0160 ----
    @Benchmark
    public int before_tickScanAlloc(Blackhole bh) {
        MutableBlockPos blockPos = new MutableBlockPos(); // before: 每 tick 分配
        blockPos.set(this.bx, this.by + 1, this.bz);
        int steps = 0;
        for (int i = 0; i < 10 && blockPos.getY() <= this.by + 10; i++) {
            bh.consume(blockPos.getY());
            blockPos.setY(blockPos.getY() + 1);
            steps++;
        }
        return steps;
    }

    @Benchmark
    public int after_tickScanField(Blackhole bh) {
        MutableBlockPos blockPos = this.fieldScratch; // after: 字段复用
        blockPos.set(this.bx, this.by + 1, this.bz);
        int steps = 0;
        for (int i = 0; i < 10 && blockPos.getY() <= this.by + 10; i++) {
            bh.consume(blockPos.getY());
            blockPos.setY(blockPos.getY() + 1);
            steps++;
        }
        return steps;
    }

    /** 等价性自检：基座判定矩阵 + 光柱轨迹一致。 */
    public static void main(String[] args) {
        // 完整 4 层信标
        Level full = new Level(100);
        if (updateBaseBefore(full, 500, 100, -300) != updateBaseAfter(full, 500, 100, -300)) {
            System.out.println("MISMATCH full");
            System.exit(1);
        }
        // 缺角（(500-2, 98, -300-2) 非基座 -> 2 层）
        Level partial = new Level(100) {
            @Override
            boolean isBaseBlock(int x, int y, int z) {
                if (y == 98 && x <= 498 && z <= -302) {
                    return false;
                }
                return super.isBaseBlock(x, y, z);
            }
        };
        if (updateBaseBefore(partial, 500, 100, -300) != updateBaseAfter(partial, 500, 100, -300)) {
            System.out.println("MISMATCH partial");
            System.exit(1);
        }
        // 顶层缺失 -> 0 层
        Level broken = new Level(100) {
            @Override
            boolean isBaseBlock(int x, int y, int z) {
                if (y == 99 && x == 500 && z == -300) {
                    return false;
                }
                return super.isBaseBlock(x, y, z);
            }
        };
        if (updateBaseBefore(broken, 500, 100, -300) != updateBaseAfter(broken, 500, 100, -300)) {
            System.out.println("MISMATCH broken");
            System.exit(1);
        }
        // 低 y 截断（minY）
        if (updateBaseBefore(full, 500, 2, -300) != updateBaseAfter(full, 500, 2, -300)) {
            System.out.println("MISMATCH minY");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
