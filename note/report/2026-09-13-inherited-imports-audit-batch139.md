# 继承导入面对抗审计（批次 139，2026-09-13）

> 范围：七个已审面（132 红石 / 133 出站登录 / 134 入站解码容器 / 135 早期分配 /
> 136 网络 pivot+刷怪战斗 / 137 跨家族交互 / 138 fork 基线修改面）之后的全新面——
> **继承导入面**：features 0001-0035 号补丁及其实现半边的**首次内容真审**。
> 这批补丁在 fork 基线（c5eb0790f1）之前已存在（上游 Paper 系导入），批 138 的
> 净 diff 面只覆盖其中 6 个被 Papo 修改者的净改 hunk 与新增的 0036-0039，**内容
> 本体从未被本系列审计**；批 135 收官句"0001-0266 全覆盖"已被批 136 勘误为仅
> 0040-0266 真审，本轮补上最后的 0001-0035。
> 实现半边同步入面：`src/main/java/io/papermc/paper/antixray/`（0029 引擎，
> 677 行主文件，继承未改、138 未触）；`src/main` 其余继承文件（1150 个）为
> stock Moonrise/工具类，按 provenance 级处理（Papo 净改 19 文件已在 138 审）。
> 对齐流目标：长期高负载多用户稳定性、数据操作完整性、不可信输入面、内存界、
> 功能表述符合性。方法：逐 hunk 对应用树源码对抗读 + 上游语义构造性等价核对 +
> 与 Papo 自有补丁（0040-0266）的交互面重点复核。
>
> **权威纯导入证据**：`grep -l "Papo" 00[0-3]*.patch` 全目录仅命中
> 0020（批 79 hunk，133 §5 已审）/0036-0039（138 §3 已审）——0001-0035 除 0020
> 外**零 fork 自著 hunk**，作者头全部为上游 Paper 贡献者（Aikar/Spottedleaf/
> jpenilla/kickash32/lukas81298/Owen1212055/Jake Potrebic/Andrew Steinborn/
> stonar96/Nassim Jahnke/Josh Roy）。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | 持久化/数据完整性簇 0004/0009/0017/0018/0019/0020/0022/0034 | **闭合**（全上游现行形态；2 项上游继承观察） | §1 |
| 2 | 网络/不可信输入簇 0003/0006/0023/0024 | **闭合**（0003 处于批 136 A2 修复后状态；0024 攻击面有界） | §2 |
| 3 | 0029 反 X 光 + src/main antixray 引擎 | **闭合**（位布局对 1.21.11 对齐格式恒等；线程模型核验） | §3 |
| 4 | 0029×Papo 0241/0242 缓存交互 | **闭合**（antixray 活跃即旁路缓存；ready 语义无冻结路径） | §4 |
| 5 | 刷怪/实体/追踪簇 0005/0014/0026/0027/0035 | **闭合**（全上游现行形态） | §5 |
| 6 | 游戏逻辑簇 0007/0010/0011/0021/0025/0028/0030/0031/0033 | **闭合**；0028 死代码观察 | §6 |
| 7 | 引擎簇 0001/0002/0008/0012/0013/0015/0016/0032 | **闭合**（provenance+门控；0034×0002 类初始化竞态以 JVM 同步收口） | §7 |
| 8 | 功能完整性（35 补丁 subject vs 实现） | **一致**（逐补丁核到，含 0020 增量链存在性证明） | §8 |
| 9 | 不可信输入面 | **零新增**（0029 全出站；0024 首错即断开；0023 上游反夹层语义） | §9 |
| 10 | 长期运行内存界 | 无新增无界结构（0024 pending ≤30、0005 唤醒配额、antixray ThreadLocal 定长） | §10 |
| 11 | 0267/0268/0269 决断 | 维持，未复发 | §10 |
| 12 | 验证 | 零代码改动轮（沿批 132 只读判例免重放；工作树 clean） | §11 |

## §1 持久化/数据完整性簇

- **0004 超大区块**：现代形态仅存兼容读半边（readOversizedChunk 合并
  Level/Entities/TileEntities 旧键）+ 写侧清标志（setOversized(false)）；超大
  **写**路径早已不存在（Moonrise 下 0017 的 MAX_CHUNK_SIZE=500MiB 兜底 +
  RegionFileSizeException→DELETE 语义取代），与上游 Paper 现行一致。旧键合并
  仅对前 1.18 时代遗留 .oversized.nbt 有意义，era-matched，维持。
