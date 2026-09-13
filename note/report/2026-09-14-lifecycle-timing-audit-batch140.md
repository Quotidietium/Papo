# 批次 140（2026-09-14）：生命周期与时序面对抗审计（1 行为缺陷 + 1 注释勘误修复，0.80.0 保持）

第九面（与 132-139 八面互异）：**生命周期与时序面**。前八面（红石/出站登录/入站解码容器/
早期分配/网络pivot+刷怪战斗/跨家族交互/fork基线净diff/继承导入）审计的都是**稳态语义**
（"这段代码做的事对不对"）；本轮审计正交维度——**"状态随时间走完全程后是否处处收敛"**：
玩家断连/实体移除/区块卸载/世界卸载/关停重载路径上所有 Papo 长生命周期状态的清理一致性、
异常路径的状态收尾、长期高负载高频 join/leave 下的内存界与等待界。盘点方式：
`grep -o papo[A-Za-z]*` 全树枚举 Papo 运行期状态标识符（约 300 个），归类为 15 个状态机
族逐一核对 创建点/所有权/清理点/异常路径/单调增长。

## 结论表

| # | 状态机族（补丁来源） | 生命周期核对 | 结论 |
|---|---|---|---|
| 1 | PapoOrderedFileWrites（批79/82/91，main树） | TAILS compute/remove 两相一致（value-match remove 保新链：`remove(target,node)` 仅当映射仍指向本节点）；pending 增减在 whenComplete 对称；ISE 内联回退两处；awaitPending 单尾 60s 界+Timeout 即退；awaitAll 截止时间界+中断恢复 | **闭合** |
| 2 | 登录预取族（0249，批82/87） | 见下节专述；F1 修复 | **1 缺陷已修** |
| 3 | 共享区块包缓存（0241/0242） | LevelChunk.papoPacketBufferCache / ChunkHolder.papoSharedChunkPacket 随 owner 生死（区块卸载→GC），单槽覆写无全局表；PapoSharedWireMemo 每 packet 至多一个压缩段，chunk 常驻包明确不 arm encode 快照（防 ~40KiB 钉住，设计内文档明示）；PAPO_LEVEL_EPOCH 静态单调无清理需求 | **闭合** |
| 4 | 配对共享缓存（0247/0248） | ChunkMap.updatePlayers 的 begin/end 有 **try/finally** 括号；papoEndPairingShare depth 归零清缓存 → sweep 内异常逃逸不残留 depth>0，后续配对不会回放陈旧缓存；缓存随 ServerEntity 生死 | **闭合** |
| 5 | 压缩器池（RegionFileVersion） | 借出置空 ThreadLocal 槽位；PapoDeflaterOutputStream/PapoInflaterInputStream.close() 的 **finally** reset+归还（super.close() 抛 IO 亦归还）；并发嵌套流 displaced 丢弃给 GC/Cleaner（注释明示=旧每流语义）；config reload 等级变更 end() 退役旧压缩器 | **闭合** |
| 6 | Connection.setupCompression（0230s/133） | live compressor 复用（防每次重配孤儿 native 上下文）；papoStampLevel 仅新压缩器路径触达，同级别不 churn epoch | **闭合** |
| 7 | 关停顺序（批79/80） | saveAll(interval==-1)→awaitAll(60s)；saveAllChunks(flush)→awaitAll(60s)；stopServer→saveEverything→…→MoonriseCommon.haltExecutors（MinecraftServer:1087）次序正确；IO 池 60s 优雅排空后 halt | **闭合** |
| 8 | netstat 计数器（Connection） | 每 Connection 生死；AtomicLong 累加+volatile 快照；tickSecond 每 20 tick 轮转窗口；总量 long 无实际溢出 | **闭合** |
| 9 | PapoWireDirtyTracking（批126-131） | 8 条带锁；每条带 2^18 阀（失败方向=staleness 非多余重算，设计内）；评估即清；per-Level 实例随世界生死 | **闭合** |
| 10 | 0230 物品计数器 | add/remove/cross-chunk-move 三回调单步自洽；归零 remove（批136 A3 修复后）；与 enderPearlChunkCount 参考实现同构；Long2IntOpenHashMap 缺省 0 | **闭合** |
| 11 | ItemObfuscationSession（0186） | ThreadLocal 会话 close 归 root；cached startContexts 与 fresh 上下文逐字段同（唯一身份检查 withContext 的 != 断言因 wither 恒构造 fresh 而成立）；netty 线程复用下无跨连接残留（context 链经 close 逐层还原） | **闭合** |
| 12 | PapoTickProfile / PapoParallelism | 诊断门控系统属性默认关；窗口清零（COUNT_KEYS 为有界键集，仅计数器名）；并行度纯函数无状态 | **闭合** |
| 13 | PrepareSpawnTask（批87/82） | papoFinishClaim CAS 单发（reentrant tick 双路径安全）；close() 取消 Preparing future；papoLoadPlayerDataOnce flag 主线程串行安全（start/spawn 均主线程窗口） | **闭合** |
| 14 | 各 per-entity scratch（MoveToBlockGoal 缓存、k-nearest、lastKnown 位置外推等） | per-entity/per-goal 字段随 owner 生死 | **闭合** |
| 15 | CraftPlayer papoPc/papoCfg（指纹加固） | 方法局部变量，非缓存 | **闭合** |

