# ReobfServer 多线程重映射评估报告（方案 D 否决）

> 报告日期：2026-08-02
> 评估对象：[ReobfServer.java:72](../../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) 的 `.threads(1)` 是否值得改为多线程以加速运行时 remapping
> 结论：**否决**。实测多线程对 reobf 无实质收益（threads=2 噪声级 ~15%，threads≥4 持平/略差），按 Papo「实测无收益即撤」纪律放弃。
> 关联：[2026-08-02-startup-remap-analysis.md](../2026-08-02-startup-remap-analysis.md) 方案 D 的实证否决；本评估工具见 [reobf-threads-bench/](reobf-threads-bench/)。

---

## 1. 假设

[ReobfServer.java:72](../../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) 把整个服务端 jar 的重映射钉死在单线程（`.threads(1)`）。`net.neoforged ART`（AutoRenamingTool）的 `Renamer.Builder.threads(int)` 是公开 API，内部 `AsyncHelper` 字节码实证：

- `threads == 1` → `Executors.newSingleThreadExecutor()`（class entry 串行处理）
- `threads > 1` → `Executors.newWorkStealingPool(n)`（class entry 并行 `submit`）

**初始假设**：reobf 重映射整个服务端 jar（~30MB，17590 entries），瓶颈是 CPU（对数千 class 做 ASM 字节码重命名），单线程是瓶颈 → 多线程应近线性加速，预期 2–3×。

## 2. 机制澄清（评估中修正的认知）

- 运行时实际使用的 ART internal（`RenamerImpl`/`AsyncHelper`）是 **paper-server shade 的较新版**，不是独立依赖 `net.neoforged:AutoRenamingTool:2.0.3`。shade 版含 3 参 `run(File,File,boolean)`（与 [ReobfServer.java:76](../../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L76) 调用匹配），独立依赖那份只有 2 参 `run(File,File)`。
- 验证 main 的 classpath 必须把含 shade 版的 jar（`run/versions/1.21.11/paper-1.21.11.jar`）放前，否则会加载独立依赖那份（2 参 run），无法复现运行时行为。
- 多线程本身**安全**：每个 class entry 独立 `processEntry`（ASM 重命名），映射表只读，结果按原序收集，无共享可变状态。

## 3. 验证方法

独立计时 main（不复用 JMH——reobf 是秒级一次性操作，JMH fork/warmup 开销不适用），复现 `ReobfServer.remap` 的 ART 调用：

```
Renamer.builder().threads(N).logger(s->{}).debug(s->{}).add(Transformer.renamerFactory(mappings, false)).build()
  → ((RenamerImpl) renamer).run(inputJar, outputJar, true)
```

- 输入：`run/versions/1.21.11/paper-1.21.11.jar`（运行时真实 `serverJar()`，含 `reobf.tiny` + shade ART + 全部服务端类，30.4MB）
- classpath：上述 jar（前）+ `run/libraries/**`（asm 9.9.1、srgutils 等）
- 机器：32 逻辑核
- 工具：[ReobfTiming.java](reobf-threads-bench/ReobfTiming.java)（正确性 + 1 vs 8 计时）、[ReobfScaling.java](reobf-threads-bench/ReobfScaling.java)（threads 1/2/4/8/16/32 scaling）

> 复现：`cd paper-server && jar xf run/versions/1.21.11/paper-1.21.11.jar META-INF/mappings/reobf.tiny`，然后以 `run/versions` jar + `run/libraries` 为 classpath 编译运行上述两类（详见源文件头注释）。

## 4. 结果

### 4.1 正确性 ✓

`threads(1)` 产物 vs `threads(8)` 产物，逐 entry sha256 比对：

```
[diff] entries same=17590 diff=0 onlyIn1=0 onlyInN=0
[correctness] ALL OK: 1-thread 与 N-thread 产物逐 entry 字节一致
```

多线程重映射的字节级结果与单线程**完全一致**——多线程只改并行度，不改每个类的处理逻辑。

### 4.2 速度 ✗（关键否决依据）

**ReobfTiming**（warmup 后 best of 3）：

