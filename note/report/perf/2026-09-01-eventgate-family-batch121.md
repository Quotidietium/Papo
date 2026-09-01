# R3 审计轮（批次 121）——事件门控/分配优化族 0200-0240 事后对抗审查（零缺陷闭合轮）

> 日期：2026-09-01。分支 `perf/multicore-r3`，输入版本 0.71.3（补丁链至 0268）。
> **无代码变更，版本保持 0.71.3**（沿用批次 115/116 无变更轮先例）。
> 本轮为常驻 goal 的持续审查迭代，覆盖面与批次 119（0260/0264/探针族/异步存档/输入面/
> 功能完整性）、批次 120（wire memo 族 0241-0248 + 登录预取）互补：**对 0200-0240 共 41 个
> 补丁（零监听器快路、scratch 复用、缓冲/零拷贝/netty 生命周期、广播去重、缓存门控、
> k-nearest 选择、纯计算内联、杂项）做设计文档之外的事后对抗审查**——以应用树
> （`paper-server/src`）实际代码为准，非补丁注释论证的复述。

## 一、审计范围与结论总表

| # | 审计面 | 补丁 | 方法 | 结论 |
|---|---|---|---|---|
| A | 零监听器快路族 | 0228/0229/0232/0233/0235/0238/0239/0240 | HandlerList 归属逐个在 API 源码核实；事件字段等价逐路径推演；0232 桶序哈希公式对照应用树 | **闭合，无缺陷** |
| B | scratch 复用族 | 0200/0201/0202/0220/0236/0255 | fill 重载追加语义、clear-first 纪律、重入路径、保留窗口、谓词逐字等价 | **闭合，无缺陷** |
| C | 缓冲/零拷贝/netty 生命周期族 | 0213-0217/0221-0225 | 尺寸上界支配性证明；引用计数逐路径（ensureCompatible 契约字节码实证）；attr 标记协议遍历推演；watchdog 零读者 grep 实证 | **闭合，无缺陷** |
| D | 广播去重族 | 0210/0211/0212 | 同实例豁免、onTeamChanged 伴生副作用、逐字节相同第二包、join 全量态不依赖广播流 | **闭合，无缺陷** |
| E | 缓存失效与门控 | 0234/0204/0209/0237 | 谓词无状态性；canSee 不变量写者全集；区块加载路径实证不经 cap 位点；@PostProcess 重建核实 | **闭合，无缺陷** |
| F | k-nearest 选择次序等价 | 0203/0220 | 平局披露复核；`floor>=1` 上游守卫实证；`addTo` 旧值语义对照上游 enderPearl 同型代码 | **闭合，无缺陷** |
| G | 纯计算/杂项族 | 0207/0208/0218/0219/0227/0230/0231 | FP 等价逐项对照应用树（getNextEntityPos 索引、normalize 守卫、a-b≡a+(-b)）；0207 单线程域核实 | **闭合，无缺陷** |
| H | 功能完整性+未信任输入面 | 全族 | 配置键消费者逐一核实（0209/0237/0225）；入站帧守卫保持；0205 方向再证 | **闭合，无缺陷** |

**总结论：0200-0240 全族零新缺陷。** 连同批次 119/120 与三轮既往审计
（0.25.1 全量、批次56 send-lambda 线程安全、批次70 失效信号），**Papo 整条补丁链
（pre-0200 + 0200-0240 + 0241-0268）至此全部经过事后对抗审计。**

## 二、逐面记录

### 2.1 面A：零监听器快路族（闭合）

- **HandlerList 归属核实（0228 的关键声明）**：`InventoryCreativeEvent` 在 paper-api
  源码中**无自有 HandlerList 声明**（grep 实证仅 InventoryClickEvent.java 有
  `private static final HandlerList`），其监听器注册进父类 InventoryClickEvent 的单一
  列表——0228 检查父类列表即完备。`InventoryDragEvent` 有自有 HandlerList（0229 检查
  自身列表）✓；0235/0238/0239（PreCreatureSpawnEvent/PlayerAttackEntityCooldownResetEvent/
  PrePlayerAttackEntityEvent）均为自有列表、无 API 子类（子类监听器本就收不到父类事件
  实例，检查不受影响）。
