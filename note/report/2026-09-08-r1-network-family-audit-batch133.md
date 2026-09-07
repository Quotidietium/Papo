# R1 网络面/登录面家族对抗审计（批次 133，2026-09-08）

> 范围：R1 多核调度与包管线家族——0241-0252 + 直提交基建
> （PapoOrderedFileWrites/PapoParallelism/PapoJoinPacketCache、批 79 存档下放、
> 批 82 预取、批 87/88 事件驱动、批 91 停机加固、0226/0227 join 缓存）。
> R4 红石家族已在批次 132 十面审计（零缺陷）；本轮按"每次覆盖不同方面"换面。
> 重点对齐本轮流目标：网络服务暴露下的不可信输入、多用户高频 join/quit
> 稳定性、数据操作完整性、长期高负载内存界、用户交互流畅度。
> 方法：逐补丁/逐应用树对抗读 + 对 vanilla 语义的构造性等价核对。
> 已接受缺陷（2026-09-03 用户决断）0267/0268/0269 只核对未复发，不重报。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | chunk 包双缓存（0241）+ 共享实例（0242）正确性 | **1 处真实竞态（F1，本轮修复）**；其余闭合 | §1 |
| 2 | 共享 wire/encode memo（0242-0245）并发与字节等价 | **闭合** | §2 |
| 3 | join 静态包缓存（0226/0245/0246）失效完备性 | **闭合**（全部变异路径核到唯一赋值/入口） | §3 |
| 4 | 登录预取/join 管线（0249-0251、0227）生命周期与双执行 | **闭合**（consume-once + 双断连钩子全覆盖，含 placeNewPlayer 监听器切换窗口分析） | §4 |
| 5 | 数据操作（批 79 下放、PapoOrderedFileWrites、批 91 停机、.dat_old/备份副作用） | **闭合**（备份/改名/排序逐项等价；非原子 JSON 写为 vanilla 原生行为非回归） | §5 |
| 6 | 光版本契约（0242 sectionLightChanged 完备性 + 同 tick 修正） | **闭合** | §6 |
| 7 | 不可信输入面 | **零可达**（预取键全部服务端权威派生） | §7 |
| 8 | 长期运行内存界 | F1 修复外无新增无界结构；0269 常驻按决断维持 | §8 |
| 9 | 已接受三缺陷状态核对 | 未复发、未扩散 | §9 |
| 10 | 构建验证 | compileJava --rerun-tasks BUILD SUCCESSFUL（输出落文件 + EXIT=0 双确认） | §10 |

## §1 F1：papoStoreChunkPacketCache 版本后读竞态（本轮唯一代码缺陷，已修）

**机制**：`LevelChunk.papoStoreChunkPacketCache`（0241）原实现先写
heightmaps/buffer 两字段、**后**读 `papoChunkDataVersion.get()` 作为缓存版本。
存储调用点（ChunkHolder.papoGetSharedChunkPacket:322 与
ClientboundLevelChunkWithLightPacket.papoCreateCached 无 holder 回退:73）都先
完整序列化 chunk 载荷（毫秒级窗口）再进 store——gen 线程若在此窗口经
`WorldGenRegion.setBlock → LevelChunk.setBlockState → papoBumpChunkDataVersion`
对同一 FULL chunk 结构粘贴（批次 132 §1 已实证该写路径存在），store 读到的
是** bump 后**的版本号：旧载荷被标成新版本，`papoChunkPacketCacheValid()`
恒真。

**传播链（0242 放大）**：ChunkHolder 共享包层在方法顶部预读版本 V 并构造/
缓存（stamp V）→ 下一位观众命中共享缓存失败（V ≠ V+1）→ 走 chunk 缓存层
→ cacheValid 为真（旧数据 stamped V+1）→ 以旧 buffer 重建共享包并 stamp V+1
→ **此后所有观众持续收到粘贴前的方块状态**，直到该 chunk 任意下一次真实
变异。服务端状态正确，属客户端可见性缺陷；概率低（需结构粘贴恰落在并发
发送的序列化窗口内）但影响持久。

