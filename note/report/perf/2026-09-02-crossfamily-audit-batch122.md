# R3 审计轮（批次 122）——跨族交互组合审查 + chunk 包缓存常驻内存面

> 日期：2026-09-02。分支 `perf/multicore-r3`，输入版本 0.71.3（补丁链至 0268，
> 整链已逐补丁审计闭环于批次 119/120/121），产出 0.71.4（新增补丁 0269 + PapoDiag jar 修复）。
> 本轮按批次 121 报告预设的后续方向执行：**逐补丁审计已闭环，转向跨族交互
> （组合行为≠单件行为之和）与 PapoDiag 运行时**。goal 重点（多线程对延迟/流畅度
> 的影响、长时间高负载多用户稳定性、未信任输入、功能完整性）继续贯穿。

## 一、审计范围与结论总表

| # | 审计面 | 方法 | 结论 |
|---|---|---|---|
| 1 | 0217 零拷贝帧 × 0260 发送批量化 × 0223 flush 缓存（叠合字节流出站栈） | 逐级推演 headroom/memo 属性协议、排水 FIFO、断连/协议换序/关停交错 | **闭合，无新缺陷** |
| 2 | 0268 locale 门控 × 0241-0248 memo 族 × 0186 物品混淆 session（逐连接差异源全量再证伪） | 六个 encode-memo 武装包逐一核查组件序列化路径与 session 作用域 | **闭合**（PlayerInfoUpdate 之外五包均无 locale 面；物品内嵌组件被 `DONT_RENDER_TRANSLATABLES` 门控；混淆 session 在 memo 路径上无 `start()`，逐连接不变） |
| 3 | 0242 共享 chunk 包 × 0241 缓存 × 0203/0220 k-nearest（快照性与未信任输入） | 构造时急切快照核验、BE 不共享契约、anti-xray 分路、`desiredChunksPerTick` clamp | **闭合**（快照不可变；BE 内容变更不 bump 版本故 BE-present 不共享；`shouldModify` 强制逐观众路径；客户端输入 clamp 到 [0.01,64] 且 NaN 守卫） |
| 4 | 0264 boot 预热 × 0267 有界预取 × 0249-0251 登录链（交错与类初始化） | clinit 依赖图、`awaitPending` 有界性、协议换序时序与挂起窗口重叠分析 | **闭合**（clinit 不依赖主线程无死锁面；`awaitPending` 60s 超时放弃=真上界；挂起窗口（tick 期/placeNewPlayer 期）与 configuration→play 换序阶段不重叠，configuration 玩家不在 PlayerList） |
| 5 | **chunk 包双缓存常驻内存**（0241/0242 的规模面，既往审计未量化） | 常驻集构成逐项（buffer/光照/memo 段）× 驱逐语义核查 | **发现 F1，已修复（0269：末观众驱逐 + chunk 级缓存同步释放）** |
| 6 | PapoDiag 运行时（高负载误报/报告稳健性/长时安全） | 看门狗线程语义、报告分区失败模式、索引/文件增长逐项 | **发现 F2/F3/F4，已修复（jar 重编）** |
| 7 | 白名单包延迟语义 × 0260 批量化（goal：用户交互流畅性） | 与 vanilla Paper 挂起窗口语义逐点对照 | **闭合**（vanilla 挂起窗口内写而不 flush，客户端看到字节的时刻本就是 tick 末；Papo 只推迟 write/encode 时刻，不改到达时刻。无延迟回归面） |
| 8 | 0225 wire 计量 × 0260（长时/可见性） | AtomicLong 语义、tick 汇总线程契约 | **闭合**（原子无丢更新；2^63 字节不可达；秒级归因误差为计量装饰性） |
| 9 | 探针族 0252-0263 × 业务路径 | `PapoTickProfile.ENABLED` static final 常量折叠确认 | **闭合**（默认关闭时分支被 JIT 消除，已在批次119 长时安全面覆盖） |

## 二、逐面记录

