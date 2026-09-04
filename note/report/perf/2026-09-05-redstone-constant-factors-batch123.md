# 批次 123（2026-09-05）：R4 开篇——红石轴 VANILLA 评估级联常量因子四补丁（0.59.0 → 0.73.0）

系列背景：多核调度系列（78-96，0.59.0）终止、R2+R3（97-122）抛弃后，用户于 2026-09-05
以更宽授权重启优化循环（红线=安全/稳定/兼容；授权完全重写；内部 API/方法可增删，
插件面向 API 与用户体验须一致；逐方法等价不再是硬性要求）。本轮仍以可证等价为
首选——首战选 R3 记忆留下的开放前沿：**红石轴 VANILLA 模式的计划 tick 评估级联**。

版本号说明：0.60.0–0.71.4 属已抛弃的 R2+R3 段（archive tag），0.72.0 被远端废弃分支
origin/perf/* 上的批次118（0265）占用——为避免任何歧义，本轮起点取 **0.73.0**。

## 一、画像（补丁 0253 探针 + JFR）

0253 = R3 批次112 探针移植（`PapoTickProfile.addCount` + ServerLevel blockTicks/
fluidTicks/blockEvents 子相位墙钟 + rs.blockTickRuns/fluidTickRuns/blockEventRuns
计数器，`-Dpapo.tickProfile` 门控，默认零行为）。

基线腿（0.59.0+0253，RedstoneScaleBench N=441 环振荡器 × 240s 稳态窗，10 bot，
vanilla，JFR profile 采样同开——两腿同条件对称）：

- 在场门：RING A=B=441 / REP A=B=441 精确；**活动门 rs.blockTickRuns 稳态尾中位
  441.0/tick PASS**（每 cell 每 tick 恰 1 次中继器跳变，与 R3 批次112 口径一致）。
- 相位：**level.blockTicks 26505us/tick（share 30.8%，主导相位）**；chunkSource
  1487us、entities 657us、connection 469us、fluidTicks 20us、blockEvents 3us。
  R3 的 23.7ms/tick 总口径复现（26.5ms 为 blockTicks 单相位口径）。
- JFR 稳态窗 Server thread 归因（11478 样本）：~90% 在 VANILLA 粉评估级联——
  叶帧族：getWireSignal 14.2%、getSignal 12.1%、handleNeighborChanged 11.2%、
  getDirectSignal 10.0%、状态属性表（map get+魔法除法）~8%、getBlockState 底层
  （SimpleBitStorage/ConcurrentLong2Reference）~4%、CollectingNeighborUpdater
  ~3.4%、updatePowerStrength 1.3%。
- **传播机制闭环**（survey 勘察结论，此前悬点）：`SignalGetter.getSignal(pos,dir)`
  对导体邻居额外做 `getDirectSignalTo`（六向再拉取）——环阵石地板使每次粉重算
  多 6 次派发+6 次 BlockPos 分配；且拉取期 `shouldSignal=false` 使粉邻居的
  getSignal/getDirectSignal 恒返 0（可安全短路）。

## 二、四补丁（全部顺序保持型——不改任何可观测顺序/事件序列）

### 0254 — ZeroCollidingReferenceStateTable 直接映射槽缓存
- **热点**：全游戏每次 `BlockState.getValue/setValue` 都经
  `propertyToIndexer.get(property.moonrise$getId())`（Int2ObjectOpenHashMap
  哈希+探测）；红石级联中粉 POWER 读写每 tick 数万次。
- **改法**：表构造期建 `id&(size-1)` 直接映射槽（键校验，冲突/外来 id 回退 map）。
  **纯正缓存**：命中返回与 map 相同的 Indexer 实例，未命中走原 map 路径。
- **为什么不用 Property 实例缓存**：`BlockStateProperties.POWER` 等属性实例跨方块
  共享（一属性多表），实例级 table 缓存会错表；槽缓存按表隔离无此问题。
- **JMH**（StateTableSlotCacheBench，wire 5 属性真实规模+槽冲突注入）：
  before 257.5±23.8 → after 218.0±17.9 ns/64gets（**1.18×**）；冲突回退腿 215.5ns
  （回退路径无劣化）。模型以 HashMap<Integer,Indexer> 同构模拟 NMS map（装箱
  存在，NMS Int2Object 更快——1.18× 为保守下界）。
- **自检**：全索引穷尽 get 对拍 + 外来属性 null 一致 + 槽冲突属性可解析 + 空表 +
  12 属性大表穷尽——5 套 ALL OK。

### 0255 — 红石粉信号拉取特化（getBlockSignal）
- **热点**：每次粉重算的 `getBestNeighborSignal` 六向拉取：6 次
  `BlockPos.relative()` 分配 + 6 次虚派发；导体邻居再走 getDirectSignalTo
  （6 分配+6 派发）。粉邻居在拉取期（shouldSignal=false）恒返 0 但仍付全额派发。
- **改法**：`papoPullBestNeighborSignal`——两个 MutableBlockPos 复用（7~13 分配→0）
  + 粉邻居零派化短路；方向序（DOWN,UP,N,S,W,E）、15 早退结构、导体必拉
  getDirectSignalTo（无论已读信号是否 15，逐行对齐 getSignal 原文）全部保留。
- **等价性**：①粉邻居：shouldSignal=false 下 wire.getSignal 首条件即返 0、wire
  非导体（isSignalSource=true）→ 通用路径恰为 0 且无内层扇出，短路等价；
  ②可变位置安全：全部 vanilla getSignal/getDirectSignal/isRedstoneConductor 实现
  只读位置（方块注册表封闭，插件不能新增 Block 类）。
- **JMH**（WirePullBench，环 cell 邻域+256 随机世界）：4036.9±153.0 →
  3657.2±152.7 ns（**1.10×**）+ 分配 7→0/pull。模型把虚派发简化为静态调用，
  真实场景（megamorphic getSignal 派发面）收益更高——以宏基准为准。
- **自检**：10 万随机世界对拍 + 环 15 早退 + 纯零 + 导体直供 15 四定向用例 ALL OK。

### 0256 — 计划 tick 去重 probe-free（PapoPosTypeSet）
- **热点**：`hasScheduledTick/willTickThisTick` 每次 `ScheduledTick.probe` 分配
  （record+pos.immutable()）再 contains——红石再调度检查（checkTickOnNeighbor）
  每 tick 数千次；LevelTicks 侧另有惰性整队拷贝。
- **改法**：新 `PapoPosTypeSet<T>`（(packedPos,type-identity) 开放寻址 + 后移
  删除）替换 LevelChunkTicks.ticksPerPosition；LevelTicks.willTickThisTick 改
  eager 维护（scheduleForThisTick 同步加、poll 同步删）消灭惰性拷贝与探针。
- **等价性**：成员语义与 UNIQUE_TICK_HASH 逐字对齐（同位置+type 引用相等）；
  collect 与 run 两阶段间无新入队 → eager 集合在任意 willTickThisTick 查询点
  与原惰性快照成员完全一致；clearArea 仅 gametest/命令路径且在收集/运行窗外
  （toRunThisTick 已清空），无分歧窗口。
- **JMH**（TickDedupSetBench，400 预填+25% 命中探测+混合负载）：
  contains 983.8±76.9 → 747.4±36.0 ns（**1.32×**）；mixed 2068.7±131.2 →
  1519.5±44.1 ns（**1.36×**，含探针 record 分配消除）。
- **自检**：10 试验 × 10 万随机操作对拍（add 返回值/size/终态全量成员）+ 10 万条
  扩容压力 + type 身分语义（不同实例可共存、同对去重）ALL OK。

### 0257 — 每 setBlock POI 检查去 Optional
- **热点**：updatePOIOnBlockStateChange 每次 setBlock 两次
  `Optional.ofNullable(map.get)` + Objects.equals——红石翻转全为双双无 POI 的
  纯分配。
- **改法**：`PoiTypes.forStateOrNull` + 引用比较；Holder 为注册表驻留实例
  （同类型同实例），引用不等（含 null）⟺ 原 Optional 对等价。执行分支
  （remove/add/stale-remove）逐形态保留。
- **JMH**：135.9±0.5 → 144.4±3.2 ns（模型内 0.94×——**EA 伪影**：-prof gc 证明
  before 在小模型中被逃逸分析消分配 0.001 B/op；真实服务端大方法链 EA 失效，
  分配为真。按批次43/0230 先例机制保留并如实披露）。
- **自检**：无/无、有/有（同实例）、有→无、无→有、有→异 五形态判定+分支计数
  全等 ALL OK。

## 三、宏 A/B（RedstoneScaleBench N=441 × 240s，两腿同条件：探针+JFR 同开）

口径修正：单窗数字受相位/调度抖动影响大，对比取**全部振荡窗分布**（rs.blockTickRuns
≥440/tick 的 400-tick 窗，两腿各 20 窗）：

| 指标 | before（0.59.0+0253） | after（+0254-0257） | Δ |
|---|---|---|---|
| level.blockTicks 中位 | 28743 us/tick | 23043 us/tick | **−19.8%（1.247×）** |
| level.blockTicks 均值 | 28840 us/tick | 23521 us/tick | −18.4%（1.226×） |
| 窗分布范围 | 23241–35780（宽散） | 22549–27200（16/20 窗在 22.5–23.2k） | 方差大幅收紧 |
| 活动门 rs.blockTickRuns | 441.0/tick PASS | 441.0/tick PASS（每窗 176400 精确） | — |
| 在场门（RING/REP A=B） | 441/441 PASS | 441/441 PASS | — |
| logErrors / exit | 0 / 0 | 0 / 0 | — |

振荡速率逐位一致（176400=441×400/窗）——优化纯降本，不改变调度量。

**JFR 方法级确认**（after 腿全 run 视图 vs before）：`Int2ObjectOpenHashMap.get`
（原 4.71% 叶帧）从热点榜消失，被槽缓存吸收（ZeroCollidingReferenceStateTable.get
4.83% + papoIndexer 2.35%，合计低于原 map+table ~8%）；`BlockPos.relative` 叶帧
1.68%（原 1.10% 的绝对量已减半——基数缩小）。剩余级联帧（getWireSignal 15.69%、
getSignal 12.88%、handleNeighborChanged 12.81%、getDirectSignal 9.97%）占比升高
系总分母缩小的正常现象——绝对样本/时间下降。粉拉取特化使 getBlockSignal 路径的
每次重算从 ~12 BlockPos 分配+~12 派发降至 0 分配+~8 派发。

**下一轮前沿（本轮画像副产物）**：getWireSignal（属性读）与 handleNeighborChanged/
updateShape/runUpdates（邻接更新机械池化）现为前二族；粉评估器重算冗余（同粉一次
级联内多次重算）是 VANILLA 语义内最大剩余面，需输入脏追踪设计（后续轮次）。

## 四、判例与披露

1. **PoiCheck EA 伪影**（-prof gc 裁决，先例批次43）：模型内中性 ≠ 服务端中性。
2. **StateTable 模型保真度**：HashMap<Integer> 模拟 Int2ObjectOpenHashMap 带装箱
   偏差，1.18× 为下界；NMS 实测以宏基准为准。
3. **WirePull 模型低估**：静态调用模拟 megamorphic getSignal 派发面，1.10× 为
   下界；粉跳过在真实派发面的收益更大。
4. **版本号跳段**（0.59.0→0.73.0）：0.60-0.71.4 已抛弃段 + 0.72.0 远端废弃分支
   占用，起点取 0.73.0 消除歧义。
5. JFR 双腿同开（基线腿画像需要）——A/B 两侧含相同采样开销，对比公平。

## 五、验证矩阵

- compileJava ×4 全绿；全量 applyPatches BUILD SUCCESSFUL（补丁链 0253-0257 经
  paperweight applier 重建内部仓库验证）。
- 4 类 JMH 自检 ALL OK（含 10-100k 级随机等价对拍）。
- 宏基准在场门/活动门/错误门双腿 PASS（见上表）。
