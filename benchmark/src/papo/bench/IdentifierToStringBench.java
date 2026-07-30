package papo.bench;

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
 * 批20: Identifier.toString() 缓存。
 * before: 每次 namespace + ":" + path 字符串拼接（StringBuilder 链 + 分配）。
 * after:  惰性缓存字段（volatile，良性竞态）。
 * 场景: 模拟包/NBT 序列化对 64 个注册表单例 Identifier 反复 string 化，每轮 1024 次。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IdentifierToStringBench {

    static final class IdBefore {
        final String namespace, path;

        IdBefore(String ns, String p) {
            this.namespace = ns;
            this.path = p;
        }

        @Override
        public String toString() {
            return this.namespace + ":" + this.path;
        }
    }

    static final class IdAfter {
        final String namespace, path;
        volatile String cached;

        IdAfter(String ns, String p) {
            this.namespace = ns;
            this.path = p;
        }

        @Override
        public String toString() {
            String s = this.cached;
            if (s == null) {
                s = this.namespace + ":" + this.path;
                this.cached = s;
            }
            return s;
        }
    }

    @Param({"1024"})
    int calls;

    private IdBefore[] before;
    private IdAfter[] after;

    @Setup
    public void setup() {
        before = new IdBefore[64];
        after = new IdAfter[64];
        String[][] ids = {
            {"minecraft", "stone"}, {"minecraft", "diamond_sword"}, {"minecraft", "player_head"},
            {"minecraft", "entity/zombie_villager"}, {"papo", "custom/content_pack_entry"}
        };
        for (int i = 0; i < 64; i++) {
            String[] id = ids[i % ids.length];
            before[i] = new IdBefore(id[0], id[1]);
            after[i] = new IdAfter(id[0], id[1]);
        }
        after[0].toString(); // 预热缓存（真实场景中单例早已被 string 化过）
    }

    @Benchmark
    public void before_concat(Blackhole bh) {
        for (int i = 0; i < calls; i++) {
            bh.consume(before[i & 63].toString());
        }
    }

    @Benchmark
    public void after_cached(Blackhole bh) {
        for (int i = 0; i < calls; i++) {
            bh.consume(after[i & 63].toString());
        }
    }
}
