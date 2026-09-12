# Fork 基线修改面对抗审计（批次 138，2026-09-12）

> 范围：七个已审面（132 红石 / 133 出站登录 / 134 入站解码容器 / 135 早期分配 /
> 136 网络 pivot+刷怪战斗 / 137 跨家族交互）之后的全新面——**fork 基线修改面**：
> Papo 对上游基线文件的一切净改动。权威枚举 = `git diff <上游基线> HEAD`（基线
> = c5eb0790f1，首个 Papo 提交 1dc42748e4 之父），按补丁号追踪会被编号重排欺骗，
> 净 diff 是唯一可信面清单：
> ①features/ 修改 6 个上游补丁（0001/0002/0005/0020/0025/0028）+ 新增 0036-0039
> （Pufferfish 移植，GPL-3.0）——0040-0266 已审不重；
> ②patches/sources/ 17 个文件（批 79/80 内嵌 hunk ×6、7 月回移植 ×5、上游樱桃
> 挑选 ×6）；
> ③src/main 19 改 + 5 增（ItemObfuscationSession/批 78-80 池族/品牌化/V5 半边/
> 构建修复首次真审，其余映射有效）；
> ④paper-api 8 改 1 删；⑤gradle.properties/build.gradle.kts 品牌化与版本接线。
> 触发原因：批 133 范围头列"批 79 存档下放"但**批 80 零审计段落**；批 135 范围
> 声明 0156-0204 却漏 0186；批 137 §9 两处映射不实（见两报告头部勘误）。
> 对齐流目标：长期高负载多用户稳定性、数据操作完整性、不可信输入面、内存界、
> 功能表述符合性。方法：逐 hunk 对已应用源码树对抗读 + 对 vanilla/上游语义的
> 构造性等价核对。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | 批 80 level.dat 写下放（LevelStorageSource/MinecraftServer） | **闭合**（读侧/备份/改名/停机四类交错逐一排除） | §1 |
| 2 | 批 78/80 线程池 sizing 族（PapoParallelism/MoonriseCommon/SpigotConfig） | **闭合**；1 处注释失实（C1，本轮修复，docs-only） | §2 |
| 3 | Pufferfish 移植 0036-0039 | **闭合**（0036 节律恒等证明、0037 构造器全覆盖证明） | §3 |
| 4 | 7 月回移植 5 处 sources + CraftBlockData 直改 | **闭合**（#13886 取消语义/#13002/#14080/#13455/attack check） | §4 |
| 5 | 上游樱桃挑选保真（0001/0002/0005 内嵌 + 6 sources + api 4 文件） | **闭合**（逐 hunk 对上游提交意图核对） | §5 |
| 6 | 0186 + ItemObfuscationSession（批 135 遗漏、批 137 错映射） | **闭合**（首次真审：no-op 分支/新鲜上下文/restore 链逐句恒等） | §6 |
| 7 | 散点（V5 commands 半边/品牌化/构建修复/TestServerBuildInfo） | **闭合** | §7 |
| 8 | 功能完整性（批 78/80 表述 vs 实现） | **一致**（三条曲线公式/优先级声明/-D 与显式配置优先/物化提示/boot 同步/备份等待/flush awaitAll 逐项核到） | §8 |
| 9 | 不可信输入面 | **零新增**（池 sizing 键全为服务端配置/派生；回移植不触入站解析） | §9 |
| 10 | 长期运行内存界 | 无新增无界结构；CombatTracker 上限化是无界→有界改善；0267/0268/0269 决断维持 | §10 |
| 11 | 历史报告勘误 | 批 135（0186 遗漏）+ 批 137（两处映射不实）就地注记 | 各报告头部 |
| 12 | 构建验证 | compileJava --rerun-tasks BUILD SUCCESSFUL、EXIT=0（src/main-only 改动，补丁树零触碰，沿批 132 纯注释判例免重放） | §11 |

