package papo.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 批次58 / C1-C3：记分板冗余广播门控行为自检（非性能——行为等价性验证，先例 FingerprintHardeningSelfCheck）。
 *
 * C1 PlayerTeam：九个 setter 原版无条件 broadcast(team 参数包)；Papo 门控——布尔/枚举同值跳过，
 *    Component「不同实例但内容相等」跳过（客户端已持有相等内容 → 包是幂等重放），同实例重设保持广播
 *    （防 NMS MutableComponent 原位变异后靠重设刷新）。
 * C2 Objective：四个 setter 同法；formattedDisplayName 仅在真变更时重算。
 * C3 ServerScoreboard.setDisplayObjective：旧 objective 仍显示于其他槽且新 objective 已被追踪时，
 *    原版把同一个 (slot, objective) 包连发两次；Papo 去重为一次（两包逐字节相同、同 tick 相邻应用，
 *    客户端状态与发一次完全一致）。
 *
 * 复刻补丁语义（与 PlayerTeam/Objective/ServerScoreboard 的改动逐条对应），对比 vanilla 模型与 papo 模型：
 * 断言 1) 跳过的全是"等值幂等"包（末状态一致）；2) 真变更路径广播次数与 vanilla 完全一致；
 * 3) C3 双发路径恰好少一个"逐字节相同"的包，其余路径包数不变。
 */
public final class ScoreboardGateSelfCheck {

    static int failures = 0;

    // ---- 组件模型（内容相等 = equals）----
    static final class Comp {
        final String text;
        Comp(final String text) { this.text = text; }
        @Override public boolean equals(final Object o) { return o instanceof Comp && ((Comp) o).text.equals(this.text); }
        @Override public int hashCode() { return this.text.hashCode(); }
    }

    // ---- C1/C2：带门控的 Team/Objective 模型 ----
    static class TeamModel {
        int broadcasts = 0;
        Comp displayName;
        Comp prefix = new Comp("");
        boolean friendlyFire = true;
        boolean seeFriendlyInvisibles = true;
        final boolean papo;

        TeamModel(final boolean papo, final String name) {
            this.papo = papo;
            this.displayName = new Comp(name);
        }

        void onTeamChanged() { this.broadcasts++; }

        void setDisplayName(final Comp name) {
            if (name == null) throw new IllegalArgumentException();
            if (this.papo && name != this.displayName && name.equals(this.displayName)) return;
            this.displayName = name;
            this.onTeamChanged();
        }

        void setPlayerPrefix(final Comp prefix) {
            final Comp normalized = prefix == null ? new Comp("") : prefix;
            if (this.papo && prefix != this.prefix && Objects.equals(normalized, this.prefix)) return;
            this.prefix = normalized;
            this.onTeamChanged();
        }

        void setAllowFriendlyFire(final boolean v) {
            if (this.papo && v == this.friendlyFire) return;
            this.friendlyFire = v;
            this.onTeamChanged();
        }

        void setSeeFriendlyInvisibles(final boolean v) {
            if (this.papo && v == this.seeFriendlyInvisibles) return;
            this.seeFriendlyInvisibles = v;
            this.onTeamChanged();
        }

        void unpackOptions(final int flags) {
            this.setAllowFriendlyFire((flags & 1) > 0);
            this.setSeeFriendlyInvisibles((flags & 2) > 0);
        }
    }

    static class ObjectiveModel {
        int broadcasts = 0;
        int formattedRecomputes = 0;
        Comp displayName;
        String renderType = "INTEGER";
        final boolean papo;

        ObjectiveModel(final boolean papo, final String name) {
            this.papo = papo;
            this.displayName = new Comp(name);
        }

        void setDisplayName(final Comp name) {
            if (this.papo && name != this.displayName && name.equals(this.displayName)) return;
            this.displayName = name;
            this.formattedRecomputes++;
            this.broadcasts++;
        }

        void setRenderType(final String t) {
            if (this.papo && Objects.equals(t, this.renderType)) return;
            this.renderType = t;
            this.broadcasts++;
        }
    }

