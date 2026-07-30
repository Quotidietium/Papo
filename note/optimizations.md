# Papo 性能优化日志

> 记录每批 Papo 自有的底层性能优化（非上游回移/Pufferfish 移植，那些见 git 历史）。
> 每条注明：文件/方法、为何是热点、改法、行为等价性证明、风险、对应补丁号。
> 红线：稳定性、安全性、API/行为兼容。优先可证行为等价的改动。

## 工作流要点（本机实测，详见 build.md）

- 所有 `gradlew` 调用一律 `--no-daemon`；长任务（applyPatches 等）后台跑。
- **cwd 陷阱**：本会话期间前台 `cd` 会改变后续前台命令的 cwd，后台命令的 cwd 默认在内部仓库目录。所有 gradlew 调用一律显式 `cd /f/Github/repo/Papo &&`（或单独一行的 cd）。
- 改 `paper-server/src/minecraft/java` 源码 → 内部仓库分次 `git commit`（每个优化一次提交=一个 feature 补丁）→ `./gradlew :paper-server:rebuildPatches`（单独调用）→ 完整 `applyPatches` 验证 → 外层仓库提交补丁文件。
- rebuildPatches 会自动 `git add` 生成的补丁，**提交时注意只 stage 本次目标的文件**（否则多个补丁会被一次 commit 吞掉）。

---

## 批次 1（2026-07-30）：热点路径分配/冗余消除

四个独立文件、各自可证行为等价、各自一个 feature 补丁（0040–0043）。全部 compileJava + 全量 test 通过，applyPatches 干净应用。

### 0040 — CompoundTag.write 消除每条目二次哈希查找
- **文件**：`net/minecraft/nbt/CompoundTag.java` `write(DataOutput)`
- **热点**：核心 NBT 序列化，每个 chunk/entity/player/block-entity 存档递归调用。原实现 `for key : keySet() { get(key) }` 每条目两次哈希探测 + 每 Entry 分配。
- **改法**：照搬同文件 `copy()` 的 Paper 模式，`Object2ObjectOpenHashMap.object2ObjectEntrySet().fastIterator()`（字段类型为 `Map`，三元判 `instanceof` 后 cast），复用 Entry、单次探测。
- **等价性**：fastutil 的 fastIterator 与 keySet() 遍历同一底层数组、同一顺序，输出字节完全一致。
- **风险**：低。

### 0041 — ExperienceOrb 磁吸复用 sqrt、减少 Vec3 分配
- **文件**：`net/world/entity/ExperienceOrb.java` 磁吸段
- **热点**：XP 刷怪塔数百~数千经验球每 tick 向玩家磁吸。
- **改法**：原 `vec3.lengthSqr()`→`Math.sqrt(d)`（算 d1）后又 `vec3.normalize()`（内部再算一次 sqrt）。改为用原始 double dx/dy/dz，算一次 `len=sqrt`，把 `normalize().scale(d1*d1*0.1)` 折叠成标量 `d1*d1*0.1/len`，`Vec3.add(double,double,double)`。
- **等价性**：与 `vec3.normalize().scale(...)` 数学等价；零长度守卫对齐 `Vec3.normalize()` 的 `squareRoot < 1.0E-5`（注意是 1e-5，非 1e-4）。省 3 个 Vec3 + 1 次 sqrt/球/tick。
- **风险**：低。

### 0042 — Level.getEntities 删除死代码 ArrayList
- **文件**：`net/world/level/Level.java` `getEntities(Entity,AABB,Predicate)`
- **热点**：所有弹射物命中检测 / 实体碰撞查找的后端，每弹射物每 tick。
- **改法**：删除 `Lists.newArrayList()`——其下 Paper 区块系统重写用的是并返回独立的 `ret` 列表，该分配每次调用即丢弃。
- **等价性**：该变量之后无任何引用，删除零影响。
- **风险**：零。

### 0043 — AbstractContainerMenu.canItemQuickReplace 缓存 getItem
- **文件**：`net/world/inventory/AbstractContainerMenu.java` `canItemQuickReplace`
- **热点**：QUICK_CRAFT 拖拽循环内每候选槽位调用。
- **改法**：`slot.getItem()` 最多查一次，空槽位短路。与原三元逻辑等价（空槽→true；有物同物→数量判定；有物异物→false）。
- **等价性**：逻辑等价，原 `&&` 短路本就不在空槽路径调 getItem。
- **风险**：零。

### 附：测试桩修复（非优化，属稳定性）
- 品牌化提交把 `Bukkit#getVersionMessage` 改成读取 `minecraftVersionId()`，而 `TestServerBuildInfo`（测试桩）该方法抛 `UnsupportedOperationException`，导致整个 paper-api 测试套件类初始化即崩。改为返回占位值（与 `asString` 返回 "" 同理），恢复绿测基线。仅测试代码，无运行时影响。

---

## 批次 2（2026-07-30）：tick 与刷怪热路径分配/扫描消除

