package papo.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 0093: InventoryChangeTrigger 无监听器早退（模型化复刻）。
 * 真实触发器在每次背包槽位同步时扫描全部槽位统计 full/empty/occupied，
 * 每非空槽位调用 getMaxStackSize()（DataComponent 查找，此处用小 Map.getOrDefault 模拟其量级）；
 * hasListeners 早退后，无监听器（老玩家常态）时这段工作整段跳过，成本降为一个集合判空。
 * before: 完整槽位扫描；after: 监听器判空早退。
 * 参数: filled=27/41（玩家背包 41 槽位中不同填充程度）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InventoryTriggerScanBench {

    @Param({"27", "41"})
    int filled;

    private static final int SLOTS = 41; // 玩家背包槽位数（含副手/盔甲）

    private Slot[] inventory;
    private boolean hasListeners;

    /** 模拟 ItemStack：count==0 为空；maxStack 经组件表 getOrDefault 查找（模拟 DataComponents.MAX_STACK_SIZE）。 */
    static final class Slot {
        int count;
        final Map<String, Integer> components;

        Slot(int count, int maxStack) {
            this.count = count;
            this.components = new HashMap<>();
            if (maxStack != 64) {
                this.components.put("max_stack_size", maxStack);
            }
        }

        boolean isEmpty() {
            return this.count == 0;
        }

        int getCount() {
            return this.count;
        }

        int getMaxStackSize() {
            return this.components.getOrDefault("max_stack_size", 64);
        }
    }

    @Setup
    public void setup() {
        this.inventory = new Slot[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            // 混合：满叠 64、半叠 32、单件不可堆叠（maxStack=1）
            int kind = i % 3;
            this.inventory[i] = i < filled
                ? new Slot(kind == 0 ? 64 : kind == 1 ? 32 : 1, kind == 2 ? 1 : 64)
                : new Slot(0, 64);
        }
        this.hasListeners = false; // 老玩家完成相关进度后监听器集合为空（常态）
    }

    @Benchmark
    public void before_fullScan(Blackhole bh) {
        // 补丁前：无条件全槽位扫描统计
        int full = 0;
        int empty = 0;
        int occupied = 0;
        for (int i = 0; i < inventory.length; i++) {
            Slot item = inventory[i];
            if (item.isEmpty()) {
                empty++;
            } else {
                occupied++;
                if (item.getCount() >= item.getMaxStackSize()) {
                    full++;
                }
            }
        }
        bh.consume(full + empty + occupied);
    }

    @Benchmark
    public void after_hasListenersEarlyExit(Blackhole bh) {
        // 补丁后：无监听器时整段跳过
        if (!hasListeners) {
            bh.consume(0);
            return;
        }
        int full = 0;
        int empty = 0;
        int occupied = 0;
        for (int i = 0; i < inventory.length; i++) {
            Slot item = inventory[i];
            if (item.isEmpty()) {
                empty++;
            } else {
                occupied++;
                if (item.getCount() >= item.getMaxStackSize()) {
                    full++;
                }
            }
        }
        bh.consume(full + empty + occupied);
    }
}