- **0009 超大方块实体**：BLOCK_ENTITY_LIMIT=750 超限项走 getUpdatePacket()
  extraPackets（null 则回落 NBT 内联）；extraPackets 经
  ClientboundLevelChunkWithLightPacket.getExtraPackets() 出站，
  0003 队列路径 buildExtraPackets0 递归展开——链路完整。
- **0017 序列化失败不落盘**：moonrise$startWrite 路径 finally close + 尺寸异常
  →WriteResult.DELETE；遗留路径手写 close 仅 RegionFileSizeException 分支不
  close——底层为 ByteArrayOutputStream（无 OS 句柄），GC 可回收，无泄漏；
  上游原样。
- **0018 实体每区块存/读限**：双写路径（ChunkEntitySlices/EntityStorage）+
  loadEntitiesRecursive 读限，默认 -1 关闭（无静默删档风险）；上游原样。
- **0019 regionfile 头重算**：灾难恢复路径全读。getLastWorldSaveTime("LastUpdate")
  与写侧 putLong("LastUpdate") 互证一致（写侧行已标 diff-on-change）；构造期
  扫描只收"完整解析+坐标在区内"的条目，重算后头指向已验证数据，读侧重试递归
  实际有界；finishReadAsync 的 recalculateCount 失配（缓存逐出+重建窗口）失败
  方向为重生而非损坏。**观察 1（上游原样维持）**：recalculateHeader 中
  `Files.list(containingFolder)` 返回的 DirectoryStream 未关闭——稀有恢复路径，
  JDK Cleaner 兜底回收，Windows 长驻最坏延迟到 GC；上游 Paper 同形，不修。
- **0020 增量存档**：主 tick 相位分解（playerSaveInterval/maxPerTick 限流 +
  saveIncrementally 全量相 level.dat+WorldSaveEvent）；**功能完整性证明**：
  增量区块保存链真实存在——ChunkMap.processUnloads 每 tick 调
  ChunkHolderManager.autoSave()（:435），非空谈。Papo 批 79 hunk（saveAll
  interval==-1 → awaitAll）在位且 133 §5 已审。
- **0022 flushRegionsOnSave**：IO 线程 finishWrite 后 config 门控 flush
  （meta=true），缓存逐出分支注释与 Moonrise 逐出即 flush 语义吻合。
- **0034 启动预载**：CrashReport.preload 异步化（memoize 语义，结果弃用无害）+
  DataConverter 双线程预 init（§7 竞态收口）+ Blocks 并行 initCache（首态主线程
  保证 Blocks 类先初始化，其余 per-state 独立）；上游原样。

## §2 网络/不可信输入簇（0003/0006/0023/0024）

- **0003 网络管理器重写**：本轮读到的为**批 136 A2 修复后状态**
  （WrappedConsumer.consumed=AtomicBoolean 在位）；send 早退
  `!connected && !preparing`、立即发送白名单 canSendImmediate、processQueue
  迭代器 remove-before-CAS、disconnect/handleDisconnection 双点
  clearPacketQueue（finish 监听器以 null future 通知）逐句与上游一致；
  FlushConsolidationHandler addFirst + `Paper.disableFlushConsolidate` 逃生门。
- **0006 Velocity 原生压缩/加密**：解压侧膨胀上限沿用 vanilla
  MAXIMUM_UNCOMPRESSED_LENGTH=8MiB 预检（claimed size 有界分配），
  libdeflate inflate 不越界；cipher 编解码 handlerRemoved 关闭。
  **观察 2（上游原样维持）**：setupCompression 对已建 pipeline 二次调用时，
  新建 VelocityCompressor 被既有 setThreshold("Only re-configure once") 丢弃
  且不 close——每次泄一个原生上下文；仅压缩阈值包重发/代理重协商触达，稀有。
- **0023 移动包碰撞优化**：hasNewCollision 合并读；两处对 vanilla 的语义差
  （moved-wrongly 即回拉，不再要求 noCollision；absSnapTo 防 desync；
  `if (false && ...)` 关闭 >1000ms RTT 客户端的传送重发）全部为上游 Paper
  既定决策，非移植走样。