| 配置 | best (ms) |
|---|---|
| threads(1) | 2611 |
| threads(8) | 2577 |
| **speedup** | **1.01×** |

**ReobfScaling**（warmup 3 次后，每档 best/mean of 3）：

| threads | best (ms) | mean (ms) |
|---|---|---|
| 1 | 2670 | 2703 |
| 2 | 2275 | 2417 |
| 4 | 2660 | 2777 |
| 8 | 2659 | 2666 |
| 16 | 2735 | 3000 |
| 32 | 2988 | 3014 |

- threads=2 的 best 2275ms 看似比 threads=1 快 ~15%，但 **mean 仅 2417 vs 2703（~11%）且处于 ±100–200ms 的测量噪声带**；
- threads≥4 **完全不加速，甚至略差**（threads=4 mean 2777 > threads=1 mean 2703；threads=16/32 明显退化）；
- 这种「threads=2 略快、4+ 退回」的**非单调**模式是噪声特征，而非真实并行收益（若并行真有效，4/8 应至少持平 2，而非退回 1 的水平）。

## 5. 瓶颈分析（为何多线程无效）

结合 `RenamerImpl.run` 字节码，reobf 的 ~2.6s 时间构成大致为：

1. **遍历输入 jar 读全部 17590 entries**（`ZipFile.entries()` + 逐个 `readAllBytes`）——单线程顺序 IO；
2. **构建 inheritance map**（"Adding input to inheritance map"，对每个 class 做继承/接口关系分析，用于方法重写映射传播）——单线程；
3. **`AsyncHelper.invokeAll` 并行 `processEntry`**（ASM 重命名 class 字节码）——**唯一可并行段**；
4. **写输出 jar**（`ZipOutputStream` 逐 entry 写）——单线程顺序 IO。

可并行的只有段 3。但段 3 对每个 class 的 ASM 重命名是**微秒级**操作，总 CPU 工作量小、且在 threads=2 已基本饱和；而段 1/2/4（单线程 jar IO + inheritance map）占了 2.6s 的绝大部分。故 threads 1→8 只能把小头并行化，整体 1.01×，threads>2 时 work-stealing pool 的调度/竞争开销反而拖慢。

> 注：本机测得 ~2.6s，[run/logs/latest.log](../../../run/logs/latest.log) 中 0.1.0 首启测得 5371ms——量级一致（秒级），差异来自机器/磁盘/输入 jar 版本。结论（多线程无效）不受绝对值影响。

## 6. 结论与有效替代

**方案 D（`.threads(1)` → 多线程）否决**，不落地。理由：实测无实质收益（≤15% 噪声级，threads≥4 持平/略差），违反 Papo「实测无收益即撤」纪律（同 0100 forEach→entrySet、0181 Movement 缓存、0187 记分板 Optional 等撤销先例）。

reobf 单次耗时（~2.6s）的瓶颈是 ART 内部的单线程 jar IO + inheritance map，不在 ReobfServer 可控范围；**单次 reobf 速度本身难以优化**。真正减少 reobf 对启动影响的途径仍是 [启动分析报告](../2026-08-02-startup-remap-analysis.md) 的：

- **方案 A（保留缓存目录 `plugins/.paper-remapped/`）**：reobf 只在首次/升级时各跑一次，之后永久跳过——零代码、零风险，首选。
- **方案 B（分发用 `createReobfBundlerJar` 产物）**：从机制上彻底跳过运行时 ReobfServer。

## 7. 附：评估工具

- [reobf-threads-bench/ReobfTiming.java](reobf-threads-bench/ReobfTiming.java)：正确性（逐 entry sha256 diff）+ threads(1) vs threads(N) 计时。
- [reobf-threads-bench/ReobfScaling.java](reobf-threads-bench/ReobfScaling.java)：threads 1/2/4/8/16/32 scaling。
- 两者均需 `run/versions/<mc>/paper-<mc>.jar`（含 shade ART）+ `run/libraries/**` 作 classpath；`reobf.tiny` 从该 jar 提取（`META-INF/mappings/reobf.tiny`，6.8MB，未入库）。