## §1 批 80 level.dat 写下放

改动：`saveDataTag` 主线程深拷贝快照 → IO 池按 dataFile 键有序写
（createTempFile + writeCompressed + safeReplaceFile，catch Exception +
LOGGER.error 与同步版 `saveLevelData` 逐句同形）；`makeWorldBackup` 顶部
awaitPending(dataFile)；`saveAllChunks(flush=true)` 尾部 awaitAll(60s)。

**读侧/交错完备性**（本轮重点）：
- **restoreLevelDataFromOld**（唯一盘上 level.dat 变换的运行期调用方）仅
  Main.java:216 boot 路径触发，早于本 JVM 任何 enqueue——无窗口。
- **makeWorldBackup/deleteLevel/renameLevel/renameAndDropPlayer**（后三者经
  modifyLevelDataWithoutDatafix 同步读写，理论上有"挂起异步写覆盖新改名"的反转
  窗口）在专用服务端源码树内**零调用方**（客户端世界列表 UI 专用，grep 全树仅
  Main 一处命中 restoreLevelDataFromOld）；awaitPending 防御性保留无害。
- **modifyLevelDataWithoutDatafix（boot 期改名路径）**：boot 期无挂起写，同步
  saveLevelData 直写安全。
- **快照隔离**：`compoundTag1.copy()` 深拷贝后才出线程；compoundTag/compoundTag1
  均为本次 createTag 新建（拷贝属纯防御）。
- **flush 语义**：stopServer → saveAllChunks(flush=true) → awaitAll 覆盖全部
  level.dat/玩家文件挂起写；haltExecutors 另有 60s×2 排水兜底（批 91 已审）。
- **多生产者**：level.dat/玩家 .dat/stats/adv 写入方全在主线程，per-key 有序链
  无交错写入。

## §2 批 78/80 线程池 sizing 族

- **曲线对照**（upstream → Papo）：
  - worker：`cores/2≤4?(≤3?1:2):cores/4` → `clamp(cores/2,2,12)`。差异点
    2 核（1→2）、8 核（2→4）、≥24 核（≥6→12）——全部为声明中的抬升方向，
    floor 2 为显式决断；`Integer.getInteger(brand+".WorkerThreadCount")` 的
    -D 优先与 `configWorkerThreads>0` 的显式配置优先逐行保持。
  - regionIo：`max(1,config)`（auto=平 1）→ `config>0 ? config :
    clamp(cores/8,1,4)`；AreaDependentQueue 同 region file 串行化断言沿批 78
    基准（per-region 并发≤1/FIFO/恰好一次自检全绿）。
  - netty（SpigotConfig）：`getInt("settings.netty-threads", 4)` → 缺省
    `clamp(cores/4,4,16)`；spigot.yml 物化值保持优先 + "legacy 4 钉死"INFO
    提示（`count==4 && def>4` 条件与批 78 表述"存量物化 4 保持 + INFO 提示"
    一致）。
- **CORES 守卫**：`Math.max(1, getTotalCores())` 防 0 核异常平台。
- **C1（本轮唯一修复，docs-only）**：MoonriseCommon/PapoParallelism 注释声称
  "workers run at NORM priority under the NORM+2 main tick thread"——全树
  grep 证伪：主 tick 线程无任何提权（默认 NORM），Moonrise 池线程亦未设优先级
  （继承 NORM），仅 Util 自有后台池经 ServerWorkerThread（NORM+modifier）降权。
  流畅性结论（批 80 tick 探针两配置偏差全 0）仍成立，但机制描述失实；两处注释
  改为如实陈述（池队列 hold 调度 + 实证，而非优先级隔离）。

## §3 Pufferfish 移植 0036-0039

