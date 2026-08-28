# 批次111：ent.serverAiStep 分解——AI 计算仅 16%，实体轴归因闭合

- 日期：2026-08-29（00:16-00:21）
- 版本：Papo-1.21.11-**0.68.0**（0261 探针，`-Dpapo.tickProfile=1` 门控，零行为变化）
- 运行：N=2000、10 站立 bot、窗口 180s；**presence gate 全绿首达**
  （A=B=2000 精确、exit=0、logErrors=0——标签化在场门根治验证）
- 日志：`benchmark/results/batch111-N2000-ai.log`

## 结论（一句话）

容量墙实体链中 **Mob AI 计算（serverAiStep）仅 3.03ms/tick（1.51us/牛，16%）**；
结合批次110，AI 束（~9.4ms）里 ~6.4ms 是**诱导物理/推挤**而非 AI 计算——
"AI 计算下放多核"路线否决（无可下放的大块纯计算），实体轴归因闭合。

## 稳态尾 4 窗（本 epoch，较批次110 轻载：worlds 23.1 vs 28.4ms）

| 相位 | us/tick | 每牛 |
|---|---|---|
| worlds | 23059.4 | — |
| level.entities | 18688.6 | 9.34us |
| **ent.serverAiStep** | **3033.0** | **1.51us** |
| player.pushEntities（=每牛有界 push 扫描，名沿用） | 2927.2 | 1.46us |

n≈751k/窗 ≈ 1879 调用/tick（≈每牛每 tick 一次 serverAiStep；goal 内部按奇偶半频）。

## 实体轴全谱（N=2000 容量墙，跨批次综合，epoch 归一后近似）

```
worlds ≈ level.entities + level.tickPending+misc
       ≈ entities(81%) + chunkSource(19%)
entities ≈ AI 计算 3.0 (16%)
         + 诱导物理/推挤 ~6.4 (34%)   ← AI 束的 68%（批次110 AI−NoAI 差 9.4 的余量）
         + 静止地板 ~9.3 (50%)        ← baseTick/重力摩擦/EAR/checkDespawn
chunkSource ≈ tracker(sendChanges 2455 + fanout 1578 + maintain 1193) + ~100 其他
tickPending+misc − chunkSource ≈ 13us/tick（相位几乎纯容器，无隐藏面）
```

（跨 epoch 声明：比例结构以同 run 内数据为准，绝对值受共享机时段影响；
批次110 AI 束占比 50.5% 与本 run entities 比例外推一致。）

## 否决与关闭记录

1. **AI 计算下放否决**：3.0ms 纯计算中 sensing/goal/nav/look 交错实体互读与
   事件（EntityTargetEvent 等 Bukkit 契约），切片下放既无规模也无等价性。
2. **O(N×P) 玩家面排除**：Moonrise 已把玩家纳入 section 切片
   （`PaperHooks.addToGetEntities` 只补龙部件，常态早退）——每牛 push 扫描
   的 1.46us 是有界扫描本体（批次99 残差），无玩家表遍历项。
3. **静止地板/诱导物理 = vanilla 语义载荷**（批次103 同判）：重力振荡、
   摩擦、方块碰撞、fluid/portal/fire 检查——等价红线内不可跳过。
4. **实体轴战役闭合**：105 在场 → 106 规模阶梯 → 107 扇出归因 → 108 发送
   批量化 → 109 soak 稳定性 → 110 AI/地板分裂 → 111 计算分裂。等价保持面
   上的可优化面全部收尽（99/100/101/108 已收割，残差均为语义本体）。

## 工具链判例（本批事故与恢复，已写入 build.md）

`fixupSourcePatches` **不可用于新增 Papo feature**：它把改动折进 "paper File
Patches" 基座且 `file` 标签不跟随 → rebuildPatches 从断裂区间导出 283 个垃圾
补丁。恢复：外层 patches 目录 restore + 内部仓库 reset 到 fixup 前 main 顶端 +
手动 feature 提交（0259/0260 的实际工作流）。build.md 工作流文档已修正。

## 下一前沿（R3 继续方向）

- **红石/流体轴**（R2/R3 记录的开放前沿）：需新负载生成 harness（红石时钟/
  活塞阵列/流水网格），仿 105/106 的实体轴勘察-归因-优化三部曲。
- 备选：静止实体 fast path（baseTick/物理跳过）——等价性论证面深（全部
  副作用枚举），留待独立批次评估。