### 0044 — Level.tickBlockEntities 复用移除集 + 跳过空闲 removeAll
- **文件**：`net/minecraft/world/level/Level.java` `tickBlockEntities`
- **热点**：每世界每 tick。原实现每 tick 新建 `ReferenceOpenHashSet` + 无条件 `blockEntityTickers.removeAll(toRemove)`（即使无可移除项也 O(n) 扫描）。
- **改法**：`toRemove` 提为实例字段，每 tick `clear()` 复用（保留容量）；`size() > 1`（即除 null 哨兵外有真实项）才 removeAll。
- **等价性**：与 Paper MC-117075 removeAll 行为一致；size==1 时 removeAll 本就只尝试移除 list 中的 null（不存在），跳过它结果相同仅更快。
- **风险**：低（方法非重入，字段仅此方法用）。

### 0045 — NaturalSpawner 刷怪距离检查免分配
- **文件**：`net/minecraft/world/level/NaturalSpawner.java` `isRightDistanceToPlayerAndSpawnPoint`
- **热点**：刷怪循环内每候选位置调用。
- **改法**：`closerToCenterThan(new Vec3(...), 24.0)` → `distToCenterSqr(double,double,double) < 576.0`（24²=576，去掉 Vec3）；同块判定 `cx==chunk.getPos().x && cz==chunk.getPos().z` 短路，免去 `new ChunkPos`。
- **等价性**：`closerToCenterThan` 即 `distToCenterSqr < Mth.square(d)`，且 `distToCenterSqr(Position)` 转调 double 重载；`ChunkPos.equals` 纯按 x/z，`ChunkPos(BlockPos)` 用 `>>4`。逐项验证。
- **风险**：低。

---

## 批次 3（2026-07-30）：流体/NBT/AI 协议级优化

### 0046 — FlowingFluid.isSolidFace 复用方块状态查找
- **文件**：`net/minecraft/world/level/material/FlowingFluid.java` `isSolidFace`
- **改法**：`level.getFluidState(neighborPos)` → `blockState.getFluidState()`（行 149 已查 blockState，免第二次世界查找）。
- **等价性**：同文件 `spread()` 行 159 及 `BlockGetter.java:96`（Paper 注释 "don't need to go to world state again"）已是同一模式。
- **风险**：零。

### 0047 — CompoundTag.merge 免每键双查找
- **文件**：`net/minecraft/nbt/CompoundTag.java` `merge`
- **改法**：迭代 `other.tags` 的 entrySet（fastutil fastIterator）而非 `keySet()+get()`，与 write()/copy() 同模式。
- **等价性**：merge 期间只改 `this.tags`（不改 other），fastIterator 安全；输出一致。
- **风险**：低。

### 0048 — LookControl.tick 免 Optional<Float> 分配（协议级重构）
- **文件**：`net/minecraft/world/entity/ai/control/LookControl.java` + `net/minecraft/world/entity/monster/Shulker.java`
- **热点**：每 mob 每 tick（lookAtCooldown>0 时）调 `getYRotD()`/`getXRotD()` 各返回 `Optional<Float>`（2 Optional + 2 Float 装箱）。
- **改法**：引入布尔+字段协议 `hasXRotD()`/`hasYRotD()`（写入复用字段 `xRotD`/`yRotD`），tick() 用布尔判断；`getXRotD()`/`getYRotD()` 保留并委托布尔法（API 兼容）；Shulker 的 getter 重写转为重写 `hasXRotD()`/`hasYRotD()`。
- **关键约束**：基类 tick() 改用布尔法后，重写过 getter 的子类（仅 Shulker）必须同步改重写布尔法，故 LookControl 与 Shulker **必须在同一补丁**。SmoothSwimmingLookControl 重写的是 tick()（用基类 getter，委托后仍工作）。已确认全树仅 Shulker 重写这两个 getter。
- **等价性**：布尔法写入的值与原 Optional 持有的值相同；零长度/空判定阈值（1e-5）逐一对齐。全量 test 通过。
- **风险**：中（核心 AI 控制类协议变更，但可证等价；test 验证无回归）。

---

## 批次 4（2026-07-30）：碰撞路径分配消除

### 0049 — Entity.collide 复用碰撞 list（每 move 每 tick 4 个 ArrayList）
- **文件**：`net/minecraft/world/entity/Entity.java` `collide(Vec3)`（Paper 已优化数学的版本）
- **热点**：每个移动实体每 tick 的 `move()` → `collide()`。原实现每调用新建 4 个 `ArrayList`（potentialCollisionsVoxel/BB、entityAABBs、stepVoxels）。
- **改法**：提为 per-entity 实例字段，每次调用 `clear()` 复用（idle 实体在零位移早返回，不触及）。
- **等价性**：已核实 `CollisionUtil.getEntityHardCollisions`（写入 `into`）/`getCollisionsForBlockOrWorldBorder`（写入）/`performCollisions`（读取）均为静态工具、参数局部、**不持有**这些 list；`collide()` 非重入；Moonrise 区域化 tick 保证单实体单线程。stepAABBs 别名（=entityAABBs）行为保持。全量 test 通过。
- **权衡**：每个 Entity 实例常驻 4 个空 ArrayList（~192B/实体）；移动实体省去每 tick 4 次分配，净收益显著。
- **风险**：中（碰撞关键路径，测试覆盖有限，依赖严格代码审查 + 既有 fork 模式）。