- **0036 窒息节律**：`tickCount%10==0 && couldPossiblyBeHurt(1.0F)` 采样。
  恒等性证明：首伤设 invulnerableTime=20（invulnerableDuration），此后伤害仅在
  invulnerableTime≤10 落地，即 vanilla 持续窒息节律恰为每 10 tick 一次；Papo
  首伤落在首个 10 倍数 tick 后，所有后续采样点（10 倍数）与 invulnerableTime==10
  窗口**精确重合**，持续伤害率恒等；仅首伤延迟 ≤9 tick（Pufferfish 声明的既定
  折衷）。couldPossiblyBeHurt 与 hurtServer 门逐字同式。WitherBoss 覆写
  shouldCheckForSuffocation()=true 还原逐 tick。边界：CraftBukkit
  setMaximumNoDamageTicks 可把 invulnerableDuration 配到 <20——该面下 Papo 采样
  慢于 vanilla 上限，为 Pufferfish 全体部署形态的既知属性，维持。
- **0037 雷击倒计时**：LevelChunk 全部 3 个构造器汇流至主构造器（:203），其尾
  :236 统一初始化 lightningTick（磁盘加载路径 SerializableChunkData:376 亦经
  此）——**无"未初始化→0→风暴首 tick 全区块齐击"缺陷**。默认 thunder_chance
  （100000）下 init 与 reset 同分布（均值 99998+1），节律与 vanilla p=1e-5 恒等。
  观察项（不修）：init 硬编码 `nextInt(100000)` 为 Pufferfish 原样——非默认
  thunder-chance 下每区块加载后的首雷延迟分布偏斜（chance<100000 偏迟、
  >100000 偏早），首雷后收敛；属移植参考属性，与随机序列红线同理维持移植决断。
  `thunder_chance>0` 守卫保持（防 nextInt(0)）。
- **0038 区块加载削减**：EnderMan.teleport 下降循环为同列（x/z 不变）不出区块
  界，单 chunk getBlockState 与逐 level.getBlockState 等价；getChunkIfLoaded
  空返回拒绝传送（防 AI 同步加载，Pufferfish 意图）。MoveToBlockGoal
  hasChunkAt 跳过未载位（Paper#6045 同构修复）。
- **0039 AttributeMap lambda 缓存**：捕获 this/supplier 均 final，字段缓存与
  每次新建 lambda 语义恒等；computeIfAbsent 调用形态不变。

## §4 7 月回移植（Papo 自著 patch 编辑）

- **attack check（ServerGamePacketListenerImpl 两行）**：外门加
  `isSpectator()` 早退 + 自交互 kick 条件去 `!isSpectator()`（外门后成死条件，
  移除正确）。行为差仅为"改版客户端伪造 spectator 交互包不再重置 idle 计时"，
  更严方向，无害。
- **#13886 loot tables（LivingEntity/Mob/AbstractChestedHorse）**：把基线旧的
  postDeathDropItems 机制整体换成 postDeathEventCallback——`lootTable =
  Optional.empty()` 从 dropFromLootTable 移入回调，仅在 `!deathEvent.
  isCancelled()` 时执行（调用点唯一：LivingEntity.dropAllDeathLoot:1996）。
  取消死亡事件（Paper 存活语义）→ loot 表保留 → 再次死亡正确重掷；
  shouldDropLoot false 时既不掷也不清，一致。
- **#13002（AbstractContainerMenu 一条件）**：`isRemoved()` → `!isAlive()`，
  死亡未移除窗口关容器时物品落地而非放回将清空的背包——与上游 PR 逐字一致。
- **#14080（CraftBlockData 直改 + 测试）**：reloadCache 为缓存实例补
  parsedStates（`new HashMap<>(getValues())`，正确适配本 fork 的 Map 字段类型；
  上游原版为 List 形态、不同 lineage）。上游同号提交 2d76c9c1d0 经
  merge-base 验证**非**基线祖先——无重复移植。
- **#13455/MC-301114（CombatTracker）**：上游自著樱桃挑选；entries 改
  ArrayListDeque + recordDamageAndCheckCombatState 内按
  misc.maxTrackingCombatEntries（默认 10240 enabled）截头；config 字段在
  GlobalConfiguration.misc 在位（本 fork 配置消费闭环）。