**修复**（批 133）：store 改为三参
`papoStoreChunkPacketCache(heightmaps, buffer, versionBeforeSerialization)`，
版本由调用方在**序列化开始前**捕获传入（ChunkHolder 用其顶部的预读值；
papoCreateCached 回退路径新增预读）。窗口内发生 bump 时缓存被 stamp 旧版本
→ 有效性检查失败 → 下次发送重序列化自愈。方向为"多失效不少失效"，
严格保守。

**为什么 132 没抓到**：132 审的是 R4 面（0253+），0241 的顺序问题属 R1 面，
R3 时代审计（119-122，报告随 archive/multicore-r3 封存）未覆盖此窗口。

## §2 共享 memo（0242-0245 编码/压缩两级）

- 压缩段自描述（[未压缩长度 varint][DEFLATE 载荷]），阈值/epoch 撕裂读的
  全部组合仍解码同一载荷（含 varint-0 未压缩段在任何阈值下合法）；跨连接
  压缩器实现差异只改变字节不改变解码结果。核对无新增缺陷。
- 编码级 memo 只装在确定性、locale 无关的编解码器上（bitset/varint 行走、
  注册表 map 行走）；replay 路径跳过 codec 但 PacketTooLarge 检查读同一
  readableBytes。快照/回放读写索引假设（HEADROOM 前缀缓冲）两侧一致。
- attribute 生命周期：PacketEncoder 发布 → CompressionEncoder
  `getAndSet(null)` 消费即清；Varint21LengthFieldPrepender 两条路径与
  CompressionEncoder 外来缓冲路径均清 memo attr——无跨写遍历滞留、无 pin
  包（memo 只持一段 byte[]）。
- 并发填充：volatile 段/编码数组单引用发布，撕裂组合均为"同内容不同字节"
  的合法编码；first-writer-wins 的丢失侧等于多压一次，无正确性影响。

## §3 join 静态包缓存失效完备性

- **tags/registry（0226）**：`PlayerList.reloadTagData` 是 tag 内容唯一变化点
  （MinecraftServer.reloadResources → loadTagsForExistingRegistries 链），
  reload() 同点丢弃 registry 双缓存；worldgen 层注册表仅跨重启变化，作
  backstop 一并丢弃。matched/mismatched 缓存键 = 客户端 known-packs 与服务端
  是否相等，requestedPacks 在两次 reload 之间为常量——键无歧义。
- **recipes（0245）**：同步数据赋值唯一入口 = `RecipeManager.finalizeRecipeLoading`
  族；无参重载（addRecipe/removeRecipe/clearRecipes 全部 CraftBukkit 变异路径
  经此）显式调 `PlayerList.reloadResources()` → reloadRecipes 先置空缓存；
  datapack reload 链（MinecraftServer.java:2336）同点。逐路径核到唯一赋值。
- **player-info（0246）**：包实例为 per-join 瞬态（memo 随包死亡），无失效
  需求；unlisted 变体同构 hoist，逐接收者构造恒等。

## §4 登录预取/join 管线生命周期

- **consume-once**：.dat（papoConsumePrefetch remove）、stats/advancements
  （take remove）均为单消费；二次进入自动落回同步路径。