- **0024 keepalive 重写**：挑战=millis，应答仅匹配本连接 pending 表（peek 头
  匹配→计量；表内乱序→断开；无匹配→断开，首错即踢使伪造应答无放大面）；
  发送 1Hz、KEEPALIVE_LIMIT 超时踢出 → pending 表上界 ≤30 条；
  PingCalculator 除数恒 ≥1（刚加入项 txTime==currTime 永不出窗）；
  cookie 跨 config↔play 相位携带 pending 表，不断链。

## §3 0029 反 X 光 + src/main 引擎（首次真审）

- **位布局恒等证明**：1.21.11 SimpleBitStorage 为对齐格式
  （`valuesPerLong = 64 / bits`，值不跨 long）。BitStorageWriter.write 的
  `bitInLongIndex + bits > 64 → flush+下 long 对齐`与该格式逐点一致；
  skip 的 `> 64 → bitInLongIndex = bits` 正确表达"跨界值整体移至下 long
  bit0"；init() 重读保留未写邻值。若为旧跨 long 格式此写器必错——格式证明
  即正确性证明。
- **线程模型**：executor=MinecraftServer.executor（vanilla 后台池，多线程）；
  obfuscate 全部可变 scratch 用 ThreadLocal（presetBlockStateBits/SOLID/
  OBFUSCATE/CURRENT/NEXT/NEXT_NEXT），per-call 分配仅 reader/writer/sections
  数组；modifyBlocks 非主线程 → scheduleOnMain（插件构造包路径安全）。
- **并发读容错**：isTransparent/readPalette 捕获 MissingPaletteEntryException
  按"透明/维持快照"处理（失败方向=瞬时多混淆/用构造时快照，无崩溃、无错写）；
  上游注释给出的不可抛论证（ArrayList set-then-size、resize 换对象不原地改）
  经核 1.21.11 palette 内部结构成立。旁块读取经 getChunkIfLoaded 快照引用，
  与主线程写无锁交错均落在上述容错内。
- **门控**：`anticheat.antiXray.enabled` → 控制器实例化；engineMode HIDE(1)/
  OBFUSCATE(2)/OBFUSCATE_LAYER(3) 三态；HIDE 按环境替换 stone/deepslate/
  netherrack/end_stone；maxBlockHeight 右移 4 左移 4 对齐；
  usePermission → paper.antixray.bypass。
- **观察 3（上游原样维持）**：obfuscate 无兜底 try/catch，理论异常会使
  setReady 永假 → 该连接 pendingActions 队列头阻塞（队首 !isReady 时
  processQueue 恒 false）。经逐源核验无现实可抛路径（palette 容错覆盖、
  buffer 索引由同一布局推导），维持上游形态。

## §4 0029 × Papo 0241/0242 缓存交互（跨代际交互重点）

- PlayerChunkSender.sendChunk 现行为：`shouldModify=true`（antixray 活跃，
  含 usePermission=true 的非 bypass 玩家）→ **逐玩家新鲜构造**（不走缓存）；
  `shouldModify=false`（antixray 关闭的全服，或 usePermission=true 的 bypass
  玩家）→ papoCreateCached 共享实例。混淆字节永不跨玩家共享、真数据缓存
  也不发给非 bypass 玩家——分叉正确。
- FeatureHooks.sendChunkRefreshPackets 以 shouldModify 为 map 键双变体刷新，
  同一原则。
- **ready 语义闭环（防冻结关键路径）**：缓存路径 modifyBlocks=false →
  chunkPacketInfo=null → 两个控制器（AntiXray 的 null-instance 分支 /
  NO_OPERATION_INSTANCE.modifyBlocks）均立即 setReady(true)；antixray 活跃
  路径由 obfuscate 尾部 setReady。无 ready=false 且永不成真的包。

## §5 刷怪/实体/追踪簇

- **0005 EAR 2.0**：现行上游重构后形态（io.papermc.paper.entity.activation
  包、ActivationType 静态 BB——activateEntities 仅主 tick 串行调用，无并发）；
  免疫矩阵/唤醒配额（wakeupInactiveRemaining 每tick+1 封顶）/inactiveTick
  覆盖（AgeableMob/AreaEffectCloud/ItemEntity/Villager/Firework/Arrow/
  MinecartHopper immunize/Piston 推挤免疫）逐项在位；HAPPY_GHAST 已纳入
  ENTITIES_THAT_FLY（1.21.11 适配活）。Papo 内嵌两 hunk（climbing cache
  #12764 + unaware AI skip #13781）138 §5 已审不重。
