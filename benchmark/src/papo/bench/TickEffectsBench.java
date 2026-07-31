package papo.bench;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次36: EnchantmentHelper.tickEffects 展开 runIterationOnEquipment →
 * runIterationOnItem → 捕获 level/entity 的 EnchantmentInSlotVisitor lambda
 * （每实体每 tick 一次 lambda 分配）为直接三层循环。
 * 复刻：8 装备槽（4 有物品，其中 2 带附魔）、附魔表 Object2IntOpenHashMap、
 * matchingSlot 检查、Enchantment.tick 消费、EnchantedItemInUse 分配保留。
 * main 自检：两路径 tick 调用序列（槽序 + 附魔序）一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TickEffectsBench {

    /** EquipmentSlot 语义复刻。 */
    enum Slot {
        MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE;
        static final List<Slot> VALUES = List.of(values());
        boolean matches(Slot other) { return this == other || (this.ordinal() <= 1 && other.ordinal() <= 1); }
    }

    /** Enchantment 语义复刻。 */
    static final class Enchantment {
        final Slot matchingSlot;
        Enchantment(Slot matchingSlot) { this.matchingSlot = matchingSlot; }
        boolean matchingSlotCheck(Slot slot) { return this.matchingSlot.matches(slot); }
        void tick(int level, EnchantedItemInUse item, StringBuilder log) {
            if (log != null) log.append(item.slot.ordinal()).append(':').append(level).append(';');
        }
    }

    /** EnchantedItemInUse 语义复刻（原版每个附魔物品一个，保留分配）。 */
    static final class EnchantedItemInUse {
        final ItemStack stack;
        final Slot slot;
        EnchantedItemInUse(ItemStack stack, Slot slot) { this.stack = stack; this.slot = slot; }
    }

    /** ItemStack 语义复刻：可带附魔表。 */
    static final class ItemStack {
        static final ItemStack EMPTY = new ItemStack(null);
        final Object2IntMap<Enchantment> enchantments; // null = 无
        ItemStack(Object2IntMap<Enchantment> enchantments) { this.enchantments = enchantments; }
        boolean isEmpty() { return this == EMPTY; }
    }

    interface EnchantmentInSlotVisitor {
        void accept(Enchantment enchantment, int level, EnchantedItemInUse item);
    }

    private final ItemStack[] equipment = new ItemStack[Slot.VALUES.size()];

    public TickEffectsBench() {
        Object2IntMap<Enchantment> sword = new Object2IntOpenHashMap<>();
        sword.put(new Enchantment(Slot.MAINHAND), 3);
        sword.put(new Enchantment(Slot.MAINHAND), 2);
        Object2IntMap<Enchantment> chest = new Object2IntOpenHashMap<>();
        chest.put(new Enchantment(Slot.CHEST), 4);
        this.equipment[Slot.MAINHAND.ordinal()] = new ItemStack(sword);
        this.equipment[Slot.FEET.ordinal()] = new ItemStack(null); // 无附魔
        this.equipment[Slot.CHEST.ordinal()] = new ItemStack(chest);
        this.equipment[Slot.HEAD.ordinal()] = ItemStack.EMPTY;
        for (int i = 0; i < this.equipment.length; i++) {
            if (this.equipment[i] == null) this.equipment[i] = ItemStack.EMPTY;
        }
    }

    private ItemStack getItemBySlot(Slot slot) {
        return this.equipment[slot.ordinal()];
    }

    /** 原版：runIterationOnEquipment + visitor lambda（捕获 log）。 */
    private static void beforeTick(TickEffectsBench self, StringBuilder log) {
        EnchantmentInSlotVisitor visitor = (enchantment, level, item) -> enchantment.tick(level, item, log);
        for (Slot slot : Slot.VALUES) {
            ItemStack stack = self.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                Object2IntMap<Enchantment> enchantments = stack.enchantments;
                if (enchantments != null && !enchantments.isEmpty()) {
                    EnchantedItemInUse inUse = new EnchantedItemInUse(stack, slot);
                    for (Object2IntMap.Entry<Enchantment> entry : enchantments.object2IntEntrySet()) {
                        Enchantment enchantment = entry.getKey();
                        if (enchantment.matchingSlotCheck(slot)) {
                            visitor.accept(enchantment, entry.getIntValue(), inUse);
                        }
                    }
                }
            }
        }
    }

    /** 新版：直接三层循环。 */
    private static void afterTick(TickEffectsBench self, StringBuilder log) {
        List<Slot> slots = Slot.VALUES;
        for (int i = 0, size = slots.size(); i < size; i++) {
            Slot slot = slots.get(i);
            ItemStack stack = self.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                Object2IntMap<Enchantment> enchantments = stack.enchantments;
                if (enchantments != null && !enchantments.isEmpty()) {
                    EnchantedItemInUse inUse = new EnchantedItemInUse(stack, slot);
                    for (Object2IntMap.Entry<Enchantment> entry : enchantments.object2IntEntrySet()) {
                        Enchantment enchantment = entry.getKey();
                        if (enchantment.matchingSlotCheck(slot)) {
                            enchantment.tick(entry.getIntValue(), inUse, log);
                        }
                    }
                }
            }
        }
    }

    @Benchmark
    public int before_visitorLambda(Blackhole bh) {
        beforeTick(this, null);
        return 1;
    }

    @Benchmark
    public int after_inlinedLoops(Blackhole bh) {
        afterTick(this, null);
        return 1;
    }

    /** 等价性自检：tick 调用序列一致。 */
    public static void main(String[] args) {
        TickEffectsBench bench = new TickEffectsBench();
        StringBuilder logA = new StringBuilder();
        StringBuilder logB = new StringBuilder();
        beforeTick(bench, logA);
        afterTick(bench, logB);
        if (!logA.toString().equals(logB.toString())) {
            System.out.println("MISMATCH sequence: " + logA + " vs " + logB); System.exit(1);
        }
        if (logA.length() == 0) { System.out.println("MISMATCH empty sequence"); System.exit(1); }
        System.out.println("ALL OK");
    }
}
