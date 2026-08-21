# 批次 70 JMH 微基准报告（2026-08-22）— 多观众区块包构造缓存（0241）

主题：**多玩家网络稳定**（用户目标主线的区块发送突发 CPU 消除）。勘察发现：区块发送主路径
`PlayerChunkSender.sendChunk` 对**每个观众**完整重序列化同一 chunk（heightmaps clone + 24 section
调色板序列化 + buffer 分配）——N 个玩家看同一 chunk = N 次全量序列化（登录/跑图突发、群体迁移场景）。
Paper 自己在 `FeatureHooks.sendChunkRefreshPackets`（同 tick 刷新路径）已按 `shouldModify` 键缓存并跨
玩家**共享同一 packet 实例**，证明共享语义成立——但该缓存仅限单次调用，不覆盖跨 tick 的主发送路径。

本批（0241）在 `LevelChunk` 每实例建立**序列化负载缓存**：heightmaps Map + 序列化 byte[] buffer，
以每 chunk 变更版本计数（`papoChunkDataVersion`）失效；BE 标签与光照数据保持逐包新鲜构造。
环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性设计（失效信号审计，四处闭合）

缓存内容 = `ClientboundLevelChunkPacketData` 中只随 chunk 变更而变的两段：

| 负载段 | 变更来源 | 失效信号 |
|---|---|---|
| heightmaps（6 类型 long[] clone） | setBlockState（Heightmap.update 原位更新） | `setBlockState` bump（变更分支顶部） |
| buffer（24 section 调色板序列化 + biomes） | setBlockState / setBiome（section.setBiome 经 ChunkAccess.setBiome，CraftWorld 唯一调用链） | 同上 + `setBiome` 覆写 bump |
| blockEntitiesData（BE 列表） | BE 增删 + **BE 内容原位变化（无 chunk 级信号！）** | **不缓存**：逐包新鲜构造 |
| lightData（光照） | 光照引擎（跨 chunk 独立变更，无 chunk 级信号） | **不缓存**：逐包新鲜构造 |

- bump 位点四处：`setBlockState`（变更 else 分支顶部，同状态早退路径不 bump）、`setBlockEntity`
  （map put 后）、`removeBlockEntity`（方法首，缺席移除的过度 bump 无害——只导致重建）、
  `setBiome` 覆写（super 调用前）。`addAndRegisterBlockEntity`/`promotePendingBlockEntity`/
  `postProcessGeneration`/BlockPopulator 全部经 setBlockState/setBlockEntity 汇流，天然覆盖。
- **BE 内容新鲜性是硬约束**：BE NBT（告示牌文字等）原位变化不触碰 LevelChunk——vanilla 语义 =
  构造时读取当前值，缓存 BE 列表会让后加入的观众拿到陈旧 BE 标签（可观察回归，红线不可越），
  故 BE 段永远新鲜。光照同理（增量光照包只发给当时在场的观众，缓存光照会让后来观众永久陈旧）。
- **anti-xray 门控**：仅 `shouldModify == false` 时走缓存（Paper 默认 `antiXray.enabled = false` 命中；
  启用或玩家 bypass=false 时回退原路径）。info=null 路径的 `modifyBlocks(packet, null)` 对两种控制器
  实现均恰为 `setReady(true)`（逐源码实证），新构造器直接置 ready=true，行为逐字一致。
- **首发送填充缓存**：miss 时按原构造器完整构造并把 `getHeightmaps()`/buffer 存入 chunk 字段；
  后续观众命中时新 packet 复用同一 map/数组（两者构造后不可变——NO_OPERATION 路径 buffer 无人改写；
  packet 跨玩家共享为 vanilla/Paper 既有行为，broadcastAll 与 FeatureHooks.refreshPackets 先例）。
- **线程模型**：缓存读写仅主线程（sendChunk 的两个调用点均主线程 tick）；版本计数用 AtomicLong
  覆盖 worldgen 线程经 ImposterProtoChunk 对未满状态 chunk 的 setBlockState（此时无发送，纯防御）。
- **内存面**：缓存仅存在于被发送过的 chunk（≤ 玩家发送视距并集），地表 chunk 序列化负载典型
  1-10KB；10vd ≈ 441 chunk/玩家，数十玩家典型净增数 MB，换取每次多观众发送的全量序列化消除。
- **带宽中立**：线上字节与原路径逐字节相同（同内容同序列化器）——本批收益是主线程 CPU/GC
  （多人区块突发稳定性），不是字节缩减。

## 2. 基准（ChunkPacketCacheBench，模型）

成本模型复刻 info=null 路径的构造三段：A) heightmaps HashMap+6×clone(long[37])；B) 24 section
调色板序列化（8 非空：12 项调色板+4bit 位存储 256 longs；16 空：单值调色板）；C) 4 个 BE 的
getUpdateTag+info 包装（两路径均新鲜，真实差异= A+B）。光照两侧同价不在模型内。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 2597.676 ± 271.850 | 每观众完整构造（A+B+C） |
| afterHit | **24.623 ± 0.363** | 版本校验 + C（A/B 引用复用） |
| afterMiss | 2776.583 ± 274.059 | 首观众：完整构造 + 存缓存（与 before CI 重叠，存缓存开销在噪声内） |

- **命中路径 ≈ 105×**（CI 完全分离）。
- `-prof gc`（f=1 复测）：before `gc.alloc.rate.norm` **43992 B/op** → afterHit **248 B/op**
  （**177× 分配消除**；after 端剩余 = BE 模型对象）。
- 模型规模诚实边界：模型 buffer ≈17KB/次构造；真实 chunk（更大调色板/更深结构、压缩前 30-90KB，
  批次 58 survey 实测）序列化成本高于模型，真实收益≥模型比例。N 观众同一 chunk 的主线程序列化
  总量从 N 次降为 1 次（+每人 BE 段与光照 memcpy）。
- 自检 main ALL OK：序列化确定性 / 缓存命中返回同一数组实例且内容与新鲜序列化逐字节全等 /
  bump 后失效并重建 / BE 列表逐包内容一致。

## 3. 未做与留档

- `FeatureHooks.sendChunkRefreshPackets`（fixlight/刷新路径）未接入本缓存：该路径已有同 tick
  per-call 缓存（按 shouldModify 双键），接入增量收益小、且该路径带监听器语义（PlayerChunkLoadEvent
  经 connection 发送避免重复触发事件——本缓存路径同样经 packetListener.send，语义一致），留档。
- `PlayerList:330` 死亡玩家空 chunk（EmptyLevelChunk）路径未接入：单次/登录且为空 chunk，收益趋零。
- 光照数据缓存（跨 tick）：需光照引擎 per-chunk 变更信号（增量广播只达当时观众，不能作失效源），
  勘察后无干净信号，不做。

## 验证链

compileJava BUILD SUCCESSFUL → 自检 ALL OK → JMH + gc 探针 → rebuildPatches（0241，单补丁无垃圾重命名）
→ 完整 applyPatches BUILD SUCCESSFUL → 全量 test（见 optimizations.md 批次 70 记录）。