---

## 暂缓项（评估后未做，附原因）

- **#2 Direction.Plane 迭代器分配**：~40 处调用点，绝大多数在世界生成（非每 tick 热路径），收益是每循环一个小 ArrayItr，改动面大 → ROI/风险不佳。
- **#8 EntitySelector.pushableBy 谓词缓存**：每 LivingEntity 每 tick 仅 2 个小对象；缓存需处理 team 变更失效（无干净钩子），内联需改 getPushableEntities 调用链，且受 EAR 2.0 门控（非激活 mob 不触发），复杂度高于收益。
- **#9 LookControl**：已做（0048，协议级重构，含 Shulker）。
- **#12 distanceTo→distanceToSqr 机械批**：`distanceTo` 返回 float（`(float)sqrt`），与 double 的 `distanceToSqr<=C²` 在阈值附近有 ~1 ULP 理论精度差异，严格不满足"可证等价"红线 → 跳过。

---

## 批次 5（2026-07-30）：插件 API 枚举缓存（CraftBukkit 直提交）

### CraftEntity.getPose / setPose 枚举数组缓存
- **文件**：`paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java`（src/main/java，**直接提交**，不走补丁）
- **改法**：`Pose.values()[ordinal]` 每次克隆数组 → 缓存为 `private static final POSE_VALUES` / `NMS_POSE_VALUES`。
- **等价性**：枚举数组内容运行期不变，缓存与每次 values() 等价；保持原有 ordinal 索引（不引入按名映射的行为变更）。
- **风险**：零。

---

## 批次 6（2026-07-30）：高价值事件/容器分配消除（第二轮扫描）

### 0050 — NeighborUpdater.executeUpdate 无监听器时跳过 BlockPhysicsEvent
- **文件**：`net/minecraft/world/level/redstone/NeighborUpdater.java` `executeUpdate`
- **热点**：每次邻通知（红石/活塞/方块破坏连锁/流体）的中心入口，负载下每秒百万级。无条件分配 `BlockPhysicsEvent` + 2 `CraftBlock` + `CraftBlockData` 克隆。
- **改法**：用 `BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0` 守卫整个分配+触发（Paper 在 PlayerChunkSender 等处用的同一模式）。
- **等价性**：事件唯一消费者是 `isCancelled()` 检查；零监听器则永不被取消，直落 `handleNeighborChanged` 与原行为一致。
- **风险**：低（纯监听器计数守卫）。**价值：高**。

### 0051 — AbstractContainerMenu.broadcastChanges 前置门控 copy-supplier
- **文件**：`net/minecraft/world/inventory/AbstractContainerMenu.java` `broadcastChanges`
- **热点**：每个开容器玩家每 containerUpdate tick，每槽位建 `Suppliers.memoize(item::copy)`（方法引用+Supplier 各一份），即使槽位无变化（常态）。
- **改法**：复用 `triggerSlotListeners`/`synchronizeSlotToRemote` 内部的廉价 match 判定，仅当 `listenerNeeds || remoteNeeds` 才建 supplier 并调用。
- **等价性**：两调用在匹配时均为无副作用 no-op；前置门控与内部判定逐字一致（同类内可访问 private 字段）。InventoryMenu.broadcastSlotChange 因 `suppressRemoteUpdates` 等 private 字段子类不可访问，未改。
- **风险**：低。**价值：高**（多玩家开容器时）。

---

## 批次 7（2026-07-30）：区块发送包编码分配消除

### 0052 — ClientboundLevelChunkPacketData 高度图编码免 stream/Collector
- **文件**：`net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java`（构造器）
- **热点**：每区块发送一次（登录/跑图突发期数百个），原 `getHeightmaps().stream().filter().collect(toMap)` 对 ≤7 个高度图条目用 stream+Collector+2 lambda。
- **改法**：普通循环填入 `HashMap`。注意 `getHeightmaps()` 返回 `Collection<Entry<Types,Heightmap>>`（非 Map，无 entrySet），直接迭代。
- **等价性**：用 HashMap（非 EnumMap）保持与 `Collectors.toMap` 相同的 map 类型与插入顺序 → 序列化字节完全一致。
- **风险**：低。**价值：中**（突发期）。

---

## 批次 8（2026-07-30）：区块发送选块解析免流

### 0053 — PlayerChunkSender.collectChunksToSend 解析段 stream→循环
- **文件**：`net/minecraft/server/network/PlayerChunkSender.java` `collectChunksToSend`
- **热点**：每玩家每 tick（登录/跑图突发期，pending>batchQuota 时）。
- **改法**：**保守重写**——`Comparators.least(floor, distanceSquared)` 选块逻辑原样保留（改它有选错块=客户端先收远区块的 UX 风险），仅把其后 `.stream().mapToLong().mapToObj().filter().toList()` 解析段换成普通循环。
- **等价性**：选块完全不变；循环按 leastKeys 同序解析+nonNull 过滤，结果列表一致。
- **风险**：低（选块未动）。**价值：中**（突发期）。

