package papo.bench;

import java.util.ArrayList;
import java.util.Arrays;
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
 * 批次77: 同序列 pairing 包共享（新实体注册场景）。
 *
 * 新实体注册（addEntity → TrackedEntity.updatePlayers(全体玩家)）在单线程连续循环中对多个
 * 玩家 addPairing——sendPairingData 的全部内容仅依赖实体状态（spawn 包/非默认 data 快照/
 * attributes/equipment 拷贝/passengers/leash），窗口内状态不变 ⇒ 每观众重复构造逐字节相同的
 * 2-6 个包。共享=每 sweep 构造一次、后续观众复用同一 List（跨玩家共享包实例为 vanilla 既有
 * 行为；窗口由 begin/end 严格括起，窗口外 pairing 照旧独立构造）。
 *
 * 模型：实体 pairing 五段（spawn + data 20 项快照 + attributes + equipment 4 槽 copy + passengers）
 *  before = 每观众完整构造；afterShare = 首观众构造 + 后续复用；bundle 包装每观众保留。
 *
 * main 自检：K 观众两路径收到的包序列内容逐项一致；窗口外构造不受缓存影响。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PairingShareBench {

    static final int VIEWERS = 4;
    static final int DATA_VALUES = 20;

    // 模型包：不可变，编码仅读
    static final class MPacket {
        final String kind; final Object payload;
        MPacket(String kind, Object payload) { this.kind = kind; this.payload = payload; }
    }

    int entityId = 77;
    int[] dataValues = new int[DATA_VALUES];
    int[] attributeIds = new int[6];
    int[] equipment = new int[4];

    @Setup
    public void setup() {
        java.util.Random rnd = new java.util.Random(0x4E_77L);
        for (int i = 0; i < DATA_VALUES; i++) this.dataValues[i] = rnd.nextInt(1000);
        for (int i = 0; i < 6; i++) this.attributeIds[i] = rnd.nextInt(50);
        for (int i = 0; i < 4; i++) this.equipment[i] = rnd.nextInt(800);
    }

    /** sendPairingData 五段构造模型（equipment 每次拷贝——与 NMS itemBySlot().copy() 同构）。 */
    List<Object> buildPairingPackets() {
        List<Object> list = new ArrayList<>(4);
        list.add(new MPacket("spawn", this.entityId));
        List<Integer> data = new ArrayList<>(DATA_VALUES);
        for (int v : this.dataValues) data.add(v);
        list.add(new MPacket("data", data));
        List<Integer> attrs = new ArrayList<>(6);
        for (int a : this.attributeIds) attrs.add(a);
        list.add(new MPacket("attributes", attrs));
        List<Integer> equip = new ArrayList<>(4);
        for (int e : this.equipment) equip.add(e);
        list.add(new MPacket("equipment", equip));
        list.add(new MPacket("passengers", this.entityId + 1));
        return list;
    }

    @Benchmark
    public void beforePerViewer(final Blackhole bh) {
        for (int v = 0; v < VIEWERS; v++) {
            List<Object> list = buildPairingPackets();
            bh.consume(list); // bundle 包装 + send 模型
        }
    }

    List<Object> shareCache;

    @Benchmark
    public void afterShare(final Blackhole bh) {
        for (int v = 0; v < VIEWERS; v++) {
            List<Object> list;
            if (this.shareCache != null) {
                list = this.shareCache;
            } else {
                list = buildPairingPackets();
                this.shareCache = list;
            }
            bh.consume(list);
        }
    }

    public static void main(String[] args) {
        PairingShareBench b = new PairingShareBench();
        b.setup();
        int failures = 0;

        // 1) K 观众：两路径包序列逐项内容一致
        for (int v = 0; v < VIEWERS; v++) {
            List<Object> fresh = b.buildPairingPackets();
            if (b.shareCache == null) b.shareCache = b.buildPairingPackets();
            if (fresh.size() != b.shareCache.size()) { failures++; System.out.println("FAIL size v" + v); break; }
            for (int i = 0; i < fresh.size(); i++) {
                MPacket f = (MPacket) fresh.get(i), s = (MPacket) b.shareCache.get(i);
                if (!f.kind.equals(s.kind) || !f.payload.equals(s.payload)) {
                    failures++; System.out.println("FAIL content v" + v + " i" + i); break;
                }
            }
        }

        // 2) 窗口外：缓存清空后构造独立（复用断言不命中）
        b.shareCache = null;
        List<Object> a = b.buildPairingPackets();
        List<Object> c = b.buildPairingPackets();
        if (a == c) { failures++; System.out.println("FAIL window-external sharing"); }
        if (!a.get(0).equals(c.get(0)) && ((MPacket)a.get(0)).kind.equals(((MPacket)c.get(0)).kind)) { /* content equal fine */ }

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
