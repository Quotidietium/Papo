package papo.bench;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Iterator;
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
 * 0040/0047: CompoundTag write/merge 遍历。
 * before: keySet() 迭代 + 每键 get()（两次哈希查找）。
 * after:  object2ObjectEntrySet().fastIterator()（复用 Map.Entry，一次定位）。
 * 尺寸: 16（典型物品/实体 NBT）、128（玩家数据）、1024（大型结构/区块实体表）。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CompoundTagIterBench {

    @Param({"16", "128", "1024"})
    int size;

    private Object2ObjectOpenHashMap<String, Object> map;

    @Setup
    public void setup() {
        map = new Object2ObjectOpenHashMap<>(size, 0.8f);
        for (int i = 0; i < size; i++) {
            map.put("key_" + i + "_somewhat_longer_name", new Object());
        }
    }

    @Benchmark
    public void before_keySetGet(Blackhole bh) {
        for (String key : map.keySet()) {
            Object value = map.get(key);
            bh.consume(key);
            bh.consume(value);
        }
    }

    @Benchmark
    public void after_fastIterator(Blackhole bh) {
        Iterator<Object2ObjectMap.Entry<String, Object>> it = map.object2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            bh.consume(entry.getKey());
            bh.consume(entry.getValue());
        }
    }
}