---

## 批次 9-12（2026-07-30，深挖阶段：第三轮 survey 子 agent 提供）

第三轮 survey 派出的子 agent 返回了大量干净等价候选，逐个代码复核后实现。补丁 0055-0063。

### 0054 — LivingEntity 缓存 pushableBy 谓词（架构级重写，原 #8 解禁）
- `pushEntities()` 每 tick 重建 `NO_SPECTATORS.and(lambda)`。pushableBy 仅捕获 source 的 team/collisionRule（canCollideWithBukkit 与候选 team 实时求值），故 per-entity 缓存、仅 team/rule 变化时重建。

### 0055 — Brain 原位 tick 运行行为
- `tickEachRunningBehavior` 内联三层循环，免每 tick ObjectArrayList（每个有 brain 的生物每 tick）。`getRunningBehaviors()` 调试 API 保留。

### 0056 — Raid.getTotalRaidersAlive 流改计数循环
- raid tick 及刷怪组循环内；`stream().mapToInt(Set::size).sum()` → 计数循环。

### 0057 — NbtAccounter 无限制账户快速路径
- 解析时每 tag 调用。`quota==MAX_VALUE`（区块/玩家加载主路径）短路，跳过永不触发的溢出检查与从不被读的 usage 累加。

### 0058 — ServerLevel 粒子/全局事件广播免每接收者 Vec3
- `sendParticles` 用 `distToCenterSqr(x,y,z)<range*range`（与 closerToCenterThan(Vec3) 等价）；`globalLevelEvent` 把常量 `Vec3.atCenterOf(pos)` 提到每玩家循环外。

### 0059 — PalettedContainer.getAll 单值调色板快速路径
- 区块特征生成期每 section 调用。单值调色板（常见）直接 `accept(valueFor(0))`，免 IntArraySet 分配（同 recalcBlockCounts 的 paletteSize==1 特例）。

### 0060 — WalkNodeEvaluator 寻路热点优化
- `canReachWithoutCollision` 免 new Vec3，标量逐步长（inv 用原 1.0F/ceil 的 float 倒数转 double，数学一致）；`getPathTypeWithinMobBB` 把 blockPosition/canPassDoors/canOpenDoors 循环不变量外提。

### 0061 — StructureStart.placeInChunk stream+toList 改回命令式循环
- CraftBukkit 把原循环改成了 stream；每个结构放置分配中间 list。改为命令式循环，首个相交 piece 时才惰性初始化 TransformerGeneratorAccess。

### 0062 — RecipeManager.getRecipeFor 直接迭代取最后匹配
- 原 `stream().toList().getLast()`，每次合成/熔炼缓存未命中分配 stream+list。改为迭代 byType 取最后匹配（SPIGOT-4638 last-wins 不变）。

### 0063 — BrewingStand 复用药水位 scratch 数组
- 每 tick 分配 boolean[3]；改为复用 scratch 原位填充，lastPotionCount 预分配独立数组、System.arraycopy 拷贝。

**暂缓（跨层较繁/低价值/中风险，已记录）**：awardStat lambda 守卫（需跨 CraftScoreboardManager 层）、Commands.performCommand Supplier（低价值）、Raid.moveRaidCenter / Beacon / StructureManager（低价值稀有路径）、LevelChunkTicks probe（中风险红石）、Scoreboard MutableBoolean（中风险）、ChunkGenerator 缓存（需线程安全）、PlayerList.broadcast 空间索引（高风险，需配置门控）、RegionFileVersion 压缩级别 / StringTag 快速 UTF-8 / Furnace 配方缓存（配置门控，收益高但需谨慎）。

---

## 批次 13-14（2026-07-30）：世界生成缓存 + 信标扫描

### 0064 — ChunkGenerator 缓存结构→步骤分组
- `addVanillaDecorations` 每个区块生成都对结构注册表做 `stream().collect(groupingBy(step().ordinal()))`，结果只依赖不可变注册表。改为 per-ChunkGenerator 缓存（按注册表身份键，volatile 安全发布，良性竞态无害）。

### 0065 — BeaconBlockEntity 光柱扫描复用 MutableBlockPos
- 光柱扫描每迭代 `blockPos.above()` 分配新 BlockPos（每 tick 最多 10 次）。改为复用 MutableBlockPos 原位移动；`pos` 参数不被修改，行为等价。

---

## 批次 15（2026-07-30）：region 文件 deflate 压缩级别可配置

### 0066 — RegionFileVersion 压缩级别配置（config-gated）
- 新增全局配置 `unsupported-settings.compression-level`（默认 6，与现状逐字节一致；超出 [1,9] 回退 6）。
- `PapoDeflaterOutputStream` 包装：自有 Deflater 在 close 时 `end()`，避免外部提供 Deflater 时原生内存泄漏（JDK 默认不 end 非自有 Deflater）。
- 可选 `BEST_SPEED`(1)：保存 CPU 大幅下降、文件略大；默认不变保证兼容。

## 批次 16（2026-07-30）：NBT 字符串读取 ASCII 快速路径