- **事件字段等价**：0233 把 torch 分支的「构造时 newCurrent=old、dispatch 前 set 目标值」
  折叠为 `handleRedstoneChange(level,pos,old,target)`——dispatch 时刻字段逐项相同
  （block/oldCurrent/newCurrent），返回值即 `getNewCurrent()`，abort 比较等价。
  0232 两站点（RedStoneWireBlock + DefaultRedstoneWireEvaluator）与 0125/0134 已迁移
  的 11 站点同型（handleRedstoneChange 零监听器时原样返回 new，CraftBlock 构造跳过）。
- **0232 桶序复刻数学实证**：注释声称 `Vec3i.hashCode = (y + z*31)*31 + x`——对照应用树
  Vec3i.java:50-52 逐字一致（`(getY() + getZ()*31)*31 + getX()`）。桶索引
  `spread(h)&15`、7 元素 < resize 阈值 12、单桶 ≤7 < 树化阈值 8、桶内插入序——
  HashMap 迭代次序复刻成立的全部前提齐备。偏移表（PAPO_OFF_*/DIRECTIONS[6]）与
  Direction.values() 序一致（DOWN/UP/NORTH/SOUTH/WEST/EAST）。
- **非监听副作用**：0240 两事件的 `i = event.getDamage()` 在零监听器下恒等于构造值；
  0239 的 PlayerVelocityEvent 站点零监听器下 `cancelled=false` 且
  `velocity.equals(event.getVelocity())`（构造克隆按值相等）→ 整块为无操作；
  0235 的 `shouldAbortSpawn()` 只在 callEvent 返回 false 分支读取。
- **动态注册竞态**：与既往轮同一判定——注册/检查均在主线程或烘焙失效即重读，
  并发注册的边缘与 CraftBukkit 自身竞态同级，不构成回归面。

### 2.2 面B：scratch 复用族（闭合）

- **fill 重载语义**：`Level.getEntities(EntityTypeTest, AABB, Predicate, List)`（应用树
  Level.java:1730）为**追加**语义——全部调用点 clear-first（0200/0201/0202/0236 均
  clear 后 fill）✓。
- **谓词逐字等价**：0200 原码为三参 `getEntitiesOfClass(cls, box, livingEntity->true)`
  （显式谓词替换 NO_SPECTATORS）→ 补丁保持 `livingEntity->true` ✓；0201 原码为二参
  重载——应用树 EntityGetter.java:29 实证二参默认谓词即 `EntitySelector.NO_SPECTATORS`，
  补丁显式传同一常量 ✓；0202 保持 Paper 移入的三参谓词逐字不动 ✓；0255 同理（二参
  `getEntities(this, aabb)` ≡ NO_SPECTATORS）且 `papoGetEntitiesInto` 与分配版
  逐语句相同（除分配）✓。
- **重入**：0200/0201 的 canUse 内扫描（getNearestProperty 路径无事件）；0202 doTick
  排序后一次遍历；0236 fill-then-iterate（moonrise 实体查找本就物化 List，快照语义与
  vanilla 相同）；0255 playerTouch 链（EntityPickupItemEvent 等插件回调）不回填 scratch
  （scratch 私有于 aiStep 块）。均无共享/嵌套使用面。
- **0236 循环内 isMergable 重查移除**：原重查仅在「fill 与访问之间邻居状态可变」时有意义；
  tryToMerge 只触及 `this` 与当前元素（合并/丢弃），不触及后续元素 → 重查为死代码，
  移除等价。墙体穿透 continue（Paper fix）保留。
- **保留窗口**：scratch 持邻居引用至下次使用（几 tick 内），实体 GC 延迟可忽略，
  不阻碍区块卸载（区块回收不经 Java 强引用）。0220 每玩家数组 ≤64 long+64 int，
  按需增长、`[0, papoSel)` 每次全量重写。

### 2.3 面C：缓冲/零拷贝/netty 生命周期族（闭合）

- **0213/0214/0215 尺寸数学**：帧缓冲 `3 + readableBytes()` 恰为最坏需求（varint21 ≤3）；
  压缩初缓冲 `n + (n>>>11) + 32` 支配实测 DEFLATE 存储块最坏界 `n + 5*ceil(n/16384) + 6`
  （~1.6× 裕量），重试路径保留为兜底；编码尺寸提示仅容量（不可观察），不足时
  ensureWritable 常规增长。
