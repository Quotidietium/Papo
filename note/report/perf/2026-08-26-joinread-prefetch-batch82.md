# 批次 82 基准报告：join 读侧下放（登录窗口预取 .dat/stats/advancements）

> 日期：2026-08-26 · 分支 `perf/multicore-r2` · 基准类 [JoinReadPrefetchBench](../../../benchmark/src/papo/bench/JoinReadPrefetchBench.java)
> 多核调度系列④——批次 79 只下放了 join **写**侧；读侧（.dat gzip+NBT+datafix、stats/advancements 严格 JSON 解析）仍在主线程同步执行（PrepareSpawnTask.start / ServerPlayer ctor，均主线程实证）。

## 机制

登录流程在 `startClientVerification`（全部认证模式汇合点，AsyncPlayerPreLoginEvent 之后）就拿到了最终 GameProfile，而消费点（配置阶段读 playerdata、ServerPlayer ctor 读 stats/advancements）至少隔一个客户端 RTT。批次 82 把三类读在**登录时刻**挂到各自文件的 per-target 有序写链上（`PapoOrderedFileWrites.enqueueRead`，读任务 `Priority.BLOCKING`），主线程消费点 join 时常已完成的 future：

```
before:  主线程 ──[.dat gunzip+NBT+datafix + stats/adv JSON ×N 人]──►  (串行阻塞)
after:   登录时刻 ──入队 3N 个读(BLOCKING)──►  RTT 窗口(主线程做别的)  ──join(通常已完成)──►
```

读挂在与写相同的 per-Path `CompletableFuture` 链上 ⇒ **结构性读后写排序**（快速重连读最新存档，批次 79 语义保持），且池线程内无任何阻塞等待（`awaitPending` 从池线程调用会在单线程 IO 池下死锁——本机制用链排序规避）。

## 模型

- 20 人重启 rush 突发 join（同一登录窗口到达）。
- 每玩家 `.dat` = 64KiB 混合熵载荷 gzip（文件 ~29KiB；gunzip 全量解压 + 全数组扫描作为 NBT DOM 遍历接触成本代理）；stats = 120 条目 JSON（~5KiB）；advancements = 150 条目 JSON（~14KiB）；解析用 Gson（StrictJsonParser 同类成本代理）。
- **datafix 未建模**（需服务器类）——它是被下放的额外纯 CPU，实测收益是保守下界。
- 池 = 真实 concurrentutil `BalancedPrioritisedThreadPool`（IO_QUEUE_HOLD_TIME=25ms）+ `OrderedStreamGroup` executor，`regionIoThreads = clamp(cores/8,1,4) = 4`（本机 32 核，批次 78 sizing 复刻）。

## 主线程 join 成本（3 reps）

| 形态 | 主线程耗时（均值） |
|---|---|
| before（同步串行读） | **15.45 ms** |
| after（预取 + 50ms RTT 窗口） | **0.04 ms**（**≈380×**） |
| after 最坏情形（**零** RTT 窗口，客户端瞬连） | 7.35 ms |

最坏情形解读：即使客户端零延迟推进到消费点，主线程也只等待池上**并行**执行的总读时延（4 IO 线程），恒不劣于同步串行（15.45ms）——机制在任意时序下不会引入回退。

```
rep0  sync=16,822us  prefetch(window)=35us   prefetch(no-window)=8,151us
rep1  sync=15,218us  prefetch(window)=66us   prefetch(no-window)=6,382us
rep2  sync=14,298us  prefetch(window)=20us   prefetch(no-window)=7,520us
```

## 饱和探针（流畅度红线：池饱和时主线程不饿死）

IO 池被 region-IO 形态任务填满（4KiB 真实读 + 500µs 模拟设备延迟 × 64，IoPoolScalingBench 同载荷）时，主线程 join 一个读任务的等待：

| 读任务优先级 | 主线程等待（best of 3） |
|---|---|
| **BLOCKING**（本机制采用） | **625 µs** |
| NORMAL（若不用优先级的对照） | 234,305 µs |

**375× 差距。** `Priority.BLOCKING` 是 vanilla 同步 sync-load 同款优先级（`MoonriseRegionFileIO.getIOBlockingPriorityForCurrentThread`，tick 线程 → BLOCKING），在池层抢占排队中的 region IO——这是"不为多核牺牲流畅度"的结构保证。另经最小复现实证：BLOCKING 任务在同一 OrderedStreamGroup 内可越过已排队的 NORMAL 任务（t4 插队 t2/t3 之前），任务异常不毒化流（后续任务照常执行）。

## 自检（安全性红线，全部 ALL OK）

1. **读后写排序**：同文件先入队写再入队读 → 读观察到写内容，且执行序写先于读；
2. **内容等价**：预取 gunzip 结果与同步读逐字节相同；
3. **consume-once / discard**：缓存 remove 语义一次恰取；
4. **缺失文件 → null**（消费点回退原同步路径，vanilla 告警行为保持）；
5. **同文件 FIFO**：两次读按提交序执行。

## 等价性论证摘要（详见补丁注释与 optimizations.md 批次 82 条目）

- 副作用（`.offline-read` 重命名、`_corrupted_` 备份）**留在主线程消费点原逻辑位置**——登录中断窗口内不会提前留下副作用（严格等价红线）；
- 预取体异常 → `fallbackToSync` 标记 → 消费点重跑原同步路径（vanilla 异常/告警行为逐字保持）；
- 单人游戏主机（isSingleplayerOwner）跳过预取（数据来自内存 tag）；
- 登录/配置两阶段断连钩子丢弃未消费预取，无缓存泄漏；
- `readCompressed` 与 `MCDataConverter.convertTag` 均纯函数且已被 moonrise worker 线程并发使用（chunk 加载），线程安全有既有实证。

## 基准工程踩坑（已修，留档）

- 共享 `ByteBuffer` 跨池任务并发 `rewind()/read()` 触发 `BufferOverflowException`，任务在计数递减前死亡 → 排水循环卡死。修复：每任务独立 buffer + `finally` 递减。
- Git Bash 下长输出经 `| head -40` 管道：Windows 管道语义下 head 退出后 java 进程写管道**阻塞不退出**（假挂）。修复：输出重定向文件。
