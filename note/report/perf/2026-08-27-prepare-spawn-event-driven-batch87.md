# 批次 87 报告（2026-08-27）— join 管线相位分解与 prepare_spawn 事件驱动完成（稳态 join −57%）

主题：批次 86 判例的执行——"多核利用率瓶颈常在主线程串行面"。用包级相位计时分解 join
管线，锁定并消除最大的量化等待面。

## 1. 相位分解（[JoinPhaseBench](../../../benchmark/src/papo/bot/JoinPhaseBench.java)）

稳态重连（文件在/区块热）12 次中位，0.55.0（批次 87 前）：

| 相位 | t | gap | 构成 |
|---|---|---|---|
| LOGIN 完成 | 21ms | 21ms | 登录处理（auth 线程往返 + 主线程调度） |
| CONFIG 注册表 ×26 包 | 23ms | 2ms | 配置管道（批次 74 memo：26 包仅 ~1ms） |
| CONFIG tags | 25ms | 2ms | |
| **CONFIG finish** | **71ms** | **46ms** | ★ prepare_spawn 状态机只在 tick 边界推进 |
| PLAY 首包 | 80ms | 9ms | placeNewPlayer（本就在 tick 间包处理窗口跑） |

**46ms ≈ 一整个 tick 的纯量化等待**：① 区块加载票据在 start() 后的**首个 tick** 才被调度；
② future 完成后要等**下个 tick** 才被观察——两级量化，最坏 ~100ms/join。

## 2. 机制（0250）

`PrepareSpawnTask` 完成事件驱动化：start() 末尾 + spawnPosition/eventFuture/chunkLoadFuture
的 whenComplete 回调统一 `server.execute`（主线程任务队列——包处理器与 placeNewPlayer
本就运行的 tick 间窗口）推进同一 `tick()` 逻辑；**CAS 单发转移**（`papoFinishClaim`）。

### 等价性与并发论证
- 全部推进路径在主线程串行；Preparing→Ready 转移 CAS 单发——事件驱动路径与常规
  listener.tick() 竞争时先到者胜、后到者让位（外层 tick() 输 CAS 即 yield false，
  listener 不再重复 finishCurrentTask）。
- **初版缺陷（burst 实测抓出）**：whenComplete 在 `Preparing.tick()` 内联触发时内层
  （事件驱动）+外层（listener 常规）双重 `finishCurrentTask` → "current task: join_world,
  requested: prepare_spawn" 异常断连——CAS 修复后 6 轮 burst 120 bot 零复现。
- 包/事件序列逐连接完全相同，仅提前（≤2 tick）；PlayerJoinEvent 仍在主线程；跨玩家事件
  顺序仅依赖主线程串行化（与 vanilla 同保证）。
- 断连/关服路径：close() 先 cancel 后置 null，回调观察到非 Preparing 即 no-op；
  executor 拒绝（停机尾部）时常规 tick 路径兜底完成。

## 3. 结果

| 指标 | 0.55.0（前） | 0.56.0-b87（后） |
|---|---|---|
| 稳态重连 spawn（相位基准 ×3） | 80ms | **32/34/35ms（−57%）** |
| 四态冒烟稳态 join mean | 83.5ms | **37.4ms（−55%）** |
| fat-dat 20-bot burst（vs 0.54.0） | 持平 | 持平偏好（−73ms，零门错误） |
| 门 | — | 全量 test ✓ / 冒烟 10/10 零异常 ✓ / burst 6 轮 ✓ |

相位级：finish-config gap 46ms→**0ms**；剩余 35ms = 登录 21 + 配置管道 4 +
placeNewPlayer 9（均已无 tick 量化成分）。

## 4. 判例

- **配置任务状态机的 tick 量化是 join 延迟的主导项**（占比 57%），事件驱动推进是正解；
  先例（placeNewPlayer 经包处理窗口运行）证明该窗口承载 join 完成路径在 vanilla 语义内。
- whenComplete 回调可能在 future 赋值表达式内**同步/内联**触发——状态机完成转移必须
  单发（CAS），否则重入双 finish。