- **0217 引用计数（关键支点字节码实证）**：压缩路径 `ensureCompatible` 原样返回时是否
  retain 决定 `compatibleIn.release()` + `in.release()` 是否双重释放——反编译
  velocity-native-3.4.0-SNAPSHOT.jar 的 MoreByteBufUtils：`isCompatible → aload_2;
  invokevirtual ByteBuf.retain()`（返回前 retain）✓ 契约为「返回值携带调用方须释放的
  一次额外引用」，两次 release 平衡（vanilla 拷贝路径本就是同一组合，互证）。
  低于阈值路径：setByte(HEADROOM-1,0) 写入 headroom 内，readerIndex 回退 1，
  prepender 右对齐写帧长 varint 于 [end-size,end) 不覆盖数据长度字节；索引界
  [2,6)⊂[0,HEADROOM) 恒成立。`papoConsumed` 在 ctx.write 前置位（宁泄漏勿双放）。
  异常路径：out 未发布即 release；attr 陈旧标记由每个消费者先清。
- **attr 标记协议**：发布仅在 prepender 为 Varint21LengthFieldPrepender 时（内存通道
  LocalFrameEncoder 不发布，避免 pin）；三阶段均在通道单一 event loop、同一次 write
  遍历内 set/consume，无嵌套 write（包编码不触发递归写）；无压缩阶段时 prepender 直接
  消费标记，语义正确（无数据长度 varint）。
- **0216 压缩器复用**：vanilla setupCompression 重跑时新压缩器被 decoder.setThreshold
  忽略（仅无压缩器时采纳）而成孤儿——补丁改为复用 decoder 活实例，encoder/decoder
  共享同一实例本就是 vanilla 既有布局（单 event loop 使用）；压缩级别 clamp 仅在
  `create(level)` 抛 IllegalArgumentException（配置 0 或 10-12 落在对应后端合法域外）
  时触发，合法配置零差异，级别来自服务端配置非用户输入。
- **0221/0224 死插装门控**：`getCurrentPacketProcessors`/`getTotalProcessedPackets`
  零读者 grep 实证（全树仅 PacketProcessor.java 自身与 Connection 推弹点——推弹已在
  门内）；WatchdogThread 不引用 PacketProcessor（grep 实证）。static final false 下
  JIT 全消。
- **0222 retainedSlice**：与 netty 自家 LengthFieldBasedFrameDecoder.extractFrame 同型；
  下游（解压/解码）只读；FlowControlHandler 跨读队列钉住至多一个读批（LTFBD 同语义）。
  入站长度守卫（零长度/21 位宽度/readableBytes>=i）全部保持，未信任帧长有界。
- **0223 flush 任务缓存**：`this::flush` 运行时读 channel，执行时经 flush() 自身
  inEventLoop 再分派；与原 lambda 语义逐项相同。
- **0225 字节计数**：AtomicLong event loop 写、主线程 tickSecond getAndSet 快照，
  总量=逐秒快照累加（无重复计入）；Sharable 注解下计数器为每连接实例字段（披露：
  若未来共享实例只并计数不会错计）。

### 2.4 面D：广播去重族（闭合）

- **0210/0211 同实例豁免**：所有 Component setter 的跳过门均为
  `新值 != 旧值引用 && 内容相等`——同实例重设保留 vanilla 重广播（NMS 原位变更的
  刷新习惯不受影响）；通过 Bukkit/Paper API 设置时组件每次新建（String→Component/
  Adventure→NMS），内容相等即客户端已持有等值渲染，可证无操作。
- **onTeamChanged 伴生副作用**（应用树 ServerScoreboard.java:202-207）：被跳过的调用
  除广播外还含 `updateTeamWaypoints(team)` 与 `setDirty()`——前者为 team 派生态的
  重建（值未变→派生态未变，跳过无观察差异），后者为存盘标记（值未变→无差异可存）。
- **0211 numberFormatOverride**：`mutableBoolean.isTrue() || !Objects.equals(...)`——
  同一 ScoreAccess 内已有其他变更时无条件发送（保守门）；值等时跳过字段赋值，
  观察者所见按值等价。
- **0212 display slot 去重**：仅去重同一 `setDisplayObjective` 调用内逐字节相同的第二包
  （CraftBukkit 结构性重复）；stopTracking/startTracking 分支不受门影响；新观众 join
  走全量态同步不依赖广播流。
