# R4 红石家族对抗审计（批次 132，2026-09-08）

> 范围：R4 全部新增面——补丁 0253-0266 + 直提交（批 123-131 的
> PapoTickProfile 子相位/计数、0254 槽位缓存、0255 信号拉取、0256 调度刻去重、
> 0257 POI 检查、0258-0266 粉脏追踪族）。R1 面（≤0252）已在 R3 审计轮
> （119-122）覆盖，其三个已接受缺陷（无界 join/locale 冻结/区块包缓存常驻）
> 按 2026-09-03 用户决断维持现状，本轮只核对未复发不重报。
> 方法：逐文件读应用树 + 对 vanilla 语义的构造性等价证明 + 实跑编译与模型自检。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | 标记钩子完备性 | **闭合** | 见 §1 |
| 2 | 输入闭包正确性（18 偏移 + 轴向 ±2 + 自排除 + 角位不可达） | **闭合**（独立重证明） | 见 §2 |
| 3 | 跳过放置点（neighborChanged 入口、onPlace 旁路、移除路径无条件评估） | **闭合** | 见 §3 |
| 4 | 0130/0131 列固定 chunk 解析与 Level.getBlockState 等价 | **闭合** | 见 §4 |
| 5 | 0265 同 chunk 快路径（缓存有效性、requireChunk=false 语义） | **闭合** | 见 §5 |
| 6 | 并发与长期运行（条带锁、gen 线程标记、内存界、探针） | **闭合** | 见 §6 |
| 7 | 不可信输入面 | **零新增** | 见 §7 |
| 8 | 数据操作（存档/序列化） | **无格式影响** | 见 §8 |
| 9 | 功能完整性（表述 vs 实现） | **一致**；1 处注释与实现相反（已修） | 见 §9 |
| 10 | 构建与自检 | BUILD SUCCESSFUL（exit=0，--rerun-tasks 全量）；WireDirtySkipBench 自检 ALL OK | §10 |

## §1 标记钩子完备性

四个生产钩子（应用树逐一核对）：

1. `LevelChunk.setBlockState:446`——节段提交点、先于该方法的任何派发
   （onPlace/affectNeighborsAfterRemoval 内联跟随）；门 =
   `isClientSide() || isTickThread()`。FULL LevelChunk 的服务端写入全部在
   tick 线程（Moonrise gen 写 proto/region chunk，不下探此路径）；gen 线程
   对 region 内 FULL chunk 的直写被 `WorldGenRegion.setBlock:352` 的第二钩子
   覆盖（region 为 reader → 通用逐读路径，区域界内安全）。
2. `WorldGenRegion.setBlock:352`——gen 线程写（结构粘贴邻接已加载区块粉），
   `blockState != state` 时经 region reader 标记进存活 Level 的追踪器。
3. `DiodeBlock.updateNeighborsInFront:194`——模拟量输出无转移刷新漏斗。
   **覆写面核查**：全树仅 `ObserverBlock` 覆写（不调 super）且不带标记——
   证明其正确：观察者 tick 两个分支（POWERED 置位/复位）均先
   `level.setBlock`（→钩子 1 已标记闭包）再派发（tick:58/65 → 69 行）；
   移除路径的派发同样跟随移除转移（钩子在节段提交点先行）。其
   `updateNeighborsAtExceptFromFacing` 触达的 pos−2f 位在 pos−f 非导体时
   对该粉不可达（见 §2），clean 跳过是构造性冗余。
4. `Level.updateNeighbourForOutputSignal:1984`——方法体内部打标，全部调用方
   （Containers/ItemFrame/CommandBlock/DetectorRail/CopperGolemStatue/
   CreakingHeart/CopperGolem 等）自动覆盖。

无转移信号源枚举核对：目标方块（箭命中→OUTPUT_POWER 置位）、检测铁轨
（矿车→POWERED）、日光传感器（tick→POWER）、讲台/雕像（状态位）、比较器
容器内容（钩子 3/4）——全部落在转移或双漏斗内。Paper 服务端方块集=vanilla
（插件不能注册方块），枚举闭。

## §2 输入闭包（独立重证明）