- **断连钩子完备**（本轮重点重证）：login 阶段断连 →
  ServerLoginPacketListenerImpl.onDisconnect（authenticatedProfile 非空时）；
  配置阶段断连 → ServerConfigurationPacketListenerImpl.onDisconnect。
  关键窗口逐一分析：
  - 预取发布（startClientVerification）→ 配置监听器挂接：login 钩子覆盖。
  - ServerPlayer 构造器内 advancements 消费（ServerPlayer.java:475）→
    此时仍处配置监听器存续期（placeNewPlayer 尚未调用）。
  - placeNewPlayer:177 监听器切换（→PLAY）到 :208 stats 消费之间断连：
    PLAY 监听器 onDisconnect 无钩子，**但 placeNewPlayer 本身继续执行**，
    stats 消费在 :208 无条件完成（断连早退点 :249 在其后）——无泄漏。
  - 配置完成 → placeNewPlayer 调用间隙：仍为配置监听器。
  - 结论：任意断连时点，三类预取要么已被消费要么被对应钩子丢弃，无泄漏
    路径；map 键有界（每 UUID 至多一条，断连即除）。
- **.dat 备份/改名副作用等价**：同步路径仅当 .dat 读空时 backup(".dat")，
  对 .dat_old 无 backup 调用；预取路径逐分支相同（backup 条件、
  .offline-read 改名仅在对应 tag 成功读出且 usingWrongFile 时、
  fallbackToSync 异常回退完整重走同步路径）。
- **0227 双读去重**：start()/spawn() 共享同一 datafix'd tag；spawn() 的
  CraftBukkit map 变异发生在最后一次消费点之后，无第三读者。
- **0250/0251 事件驱动**：主线程队列串行 + 状态机守卫（VERIFYING/
  Preparing 状态 + papoFinishClaim CAS）双保险；server.execute 队列发布提供
  happens-before，跨线程读 state/authenticatedProfile 安全；executor 拒绝
  （停机尾）回退常规 tick 路径。

## §5 数据操作

- **PapoOrderedFileWrites**：per-target 链经 `TAILS.compute` 原子拼接，
  条件 remove(target, node) 保新尾；失败前驱 handle() 吞异常不断链；
  awaitPending 有界（60s）且 temp+ATOMIC_MOVE 模式下无撕裂读；
  enqueueRead 与写共用链键（读排在前序写后）；shutdown 窗口
  executeOrRun/queueTask 双路径内联降级（批 91）；awaitAll monitor 计数
  配对精确（enqueue/enqueueRead 增、whenComplete 减）。
- **.dat 保存**（批 79）：主线程快照 + 深拷贝 NBT 树 → IO 池
  createTempFile + writeCompressed + safeReplaceFile(.dat, .dat_old)，整链
  键于 .dat 路径（含轮转），无 .dat_old 键独立写——预取读与同步读的排序
  语义一致。
- **stats/advancements JSON 非原子直写**：核对补丁基线（`-` 侧）——
  vanilla/Spigot 本体即 `newBufferedWriter(file)` 直写，Papo 下放保持同一
  调用形态与字节输出，**非本 fork 引入的回归**；崩溃窗口截断风险与
  vanilla 等价，维持现状（如需原子化属行为变更，超出审计轮边界）。
- **停机**（批 91）：残余 halt(false) 丢写窗口已在源注释中如实声明
  （等价于 watchdog 强杀，vanilla 同步保存同样不可存活）。

## §6 光版本契约（0242）

- 完备性：vanilla `LayerLightSectionStorage:271` 与 moonrise
  `StarLightEngine:280/1035` 全部经 `ServerChunkCache.onLightUpdate`
  （mainThreadProcessor.execute 跳主线程）→ sectionLightChanged →
  papoBumpLightVersion（在 untracked 早退**之前**，不漏计数）。
- 陈旧窗自愈：调度线程光写完成但通知在队 → 共享包嵌入旧光 → 主线程处理
  排队通知置位过滤 Bitset → 同 tick broadcastChanges 发增量
  ClientboundLightUpdatePacket；此时新观众已在 playersSentChunkTo
  （moonrise$addReceivedChunk 于发送完成时标记，均主线程序）→ 修正可达，
  无持久 desync。

## §7 不可信输入面

