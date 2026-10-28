# R52 复合负载轴（机械+实体+方块交互多域同窗叠加）分配普查 → 判负闭合（批次193）

- **轮次**：R52（批次 193）｜分支 perf/memory-r5｜版本保持 **0.110.0**（无代码变更）
- **轴**：复合负载轴——R50 指令候选（当时选了存活归因维度）。新证据等级=**多域同窗叠加**：R24-R51 十域普查全部是单域隔离窗，**跨域交互面从未成像**。
- **结论**：**判负闭合（四支柱）**。复合差分 ≥20 阈面全数归因既有十域清单，零未归因新面；**叠加是线性的（可加）非超线性**——挖掘链面在旋涡叠加下与 R48 隔离值恒等或更低；**跨域交互候选（SectionBlocksUpdate 批处理交错/光照并发重传播/chunk 脏标记竞争）全在噪声底（0-3 events）**。
- **工件**：`benchmark/CompositeCensus.java`、`benchmark/alloccensus-r52composite.sh`、`benchmark/results/alloccmp-20261013-004350-A-110nat/`、`benchmark/results/alloccmp-20261013-004959-B-110cmp/`

## 1. 动机与选轴（R38 判例：新轮须新证据等级）

十域普查（流送/盘载/驻留/实体+保存/机械负载/物品旋涡/方块交互/玩家生命周期/存活归因维度/高并发流送）全部在**单域隔离窗**成像。真实繁忙服形态是多域并发：漏斗机械+物品旋涡+玩家挖掘+区块簿记同时打在同一批节/同一 tick 循环/同一网络栈上。隔离普查结构性看不见的**跨域交互面**：

- 多变更者并发打同一节的 SectionBlocksUpdate 批处理交错（挖掘的方块变更与漏斗邻近节的 BE 变更汇入同一批）；
- 光照引擎并发重传播（挖掘穿孔与农场放置的光照增量竞争同一 propagate 队列）；
- chunk holder 脏标记/保存簿记的跨域竞争；
- 混合包型（BLOCK_UPDATE+ENTITY 动同步）广播批处理。

这些面只有在叠加窗才可能浮现——隔离窗里各自的 counterpart 不在场。R51 已证高并发单域（流送）零新面；本轮补上**多域并发**这一格证据矩阵。

## 2. 方法：CompositeCensus（R48 立方 + R47 农场同窗叠加）

- **bot 零改动**：复用 `joinClimbSpreadDigAndDisconnect`（批次189）。12 creative bot 爬升 y=200 + 径向撒布 150s 定点。
- **挖掘域（R48 原样）**：每 bot 石立方 11×15×11（bx±5, y[195,209], bz±5）内保守可达球 793 格 × 100ms/挖。
- **机械+实体域（R47 几何整体 z+8 平移）**：漏斗排 6（bx-6..bx-1, 200, bz+8）→双箱+漏斗柱 150/列×6（~21 items/s/bot 吸排→45-75s drain 相）+石台 10×10 召唤格 3 件（永驻站立相）。**空间隔离判据：挖掘候选 |dz|≤5，农场在 z+8 → 候选域与农场零交集，bot 无法破坏自己的负载源**（域间零物理干扰=差分可归因的前提）。
- **时间线**：fill+summon 14,461 命令（burst 实测 2.5s）→ marker → +8s 探针① → **+12s loadWarmed=JFR 60s@1ms 锚点（同刻 latch：挖掘 M+12..91 与 drain M+0..~75 同窗全叠加）** → +90s 探针② → +150s 驻留测量（bot 全程在线=区块票据，R48 判例）。
- **配对设计**：A 腿=自然驻立基线（零 fill 零挖掘，R47 A 形；markerWall=fillWall 同偏移自锚）；B−A=复合窗全谱，逐面归因既有清单，未归因面=交互面候选。两腿时间线恒等（loadWarmed t=182.0s vs 185.2s≈burst 差）。
- **门**（腿脚本内建，R50 端口独占门+批次186 工具链纪律）：TRACE≥12+零类版本错误+exit 0+B 腿 DIGDONE≥12·digsTotal≥4,800·air×3·ctrl×2·漏斗 Items·chest cobblestone·实体在场×2；A 腿 digsTotal=0。

## 3. 探针轮（协议风险前置，全绿）

首轮 B 形态全量 12 bot（spread=120s）：burst 2.5s（万级命令服务器侧处理耗时不可假设判例——本轮实测远小于 120s latch 预算）；探针① ctrl=stone+漏斗 Items 非空（5 槽实物栈=drain 活跃中）+实体在场；探针② 首 3 挖全 air+ctrl 完好+chest 收 cobblestone（24 个，全链完成）+实体在场；DIGDONE×12、digsTotal=9,501（≈全可达球耗尽=农场共存对挖掘零干扰）、logErrors=0、exit 0。**复合时间线共存成立**。

