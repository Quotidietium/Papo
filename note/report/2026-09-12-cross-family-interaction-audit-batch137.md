# 跨家族交互面与配对共享补审对抗审计（批次 137，2026-09-12）

> 范围：五个已审家族（132 红石 / 133 出站登录 / 134 入站解码容器 / 135 早期分配 /
> 136 缺口闭合）之外的**全新面**——
> ①**跨家族交互面**：被 ≥2 个审计家族的补丁共触的全部 33 个文件（脚本全量
> 枚举，无一遗漏），重点核对**同方法叠栈**（逐族审计结构性漏掉的低频组合效应）；
> ②**批 133 宣称范围 vs 显式段落核对**：0241-0252 头声明中 0247/0248 两补丁
> 无任何审计段落——本轮**首次真审**；
> ③**直提交清点**：src/main 树 19 个 Papo 标记文件逐一映射审计范围，批 5
> CraftEntity 枚举缓存（从未入任何报告范围）本轮补审；
> ④**功能完整性核验**：optimizations.md 表述的运行时可见特性（netstat 命令/
> 压缩级别旋钮/物品上限旋钮/tickProfile 门控）对照实现逐项确认。
> 对齐流目标：长期高负载多用户稳定性、插件生态下的行为兼容（API/行为兼容
> 红线）、不可信输入面零新增、内存界。方法：逐文件对已应用源码树对抗读 +
> 跨补丁不变量交叉核对 + 事件可达性构造证明。
> 已接受缺陷（2026-09-03 用户决断）0267/0268/0269 只核对未复发，不重报。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | 0248 同 sweep 配对包共享（首次真审） | **1 处真实行为偏差（B1，本轮修复）**：sweep 内两个插件事件回调点使"窗口内实体状态不变"前提可被合法打破，拴绳类变更无自愈路径 | §1 |
| 2 | 0247 无观众跳过包构造（首次真审） | **闭合**（12 个门控点全核：所有 ServerEntity 内部状态更新在门外推进；papoHasViewers 快照安全性经 updateDataBeforeSync 仅两覆写者且均为纯数据更新证明） | §2 |
| 3 | ServerEntity 配对四补丁栈（0206×0219×0247×0248） | **闭合**（0206 fresh 快照在 0248 窗口内退化为同状态读；0219 预尺寸列表即共享缓存载体；nesting 深度钳制 + finally 清缓存） | §3 |
| 4 | 网络编码栈（0207×0216/0218/0223-0225×0242） | **闭合**（send 快路径重排两臂纯谓词；consume-once CAS 与带宽计数器接线互不依赖；压缩换级 → PapoSharedWireMemo epoch stamp 在位） | §4 |
| 5 | 容器/物品栈（SGPLI/AbstractContainerMenu/ItemStack/RegistryAccess） | **闭合**（同方法跨族点逐一核对：handleUseItemOn 标量检查×criteria 门无共享状态；handleContainerClick 内 0154 为单族；validatedStreamCodec 缓存上下文经 CountingOps 无状态单例证明线程安全） | §5 |
| 6 | 红石叠栈（0071/0077×0232×0253+）与 Level/ServerLevel/LevelChunk | **闭合**（0232 事件门在 R4 合并扫除后的语义位置保持（oldPower≠i 点）；LevelChunk.setBlockState 双钩子（0262 脏标记→0241 版本 bump）均为派发前簿记互不依赖；Level/ServerLevel 各标记异方法） | §6 |
| 7 | 区块发送族（ChunkMap/ChunkHolder/PlayerChunkSender/ClientboundLevelChunkPacketData） | **闭合**（moonrise$tick 三补丁叠栈：hoist 只读不变量 + 惰性移除收集避免 CME；collectChunksToSend 三补丁同方法叠栈：k-nearest 的 floor≥1 经 sendNextChunks 守卫链（max≥1→quota≥1.0 门）排除空数组越界；0145 广播循环与 0242 共享包异方法） | §7 |
| 8 | 实体域（LivingEntity/Mob/ExperienceOrb/ItemEntity/NaturalSpawner/PathNavigation/Player/GameEventDispatcher/RegionFileVersion/MinecraftServer/CompoundTag） | **闭合**（全部异方法分层；0108 事件门×0236 merge 扫描 scratch 分层且 merge() 不触迭代中列表；GameEventDispatcher.post() 三补丁同方法叠栈：visitor 每调用新实例无共享状态、int 坐标 floor 与 BlockPos.containing 同式；A4 修复在位复认） | §8 |
| 9 | 直提交清点 | 批 5 CraftEntity.getPose/setPose 枚举数组缓存补审**闭合**（ordinal 索引保持、null 守卫保留）；其余 18 个 src/main 标记文件全部映射到 133/134/136 已审范围（PapoWireDirtyTracking=132、PapoOrderedFileWrites/PapoParallelism/SpigotConfig netty sizing=133、NetstatCommand/PaperCommand=134 §10、指纹族/CraftPlayer/CraftDefaultPermissions=136、ItemObfuscationSession=135 §9） | §9 |
| 10 | 功能完整性（表述 vs 实现） | **全部一致**：/paper netstat 注册（PaperCommand:55）+计数器穿线+1s 快照；misc.compressionLevel（默认 6，IllegalArgumentException 时 clamp 回退）；entities.spawning.itemEntityLimitPerChunk（WorldConfiguration:291，默认 -1 禁用）；PapoTickProfile opt-in（"true"/"1" 双形态，禁用时零成本静态门，400 tick 窗口，COUNT_KEYS 常驻键集有界含零行打印） | §10 |
| 11 | 不可信输入面 + 内存界 + 已接受缺陷 | 本轮触面（0247/0248/collectChunksToSend/枚举缓存）零客户端可控数据；papoPairingShareCache 瞬态（sweep 终置 null，修复后仅在回调封闭窗口内存在）；无新增无界结构；0267/0268/0269 决断维持未复发 | §11 |
| 12 | 构建验证 | check_patch_counts ALL OK + applyPatches 全量重放 EXIT=0（重生成 ChunkMap/ServerEntity 与手改树逐字节一致）+ compileJava --rerun-tasks BUILD SUCCESSFUL | §12 |

