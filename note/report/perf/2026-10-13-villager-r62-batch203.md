# R62（批次203）：村民调度/POI 轴分配普查→brain tick 三机械面收割（0299）

**版本 0.110.0 → 0.112.0（两步：0.111.0 对象复用+0.112.0 BFS 队列预容量）· 2026-10-13 · 分配普查第十九域（真新格轴）· 收割轮**

## 0. 选轴事实核查（验证器候选四逐一裁决）

| 候选轴 | 命中核查 | 裁决 |
|---|---|---|
| 村民调度/寻路 | report/perf 全史 Villager/PoiManager/acquirePOI **零命中**；R25 只有自然 mob 的 Brain **驻留**画像（~679 mob 0.75% 堆，懒化候选 ~250KB 低于 R24 三门槛判负）+寻路 Node/BinaryHeap 收割（批次166）；村民 POI 查询/职业调度的**分配**面从未被任何时代的任何口径成像 | **真新格第十九域** |
| 地图与成就推送 | 地图=批次38（0155-0158）已正面收割 MapItemSavedData.addDecoration+tickCarriedBy；成就推送=批次26（0086-0089 触发器）+批次79/82（.dat/advancements IO 下放/预取）+R49 join 相 advancements +40 归因（语义状态本体）多重覆盖 | 判出 |
| 药水状态效果 | 批次39（0162 信标 MobEffectInstance copy 快路+酿造台勘察）+批次53（dirty 聚集）+0210（tickServer 事件门控）三重收割史 | 判出 |
| 传送门轴 | 零命中，但独有面薄=R57 维度生成族+R49 玩家迁移族+网络回声的合成（次选留档） | 判出（本轮不选） |

真实负载对应：村民交易所/刷铁机/交易大厅是真实服务器头号实体负载形态；R25 明示"新场景轴先 census 后立项"——本轮即该判例的兑现（第十九域 census 后三面立项）。

## 1. harness 与双腿（探针腿先行，R60 判例）

**形态**（`CompositeCensus` 新增 `vill` 参数 args[11]，bot 零改动，照 boom 骨架）：

- 每 bot 石板 24×24×3 y[148,150]（boom 同制式）+**围墙 2 格高**（y=151..152 周界四条——腿前自查发现 1 格墙可被 villager 自动跳跃翻越）+lectern×12 北缘内一排（librarian job site=PoiTypes.lectern 注册源，两腿同权）
- `time set 1000` 白天锚（JFR 窗 ~140s=2800 tick 不入夜，全程 WORK/IDLE 相=调度主体；SLEEP 相会引入 bed 寻路偏离轴心）
- **vill=1 对照**：设施+POI 注册在场、零村民（方块更新/POI 注册面两腿相消）——`vill=2`：+summon librarian×12/bot（`{VillagerData:{profession:"minecraft:librarian",level:5},PersistenceRequired:1b}`，level 5 熟练工=工作行为权重满格）=**144 村民调度风暴**：brain 每 tick tickSensors+startEachNonRunningBehavior+tickEachRunningBehavior，每 20gt 完整 refreshActivities+AcquirePoi 异步 POI 查询（SCAN_RANGE 48 BFS）——无需波驱动，召唤即刻持续调度
- 命令 349/腿（12×29+time+marker，逐位对账），burst 0.6s
- 探针：`VLGR-PRESENT`（B）/`VLGR-ABSENT`（A，unless 反向可门化 R60 判例）/`VLGR-LECTERN`（两腿）/`data get entity VillagerData.profession`（**职业校验**——若 NBT 路径错全变默认 nitwit=轴失效，腿前自查新增）+`villagerSummonEcho`（harness 内统计 logLines，R59/R60 判例通式）

**腿表**（`alloccensus-r62village.sh`，JFR 300s 双事件集 anchor=loadWarmed）：

| 腿 | 形态 | events | raw MB | heap KB | OldSample |
|---|---|---|---|---|---|
| A-110ctrl | vill=1 设施零村民 | 9,754 | 26,821.9 | 534,909 | （存活归因见 §4） |
| B-110village | vill=2 144 村民 | 17,608 | 33,963.3 | 636,686 | B 独有 144 村民+POI 常驻 |

