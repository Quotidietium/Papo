# 批次 100 报告（2026-08-27）— aiStep touch 扫描 scratch 化：listenerTick 剩余面的分配消除（多核调度系列⑯，0.61.0→0.62.0）

主题：批次 99 后 conn.listenerTick 剩余（160bot 5461us）中定位到的第二个每玩家每 tick
无界分配点：`Player.aiStep` 的 `getEntities(this, aabb.inflate(1,0.5,1))`——**每玩家每 tick
一个新 ArrayList**（160 聚堆 ≈ 4800 次/tick 分配）。消费者是逐实体 `entity.playerTouch(this)`
多态派发（ItemEntity 拾取等）+ XP 球收集——**不可有界化**（playerTouch 为公开多态点，
Mixin/插件可运行时注入覆写，跳过任何实体即行为变更，精确性红线）。可安全做的上限是
分配消除：per-player scratch 复用（0255 补丁），谓词逐字 `EntitySelector.NO_SPECTATORS`
（2 参重载的真实谓词），扫描语义/顺序/快照语义零变化。

## 等价性

- 谓词与 vanilla 2 参 `getEntities(entity, area)` 逐字一致（NO_SPECTATORS）；
- fill 序/快照语义同构（vanilla 列表同样在 touch 回调后继续迭代快照）；
- 重入分析：aiStep 单 tick 不可重入同玩家，scratch 消费完全内联；
- 沿用批次 29 判例：纯分配消除候选免微基准前置。

## 实测（如实披露：中性）

160 bot A/B（0.61.0→0.62.0，同 harness 同种子）：conn.listenerTick avg 5461→5776us
（min 4460→4172）——**统计持平**。分配消除的预期量级（数百 us/tick 量级）低于共享机
争抢噪声底（窗口方差 ±500us+）；gcMs 窗口分布不变（5-24 → 6-33）。机制收益真实
（每 tick ~4800 ArrayList 分配归零）但本机测量分辨率不足以单独显现；无任何回归信号。

## 验证

- 全量 test ✓；0255 补丁 rebuild 干净；0.62.0 jar 构建成功；四态冒烟 10/10 全绿
  （touch 是拾取等游戏可见行为的上游，门必过）；
- 160 A/B：exit 0、logErrors=0、稳态无滞后。

## 运行判例（复发+对策升级）

- **端口孤儿复发（批次84 形态）**：外部击杀 harness 后服务器子进程存活占住 25594，
  后续 attempt 全部 "server_full"×160 拒绝（bot 连到孤儿），表现为稳定快速失败——
  与击杀的"无输出死亡"症状不同，诊断时先 netstat 查端口再查进程。
- **重试环新增前置清理**：每 attempt 前 `netstat -ano | grep :25594.*LISTENING` →
  杀该 PID（只杀端口占用者，避免误杀共享机邻居）。加入后一次通过。

## 后续前沿

listenerTick 剩余（160bot ~5.5ms）的主体为玩家实体 tick 链的常量成本（~34us/人），
聚堆密度超线性已被批次 99 消除（push 135us 全量）。下一候选轴：tracker.maintain
（6.7×@4×bot 温和超线性，moonrise NearbyPlayers 侧）与 level.entities 段。