## 4. 双腿门与窗总量

| | A-110nat | B-110cmp |
|---|---|---|
| 门 | traces 96·vererr 0·unknown 0·exit 0·digs **0** | traces 24·vererr 0·unknown 0·exit 0·digdone 12·**digs 9,516**·air 3·ctrl 2·hopperItems 1·chestCobble 1·itemsPresent 2 |
| events / raw | 2,217 / 11,782MB | 13,067 / 26,664MB |
| heap（GC×2 后） | 610,497KB | 987,719KB |
| 直方图锚 | ItemEntity 210·HopperBE 46 | ItemEntity 3,723·HopperBE 118（72 农场+46 基线）·ItemStack 6,332 |

窗内 ~7,200 挖（12 bot×10/s×60s）+ drain 全程 + 站立相 3,600 实体在场——四域（机械/实体/方块交互/玩家驻立）同窗全活跃。

## 5. 发现与逐面判定（B−A 全站点差分，301 非零站点）

### ≥20 立信阈面：全数归因既有清单，零未归因新面

| 族（Δ=B−A trips） | Δ | 归因 |
|---|---|---|
| 旋涡物理结果对象（AABB.inflate +471·Vec3.multiply/scale +454·Vec3.add +379/+184·makeBoundingBox +434·setBoundingBox +135·setPosRaw/move/applyGravity/setFluidMovement/collidedWithFluid） | ~2,200 | **R47 旋涡域本体维持**（结果对象族，每 item tick 运动学） |
| 碰撞查询（CollisionUtil 族 10 站点） | ~1,100 | Moonrise 上游维持（R47 同族） |
| 流体（FlowingFluid.getFlow +298·Fluid.getAABB +154·LiquidBlock.getCollisionShape +48） | ~500 | 流体数据/结果面维持（R42/R47） |
| 网络/tracker 回声（DirectByteBuffer.slice +916·RegistryFriendlyByteBuf +511·copyOfRangeByte +452·sendPacket +86·sendChanges +83·jctools +81·netty newBuffer +27） | ~2,100 | 网络/tracker 代价面维持（R44 判负+netty 上游；14.4k 实体 spawn/destroy/位置同步+挖掘包回声） |
| **JFR 自观测族**（InetSocketAddress.toString +812·Inet4Address +435·InetAddress +387·StringBuilder +513·ThreadImpl +110） | ~2,250 | **R41 剥离判例**（networkStatFor 每包 toString，随包数缩放——复合窗包数=实体同步+挖掘 ack 叠加，自观测权重如实放大，剥离后归因零残留） |
| **R47 收割替代面形态**（papoVisitCornersInDirection +96·betweenClosed lambda +30·BlockPos$4 +15·StepBasedCollector.recorded +45·MutableBlockPos.immutable(recorded) +46·listIterator +95·ArrayDeque.iterator +61·ReferenceOpenHashSet.spliterator +71·getFurthestCorner +48） | ~510 | **0298 收割后替代形态在复合负载下零回流**（cursor 契约下限的既定残余，R47 配对腿 73 trips 同族；又一零回归确认） |
| inside-block 走查宿主（checkInsideBlocks +116/+40·Vec3.relative +69·getOnPos/atY +119·checkSupportingBlock +40·findSupportingBlock +75·Movement Optional +41） | ~400 | R47 域本体（移动实体支撑面） |
| spark 采样器族（6 站点） | ~190 | 上游观测面（R41 判例剥离） |
| 生成族（spawnForChunk +60·getRandomPosWithin **−75**·getMobsAt +14·WeightedList +17 混号） | 混号 | 工作量/相位噪声（RNG 绝对红线域，R39 分开记账） |
| 并发基建（AQS +28·BalancedPrioritisedThreadPool +20） | ~50 | Moonrise/concurrentutil 上游（负载随活动量缩放） |

### 挖掘链 in-stack 差分：叠加下与隔离值恒等或更低（无放大）

| 面 | B | A | Δ | R48 隔离值（5,760 挖） | 本轮 ~7,200 挖线性预期 |
|---|---|---|---|---|---|
| LightEngine | 11 | 3 | **+8** | +14 | ~+18 → **实测低于线性** |
| destroyBlock 机器 | 3 | 0 | +3 | +4 | +5 → 恒等 |
| handleBlockBreakAction | 3 | 0 | +3 | +3 | +4 → 恒等 |
| playerWillDestroy | 0 | 0 | 0 | +3 | 采样底 |
| SectionBlocksUpdate | 0 | 1 | −1 | +2 | 噪声底 |
| BlockUpdatePacket/Ack | 0 | 0 | **0** | 0 | 恒等 |
| CraftBlockState/BlockBreakEvent | 2/0 | 2/0 | 0 | ~+2 | 恒等 |