### 0067 — CompoundTag.papoReadUtf + StringTag.readAccounted
- `readUnsignedShort` + `readFully` + 字节扫描：全 ASCII 时 `new String(bytes, ISO_8859_1)` 一次解码，省 JDK `readUTF` 的 per-call byte[]/char[] scratch 与 modified-UTF-8 状态机。
- 任一非 ASCII 字节即回退 JDK `readUTF`（长度前缀回填构造输入流），语义与 modified-UTF-8 完全一致（ASCII 子集两解码器结果逐字符相同）。
- NBT 键名与绝大多数字符串值为 ASCII，命中快速路径为常态。

## 批次 17（2026-07-30）：NBT int/long 数组批量读取

### 0068 — IntArrayTag/LongArrayTag.readAccounted 批量读 + 大端解码
- 逐元素 `readInt()/readLong()`（每次 4/8 次单字节拼接）→ `readFully(buf)` 一次系统调用 + `ByteBuffer.wrap(buf).order(BIG_ENDIAN).asIntBuffer()/asLongBuffer().get(...)` 批量解码。
- IntArrayTag 有 Spigot 上限 `1<<24`，`_int<<2` 不溢出；LongArrayTag 无上限，`_int > Integer.MAX_VALUE/8` 时回退逐元素读取。
- 区块高度图、生物群系、结构数据等大数组是区块加载期热点。

## 批次 18（2026-07-30）：Raid 中心迁移 + 命令签名早退

### 0069 — Raid.moveRaidCenterToNearbyVillageSection 手动 argmin
- `SectionPos.cube(...).min(comparing(...))` 流 → 手动循环 argmin。`d < bestDist` 严格小于，与 `min()` 的首并列胜出语义一致；免流对象、Comparator、Optional 分配。

### 0070 — SignableCommand.hasSignableArguments 早退
- 原 `!of(parseResults).arguments().isEmpty()` 构建完整列表只为判空。新增 `hasSignableArgumentIn` 辅助：沿 context 链命中首个签名参数即返回 true，零列表分配。

---

## 批次 19（2026-07-30）：热路径 Direction.values() 克隆消除

枚举 `values()` 每次调用克隆整个数组（6 元素）。vanilla `Direction` 内部虽有 `VALUES` 缓存，但外部直接调 `Direction.values()` 的站点仍然每次克隆。对 tick 热路径站点逐一改为**每类私有静态缓存数组**（枚举顺序与值集不变，迭代行为逐元素等价；数组私有，无外部篡改面）。

### 0071 — 红石元件（4 文件 5 处）
- `DefaultRedstoneWireEvaluator.updatePowerStrength`（每次红石粉功率更新）、`RedStoneWireBlock.checkCornerChangeAt`/`affectNeighborsAfterRemoval`、`DiodeBlock.neighborChanged`（中继器/比较器破坏时邻更新）、`RedstoneTorchBlock.notifyNeighbors`。

### 0072 — 寻路节点评估器（2 文件 2 处）
- `SwimNodeEvaluator.getNeighbors`（每次邻居展开都遍历 6 向，寻路最热循环之一）、`AmphibiousNodeEvaluator.getPathTypeFromState` 的水边界检查。

### 0073 — 火蔓延与活塞（3 文件 6 处）
- `FireBlock` 放置状态计算/`isValidFireLocation`/`getIgniteOdds` 扫描；`PistonStructureResolver.addBranchingBlocks`（黏液块递归分支）、`PistonBaseBlock.getNeighborSignal`（每次活塞信号查询 2 个循环）。

- 基准：1024 次 6 向循环 4465.8ns → 1718.0ns（**2.6×**）。
- 未覆盖：`LightEngine`（Moonrise Starlight 已替代主光照路径）、worldgen/数据生成等冷路径 40+ 处（收益可忽略，不动以减少 diff）。

---

## 批次 20（2026-07-30）：Identifier.toString() 惰性缓存

### 0074 — Identifier.toString() 缓存
- `Identifier`（原 ResourceLocation）是不可变 final 类，但 `toString()` 每次 `namespace + ":" + path` 拼接。
- 热点：`STREAM_CODEC`/`CODEC`（每个含 Identifier 的包与 NBT 写出：注册表同步、配方、声音、方块实体保存）、`FriendlyByteBuf.writeIdentifier`、`toShortString`/`toDebugFileName` 等。注册表中的 Identifier 是单例（如 `Blocks.STONE` 的 id 全局同一实例），缓存命中率接近 100%。
- 实现：`private volatile String papoCachedToString` 惰性初始化。volatile 读 + 良性竞态（两线程同算等值串，String 不可变 + final 字段语义保证安全）；等价性：返回值逐字符相同，仅返回实例身份变化（无合法代码依赖 toString 身份）。
- 基准：1024 次 stringify 13030ns → 792ns（**16.5×**）。

---

## 批次 21（2026-07-30）：NBT int/long 数组批量写出（0068 对称面）

