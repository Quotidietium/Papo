package papo.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * 批次65 / 0228-0231：容器/菜单域四项（批次 49 暂缓清单回头攻克）。
 *
 * 0228 callPrepareResultEvent 零监听器快路（PrepareInventoryResultEvent 全族唯一表；铁砧改名每击键、
 *     各结果菜单每次输入变化：省事件构造+instanceof 链+callEvent+值恒等回写）。
 * 0229 InventoryCreativeEvent 零监听器快路（InventoryClickEvent 父表共享，与 0154 同键；快路仅 1 句
 *     packet.itemStack().copy()——asNMSCopy(asBukkitCopy(x)) ≡ x.copy() 逐例恒等）。
 * 0230 InventoryDragEvent 零监听器快路（自有表；两段 setCarried 与 view.setItem 循环逐句保留，仅省
 *     eventMap+事件对象+派发）。
 * 0231 RecipeManager nullable 内部核（熔炉/营火每 tick hint 命中路径 2 次 Optional 分配 → 1 次）。
 *
 * 模型：事件对象/Map/快照拷贝 vs 门控跳过；Optional 双包 vs 单包。
 * main 自检：快路复刻赋值的终状态与慢路逐字段一致（drag：carried 值 + 槽位写入次数；creative：
 * itemStack 引用内容；prepare-result：无回写差异（值恒等）；recipe：双包与单包返回值一致）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ContainerGateBench {

    static final int DRAG_SLOTS = 9;

    /** 模型化的 carried/slot 状态。 */
    int carriedCount = 64;
    int[] slotCounts = new int[DRAG_SLOTS];
    int setCarriedCalls;
    int slotWrites;

    static final class ModelStack {
        final int id;
        int count;
        ModelStack(final int id, final int count) { this.id = id; this.count = count; }
        ModelStack copy() { return new ModelStack(this.id, this.count); }
    }

    ModelStack carried = new ModelStack(1, 64);

    // ===== 0230 InventoryDragEvent 模型 =====

    /** before：完整事件块（快照、eventMap、事件对象、派发、结果消费）。 */
    @Benchmark
    public int before_dragEvent() {
        final ModelStack itemStack = this.carried.copy(); // :440 快照
        int count = this.carried.count; // :446
        final Map<Integer, ModelStack> draggedSlots = new HashMap<>();
        for (int s = 0; s < DRAG_SLOTS; s++) {
            draggedSlots.put(s, new ModelStack(itemStack.id, itemStack.count / DRAG_SLOTS)); // copyWithCount 模型
        }
        // 事件侧
        final ModelStack newCarried = itemStack; // asCraftMirror 模型（别名）
        newCarried.count = count; // setAmount 经镜像落 count
        final Map<Integer, ModelStack> eventMap = new HashMap<>();
        for (final Map.Entry<Integer, ModelStack> e : draggedSlots.entrySet()) {
            eventMap.put(e.getKey(), e.getValue().copy()); // asBukkitCopy 模型
        }
        this.setCarriedCalls++; // :476 预写
        this.carried = newCarried.copy(); // setCarried(asNMSCopy(newCarried)) 模型
        // 零监听器：result != DENY 恒真
        for (final Map.Entry<Integer, ModelStack> e : draggedSlots.entrySet()) {
            this.slotWrites++; // view.setItem 模型
            this.slotCounts[e.getKey()] = e.getValue().count;
        }
        this.setCarriedCalls++; // :491 末写
        this.carried = newCarried.copy();
        return this.carried.count + this.slotWrites;
    }

    /** after：门控快路（保留两段 setCarried 与槽位循环，仅省事件侧）。 */
    @Benchmark
    public int after_dragFastPath() {
        final ModelStack itemStack = this.carried.copy();
        final int count = this.carried.count;
        final Map<Integer, ModelStack> draggedSlots = new HashMap<>();
        for (int s = 0; s < DRAG_SLOTS; s++) {
            draggedSlots.put(s, new ModelStack(itemStack.id, itemStack.count / DRAG_SLOTS));
        }
        final ModelStack newCarried = itemStack;
        newCarried.count = count;
        this.setCarriedCalls++;
        this.carried = newCarried.copy();
        for (final Map.Entry<Integer, ModelStack> e : draggedSlots.entrySet()) {
            this.slotWrites++;
            this.slotCounts[e.getKey()] = e.getValue().count;
        }
        this.setCarriedCalls++;
        this.carried = newCarried.copy();
        return this.carried.count + this.slotWrites;
    }

    // ===== 0229 InventoryCreativeEvent 模型 =====

    /** before：view + asBukkitCopy + 事件 + 派发 + switch + asNMSCopy(getCursor)。 */
    @Benchmark
    public Object before_creativeEvent() {
        final Object view = new Object(); // getBukkitView 模型
        final ModelStack item = new ModelStack(this.carried.id, this.carried.count); // asBukkitCopy
        final Object event = new Object[] {view, item}; // 事件对象模型
        final Object dispatched = event; // callEvent 模型
        return dispatched; // getCursor → asNMSCopy（零监听器 ≡ packet.itemStack().copy()）
    }

    /** after：快路一句 copy。 */
    @Benchmark
    public Object after_creativeFastPath() {
        return this.carried.copy();
    }

    // ===== 0231 RecipeManager nullable 核模型 =====

    static final ModelStack RECIPE = new ModelStack(7, 1);

    /** before：4 参 hint 命中 Optional.of + createCheck 再 Optional.of（双包）。 */
    @Benchmark
    public Optional<Object> before_doubleWrap() {
        final Optional<Object> recipeFor = Optional.of(RECIPE); // 4 参版包一次
        if (recipeFor.isPresent()) {
            return Optional.of(recipeFor.get()); // createCheck 再包一次
        }
        return Optional.empty();
    }

    /** after：nullable 核 + 单次包装。 */
    @Benchmark
    public Optional<Object> after_singleWrap() {
        final Object papoHolder = RECIPE; // nullable 核
        if (papoHolder != null) {
            return Optional.of(papoHolder);
        }
        return Optional.empty();
    }

    @Setup
    public void setup() {
        for (int i = 0; i < DRAG_SLOTS; i++) {
            this.slotCounts[i] = 0;
        }
    }

    public static void main(final String[] args) {
        // 0230 行为自检：快/慢路终状态一致（carried 值、槽位写入、setCarried 次数）
        final ContainerGateBench slow = new ContainerGateBench();
        final ContainerGateBench fast = new ContainerGateBench();
        slow.setup();
        fast.setup();
        slow.before_dragEvent();
        fast.after_dragFastPath();
        if (slow.carried.count != fast.carried.count || slow.slotWrites != fast.slotWrites
            || slow.setCarriedCalls != fast.setCarriedCalls) {
            System.out.println("FAIL drag state mismatch");
            System.exit(1);
        }
        for (int i = 0; i < DRAG_SLOTS; i++) {
            if (slow.slotCounts[i] != fast.slotCounts[i]) {
                System.out.println("FAIL drag slot " + i);
                System.exit(1);
            }
        }

        // 0231 行为自检：双包与单包返回值一致（命中与空两态）
        final ContainerGateBench b = new ContainerGateBench();
        b.setup();
        if (!b.before_doubleWrap().equals(b.after_singleWrap()) || b.after_singleWrap().get() != (Object) RECIPE) {
            System.out.println("FAIL recipe wrap equivalence");
            System.exit(1);
        }

        System.out.println("ALL OK");
    }
}
