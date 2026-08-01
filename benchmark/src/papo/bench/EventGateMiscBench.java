package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次49 / 0196 + 0197 + 0198 + 0199: 四类零监听器事件门控（复刻，与已实现站点同构）。
 * 0196 TradeSelectEvent：村民交易选项点击，事件构造 + getBukkitView + callEvent，调用方只读 isCancelled。
 * 0197 BlockBurnEvent：火烧毁判定，CraftBlock×2 + 事件 + callEvent，调用方只读取消。
 * 0198 CauldronLevelChangeEvent：炼药锅水位变化，CraftBlockState + CraftBlock + 事件 + callEvent，零监听器 == level.setBlock。
 * 0199 BlockIgniteEvent：岩浆引火，CraftBlock×2 + 事件 + callEvent，调用方只读 isCancelled。
 * after：各自权威表零监听器门控，零监听器时默认流直达。
 * main 自检：四事件零监听器场景两路径可观察结果（交易继续/未取消/水位应用/引火继续）一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class EventGateMiscBench {

    static final class CraftBlock {
        final Object level; final long pos;
        CraftBlock(Object level, long pos) { this.level = level; this.pos = pos; }
        static CraftBlock at(Object level, long pos) { return new CraftBlock(level, pos); }
    }

    static final class CraftBlockState {
        final Object level; final long pos; int data;
        CraftBlockState(Object level, long pos) { this.level = level; this.pos = pos; }
        void setData(int d) { this.data = d; }
        boolean place() { return true; } // level.setBlock 复刻
    }

    // ---- 0196 交易 ----
    static final class TradeSelectEvent {
        static final List<Consumer<TradeSelectEvent>> HANDLER_LIST = new ArrayList<>();
        final Object view; final int index;
        boolean cancelled;
        TradeSelectEvent(Object view, int index) { this.view = view; this.index = index; }
        void callEvent() { for (Consumer<TradeSelectEvent> l : HANDLER_LIST) l.accept(this); }
        boolean isCancelled() { return this.cancelled; }
    }

    Object merchantView; int selIndex = 2;
    boolean sendAllCalled;

    public int tradeBeforeBody() {
        TradeSelectEvent event = new TradeSelectEvent(this.merchantView(), this.selIndex);
        event.callEvent();
        if (event.isCancelled()) { this.sendAllCalled = true; return -1; }
        this.sink = event;
        return this.selIndex;
    }

    public int tradeAfterBody() {
        if (TradeSelectEvent.HANDLER_LIST.size() > 0) {
            TradeSelectEvent event = new TradeSelectEvent(this.merchantView(), this.selIndex);
            event.callEvent();
            if (event.isCancelled()) { this.sendAllCalled = true; return -1; }
        }
        return this.selIndex;
    }

    Object merchantView() { Object v = this.merchantView; if (v == null) v = this.merchantView = new Object(); return v; }

    // ---- 0197 烧毁 ----
    static final class BlockBurnEvent {
        static final List<Consumer<BlockBurnEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block; final CraftBlock source;
        BlockBurnEvent(CraftBlock block, CraftBlock source) { this.block = block; this.source = source; }
        boolean callEvent() { for (Consumer<BlockBurnEvent> l : HANDLER_LIST) l.accept(this); return true; }
    }

    Object level = new Object();
    long burnPos = 7L, sourcePos = 9L;

    public int burnBeforeBody() {
        CraftBlock burnBlock = CraftBlock.at(this.level, this.burnPos);
        CraftBlock sourceBlock = CraftBlock.at(this.level, this.sourcePos);
        if (!new BlockBurnEvent(burnBlock, sourceBlock).callEvent()) return -1;
        this.sink = burnBlock;
        return 1;
    }

    public int burnAfterBody() {
        if (BlockBurnEvent.HANDLER_LIST.size() > 0) {
            CraftBlock burnBlock = CraftBlock.at(this.level, this.burnPos);
            CraftBlock sourceBlock = CraftBlock.at(this.level, this.sourcePos);
            if (!new BlockBurnEvent(burnBlock, sourceBlock).callEvent()) return -1;
        }
        return 1;
    }

    // ---- 0198 炼药锅 ----
    static final class CauldronLevelChangeEvent {
        static final List<Consumer<CauldronLevelChangeEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block; final Object entity; final Object reason; final CraftBlockState state;
        CauldronLevelChangeEvent(CraftBlock block, Object entity, Object reason, CraftBlockState state) {
            this.block = block; this.entity = entity; this.reason = reason; this.state = state;
        }
        boolean callEvent() { for (Consumer<CauldronLevelChangeEvent> l : HANDLER_LIST) l.accept(this); return true; }
    }

    int newLevel = 2;

    public boolean cauldronBeforeBody() {
        CraftBlockState newState = new CraftBlockState(this.level, this.burnPos);
        newState.setData(this.newLevel);
        CauldronLevelChangeEvent event = new CauldronLevelChangeEvent(CraftBlock.at(this.level, this.burnPos), null, "evap", newState);
        if (!event.callEvent()) return false;
        newState.place();
        this.sink = newState;
        return true;
    }

    public boolean cauldronAfterBody() {
        if (CauldronLevelChangeEvent.HANDLER_LIST.size() > 0) {
            CraftBlockState newState = new CraftBlockState(this.level, this.burnPos);
            newState.setData(this.newLevel);
            CauldronLevelChangeEvent event = new CauldronLevelChangeEvent(CraftBlock.at(this.level, this.burnPos), null, "evap", newState);
            if (!event.callEvent()) return false;
            newState.place();
        } else {
            // level.setBlock(pos, newBlock, UPDATE_ALL) —— 炼药锅无方块实体，等价 newState.place
            this.placedLevel = this.newLevel;
        }
        return true;
    }

    int placedLevel;

    // ---- 0199 引火 ----
    static final class BlockIgniteEvent {
        static final List<Consumer<BlockIgniteEvent>> HANDLER_LIST = new ArrayList<>();
        final CraftBlock block; final CraftBlock source;
        BlockIgniteEvent(CraftBlock block, CraftBlock source) { this.block = block; this.source = source; }
        boolean isCancelled() { return false; }
    }

    static BlockIgniteEvent callIgnite(Object level, long pos, long source) {
        return new BlockIgniteEvent(CraftBlock.at(level, pos), CraftBlock.at(level, source));
    }

    public int igniteBeforeBody() {
        if (callIgnite(this.level, this.burnPos, this.sourcePos).isCancelled()) return -1;
        return 1;
    }

    public int igniteAfterBody() {
        if (BlockIgniteEvent.HANDLER_LIST.size() > 0 && callIgnite(this.level, this.burnPos, this.sourcePos).isCancelled()) return -1;
        return 1;
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    @Benchmark public int tradeBefore(Blackhole bh) { int r = this.tradeBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int tradeAfter(Blackhole bh) { int r = this.tradeAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int burnBefore(Blackhole bh) { int r = this.burnBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int burnAfter(Blackhole bh) { int r = this.burnAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public boolean cauldronBefore(Blackhole bh) { boolean r = this.cauldronBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public boolean cauldronAfter(Blackhole bh) { boolean r = this.cauldronAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public int igniteBefore(Blackhole bh) { int r = this.igniteBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public int igniteAfter(Blackhole bh) { int r = this.igniteAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        EventGateMiscBench b = new EventGateMiscBench();
        // 0196：两路径均到达交易（返回选中索引）
        if (b.tradeBeforeBody() != b.selIndex || b.tradeAfterBody() != b.selIndex) throw new AssertionError("trade mismatch");
        if (b.sendAllCalled) throw new AssertionError("trade cancelled spuriously");
        // 0197：两路径均未被取消（返回 1）
        if (b.burnBeforeBody() != 1 || b.burnAfterBody() != 1) throw new AssertionError("burn mismatch");
        // 0198：两路径均成功应用水位
        if (!b.cauldronBeforeBody() || !b.cauldronAfterBody() || b.placedLevel != b.newLevel) throw new AssertionError("cauldron mismatch");
        // 0199：两路径均引火继续
        if (b.igniteBeforeBody() != 1 || b.igniteAfterBody() != 1) throw new AssertionError("ignite mismatch");
        System.out.println("ALL OK");
    }
}