### 0075 — IntArrayTag/LongArrayTag.write 批量编码
- 读侧已在 0068 优化；写侧仍是逐元素 `writeInt/writeLong`（DataOutputStream 每元素 4/8 字节小缓冲拷贝 + 调用开销），区块保存（高度图、生物群系、`Position`/`UpgradeData` 等）热路径。
- 改为 `ByteBuffer.wrap(buf).order(BIG_ENDIAN).asIntBuffer()/asLongBuffer().put(data)` 一次编码 + `output.write(buf)` 单次写出；`length > MAX_VALUE/4(/8)` 溢出保护回退逐元素（与读侧对称）。线上字节完全一致。
- 基准：int 9.3-9.6×，long 5.4-6.3×。

---

## 批次 22（2026-07-30）：寻路/流体/红石的 Plane.HORIZONTAL 迭代与 EnumMap 消除

`Direction.Plane.HORIZONTAL` 的 for-each 每次分配 `Iterators.forArray` 迭代器；寻路邻居展开还每次调用 `Maps.newEnumMap(Direction.class)`。统一改为每类私有静态缓存数组 `{NORTH, EAST, SOUTH, WEST}`（与 Plane.HORIZONTAL faces 顺序逐元素一致，迭代行为等价）。

### 0076 — 寻路评估器（2 文件）
- `WalkNodeEvaluator.getNeighbors`：2 个 Plane 循环改缓存数组；`getFloorLevel(new BlockPos)` 改复用 `floorLevelPos`（MutableBlockPos，getFloorLevel 只读）。
- `SwimNodeEvaluator.getNeighbors`：每调用 EnumMap 改 `Node[6]` ordinal 索引数组（get/put/覆盖/null 语义一致；Direction 固定 6 个 ordinal）；`findAcceptedNode` 内 `new BlockPos` 改复用 `fluidPos`。

### 0077 — 流体与红石粉（3 文件 13 处循环）
- `FlowingFluid`（6 处：扩散/坡度/源统计/更新形状——流体 tick 热路径）、`RedstoneWireEvaluator.getIncomingWireSignal`、`RedStoneWireBlock`（6 处：连接状态计算/更新）。
- `FlowingFluid.getSpread` 的 EnumMap 保留（作为 protected 返回值逃逸，改结构会变签名，兼容风险）。
- 基准：模拟邻居展开 512 次 11842ns → 7897ns（1.5×，另含每次 2 次分配消除）。

---

## 基准测试（2026-07-30）

新增 `benchmark/`：JMH 1.37 微基准，忠实复刻 0067/0068/0040/0047/0048/0069/0045/0058/0070 的前后实现对比。
运行：`cd benchmark && ./run.sh`。对比报告见 `note/report/perf/`。

---

## 批次 23（2026-07-30）：Bukkit 事件无监听器门控（0078-0079）

与 0050（BlockPhysicsEvent）同一模式：`Event.getHandlerList().getRegisteredListeners().length > 0` 时才构建+派发事件。

### 0078 — GameEventDispatcher.post 的 GenericGameEvent 门控
- **热点**：每次游戏事件派发（移动实体落脚 STEP、SWIM/SPLASH、方块放置/破坏、容器开关、活塞等），繁忙服每 tick 数百至数千次。原实现每次派发分配 `GenericGameEvent` + `CraftLocation.toBukkit` Location + registry 查询。
- **等价性**：`GenericGameEvent` 有独立 `HandlerList` 且无任何子类（已全库核实）；零监听器时 `callEvent()` 恒返回 true 且 radius 不被修改，跳过与执行完全一致。
- **风险**：低。

### 0079 — FlowingFluid.tick 的 FluidLevelChangeEvent 门控（2 处分支）
- **热点**：每次流体液位变化（枯竭/流平/下落），刷石机/海带机/排水工程中极频繁。原实现每次分配事件 + `CraftBlock.at` + `asBlockData()`。
- **等价性**：`CraftEventFactory.callFluidLevelChangeEvent` 除构造+派发外无副作用（已核实）；零监听器时不可被取消，`getNewData()` 即传入 state（`asBlockData` 包装同一 BlockState，`getState()` 原样返回）。
- **风险**：低。

## 批次 24（2026-07-30）：游戏事件/红石/邻居更新分配消除（0080-0083）

### 0080 — RedstoneWireEvaluator.getIncomingWireSignal
- **热点**：每次红石线功率重算（高频红石下每 tick 数百至数万次），原实现每次调用固定分配 12 个 BlockPos（4×relative + 4×above 循环不变量 + 4×above/below）。
- **改法**：`pos.above()` 及其 state/conductor 判定外提（纯读，循环不变量）；单个 MutableBlockPos 驱动 relative/above/below 三段查找。
- **等价性**：`getBlockState`/`isRedstoneConductor`/`getWireSignal` 均只读位置不保留引用；坐标访问序列与求值顺序逐点不变。
- **风险**：低。

### 0081 — EuclideanGameEventListenerRegistry.getPostableListenerPosition
- **热点**：每次游戏事件派发 × 每个 section 每个监听器（sculk 区域每 STEP 都触发）。原实现每次分配 Optional + 2 个 `BlockPos.containing`。
- **改法**：返回 `@Nullable Vec3`；`BlockPos.containing(a).distSqr(BlockPos.containing(b))` 内联为 floor 后整数差平方和。
- **等价性**：`BlockPos.containing` 即逐坐标 `Mth.floor`；两个 int 位置的 distSqr 定义为差平方和，long 域计算数值完全一致（该量级无精度差）。
- **风险**：低。