    // ---- C3：setDisplayObjective 模型（vanilla 双发 vs papo 去重）----
    static final class SlotPacket {
        final String slot;
        final String objective;
        SlotPacket(final String slot, final String objective) { this.slot = slot; this.objective = objective; }
        @Override public boolean equals(final Object o) {
            return o instanceof SlotPacket && ((SlotPacket) o).slot.equals(this.slot)
                && Objects.equals(((SlotPacket) o).objective, this.objective);
        }
        @Override public int hashCode() { return this.slot.hashCode() * 31 + Objects.hashCode(this.objective); }
        @Override public String toString() { return "(" + this.slot + "," + this.objective + ")"; }
    }

    static class ScoreboardModel {
        final boolean papo;
        final Map<String, String> displayBySlot = new HashMap<>();
        final Set<String> tracked = new HashSet<>();
        final List<SlotPacket> packets = new ArrayList<>();

        ScoreboardModel(final boolean papo) { this.papo = papo; }

        int slotCount(final String objective) {
            int c = 0;
            for (final String o : this.displayBySlot.values()) {
                if (o.equals(objective)) c++;
            }
            return c;
        }

        void setDisplayObjective(final String slot, final String objective) {
            final String old = this.displayBySlot.get(slot);
            if (objective == null) {
                this.displayBySlot.remove(slot);
            } else {
                this.displayBySlot.put(slot, objective);
            }
            boolean sent = false;
            if (!Objects.equals(old, objective) && old != null) {
                if (this.slotCount(old) > 0) {
                    this.packets.add(new SlotPacket(slot, objective));
                    sent = true;
                } else {
                    this.tracked.remove(old);
                }
            }
            if (objective != null) {
                if (this.tracked.contains(objective)) {
                    if (!this.papo || !sent) {
                        this.packets.add(new SlotPacket(slot, objective));
                    }
                } else {
                    this.tracked.add(objective); // startTrackingObjective（发送自有初始化包，不在此建模）
                }
            }
        }
    }

    static void eq(final String what, final Object expected, final Object actual) {
        if (!Objects.equals(expected, actual)) {
            System.out.println("FAIL " + what + ": expected=" + expected + " actual=" + actual);
            failures++;
        }
    }