## §5 上游樱桃挑选保真（6 sources + features 内嵌 + api 4 文件）

- **#14011 throttle（ServerHandshakePacketListenerImpl 单行）**：
  `time > throttle` → `currentTime - time > throttle`，修复"到达阈值后整表
  清空"。上游原文。
- **waypoint 族（ServerLevel/ServerWaypointManager/WaypointTransmitter + 0001
  内嵌一行）**：`new ServerWaypointManager(this)` / locatorBarEnabled 赋值 /
  `distanceToSqr >= Mth.square(min)`（非负平方保序，等价）——与上游
  8fad415d57/d165ddb514 意图逐 hunk 一致；ServerWaypointManager 净改动 50 行
  与樱桃挑选 50 行对上。
- **ContainerOpenersCounter**：trapped chest 红石事件（上游 #12206 的 sources
  半边）。
- **timings executor 移除（api：Event/SimplePluginManager/
  TimedRegisteredListener/JavaPluginLoader + 测试删除）**：上游 #13969 原文。
- **datafixer schema skip（PaperBootstrap + updatingMinecraft 属性接线）**：
  上游 #14077/Nassim 原文，`paper.updatingMinecraft` 在 build.gradle.kts 以
  false 缺省接线。
- **0005 内嵌（climbing cache #12764 + unaware AI skip #13781）、0002（净内容
  零）、0020/0025/0028（净改动=偏移漂移 + 已审批 79 hunk）**：全部对上上游
  提交，无 Papo 自著语义。

## §6 0186 + ItemObfuscationSession（首次真审）

- `withContext(c -> c.itemStack(v))` → `withItemStack(v)`：不混淆早退
  `() -> {}`、`context().itemStack(x)` 恒构造新 record（checkState 不变量保持）、
  switchContext/返回 close 链逐句同序；try-with-resources 异常路径 close 还原
  previousContext 不变。
- `start()` 每级别缓存上下文：record 不可变、字段与 fresh 实例逐项一致
  (session,null,null,level)、ThreadLocal per-session 无跨线程共享、嵌套
  start→withItemStack→close 的 prev 链与 fresh 实例语义恒等。
- 调用点（0186，ItemStack.STREAM_CODEC 编码处）单点换调用，语义零差。
- 批 135 范围遗漏 + 批 137 错映射已就地勘误（两报告头部）。

## §7 散点

- **V5 commands 半边（GlobalConfiguration.Commands）**：
  papoResolveDefault 大小写不敏感、未知回退 TRUE（=现行为）；消费端
  CraftDefaultPermissions.papoOverrideCommandVisibility 的 null 守卫/启动期
  单次应用/recalculate 语义（136 已审）与本半边闭环。
- **品牌化**：Bukkit.getVersionMessage/PaperVersionFetcher（**不再对上游发起
  HTTP 查询**——外发面缩减而非扩张）/ServerBuildInfo.papoVersion()（api 默认
  Optional.empty，非 Papo 服 absent）/ServerBuildInfoImpl/TestServerBuildInfo/
  manifest（Brand-Id 保留 papermc:paper 供 isBrandCompatible，显式注释）。
- **PaperConfigurations.defaultFieldProcessors 返回类型放宽**（64a0cd911c）：
  私有静态方法、与被调方参数类型逐字相同后 javac 增量编译二进制签名陷阱解除，
  零 API/行为。
- **gradle.properties/build.gradle.kts**：papoVersion=0.80.0 与当前版本一致；
  createPapoJar Copy 任务纯产物命名。

## §8 功能完整性（表述 vs 实现）

