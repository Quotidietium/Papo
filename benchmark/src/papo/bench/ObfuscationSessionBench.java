package papo.bench;

import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * 批次46 / 0186 + 直提: ItemObfuscationSession withItemStack 直路 + start 上下文按级缓存。
 * before：每个非空 ItemStack 编码 withContext(c -> c.itemStack(value)) 捕获 lambda 一次分配；
 *        start(level) 每次 new ObfuscationContext(session, null, null, level)。
 * after：withItemStack(x) 逐步等价直路（无 lambda）；start 按 ObfuscationLevel 预建缓存上下文。
 * 语义复刻：ItemObfuscationSession 真实结构（ThreadLocal 会话、context 链、checkState 不变量、
 *        close 恢复 previous/root）——before/after 两版类逐字对应真实代码。
 * main 自检：NONE/OVERSIZED/ALL 三级下——上下文链字段（level/itemStack/previous）、close 恢复序列、
 *        checkState 不变量、返回值可关闭语义，两版一致。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ObfuscationSessionBench {

    interface SafeAutoClosable extends AutoCloseable {
        @Override void close();
    }

    enum ObfuscationLevel {
        NONE, OVERSIZED, ALL;
        boolean isObfuscating() { return this != NONE; }
    }

    static final class ItemStack {
        final int id;
        ItemStack(int id) { this.id = id; }
    }

    // ============ before 版（真实代码逐字复刻） ============
    static final class SessionBefore {
        static final ThreadLocal<SessionBefore> TL = ThreadLocal.withInitial(SessionBefore::new);

        static SessionBefore start(ObfuscationLevel level) {
            SessionBefore s = TL.get();
            s.switchContext(new CtxBefore(s, null, null, level));
            return s;
        }

        static SafeAutoClosable withContext(UnaryOperator<CtxBefore> updater) {
            SessionBefore s = TL.get();
            if (!s.obfuscationLevel().isObfuscating()) return () -> {};
            CtxBefore newContext = updater.apply(s.context());
            if (newContext == s.context()) throw new IllegalStateException("same context");
            s.switchContext(newContext);
            return newContext;
        }

        private final CtxBefore root = new CtxBefore(this, null, null, ObfuscationLevel.NONE);
        private CtxBefore context = this.root;

        void switchContext(CtxBefore c) { this.context = c; }
        CtxBefore context() { return this.context; }
        ObfuscationLevel obfuscationLevel() { return this.context.level; }
        void close() { this.context = this.root; }
    }

    static final class CtxBefore implements SafeAutoClosable {
        final SessionBefore parent;
        final CtxBefore previousContext;
        final ItemStack itemStack;
        final ObfuscationLevel level;

        CtxBefore(SessionBefore parent, CtxBefore previousContext, ItemStack itemStack, ObfuscationLevel level) {
            this.parent = parent; this.previousContext = previousContext; this.itemStack = itemStack; this.level = level;
        }

        CtxBefore itemStack(ItemStack s) { return new CtxBefore(this.parent, this, s, this.level); }
        @Override public void close() { this.parent.switchContext(this.previousContext); }
    }

    // ============ after 版（含缓存与 withItemStack） ============
    static final class SessionAfter {
        static final ThreadLocal<SessionAfter> TL = ThreadLocal.withInitial(SessionAfter::new);

        static SessionAfter start(ObfuscationLevel level) {
            SessionAfter s = TL.get();
            s.switchContext(s.startContexts[level.ordinal()]);
            return s;
        }

        private final CtxAfter[] startContexts = new CtxAfter[]{
            new CtxAfter(this, null, null, ObfuscationLevel.NONE),
            new CtxAfter(this, null, null, ObfuscationLevel.OVERSIZED),
            new CtxAfter(this, null, null, ObfuscationLevel.ALL),
        };

        static SafeAutoClosable withItemStack(ItemStack itemStack) {
            SessionAfter s = TL.get();
            if (!s.obfuscationLevel().isObfuscating()) return () -> {};
            CtxAfter newContext = s.context().itemStack(itemStack);
            if (newContext == s.context()) throw new IllegalStateException("same context");
            s.switchContext(newContext);
            return newContext;
        }

        private final CtxAfter root = new CtxAfter(this, null, null, ObfuscationLevel.NONE);
        private CtxAfter context = this.root;

        void switchContext(CtxAfter c) { this.context = c; }
        CtxAfter context() { return this.context; }
        ObfuscationLevel obfuscationLevel() { return this.context.level; }
        void close() { this.context = this.root; }
    }

    static final class CtxAfter implements SafeAutoClosable {
        final SessionAfter parent;
        final CtxAfter previousContext;
        final ItemStack itemStack;
        final ObfuscationLevel level;

        CtxAfter(SessionAfter parent, CtxAfter previousContext, ItemStack itemStack, ObfuscationLevel level) {
            this.parent = parent; this.previousContext = previousContext; this.itemStack = itemStack; this.level = level;
        }

        CtxAfter itemStack(ItemStack s) { return new CtxAfter(this.parent, this, s, this.level); }
        @Override public void close() { this.parent.switchContext(this.previousContext); }
    }

    // ============ 基准 ============
    final ItemStack stack = new ItemStack(42);

    @Setup
    public void setup() {
        SessionBefore.start(ObfuscationLevel.ALL);
        SessionAfter.start(ObfuscationLevel.ALL);
    }

    /** before：混淆中 withContext 捕获 lambda。 */
    @Benchmark
    public Object withContextLambda(Blackhole bh) {
        SafeAutoClosable c = SessionBefore.withContext(ctx -> ctx.itemStack(this.stack));
        bh.consume(c);
        c.close();
        return c;
    }

    /** after：混淆中 withItemStack 直路。 */
    @Benchmark
    public Object withItemStackDirect(Blackhole bh) {
        SafeAutoClosable c = SessionAfter.withItemStack(this.stack);
        bh.consume(c);
        c.close();
        return c;
    }

    /** before：start 每次新建上下文。 */
    @Benchmark
    public Object startFresh(Blackhole bh) {
        SessionBefore s = SessionBefore.start(ObfuscationLevel.OVERSIZED);
        bh.consume(s.obfuscationLevel());
        s.close();
        return s;
    }

    /** after：start 缓存上下文。 */
    @Benchmark
    public Object startCached(Blackhole bh) {
        SessionAfter s = SessionAfter.start(ObfuscationLevel.OVERSIZED);
        bh.consume(s.obfuscationLevel());
        s.close();
        return s;
    }

    public static void main(String[] args) {
        for (ObfuscationLevel level : ObfuscationLevel.values()) {
            // before 链
            SessionBefore sb = SessionBefore.start(level);
            ItemStack item = new ItemStack(7);
            CtxBefore startCtxB = sb.context();
            SafeAutoClosable cb = SessionBefore.withContext(c -> c.itemStack(item));
            boolean inObfB = sb.obfuscationLevel().isObfuscating();
            ObfuscationLevel levelB = sb.context().level;
            ItemStack itemB = sb.context().itemStack;
            CtxBefore prevB = sb.context().previousContext;
            cb.close();
            CtxAfter afterCloseProbe; // 占位，保持两版结构对照
            boolean backToStartB = sb.context() == startCtxB || sb.context() == null;
            sb.close();
            boolean rootB = sb.context() == sb.root;

            // after 链
            SessionAfter sa = SessionAfter.start(level);
            CtxAfter startCtxA = sa.context();
            SafeAutoClosable ca = SessionAfter.withItemStack(item);
            boolean inObfA = sa.obfuscationLevel().isObfuscating();
            ObfuscationLevel levelA = sa.context().level;
            ItemStack itemA = sa.context().itemStack;
            CtxAfter prevA = sa.context().previousContext;
            ca.close();
            boolean backToStartA = sa.context() == startCtxA || sa.context() == null;
            sa.close();
            boolean rootA = sa.context() == sa.root;

            if (inObfB != inObfA) throw new AssertionError(level + " isObfuscating");
            if (levelB != levelA) throw new AssertionError(level + " context level");
            if ((itemB == null) != (itemA == null) || (itemB != null && itemB.id != itemA.id)) throw new AssertionError(level + " itemStack");
            // NONE 级：两版均不进入上下文（previous 无从谈起）；混淆级：previous 应为 start 上下文
            if (level.isObfuscating()) {
                if (prevB == null || prevA == null) throw new AssertionError(level + " previous missing");
                if (prevB.level != level || prevA.level != level) throw new AssertionError(level + " previous level");
                if (prevB.itemStack != null || prevA.itemStack != null) throw new AssertionError(level + " previous item");
                if (!backToStartB || !backToStartA) throw new AssertionError(level + " close restore");
            }
            if (rootB != rootA) throw new AssertionError(level + " root restore");
            // start 上下文字段等价
            if (startCtxB.level != startCtxA.level || startCtxB.itemStack != null || startCtxA.itemStack != null
                || startCtxB.previousContext != null || startCtxA.previousContext != null) {
                throw new AssertionError(level + " start ctx fields");
            }
        }
        System.out.println("ALL OK");
    }
}