    public static void main(final String[] args) {
        // ===== C1 PlayerTeam =====
        {
            final TeamModel vanilla = new TeamModel(false, "红队");
            final TeamModel papo = new TeamModel(true, "红队");
            // 1. 等值新实例（TAB 插件周期重设的常态）→ vanilla 1 / papo 0
            vanilla.setDisplayName(new Comp("红队"));
            papo.setDisplayName(new Comp("红队"));
            eq("C1 等值新实例 vanilla 广播", 1, vanilla.broadcasts);
            eq("C1 等值新实例 papo 广播", 0, papo.broadcasts);
            eq("C1 等值新实例字段一致", vanilla.displayName, papo.displayName);
            // 2. 真变更 → 都 +1
            vanilla.setDisplayName(new Comp("蓝队"));
            papo.setDisplayName(new Comp("蓝队"));
            eq("C1 真变更 vanilla", 2, vanilla.broadcasts);
            eq("C1 真变更 papo", 1, papo.broadcasts);
            // 3. 同实例重设（NMS 可变组件刷新手段）→ 都广播（vanilla 奇偶性保持）
            final Comp same = papo.displayName;
            papo.setDisplayName(same);
            vanilla.setDisplayName(vanilla.displayName);
            eq("C1 同实例重设 papo 仍广播", 2, papo.broadcasts);
            eq("C1 同实例重设 vanilla", 3, vanilla.broadcasts);
            // 4. prefix null 归一化等值（当前为 EMPTY）→ papo 0 / vanilla +1
            papo.setPlayerPrefix(null);
            vanilla.setPlayerPrefix(null);
            eq("C1 prefix null 归一化 papo", 2, papo.broadcasts);
            eq("C1 prefix null 归一化 vanilla", 4, vanilla.broadcasts);
            eq("C1 prefix 字段一致", vanilla.prefix, papo.prefix);
            // 5. 布尔同值 → papo 0 / vanilla +1
            papo.setAllowFriendlyFire(true);
            vanilla.setAllowFriendlyFire(true);
            eq("C1 布尔同值 papo", 2, papo.broadcasts);
            eq("C1 布尔同值 vanilla", 5, vanilla.broadcasts);
            papo.setAllowFriendlyFire(false);
            vanilla.setAllowFriendlyFire(false);
            eq("C1 布尔变更 papo", 3, papo.broadcasts);
            eq("C1 布尔变更 vanilla", 6, vanilla.broadcasts);
            // 6. unpackOptions 默认 flags（加载期）→ papo 0 广播（加载期无接收者）
            final TeamModel loaded = new TeamModel(true, "x");
            loaded.unpackOptions(0b11); // 两个 true 均与默认相同 → 0 包
            eq("C1 unpackOptions 默认值 0 广播", 0, loaded.broadcasts);
            final TeamModel loadedVanilla = new TeamModel(false, "x");
            loadedVanilla.unpackOptions(0b11);
            eq("C1 unpackOptions 默认值 vanilla 2 广播", 2, loadedVanilla.broadcasts);
        }

        // ===== C2 Objective =====
        {
            final ObjectiveModel vanilla = new ObjectiveModel(false, "分数");
            final ObjectiveModel papo = new ObjectiveModel(true, "分数");
            vanilla.setDisplayName(new Comp("分数"));
            papo.setDisplayName(new Comp("分数"));
            eq("C2 等值新实例 papo 0", 0, papo.broadcasts);
            eq("C2 等值新实例 vanilla 1", 1, vanilla.broadcasts);
            eq("C2 等值不重算 formatted", 0, papo.formattedRecomputes);
            vanilla.setRenderType("HEARTS");
            papo.setRenderType("HEARTS");
            eq("C2 renderType 变更", 1, papo.broadcasts);
            vanilla.setRenderType("HEARTS");
            papo.setRenderType("HEARTS");
            eq("C2 renderType 同值 papo 不广播", 1, papo.broadcasts);
            eq("C2 renderType 同值 vanilla 广播", 3, vanilla.broadcasts);
        }

        // ===== C3 display slot 双发去重 =====
        {
            // 场景构造：A 同占两槽且 tracked，B tracked —— set(slot0, B) 触发原版双发
            final ScoreboardModel vanilla = new ScoreboardModel(false);
            final ScoreboardModel papo = new ScoreboardModel(true);
            for (final ScoreboardModel m : new ScoreboardModel[]{vanilla, papo}) {
                m.setDisplayObjective("sidebar", "A");   // A tracked（首装）
                m.setDisplayObjective("belowname", "A"); // A 两槽 + 已 tracked → 单发
                m.setDisplayObjective("list", "B");      // B tracked（首装）
                m.packets.clear();                       // 进入受测状态
            }
            vanilla.setDisplayObjective("sidebar", "B");
            papo.setDisplayObjective("sidebar", "B");
            eq("C3 双发路径 vanilla 2 包", 2, vanilla.packets.size());
            eq("C3 双发路径 papo 1 包", 1, papo.packets.size());
            eq("C3 去重包内容", vanilla.packets.get(0), papo.packets.get(0));
            eq("C3 末状态一致", vanilla.displayBySlot, papo.displayBySlot);
            // 其余路径包数不变：换到未追踪 objective → 旧 B 仍显示于 list → 两模型各 +1 个 (sidebar,C)；
            // C 未追踪 → startTracking（自有初始化包，不在此建模）
            vanilla.setDisplayObjective("sidebar", "C");
            papo.setDisplayObjective("sidebar", "C");
            eq("C3 未追踪 objective vanilla +1", 3, vanilla.packets.size());
            eq("C3 未追踪 objective papo +1", 2, papo.packets.size());
            eq("C3 末状态一致2", vanilla.displayBySlot, papo.displayBySlot);
            // 清槽（objective=null，旧 A 不再显示于其他槽）→ stopTracking 路径，无 slot 包（两模型一致）
            vanilla.setDisplayObjective("belowname", null);
            papo.setDisplayObjective("belowname", null);
            eq("C3 清槽 vanilla 不变", 3, vanilla.packets.size());
            eq("C3 清槽 papo 不变", 2, papo.packets.size());
            eq("C3 末状态一致3", vanilla.displayBySlot, papo.displayBySlot);
            // 同 objective 重设（刷新语义）→ 两模型均 1 包（vanilla 奇偶性保持）
            vanilla.setDisplayObjective("list", "B");
            papo.setDisplayObjective("list", "B");
            eq("C3 同值重设 vanilla +1", 4, vanilla.packets.size());
            eq("C3 同值重设 papo +1", 3, papo.packets.size());
        }

        if (failures == 0) {
            System.out.println("ALL OK");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }
}