## §1 B1：0248 配对共享的插件回调窗口（本轮唯一代码修复）

**机制**：0248 在 `TrackedEntity.updatePlayers(List)` sweep 内用
`papoBeginPairingShare/papoEndPairingShare` 括起连续 addPairing，后续观众复用首位
观众构造的配对包列表。其"窗口内实体状态不变"前提的原始论证是"单线程循环、无
实体 tick 交错"——但 sweep 内存在两个**插件事件回调点**：

1. `PlayerTrackEntityEvent`（ChunkMap updatePlayer 内，addPairing **之前**，
   零监听器短路门控——有监听器时每个新配对观众都触发一次）；
2. `PlayerUntrackEntityEvent`（removePlayer → removePairing → stopSeenByPlayer，
   同 sweep 内玩家离开范围时触发）。

插件处理器在这些事件里**合法地**可变更配对相关实体状态（拴绳 setLeashHolder、
元数据、装备、乘客）。vanilla 每观众新鲜构建，后位观众能看到变更；0248 的缓存
回放则给出变更前字节。

**危害分级**：元数据/装备/乘客类变更有 ≤1 tick 自愈（SynchedEntityData.set 标脏
→ sendDirtyEntityData 增量；detectEquipmentUpdates 每 tick；乘客差分每 tick）；
**拴绳类变更无周期自愈广播**（ClientboundSetEntityLinkPacket 仅在拴绳状态变化时
发给当时已在 seenBy 的观众——mid-sweep 变更时刻后位观众尚未入集），陈旧显示可
持续到下一次拴绳变化或重新配对。属插件可达的持久客户端可见偏差，触碰
"API/行为兼容"红线。

