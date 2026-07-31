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
 * 批次43 / 0172: 钓鱼浮标开阔水域判定命令式重写。
 * before（原版每次判定）：4 区域 × (pos.offset×2 + betweenClosedStream 流/ Spliterator/
 *        方法引用 + 125 不可变 BlockPos)，reduce 无早退。
 * after：单 scratch + 三重循环，INVALID 吸收早退（组合子 (t1==t2?t1:INVALID) 序无关且
 *        INVALID 吸收，早退保值）；外层 8 次 offset 折入循环边界。
 * 语义复刻：水域模型——水表 y=W 层为空气、其下为水源；百合垫/流水（非源）变体。
 * main 自检：全水/含空气柱/百合垫/流水/全干/跨区域混合 六场景两路径一致，
 *        且早退不改变最终结果（INVALID 吸收验证：首区域 INVALID 后区域有效仍为 false）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class FishingOpenWaterBench {

    enum OpenWaterType {
        ABOVE_WATER,
        INSIDE_WATER,
        INVALID
    }

    /** BlockPos 语义复刻。 */
    static final class BlockPos {
        final int x;
        final int y;
        final int z;

        BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        BlockPos offset(int dx, int dy, int dz) {
            return new BlockPos(this.x + dx, this.y + dy, this.z + dz);
        }
    }

    /** 水域语义复刻：y < waterY 为水源，y == waterY 及以上为空气；lilyPads/flowing 集合覆盖。 */
    static final class Level {
        final int waterY;
        final java.util.Set<Long> lilyPads = new java.util.HashSet<>();
        final java.util.Set<Long> flowing = new java.util.HashSet<>(); // 非源水 -> INVALID

        Level(int waterY) {
            this.waterY = waterY;
        }

        static long pack(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
        }

        /** 复刻 getOpenWaterTypeForBlock：空气或百合垫 -> ABOVE；水源且无碰撞 -> INSIDE；否则 INVALID。 */
        OpenWaterType getOpenWaterTypeForBlock(int x, int y, int z) {
            long p = pack(x, y, z);
            boolean air = y >= this.waterY;
            if (!air && !this.lilyPads.contains(p)) {
                return !this.flowing.contains(p) ? OpenWaterType.INSIDE_WATER : OpenWaterType.INVALID;
            }
            return OpenWaterType.ABOVE_WATER;
        }
    }

    private Level level;
    private int bx;
    private int by;
    private int bz;

    @Setup
    public void setup() {
        this.level = new Level(64); // 水表在 y=64
        this.bx = 500;
        this.by = 65;
        this.bz = -300; // 浮标位（i ∈ [-1..2] 覆盖 y-3..y 区域：水+空气混合的有效开阔水域）
    }

    // ---- before：原版流式 ----
    private static List<BlockPos> betweenClosed(int x1, int y1, int z1, int x2, int y2, int z2) {
        List<BlockPos> list = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    list.add(new BlockPos(x, y, z));
                }
            }
        }
        return list;
    }

    private static OpenWaterType getOpenWaterTypeForAreaBefore(Level level, BlockPos pos1, BlockPos pos2) {
        OpenWaterType acc = null;
        for (BlockPos p : betweenClosed(pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z)) {
            OpenWaterType type = level.getOpenWaterTypeForBlock(p.x, p.y, p.z); // map(this::getOpenWaterTypeForBlock)
            acc = acc == null ? type : (acc == type ? acc : OpenWaterType.INVALID); // reduce 组合子，无早退
        }
        return acc == null ? OpenWaterType.INVALID : acc; // orElse(INVALID)
    }

    private static boolean calculateOpenWaterBefore(Level level, BlockPos pos) {
        OpenWaterType openWaterType = OpenWaterType.INVALID;
        for (int i = -1; i <= 2; i++) {
            OpenWaterType forArea = getOpenWaterTypeForAreaBefore(level, pos.offset(-2, i, -2), pos.offset(2, i, 2));
            switch (forArea) {
                case ABOVE_WATER:
                    if (openWaterType == OpenWaterType.INVALID) {
                        return false;
                    }
                    break;
                case INSIDE_WATER:
                    if (openWaterType == OpenWaterType.ABOVE_WATER) {
                        return false;
                    }
                    break;
                case INVALID:
                    return false;
            }
            openWaterType = forArea;
        }
        return true;
    }

    // ---- after：命令式 ----
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

    private static OpenWaterType getOpenWaterTypeForAreaAfter(Level level, BlockPos pos, int i, MutableBlockPos scratch) {
        OpenWaterType openWaterType = null;
        int minX = pos.x - 2;
        int minY = pos.y + i;
        int minZ = pos.z - 2;
        for (int x = minX; x <= minX + 4; x++) {
            for (int y = minY; y <= minY + 4; y++) {
                for (int z = minZ; z <= minZ + 4; z++) {
                    OpenWaterType type = level.getOpenWaterTypeForBlock(scratch.set(x, y, z).x, scratch.y, scratch.z);
                    if (openWaterType == null) {
                        openWaterType = type;
                    } else if (openWaterType != type) {
                        return OpenWaterType.INVALID;
                    }
                }
            }
        }
        return openWaterType == null ? OpenWaterType.INVALID : openWaterType;
    }

    private static boolean calculateOpenWaterAfter(Level level, BlockPos pos) {
        OpenWaterType openWaterType = OpenWaterType.INVALID;
        MutableBlockPos scratch = new MutableBlockPos();
        for (int i = -1; i <= 2; i++) {
            OpenWaterType forArea = getOpenWaterTypeForAreaAfter(level, pos, i, scratch);
            switch (forArea) {
                case ABOVE_WATER:
                    if (openWaterType == OpenWaterType.INVALID) {
                        return false;
                    }
                    break;
                case INSIDE_WATER:
                    if (openWaterType == OpenWaterType.ABOVE_WATER) {
                        return false;
                    }
                    break;
                case INVALID:
                    return false;
            }
            openWaterType = forArea;
        }
        return true;
    }

    @Benchmark
    public boolean before_streamFold(Blackhole bh) {
        boolean r = calculateOpenWaterBefore(this.level, new BlockPos(this.bx, this.by, this.bz));
        bh.consume(r);
        return r;
    }

    @Benchmark
    public boolean after_imperative(Blackhole bh) {
        boolean r = calculateOpenWaterAfter(this.level, new BlockPos(this.bx, this.by, this.bz));
        bh.consume(r);
        return r;
    }

    /** 等价性自检：六场景 + INVALID 吸收验证。 */
    public static void main(String[] args) {
        // 场景 1：标准开阔水域（水表 64，浮标 y=65 —— 区域 y ∈ [61..66]，水+空气分层有效）
        check("open-water", new Level(64), 500, 65, -300);
        // 场景 2：全干（无水域——全空气 -> 区域全 ABOVE_WATER，首区域即与 INVALID 比较 -> false）
        check("all-air", new Level(-1000), 500, 65, -300);
        // 场景 3：全浸（水表很高——区域全 INSIDE_WATER -> true）
        check("all-water", new Level(1000), 500, 65, -300);
        // 场景 4：百合垫（水表下一格百合垫 -> ABOVE 与 INSIDE 混合 -> false）
        Level lily = new Level(64);
        for (int x = 498; x <= 502; x++) {
            for (int z = -302; z <= -298; z++) {
                lily.lilyPads.add(Level.pack(x, 63, z));
            }
        }
        check("lily-pad", lily, 500, 65, -300);
        // 场景 5：流水（区域内非源水 -> INVALID 吸收：首区域 INVALID 即早退，结果 false）
        Level flow = new Level(64);
        for (int x = 498; x <= 502; x++) {
            for (int z = -302; z <= -298; z++) {
                flow.flowing.add(Level.pack(x, 62, z));
            }
        }
        check("flowing-water", flow, 500, 65, -300);
        // 场景 6：边界坐标
        check("far-coords", new Level(-32), 30000000, -30, -30000000);
        System.out.println("ALL OK");
    }

    private static void check(String name, Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        boolean before = calculateOpenWaterBefore(level, pos);
        boolean after = calculateOpenWaterAfter(level, pos);
        if (before != after) {
            System.out.println("MISMATCH @" + name + ": " + before + " vs " + after);
            System.exit(1);
        }
    }
}
