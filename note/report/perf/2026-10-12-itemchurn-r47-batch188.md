# R47 批次 188：物品实体旋涡轴分配普查 → inside-block 走查双机械面收割（0.110.0）

日期：2026-10-12 ｜ 二进制：0.109.0（发现+对照）→ 0.110.0（收割）｜ 工具：`benchmark/src/papo/bot/ItemChurnCensus.java` + `itemchurn-r47legs.sh` + `itemverify-r47legs.sh`

## 0. 摘要

新场景轴轮（R38 判例四度应用）：R24-R46 分配普查六域（流送/盘载/驻留/实体密集+保存/
机械空查询/BE 驻留）中，**物品实体旋涡（生成→飞行→着地→堆叠合并→被吸取→入容器）
从未成像**——R42 实体密集窗的实体是"在场不动"的自然刷怪，R46 机械窗漏斗对实体查询
的答案永远是"空"。真实农场服（刷怪塔/树场/矿山）的常开负载形态首次成像。发现腿
B（旋涡）12,342 事件 vs A（自然）1,156（10.7×）；逐面判定立项唯一机械面=移动实体
inside-block 走查双件套（travel-dedupe `LongOpenHashSet` 每调用新建 489 trips +
`betweenCornersInDirection` 迭代器簇 304 trips in-stack），收割后配对腿面全零、窗口
分配事件 −10.4%/raw −11.6%，MSPT 三序零回归（§7）。**附 R46 播种失效追溯性勘误（§8）。**

## 1. 场景设计（探针 #4 全链实证后定稿）

机械驱动设计三连败（`/replaceitem` 不存在→`/item replace block` 结构错→观察者对无法
供能邻接发射器，见 build.md 判例）后收敛为 **summon 批式**：一次性命令、JFR 窗内零命令
伪影。每 bot（12 bot 环形撒布 y≈64..90，dwell 于农场正下/近旁——MISC 激活列=水平
inflate×世界高全柱，y=200 物品即激活）：

- y=200：漏斗排 6（`facing=east` 链 cx-6..cx-1）→ 双箱（(cx,cz)/(cx,cz+1)，54 槽）；
  石台 10×10（cx-12..cx-8, cz-2..cz+2）；
- y=201.3：漏斗柱 150/列×6=900（8 类轮转：同列同类距 0 就地堆叠=合并相，跨列异类
  1 格>0.75 合并盒=柱数维持）+ 石台 3/格×100=300（邻格异类→永驻站立相：tick/合并
  扫描/驻留，despawn 6000t>测量轴）；12 bot 合计 14,400 召唤+48 构建命令，2.5s 内
  处理完（服务器日志 "Unknown or incomplete" 恰 3 条=仅探针，构建命令族全绿）；
- 时间线锚 **fill 标记**（非发送时刻——14.4k 命令的服务器侧处理耗时不可假设）：
  marker+8s 探针①（漏斗 Items+`@e` 在场，JFR 窗前）→ marker+12s loadWarmed
  （JFR 60s @1ms 覆盖 drain 相 ~45-75s）→ marker+90s 探针②（箱 Items cobblestone=
  全链完成，窗后）→ marker+150s 驻留测量（双 GC+直方图）。

旋涡正证据（B 腿三正证据门全绿）：探针①漏斗 +8s 已吸 98 件五类（sand 2/stone 20/
dirt 38/granite 19/diorite 19）；`[Server] CHURN-ITEMS-PRESENT`；探针②箱 cobblestone
64+30、andesite 64+28（吸取→链转移→入箱全链）。驻留相直方图：ItemEntity 3,693≈
12×300 石台驻立+残留（A 腿自然基线 240-306），heap 1,019-1,079MB vs A 632-663MB。

## 2. 发现腿（同二进制 0.109.0，A 自然 vs B 旋涡，JFR 60s @1ms）

