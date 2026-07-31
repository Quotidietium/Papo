package papo.bench;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: EntityEquipment.tick 每实体每 tick EnumMap.entrySet() 迭代（每个已设槽位
 * 一个 MapEntry 分配）→ EquipmentSlot.VALUES 索引循环 + EnumMap.get（O(1) 数组读，
 * 未设槽返回 null 跳过）。两者同为枚举序。
 * 复刻：真实 EnumMap（8 槽枚举）、ItemStack.isEmpty 语义、inventoryTick 消费。
 * 装备分布：4/8 槽有物品（村民/僵尸典型），其中 1 个为空栈（put 了 EMPTY）。
 * main 自检：两路径 tick 调用顺序与次数一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EquipmentTickBench {

    /** EquipmentSlot 语义复刻（8 常量 + VALUES 缓存列表）。 */
    enum Slot {
        MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE;
        static final List<Slot> VALUES = List.of(values());
    }

    /** ItemStack 语义复刻。 */
    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack(true);
        final boolean empty;
        int tickCount;
        ItemStack(boolean empty) { this.empty = empty; }
        boolean isEmpty() { return this.empty; }
        void inventoryTick(Slot slot, StringBuilder log) {
            this.tickCount++;
            if (log != null) log.append(slot.ordinal()).append(';');
        }
    }

    private final EnumMap<Slot, ItemStack> items = new EnumMap<>(Slot.class);

    public EquipmentTickBench() {
        this.items.put(Slot.MAINHAND, new ItemStack(false));
        this.items.put(Slot.FEET, new ItemStack(false));
        this.items.put(Slot.LEGS, new ItemStack(false));
        this.items.put(Slot.CHEST, ItemStack.EMPTY); // put 了空栈：entrySet 包含但跳过
        this.items.put(Slot.HEAD, new ItemStack(false));
    }

    @Benchmark
    public int before_entrySetIteration(Blackhole bh) {
        int ticks = 0;
        for (Map.Entry<Slot, ItemStack> entry : this.items.entrySet()) {
            ItemStack stack = entry.getValue();
            if (!stack.isEmpty()) {
                stack.inventoryTick(entry.getKey(), null);
                ticks++;
            }
        }
        bh.consume(ticks);
        return ticks;
    }

    @Benchmark
    public int after_valuesLoop(Blackhole bh) {
        int ticks = 0;
        List<Slot> slots = Slot.VALUES;
        for (int i = 0, size = slots.size(); i < size; i++) {
            Slot slot = slots.get(i);
            ItemStack stack = this.items.get(slot);
            if (stack != null && !stack.isEmpty()) {
                stack.inventoryTick(slot, null);
                ticks++;
            }
        }
        bh.consume(ticks);
        return ticks;
    }

    /** 等价性自检：tick 顺序与集合一致。 */
    public static void main(String[] args) {
        EquipmentTickBench bench = new EquipmentTickBench();
        // 顺序记录对比
        StringBuilder logA = new StringBuilder();
        for (Map.Entry<Slot, ItemStack> entry : bench.items.entrySet()) {
            ItemStack stack = entry.getValue();
            if (!stack.isEmpty()) stack.inventoryTick(entry.getKey(), logA);
        }
        StringBuilder logB = new StringBuilder();
        List<Slot> slots = Slot.VALUES;
        for (int i = 0, size = slots.size(); i < size; i++) {
            Slot slot = slots.get(i);
            ItemStack stack = bench.items.get(slot);
            if (stack != null && !stack.isEmpty()) stack.inventoryTick(slot, logB);
        }
        if (!logA.toString().equals(logB.toString())) {
            System.out.println("MISMATCH order: " + logA + " vs " + logB); System.exit(1);
        }
        // 空 EnumMap 边界
        EquipmentTickBench empty = new EquipmentTickBench();
        empty.items.clear();
        Blackhole bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
        if (empty.before_entrySetIteration(bh) != 0 || empty.after_valuesLoop(bh) != 0) {
            System.out.println("MISMATCH empty map"); System.exit(1);
        }
        // 全满边界
        EquipmentTickBench full = new EquipmentTickBench();
        for (Slot s : Slot.VALUES) full.items.put(s, new ItemStack(false));
        if (full.before_entrySetIteration(bh) != 8 || full.after_valuesLoop(bh) != 8) {
            System.out.println("MISMATCH full map"); System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
