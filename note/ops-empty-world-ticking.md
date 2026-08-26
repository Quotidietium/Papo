# 运营文档：空维度 tick 固定成本与 `disable-world-ticking-when-empty` 旋钮

来源：批次 90 稳态 survey（[报告](report/perf/2026-08-27-tick-survey-batch90.md)）发现
worlds 相位占主线程 37-39%，其中 tickPending+misc 22-23% 为**空维度固定成本**（无玩家
也照付的 per-tick 脚手架）。本文档给出上游既有旋钮的**源码级精确语义**与运营建议。

## 旋钮是什么

- **配置路径**：`config/paper-world-defaults.yml`（或 per-world `paper-worlds/<世界>.yml`）
  → `unsupported-settings.disable-world-ticking-when-empty`
- **默认值**：`false`（`WorldConfiguration.java:501`，Papo 不改默认）
- **机制**（`ServerLevel.java:820-830`）：开启后，仅当 `chunkSource.hasActiveTickets()`
  为真才重置 `emptyTime`；无活跃票据的世界 `emptyTime` 累计，**达到 300 tick（20 TPS 下
  15 秒）后跳过实体段**。

## 源码级语义：停什么、不停什么（ServerLevel.tick() 逐段核对）

**门内（emptyTime ≥ 300 后跳过）**：
- 末地龙战斗逻辑（`dragonFight.tick()`）
- 实体激活与实体 tick（`ActivationRange` / `entityTickList`，含 `checkDespawn`）
- **区块实体 tick（`tickBlockEntities()`——熔炉、漏斗等）**

**门外（无论旋钮开关都照常每 tick 执行）**：
- world border、天气循环、睡觉跳时、天空亮度、游戏时间推进（`tickTime`）
- **计划方块/流体 tick（`blockTicks` / `fluidTicks`）**
- 突袭（`raids.tick`）
- **区块源 tick（`chunkSource.tick`——区块系统调度脚手架）**
- blockEvents 队列

**主世界通常不受影响**：出生点加载带活跃票据 → `hasActiveTickets()` 恒真 → 旋钮实际
只作用于**无玩家的附属维度**（下界/末地/Multiverse 等常驻空维度）。

## 对批次 90 数字的修正（重要）

批次 90 报告将 22-23% tickPending+misc 归因于"空维度固定成本（已有配置旋钮）"。
逐段核对后修正：**该 22-23% 的大头（计划 tick/突袭/chunkSource 脚手架）在门外，
旋钮管不到**；旋钮能消除的只有 entities/blockEntities 段（空维度实体本就稀少，收益
有限）。剩余固定成本属上游 per-world per-tick 结构性支出，消除需要上游重构（超出
Papo 等价性红线）。运营预期请以"空维度实体/区块实体冻结"为主收益，不要期待 worlds
相位 22-23% 全额消失。

## 运营建议

**适合开启**：
- 服务器常驻大量无玩家维度（多世界/副本/资源世界），且接受"空世界里的实体与区块实体
  在玩家离开 15 秒后冻结"（物品不老化、不自然 despawn、熔炉/漏斗暂停——玩家回来后恢复）。

**不适合开启**：
- 依赖空世界实体老化的机制：物品自然消失、农场产物堆积节奏、定时清理插件的假设。
- 空维度需要区块实体持续工作（远程漏斗系统、无人熔炉组）。
- 追求与 vanilla 逐 tick 语义一致的服。

**档位含义**：位于上游 `unsupported-settings` 档——Paper 官方不承诺该选项的问题支持，
CraftBukkit 注释明示其历史动因是"no-player 服务器的实体清理等问题"（`ServerLevel.java:820`）。

**开启后验证**：`-Dpapo.tickProfile=true`（批次 90 基建，每 400 tick 打印各相位占比）
对比开/关时 `level.entities` 与 worlds 相位占比即可量化收益（参考
[批次 90 报告](report/perf/2026-08-27-tick-survey-batch90.md) 的读数方法）。

## 红线备注

此为**运营 opt-in 行为变更**，非 Papo 默认优化：开启即偏离 vanilla 空世界 tick 语义。
Papo 侧不改默认值、不做代码介入——默认行为等价红线保持。