## 第 2 族专述：登录预取族全链生命周期

**触发界（洪水）**：预取仅在 startClientVerification（认证后、AsyncPlayerPreLoginEvent
后）触达，键=UUID（非客户端可控字符串）；离线模式洪水下每在飞登录 ≤3 个 map 条目+3 个
TAILS 链节点，界=并发连接数（与 vanilla 每连接内存同阶）。完成后的条目持有已解析 payload
（重型玩家 .dat 可达 MB 级），但生命周期≤登录窗口。

**丢弃链**：login listener onDisconnect（authenticatedProfile 在预取**之前**置位，判空
不漏）+ config listener onDisconnect（与 prepareSpawnTask.close() 同点）。

**消费链**：.dat 于 PrepareSpawnTask.papoLoadPlayerDataOnce（→PlayerDataStorage.load→
papoConsumePrefetch）；stats/adv 于 **ServerPlayer 构造器 474-475 行无条件创建**
（getPlayerStats/getPlayerAdvancements 惰性分支在构造时必走 null→新建）。关键排除：
非持久玩家（NPC 类）save() 早退不构造 stats/adv 的疑虑不成立——构造发生在 ServerPlayer
ctor，早于任何 save 路径；PlayerSpawnLocationEvent 的 legacy 临时 ServerPlayer（ctor 同样
消费）在 spawn 时被复用为正式实例，计数器随实例延续。**所有加入路径三预取必被消费**，
中途异常由活动 listener 的 onDisconnect 兜底（listener 切换前后各有归属）。

**并发重登录**：同 UUID 第二连接 putIfAbsent 落败 → 孤儿 future 由 TAILS whenComplete
自清；后续 take miss → 同步回退（=vanilla 路径），仅优化损失。`.dat_old` 读序：其写入
（safeReplaceFile 第三参）恒在 .dat 键链任务内，load(".dat") 的 awaitPending 即覆盖
.dat_old 可见性；预取读在同一链任务内先 .dat 后 .dat_old，序正确。locateStatsFile 的
legacy name.json 迁移 corner 返回 path1 时 take miss → 同步回退，良性。

**数据操作面**：异步写在 per-target 链上序化（陈旧快照永不覆盖新快照）；所有同步读回退
路径均先 awaitPending（PlayerDataStorage.load(".dat")/ServerStatsCounter:89/
PlayerAdvancements:138/LevelStorageSource:624）；重命名/备份副作用全部收敛在主线程消费点
（中止登录不留改名残迹——与同步路径副作用时序逐点一致）。

## F1（0249，本轮唯一行为缺陷）：stats/advancements 消费点无界 join()

.dat 侧消费（PlayerDataStorage.papoConsumePrefetch）明文契约："Bounded wait (same
budget as the batch 79 awaitPending): a wedged stage degrades to the synchronous path
instead of hanging the main thread"，用 `get(60, SECONDS)`。但 stats/adv 侧两个消费点
（ServerStatsCounter 构造器 / PlayerAdvancements.load）用的是**无界 `future().join()`**
——同族兄弟路径的等待契约漂移，逐补丁审计各自为政时不可见。

