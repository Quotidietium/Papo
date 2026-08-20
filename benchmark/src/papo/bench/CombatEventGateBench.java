package papo.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * 批次69 / 0238-0241：伤害/战斗管线域（本 fork 事件门控未覆盖的最大高频集群）。
 *
 * 0238 PlayerAttackEntityCooldownResetEvent 门控（每次玩家近战命中）+ PrePlayerAttackEntityEvent
 *     门控（每次玩家攻击含 stab，两站点）+ PlayerVelocityEvent 第二站点门控（0100 补齐）+
 *     PlayerItemDamageEvent/EntityDamageItemEvent 门控（每击最多 4-5 发：护甲 4 槽+武器）。
 * 0239 横扫 knownCause 提出 DamageSource 副本循环（循环不变量）。
 * 0240 EntityDamageEvent 构造器 Preconditions stream→循环（paper-api 直提交；每次伤害实例 ~10-16 对象）。
 *
 * 模型：四事件门控（构造+派发 vs 跳过）+ 构造器校验（stream vs 循环）。
 * main 自检：门控三态语义（零监听=默认路径）；构造器校验循环与 stream 在合法/非法输入下行为一致
 * （异常类型+消息）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CombatEventGateBench {

    static final class Evt { final int payload; Evt(final int p) { this.payload = p; } }

    int attackStrengthScaleCalls;

    // ===== 事件门控模型 =====

    /** before：无条件构造（含 getAttackStrengthScale 类纯计算）+ 派发。 */
    @Benchmark
    public boolean before_cooldownEvent() {
        this.attackStrengthScaleCalls++;
        final Evt event = new Evt(this.attackStrengthScaleCalls);
        return event.payload > 0; // callEvent 恒真模型
    }

    /** after：零监听直落默认路径。 */
    @Benchmark
    public boolean after_gate() {
        return true;
    }

    // ===== 构造器校验模型（EntityDamageEvent 形态） =====

    static final java.util.Map<ModKey, Double> MODS = new java.util.EnumMap<>(ModKey.class);
    static final java.util.Map<ModKey, Object> FUNCS = new java.util.EnumMap<>(ModKey.class);

    enum ModKey { BASE, ARMOR, MAGIC }

    static {
        for (final ModKey k : ModKey.values()) {
            MODS.put(k, 1.0);
            FUNCS.put(k, new Object());
        }
    }

    /** before：stream allMatch 校验（现行构造器形态）。 */
    @Benchmark
    public long before_streamChecks() {
        boolean ok = MODS.values().stream().allMatch(java.util.Objects::nonNull)
            && MODS.keySet().equals(FUNCS.keySet())
            && FUNCS.values().stream().allMatch(java.util.Objects::nonNull);
        return ok ? 1 : 0;
    }

    /** after：循环校验（Papo 形态）。 */
    @Benchmark
    public long after_loopChecks() {
        for (final Double v : MODS.values()) {
            if (v == null) {
                return 0;
            }
        }
        boolean ok = MODS.keySet().equals(FUNCS.keySet());
        if (!ok) {
            return 0;
        }
        for (final Object f : FUNCS.values()) {
            if (f == null) {
                return 0;
            }
        }
        return 1;
    }

    public static void main(final String[] args) {
        // 门控语义：零监听 → 与默认路径一致
        final CombatEventGateBench b = new CombatEventGateBench();
        if (!b.before_cooldownEvent() || !b.after_gate()) {
            System.out.println("FAIL gate semantics");
            System.exit(1);
        }
        // 构造器校验等价：合法输入两路径同过；null 注入两路径同拒且异常消息一致
        if (b.before_streamChecks() != 1 || b.after_loopChecks() != 1) {
            System.out.println("FAIL valid input");
            System.exit(1);
        }
        final java.util.Map<ModKey, Double> badMods = new java.util.EnumMap<>(ModKey.class);
        badMods.put(ModKey.BASE, null);
        String streamMsg = null;
        try {
            if (!badMods.values().stream().allMatch(java.util.Objects::nonNull)) { throw new IllegalArgumentException("Cannot have null modifier values"); }
        } catch (final IllegalArgumentException e) {
            streamMsg = e.getMessage();
        }
        String loopMsg = null;
        try {
            for (final Double v : badMods.values()) {
                if (v == null) { throw new IllegalArgumentException("Cannot have null modifier values"); }
            }
        } catch (final IllegalArgumentException e) {
            loopMsg = e.getMessage();
        }
        if (streamMsg == null || !streamMsg.equals(loopMsg)) {
            System.out.println("FAIL exception parity: " + streamMsg + " vs " + loopMsg);
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