**修复**（批 137）：共享仅在 sweep **插件回调封闭**时启用——
`updatePlayers` 顶部检查 PlayerTrackEntityEvent 与 PlayerUntrackEntityEvent 两个
HandlerList 均**零监听器**才 `papoBeginPairingShare()`。无此类插件时（绝大多数
服务器）优化保持；任一事件有监听器即恢复 vanilla 逐观众新鲜构建，偏差归零。
sweep 内其余代码逐点核对无回调（updateDataBeforeSync 两覆写者纯数据更新、
onPlayerAdd 仅置 forceStateResync、WitherBoss.startSeenByPlayer 仅 boss 条 UI、
debugSynchronizers 纯簿记、connection.send 异步入队无回调），零监听器下
"字节相同"论证成为**绝对**成立。ServerEntity 侧注释同步改写（原"no callback
into entity ticking"表述不完整）。

**为什么 133/136 都没抓到**：133 的报告头宣称 0241-0252 范围但结论表与章节
均无 0247/0248 段落（同款"宣称范围 vs 显式段落"缺口，136 勘误时只核对了
132-135 四家、未复核 133 自身头声明与段落的映射）；136 补审的是
0205-0212/0230-0240。本轮以脚本逐补丁×逐报告显式清单映射后暴露。

## §2 0247 无观众跳过包构造（首次真审，闭合）

- **门控点全数核对**（sendChanges 12 处 + handleMinecartPosRot + sendDirtyEntityData）：
  所有 ServerEntity 内部状态推进在门外——lastSentYRot/XRot、lastSentMovement、
  teleportDelay=0、wasOnGround、wasRiding、positionCodec.setBase（flag3/flag4 置位
  与 packet 构造解耦）、behavior.lerpSteps.clear()、hurtMarked=false、
  packDirty()（清脏标志）、attributesToSync.clear()、injectScaledMaxHealth（幂等
  快照内替换）。`packet` 变量在无观众时保持 null，`if (packet != null)` 发送门
  一致；Rot/Pos/PosRot 分支的 lastSent* 更新均在门外。
- **papoHasViewers 快照安全性**：标志在方法开头取一次，方法体内唯一回调
  `entity.updateDataBeforeSync()` 的全部覆写者（Entity 空、LivingEntity=
  updateDirtyEffects）为纯数据更新，不增删 trackedPlayers；sendChanges 主线程
  执行，无并发追踪增删。
- **trackedPlayers ≡ seenBy 同引用**（构造器传引用，ServerEntity:76）；
  `player != entity` 守卫在 updatePlayer 首行（ChunkMap:1387），配对路径
  `entity.getId()==player.getId()` scaled-health 分支不可达，论证成立。
- **AndSelf 自发路径**：papoHasRecipients = 有观众 ∥ 实体是 ServerPlayer，
  玩家自身元数据/属性包不因无观众丢失。ItemFrame 先例（vanilla Paper 自带
  isEmpty 守卫）同构。

## §3 配对栈交叉（0206×0219×0247×0248）

- 0206（fresh getNonDefaultValues）在 0248 窗口内：首位观众构建时读当前值，
  窗口内无状态变化（B1 修复后为绝对）⇒ 共享回放与逐观众 fresh 相同。
- 0219 预尺寸 ArrayList(4) 即共享缓存载体，无冲突。
- 0247 的 `!papoAlreadyTracked && seenBy.add(...)` 短路在 sweep 内防同玩家重复
  配对；PlayerTrackEntityEvent 零监听器短路门与 0248 新门共享同一判据源。
- moonrise$tick 的 sweep 不调 begin/end（不共享）——按设计可选加入，语义为
  vanilla 逐观众构造，正确。

## §4 网络编码栈接缝

- 0218 send 快路径重排：`(主线程∧ready∧队列空∧无extra) ∥ canSendImmediate`
  两臂均纯谓词（字段读 + instanceof 链），析取交换不可观察。
- 0207 队列（136 A2 修复态 AtomicBoolean）：isConsumed 跳过 → isReady 门 →
  remove → tryMarkConsumed CAS → accept，与 0216-0225 的帧化/计数互不共享状态。
- 0225 带宽计数：出站=Prepender 逐帧累计 AtomicLong、入站=Decoder 侧；
  tickSecond 主线程 getAndSet(0) 快照与 netty 线程增量原子交叠，溢出不可达。
- setCompressionLevel（0242 触面）换级路径含 clamp 回退（非法级别
  IllegalArgumentException → min(9,max(1,level))）与
  PapoSharedWireMemo.papoStampLevel epoch 戳——133 §2 判例在位。

