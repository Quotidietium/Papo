# 批次 84 报告（2026-08-26）— join 突发压测：老玩家 fat .dat × 20 bot 并发（批次 82 的实战检验与三轮设计迭代）

主题：**稳定性压测 + 实测驱动的设计修正**。批次 83 的顺序 join 冒烟未覆盖并发登录；
本批构建 fat .dat 生成器（DataVersion=3700 → 真实 datafix 全链，78KB 未压缩结构化玩家
NBT）与 20 bot 屏障并发 burst 对拍（0.55.0 vs 0.54.0，fresh 服务器/固定 seed/双向序各
3 轮）。**压测报出一个真实并发 bug 并推翻批次 82 的两项设计假设**，经三轮迭代收敛到
统计持平且略优的终态。

## 1. 抓到的真实 bug：getLevelPath HashMap 并发修改（已修）

20 个 bot 同时登录时 authenticator 池多线程并发调 `papoPrefetchJoinData` →
`MinecraftServer.getWorldPath` → `LevelStorageAccess.getLevelPath` 的**普通 HashMap
computeIfAbsent 缓存** → `ConcurrentModificationException` → 该 bot 登录失败
（"Failed to verify username!"）。顺序 join（批次 83）单 authenticator 线程串行永不触发。
**修复**：stats/adv 目录在 PlayerList 构造期（主线程）一次性解析为 final 字段，authenticator
线程只读。修复后 240+ 并发 join 零复现。

## 2. 三轮设计迭代（实测否决 → 收敛）

| 版本 | datafix 位置 | 读优先级 | 20-bot burst 结果（vs 0.54.0 基线 3542-3686ms） |
|---|---|---|---|
| v1（批次82原版） | IO 池（读任务内） | BLOCKING | **慢 ~250-450ms**（一致性，双序 6 轮） |
| v2/v3 | worker 池（HIGHEST→NORMAL） | BLOCKING | **仍慢 ~270-450ms** |
| **v5（终态）** | **主线程消费点（与原版逐字相同）** | **NORMAL + 消费点未完成则 raiseToBlocking** | **持平且双序均值均略优 30-50ms** |

### 归因链（对照实验）
- **datafix 位置否决**：v1 把 datafix（纯 CPU，旧文件 ~50-150ms/人）放 IO 池——占住 IO 线程
  与 spawn 区块的 region 读竞争；v2/v3 移到 worker 池——与区块加载反序列化（也在 worker 池）
  竞争。**现代文件对照实验**（DataVersion=4671，datafix 无步进）v3 仍慢 ~270ms——证明回归
  与 datafix 无关或不止 datafix。
- **读优先级否决**：真正元凶是 BLOCKING 优先级的 60 个读任务（20×.dat + 40×stats/adv）在
  池层**抢占 spawn 区块读**（区块读自身常为 BLOCKING——moonrise 同步等待语义）。
- **终态设计**（moonrise 自身模式 `getIOBlockingPriorityForCurrentThread` 的镜像）：
  入队 **NORMAL**（窗口期无人在等，不抢占任何人）+ 消费点发现未完成则
  **raisePriority(BLOCKING)**（主线程真在等时才升级，恢复 vanilla 同步加载语义）。
  模型基准的饱和探针（独立组 NORMAL 读等 252ms / BLOCKING 704µs）恰好构成升级机制存在
  必要性的证据——两探针数字从"矛盾"变为"互补"。

## 3. 终态 burst 结果（双向序 × 3 轮，全绿）

| 指标 | 0.55.0 (v5) | 0.54.0 |
|---|---|---|
| lastSpawn（6 轮） | 3394/3656/3856/3526/3650/3557 | 3578/3692/3781/3851/3472/3494 |
| 均值 | **3607ms** | 3645ms |
| best | 3394 | 3472 |
| 门 | 全部通过（exit 0 / 零门错误 / dat 合法） | 同左 |

双向序各自内部：0.55 先行时 0.55 均值低 49ms；0.54 先行时 0.55 均值低 28ms——**两序
均非负**，轮间噪声 ±200ms 下判为统计持平（不主张端到端收益；主线程收益由模型基准
量化，见批次 82 报告）。

## 4. 结论与判例

1. **批次 82 终态语义修正**：预取 = 纯读侧（file+gzip+NBT parse，IO 池 NORMAL + 消费点
   升级）；**datafix 留在主线程消费点与原版逐字相同**——"读侧下放"名副其实，严格增量。
2. **判例：跨池移动 CPU 工作必须看它在和谁竞争**——datafix 在主线程是"自然限流"，移到
   IO/worker 池就与区块管线竞争；突发的端到端反而变慢。模型基准的 380× 主线程收益
   数字为真，但端到端 burst 视角下 v1 的总布局是净负——**两个基准口径都要看**。
3. **判例：BLOCKING 优先级是"有人真在等"的资源**——入队即 BLOCKING 等于在无人等待时
   预支抢占权；moonrise 的 raisePriority 模式（等待发生时升级）是正确形态。
4. 并发登录压测必须进验证矩阵（顺序 join 测不出的 CME）。

## 5. 工具沉淀

- [MakeFatPlayerDat](../../../benchmark/src/papo/bot/MakeFatPlayerDat.java)：fat 老玩家
  .dat 生成器（NBT writer 纯 JDK；PAPO_FAT_DV 环境变量切换新旧版本做归因对照）。
- [BurstJoinVerify](../../../benchmark/src/papo/bot/BurstJoinVerify.java)：20 bot 屏障并发
  burst + 关服契约 + 产物校验 + 零异常门（bot 突断噪声三形态已知良性：StacklessClosed/
  Connection reset/中文 reset——服务器 `-Dfile.encoding=UTF-8` 后统一可匹配）。