| 指标 | A（自然） | B（旋涡） |
|---|---|---|
| 事件数 | 1,156 | 12,342（10.7×） |
| raw 权重 | 12,381MB | 24,455MB |
| TRACE[ICB 正证据 | 88 | 84 |
| 类版本错误 | 0 | 0 |
| 面数量（≥20 trips） | — | 40+（截断） |

### 逐面判定（判例清单对照）

| 面（B trips，in-stack 口径） | A | 判定 |
|---|---|---|
| **LongOpenHashSet.<init>（forEachBlockIntersectedBetween L223 travel-dedupe）** | 5 | **489 → 立项 0298a** |
| **betweenCornersInDirection 迭代器簇（lambda$8+BlockPos$6+allocateInstance）** | 8 | **304 → 立项 0298b** |
| forEachBlockIntersectedBetween 全路径（含 visitor lambda/AtomicInteger/自帧） | 40 | 1,793 → 簇内判读：收割面立项；visitor lambda ~117 阈上门但捕获 from/to/aabb/collector 无恒等消除路径（lambda 机械化重构超等价授权）；Vec3.subtract=结果对象维持 |
| InetSocketAddress/Inet4Address/InetAddress toString+StringBuilder 簇 | ~0 | 2,811 → **R41 JFR 自观测伪影判例剥离**（networkStatFor 每包 toString 链） |
| spark 采样器族 | 108 | 711 → R41 判负维持（上游） |
| DirectByteBuffer.slice/copyOfRange/RegistryFriendlyByteBuf/sendChanges | 低 | 938/619/438/114 → 网络/tracker 代价面维持（R44 判负+netty 上游）——14.4k→3.6k 实体的 spawn/destroy/位置同步回声，事件在窗前半 |
| Vec3.multiply/add/scale、AABB.inflate、makeBoundingBox、collision 族 | 低 | 数千 → 结果对象维持（R42 判例族；碰撞域值货币） |
| FlowingFluid.getFlow/Fluid.getAABB | 8 | 253/129 → 流体状态数据/结果面维持（R42 同族；每 item tick 的 updateInWaterStateAndDoFluidPushing） |
| Holder$Reference.tags() stream | 低 | 95 → 数据面维持（vanilla registry 架构） |
| suck 查询（getItemsAtAndAbove→getEntitiesOfClass ArrayList/forClass/包装 lambda） | 0 | **0-1 → 阈下**：漏斗吸取按栈段整取（~48 段/bot），查询速率 ~1-2/s/bot，三件小分配采样不可达——R46 的空侧快路恰使非空侧低频；**真实农场旋涡的实体查询分配面不立项**（设计实证：堆叠后栈段粒度转移） |
| mergeWithNeighbours | — | 11 → 阈下（既有 Papo scratch 优化在场） |
| hopper push/pull ItemStack.copy 族 | — | 0 → 阈下 |

## 3. 收割 0298：inside-block 走查双机械面

### 3a. travel-dedupe LongOpenHashSet → 调用方懒 scratch

`BlockGetter.forEachBlockIntersectedBetween` 移动分支每调用 `new LongOpenHashSet()`
（去重三段走查的访问位）。新增 5 参重载（`@Nullable LongSet papoScratch`）：原 4 参
委托（null=每调用新建，Ghast 冷路径原语义逐位保持）；热路径调用方
`Entity.checkInsideBlocks` 传 per-Entity 懒字段 `papoTravelDedupeScratch`。恒等性：

- 集合为调用局部、出口即死 → caller-owned + **try/finally 全出口清空**（正常/短路/
  异常三态；visitor 抛 ReportedException 亦清）；
- fastutil `clear()` 保容量清键（R39 字节码实证判例族）；
- 线程封闭（主线程走查，visitor 回调无重入 forEachBlockIntersectedBetween——回调
  体=getBlockState/形状/流体检查/效果施加）；
- 入空契约：字段懒初始化即空，出口清空维持。

### 3b. betweenCornersInDirection 迭代器簇 → 索引走查

三个调用点**全部**在 BlockGetter 内部（loop1 起点盒/loop2 终点盒/addCollisionsAlongTravel
碰撞盒——公开 API 未变，插件可见面零变化）。原每循环分配：捕获 lambda（Iterable）+
AbstractIterator（~15 捕获字段）+ `Direction.axisStepOrder`（常量零分配，实证）。
新私有 `papoVisitCornersInDirection`：三重索引循环逐位复刻 computeNext 的游标推进
（t 最内、end 语义、短路），**每循环一枚共享 MutableBlockPos cursor**。恒等性要点：

- **跨循环 cursor 别名语义与 vanilla 逐位同构**：vanilla 每循环独立迭代器=独立
  cursor；改写每 helper 调用一枚 cursor（3 调用=3 cursor）。跨调用复用会把别名窗口
  扩到"块回调留存 pos 观察到后续变异"（vanilla 契约下留存永久有效）=插件兼容红线，
  故 cursor 不跨调用复用——**这是替代面的语义下限**；
- StepBasedCollector.currentBlockPos 的唯一读取点 recorded() 即时 `.immutable()` 快照
  （L103），cursor 变异不影响已排队效果——别名安全在消费侧另有实证；
- loop1 的 `boundingBox.move(vec3.scale(-1.0))` 内联为分量表达式
  （`minX - vec3.x`；IEEE `a+(-b)≡a-b` 逐位相同），并顺带消灭 4×
  getMinPosition/getMaxPosition Vec3 与 1×moved AABB——**面值外的赠收**。

### 3c. 验证链

- oracle 差分单测 `PapoBlockWalkEquivalenceTest`（同包访问 package-private 静态）：
  oracle=原方法体逐字副本（走未改动的公开 BlockPos 原语）；3 用例——①18 组合网格
  差分（六向/对角/负坐标/远坐标/亚 1e-5 短路分支 × 三盒型：位置序列+步索引+完成态
  逐位相等+**cursor 身份分组结构相等**（per-loop 共享游标别名性对等））②早停对等
  （三种 stopAfter×scratch 开关：精确停位+scratch 出口空）③scratch 契约（与 oracle
  序列恒等+复用第二走查无状态泄漏+visitor 抛异常传播且 finally 清空）。实测 3/0/0；
- 链门：内部 commit `fb68b222`（inside block walk de-allocation）→ rebuildPatches
  EXIT=0 → applyPatches EXIT=0 → 套件 **9,088/0/0**（9,085+3）→ compile/出包 EXIT=0；
- 出包新鲜度（R46 判例）：`--rerun-tasks` 强制重建后 javap 验包内嵌套 jar
  BlockGetter.papoVisitCornersInDirection 与 Entity.papoTravelDedupeScratch 字节码在场。

## 4. 配对验证腿（V109=0.109.0 vs V110=0.110.0，双腿旋涡 fill=1）

| 收割面（位点口径） | V109 | V110 |
|---|---|---|
| LongOpenHashSet.<init> | 467 trips / 444.2MB | **0** |
| BlockPos.lambda$betweenCornersInDirection$8 | 191 / 219.8MB | **0** |
| BlockPos$6.<init> | 92 / 86.3MB | **0** |
| DirectMethodHandle.allocateInstance ← betweenCorners | 85 / 83.4MB | **0** |
| forEachBlockIntersectedBetween 自帧（Vec3.subtract 等） | 94 / 97.1MB | **0** |
| **替代面** papoVisitCornersInDirection（MutableBlockPos×3/调用） | — | 73 / 82.1MB |

簇合计 929 trips/~931MB → 0，替代面 73 trips = **−92% trips/−91% 权重**；替代面为
§3b 论证的 cursor 契约下限（跨调用复用=插件兼容红线，不取）。窗口总量：事件
12,700→11,380（**−10.4%**）、raw 权重 24,768→21,901MB（**−11.6%**）——旋涡 drain 相
整机少分配一成。双腿门全绿：TRACE 96/87（≥12）、零类版本错误、旋涡三正证据
hopperItems/chestCobble/itemsPresent 各 1（探针过滤排除错误回显后计数）；heap
1,057,562 vs 1,019,710KB（−3.6%，走查对象短命+drain 相同——驻留面本收割不涉）。
组成一致性：summons 14,400/14,400、hoppers 72/72、burstMs 2,511/2,515。

## 5. 收益结构

每移动实体每移动段的 inside-block 走查：~5 件分配（dedupe set+2-3 迭代器簇+中间
Vec3/AABB）→ 3 件 32B cursor（语义下限）。B 腿窗口内移动 item 实体（下落柱+drain
残余）× 每 4t 静止走查/每 tick 移动走查——每 bot 农场全生命周期（900 下落+300 驻立）
约 5×10⁴ 次走查，全服 12 农场 ≈ 6×10⁵ 次/腿 → 窗内 ~930MB→~82MB。通用收益：一切
移动实体（玩家/生物/矿车/掉落物）的每次 move+checkInsideBlocks 均受益，非旋涡独占。

## 6. 残余与判负维持

- visitor lambda（~117 trips）：捕获 from/to/aabb/collector/AtomicInteger——无恒等
  消除路径（整捕获环境机械化重构超等价授权），维持；
- 网络面（slice/copyOfRange/RegistryFriendlyByteBuf/sendChanges）：R44 判负维持；
- 结果对象族（Vec3/AABB/collision）：R42 维持；流体 getFlow/getAABB：数据/结果面维持；
- spark/JFR 自观测：R41 剥离判例维持（本轮 B 腿再次复现实证：剥离前 toString 簇
  占 in-stack 2,811 居首）。

## 7. MSPT 三序守护+同序复跑（0.109.0 vs 0.110.0，MsptLoadProbe 12 bot，八腿全绿）

| 槽（win5s 首值 B−A） | 主① | 倒序 | 钉死 | 主②（同序复跑） |
|---|---|---|---|---|
| s1 stream | +0.1 | −0.2 | −0.1 | +0.2 |
| s2 stream | −0.3 | +0.3 | +0.7 | −0.4 |
| s3 末流送 | **+1.4** | **+0.2** | **+0.9** | **−0.3 破号** |
| s4 首驻留 | **+0.3** | **+1.3** | **+1.4** | **−0.2 破号** |
| s5 dwell | +0.6 | +0.7 | −0.8 | −0.4 |
| s6 dwell | −0.3 | +0.3 | −0.1 | +0.1 |

腿优计数：主① 2/6 优·倒序 1/6 优·钉死 3/6 优·主② **4/6 优**。槽 3/4 三序同号劣
但均为**相位过渡槽**（末流送/首驻留）偏劣+稳态槽互有胜负=R46 相变槽判例同形；本轮
改动在 tick 路径有真实通道（每移动实体每段 checkInsideBlocks），无通道否决权不可
用→同序复跑终审：**双槽同序破号反优**（R42 判例四度应用=瞬态漂移）；带重叠复核：
slot4 A 带扩至 6.6 入 B 带（主② A=6.6 vs B 带 6.0-7.3）、slot3 B=8.3 入 A 带
（8.0-8.6）——三样本带不重叠是 3×3 小样本的常见假象，第四对即穿。TRACE 85-91/腿
全过阈、零类版本错误、logErrors=0、exit 0。**判定：零回归。**

## 8. R46 播种失效追溯性勘误

R46（批次 187）的 `/replaceitem` 播种自始未生效（1.21.11 无该命令；R46 探针回显
`[]` 即证据）——R46 场景实为**空漏斗空查询+观察者时钟**。目标面（真实运行的空查询
面）与收割（空探针快路+配对面坍缩+MSPT 零回归）**有效性不受影响**；但 R46 文档中
"replaceitem 预置种子/物品环流"设计组件描述失实，以本勘误为准。R47 的 summon 批式
即该设计意图的正确实现（探针 #4 实证全链）。

## 9. 判例（新增）

1. **探针命令双斜杠事故族**：错误回显可同时满足 marker 门与在场门（回显含目标子串）；
   console 探针门必须以成功响应形态为锚（`data get <pos> <path>` 成功响应只回显值、
   不含路径键名）且显式排除 `--[HERE` 回显；服务器日志 `Unknown or incomplete` 计数
   是命令族失败总数的决定性证据。
2. **时间线锚 fill 标记而非发送时刻**：万级命令的服务器侧处理耗时不可假设（本轮
   2.5s，但不可先验）；marker 检测须排除错误回显。
3. **迭代器消除的 cursor 契约下限**：vanilla 每循环共享 cursor+跨循环独立——索引
   走查改写保持同构（每循环一枚）；跨调用 cursor 复用会把块回调留存 pos 的观察窗口
   扩大到后续走查=插件兼容红线，替代面如实保留并定量（−92%）。
4. **调用局部容器 caller-owned scratch 通式**（R39/R46 判例延伸）：try/finally 全出口
   清空（含异常）+线程封闭+无重入=三支柱；本例 fastutil LongOpenHashSet clear 保容量。
5. **堆叠后栈段粒度转移判读**：漏斗吸取按整栈段取（isMergable→同位合并→~8 栈/列），
   非空实体查询面（ArrayList/forClass/包装 lambda 三件）速率被堆叠压到阈下——旋涡
   轴的查询分配面不立项是**设计实证结论**，非普查遗漏（配对腿 0-1 trips 复现）。