门：A 腿全绿（traces 12/absent/lectern/logErrors=0）；B 腿实质全绿（present/lectern/**职业校验回显 "minecraft:librarian"**/logErrors=0），仅 echo 计数=0——**回显措辞测量瑕疵**：villager summon 回显用 display name（"Summoned new **Librarian**"）非实体类型名，匹配词绑定类型名必零命中；负载铁证三源齐（present+lectern+librarian 职业校验+JFR 调度帧），R61 复合门判例免重跑，匹配词改 display-name 无关式后数据留用。A 腿门 gateOk=0 同为门设计瑕疵（vill=1 无 summon 分支=harness 不打印 echo 行，shell 门把 none 当失败）——门与 harness 输出约定腿前对齐判例（R59 同族）。

**JFR 调度链铁证**（B 腿符号扫描）：Villager/Brain/Behavior/poi 帧 23,490 处；`SecondaryPoiSensor.doTick` 热在场。

## 2. B−A 差分与三面立项

B 事件 +51%（9,754→17,608 全口径）；top-40 面 dB=+68.7%。深栈归因（R59 判例：全栈符号扫描非聚合 top-N）：

### 立项面（count≥20 阈+恒等可证）

| # | 面 | trips | dW MB | 深栈归因 |
|---|---|---|---|---|
| 1 | `LinkedHashMap$LinkedKeySet.iterator ← HashSet.iterator` | +1,261 | +1,377 | `Brain.tickEachRunningBehavior` 935+`Brain.startEachNonRunningBehavior` 326——三层 for-each（TreeMap×HashMap×LinkedHashSet）**每 tick 每 brain 每集合一枚迭代器，全 STOPPED 也要分配**（R41"什么都不做的路径也要分配"通式） |
| 2 | `LongOpenHashSet.rehash` +`LongArrayFIFOQueue.resize` | +519 +151 | +573 +112 | `AcquirePoi.lambda$create$7` →`PoiAccess.findNearestPoiRecords` BFS 工作集——每查询 new 双件，Paper 自有优化代码（io.papermc.paper.util.PoiAccess L227/L470 两 BFS 体） |
| 3 | `BlockPos.offset ← SecondaryPoiSensor.doTick` | +171 | +177 | 每 40tick×405 格（9×5×9）扫描的全 immutable BlockPos——纯查询载体（R37 scratch 族同构） |

### 判负维持面（已知族/语义面）

- **物理/走位族**（B 独有+共享增量）：`Vec3.add` +103/`AABB.move ← CollisionUtil` +108/`AABB.inflate` +64/`EntityDimensions.makeBoundingBox` +89（共享）/`Entity.getOnPos → BlockPos.atY` +114——村民走动=结果对象维持（R41/R48/R52/R60 判例族）
- **brain 语义小面**：`Optional.ofNullable ← Optional.map` +230（memory 条件链）、`MemoryCondition$Registered.createAccessor` +105、`BehaviorBuilder$PureMemory$1.tryTrigger` +96（声明式触发器）、`ShufflingList.iterator` +96（行为列表洗牌=vanilla 语义本体）——均 <R24 三门槛份额或语义面
- `RegistryFriendlyByteBuf +156`=R44 维持同脸**第五见**
- 自观测网络统计族（InetSocketAddress/Inet4Address/toString 共享增量）=R41 判例按事件率放大
- NBT 保存族共享增量（CompoundTag/LongArrayTag.write/BufferedOutputStream）=144 村民实体数据自动保存，R32/R43 维持+数据面
- `NaturalSpawner` 共享族两腿同权（peaceful 动物 spawn）相位内相消；`BalancedPrioritisedThreadPool.findEligibleGroups` +65 trips/2,009MB 权重=Moonrise 调度池上游维持（R42 判例族）
- A 独有=挤窗位移（R56 相位噪声族：PalettedContainer.copy/SimpleBitStorage 等保存面）

## 3. 0299 三面实现（内仓 commit e761d65c → 补丁 0299）

### 3a. Brain 脏标记 lazy 行为快照

`availableBehaviorsByPriority` 结构保留（Map<Integer,Map<Activity,Set>>，插件/refreshBrain 兼容面零变化），新增平行快照：`(BehaviorControl[], Activity[])` 平行数组+**脏标记**——`addActivityAndRemoveMemoriesWhenStopped`（全部 add 族收口点）与 `removeAllBehaviors` 尾部置 null，`startEachNonRunningBehavior`/`tickEachRunningBehavior` 惰性重建后**索引遍历零迭代器**。

