package papo.bench;

import java.util.Optional;
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
 * 0048: LookControl Optional<Float> 协议 → boolean+字段协议。
 * 模拟 Mob.tick() 中每 tick 每实体对 xRotD/yRotD 的读取：
 * before: getXRotD()/getYRotD() 每次构造 Optional（isPresent/get 拆箱）。
 * after:  直接读 boolean 标志 + float 字段，零分配。
 * 每组模拟 64 个实体 * 每 tick 的访问。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class OptionalProtocolBench {

    @Param({"64"})
    int entities;

    private Before[] beforeArr;
    private After[] afterArr;

    /** 优化前的 LookControl 形态。 */
    static final class Before {
        private Float xRotD;
        private Float yRotD;

        void rotateTowards(double xRot, double yRot) {
            this.xRotD = (float) xRot;
            this.yRotD = (float) yRot;
        }

        void reset() {
            this.xRotD = null;
            this.yRotD = null;
        }

        Optional<Float> getXRotD() {
            return Optional.ofNullable(this.xRotD);
        }

        Optional<Float> getYRotD() {
            return Optional.ofNullable(this.yRotD);
        }
    }

    /** 优化后的 LookControl 形态（Papo 0048）。 */
    static final class After {
        private boolean hasXRotD;
        private boolean hasYRotD;
        private float xRotD;
        private float yRotD;

        void rotateTowards(double xRot, double yRot) {
            this.xRotD = (float) xRot;
            this.yRotD = (float) yRot;
            this.hasXRotD = true;
            this.hasYRotD = true;
        }

        void reset() {
            this.hasXRotD = false;
            this.hasYRotD = false;
        }
    }

    @Setup
    public void setup() {
        beforeArr = new Before[entities];
        afterArr = new After[entities];
        for (int i = 0; i < entities; i++) {
            beforeArr[i] = new Before();
            afterArr[i] = new After();
            if ((i & 1) == 0) { // 一半实体本 tick 有旋转目标
                beforeArr[i].rotateTowards(i * 0.5, i * 0.25);
                afterArr[i].rotateTowards(i * 0.5, i * 0.25);
            }
        }
    }

    @Benchmark
    public void before_optional(Blackhole bh) {
        for (Before c : beforeArr) {
            c.getXRotD().ifPresent(x -> {
                bh.consume(x);
            });
            c.getYRotD().ifPresent(y -> {
                bh.consume(y);
            });
            c.reset();
        }
    }

    @Benchmark
    public void after_booleanField(Blackhole bh) {
        for (After c : afterArr) {
            if (c.hasXRotD) {
                bh.consume(c.xRotD);
            }
            if (c.hasYRotD) {
                bh.consume(c.yRotD);
            }
            c.reset();
        }
    }
}