### 2.1 出站栈叠合（0217×0260×0223，闭合）

单件审计（批次 120/121）覆盖了各级正确性；本轮专门验证**组合不变量**：

- **headroom/memo 属性协议**：`PacketEncoder` 只在 prepender 为真 Varint21 时发布
  headroom 标记；`CompressionEncoder.write` 身份匹配则消费（`getAndSet(null)`）两级
  属性、不匹配/外来 buffer 则双双清理；`Varint21LengthFieldPrepender` 双向同样清理。
  关键对抗问题——**「本包非 carrier 时陈旧 memo 是否会被误用」**：陈旧 memo 若持有
  活动段，须其产生遍历（压缩级）未被消费才可能残留；而压缩级只要在管线内必然逐遍历
  `getAndSet(null)`；无压缩期产生的 memo 段恒为 null（段只由压缩级写入）→ 任何后续
  身份匹配遍历拿到 null 段 → 回退新鲜压缩。**无撕裂路径**。协议换序（terminal 包
  encoder finally 换装）是编码时点而非发送时点，FIFO 排水保证先批先编码。
- **0260 排水 × 协议换序**：挂起窗口仅存在于（a）tick 期 PlayerList 内玩家、（b）
  placeNewPlayer 期（play 阶段）。configuration→play 换序发生在 ServerConfiguration
  阶段（玩家不在 PlayerList、`suspendFlushingOnServerThread=false`、flush=true 直发）
  → 换序包永不入批。**窗口与换序不相交**。
- **断连/关停**：排水任务在通道关闭后仍安全（`doSendPacket` 首行 `isConnected` 回退，
  finish 钩子无重写者——批次119 已证）；批数组随 Connection 回收。
- **异步白名单超越**：批次108 已披露（pong 类无次序契约），本轮在 2.7 面补充：其
  客户端可见时刻本就受 tick 末 flush 约束，超越不产生可观察差异。

### 2.2 locale/混淆逐连接差异源全量再证伪（0268 完备性，闭合）

批次 120 修复了 PlayerInfoUpdate；本轮对**全部六个 encode-memo 武装包**独立重验
（不采信补丁注释，锚定应用树）：

| 武装包 | 组件序列化 | locale 面 |
|---|---|---|
| ClientboundUpdateTagsPacket | 无 | 无 |
| ClientboundSectionBlocksUpdatePacket | 方块位置+状态 varint | 无 |
| ClientboundUpdateRecipesPacket | 含 ItemStack（custom_name/lore）| **无**——`ItemStack.encode` 全程 `DONT_RENDER_TRANSLATABLES=true`（ItemStack.java:211-217），物品内嵌组件不做服务端本地化 |
| ClientboundPlayerInfoUpdatePacket | 直接组件字段 | 有，0268 `papoLocaleDependent()=true` 门控 ✓ |
| ClientboundRegistryDataPacket | 注册表 NBT | 无 |
| ClientboundLightUpdatePacket | 光照字节+位置 | 无 |

- **混淆 session 交叉（0186×0245）**：`ItemObfuscationSession` 为 ThreadLocal 带
  `ObfuscationLevel`，但 `start(level)` 仅存在于 SetEquipment/SetEntityData 编码内且
  try-with-resources 恢复；recipes 编码路径无 session 启动 → 恒见默认 session →
  **逐连接不变**，memo 不冻结混淆输出。
- **regime 翻转**：`hasAnyTranslations()` false→true→false 运行时翻转下，段/快照的
  存储与回放共用同一当前 regime 门（`!localeDependent || !hasAnyTranslations`），
  (b) regime 产生的字节永不入库 → 回放只在确定性 regime 下命中。广播中途翻转与
  vanilla 各时刻行为一致。

### 2.3 共享 chunk 包快照性与输入面（0242×0241×0203/0220，闭合）

- 构造即急切快照（`ClientboundLevelChunkPacketData` 提取 buffer + BE NBT 列表，
  vanilla 同构）→ 事件循环编码只读不可变数据，无主线程变更撕裂面。