- **残留披露（非缺陷，NMS 反射专属）**：原位变更旧组件实例后设置内容相等的**新**实例
  → 不重广播（客户端停留旧值直至下次变更）。公开 API 不可达（所有 API 路径均新建
  组件），维持披露不修。

### 2.5 面E：缓存失效与门控（闭合）

- **0234**：「缓存」对象是无状态谓词 lambda（捕获 Direction 常量，调用时求值），
  无失效面。
- **0204 canSee 跳过不变量**：`seenBy ∋ player ⟹ canSee=true` 的写者全集核对——
  hiddenPlayers/invertedVisibility 两集合的全部写路径（hidePlayer/showPlayer/
  setVisibleByDefault/插件禁用清理）均先经 untrack 链移除 seenBy；seenBy 唯一添加点
  在 flag（含 canSee）为真分支内。主线程域。
- **0209 数据完整性（本轮重点实证）**：区块加载路径 `EntityStorage.loadEntities →
  loadingInbox → addEntity`（存储层）**不经过** `ServerLevel.addEntity`（cap 检查位点，
  应用树 PersistentEntitySectionManager.java:270-281 + NewChunkHolder.java:124 实证）——
  开启上限不会在加载期丢弃存档物品，无数据丢失面；cap 只拦新生路径且置于 captureDrops
  之后（方块破坏捕获掉落不受影响）。计数器维护回调与上游 enderPearlChunkCount 同一
  线程域（moonrise 主线程回调契约）、同一 map 类型。
- **0237**：`papoDespawnRangesByOrdinal` 在 despawnRanges 的同一 @PostProcess
  （precomputeDespawnDistances，配置加载/reload 时）重建（应用树
  WorldConfiguration.java:216-219 核实）；map 以全类别播种（@MergeMap 保键），
  每序数有项，无越界/空槽面。

### 2.6 面F：k-nearest 选择次序等价（闭合）

- **`floor>=1` 守卫实证**：`sendNextChunks` 在 `collectChunksToSend` 前有
  `if (!(this.batchQuota < 1.0F))`（应用树 PlayerChunkSender.java:66）→ 进入选择分支
  时 `Mth.floor(batchQuota) >= 1`，空数组索引（floor=0 时 else-if 读 `papoSelDist[0]`）
  **不可达**；且 else-if 仅在填充阶段完成后才求值（papoSel==floor≥1）。
- **选择/排序语义**：k 近邻维持（严格 `<` 驱逐最远持有项）+ 选择升序排序——与
  Guava `Comparators.least`（升序返回）在距离互异时逐一相同；边界平局两实现均不
  指定且不可观察（同 tick 批发/余者留待后续 tick，客户端无从区分），披露成立。
- **`batchQuota - list.size()` 不下穿**：list.size() ≤ papoSel ≤ floor ≤ batchQuota。
- **0220**：scratch 单调用点（sendNextChunks 每 tick 一次，主线程）、按需增长、
  `[0,papoSel)` 每次全量重写；batchQuota 涨落后大数组复用安全。

### 2.7 面G：纯计算/杂项族（闭合）

- **0230 FP 等价逐项对照应用树**（两项与记忆中旧版 vanilla 不同、以树为准的实证）：
  ① `Path.getNextEntityPos = getEntityPosAtNode(entity, nextNodeIndex)`（Path.java:84-86，
  **非** nextNodeIndex+1——内联用 `getNextNode()` 正确）；② `Vec3.normalize` 守卫为
  `1.0E-5F`（Vec3.java:84，**非** 1.0E-4）。另：`a-b ≡ a+(-b)` IEEE754 逐位成立
  （含 ±0）；lengthSqr/点积分量序保持；normalize 零向量分支（分量 0 → 点积 0 →
  `0<0` false）与 vanilla 一致。getGroundY 可变 scratch：move(DOWN)/move(UP) 严格
  对应 below() 原位；tick 单线程每 mob（0190 先例）；canMoveDirectly 为虚方法故
  Vec3 实参保留物化（批次31 红线遵守）。
- **0207**：`tryMarkConsumed`/`isConsumed` 唯一调用点 processQueue（grep 实证），
  play 期主线程、login/status 期 synchronized(pendingActions) 串行——plain boolean
  足够（同锁 happens-before）。