可达挂死场景：批 91 已证 `halt(false)` 残留窗口（60s 优雅排空超时后）`queueTask` 返回
永不调度的 Task、future 永不完成；或病态 IO 池 wedge。此时预取已在登录早期成功入列
（enqueueRead 返回非 null），ServerPlayer 构造器在 join() 上**永久挂死主线程**——不是
数据丢失等价物（批 91 对写路径的接受论证），而是服务器级冻结，严格劣于 vanilla 同步读
（同步读在 halt 后仍可完成）。全树 grep 证实 Papo 代码中无界 join 仅此两处（MCUtil 为
上游 Paper 自带）。

修复：与 .dat 侧同构——`get(60, TimeUnit.SECONDS)` 包 try/catch，超时/异常置 null 落入
原同步路径（其 awaitPending 自身 60s 有界，Timeout 即放行读文件）。最坏路径合计有界
（≤120s/病态加入），语义与"无预取"回退完全一致。

## F2（0249，docs）：BLOCKING 表述与实现漂移

burst 验证轮已将预取读从 BLOCKING 改为 NORMAL+消费点升级（enqueueRead javadoc 明载
动机：BLOCKING 读抢占并发 join 的出生区块 IO、端到端更慢），但两处文档未跟上：补丁
正文 "reads run at Priority.BLOCKING (the priority vanilla sync-loads use)" 与
PlayerDataStorage.papoPrefetch 注释 "BLOCKING priority"。就地勘误为如实机制描述
（NORMAL 严格增量 + 消费点将等待时升级 BLOCKING），fallback 行补 bounded-wait timeout
一类。纯注释/提交说明零行为差异。

## 不可信输入面 / 内存界

不可信输入面零新增（本轮触达的预取键=UUID、计数器键=服务端 packed pos、memo 键=packet
实例，无一处读客户端可控数据作键或路径）；内存界无新增无界结构（预取条目生命周期≤登录
窗口、其余 14 族均随 owner 生死或有界阀）；0267/0268/0269 决断维持。

## 观察（不修，记录在案）

1. papoConsumePrefetch 的 `catch (Exception)` 吞中断标志未恢复（awaitPending 批 91 已
   恢复）——shutdown-interrupt 下退化为同步读，与 vanilla 同阶，保持与现状一致。
2. getPlayerStats(GameProfile) 离线查询路径会按路径消费在飞预取（take 命中）致加入方
   同步回退——正确性无损（回退=vanilla），需插件在登录窗口查询同玩家统计才可达，罕见。

## 验证

- `python note/check_patch_counts.py 0249` ALL OK；全仓 918 文件 ALL OK。
- `./gradlew applyPatches` 全量重放 EXIT=0；重生成树三文件逐一核对含两处修复
  （PlayerAdvancements:123-130 / ServerStatsCounter:79-86 / PlayerDataStorage javadoc）。
- `./gradlew :paper-server:compileJava --rerun-tasks` BUILD SUCCESSFUL EXIT=0
  （仅上游既有 deprecation/unchecked 警告）。
- 手改 hunk 计数本轮再次差 2（13-5=8 行非预估 6）——check_patch_counts 兜住后按实际值
  修正，批 137 判例重演（印证：手改 hunk 后必须跑计数器，不能心算）。

## 判例入库

1. **同族兄弟路径必须横向对齐等待契约**：.dat 有界 vs stats/adv 无界的契约漂移，逐补丁
   各自审计不可见——审计"优化族"时要枚举该族全部消费/等待点做横向对比，而非只看本补丁。
2. **"future 永不完成"是一等挂死类**：halt 残留/池 wedge 下无界 join 把设计好的"降级
   同步路径"变成永久主线程冻结；异步结果的每个消费点默认必须有界。
3. **行为调优后文档双向跟随**：BLOCKING→NORMAL 的实现变更留下了补丁提交说明与方法
   注释两处陈旧表述——表述-实现一致性核查范围必须包含补丁正文（commit message）本体。

审计轮不 bump 不发布，版本 0.80.0 保持。