- **BE-present 不共享**：BE 内容变更（告示牌文字等）不 bump `papoChunkDataVersion`
  （只有 BE 增删 bump），共享实例会向后来观众嵌入陈旧 BE NBT 且无增量纠正包 →
  0242 的 `getBlockEntities().isEmpty()` 门正确堵死该面。
- **anti-xray 分路**：`shouldModify` 为真时强制 vanilla 逐观众构造（ready-flag 协议
  依赖逐包实例），共享路径只在无混淆时启用。
- **k-nearest scratch（0220）**：单调用点主线程、`floor>=1` 由 `!(batchQuota<1.0F)`
  保证；`desiredChunksPerTick` 来自客户端 `ServerboundChunkBatchReceivedPacket`
  ——**未信任输入已清洗**：NaN→0.01、clamp [0.01,64]（PlayerChunkSender.java:204），
  与 vanilla 语义一致。
- 光照新鲜度：批次 120 已披露同 tick 增量光纠正自愈，本轮无新证据推翻。

### 2.4 登录链交错（0264×0267×0249-0251，闭合）

- 0264 预热的两个 `<clinit>` 只构建静态查表/探测 intrinsic，不依赖主线程 → init 锁
  等待有界（最坏=预热前原成本），无死锁。
- 0267 有界等待（60s）后的同步回退中 `awaitPending` 亦 60s 超时放弃——**总停顿真有界**
  （最坏 60s+60s+同步读，仅病理 IO 池场景）。
- 预取 map 生命周期（孤儿清理/旧名回退）批次119 已修，本轮复核无新路径。

### 2.5 chunk 包双缓存常驻内存（**F1，修复=0269**）

**发现**：0241/0242 的缓存失效只「标记无效」，不释放引用，且无「末观众离开」驱逐：

- `LevelChunk.papoPacketBufferCache`：失效后陈旧 buffer（典型 10-40KiB）持有至区块
  卸载；`ChunkHolder.papoSharedChunkPacket`：BE-empty 已发送区块整包常驻（光照快照
  +memo DEFLATE 段，合计典型 40-115KiB/区块）持有至卸载。
- 规模：多玩家大视距服务器（如 200 人 r=10，唯一已加载区块数万）在无人观看的已加载
  区块上（出生点常驻/强制加载/卸载延迟尾迹/视距重叠边缘）线性累积 **GB 级**常驻——
  vanilla 为零（逐发送瞬态）。长期高负载场景（goal 指定重点）为真实 OOM/换页风险面，
  既往审计只验证了正确性与 CPU，未量化过此面。

**修复（0269）**：`moonrise$removeReceivedChunk` 在移除后 `playersSentChunkTo` 归零时
（主线程，`TickThread.ensureTickThread` 契约），清 holder 共享包与版本戳，并同步释放
chunk 级序列化缓存（buffer/heightmaps/版本戳）。在途发送不受影响（批内持有各自不可变
包引用）；新观众到达时按版本门纯函数重建，字节逐位一致。有观众期间的工作集保留
（这是该优化的收益本体，规模与 vanilla 发送成本同阶）。

**验证**：applyPatches 链重建 ✓ + compileJava ✓ + SmokeJoinVerify 10/10（join/存档
三件套/干净关机）✓ + WalkGen 128s 连续边缘 churn（数百次驱逐触发，tps=20.00、
dur p99=2.25ms、零错误）✓ + ChurnStabilityBench 4 slot×36 个完整驱逐→重观看→重建
周期（joinFails=0，dur p99=2.84ms，零错误，gate PASS）✓。

### 2.6 PapoDiag 运行时（**F2/F3/F4，修复=jar 重编**）

- **F2（报告分区连带丢失）**：`report()` 从看门狗线程调 `Bukkit.getOnlinePlayers()`/
  `getPlugins()`，join/quit 恰好交错时可抛 CME——原实现单 try 包裹整份报告，players
  段失败会连带丢弃**已构建完成的主线程栈**（停摆现场是报告的核心价值）。修复：分节
  独立 try/catch，失败节降级为 `<unavailable: X>` 标记。
