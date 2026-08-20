# 批次 64 JMH 微基准报告（2026-08-20）— 加入链路静态包缓存 + 双重读盘去重（0226/0227，编号以补丁文件为准）

加入链路（登录→配置→出生就绪）survey 定案三候选全落地。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。

## 1. 0226 — join 静态配置包（tags + 注册表）按 reload 纪元缓存

**热点**：每个玩家 join 的配置阶段在主线程重建：
- `ClientboundUpdateTagsPacket`：625 tag / 4377 条目（vanilla 1.21.11 实测统计）的 id 查找 + 集合构建；
- 24 个 `ClientboundRegistryDataPacket`：371 条目；**known-packs 不匹配的客户端（典型 ViaVersion 转译）每条目全量 NBT 编码（数毫秒 + ~100KB wire）**。

内容在两次资源重载之间逐字节恒定（失效收口唯一：`MinecraftServer.reloadResources → loadTagsForExistingRegistries → PlayerList.reloadTagData`；worldgen 层仅随重启变化）。

**改法**：`PapoJoinPacketCache`（静态缓存：tags 包 + 两分支注册表包列表）；`reloadTagData()` 以新广播实例换缓存并清注册表缓存（免费兜底）；`SynchronizeRegistriesTask.sendRegistries` 按分支命中/构建。包对象跨玩家共享为 vanilla 既有行为（reloadTagData broadcastAll 同实例、reloadRecipes 同实例）；两包均未覆写 Paper per-send 钩子（默认 no-op），编码只读。

| 方法 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| before_rebuildAll vs after_cached | 210,270.209 ± 2,159.117 | 0.960 ± 0.003 | **~0.21ms/join 主线程消除**（模型量级；survey 实测构建含 4377 次注册表 id 查找，估 0.3-1ms；Via 分支另加数毫秒 NBT 编码 + ~100KB） |

- 字节等价：缓存实例与逐次构建产出的包内容恒等（同一构建函数、同一不可变输入）；时点不变（仍在 handleSelectKnownPacks 主线程发送）。
- 自检 ALL OK：未命中构建一次 → 命中复用同实例 → reload 失效 → 重建。

## 2. 0227 — PrepareSpawnTask 双重 loadPlayerData 去重

**热点**：`start()`（:55-58）与 `spawn()`（:283-297）在同一 join 内各调一次 `loadPlayerData`——磁盘读 + gzip + 全量 NBT 解析 + **datafix 全树转换**，主线程 ×2（上游 vanilla 同样读两次）。两处消费均为只读（start 读 bukkit world 引用/保存位置；spawn 喂 serverPlayer.load）。

**改法**：task 实例字段缓存首次结果（含 empty 情形——新玩家 join 中途不会凭空出现 .dat 文件）；spawn 复用。

| 方法 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| before_doubleLoad vs after_singleLoad（8KB gzip 模型） | 6,060.598 ± 186.889 | 3,007.712 ± 38.306 | **2.01×**（CI 不重叠；真实场景含磁盘 IO + datafix，节省更大——背包重的玩家（潜影盒）为毫秒级） |

- **非等价残余（已记录）**：join 中途 .dat 被外部进程改写时行为不同（病态场景，非默认行为）。

## 3. 记录项（红线外，上游 Paper/CraftBukkit 行为，去重即改变包流）

- `sendLevelInfo` 双发（Paper 提前触发块 :229 + vanilla 保留处 :292）→ 客户端收 2 份 border/time/spawn/天气包。
- `initInventoryMenu` 双发（:233/:304，每次 46 槽全量同步 + item.copy）——且两次内容可能因插件 join-kit 不同，不可去重。
- 登录 VERIFYING 的 tick 门（0-50ms 固定延时）与 Paper join 缓冲（maxJoinsPerTick=5）——vanilla/Paper 语义。
- 配置任务串行 + known-packs RTT 前置阻塞区块预载——重叠可省 RTT 但提前 PlayerSpawnLocationEvent 时点（插件可见），中风险结构项，不做。

## 4. 排除项（已核实）

- recipe book（per-player 内容）、UpdateRecipes 包（RecipeManager 预建不变字段，上游已对）、命令树（Paper 已异步池 + per-player 过滤）、出生点搜索（Moonrise 异步）、known-packs/features/brand（微秒级）。
- 编码层跨玩家字节缓存：与 0217 的 headroom 原地改写机制冲突，且对象级缓存（0226/0227）已拿走绝大部分收益。

## 验证链

compileJava BUILD SUCCESSFUL → 自检 ALL OK → JMH 本报告 → rebuildPatches（0226/0227）→ applyPatches → 全量 test（见 optimizations.md 批次 64 记录）。
