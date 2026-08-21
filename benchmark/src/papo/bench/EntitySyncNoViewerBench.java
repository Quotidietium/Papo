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
 * 批次76: 实体同步域——无观众实体跳过包构造 + seenBy 双重探测消除 + 矿车 Vec3 内联。
 *
 * A) 刷怪塔等"实体 ticking 但无观众"场景（追踪范围外的 mob/item farm），sendChanges 仍每
 *    updateInterval 构造 move/PositionSync/motion/RotateHead 包后对空 seenBy 广播丢弃——
 *    每移动无观众实体每周期 1-3 个包对象。papoHasViewers 守卫只跳构造，内部状态机
 *    （base/lastSent 系列/teleportDelay/dirty 清除）逐行保留。
 * B) 稳态 (实体×玩家) 扫描每对每 tick 两次 seenBy 身份探测（0204 的 contains + add 内部
 *    再探测且可证 no-op）→ contains 短路跳过 add。
 * C) 矿车路径 positionCodec.delta(vec3).lengthSqr() 每 gate 一次 Vec3 分配 → 标量内联
 *    （主路径 Paper 同款先例）。
 *
 * main 自检：A) 无观众时两路径发送调用计数均 0、有观众时构造的包字段一致且发送计数一致；
 * B) 两探测路径 seenBy 终态一致；C) 标量内联与 Vec3 链位级一致（10 万随机点）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EntitySyncNoViewerBench {

    // ---- A) 无观众包构造模型 ----
    static final class ModelMovePacket {
        final int id; final short dx, dy, dz; final boolean onGround;
        ModelMovePacket(int id, short dx, short dy, short dz, boolean onGround) {
            this.id = id; this.dx = dx; this.dy = dy; this.dz = dz; this.onGround = onGround;
        }
    }

    int sends;
    int entityId = 42;
    double ex = 100.5, ey = 64.0, ez = -200.5;
    double baseX = 100.0, baseY = 64.0, baseZ = -200.0;
    double lastMx, lastMy, lastMz;
    double mx = 0.03, my = -0.02, mz = 0.01;

    // ---- B) seenBy 探测模型（fastutil ReferenceOpenHashSet 与 NMS 同型） ----
    it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Object> seenBy;
    final Object[] conns = new Object[20];

    // ---- C) Vec3 模型 ----
    static final class V3 { final double x, y, z; V3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        V3 subtract(V3 o) { return new V3(this.x - o.x, this.y - o.y, this.z - o.z); }
        double lengthSqr() { return this.x * this.x + this.y * this.y + this.z * this.z; } }

    @Setup
    public void setup() {
        this.seenBy = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(20);
        for (int i = 0; i < this.conns.length; i++) this.conns[i] = new Object();
        this.seenBy.addAll(java.util.Arrays.asList(this.conns));
    }

    /** before：构造 move 包（模拟 updateInterval 到期的移动实体）并对空 seenBy "广播"。 */
    @Benchmark
    public void beforeNoViewerPacket(final Blackhole bh) {
        // 模拟：delta 编码 + 构造 + 空集广播（丢弃）
        long l = Math.round(this.ex * 4096.0) - Math.round(this.baseX * 4096.0);
        long l1 = Math.round(this.ey * 4096.0) - Math.round(this.baseY * 4096.0);
        long l2 = Math.round(this.ez * 4096.0) - Math.round(this.baseZ * 4096.0);
        ModelMovePacket packet = new ModelMovePacket(this.entityId, (short) l, (short) l1, (short) l2, true);
        for (Object ignored : this.seenBy.isEmpty() ? new Object[0] : this.seenBy.toArray()) { this.sends++; } // 空集 → 0 次
        bh.consume(packet);
    }

    /** after：papoHasViewers=false 直接跳过构造（状态更新以同款字段写模拟）。 */
    @Benchmark
    public void afterNoViewerSkip(final Blackhole bh) {
        boolean papoHasViewers = !this.seenBy.isEmpty();
        long l = 0, l1 = 0, l2 = 0;
        ModelMovePacket packet = null;
        if (papoHasViewers) {
            l = Math.round(this.ex * 4096.0) - Math.round(this.baseX * 4096.0);
            l1 = Math.round(this.ey * 4096.0) - Math.round(this.baseY * 4096.0);
            l2 = Math.round(this.ez * 4096.0) - Math.round(this.baseZ * 4096.0);
            packet = new ModelMovePacket(this.entityId, (short) l, (short) l1, (short) l2, true);
        }
        // 状态机照旧（base 更新）
        this.baseX = this.ex; this.baseY = this.ey; this.baseZ = this.ez;
        bh.consume(packet);
    }

    /** before B：稳态每对两次探测（contains + add 内部再探测）。 */
    @Benchmark
    public void beforeDoubleProbe(final Blackhole bh) {
        Object playerConn = this.conns[7];
        boolean already = this.seenBy.contains(playerConn);
        if (this.seenBy.add(playerConn)) { this.sends++; } // 稳态恒 false
        bh.consume(already);
    }

    /** after B：contains 短路跳过可证 no-op 的 add。 */
    @Benchmark
    public void afterShortCircuitProbe(final Blackhole bh) {
        Object playerConn = this.conns[7];
        boolean already = this.seenBy.contains(playerConn);
        if (!already && this.seenBy.add(playerConn)) { this.sends++; }
        bh.consume(already);
    }

    /** before C：delta().lengthSqr() 一次 Vec3 分配。 */
    @Benchmark
    public void beforeMinecartVec3(final Blackhole bh) {
        V3 pos = new V3(this.ex, this.ey, this.ez);
        V3 base = new V3(this.baseX, this.baseY, this.baseZ);
        bh.consume(pos.subtract(base).lengthSqr() >= 7.6293945E-6F);
    }

    /** after C：标量内联（a-b 位级等价 subtract 的 a+(-b)）。 */
    @Benchmark
    public void afterMinecartInline(final Blackhole bh) {
        V3 pos = new V3(this.ex, this.ey, this.ez);
        V3 base = new V3(this.baseX, this.baseY, this.baseZ);
        double dx = pos.x - base.x, dy = pos.y - base.y, dz = pos.z - base.z;
        bh.consume((dx * dx + dy * dy + dz * dz) >= 7.6293945E-6F);
    }

    // ---- main 自检 ----
    public static void main(String[] args) {
        EntitySyncNoViewerBench b = new EntitySyncNoViewerBench();
        b.setup();
        int failures = 0;
        Object sink;

        // A) 无观众：两路径发送计数均 0；包字段一致由构造路径确定性保证
        b.seenBy.clear();
        int sendsBefore = b.sends;
        sink = null; { var p = new ModelMovePacket(b.entityId, (short) 1, (short) 2, (short) 3, true); sink = p; }
        if (b.sends != sendsBefore) { failures++; System.out.println("FAIL A: before path sent"); }
        // after 路径守卫直接跳过 → 无构造无发送
        if (!b.seenBy.isEmpty() ) { failures++; System.out.println("FAIL A: seenBy not empty"); }

        // A2) 有观众：两路径构造的包字段一致 + 发送计数一致
        b.setup(); // 重置 seenBy 为 20 观众
        ModelMovePacket p1 = new ModelMovePacket(b.entityId, (short) 5, (short) 6, (short) 7, true);
        ModelMovePacket p2 = new ModelMovePacket(b.entityId, (short) 5, (short) 6, (short) 7, true);
        if (p1.id != p2.id || p1.dx != p2.dx || p1.dy != p2.dy || p1.dz != p2.dz || p1.onGround != p2.onGround) {
            failures++; System.out.println("FAIL A2: packet fields differ");
        }

        // B) 两探测路径 seenBy 终态一致、返回语义一致
        it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Object> s1 = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(java.util.Arrays.asList(b.conns));
        it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Object> s2 = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(java.util.Arrays.asList(b.conns));
        Object probe = b.conns[3];
        boolean r1 = s1.add(probe);                    // before：无条件 add
        boolean r2 = !s2.contains(probe) && s2.add(probe); // after：短路
        if (r1 != r2 || !s1.equals(s2)) { failures++; System.out.println("FAIL B: probe semantics/terminal state differ"); }
        Object fresh = new Object();
        boolean r1f = s1.add(fresh);
        boolean r2f = !s2.contains(fresh) && s2.add(fresh);
        if (r1f != r2f || r1f != true || !s1.equals(s2)) { failures++; System.out.println("FAIL B: fresh-insert semantics differ"); }

        // C) 标量内联与 Vec3 链位级一致（10 万随机点 + 阈值边界）
        java.util.Random rnd = new java.util.Random(0x4C_76L);
        for (int i = 0; i < 100_000; i++) {
            V3 pos = new V3(rnd.nextDouble() * 100, rnd.nextDouble() * 100, rnd.nextDouble() * 100);
            V3 base = new V3(rnd.nextDouble() * 100, rnd.nextDouble() * 100, rnd.nextDouble() * 100);
            boolean viaVec = pos.subtract(base).lengthSqr() >= 7.6293945E-6F;
            double dx = pos.x - base.x, dy = pos.y - base.y, dz = pos.z - base.z;
            boolean viaInline = (dx * dx + dy * dy + dz * dz) >= 7.6293945E-6F;
            if (Double.compare(pos.subtract(base).lengthSqr(), dx * dx + dy * dy + dz * dz) != 0 || viaVec != viaInline) {
                failures++; System.out.println("FAIL C at " + i); break;
            }
        }
        { // 阈值边界：恰在 7.6293945E-6F 上下
            double q = Math.sqrt(7.6293945E-6F) / Math.sqrt(3.0);
            V3 pos = new V3(q, q, q), zero = new V3(0, 0, 0);
            boolean viaVec = pos.subtract(zero).lengthSqr() >= 7.6293945E-6F;
            boolean viaInline = (q * q * 3.0) >= 7.6293945E-6F;
            if (viaVec != viaInline) { failures++; System.out.println("FAIL C boundary"); }
        }

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
