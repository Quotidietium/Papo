# 批次 91 报告（2026-08-26）— 停机窗口提交竞态：语义实证 + 加固（稳定性轮，多核调度⑫）

主题：goal 后半句——"多核调度之后的服务器核心稳定性"。批次 79/82 落地的
`PapoOrderedFileWrites`（离线程玩家存档写 + join 读预取）在**停机窗口**的提交语义此前
从未实证。本轮用行为探针先取事实，再修缺陷，最后模型对拍 + 真实服务器实弹验证。

## 一、语义实证（[HaltSemanticsProbe](../../../benchmark/src/papo/bench/HaltSemanticsProbe.java)，concurrentutil 0.0.8 实跑）

针对 `BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue`：

| 场景 | `isActive()` | `queueTask` 行为 |
|---|---|---|
| Q1 `shutdown(false)` 排空中 | **true**（预检失效） | 抛 `IllegalStateException("Queue is shutdown")` |
| Q2 `shutdown(false)`+join 终止 | false | 同上抛 ISE（一元重载 Q6 同） |
| Q3 `halt(false)` 强制后 | **true**（预检失效） | 返回 Task 但**永不调度**（静默丢弃） |
| Q4 `halt(false)` 对已排队任务 | — | 丢弃 |
| Q5 终止池上 CompletableFuture 提交 | — | future 异常完成，**提交的 ioTask 不运行** |

关键结论：
1. **`isActive()` 预检只覆盖 Q2**。Q1（排空期，isActive 仍 true）正是真实竞态窗口——
   authenticator 线程的 `enqueueRead`（登录预取）与主线程 `stopServer` 尾部
   `MoonriseCommon.haltExecutors()` 的 IO 池 `shutdown(false)` 并发。
2. 修复前故障模式（Q5/G 门实证）：CompletableFuture 把 executor 抛出的 ISE 转成异常完成，
   簿记（TAILS/pending）保持一致，**但写任务被静默丢弃（丢档），读的 result future 永不完成**
   ——`PlayerDataStorage` 消费方有 60s 有界 get 兜底，`ServerStatsCounter`/`PlayerAdvancements`
   消费方是**无界 `join()`**（潜在主线程永久挂起，仅在关服竞态下可达）。
3. Q3/Q4 的 `halt(false)` 路径**提交侧不可检测**（isActive 撒谎 + 返回真 Task 对象）——仅在
   60s 优雅停机超时（病态 IO）后触发，接受为残余面：该窗口丢写在性质上等同 watchdog 强杀
   （vanilla 同步存档同样无法幸存），已在代码注释留档。

## 二、修复（`PapoOrderedFileWrites.java`，直跟踪源码非补丁树）

1. **写路径**：`thenRunAsync` 的 executor 由 `QUEUE::queueTask` 换为 `executeOrRun`——捕获
   ISE 就**内联降级执行**（与既有 `isActive()` 预检的同步降级同一契约："runs the task
   synchronously so durability never silently degrades"）。窗口命中时存档不再丢，而是就地写。
2. **读路径**：`enqueueRead` 的 executor 同样捕获 ISE → `task.run()` 内联——result future
   必然完成（消费方 60s get / 无界 join 的悬挂类整类消除），语义=同步回退路径。
3. **`awaitPending` 中断卫生**：`InterruptedException` 分支恢复中断标志（原吞掉不恢复，
   关服中断会隐形）。
4. 正常路径零行为变化：包装只多一层 try/catch，不抛时与原 `queueTask` 完全同构（提交成本
   ns 级 vs 文件 IO ms 级，无需基准化）。

## 三、模型对拍（[HaltRaceBench](../../../benchmark/src/papo/bench/HaltRaceBench.java)，G1-G5 全绿）

修复前复刻链 vs 修复后复刻链（真实 concurrentutil 池）：

| 门 | 场景 | legacy（修复前） | fixed（修复后） |
|---|---|---|---|
| G1 | 终止池·写 | ioTask **未运行**（丢档），future 异常完成 | ioTask 运行，future 正常完成，pending=0 |
| G2 | 终止池·读 | result future 500ms **未完成**（悬挂） | 完成且值正确 |
| G3 | 排空池·写（isActive=true 真实窗口） | 同 G1（丢档） | 内联降级，pending=0 |
| G4 | 窗口命中后同路径再提交 | 第二个任务同样被丢 | 链健康，任务运行 |
| G5 | awaitPending 中断 | 标志丢失 | 标志恢复 |

## 四、真实服务器验证（0.59.0）