| 表述（来源） | 实现 | 一致 |
|---|---|---|
| netty=cores/4 clamp[4,16]（批 78） | PapoParallelism.nettyEventLoopCount | ✓ |
| regionIo=cores/8 clamp[1,4]、显式配置优先（批 78） | MoonriseCommon ioThreads 三目 | ✓ |
| worker=cores/2 clamp[2,12]、-D/显式配置优先（批 80） | workerThreadCount + Integer.getInteger 行保持 | ✓ |
| 存量 spigot.yml 物化 4 保持 + INFO 提示（批 78） | count==4 && def>4 提示行 | ✓ |
| level.dat boot 路径同步（批 80） | modifyLevelDataWithoutDatafix → saveLevelData | ✓ |
| 备份读前等待（批 80） | makeWorldBackup awaitPending | ✓ |
| flush 语义覆盖离线程写（批 80） | saveAllChunks flush → awaitAll(60s) | ✓ |
| save-all/shutdown 全量保存契约（批 79） | PlayerList interval==-1 → awaitAll | ✓（133 §5 复认） |

## §9 不可信输入面

池 sizing 三键全为服务端侧（spigot.yml/paper-global/-D 系统属性/OSNuma 核数）；
level.dat/玩家文件路径全为服务端派生；回移植与樱桃挑选不触任何入站解析路径
（attack check 在入站处理上仅收紧伪造包行为）。**零新增可达输入面**。

## §10 长期运行内存界

- 池线程总数有界：netty ≤16 + worker ≤12 + regionIo ≤4 + IO 池（PapoOrderedFileWrites，
  133 §5 已审）——纯线程数，无常驻累积结构。
- lightningTick：每 chunk 1 int。
- startContexts：每线程 3 个不可变 record。
- CombatTracker：上限化（10240 截头）是本轮面内唯一的容量语义变化，方向为
  无界→有界（上游修复）。
- 0267/0268/0269：决断维持，未复发、未扩散。

## §11 验证

- 修复（C1，两处注释）落盘 src/main 直改文件：`./gradlew
  :paper-server:compileJava --rerun-tasks --no-configuration-cache` →
  BUILD SUCCESSFUL、EXIT=0（输出落文件双确认）。
- 本轮补丁树零触碰（0036-0039 等均只读审计），applyPatches 重放与
  check_patch_counts 不适用（沿批 132 fd3fc17d12 纯 src/main 注释修复判例）。

## 判例（可复用）

1. **"范围头声明 vs 正文段落"三连**（133→137→138 本 fork 审计史重复模式）：
   批 133 头列 PapoParallelism 但正文无池 sizing 段落、批 135 范围 0156-0204 漏
   0186、批 137 引用"135 §9"而 §9 是别的主题——**引用他轮覆盖时必须对正文段落
   做文本级核对，范围头/结论表不可传递信任**（137 已立此判例，本轮再证：连
   "映射表引用具体段落号"也可能指错段落）。
2. **fork 审计的权威面枚举 = 净 diff vs 上游基线**：按补丁号/提交号追踪会被
   编号重排与 hunk 偏移漂移欺骗（0001 的 16 行"改动"实为 index/偏移重排，净
   内容仅 1 行上游上下文）。先 `git diff <基线> HEAD --name-only` 定面，再逐
   文件剥内容行。
3. **回移植前先查目标提交是否已在基线**：`git merge-base --is-ancestor
   <上游提交> <基线>`——#14080 同号提交在基线附近时间窗出现（07-26 14:13 vs
   基线 07-26 21:33）但不同 lineage，逐字回移植才有意义；反之同 lineage 内重复
   应用会冲突暴露，不同 lineage 的"看似已存在"最危险。
4. **异步写 + 同步读写同文件的反转窗口要以调用方可达性收口**：理论竞态
   （makeWorldBackup/renameLevel vs 挂起 level.dat 写）在专用服务端零调用方即
   闭合——但须 grep 全树证零调用方 + boot 路径先于一切 enqueue 双重锚定，
   而不是"理论上是客户端专用"一句话带过。
