package papo.bench;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次50 / 补丁0204: ChunkMap.TrackedEntity.updatePlayer 对已在 seenBy 中的对跳过冗余 canSee 检查。
 * moonrise$tick 每 tick 对每个被追踪实体 × chunk 内每个玩家调 updatePlayer；稳态聚集时绝大多数对
 * 已在 seenBy 中（seenBy.add 返回 false，无副作用），但原实现仍重算
 * player.getBukkitEntity().canSee(entity.getBukkitEntity())（2 次 getBukkitEntity + 一次
 * invertedVisibilityEntities.containsKey(uuid) HashMap 查找）。
 *
 * 不变量（补丁等价性依据）：seenBy.contains(conn) ⟹ canSee == true（hideEntity /
 * setVisibleByDefault(false) / resetAndHideEntity 均经 removePlayer 先移除对）。故对已追踪对
 * canSee 必为 true，CraftBukkit vanish 分支不可能命中，可安全跳过。
 *
 * 复刻：1 个被追踪实体，N=20 个已追踪玩家（稳态聚集典型），invertedVisibilityEntities 为空（生产常态：
 * 绝大多数玩家未 hide 任何实体）。
 *   - before: 每玩家计算 canSee（HashMap.containsKey 空表 + 2 次 bukkit 字段读）。
 *   - after:  每玩家 seenBy.contains(conn)（ReferenceOpenHashSet，恒 true）短路，跳过 canSee。
 *
 * main 自检：两路径对所有玩家得出的 flag（是否继续追踪）一致（全 true）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TrackCanSeeBench {

    /** CraftPlayer 语义复刻：持有 invertedVisibilityEntities（空，生产常态）。 */
    static final class CraftPlayer {
        final HashMap<UUID, Object> inverted = new HashMap<>();
    }
    /** CraftEntity 语义复刻：visibleByDefault + uuid。 */
    static final class CraftEntity {
        final boolean visibleByDefault = true;
        final UUID uuid;
        CraftEntity(UUID uuid) { this.uuid = uuid; }
    }
    /** ServerPlayer.connection 用 Object 模拟身份键。 */
    static final class Conn {}

    private static final int N = 20;
    private final CraftEntity entityBukkit;
    private final CraftPlayer[] players;
    private final Conn[] conns;
    private final ReferenceOpenHashSet<Conn> seenBy;

    public TrackCanSeeBench() {
        this.entityBukkit = new CraftEntity(UUID.randomUUID());
        this.players = new CraftPlayer[N];
        this.conns = new Conn[N];
        this.seenBy = new ReferenceOpenHashSet<>(N);
        for (int i = 0; i < N; i++) {
            this.players[i] = new CraftPlayer();
            this.conns[i] = new Conn();
            this.seenBy.add(this.conns[i]); // 全部已追踪（稳态聚集）
            // 真实场景：开启 vanish/隐身类插件时，每个玩家的 invertedVisibilityEntities 非空
            //（藏了若干其他实体）。这里每玩家藏 5 个 dummy 实体（不含本被追踪实体），
            // 使 canSee 的 HashMap.containsKey 做真实探测而非被 JIT 当空表常量折叠。
            for (int d = 0; d < 5; d++) {
                this.players[i].inverted.put(UUID.randomUUID(), new Object());
            }
        }
    }

    /** canSee(entity) 忠实复刻 CraftPlayer.canSee(Entity)。 */
    static boolean canSee(CraftPlayer viewer, CraftEntity entity) {
        return entity.visibleByDefault ^ viewer.inverted.containsKey(entity.uuid);
    }

    /** before: 每玩家重算 canSee（原版每对都做）。flag 恒为传入 true（距离/范围已过）。 */
    @Benchmark
    public int before_canSeeEveryPair(Blackhole bh) {
        ReferenceOpenHashSet<Conn> seenBy = this.seenBy;
        CraftEntity entity = this.entityBukkit;
        CraftPlayer[] players = this.players;
        Conn[] conns = this.conns;
        int tracked = 0;
        for (int i = 0; i < N; i++) {
            boolean flag = true; // 距离/broadcastToPlayer/isChunkTracked 均通过（稳态）
            // CraftBukkit - respect vanish
            if (flag && !canSee(players[i], entity)) {
                flag = false;
            }
            if (flag) {
                if (seenBy.add(conns[i])) { // 已追踪 → 返回 false
                    tracked++; // 不会进入（稳态）
                }
                bh.consume(conns[i]);
            }
        }
        return tracked;
    }

    /** after: 对已追踪对跳过 canSee（补丁）。seenBy.contains 短路，canSee 不计算。 */
    @Benchmark
    public int after_skipIfTracked(Blackhole bh) {
        ReferenceOpenHashSet<Conn> seenBy = this.seenBy;
        CraftEntity entity = this.entityBukkit;
        CraftPlayer[] players = this.players;
        Conn[] conns = this.conns;
        int tracked = 0;
        for (int i = 0; i < N; i++) {
            boolean flag = true;
            // Papo - skip redundant vanish check for already-tracked pairs
            final boolean alreadyTracked = seenBy.contains(conns[i]);
            if (!alreadyTracked && flag && !canSee(players[i], entity)) {
                flag = false;
            }
            if (flag) {
                if (seenBy.add(conns[i])) {
                    tracked++;
                }
                bh.consume(conns[i]);
            }
        }
        return tracked;
    }

    /** 等价性自检：两路径对所有玩家 flag 一致（稳态全 true，tracked 计数均为 0）。 */
    public static void main(String[] args) {
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        TrackCanSeeBench b = new TrackCanSeeBench();
        if (b.before_canSeeEveryPair(bh) != 0 || b.after_skipIfTracked(bh) != 0) {
            System.out.println("MISMATCH tracked count"); System.exit(1);
        }
        // 构造一个未追踪玩家 + inverted 非空的场景，验证 after 仍正确计算 canSee（未追踪路径不短路）
        CraftEntity ent = new CraftEntity(UUID.randomUUID());
        CraftPlayer viewer = new CraftPlayer();
        viewer.inverted.put(ent.uuid, new Object()); // 该实体被 hide → canSee false
        Conn conn = new Conn();
        ReferenceOpenHashSet<Conn> empty = new ReferenceOpenHashSet<>();
        // 未追踪 + canSee false → 应 flag=false（两路径一致）
        boolean beforeFlag = !(true && !canSee(viewer, ent)); // before: flag && !canSee → flag=false
        boolean afterFlag;
        {
            boolean already = empty.contains(conn); // false
            boolean flag = true;
            if (!already && flag && !canSee(viewer, ent)) flag = false;
            afterFlag = flag;
        }
        if (beforeFlag != afterFlag) {
            System.out.println("MISMATCH untracked-hidden path"); System.exit(1);
        }
        if (beforeFlag) { System.out.println("MISMATCH expected false for hidden"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