粉 W 的评估读集 = W 的 6 面（信号+导体）+ 各面的扇出读 F±d（直通信号）+
变体列（水平邻位 ±竖直）。pos ∈ W 读集 ⟺ W ∈ {pos 的 6 面} ∪
{pos 的 12 棱} ∪ {轴向 ±2 且 pos±1 为导体} ∪ {W=pos（自，读值恒 0——
shouldSignal=false 下 getSignal/getDirectSignal 均 0，批 127 自排除成立）}。
角位 (±1,±1,±1)：W 的任何读位（面/扇出/变体）到 W 的偏移最多两个非零轴且
值为 ±1，逐一验证不存在三非零轴偏移 → pos 不可能出现在角位 W 的读集中。
代码 `SCAN_D*`（6 面+12 棱）+ 轴向探测（6 向，导体门）与该集合逐位相等。

## §3 跳过放置点

- 入口：`RedStoneWireBlock.neighborChanged:494-499`，门 =
  `redstoneImplementation == VANILLA && !useExperimentalEvaluator`
  （AC/EIGENCRAFT/实验评估器逐字节等于上游）；clean 跳过整条通知（canSurvive
  免检的论证成立：支撑块在 pos.below() ⊂ 闭包，浮空必先经转移标记）。
- onPlace 首评估旁路：`DefaultRedstoneWireEvaluator.updatePowerStrength:51-53`
  `updateShape==true` 时无条件评估并清残留位（放置默认 POWER 非计算值）。
- 移除路径（updateShape=false、pos 已 air）无跳过、无条件评估——同时保住
  CraftBukkit 移除态 BlockRedstoneEvent。
- 嵌套/重入：评估中 setBlock → 标记邻域（自身位排除）→ 嵌套通知走同一
  dirty/clean 判定，与 vanilla 递归收敛同构。

## §4 列固定 chunk 解析等价（0130/0131）

- 读集水平跨度 x±2/z±2 ⊂ 2×2 chunk 槽（基数 (x−2)>>4，slot 编码 0-3 无越界，
  负坐标算术移位正确）。
- `Level.getBlockState`（Level.java:1300-1315）= capture 检查（mark/评估器
  入口已分流）+ `isInValidBounds`（纵向 = isOutsideBuildHeight；横向 =
  `ChunkPos.isValid`，Level.java:951-955——±30M 坐标极限，**非动态世界边界**）
  + `getChunk(FULL,true)` 强加载 + `chunk.getBlockState`。
  `papoSlotsValid` 预检 = 同一 `ChunkPos.isValid` 四槽检查 → 横向等价。
- 纵向：越界 y 时 `LevelChunk.getBlockStateFinal`（LevelChunk.java:332-340）
  返回 AIR 而 Level 路径返回 VOID_AIR——对这些消费者（is 粉判定/导体判定/
  getSignal/getDirectSignal）逐一同值，无事件观察（批 131 对偶判例的复核）。

## §5 0265 同 chunk 快路径

- `ServerChunkCache.getChunk(FULL,false)`（ServerChunkCache.java:308-322）：
  命中 `fullChunks` 返回完整 FULL LevelChunk，否则 **null**（不返回加载中/
  部分 chunk）——缓存引用只会是完整数据或禁用，无半载读取。
- 缓存有效性：一个 MultiNeighborUpdate 的全部 runNext 在持线程单次
  runUpdates 突发内执行；Moonrise 卸载在 tick 边界处理点执行，突发中无
  卸载/替换交错；即便并发 IO 侧摘除 map 槽，缓存引用指向的活对象节段读取
  依然一致（卸载不改写状态）。
- 门完备：坐标匹配 + `!isOutsideBuildHeight`（保 BlockPhysicsEvent 的
  VOID_AIR 可见性）+ `!captureTreeGeneration`；exotics 全走 vanilla 路径。

## §6 并发与长期运行

- 条带锁（8 条带，stripe 混合 x/y 位）；gen 线程标记与主线程评估的竞争由
  synchronized 覆盖，临界区为纯集合操作。