### 0082 — Level.updateNeighbourForOutputSignal
- **热点**：带模拟量输出方块（容器/堆肥桶/命令方块等）setBlock UPDATE_NEIGHBORS 时调用。原实现 Plane.HORIZONTAL for-each 迭代器 + 4~8 个 relative BlockPos。
- **改法**：类级缓存 `Direction[]`（NORTH→EAST→SOUTH→WEST 顺序一致）+ 复用 MutableBlockPos。
- **等价性**：`ServerLevel.neighborChanged(state,...)` 路由到 `CollectingNeighborUpdater`，其明确 `pos.immutable()` 拷贝（已核实行号）；`hasChunkAt`/`getBlockState`/`isRedstoneConductor` 纯读。
- **风险**：低。

### 0083 — ContainerOpenersCounter.getEntitiesWithContainerOpen
- **热点**：打开中的容器每 5 tick recheckOpeners。原实现在 `getEntities` 已返回新建列表后再 stream().map(强转).collect() 二次装箱。
- **改法**：谓词 `hasContainerOpen` 仅在 `instanceof ContainerUser` 时返回 true，直接 `(List<ContainerUser>)(List<?>)` 强转返回。
- **等价性**：谓词保证元素类型；`getEntities` 返回的是新建私有列表（0042 已核实），无别名风险；元素顺序内容不变。
- **风险**：低。

## 批次 25（2026-07-30）：网络编码热路径（0084-0085）

### 0084 — Utf8String.write 免临时 ByteBuf
- **热点**：每包每个字符串字段（聊天、Identifier、队伍名、BossBar 等），网络编码最高频辅助函数之一。原实现每次 `alloc().buffer(utf8MaxBytes)` 临时 buf + 编码 + 拷贝 + release。
- **改法**：`ByteBufUtil.utf8Bytes(string)`（纯计算零分配，与 `writeUtf8` 同孤代理项 '?' 替换语义，字节数保证一致）先算精确长度，两个长度检查保持原顺序原异常消息，然后 `VarInt.write` + `ByteBufUtil.writeUtf8(buffer, string)` 直写目标。
- **等价性**：线上字节 = varint(实际编码长度) + writeUtf8 内容，两种写法逐字节一致；基准类 main 方法字节级自检通过（ascii/utf8 两组）。
- **风险**：低。

### 0085 — FriendlyByteBuf.writeFixedSizeLongArray 批量写出
- **热点**：每区块包每 section 的 states 位存储写出（256–512 个 long × 24 section ≈ 6-12k 次 `writeLong`，每次带 ensureWritable 检查）。目标 buffer 为 `Unpooled.wrappedBuffer(byte[])`（单组件大端）。
- **改法**：`nioBufferCount()==1 && order()==BIG_ENDIAN` 时 `ensureWritable` 一次 + `internalNioBuffer(w, n).asLongBuffer().put(array)` + writerIndex 推进；否则回退原循环。
- **等价性**：`LongBuffer.put` 产出的 BE 字节序列与逐次 `writeLong` 完全相同；基准 main 方法字节级自检通过（含非 2 幂长度）。
- **风险**：低-中（快路径有条件门控，回退保真）。

## 批次 26（2026-07-30）：advancements/背包/杂项（0086-0089）

### 0086 — 击杀/命中/交互/拾取触发器 hasListeners 早退（5 文件）
- **热点**：`KilledTrigger`（每次击杀，刷怪塔极热）、`PlayerHurtEntityTrigger`（每次近战命中）、`PlayerInteractTrigger`（每次右键实体）、`PickedUpItemTrigger`（每次拾取）在调用基类前急切执行 `EntityPredicate.createContext`（建 LootParams/LootContext Builder + 参数校验）。老玩家完成相关进度后监听器集合为空，基类本就无操作，上下文白建。
- **改法**：`SimpleCriterionTrigger` 加 `protected final boolean hasListeners(ServerPlayer)`（读 criterionData 判空），4 个子类 trigger 首行早退。
- **等价性**：无监听器时基类 trigger 对空集直接返回（已核实），提前返回不改变任何可见行为；上下文内容与创建时机无关（同 tick 同线程）。
- **风险**：低。

### 0087 — Slot.tryRemove @Nullable 内部路径 + carried SlotAccess 缓存
- **热点**：`safeTake`/`doClick`（每次背包拾取点击）经 `Optional.ofNullable` + `ifPresent` lambda；`tryItemClickBehaviourOverride` 每次点击分配匿名 SlotAccess（默认实现对 Bundle 以外物品根本不用它）。
- **改法**：`Slot` 新增包内 `@Nullable ItemStack tryRemoveInternal`（分支结构逐行同构，null==empty），public `tryRemove` 委托包装保持兼容；`safeTake` 与 `doClick` 2 处改走内部路径。SlotAccess 是无状态委托（仅捕获 `this`），缓存为每 menu 单例（惰性）。
- **等价性**：纯包装层消除；单例化后所有调用点行为逐字节等价（menu 操作均在主线程）。
- **风险**：低。