**序恒等论证**：结构无突变期间 TreeMap values（priority 升序）×HashMap entrySet（桶序，无突变时确定）×LinkedHashSet 迭代序（插入序）三层全确定 → 快照序 ≡ vanilla 每 tick 遍历序逐位相同；突变（Villager.refreshBrain 职业转换等）后下一 tick 重建=vanilla 突变后立即生效同构。运行期突变路径实测存在（Villager.java:228-249 refreshBrain）→ 不能用"构造期冻结"假设，脏标记为唯一正确形态。tryStart/tickOrStop 无结构突变（vanilla 迭代器下突变即 CME，快照遍历反而更宽容异常路径）。

### 3b. PoiAccess BFS 工作集 caller-owned scratch（两步）

两 BFS 体（findClosestPoiDataRecords/findNearestPoiRecords）的 `new LongArrayFIFOQueue()+new LongOpenHashSet()` → `ThreadLocal` 单槽对，**入口无条件 clear**（异常退出残留由下次调用兜底；fastutil clear() 保容量、原生后备无引用语义=R26 判例）。调用方线程面核查：AcquirePoi/NearestBedSensor（brain 主线程）+PortalForcer（主线程）+PoiManager 自身（调用线程）——ThreadLocal 每线程独立槽全安全；查询非重入（BFS 不嵌套 BFS）。

**第二步（0.112.0，V111 配对腿浮出）**：对象复用后 `LongArrayFIFOQueue.resize` 残余 160 trips **全窗均匀分布**（vs 修正前 167）——fastutil FIFO 的 grow-ladder 爬升分配在 IO 池线程轮转下每槽反复触发（线程轮转=每槽首查询都要 16→2048 爬一遍），对象壳复用不消除它。修正=`ThreadLocal.withInitial(() -> new LongArrayFIFOQueue(2048))` 预容量到 BFS 前沿上界（48 格 range≈7×7×24 section 邻域≈1,176+出界邻域展开），R39 同族完成判例应用（rehash 与 resize 同族不留半脸）。

### 3c. SecondaryPoiSensor 单 cursor 扫描

405 格循环 `blockPos.offset` → 方法内单实例 `MutableBlockPos` 循环复用（405→1 分配/扫描）；**命中时 `cursor.immutable()` 拷贝**（值过 GlobalPos 进 brain memory=语义载体，同坐标同新鲜度）。getBlockState 纯读坐标（vanilla 特征循环自传 mutable 先例，R37 同构）。

## 4. 验证

- **恒等单测 9 用例**（注册 PapoRegressionTestSuite）：Brain 5（插入序=tryStart 仲裁序/跨 priority 升序/连续两 tick 序列逐位同/运行期 addActivity 后脏重建+前缀保序/removeAll 清空）；Sensor 2（oracle 手工 405 域命中集+序逐位同+immutable 不泄漏/无命中 erases 分支）；PoiAccess 2（背靠背同参查询结果逐位同=seen 残留会剪枝第二次 BFS 的复用契约/nearest 查询同）。**自查纠偏三次**：期望漏算 vanilla 同 tick startEach+tickEach 双段语义（刚 start 的行为同 tick 即被 tick）×2、findClosestPoiDataRecords=最近距离平局语义非全量返回——测试语义滑误（R33/R42 同族），产品代码零改。
- **链门**：`rebuildPatches`（补丁 `0299+0300-villager-brain-tick-de-allocation-*.patch` 生成）+`applyPatches --rerun-tasks`+`test --rerun-tasks` 全 EXIT=0；套件 177 tests 0 failed。
- **出包**（两步各验证）：0.111.0（第一步）与 0.112.0（第二步 papoVersion bump+rebuildPatches/applyPatches/test 链门复跑）；**javap 验嵌套 server jar 字节码**（paperclip 嵌套结构须解 META-INF/versions 内嵌 jar 再 javap——外层 javap 找不到类）：Brain papoSnapshot 双字段+双方法/PoiAccess PAP0 ThreadLocal 双槽（V112 含 2048 预容量 lambda）/Sensor MutableBlockPos 引用×4 全在场。首次 javap 零命中=R46 新鲜度判例复现（Gradle up-to-date 误判），删产物+`--rerun-tasks` 强制重建后三面齐。
- **MSPT 三序六腿**（`msptload-r62legs.sh`）：主序（A→B）B 4/6 劣（唯一大额 s2 +2.2）→倒序（B→A）**B 5/6 优、s2 反转 −1.9**（跨序破号=R38 噪声判例）→钉死对 B **5/6 优**（s2 −0.4 反优）。**零三序同号劣化槽**；主序劣+倒序优=R37"后跑腿劣势伪影"形态（谁后跑谁亏，时间趋势非代码通道；0299 只减分配零加法路径无 tick 劣化机制）→**零回归**。
- **村民轴配对腿 V110 vs V111/V112**（`alloccensus-r62pair.sh`，同 vill=2 形态跨版本，vecho=144/144 全腿中=echo 修正实证）：
  - **V111（第一步）**：`LinkedHashMap$LinkedKeySet.iterator` **1,305→0**、`LongOpenHashSet.rehash` **501→0**、`SecondaryPoiSensor.doTick→BlockPos.offset` **166→0** 三面坍缩；`LongArrayFIFOQueue.resize` 167→160 残余（见 §3b 第二步）；全口径事件 21,278→14,666（−31.1%）/raw −24.2%
  - **V112（终态）**：**四面全归零**（FIFO resize 110→0 补齐）；top 面口径 dB=−12.1%；B-only 新面=Vec3.add 等物理族采样波动（0299 三面路径零新分配，非替代面）
  - heap：V110 753,537 / V111 644,522 / V112 744,753 KB——腿间窗相位波动（spawn/村民走动活跃度）大于版本差，如实分层：配对 JFR 面坍缩是硬证据，heap 差不作收割度量（R39 分开记账判例）

