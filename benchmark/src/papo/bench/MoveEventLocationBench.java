package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次35: ServerGamePacketListenerImpl PlayerMoveEvent from/to Location 阈值后延迟构造。
 * 原实现：每个移动包先构造 from（1 Location）+ to（getLocation+clone，2 Location），
 *         再算 delta/deltaAngle；多数包被 1/256 阈值过滤 → 3 个 Location 白分配。
 * 新实现：先按 absSnapTo 存储语义（x/z clamp ±3e7、yaw %360、pitch clamp±90 后 %360）算标量，
 *         阈值通过才构造 2 个 Location。
 * Location/Mth 语义复刻（float 存取、clone、Math.max/min clamp）。
 * main 自检：hasPos/hasRot × 常规/越界 yaw/pitch/超 3e7 坐标矩阵下，标量值与 Location 路径逐位一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MoveEventLocationBench {

    /** org.bukkit.Location 语义复刻（字段+clone）。 */
    static final class Location {
        double x, y, z;
        float yaw, pitch;
        Location(double x, double y, double z, float yaw, float pitch) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
        double getX() { return this.x; }
        double getY() { return this.y; }
        double getZ() { return this.z; }
        float getYaw() { return this.yaw; }
        float getPitch() { return this.pitch; }
        Location cloneLoc() { return new Location(this.x, this.y, this.z, this.yaw, this.pitch); }
        void setX(double v) { this.x = v; }
        void setY(double v) { this.y = v; }
        void setZ(double v) { this.z = v; }
        void setYaw(float v) { this.yaw = v; }
        void setPitch(float v) { this.pitch = v; }
    }

    /** Mth 语义复刻。 */
    static double clamp(double v, double min, double max) { return v < min ? min : (v > max ? max : v); }
    static float clamp(float v, float min, float max) { return v < min ? min : (v > max ? max : v); }
    static double square(double v) { return v * v; }

    // 模拟每包状态：lastPos/lastYaw 事件状态 + prev/packet 值
    private double lastPosX = 100.5, lastPosY = 64, lastPosZ = -200.5;
    private float lastYaw = 45f, lastPitch = 10f;
    private double prevX = 100.51, prevY = 64, prevZ = -200.49; // absSnapTo 前的当前位置（亚阈值移动）
    private float prevYaw = 45.05f, prevPitch = 10.02f;

    /** 原实现：先构造 from/to Location（含 getLocation+clone），再算阈值。返回是否过阈值。 */
    @Benchmark
    public boolean before_locationsFirst(Blackhole bh) {
        Location from = new Location(this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYaw, this.lastPitch);
        // player.getLocation() 在 absSnapTo(prev...) 之后：读回 clamp/归一化后的值
        Location to = new Location(
            clamp(this.prevX, -3.0E7, 3.0E7), this.prevY, clamp(this.prevZ, -3.0E7, 3.0E7),
            this.prevYaw % 360.0F, clamp(this.prevPitch, -90.0F, 90.0F) % 360.0F
        ).cloneLoc();
        // packet.hasPos/hasRot：本包为纯旋转包（不含位置）
        to.setYaw(45.06f);
        to.setPitch(10.03f);
        double delta = square(this.lastPosX - to.getX()) + square(this.lastPosY - to.getY()) + square(this.lastPosZ - to.getZ());
        float deltaAngle = Math.abs(this.lastYaw - to.getYaw()) + Math.abs(this.lastPitch - to.getPitch());
        boolean passed = delta > 1f / 256 || deltaAngle > 10f;
        bh.consume(from);
        bh.consume(to);
        return passed;
    }

    /** 批次35：标量先行，阈值不过零分配。 */
    @Benchmark
    public boolean after_scalarsFirst(Blackhole bh) {
        double toX = clamp(this.prevX, -3.0E7, 3.0E7); // !hasPos
        double toY = this.prevY;
        double toZ = clamp(this.prevZ, -3.0E7, 3.0E7);
        float toYaw = 45.06f;   // hasRot → packet.yRot
        float toPitch = 10.03f; // hasRot → packet.xRot
        double delta = square(this.lastPosX - toX) + square(this.lastPosY - toY) + square(this.lastPosZ - toZ);
        float deltaAngle = Math.abs(this.lastYaw - toYaw) + Math.abs(this.lastPitch - toPitch);
        boolean passed = delta > 1f / 256 || deltaAngle > 10f;
        if (passed) {
            // 阈值通过才构造（本场景不过 → 零分配）
            bh.consume(new Location(this.lastPosX, this.lastPosY, this.lastPosZ, this.lastYaw, this.lastPitch));
            bh.consume(new Location(toX, toY, toZ, toYaw, toPitch));
        }
        return passed;
    }

    /** 等价性自检：标量路径与 Location 路径的分量与阈值判定逐位一致（含归一化边界矩阵）。 */
    public static void main(String[] args) {
        double[] xs = {0.0, 100.5, -29999999.9, 3.0E7 + 100, -3.0E7 - 100, 12345678.25};
        float[] yaws = {0f, 45.5f, 360f, 720.25f, -90.5f, -720.75f, 359.999f};
        float[] pitches = {0f, 10f, 90f, -90f, 120f, -120f, 89.999f};
        for (double x : xs) {
            for (float yaw : yaws) {
                for (float pitch : pitches) {
                    for (int flags = 0; flags < 4; flags++) {
                        boolean hasPos = (flags & 1) != 0, hasRot = (flags & 2) != 0;
                        double px = 987.654, py = 70.0, pz = -123.456; // packet 值
                        float pyaw = 12.34f, ppitch = -56.78f;
                        // Location 路径（语义复刻原实现）
                        Location to = new Location(clamp(x, -3.0E7, 3.0E7), 64.0, clamp(-x, -3.0E7, 3.0E7), yaw % 360.0F, clamp(pitch, -90.0F, 90.0F) % 360.0F).cloneLoc();
                        if (hasPos) { to.setX(px); to.setY(py); to.setZ(pz); }
                        if (hasRot) { to.setYaw(pyaw); to.setPitch(ppitch); }
                        // 标量路径（批次35）
                        double sx = hasPos ? px : clamp(x, -3.0E7, 3.0E7);
                        double sy = hasPos ? py : 64.0;
                        double sz = hasPos ? pz : clamp(-x, -3.0E7, 3.0E7);
                        float syaw = hasRot ? pyaw : yaw % 360.0F;
                        float spitch = hasRot ? ppitch : clamp(pitch, -90.0F, 90.0F) % 360.0F;
                        if (Double.compare(to.getX(), sx) != 0 || Double.compare(to.getY(), sy) != 0 || Double.compare(to.getZ(), sz) != 0
                            || Float.compare(to.getYaw(), syaw) != 0 || Float.compare(to.getPitch(), spitch) != 0) {
                            System.out.println("MISMATCH x=" + x + " yaw=" + yaw + " pitch=" + pitch + " flags=" + flags);
                            System.exit(1);
                        }
                        // 阈值判定一致性
                        double d1 = square(0.5 - to.getX()) + square(64 - to.getY()) + square(-0.5 - to.getZ());
                        float a1 = Math.abs(1f - to.getYaw()) + Math.abs(2f - to.getPitch());
                        double d2 = square(0.5 - sx) + square(64 - sy) + square(-0.5 - sz);
                        float a2 = Math.abs(1f - syaw) + Math.abs(2f - spitch);
                        if ((d1 > 1f / 256 || a1 > 10f) != (d2 > 1f / 256 || a2 > 10f)) {
                            System.out.println("MISMATCH threshold"); System.exit(1);
                        }
                    }
                }
            }
        }
        System.out.println("ALL OK");
    }
}