- **0208**：`instanceof BundlePacket → unbundlePacket`（内部 type() 检查失败仍落
  list.add），非 Bundle 直接 add——两臂输出与原单臂逐一相同。
- **0218**：两析取臂均为纯谓词（isMainThread/isReady/isEmpty/getter 链），求值序
  不可观察。
- **0219**：容量预置 4（2-4 包典型形），ArrayList 动态增长兜底。
- **0227**：join 内两次 loadPlayerData 合一——两消费点均只读（start() 读 bukkit
  引用/出生点，spawn() 喂 load）；空结果缓存成立（join 中途无 .dat 写者）；与
  0249-0251 预取管线正交（loadPlayerData 自身缓存感知）。
- **0231**：Present 条件原位读——`Present.createAccessor` 对未注册（null）与缺席
  （empty）同产 null accessor，折叠为单次 null 原始读等价；MemoryCondition 为
  final record 无第三方实现；Accessor 构造（IdF.create 直构）与
  `trigger.createAccessor(brain, Optional.of(v))` 等价。

### 2.8 面H：功能完整性+未信任输入面（闭合）

- **配置键消费者逐一核实**：`entities.spawning.itemEntityLimitPerChunk`（默认 -1=禁用，
  带 Comment；消费点 ServerLevel.addEntity）；`papoDespawnRangesByOrdinal`（@PostProcess
  重建+transient）；0225 四个 wire 字节 getter 全部被 NetstatCommand 消费（52/64-67/
  89-92 行——/paper netstat 出/入 per-sec 与累计列）；0214 尺寸提示/0217 headroom/
  0216 clamp 无新配置面。
- **未信任输入面**：本族 41 补丁全部为服务端内部路径——唯一触及客户端可控数据的
  0222 只改帧提取方式，长度守卫（零长度 CorruptedFrameException、21 位宽度、
  readableBytes>=i 才切片）保持 vanilla/Paper 原样；0205 复核为出站方向
  （ServerConfigurationPacketListenerImpl 发送 ClientboundCustomPayloadPacket(BrandPayload)），
  非输入面（批次120 结论再证）。0209 cap 检查在 spawn 事件之前（启用时被拦的新生
  掉落不触发事件——配置 Comment 已述明设计意图）。
- **文档声明 vs 实现**：0232 注释的哈希公式、0230 注释的两处 FP 声明、0220/0203 的
  平局披露、0216 的 clamp 触发条件——均与应用树实际代码一致（本轮逐项核对）。

## 三、本轮验证方法论备注（判例）

1. **补丁注释的「verified/perf:」声明不可作为审计证据**——本轮三处与通行记忆相悖的
   声明（Vec3i 哈希公式、normalize 守卫常量、getNextEntityPos 索引）全部以应用树
   逐字对照收案，其中任何一处若按记忆「纠正」反而会引入缺陷：审计必须锚定树。
2. **第三方契约的字节码实证**：`ensureCompatible` 的 retain 语义是 0217 引用计数
   成立的支点——反编译依赖 jar 一行 `invokevirtual retain` 胜过任何推理；同类
   「上游同型代码背书」（enderPearl 计数器 vs itemEntity 计数器）同理。
3. **零缺陷轮的价值锚**：本轮 41 补丁八面全部闭合，说明 0200-0240 落盘期的等价性
   论证质量整体可靠；整链审计闭环后，后续轮次可转向（a）新变更增量、（b）跨族
   交互（如 0217×0260×0268 三方在 locale 门控+批量化+零拷贝叠合时的字节流）、
   （c）运行时探针数据（PapoDiag 回传）驱动的定向复查。

## 四、披露项汇总（本轮无新增修复）

| 披露 | 级别 | 依据 |
|---|---|---|
| 0210/0211 NMS 原位变更+等值新实例设置不重广播 | 仅 NMS 反射可达，公开 API 不可达 | §2.4 |
| 0203/0220 选择边界平局次序两实现均不指定 | 不可观察（同 tick 批发） | §2.6 |
| 0209 启用时超限新生掉落不触发 spawn 事件 | 配置 Comment 已述设计意图 | §2.8 |
| 0225 Sharable 注解下若未来共享 prepender 实例则并计数 | 每连接实例化现状下无影响 | §2.3 |