**关键新证据：无任何挖掘面在叠加下超线性增长**——若存在跨域批处理/重传播放大通道，LightEngine/SectionBlocksUpdate 应高于线性预期；实测全部 ≤ 线性（LightEngine 甚至低于隔离值=采样波动带内）。

### 交互候选结构：全在噪声底

| 交互候选 | B | A | Δ |
|---|---|---|---|
| SectionBlocksUpdate（多变更者批处理交错） | 0 | 1 | −1 |
| ChunkHolder（跨域脏标记/簿记竞争） | 5 | 2 | +3 |
| HopperBlockEntity（机械域 tick） | 3 | 0 | +3 |
| suckInItems（吸取链） | 2 | 0 | +2 |
| addEntity（实体入册跨域竞争） | 1 | 0 | +1 |

系统性跨域结构（批处理/脏标记/光照合流）**不产生可测分配**——它们记账在原始/身份结构上，不是分配站点。交互面在本轴的存在性证据：**阴性**。

## 6. 判负闭合论证（四支柱，R48/R49 同型）

1. **零新机械面**：≥20 阈面全数归因（旋涡本体/碰撞上游/流体/网络回声/自观测/收割替代形态/生成族混号），逐条落 R42/R44/R47/R48/R41 判例清单；301 非零站点全谱扫描无一漏网。
2. **叠加线性（本轴独有支柱）**：挖掘链全 face 在旋涡叠加下与 R48 隔离值恒等或更低（无放大通道）；旋涡族与 R47 隔离窗形态逐族对应（sendChanges 84 vs 114=drain 相位带内）。**复合=可加和，隔离普查的闭合结论在叠加形态下成立**——这正式回答了"隔离窗闭合是否足以外推真实复合负载"的方法论问题。
3. **交互面存在性阴性**：批处理交错/光照合流/脏标记竞争候选全在 0-3 噪声底——无收割对象。
4. **量级与语义面**：窗内复合分配主体=旋涡域 young 短命对象（raw 26.7GB 全窗、heap B +377MB 为物品实体/农场活态语义驻留=R27 画像域），语义/上游面恒等可证性失败均已在前十域逐条裁决（R42 流体/R44 网络/R47 结果对象与 cursor 契约/Moonrise 与 netty 上游）。

## 7. 判例

1. **复合叠加线性判例**：多域同窗差分中各域面与隔离窗值恒等（±采样带）→ 隔离普查的判负闭合可外推复合负载；反之若见超线性面（如 LightEngine ≫ 线性预期）=交互通道存在性正证据，须立项。本轮为前者的首例定谳。
2. **多域 harness 空间隔离设计**：域负载源几何置于对方作用域外（农场 z+8 vs 挖掘候选 |dz|≤5）= bot 无法破坏自身负载源、域间零物理干扰，是复合差分可归因的前提；配合 bot 零改动复用（joinClimbSpreadDigAndDisconnect），复合轮的协议风险只剩时间线共存（探针轮一次覆盖）。
3. **自观测族随复合包数如实放大**：InetSocketAddress.toString 族 Δ+812（R48 挖掘窗 +56、R47 旋涡窗独立存在）——复合窗包数=各域包数之和，自观测权重=可加和；剥离判例（R41）在复合窗同样适用且必要（不剥离则该族假面霸榜第一）。

## 8. 工件清单

| 工件 | 说明 |
|---|---|
| `benchmark/src/papo/bot/CompositeCensus.java` | 复合普查 harness（bot 零改动；14,461 命令 fill+summon；双探针；jcmd 测量） |
| `benchmark/alloccensus-r52composite.sh` | 双腿脚本（端口独占门/J21 编译纪律/loadWarmed 锚 JFR/复合正证据门） |
| `benchmark/results/alloccmp-20261013-004350-A-110nat/` | A 腿 JFR+heap+直方图 |
| `benchmark/results/alloccmp-20261013-004959-B-110cmp/` | B 腿 JFR+heap+直方图 |
| `benchmark/results/alloccmp-A-sum.txt` / `-B-sum.txt` | 聚合 top-40 |

分配普查第十一域（复合负载/多域叠加）成像→判负留档。**证据矩阵补全：单域隔离×10 + 高并发×1 + 多域叠加×1 全部闭合，隔离外推性由本轮直接证明。**