### 0088 — Ingredient.testOptionalIngredient 三目化
- **热点**：`ShapedRecipePattern.matches` 对每个候选有序配方的每个格子调用（3×3 × N），锻造台匹配同。原实现每次分配 Optional<Boolean> 装箱 + 捕获 lambda + 方法引用。
- **改法**：`ingredient.isPresent() ? ingredient.get().test(stack) : stack.isEmpty()`。
- **等价性**：`test` 返回原生 boolean，map 只是装箱再拆箱；orElseGet 仅 empty 时求值，与三目完全同构。
- **风险**：低。

### 0089 — SleepStatus 单遍 + ServerFunctionManager 空表早退
- `areEnoughDeepSleeping`：夜晚有人睡觉期间每 tick 每世界两次 stream 遍历（filter().count() + anyMatch）改单遍循环（两谓词无副作用、读同一时刻状态，融合等价）。
- `ServerFunctionManager.tick`：`#minecraft:tick` 标签为空（绝大多数服务器）时跳过 `executeTagFunctions`（其效果仅 profiler push/pop + 空遍历）。
- **风险**：低。

## 批次 27（2026-07-30）：区块加载/NBT 收尾（0090-0091）

### 0090 — 区块加载路径 stream 消除与预尺寸（3 文件）
- `SerializableChunkData.parse`：entities/block_entities 的 `Optional.stream().flatMap(compoundStream).toList()` 改预尺寸循环（getList 空 ⟺ getListOrEmpty 空表；compoundStream 只留 CompoundTag；消费方仅迭代）。
- `SerializableChunkData.unpackStructureStart`：同一 key 的 `getCompoundOrEmpty` 双重哈希查找提升为局部变量（两次调用间无写入）。
- `SavedTick.filterTickListForChunk`：stream filter/toList 改 isEmpty 早退 + 预尺寸循环（每区块加载 ×2）。
- `ClientboundLevelChunkPacketData`：`blockEntitiesData` 按 `getBlockEntities().size()` 预尺寸（仅追加+迭代）。
- **风险**：低。

### 0091 — CompoundTag.accept(StreamTagVisitor) fastIterator
- **热点**：`IOWorker.scanChunk` 命中 pendingWrites 时及 NBT visitor API。0040/0047 同款模式的遗漏点：fastutil map 上 entrySet 迭代每 entry 分配。
- **改法**：`object2ObjectEntrySet().fastIterator()` 三元式；循环体提取为 `acceptEntry`（返回 null=继续下一 entry）共享 fastIterator 与 entrySet 两路径。
- **等价性**：fastIterator 复用 entry 但不逃逸出迭代体；同一底层数组遍历顺序一致。
- **备注**：`@Nullable` 须写为 `StreamTagVisitor.@Nullable ValueResult`（jspecify TYPE_USE 不能标注嵌套类型的作用域结构，Java 21 编译错误，已修）。
- **风险**：低。

---

## 候选后续批次（来自 survey，按 价值×置信/风险 排序）

（旧清单中 Direction.Plane 迭代器、tickBlockEntities 移除集、NaturalSpawner 距离、pushableBy、LookControl Optional、distanceToSqr 批、CraftBukkit 枚举缓存均已完成，见对应批次。）

### 批次 23-27 survey 新增候选（2026-07-30，尚未做）

- **InventoryChangeTrigger 惰性计数**（低-中）：`slots == Slots.ANY` 时三个槽位计数不被使用，可惰性化。
- **EntitySelectorOptions "scores" Objective 解析 memoize**（低-中）：循环命令方块每 tick 每实体 E×K 次 getObjective 哈希查找。
- **AbstractFurnaceBlockEntity canBurn 产物缓存**（中）：每燃烧熔炉每 tick `assemble()`=result.copy()。
- **Entity.checkSupportingBlock Optional 复用**（中，涉 Paper 碰撞补丁区）。
- **Entity.checkInsideBlocks AtomicInteger 消除**（中，需 BlockGetter 加返回 index 的重载）。
- **DefaultRedstoneWireEvaluator.updatePowerStrength 去 HashSet**（中：更新顺序变化，不满足严格序等价，暂缓）。
- **Utf8String 写侧 NBT ASCII 快速路径（papoWriteUtf）**（低-中，0067 的写侧镜像）。
- **FriendlyByteBuf.writeNbt/readNbt 轻量 DataInput/DataOutput 适配器**（中，每次 3 个 stream 包装器分配）。
- **MinecraftServer.tickChildren 无玩家维度免每 tick 建 SetTimePacket**（低）。
- **EntitySelector.getPredicate 特性谓词缓存**（中，收益小）。

已确认**已优化、勿重复**：EntityTickList.forEach、LevelTicks、LevelChunk.getBlockState、getEntitiesOfClass、Entity.collide 数学、CompoundTag.copy、PatchedDataComponentMap、getNearestPlayer、PoiManager、Brigadier 子节点查找等。
