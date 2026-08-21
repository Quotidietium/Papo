# 批次 76 JMH 微基准报告（2026-08-22）— 实体同步域三项（0247）

主题：**多玩家网络稳定（实体同步域）**——稳态出站流量大头的 CPU/分配面。survey 子代理系统扫描
ServerEntity/TrackedEntity/ChunkMap 追踪路径后落地三项等价改造（survey 明确否定：实体包均
<256B 压缩阈值、memo 不适用稳态实体同步；空闲实体无每 tick 分配；broadcast 迭代器换 forEach 无净收益）。

1. **无观众实体跳过包构造（主项）**：刷怪塔/农场等"实体 ticking 但无观众"场景（追踪范围外的
   mob/item farm——常见配置），`sendChanges` 仍每 updateInterval 构造 move/PositionSync/motion/
   RotateHead/minecart 包后**对空 seenBy 广播丢弃**。`papoHasViewers = !trackedPlayers.isEmpty()`
  （trackedPlayers 即 TrackedEntity.seenBy 同引用，实证）守卫 11 个构造站点，**只跳 new**——
   base/lastSent 系列/teleportDelay/flag3/flag4/packDirty 清 dirty/attributesToSync.clear/
   injectScaledMaxHealth 全部逐行保留；AndSelf 站点（metadata/attributes/hurtMarked motion）以
   `papoHasViewers || entity instanceof ServerPlayer` 守卫（玩家自收保留）。仓库内先例：同方法
   ItemFrame 路径本就用 `trackedPlayers.isEmpty()` 门控。
2. **seenBy 双重探测消除**：0204 引入的 contains 之后 add 内部再探测——稳态每 (实体,玩家) 对每
   tick 两次身份哈希探测；`!papoAlreadyTracked && add(...)` 短路（contains 与 add 之间无
   seenBy 写入，add 对已存在元素是可证 no-op）。
3. **矿车路径 Vec3 内联**：`positionCodec.delta(vec3).lengthSqr()` 每 gate 一次 Vec3 分配 →
   标量内联（主路径 Paper 同款先例；`a-b` 与 subtract 的 `a+(-b)` 位级一致；7.6293945E-6F 字面量保持）。

环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 基准（EntitySyncNoViewerBench）

| 项 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| A 无观众包构造（每 updateInterval/实体） | 76.295 ± 1.840 | **12.215 ± 0.465** | **6.2×（CI 分离）** |
| B 稳态双探测（每对每 tick） | 3.563 ± 0.207 | **1.916 ± 0.114** | **1.86×（CI 分离）** |
| C 矿车 Vec3 | 1.070 ± 0.090 | 1.237 ± 0.092 | CI 重叠（见下） |

- A 场景外推：500 只追踪范围外移动实体 ≈ 每 tick（updateInterval=3 折算）~64µs 主线程纯垃圾
  构造消除；另有每实体 60-tick 强制 PositionSync 与弹射物 motion 包同归零。
- B 场景外推：500 实体 × 20 玩家 = 10k pair → ~16µs/tick。
- **C 诚实定位**：复刻内 CI 重叠且均值略负——EA 已把被立即消费的 Vec3 标量替换（before 的分配
  被消除），0140/0157/0180 判例的浅栈伪影；真实深调用栈（sendChanges→handleMinecartPosRot）分配
  真实发生，机制保留 + 位级自检（10 万随机点 + 阈值边界）ALL OK。
- 自检 main ALL OK：A 无观众两路径发送计数均 0/有观众包字段一致；B 探测语义与终态逐集一致
  （已存在元素与新鲜元素两形态）；C 标量内联与 Vec3 链位级一致。

## survey 否定结论（留档勿重复勘察）

- 实体同步稳态包全部 <256B 压缩阈值（Pos~10B/Rot~7B/PosRot~13B/Motion~17B/PositionSync~44B/
  RotateHead~6B/Passengers~6-10B）——**PapoSharedWireMemo 压缩路径不适用**；equipment/metadata/
  attributes 可超 256B 但为事件驱动且配对路径 per-viewer 构造，memo 边际价值不落地。
- 空闲实体 sendChanges 无每 tick List/迭代器分配（passengers 为 ImmutableList 字段 equals；
  packDirty 清洁时返回 null 不分配；attributes 集合为存储字段）。
- broadcast 增强 for 的 SetIterator 换 forEach 只是把迭代器分配换成捕获 lambda 分配，无净收益。
- per-pair updatePlayer 各辅助调用（catchOp/getPlayerViewDistance/isChunkTracked/paperConfig/
  chunkPosition/broadcastToPlayer）全部为字段读/静态钩子，无候选。
- AbstractHurtingProjectile 每 tick 3 对象 bundle——去 bundle 改 wire 字节（delimiter），否决。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0247）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 76 记录）。
