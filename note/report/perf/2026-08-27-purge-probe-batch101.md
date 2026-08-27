# 批次 101 报告（2026-08-27）— tracker purge 探测 O(1) 化：purge 风暴面消除（多核调度系列⑰，0.62.0→0.63.0）

主题：批次 98/100 遗留的 `chunkMap.tracker.maintain` 温和超线性（154→470→1032us@40/120/160）
勘察归因。逐对成本解剖（updatePlayer 全链：AsyncCatcher/距离²/getPlayerViewDistance 钩子/
trackingRangeY 配置链/broadcastToPlayer/chunkPosition 缓存读/isChunkTracked/seenBy 探测，
~40ns/对，且此前批次已做 3 处 Papo 优化：范围提升、vanish 跳过、add 跳过）——**对数
O(N²) 在精确性红线下不可削减**（spectator 切换/vanish/lastSendDistance 均无完整事件化，
跳过重评估即行为分歧）。真正的结构性浪费在 **purge 风暴**：

## 归因：purge 风暴

`moonrise$tick` 的 purge 分支（chunk 视图集 updateCount 变化时）对每个 tracker 的每个
seenBy 成员执行 `ReferenceList.contains(player)`——**线性身份扫描**。聚堆 160 bot 全在
同一 chunk：任一 bot 行走跨 chunk 边界（160 bot 错峰下近乎每 tick 都有）→ 该 chunk 全部
160 tracker 触发 purge → 160 tracker × 160 seenBy × O(160) 线性扫 ≈ **每风暴 tick 数百万次
比较**，摊入窗口均值即 maintain 的 ~1ms 超线性。

## 实现（0256 补丁）

- `NearbyPlayers.TrackedChunk.papoContainsInViewDistance(player)`：惰性构建
  `ReferenceOpenHashSet` 探测集，**以 updateCount 为失效键**（每次 add/remove 自增，
  天然精确失效）；同 tick 内同 chunk 的全部 tracker 共享一次重建，此后 O(1) 探测。
- `ChunkMap.TrackedEntity.moonrise$tick` purge 分支换用该探测。
- 等价性：视图表按构造无重复（add 重复抛异常），集合成员 ⟺ 列表成员；purge 循环内
  removePlayer 不触碰 NearbyPlayers 表（无中途失效窗口）；跨 tick 由 updateCount 失效。

## 基准

- **PurgeProbeBench 等价自检**：随机玩家集/seenBy 子集/两代 updateCount 代际 ×5,000 组，
  移除决策序列逐位一致（含代际失效语义）全 PASS。
- **JMH（160 玩家 × 160 tracker × 160 seenBy 风暴模型）**：before 1389.7us → after 230.5us
  （**6.0×**，after 含一次建集）。

## 验证

- 全量 test ✓；0256 补丁 rebuild 干净（仅新增）；0.63.0 jar 构建成功；
- 四态冒烟 10/10 joins、exit 0、dat/stats/advancements 全 ok；
- 160 bot A/B：见下节（共享机重度争抢时段的净窗口追测）。

## 160 bot A/B（共享机争抢披露）

验证期（20:04-21:13）恰逢外部租户持续满载：连续 8 轮追测全部 util 92-128%（tick 滞后，
跨 epoch 与 0.62.0 净基线无可比性）——**本会话未取得净窗口 A/B，留作机器静默后的
后续补测项**。8 轮全部行为门绿（exit 0 / walkDone / logErrors=0 / 无孤儿）：优化在高
争抢下亦无任何行为异常；机制量化的权威证据为 JMH 6.0× 与等价自检（上节），maintain
为 chunkSource 内子相位，其风暴成本与窗口均值的关系已在归因节闭合。

## 判例

- **无序 ReferenceList 的 contains 是隐匿 O(n)**：moonrise 高性能容器在"成员探测"上退化
  为线性扫——跨 tracker 共享的探测场景按内容失效键（updateCount）建集合是精确等价的
  标准解法。
- **O(N²) 对数不可削减时要查"每事件扇出"**：本例真浪费不是对数而是每 purge 风暴的
  trackers×seenBy×O(list) 扇出——归因粒度要到"每次列表变化触发多少次全扫"。