- **F3（报告文件无界增长）**：持续病理停摆下每 5s 追加 2KiB（~17MB/天，与批次119
  修复的禁用后伪停摆同量级），长期运行磁盘无界。修复：>8MiB 轮转一次
  （`stall-report.txt`→`stall-report.old.txt`，旧 .old 删除；删除失败则继续追加原文件
  ——绝不因轮转失败丢报告）。
- **F4（tick 历史索引回绕）**：`tickHistoryIndex` int 自增 ~2^31 tick（约 3.4 年）后
  回绕为负，`% length` 抛 AIOOBE 且终止定时任务 → 心跳永久停止 → 看门狗从此每 5s 记
  伪停摆（与批次119 修复的形态相同）。修复：读写两侧 `Math.floorMod`。
- 看门狗阈值（150ms/25ms 检查/5s 去抖）在合法高负载下的行为复核：>150ms 主线程间隙
  对玩家即真实停摆，捕获属设计目的而非误报；负载本身不触发（心跳由 tick 更新）。

### 2.7 白名单包延迟语义（闭合，goal：交互流畅性）

canSendImmediate 白名单（chat/title/sound/particles/bossbar/player-info/pong）的
「立即」语义是**队列旁路**而非提前出网：挂起窗口内 vanilla Paper 同样 write 而不 flush，
客户端看到字节的时刻都是 tick 末统一 flush。Papo 批量化只把 write+encode 推迟到排水，
**不改任何字节到达时刻**；256 中途排水只会让写入更早。keepalive 批内延迟 ≤1 tick，
远低于 30s 超时。无回归面（与批次119 2.1 面结论互证）。

### 2.8 WalkGen harness 地形依赖判例（工具面，非服务器缺陷）

本轮 WalkGen 3 bot × 150s 全部被 "Invalid move player packet received" 踢出——
bot 以固定 y=100 合成坐标直线行走（不响应服务器位置纠正），撞入高于 y=100 的地形后
触发 vanilla 移动验证，~128s（≈205 格）处三 bot 在 2s 窗口内相继被踢。**与 0269 无关**
（移动验证与包缓存不相交；同一 jar 上 SmokeJoin 10/10、Churn gate PASS）。判例：
该 harness 的通过性依赖所行走扇面的地形剖面，未来用作回归门时应在低幅地形世界或
缩短窗口，或让 bot 遵循纠正包。

## 三、判例沉淀

1. **逐补丁审计闭环 ≠ 组合审计闭环**：memo 属性协议（2.1）与 locale 门控完备性（2.2）
   的关键不变量只在「族间叠合」处可被证伪——单件审计的「verified」声明在组合处须
   重新锚定应用树推导。
2. **缓存类优化必须回答「何时释放」**：失效标记（version 门）保证了正确性，但引用
   释放决定长期常驻规模；高负载审查必须给缓存的常驻集一个显式上界论证（谁在观看/
   持有多久/无观众时如何回收）。
3. **诊断工具的失败模式与被诊断对象同优先级**：PapoDiag 的报告在最高负载（CME 竞态）
   恰好最易丢失核心分区——诊断工具按「最坏时刻可用」标准设计。

## 四、产物

- 补丁 `0269-Papo-batch-122-evict-shared-chunk-send-caches-o.patch`
  （ChunkHolder + LevelChunk，+31 行）
- `benchmark/papodiag/PapoDiag.jar` 重编（分节容错/轮转上限/floorMod，javap 验证）
- 版本 0.71.3 → 0.71.4（gradle.properties）
- 验证链：applyPatches ✓ → compileJava ✓ → createPapoJar（Papo-1.21.11-0.71.4.jar）✓
  → SmokeJoinVerify ✓ → WalkGen churn 段 ✓ → ChurnStabilityBench gate PASS ✓
