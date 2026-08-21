# 批次 77 JMH 微基准报告（2026-08-22）— 同序列 pairing 包共享（0248）

主题：**多玩家网络稳定（实体配对域）**——多观众冗余链在 pairing 侧的最后一块。新实体注册
（`ChunkMap.addEntity` → `TrackedEntity.updatePlayers(this.level.players())`）在**单线程连续循环**中
对多个玩家 `addPairing`：`sendPairingData` 的全部内容仅依赖实体状态（spawn 包 / 非默认 data 快照 /
attributes / equipment 逐槽 copy / passengers×2 / leash），窗口内实体状态不变（方法内循环、无实体
tick 交错）⇒ 每观众重复构造**逐字节相同**的 2-6 个包。本批（0248）：sweep 由
`papoBeginPairingShare/papoEndPairingShare`（try/finally）括起，窗口内首观众构造并缓存 List，
后续观众复用同一实例列表（bundle 包装与 startSeenByPlayer 保留 per-viewer）。

环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性（逐项）

- **窗口内状态不变**：`updatePlayers` 为方法内连续循环，`updatePlayer → addPairing →
  sendPairingData（读实体状态）→ connection.send（入队）` 之间无任何实体 tick / 状态写回调
  （单线程；send 仅入队或同步编码——编码读包对象快照不回读实体；attributes 集合 live 引用是
  vanilla 每观众独立包本就共享的同一集合，不新增并发面）。
- **per-player 分支不可达**：`sendPairingData` 的 `entity.getId()==player.getId()` scaled-health
  分支在 pairing 路径死代码——`updatePlayer` 首行 `player != entity` 守卫（玩家永不给自己
  pairing）；唯一其他调用方 `Entity.resendPossiblyDesyncedEntityData` 不在窗口内。
- **幂等副作用**：`updateDataBeforeSync`（布尔字段检查，批次 76 survey 实证）与
  `detectEquipmentUpdates`（版本比较，二次调用 no-op）每 sweep 一次 vs 每观众一次不可观察。
- **窗口纪律**：窗口外（玩家走动进入范围、传送等一切其他 addPairing 路径）`papoPairingShareCache`
  为 null → 独立构造照旧；end 时（含异常路径 finally）清缓存；深度计数防御性钳制。
- 跨玩家共享包实例为 vanilla 既有行为（broadcastAll/FeatureHooks/0226/0241-0246 先例链）。

## 2. 基准（PairingShareBench，模型=五段 pairing 构造 × 4 观众 sweep）

| 方法 | ns/op | 说明 |
|---|---|---|
| beforePerViewer | 692.413 ± 11.853 | 每 sweep：4 观众 × 完整五段构造 |
| afterShare | **0.564 ± 0.184** | 首 sweep 构造后复用（模型复用为纯引用读） |

- 模型内每观众构造 ~173ns；真实 NMS pairing 构造更重（getNonDefaultValues 全量扫描 20-40 项 +
  equipment 4 槽 copy + 5 个包对象 + 集合），实测口径 **每观众省 (K-1)/K 的构造**（K=在范围内观众数）。
- 场景：满负荷自然刷怪 ~30 新实体/tick × 平均 2-4 在范围观众 → 每 tick 数十次重复构造消除
  （跑图突发/区块实体恢复批量注册时 K 更大）。
- 自检 main ALL OK：4 观众两路径包序列逐项内容一致；窗口外构造独立（缓存清空断言）。
- **诚实定位**：模型 afterShare 的近零读数是复用路径的纯引用语义（无构造无编码），倍率
  1227× 为模型夸张上界；真实收益=(K-1)×每观众 pairing 构造成本（µs 级/实体/sweep）。

## 3. 留档

- 玩家移动进入范围场景（TrackedEntity 各玩家扫描段）不共享：sweep 段之间可能夹实体 tick
  （tick 内顺序无局部可证性），保守排除——只有方法内连续循环可局部证明。
- `resendPossiblyDesyncedEntityData`（插件/NMS 重发）单观众，无冗余。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0248）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 77 记录）。
