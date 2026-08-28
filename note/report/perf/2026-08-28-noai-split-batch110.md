# 批次110：实体链 AI/非AI 分量分解——NoAI 变体 A/B（容量墙 50/50 分裂确立）

- 日期：2026-08-28 深夜（23:54-00:04）
- 版本：Papo-1.21.11-**0.67.0**（零服务器代码；纯 harness 变体）
- Harness：`EntityScaleBench` NoAI 变体（第 4 参 `noai`：召唤 NBT `{NoAI:1b}`）+
  在场门标签化（`tag=papoCow` 只数召唤群——本批起在场门全精度）
- 运行：N=2000、10 站立 bot、窗口 180s，AI 组与 NoAI 组先后同 harness
- 日志：`benchmark/results/batch110-N2000-ai.log`、`batch110-N2000-noai.log`

## 结论（一句话）

容量墙（N=2000 聚堆牛）的实体链 **精确对半分裂**：AI 束（goal/nav/look + 诱导物理 +
诱导追踪）11.64ms/tick，非 AI 地板（baseTick/静止物理/推挤/EAR/追踪读）11.41ms/tick；
worlds 总差 13.88ms/tick——实体链多核/优化攻击面从此有了完整的地板-束结构。

## 在场门（标签化首跑）

- AI 组：A=**1995** B=1995（召唤 2000，窗前损失 5 头，窗口内零死亡；logErrors=0）
- NoAI 组：A=**2000 精确**（窗口内被外部消灭前零损失，见披露）
- **诊断**：AI 组 5 头损失为 AI 行为性死亡（游走挤压致死，vanilla 语义；批次106 起
  "两杀/杂散"模式在此定性：此前全局计数 杂散−死亡 恰好抵消成 A=2000）；NoAI 牛
  不走动 → 零死亡 → 排除推动机制/计数竞态假说。两组队列窗口内均稳定，数据有效。

## 稳态分解（尾 4 窗均值，us/tick）

| 相位 | AI | NoAI | Δ（AI 束） |
|---|---|---|---|
| worlds | 28398 | 14518 | 13880 |
| **level.entities** | **23049** | **11413** | **11636（50.5%）** |
| 每牛 level.entities | 11.55 | 5.72 | 5.83 |
| level.tickPending+misc | 5336 | 3090 | 2246（含 epoch 噪声） |
| level.chunkSource | 5323 | 3076 | 2247（含 tracker 嵌套） |
| chunkMap.tracker.sendChanges | 2455 | 541 | 1914 |
| chunkMap.tracker.fanout | 1578 | 54 | 1524 |
| player.pushEntities | 3432 | 1824 | 1608 |
| fanout.sends（计数/tick） | 18954 | 686 | 18268 |
| 每 send fanout 成本 | 83ns | 78ns | 持平（批次108 成果在两态均成立） |

嵌套注意：tracker 相位在 chunkSource 内（批次98/102 已建模）；"每牛"按 1995/2000 头计。

## 读数

1. **非 AI 地板 5.72us/牛**（2000 头静止牛仍耗 11.4ms/tick）：baseTick + 静止物理
   （摩擦/重力/无位移碰撞）+ 牛-牛与被玩家推挤扫描 + EAR 检查 + 追踪读。这是
   任何"让 AI 更便宜"路线都碰不到的下界——密度超线性面（批次98 发现）住在这里。
2. **AI 束 5.83us/牛**：serverAiStep 计算 + 导航诱导的实际位移（collide/方块碰撞/
   trackDeltas）+ 诱导包构建（sendChanges +1.9ms）+ 诱导扇出（+1.5ms）+ 诱导
   push 扫描（+1.6ms）。fanout 每 send 成本两态持平——批次108 的移交摊销与
   牛是否走动无关（机制级成本）。
3. **NoAI 侧 fanout.sends 686/tick 非零**：静止牛仍有 velocity（重力微振荡）与
   headRot 残余——与批次107 归因一致（trackDeltas+重力振荡=每牛每 tick 速度包）。

## 环境披露（NoAI 组窗口末尾进程被外部消灭）

- 现象：probe B 时 stdin 已断（B=-1 按判例返回）、10 bot 全断连、`exited=false`。
- 证据：`logs/latest.log` 止于 00:03:50 完整 400-tick 窗（无 ERROR、无 stop 消息、
  temp 目录无 hs_err）→ 进程被外部直接消灭，非崩溃非停机——**共享机 java 清扫
  判例**（同晚 22:15 两起 JMH fork mmap 失败、批次108 同窗页面文件耗尽同源）。
- 数据有效性：尾 4 窗全部落在 180s 测量窗内（00:01:13-00:04:13），死亡发生在
  窗口结束与 probe B 之间；AI 组不受影响。**分解成立**。

## 交接（批次111，已实施）

AI 束内部再分一刀：`ent.serverAiStep` 子相位探针（0261，0.67.0→0.68.0）——
`Mob.serverAiStep`（sensing/goals/nav/controls）与诱导物理（pushEntities 在 aiStep
段、serverAiStep 之外）切开。批次110 的 11.64ms AI 束 = serverAiStep 计算 +
诱导物理+诱导追踪；两刀合璧后实体链四分量全谱（AI 计算/诱导物理/静止地板/追踪）
就位，批次112 的结构优化（多核下放候选 vs 串行减面）据此定靶。