预取路径键全部由服务端权威派生：GameProfile.id()（Mojang 会话或离线 UUID
服务器侧计算）、`uuid + ".json"`/`uuid + ".dat"` 固定格式拼接（无路径段
注入面）；nameAndId.name() 仅入日志。memo/缓存的键（threshold/epoch/
版本号/known-packs 相等性）无一来自客户端可控数据。chunk 包缓存键为
服务端版本计数器。0241-0252 全部触碰文件清单不含包解析/命令/聊天入站
处理。**零新增可达输入面**。

## §8 长期运行内存界

- 预取 map：每 UUID 有界、断连/消费即除（§4）。
- memo：瞬态包随包回收；共享 chunk 包的 memo 段常驻属 0269 决断范围。
- PapoJoinPacketCache/papoCachedRecipesPacket：每 reload 常量个包实例。
- encode memo 只装瞬态广播包与 join 静态包（chunk 共享包仅压缩 memo，
  ~40KiB 编码快照不 pin——设计如此，本轮复核无违反）。
- wire memo PAPO_LAST_LEVEL/LEVEL_EPOCH：静态标量。

## §9 已接受三缺陷状态核对（2026-09-03 决断维持）

| 缺陷 | 状态 |
|---|---|
| 0267 join 预取无界等待（stats/adv `future().join()` 无超时） | 仍在（决断维持）；本轮确认 .dat 侧 60s 有界不受影响 |
| 0268 0245/0246 locale 冻结 | 仍在（决断维持）；本轮核对的 0243/0244 载荷编解码器为纯结构行走，不在该缺陷面内 |
| 0269 0241/0242 chunk 包缓存常驻 | 仍在（决断维持）；F1 修复不改变常驻行为（只改版本 stamp 语义） |

R4 面（132 已审）无复发。

## §10 验证

- 修复落盘流程：应用树三文件修改 → 手改 0242 补丁 hunk（本仓库既定工作流，
  rebuildPatches 在本仓库有 4 次垃圾重命名复发史，历史批次均手改补丁文件）
  → `applyPatches` 全量重放：BUILD SUCCESSFUL、EXIT=0，**重生成的三文件与
  已编译树逐字节一致**（diff 全 IDENTICAL）——补丁序列确证可复现修复后状态。
- `./gradlew :paper-server:compileJava --rerun-tasks --no-configuration-cache`：
  BUILD SUCCESSFUL、EXIT=0（输出落文件双确认，遵守构建判例：管道退出码不可信）；
  重放后增量编译 UP-TO-DATE 复核一致。
- 判例：手改补丁 hunk 时新侧行数必须按"上下文+新增"实数核对——本轮
  第一次提交计数差 1（`// Papo end` 后空行的新增/上下文归属）即触发
  `corrupt patch`，git am 的 hunk 头计数是强校验不是提示。

## 判例（可复用）

1. **"先存数据后读版本"是缓存-版本对的顺序缺陷**：版本必须在快照数据决定
   的那一刻之前捕获。凡是"数据+一致性戳"结构，戳的读取点必须先于（或同
   步于）数据的生产起点，否则并发变异者会把旧数据洗白成新版本。审计此类
   缓存时先找 stamp 的读取点相对序列化的位置。
2. **生命周期钩子的完备性要按"消费点-监听器切换点"相对顺序证明**，而不是
   数钩子数量：placeNewPlayer 的监听器切换（:177）与 stats 消费（:208）之间
   断连不泄漏的唯一原因是消费在函数内无条件先行于断连早退点（:249）。
   一旦有人把 stats 消费挪到 :249 之后，该窗口即成为泄漏点——应在
   断连早退点旁加注释锚定此依赖。
3. **审计"下放 IO"补丁时先 diff 写模式再谈并发**：本 fork 的 .dat 下放
   保留了 temp+原子替换，而 stats/advancements 的直写是 vanilla 原生——
   基线（补丁 `-` 侧）才是判定"回归"的参照，不能拿理想写模式当标准。