## 5. 判例

1. **回显词=display name 非实体类型名**：villager summon 回显 "Summoned new Librarian"（职业实体名）——echo 匹配词不得绑定实体类型名（R60 TNT/"Primed TNT"同源；通用式=`Summoned new`/`Object summoned` 无关词）。
2. **门与 harness 输出约定腿前对齐**（R59 门设计一致性判例新形态）：vill=1 无 summon 分支=harness 不打印 echo 行，shell 门把 none 当失败——A 腿门败于输出约定非腿数据；门变量在对照腿的"无输出口径"必须显式定义。
3. **运行期结构突变容忍是快照遍历的立项前提**：Brain 行为集"构造后冻结"假设被 Villager.refreshBrain（职业转换运行期 addActivity）证伪——脏标记 lazy 重建是唯一正确形态（结构突变=置脏，下 tick 重建，突变后立即生效与 vanilla 同构）。
4. **paperclip 嵌套 jar 的 javap 验法**：Papo-*.jar 是 paperclip 结构（server jar 嵌套在 META-INF/versions/<mc>/），外层 `javap -cp` 找不到 net.minecraft 类——须解包内嵌 jar 再验（R46 新鲜度判例的嵌套形态补全）。
5. **MSPT 三序"后跑腿劣势伪影"再实证**（R37 判例二度应用）：主序 B 劣 4/6+倒序 B 优 5/6+钉死 B 优 5/6=谁后跑谁亏的时间趋势形态，非代码通道；同号槽判读必须先排除该形态再谈回归。
6. **对象复用 ≠ 内部数组复用（fastutil grow-ladder）**：ThreadLocal 对象壳复用后，集合内部数组的扩容爬升（16→32→…倍增 copyOf）仍逐查询发生——IO 线程池轮转使每线程槽反复爬升（均匀分布全窗=爬升特征签名）；预容量到工作集上界是彻底解，且**配对腿是浮出残余同族面的唯一仪器**（R39 不留半脸判例的测量面）。

## 6. 工件

- harness/脚本：`benchmark/src/papo/bot/CompositeCensus.java`（vill 参数）、`benchmark/alloccensus-r62village.sh`、`benchmark/alloccensus-r62pair.sh`、`benchmark/msptload-r62legs.sh`、`benchmark/r62diff.py`
- 腿数据：`benchmark/results/village-20261013-083137-B-110village/`、`village-20261013-084057-A-110ctrl/`、`vpair-20261013-{101924-A-V110,102731-B-V111,105226-B-V112}/`、`msptload-20261013-{092458,093722,100700}-leg-*.log`
- 单测：`PapoBrainSnapshotTraversalTest`/`PapoSecondaryPoiSensorCursorTest`/`PapoPoiAccessScratchReuseTest`
- 补丁：`paper-server/patches/features/0299-villager-brain-tick-de-allocation-*.patch`（内仓 e761d65c+7f8e8033 两提交）
- 二进制：`paper-server/build/libs/Papo-1.21.11-0.112.0.jar`（0.111.0 中间版 jar 同存=第二步修正前基线）