- 内存界：每 Level 实例、非静态（Level 卸载即回收，无停机清理需求）；
  CAP=2^18/条带 泄流阀封顶（最坏 ~2M 位 ≈ 16-32MB/维度，不可达量级）。
  **阀的方向语义勘误**（本轮唯一代码库修改）：清空后粉评估为 clean——突中
  途清空可让一次已变输入的评估被跳过（stale 至闭包内下次转移），原注释
  "all wires then simply re-evaluate once dirty" 表述相反，已改为如实陈述
  （PapoWireDirtyTracking.java 类注释；纯注释，零行为差异）。
- 集成客户端（单人游戏）标记为纯簿记开销（客户端无评估入口，永不查询），
  量级可忽略（≤4 次区块解析+24 次节段读/方块更新），保留为安全对称。
- PapoTickProfile：默认关闭（静态 final 早退）；COUNT_KEYS 键集=代码字面量
  有界；窗口清理 TOTALS/COUNTS；tickCount int 回绕不影响 %400 报告节奏。

## §7 不可信输入面

R4 补丁触碰文件清单（0253-0266 逐一核对）：ServerLevel、
ZeroCollidingReferenceStateTable、RedStoneWireBlock、LevelChunkTicks/
LevelTicks/PapoPosTypeSet、PoiTypes、Level、LevelChunk、WorldGenRegion、
DiodeBlock、CollectingNeighborUpdater、DefaultRedstoneWireEvaluator——
**无包解析/命令/聊天文件**。客户端可达的全部路径（放置/破坏/交互）经服务端
权威 setBlock 进入钩子；chunk 槽缓存、闭包扫描、去重集合的键全部来自服务端
状态，无一处读取客户端可控数据。0257 的 Holder 引用等价依赖注册表驻留
（vanilla 数据，非用户输入）。

## §8 数据操作

0256 不触碰序列化格式：pack 走 tickQueue+pendingTicks，集合为纯运行时结构；
构造器把 pendingTicks 加入集合 + unpack 走 scheduleUnchecked 的组合与
vanilla（集合含一、队列含 N 重复）在重复/损坏 NBT 输入下行为逐位一致。
0254 缓存键为属性 id（JVM 内一次分配恒定），表构造后 propertyToIndexer
不可变 → 无失效需求。区块数据版本戳（papoBumpChunkDataVersion）在标记后
同点触发，序列化失效信号不受影响。

## §9 功能完整性

- 0.80.0 发布说明与本轮逐项核对一致：markLevel ≤4 查找（0130）、评估器
  ≤4 查找（0131）、capture/越界回退（两处已核）。
- 0128 评估计数器口径（"rs.wireEvalRuns 现仅计已进行评估"）与代码一致。
- 发现并修复：CAP 泄流阀注释方向反写（§6）。此外无表述与实现不符项。

## §10 验证

- `./gradlew :paper-server:compileJava --rerun-tasks --no-configuration-cache`
  （注释修改前后各一次）：BUILD SUCCESSFUL、exit=0（输出落文件双确认，
  遵守构建判例：管道退出码不可信）。
- `WireDirtySkipBench` 自检 main：ALL OK（10 万随机通知暴力对照含自身转移
  跳过判例 + 4 线程 mark/主线程 eval 压力，条带锁无死锁/无异常）。

## 已接受基线缺陷（状态核对，非新发现）

按 2026-09-03 决断维持：join 预取无界等待（0249 族）、locale 敏感包共享
memo 首观众冻结（0245/0246 族）、区块包双缓存无末观众驱逐（0241/0242 族）。
本轮未改动相关文件，未复发未新增同类模式（R4 面无 memo/无 future join）。

## 判例（可复用）

1. **"通知流 vs 输入依赖"是标记完备性的正确判据**：钩子按输入面
   （转移/模拟漏斗）而非通知派发点放置；不带标记的派发（ObserverBlock
   覆写）在其派发必经先行动作（setBlock 转移）时自动闭合——审计覆写面时
   应证明"派发前有转移"或"通知目标不可达读取面"二者之一。
2. **`getChunk(FULL,false)` 在 Moonrise 下是布尔语义**（完整或 null），
   非"部分数据"——以它为门的快路径不需要额外状态校验，读源码族
   （ServerChunkCache.getChunk:308）即可定论。
3. **注释与实现相反是真实缺陷**：CAP 阀方向反写若被后续维护者当真，
   会把 staleness 风险当成零成本——文档级修复也须全量编译验证。