- **0014 追踪器去同步**：onPlayerAdd → forceStateResync → sendChanges 三点
  强制（首包全量、teleport 门、旋转/位置）消费后复位；与 0247/0248 的
  零监听器门共存（137 已审组合语义）。
- **0026/0027 每玩家刷怪**：mobCounts/mobBackoffCounts 双表，TICK_VIEW_DISTANCE
  ReferenceList 就近计数；backoff 每 tick -1 地板 0；ABORT/CANCELLED 双态计
  入 backoff；maxSpawns=minDiff 传导至 spawnCategoryForChunk 的
  `i >= maxSpawns` 早退。上游原样。
- **0035 箱子开合回调延迟**：delayCallbacks → incrementOpeners/decrementOpeners
  仅 scheduleRecheck(1)，由方块 scheduled tick 的重check 归纳应用——
  退出登录/停机窗口的 chunk-load 限制场景正是其设计目标；红石比较器输出
  ≤1 tick 迟滞为上游既定折衷。

## §6 游戏逻辑簇

0007（GoalSelector 位集化+UNKNOWN_BEHAVIOR 兜底）/0010（草传播 chunk 缓存，
getChunkIfLoaded null 即跳过）/0011（BlockPos 26+12+26 打包内联——与 vanilla
自算常量恒等：1+log2(2^25)=26）/0021（Spottedleaf POI BFS 外扩，moonrise
WorldUtil 接线）/0025（EntityScheduler 注册表化，ServerEntityLookup.addEntity
统一注册含玩家）/0030（exact-choice 配方，ItemOrExact sealed 体系 +
StackedContents 去 Reference 化）/0031（V4307 HashSet 化+can_place_on 先序）
/0033（比较器 neighborChanged/模式切换前的 is(this) 守卫）——全部上游现行
形态逐 hunk 对上。**0028 漏斗**：getFullState 三态/hopperPush/hopperPull
（origItemStack.copy(true) 保留原 item 语义）/canMergeItems `<` 修复/
ignoreBlockEntityUpdates 静态门（vanilla 容器 setItem 不抛，无 finally 风险）
与上游一致；**观察 4（死代码）**：allMatch/anyMatch 在应用树零调用
（isFullContainer 已被后续上游补丁重写为直接循环），anyMatch 尾部
`return true` 若被调用将是 bug——现为不可达死代码，删除会造成对上游补丁的
无收益漂移，维持并注记。

## §7 引擎簇（provenance/门控级）

- **纯导入硬证据**：见范围头 grep；0001 Moonrise/0002 dataconverter 重写
  （上游 Paper 已合并的 Moonrise dataconverter 体系，DataFixers DATA_FIXER
  为 static final 类初始化）/0008 IndirectMerger 无限列快路/0012/0013
  （Aikar/JRoy 上游）/0015 Eigencraft/0016 Alternate Current（Space Walker）
  /0032（jpenilla 2026-02 AsyncAppender flush，FlushEvent 哨兵+超时预算
  线性扣除）。
- **红石三态门控**：`paperConfig().misc.redstoneImplementation ∈
  {VANILLA, EIGENCRAFT, ALTERNATE_CURRENT}` 在 RedStoneWireBlock 四处分支
  在位；R4 全部红石优化（0232-0234/0253+/0258-0266）显式门控
  `== VANILLA && 非实验评估器`（RedStoneWireBlock:490-494 注释与实现核对），
  AC/EIGENCRAFT 路径与上游逐字节一致，无双引擎交叠。
- **0034×0002 竞态收口**：`MCTypeRegistry::init` 实为空方法（触发类加载，
  static 块在 JVM 类初始化锁下执行一次）；`DataFixers::getDataFixer` 触发
  `static final DATA_FIXER = createFixerUpper()` 类初始化——主线程与预载
  线程并发触发时由 JVM 类锁串行化，无双构建/半初始化发布窗口。

## §8 功能完整性（subject vs 实现）