1. **全量测试套件**：BUILD SUCCESSFUL（exit 0）。
2. **四态冒烟**（[SmokeJoinVerify](../../../benchmark/src/papo/bot/SmokeJoinVerify.java)）：
   10/10 join、shutdown exit 0、日志零 ERROR/Exception、三类存档产物合法；
   稳态 join mean 18.4ms / p50 15.0ms / max 46.0ms——与批次 88 基线 19.0ms 持平（无回退）。
3. **突发压测**（BurstJoinVerify 20 fat-dat bot）：lastSpawn 3864ms / p50 3384ms / p95 3540ms、
   exit 0、零门错误、dats ok——在 0.57.0 同场景带宽（3348-3478ms）内，无回退。
4. **停机竞态实弹**（新 [ShutdownRaceVerify](../../../benchmark/src/papo/bot/ShutdownRaceVerify.java)，
   本批新增）：预热 join×2 → 12 bot 错峰（0..2750ms）join+5s 停留 → t=+1200ms 下发 `stop`
   （关服时刻必然混合 login/config 读预取在飞 + play quit 存档在飞）→ 核验 exit 0、
   日志零错误（扣良性突断）、**playerdata 下每个存在 .dat 全部 gzip+NBT 合法** → 同目录
   重启 boot+join+stop 再核验（竞态不得破坏世界可读性）。

## 四A、race 门捕获的上游错误（归属分析，A/B 实证）

首轮 race 抓到 `Thread failed main thread check: Accessing poi chunk off-main,
context=thread=Paper Common Worker #3` + `Error executing task on Server`。**完整堆栈归属**：
worker 池并行世界生成（`ChunkUpgradeGenericStatusTask.generateFeatures`）→ 结构放置
`WorldGenRegion.setBlock` → `ServerLevel.updatePOIOnBlockStateChange`（上游 Paper 把 POI
变更包进 `server.execute(...)` 转主线程）→ 但 `MinecraftServer.scheduleExecutables()` 被
CraftBukkit 上游覆写为 `super && !isStopped()`——**关服尾部 `isStopped()` 为真时任何线程的
`server.execute` 都走 `BlockableEventLoop.execute` 的 `doRunTask` 内联分支**，POI 检查因此
在 Worker #3 上执行，Paper 区块系统的主线程断言报 ERROR。堆栈不含任何 Papo 改动行；
新生成区块不存在 stale POI（该清理本就无事可做），无数据影响（exit 0、dat 全合法、重启
干净佐证）。

**A/B 归属实证**：构建 pre-fix 对照 jar **0.58.1**（= 0.58.0 源码 + 批次91 修复回退）与
**0.59.0** 各跑 race——0.58.1 首轮即触发该 ERROR（`Paper Common Worker #6`，同一上游堆栈），
**先在性证明：上游既有行为，非批次 91 回归**（两 jar 上游代码相同，触发为随机时序事件：
stop 时刻恰有结构生成在 worker 上触碰 POI 钩子）。处理：race 门将其单独披露计数
（`upstream-poi-inline`），不计入失败门（与良性 bot 突断同待遇：根因明确 + 披露 + 排除）；
不做代码"修复"——修上游停机内联语义超出等价性红线，且该 ERROR 在此场景无实际后果。
最终记录（修正口径后双 jar ×3）：

| jar | 轮次 | 门 | spawned/abrupt | .dat 校验 | 重启核验 | upstream-poi-inline |
|---|---|---|---|---|---|---|
| 0.59.0（fixed） | ×3 | **全 PASS** | 5/7 ×3 | 6 个全 ok ×3 | exit 0 + join + dat ok ×3 | 0 |
| 0.58.1（pre-fix） | ×3 | **全 PASS** | 5/7 ×3 | 6 个全 ok ×3 | exit 0 + join + dat ok ×3 | 0（另一次单独运行触发 1 次） |

两 jar 行为画像一致（fix 不引入任何可观察差异）；POI 触发为随机时序事件（pre-fix 首轮
运行已复现），与 jar 版本无关——归属链闭环：上游堆栈 + pre-fix 复现 + 双版本同分布。

## 五、判例

1. **第三方池的停机语义必须实证而非读名**：`isActive` 在排空期与 halt 后都会"撒谎"
   （排空期 true + 拒收抛异常；halt 后 true + 静默丢弃），提交侧防御要以抛出的 ISE 为准
   （catch → 降级），不能依赖 isActive 预检。
2. CompletableFuture 会把 executor 抛出的异常转成 future 的异常完成——**任务体不运行但
   future 有终态**，这类"安静失败"在离线程管线里只能靠对拍基准抓。
3. 停机窗口加固的正确形态是**降级而非重试**：与既有同步降级契约统一，不引入新的调度假设。
