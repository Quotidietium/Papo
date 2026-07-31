package papo.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * 批次38 / 0157: MapItemSavedData.addDecoration 稳态比较优先。
 * before（原版）：每 tick 每携带者计算位置（玩家类型经 Pair + MapDecorationLocation
 * 两次记录分配）+ 构造 MapDecoration + put + equals 丢弃。
 * after：目标字段算入局部量，与现存装饰逐字段相等则直接返回（0 分配）；仅变化时分配。
 * 语义复刻：clampMapCoordinate / isInsideMap / calculateRotation（非下界分支）逐公式，
 * record 等价（Holder 以标识比较复刻 —— 0157 用 Objects.equals，Holder 未覆写 equals，
 * 同义）。main 自检：静止/移动/旋转/出入界脚本序列下两实现的 map 内容、标脏次数、
 * 追踪计数逐步一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MapDecorationBench {

    /** MapDecoration 语义复刻（type 以标识比较，复刻 Holder 未覆写 equals 的事实）。 */
    record Decoration(Object type, byte x, byte y, byte rot, Optional<String> name) {}

    /** 位置记录复刻（before 路径每 tick 分配）。 */
    record Location(Object type, byte x, byte y, byte rot) {}

    static final class Pair {
        final Object first;
        final byte second;

        Pair(Object first, byte second) {
            this.first = first;
            this.second = second;
        }
    }

    /** MapDecorationTypes 全局 Holder 单例复刻（两实例间共享，与注册表 Holder 一致）。 */
    static final Object PLAYER_TYPE = new Object();
    static final Object PLAYER_OFF_MAP = new Object();
    static final Object PLAYER_OFF_LIMITS = new Object();

    /** MapItemSavedData 相关状态复刻。 */
    @SuppressWarnings("unused")
    static final class MapData {
        final Map<String, Decoration> decorations = new LinkedHashMap<>();
        final Object playerType = PLAYER_TYPE;
        final Object playerOffMap = PLAYER_OFF_MAP;
        final Object playerOffLimits = PLAYER_OFF_LIMITS;
        int centerX = 0;
        int centerZ = 0;
        byte scale = 0;
        boolean unlimitedTracking = false;
        int trackedDecorationCount;
        int dirtyMarks;
        boolean trackCountType = true; // PLAYER type 的 trackCount

        void setDecorationsDirty() {
            this.dirtyMarks++;
        }

        void removeDecoration(String id) {
            Decoration old = this.decorations.remove(id);
            if (old != null && this.trackCountType) {
                this.trackedDecorationCount--;
            }
            if (old != null) {
                this.setDecorationsDirty(); // Paper 语义：仅真实移除才标脏
            }
        }

        byte calculateRotation(double yRot) {
            double d = yRot < 0.0 ? yRot - 8.0 : yRot + 8.0;
            return (byte) (d * 16.0 / 360.0);
        }

        static boolean isInsideMap(float x, float z) {
            return x >= -63.0F && z >= -63.0F && x <= 63.0F && z <= 63.0F;
        }

        static byte clampMapCoordinate(float coord) {
            if (coord <= -63.0F) {
                return -128;
            } else {
                return coord >= 63.0F ? 127 : (byte) (coord * 2.0F + 0.5);
            }
        }

        Object decorationTypeForPlayerOutsideMap(float x, float z) {
            boolean flag = Math.abs(x) < 320.0F && Math.abs(z) < 320.0F;
            if (flag) {
                return this.playerOffMap;
            } else {
                return this.unlimitedTracking ? this.playerOffLimits : null;
            }
        }

        // ---- before（原版流程）：Pair + Location + Decoration 每 tick 分配 ----
        Pair playerDecorationTypeAndRotation(Object decorationType, double yRot, float x, float z) {
            if (isInsideMap(x, z)) {
                return new Pair(decorationType, this.calculateRotation(yRot));
            } else {
                Object holder = this.decorationTypeForPlayerOutsideMap(x, z);
                return holder == null ? null : new Pair(holder, (byte) 0);
            }
        }

        Location calculateDecorationLocationAndType(Object decorationType, double yRot, float x, float z) {
            byte b = clampMapCoordinate(x);
            byte b1 = clampMapCoordinate(z);
            if (decorationType == this.playerType) {
                Pair pair = this.playerDecorationTypeAndRotation(decorationType, yRot, x, z);
                return pair == null ? null : new Location(pair.first, b, b1, pair.second);
            } else {
                return !isInsideMap(x, z) && !this.unlimitedTracking
                    ? null
                    : new Location(decorationType, b, b1, this.calculateRotation(yRot));
            }
        }

        void addDecorationBefore(Object decorationType, String id, double x, double z, double yRot, String displayName) {
            int i = 1 << this.scale;
            float f = (float) (x - this.centerX) / i;
            float f1 = (float) (z - this.centerZ) / i;
            Location loc = this.calculateDecorationLocationAndType(decorationType, yRot, f, f1);
            if (loc == null) {
                this.removeDecoration(id);
            } else {
                Decoration deco = new Decoration(loc.type(), loc.x(), loc.y(), loc.rot(), Optional.ofNullable(displayName));
                Decoration old = this.decorations.put(id, deco);
                if (!deco.equals(old)) {
                    if (old != null && this.trackCountType) {
                        this.trackedDecorationCount--;
                    }
                    if (this.trackCountType) {
                        this.trackedDecorationCount++;
                    }
                    this.setDecorationsDirty();
                }
            }
        }

        // ---- after（0157）：比较优先，稳态 0 分配 ----
        void addDecorationAfter(Object decorationType, String id, double x, double z, double yRot, String displayName) {
            int i = 1 << this.scale;
            float f = (float) (x - this.centerX) / i;
            float f1 = (float) (z - this.centerZ) / i;
            byte b = clampMapCoordinate(f);
            byte b1 = clampMapCoordinate(f1);
            Object type;
            byte rot;
            if (decorationType == this.playerType) {
                if (isInsideMap(f, f1)) {
                    type = decorationType;
                    rot = this.calculateRotation(yRot);
                } else {
                    Object holder = this.decorationTypeForPlayerOutsideMap(f, f1);
                    if (holder == null) {
                        this.removeDecoration(id);
                        return;
                    }
                    type = holder;
                    rot = 0;
                }
            } else if (!isInsideMap(f, f1) && !this.unlimitedTracking) {
                this.removeDecoration(id);
                return;
            } else {
                type = decorationType;
                rot = this.calculateRotation(yRot);
            }

            Optional<String> name = Optional.ofNullable(displayName);
            Decoration old = this.decorations.get(id);
            if (old != null
                && Objects.equals(type, old.type())
                && b == old.x()
                && b1 == old.y()
                && rot == old.rot()
                && Objects.equals(name, old.name())) {
                return;
            }

            this.decorations.put(id, new Decoration(type, b, b1, rot, name));
            if (old != null && this.trackCountType) {
                this.trackedDecorationCount--;
            }
            if (this.trackCountType) {
                this.trackedDecorationCount++;
            }
            this.setDecorationsDirty();
        }
    }

    private MapData beforeMap;
    private MapData afterMap;
    private double px;
    private double pz;
    private double rot;

    @Setup
    public void setup() {
        this.beforeMap = new MapData();
        this.afterMap = new MapData();
        // 预置稳态装饰（模拟已运行一段）
        this.px = 512.5;
        this.pz = -120.25;
        this.rot = 90.0;
        this.beforeMap.addDecorationBefore(this.beforeMap.playerType, "player", this.px, this.pz, this.rot, null);
        this.afterMap.addDecorationAfter(this.afterMap.playerType, "player", this.px, this.pz, this.rot, null);
    }

    @Benchmark
    public int before_steadyTick(Blackhole bh) {
        this.beforeMap.addDecorationBefore(this.beforeMap.playerType, "player", this.px, this.pz, this.rot, null);
        bh.consume(this.beforeMap.decorations);
        return this.beforeMap.dirtyMarks;
    }

    @Benchmark
    public int after_steadyTick(Blackhole bh) {
        this.afterMap.addDecorationAfter(this.afterMap.playerType, "player", this.px, this.pz, this.rot, null);
        bh.consume(this.afterMap.decorations);
        return this.afterMap.dirtyMarks;
    }

    /** 等价性自检：脚本化序列逐步比对。 */
    public static void main(String[] args) {
        MapData a = new MapData();
        MapData b = new MapData();
        // 脚本：静止 → 旋转 → 移动 → 出界(>=63) → 远出界(>=320, 非 unlimited -> 移除) → 回界
        double[][] script = {
            {10.0, 10.0, 0.0}, {10.0, 10.0, 0.0}, {10.0, 10.0, 45.0}, {10.0, 10.0, 45.0},
            {64.0, 10.0, 90.0}, {64.0, 10.0, 90.0}, {20000.0, 10.0, 180.0}, {20000.0, 10.0, 180.0},
            {0.0, 0.0, -90.0}, {0.0, 0.0, -90.0}, {-63.9, 63.9, 359.0}, {-63.9, 63.9, 359.0},
        };
        for (int step = 0; step < script.length; step++) {
            double x = script[step][0];
            double z = script[step][1];
            double r = script[step][2];
            a.addDecorationBefore(a.playerType, "player", x, z, r, null);
            b.addDecorationAfter(b.playerType, "player", x, z, r, null);
            if (!a.decorations.equals(b.decorations)
                || a.trackedDecorationCount != b.trackedDecorationCount
                || a.dirtyMarks != b.dirtyMarks) {
                System.out.println("MISMATCH step " + step
                    + ": decoEq=" + a.decorations.equals(b.decorations)
                    + " count=" + a.trackedDecorationCount + "/" + b.trackedDecorationCount
                    + " dirty=" + a.dirtyMarks + "/" + b.dirtyMarks);
                System.exit(1);
            }
        }
        // 带名旗帜路径（非玩家类型 + displayName）
        Object banner = new Object();
        a.addDecorationBefore(banner, "banner", 5.0, 5.0, 180.0, "base");
        b.addDecorationAfter(banner, "banner", 5.0, 5.0, 180.0, "base");
        a.addDecorationBefore(banner, "banner", 5.0, 5.0, 180.0, "base");
        b.addDecorationAfter(banner, "banner", 5.0, 5.0, 180.0, "base");
        if (!a.decorations.equals(b.decorations) || a.dirtyMarks != b.dirtyMarks) {
            System.out.println("MISMATCH banner");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
