package papo.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次46 / 0187: 包构造微优化——属性快照列表预尺寸 + 记分板 Optional.empty。
 * (a) ClientboundUpdateAttributesPacket：Lists.newArrayList() 默认容量 10，多属性同步时扩容拷贝；
 *     改为 newArrayListWithExpectedSize(attributes.size())（容量不经 List API 可观察）。
 * (b) ServerScoreboard.onScoreChanged：Optional.ofNullable(null) 本就返回 Optional.empty() 单例；
 *     三目写法省 null 分支的两次 Optional 分配（display/numberFormat 几乎恒 null）。
 * main 自检：列表内容/顺序一致；Optional 空分支返回同一单例、非空分支 equals。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class PacketConstructionBench {

    static final class AttributeInstance {
        final int id;
        AttributeInstance(int id) { this.id = id; }
    }

    static final class AttributeSnapshot {
        final int id;
        AttributeSnapshot(int id) { this.id = id; }
    }

    final AttributeInstance[] attrs = new AttributeInstance[14];
    {
        for (int i = 0; i < this.attrs.length; i++) this.attrs[i] = new AttributeInstance(i);
    }

    /** (a) before：默认容量。 */
    public Object attributesBeforeBody() {
        List<AttributeSnapshot> list = new ArrayList<>();
        for (AttributeInstance a : this.attrs) {
            list.add(new AttributeSnapshot(a.id));
        }
        this.sink = list;
        return list;
    }

    /** (a) after：预尺寸。 */
    public Object attributesAfterBody() {
        List<AttributeSnapshot> list = new ArrayList<>(this.attrs.length);
        for (AttributeInstance a : this.attrs) {
            list.add(new AttributeSnapshot(a.id));
        }
        this.sink = list;
        return list;
    }

    /** 逃逸汇（对齐 bh.consume 语义，供 main 自检调用）。 */
    Object sink;

    Object display = null;
    Object numberFormat = null;

    /** (b) before：ofNullable ×2（null 场景，生产常态）。 */
    public Object scoreboardBeforeBody() {
        Optional<Object> a = Optional.ofNullable(this.display);
        Optional<Object> b = Optional.ofNullable(this.numberFormat);
        this.sink = a;
        this.sink = b;
        return a;
    }

    /** (b) after：三目 empty 单例。 */
    public Object scoreboardAfterBody() {
        Optional<Object> a = this.display == null ? Optional.empty() : Optional.of(this.display);
        Optional<Object> b = this.numberFormat == null ? Optional.empty() : Optional.of(this.numberFormat);
        this.sink = a;
        this.sink = b;
        return a;
    }


    @Benchmark public Object attributesBefore(Blackhole bh) { Object r = this.attributesBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Object attributesAfter(Blackhole bh) { Object r = this.attributesAfterBody(); bh.consume(this.sink); return r; }
    @Benchmark public Object scoreboardBefore(Blackhole bh) { Object r = this.scoreboardBeforeBody(); bh.consume(this.sink); return r; }
    @Benchmark public Object scoreboardAfter(Blackhole bh) { Object r = this.scoreboardAfterBody(); bh.consume(this.sink); return r; }

    public static void main(String[] args) {
        PacketConstructionBench b = new PacketConstructionBench();
        // (a) 内容/顺序一致
        @SuppressWarnings("unchecked")
        List<AttributeSnapshot> l1 = (List<AttributeSnapshot>) b.attributesBeforeBody();
        @SuppressWarnings("unchecked")
        List<AttributeSnapshot> l2 = (List<AttributeSnapshot>) b.attributesAfterBody();
        if (l1.size() != l2.size()) throw new AssertionError("size");
        for (int i = 0; i < l1.size(); i++) if (l1.get(i).id != l2.get(i).id) throw new AssertionError("elem " + i);
        // (b) null：两路径均返回 Optional.empty() 同一单例
        Optional<?> n1 = Optional.ofNullable(null);
        Optional<?> n2 = Optional.empty();
        if (n1 != n2) throw new AssertionError("ofNullable(null) != empty() singleton");
        // (b) 非 null：两路径 equals
        b.display = "x";
        Optional<Object> p1 = Optional.ofNullable(b.display);
        Optional<Object> p2 = b.display == null ? Optional.empty() : Optional.of(b.display);
        if (!p1.equals(p2)) throw new AssertionError("non-null");
        System.out.println("ALL OK");
    }
}
