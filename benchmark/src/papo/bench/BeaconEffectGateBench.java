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
 * 批次39 / 0162: BeaconEffectEvent 零监听器快路。
 * before（原版每 80 tick 每激活信标）：共享 toBukkit（PotionEffect 包装）+ CraftBlock
 * + 每玩家（BeaconEffectEvent + callEvent 0 监听器派发 + fromBukkit 六参构造含 clamp）。
 * after：监听器长度检查 + 每玩家 copy 构造（setDetailsFrom 字段复制）。
 * 语义复刻：MobEffectInstance 七字段（effect/duration/amplifier/ambient/visible/showIcon/
 * hiddenEffect）+ copy 构造与 fromBukkit 往返（注册表双向映射以恒等复刻）+ addEffect
 * 记录实收实例。事件经 Blackhole 强制逃逸（真实路径 callEvent 发布）。
 * main 自检：两路径每玩家实收实例逐字段相等、调用次数一致（含 0/1/多玩家与空玩家表）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class BeaconEffectGateBench {

    /** MobEffectInstance 语义复刻。 */
    static final class EffectInstance {
        final Object effect;
        int duration;
        int amplifier;
        boolean ambient;
        boolean visible;
        boolean showIcon;
        EffectInstance hiddenEffect;

        EffectInstance(Object effect, int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon, EffectInstance hiddenEffect) {
            this.effect = effect;
            this.duration = duration;
            this.amplifier = Math.max(0, Math.min(amplifier, 255)); // Mth.clamp(0,255)
            this.ambient = ambient;
            this.visible = visible;
            this.showIcon = showIcon;
            this.hiddenEffect = hiddenEffect;
        }

        /** 五参构造复刻：showIcon = visible（信标 applyEffects 使用的构造）。 */
        EffectInstance(Object effect, int duration, int amplifier, boolean ambient, boolean visible) {
            this(effect, duration, amplifier, ambient, visible, visible, null);
        }

        /** copy 构造复刻（setDetailsFrom：无 clamp 直复制，信标 amplifier 0/1 原本即在界内）。 */
        EffectInstance(EffectInstance other) {
            this.effect = other.effect;
            this.duration = other.duration;
            this.amplifier = other.amplifier;
            this.ambient = other.ambient;
            this.visible = other.visible;
            this.showIcon = other.showIcon;
            this.hiddenEffect = other.hiddenEffect;
        }

        boolean fieldEquals(EffectInstance o) {
            return this.effect == o.effect
                && this.duration == o.duration
                && this.amplifier == o.amplifier
                && this.ambient == o.ambient
                && this.visible == o.visible
                && this.showIcon == o.showIcon
                && this.hiddenEffect == o.hiddenEffect;
        }
    }

    /** Bukkit PotionEffect 包装复刻。 */
    static final class PotionEffect {
        final Object type;
        final int duration;
        final int amplifier;
        final boolean ambient;
        final boolean particles;
        final boolean icon;
        final PotionEffect hidden;

        PotionEffect(Object type, int duration, int amplifier, boolean ambient, boolean particles, boolean icon, PotionEffect hidden) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
            this.particles = particles;
            this.icon = icon;
            this.hidden = hidden;
        }
    }

    /** CraftBlock 复刻。 */
    static final class CraftBlock {
        final int x;
        final int y;
        final int z;

        CraftBlock(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** BeaconEffectEvent 复刻（0 监听器：callEvent 恒 true，getEffect 恒为构造值）。 */
    static final class BeaconEffectEvent {
        final CraftBlock block;
        final PotionEffect effect;
        final Object player;
        final boolean primary;

        BeaconEffectEvent(CraftBlock block, PotionEffect effect, Object player, boolean primary) {
            this.block = block;
            this.effect = effect;
            this.player = player;
            this.primary = primary;
        }

        boolean callEvent() {
            return true;
        }

        PotionEffect getEffect() {
            return this.effect;
        }
    }

    /** 玩家复刻：addEffect 覆盖式记录（批次36 BroadcastLoopBench 教训：有界存储防跨调用增长噪声）。 */
    static final class Player {
        EffectInstance last;
        int count;

        void addEffect(EffectInstance instance) {
            this.last = instance;
            this.count++;
        }
    }

    /** CraftPotionUtil.toBukkit 复刻（注册表双向映射以恒等复刻）。 */
    static PotionEffect toBukkit(EffectInstance effect) {
        return new PotionEffect(
            effect.effect, effect.duration, effect.amplifier, effect.ambient, effect.visible, effect.showIcon,
            effect.hiddenEffect == null ? null : toBukkit(effect.hiddenEffect)
        );
    }

    /** CraftPotionUtil.fromBukkit 复刻（hidden 不复制——信标实例 hidden 为 null，无差异）。 */
    static EffectInstance fromBukkit(PotionEffect effect) {
        return new EffectInstance(effect.type, effect.duration, effect.amplifier, effect.ambient, effect.particles, effect.icon, null);
    }

    private List<Player> players;
    private EffectInstance beaconEffect;
    private CraftBlock pos;

    @Setup
    public void setup() {
        this.players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            this.players.add(new Player());
        }
        this.beaconEffect = new EffectInstance(new Object(), 260, 1, true, true); // 4 级信标 primary
        this.pos = new CraftBlock(100, 70, 100);
    }

    /** before：原版事件路径（0 监听器）。 */
    @Benchmark
    public int before_eventPath(Blackhole bh) {
        PotionEffect apiEffect = toBukkit(this.beaconEffect);
        CraftBlock apiBlock = this.pos;
        int applied = 0;
        for (Player player : this.players) {
            BeaconEffectEvent event = new BeaconEffectEvent(apiBlock, apiEffect, player, true);
            bh.consume(event); // callEvent 发布逃逸
            if (!event.callEvent()) {
                continue;
            }
            player.addEffect(fromBukkit(event.getEffect()));
            applied++;
        }
        return applied;
    }

    /** after：零监听器快路。 */
    @Benchmark
    public int after_zeroListenerFastPath(Blackhole bh) {
        int applied = 0;
        for (Player player : this.players) {
            player.addEffect(new EffectInstance(this.beaconEffect));
            applied++;
        }
        bh.consume(applied);
        return applied;
    }

    /** 等价性自检（列表记录版，与基准路径的覆盖式记录等价性无关——直接核对实收矩阵）。 */
    public static void main(String[] args) {
        int[][] playerCounts = {{0}, {1}, {8}};
        for (int[] pc : playerCounts) {
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < pc[0]; i++) {
                players.add(new Player());
            }
            EffectInstance beaconEffect = new EffectInstance(new Object(), 260, 1, true, true);
            CraftBlock pos = new CraftBlock(100, 70, 100);

            // before
            List<EffectInstance> beforeReceived = new ArrayList<>();
            PotionEffect apiEffect = toBukkit(beaconEffect);
            for (Player player : players) {
                BeaconEffectEvent event = new BeaconEffectEvent(pos, apiEffect, player, true);
                if (event.callEvent()) {
                    EffectInstance inst = fromBukkit(event.getEffect());
                    player.addEffect(inst);
                    beforeReceived.add(inst);
                }
            }
            // after
            List<EffectInstance> afterReceived = new ArrayList<>();
            for (Player player : players) {
                EffectInstance inst = new EffectInstance(beaconEffect);
                afterReceived.add(inst);
            }
            if (beforeReceived.size() != afterReceived.size()) {
                System.out.println("MISMATCH count @" + pc[0]);
                System.exit(1);
            }
            for (int i = 0; i < beforeReceived.size(); i++) {
                if (!beforeReceived.get(i).fieldEquals(afterReceived.get(i))) {
                    System.out.println("MISMATCH fields @" + pc[0] + " player " + i);
                    System.exit(1);
                }
                // 副本独立性：改 after 副本 duration 不影响共享原实例
                afterReceived.get(i).duration = 1;
                if (beaconEffect.duration != 260) {
                    System.out.println("MISMATCH shared-instance mutation");
                    System.exit(1);
                }
            }
        }
        System.out.println("ALL OK");
    }
}