## §5 容器/物品栈接缝

- SGPLI：0154 零监听器快路（handleContainerClick）为 134 单族；135 族门
  （TradeSelect/SwapHand/ArmSwing/InteractEntity）均异方法。handleUseItemOn 内
  0140 标量距离检查与 criteria 门（2139）无共享状态——纯计算替换×事件构造跳过。
- AbstractContainerMenu：0229 拖拽事件门（doClick QUICK_CRAFT 段）与 0043
  canItemQuickReplace 缓存（getQuickCraftPlaceCount 内层纯查询）分层；
  suppressRemoteUpdates 字段加宽（0110）仅 InventoryMenu 读。
- ItemStack：212（encode 侧 obfuscation session 免分配）与 230（decode 侧
  CountingOps 上下文缓存）异方法（createOptionalStreamCodec.encode vs
  validatedStreamCodec.decode）；CountingOps.INSTANCE 无状态单例（benchmark
  文档与 135 §10 判例双源）——并发 decode 无共享深度计数。
- RegistryAccess：双缓存（NbtOps/CountingOps）独立 volatile 字段、良性竞态、
  reload 换新 access 实例整体替换（旧 ops 随旧实例死亡）。

## §6 红石叠栈与 Level/ServerLevel/LevelChunk

- DefaultRedstoneWireEvaluator 终态：0071 DIRECTIONS 缓存（行 15/94/163）、
  0232 事件门（60-64，handleRedstoneChange 内零监听器快路）在 R4 合并扫除
  （0255+ 批 125-131）重排后保持 vanilla 语义位置（oldPower≠i → 事件 → 复查 →
  setBlock/扇出）；R4 审计（132）本就对含门终态做十面核对。
- RedStoneWireBlock/DiodeBlock/ComparatorBlock/RedstoneTorchBlock：0071/0077
  纯迭代替换 × 0125/0232/0233 事件门 × 0234 谓词缓存——无共享可变状态。
- LevelChunk.setBlockState：0262 族脏标记（443-456）与 0241 版本 bump（457）
  相邻但均为**派发前簿记**，互不读对方状态；批 133 F1 修复的
  versionBeforeSerialization 语义不受脏标记影响。
- Level：papoWireDirty 字段/tickBlockEntitiesToRemove 复用/getEntities 死局部/
  papoGetEntitiesInto 填充重载/updateNeighborsAt 横向数组——全部异方法；
  tickBlockEntitiesToRemove 复用的非重入性（135 §4 已证）不因其他族补丁改变。
- ServerLevel：tick profile 计数（0252 族）/物品上限（0209，136 A3 修复态）/
  Vec3 hoist（0058）/registry 持有者身份比较（0253 族）——异方法分区。

## §7 区块发送族接缝

- ChunkMap.moonrise$tick：0147 hoist（getEffectiveRange 只读 range/乘客表/
  服务器配置，updatePlayer 不变异）+ 惰性移除收集（1253/1287，先收集后删，
  避免 CME；removePlayer 的 PlayerUntrackEntityEvent 回调只影响 toRemove 列表
  之外的 seenBy，迭代安全）。
- PlayerChunkSender.collectChunksToSend：0220 scratch 字段 × 0203 k-ne近邻选择
  × 0141 命令式回退——同方法三补丁叠栈闭合：scratch 读域 [0,papoSel) 每调用
  全量重写；maxIdx 重算循环域 j<floor 与 papoSel==floor 恒同；平局两实现均
  未指定且批内不可观察（0203 注释论证在位）。
  **边界证明**：floor = Mth.floor(batchQuota) ≥ 1——sendNextChunks 守卫链
  `max = Math.max(1.0F, desired)` → `quota = min(quota+desired, max)` →
  `if (!(quota < 1.0F))` 门（PlayerChunkSender:55-57），空 scratch 数组
  `papoSelDist[0]` 越界不可达。
- ChunkHolder：0145 广播索引循环与 0242 共享包/光版本计数异方法
  （broadcastBlockEntity 链 vs papoGetSharedChunkPacket）。