| 补丁 | 表述 | 实现 | 一致 |
|---|---|---|---|
| 0003 | 队列仅主线程处理/异步包主线程派发/flush 合并 | flushQueue 主线程门 + canSendImmediate 白名单 + FlushConsolidationHandler | ✓ |
| 0004 | 超大区块可存（现代=兼容读+标志清理） | §1 | ✓ |
| 0005 | EAR 2.0 免疫/唤醒/分类 | §5 全矩阵 | ✓ |
| 0009 | 超多 BE 拆包 | extraPackets 链路 | ✓ |
| 0017 | 序列化失败不落盘 | DELETE 语义双路径 | ✓ |
| 0018 | 每区块实体存/读限 | 三点全，默认关 | ✓ |
| 0019 | 头损坏重算 | 扫描+备份+摘要+时间戳重建 | ✓ |
| 0020 | 增量区块与玩家存档 | autoSave 链存在性证明 + 限流 | ✓ |
| 0022 | 保存时 flush regionfile | config 门控 | ✓ |
| 0023 | 移动包碰撞检查优化 | hasNewCollision | ✓ |
| 0024 | 更密 keepalive+5s 真均值 | 1Hz+双窗口 | ✓ |
| 0025 | EntityScheduler ticking 优化 | 注册表化 | ✓ |
| 0026/0027 | 每玩家刷怪+取消回退 | 双表+backoff | ✓ |
| 0028 | 漏斗优化（六点） | 全部在位 | ✓ |
| 0029 | 反 X 光 | 三引擎模式+权限旁路 | ✓ |
| 0030 | exact choice 配方 | ItemOrExact 体系 | ✓ |
| 0034 | 启动期主线程外预载 | 三线程预载 | ✓ |
| 0035 | 箱子开合回调延迟 | delayCallbacks 体系 | ✓ |

## §9 不可信输入面

0029 全部改动在**出站**编码侧（客户端输入不影响混淆决策，仅服务端配置与
世界数据）；0024 入站 keepalive 应答首错即踢、无无界累计；0023 入站移动包
走上游反夹层判定（等价核见 §2）；0006 解压claimed-size 上游预检；0003 的
入站面 134 已审不重。**零新增可达输入面**。

## §10 长期运行内存界

0024 pending ≤30 条/连接、ping 双窗口队列随出窗弹出；0005 唤醒配额每 tick
封顶；antixray ThreadLocal 定长数组（注册表大小级）；0019 重算数组全部
32×32 定长；0018 计数 map 每次保存新建即弃。无新增无界结构。
0267/0268/0269：决断维持，未复发。

## §11 验证

本轮为只读审计：补丁树与 src/main 零触碰（`git status` clean），沿批 132
只读轮判例免 applyPatches 重放/编译。发现的四项观察全部为上游继承形态
（判定标准同 138 §3/§4：与上游逐字一致+无可达触发路径或自愈机制），
按"上游原样维持"判例处置并入库备查。

## 判例（可复用）

1. **"净 diff 面"的盲区是继承未改内容**：批 138 以净 diff 枚举 fork 修改面
   时，基线已存在且 Papo 未触碰的 0001-0035 与 stock src/main 天然不在
   diff 里——"无净改"≠"已审"。面枚举应显式三分：净改（真审内容）/净增
   （真审内容）/继承未改（provenance 级+实现半边分级）。
2. **继承导入的纯度用标记 grep 硬证明**：`grep -l "Papo" features/*.patch`
   一条命令区分"纯上游导入"（作者头+内容对上游）与"含 fork hunk"
   （0020/0036-0039）——纯导入件的审计深度可以降为 provenance+门控+交互
   面，把深读预算留给 fork 自己写的内容。
3. **位级写器的正确性锚定在格式代际上**：antixray BitStorageWriter 的跨
   long 重对齐在旧式跨 long 格式下是 bug、在 1.21.2+ 对齐格式
   （valuesPerLong=64/bits）下是恒等——审位操作代码必须先证目标格式，
   再谈读写对称。
4. **异步 init 的线程安全以类初始化锁收口**：`Thread.start(X::init)` 当
   init 触发的是 static final 字段/静态块时，JVM 类锁天然串行化双线程
   触发——无需应用层同步；若未来有人把懒加载改成 DCL 范式（非类初始化），
   该论证即失效，需重审。
