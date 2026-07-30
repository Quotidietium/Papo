package papo.bench;

import java.util.ArrayList;
import java.util.List;
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
 * 0070: SignableCommand.hasSignableArguments 早退。
 * 模拟原实现 of() 的完整列表构建 + isEmpty() 判断 vs 命中即返回。
 * 参数: signedAt = 签名参数出现的位置（0=首个，-1=无签名参数）。
 * 列表长度 16，模拟典型命令的节点链。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EarlyExitBench {

    @Param({"0", "8", "-1"})
    int signedAt;

    private List<boolean[]> nodes; // 每个 context 节点的参数表，true=签名参数

    @Setup
    public void setup() {
        nodes = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            boolean[] args = new boolean[4];
            if (i == signedAt) {
                args[1] = true;
            }
            nodes.add(args);
        }
    }

    /** 原实现：构建完整列表再判空。 */
    @Benchmark
    public void before_buildFullList(Blackhole bh) {
        List<String> result = new ArrayList<>();
        for (boolean[] args : nodes) {
            for (int j = 0; j < args.length; j++) {
                if (args[j]) {
                    result.add("arg" + j);
                }
            }
        }
        bh.consume(!result.isEmpty());
    }

    /** Papo 0070：命中首个签名参数即返回。 */
    @Benchmark
    public void after_earlyExit(Blackhole bh) {
        boolean found = false;
        outer:
        for (boolean[] args : nodes) {
            for (boolean arg : args) {
                if (arg) {
                    found = true;
                    break outer;
                }
            }
        }
        bh.consume(found);
    }
}