- ClientboundLevelChunkPacketData：vanilla 构造器（0052 循环 + 0090 预尺寸）
  与缓存构造器（0241）共享 papoBuildBlockEntityData——BE 数据两路都新鲜构建
  （BE 内容变化不 bump 版本，133 已证）；0099 共享编解码器在 BlockEntityInfo
  读写面，独立。

## §8 实体域接缝

- LivingEntity：baseTick scratch（0190）/格挡角内联（0176/0177）/击退内联
  （0175）/冷却重置事件门（0238，hurt 内）/pushEntities scratch+谓词缓存
  （0114）——异方法；A 判例族（135 §2）不受影响。
- Mob：EntityTargetEvent 门（0124，setTarget）/拾掠 scratch（0191，aiStep）/
  despawnRanges 扁平化（0237，checkDespawn）——异方法。
- ExperienceOrb：磁吸复用（0041，tick）/merge+playerTouch+修理事件门
  （0108）/mergeWithNeighbours scratch（0236）——0108 门在 merge() 内、0236
  循环调用 merge()，merge() 不触迭代中 scratch 列表，分层无状态交互。
- ItemEntity：merge 扫描 scratch（0236）与拾取双事件门+flyAtPlayer 修复
  （0107/0112，135 F4 修复态注释在位）。
- NaturalSpawner：重生点距离标量化（0045，isValidSpawnPosition）与
  PreCreatureSpawnEvent 门（0235，spawn 循环）异方法。
- PathNavigation：0230 重写（shouldTargetNextNodeInDirection，含 136 A4 精确
  取反修复在位）与 0120/0121（tick 内联/getGroundY scratch/节点比较）异方法。
- Player：aiStep 惰性 XP 列表（0101）与 attack 门（0239）及 sweep 源提升
  （0172 族）异方法。
- GameEventDispatcher.post()：0101（PapoPostVisitor 惰性 BY_DISTANCE 队列，
  每调用新实例——重入安全）× 0078/0181（int 坐标/调试广播门）同方法叠栈
  闭合：visitor 无共享状态；Mth.floor 与 BlockPos.containing 同式；事件门内
  才物化 BlockPos。
- RegionFileVersion：0066/0103/0129 三补丁汇聚为单一池化实现（ThreadLocal
  单槽借还平衡，134 §8 已审终态）。
- MinecraftServer：0094 时间包惰性构造与 0252 tick profile 异方法。
- CompoundTag：0040/0047（写侧/合并）与 0067/0091/0092（读侧/批量写）异方法。

## §9 直提交清点（src/main 树 19 个标记文件）

| 文件 | 归属 | 审计状态 |
|---|---|---|
| CraftEntity（批 5 枚举数组缓存） | 批 5 直提交 | **本轮补审闭合**：POSE_VALUES/NMS_POSE_VALUES 静态缓存，ordinal 索引与 vanilla values()[ordinal] 同构，pose≠null 守卫保留，枚举内容运行期不变 |
| PapoWireDirtyTracking | 批 126+（R4） | 132 已审 |
| PapoOrderedFileWrites/PapoParallelism/SpigotConfig（netty sizing）/MoonriseCommon | 批 78-91（R1） | 133 已审 |
| NetstatCommand/PaperCommand | 批 62 | 134 §10 已审 |
| 指纹族（PaperVersionFetcher/ServerBuildInfoImpl/PaperServerListPingEventImpl/CraftPlayer/CraftEventFactory 部分/CraftDefaultPermissions/GlobalConfiguration 部分/PaperConfigurations） | 批 51/52 | 136 已审（0205-0212 + 4 直提交） |
| WorldConfiguration（itemEntityLimitPerChunk） | 批 57/0209 | 136 已审（A3） |
| GlobalConfiguration（compressionLevel） | 批 15 | 134/136 域内（换级 clamp 本轮 §4 复认） |
| PapoTickProfile | 批 90/123/126 | **本轮功能完整性核验**（§10） |
| ItemObfuscationSession | 批 37 域 | 135 §9 已审 |

## §10 功能完整性（表述 vs 实现）

- **/paper netstat（批 62）**：命令注册（PaperCommand `commands.put(Set.of("netstat"), ...)`）、
  出站/入站 wire 计数器（Connection.papoSentWireBytes/papoReceivedWireBytes 经
  configureSerialization 穿到 Prepender/Decoder）、1s 快照（tickSecond
  getAndSet(0) 同相位）——全链接在位。
- **misc.compressionLevel（批 15）**：默认 6；setCompressionLevel 读
  `GlobalConfiguration...compressionLevel.or(-1)`，Velocity native 构造抛
  IllegalArgumentException 时 clamp 到 [1,9] 重试——防粘贴错误配置踢连接。
- **entities.spawning.itemEntityLimitPerChunk（批 57/0209）**：
  WorldConfiguration:291 默认 -1（禁用）；addEntity 入口 cap 检查
  （ServerLevel:1636-1648）；三回调计数路径 136 A3 修复态。
- **PapoTickProfile（批 90/123/126）**：opt-in `-Dpapo.tickProfile=true|1`
  （双形态，注释明示 Boolean.getBoolean 只认 "true" 的坑）；禁用时每相位一次
  静态 final boolean 检查、不触 map；400 tick 窗口报告+重置；COUNT_KEYS
  常驻键集（有界固定键集）保证零行打印；逐行 println（日志系统续行吞噬判例
  注释在位）；maybeReport 挂 tickServer。

## §11 不可信输入面 + 内存界 + 已接受缺陷

- 本轮全部触面（0247/0248/collectChunksToSend/CraftEntity/各接缝）**零客户端
  可控数据**：PlayerTrackEntityEvent/PlayerUntrackEntityEvent 为服务端插件事件；
  k-nearest 输入为服务端 pendingChunks。
- 内存界：papoPairingShareCache 瞬态（sweep 终置 null；B1 修复后仅在回调封闭
  窗口内存在）；PlayerChunkSender scratch 按需增长有界（floor ≤ 64）；
  COUNT_KEYS 固定键集；无新增无界结构。
- 0267/0268/0269 决断维持，未复发未扩散（本轮未触及三缺陷所在路径）。

## §12 验证

- `python note/check_patch_counts.py`：ALL OK。
- `./gradlew applyPatches --no-daemon --no-configuration-cache` 全量重放：
  EXIT=0（输出落文件确认），重生成的 ChunkMap/ServerEntity 与手改应用树
  **逐字节一致**——补丁序列确证可复现修复后状态。
- `./gradlew :paper-server:compileJava --rerun-tasks --no-configuration-cache`：
  BUILD SUCCESSFUL、EXIT=0。

## 判例（可复用）

1. **审计闭环判定必须逐补丁对"显式段落"做映射核对，范围头声明不可作为依据**：
   批 136 的勘误核对了 132-135 四家的显式清单，却信任了 133 自己的头声明
   （0241-0252），漏掉其中无任何段落的 0247/0248。闭环声明 = 每个补丁号可指到
   一段具体分析，缺一即未闭合——脚本化映射（补丁号 × 报告显式清单）是唯一
   可靠做法。
2. **共享/缓存优化的"窗口内不变"前提必须按事件可达性证明，不能按"无实体
   tick"论证**：Bukkit 事件回调（本例 PlayerTrackEntityEvent/
   PlayerUntrackEntityEvent）是插件合法的状态变异入口，且不同内容的自愈性
   不同（元数据/装备 ≤1 tick 自愈，拴绳无周期广播）。保守修法是把优化门控
   挂在事件自身的零监听器检查上——无插件时优化保持，有插件时精确回退
   vanilla 行为，零成本换绝对正确。
3. **同方法跨族叠栈是逐族审计的结构性盲区，但"后审家族已读终态"不等于
   "组合语义已证"**：五个家族的审计都读同一棵终态树，但各自的等价性证明只
   对照 vanilla 基线，不对照彼此的前置改写。本轮对 33 个共触文件中全部
   同方法叠栈点（配对栈/collectChunksToSend/GameEventDispatcher.post）逐一
   做了组合不变量核对——结论是组合闭合，但这个结论本身需要显式证明才能
   入账，不能从"都审过"推出。
