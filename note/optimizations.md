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
- IntArrayTag 有 Spigot 上限 `1<<24`，`_int<<2` 不溢出；LongArrayTag 无上限，`_int > 1<<20` 时回退逐元素读取。
- 区块高度图、生物群系、结构数据等大数组是区块加载期热点。
- **2026-08-01 稳定性审计修复**：阈值由"仅溢出守卫 `Integer.MAX_VALUE/8`"下调为"内存安全阈值 `1<<20`"。原因：LongArrayTag 批量读会同时持有 `long[_int]`(8n)与临时 `byte[_int<<3]`(8n)，峰值 16n（vanilla 流式仅 8n）；LongArrayTag 无 Spigot 上限且区块加载走 `NbtAccounter.unlimitedHeap()`，恶意/损坏的 region 文件（世界导入等半可信磁盘输入）可在 8n<剩余堆<16n 时触发 OOM。下调后：`_int<=1<<20`（临时缓冲≤8MB，覆盖所有合法高度图/结构数组）走批量；更大/对抗性数组回退流式（vanilla 8n 峰值，消除 2x 放大，且 `_int<<3` 仍在 int 范围故覆盖原溢出守卫）。0075 写侧同步对称。客户端 NBT 不受影响（走 `readNbt`→`defaultQuota` 2MB 上限，放大本就受限于 4MB 峰值）。

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
- 改为 `ByteBuffer.wrap(buf).order(BIG_ENDIAN).asIntBuffer()/asLongBuffer().put(data)` 一次编码 + `output.write(buf)` 单次写出；`length > MAX_VALUE/4`（int）/ `> 1<<20`（long）回退逐元素（与读侧对称）。线上字节完全一致。
- **2026-08-01 稳定性审计修复**：LongArrayTag 写侧阈值由 `MAX_VALUE/8`（仅溢出守卫）下调为 `1<<20`（内存安全，与读侧 0068 对称），消除对超大 long[] 写出时临时缓冲的 2x 峰值放大。int 侧有 Spigot 上限不动。
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

## 批次 28（2026-07-30）：NBT 写侧与网络 NBT 零分配化（0092-0095）

### 0092 — NBT 字符串写出 ASCII 快速路径 papoWriteUtf（0067 写侧镜像）
- **文件**：`net/minecraft/nbt/CompoundTag.java`（writeNamedTag 键名）+ `net/minecraft/nbt/StringTag.java`（write 值）
- **热点**：NBT 序列化每个键名与每个字符串值——区块/实体/玩家存档（IOWorker）与网络 NBT（0095 入口）共用 `Tag.write(DataOutput)`。JDK `DataOutput.writeUTF` 每次调用分配 `byte[utflen+2]` scratch 并两遍遍历（算长+编码）。
- **改法**：`CompoundTag.papoWriteUtf(DataOutput, String)`：单遍 ASCII 扫描（0x01-0x7f），命中时长度前缀与内容写入同一 ThreadLocal 8KB scratch、**单次 `output.write(scratch, 0, length+2)` 批量写出**（>8190 字符走 writeShort+分块）；含 NUL 或 ≥0x80 字符回退 `writeUTF`；长度 >65535 委托 `writeUTF` 抛出同一 `UTFDataFormatException`。
- **迭代教训**：初版用 `writeShort`（两次单字节 write）+ 分块，短键名实测**回退 0.83×**（单字节调用 + ThreadLocal.get 盖过省下的分配）；改单次批量写后全场景回正（短键 1.10×、62 字符 1.30×、10KB 1.89×）。
- **等价性**：modified-UTF-8 中 0x01-0x7f 逐字符编码为单字节等值字节；NUL 为 0xC0 0x80 双字节故必须回退。基准 main 字节级自检：空/短/10KB 分块/utf8 回退/NUL 回退/65535 边界全等，超长异常类型一致。
- **风险**：低。

### 0093 — InventoryChangeTrigger hasListeners 早退（0086 同款模式）
- **文件**：`net/minecraft/advancements/criterion/InventoryChangeTrigger.java` `trigger(ServerPlayer,Inventory,ItemStack)`
- **热点**：每次背包槽位同步（`ServerPlayer.containerListener.slotChanged`）调用，原实现无条件全槽位扫描统计 full/empty/occupied（每非空槽位含 `getMaxStackSize` 组件查找）。老玩家完成相关进度后监听器集合为空，基类 `SimpleCriterionTrigger.trigger` 对空集本就无操作，扫描纯白做。
- **改法**：方法首行 `if (!this.hasListeners(player)) return;`（0086 引入的 protected final 助手，读 criterionData 判空）。
- **等价性**：无监听器时基类 trigger 直接返回（已核实 `set != null && !set.isEmpty()` 守卫）；扫描为纯读（getItem/isEmpty/getCount/getMaxStackSize）无副作用，计数唯一消费者是 matches。
- **风险**：低。

### 0094 — tickChildren 时间同步 SetTimePacket 惰性分配
- **文件**：`net/minecraft/server/MinecraftServer.java` `tickChildren`
- **热点**：每世界每 tick 无条件构造共享 `worldPacket`，但仅当存在通过 `(tickCount + id) % 20` 守卫的玩家才使用：无玩家世界及 19/20 的 tick 白建（默认 3 世界 ≈ 60 次/秒）。
- **改法**：`worldPacket` 改 null 初始化 + 循环内首次使用时构造。
- **等价性**：构造参数（worldTime/dayTime/doDaylight）循环不变；`ClientboundSetTimePacket` 为 record，构造无副作用，首用构造产出完全相同。
- **风险**：零。

### 0095 — FriendlyByteBuf.writeNbt ThreadLocal 轻量 DataOutput 适配器
- **文件**：`net/minecraft/network/FriendlyByteBuf.java` `writeNbt(ByteBuf,Tag)`
- **热点**：区块包方块实体（`ClientboundLevelChunkPacketData.BlockEntityInfo.write` 每方块实体每区块发送）与带 NBT 组件物品（`ByteBufCodecs.COMPOUND_TAG` 等，物品栏同步）的编码入口。原实现每次 `new ByteBufOutputStream(buffer)`。0092 落地后这是 writeNbt 路径最后的 per-call 分配。
- **改法**：`PapoByteBufDataOutput extends OutputStream implements DataOutput`（嵌套静态类），ThreadLocal 复用；buffer 字段 save/restore（finally）保证 `Tag.write` 内重入 writeNbt 的正确性。`writeUTF` 经惰性缓存的 `DataOutputStream` 包装（与 Netty `ByteBufOutputStream.utf8out` 字段同构——javap 实证 Netty 即此实现；`DataOutputStream.writeUTF(String,DataOutput)` 包私有不可直接调）。
- **等价性**：与 Netty 4.2.7 `ByteBufOutputStream` 逐方法字节级比对（基准 main 对真实 jar 自检）：全部原语 BE 直写、writeBytes/writeChars 逐字符循环、writeUTF（ascii/utf8/NUL/空）、0/1/5/20 条目树形写出全等。读侧 ByteBufInputStream 不变。
- **风险**：低（适配器纯转发；重入经 save/restore 保证；字节等价有实证）。

### 暂缓（批次 28 评估后未做，附原因）
- **EntitySelectorOptions "scores" Objective memoize**：记分板目标可运行时增删（`/scoreboard objectives remove` 后同名重建），按名缓存 Objective 无干净失效钩子，不满足可证等价红线。
- **AbstractFurnaceBlockEntity canBurn 产物缓存**：两次 canBurn 之间隔 FurnaceBurnEvent（插件可改熔炉库存），且插件可原位改输入槽组件（对象身份不变），缓存键不可证等价。
- **Entity.checkInsideBlocks AtomicInteger 消除**：该路径每调用还分配 movements list、Vec3、aabb、LongOpenHashSet、betweenCorners 迭代器等，AtomicInteger 仅为其中之一；精确等价需 BlockGetter 加返回 index 的重载（addCollisionsAlongTravel 内部 visit 也需穿线），复杂度高于收益。
- **Entity.checkSupportingBlock Optional 复用**：涉 Paper 碰撞补丁区（findSupportingBlock），每调用仅 1 Optional + 1 AABB，收益低。
- **FriendlyByteBuf.readNbt 读侧适配器**：DataInput 的 EOF 语义（Netty checkAvailable vs EOFException）需逐方法实证，本批未做，留作后续候选。

---

## 批次 29（2026-07-31）：网络读侧与事件/追踪分配消除（0096-0103）

三个 survey 子代理（网络编解码 / 实体同步与 tick / 区块 IO 与 NBT 读侧）共产出 21 个候选，逐个代码复核（含 javap 实证 Netty 4.2.7 与 JDK 21 字节码）后实现 9 个、基准后回退 1 个，最终 8 个。

### 0096 — FriendlyByteBuf.readNbt ThreadLocal 轻量 DataInput 适配器（0095 读侧镜像）
- **文件**：`net/minecraft/network/FriendlyByteBuf.java` `readNbt(ByteBuf, NbtAccounter)`
- **热点**：入站 NBT 解码（创造模式带组件物品、自定义 payload 等）每次分配 `new ByteBufInputStream(buffer)` 且从不 close，纯垃圾。
- **改法**：`PapoByteBufDataInput extends InputStream implements DataInput`（嵌套静态类），ThreadLocal 复用；bind 时快照 `endIndex = readerIndex + readableBytes`（与 Netty 构造器一致），save/restore 两字段防重入。
- **等价性**：javap 反编译 Netty 4.2.7 `ByteBufInputStream` 逐方法实证后 1:1 复刻——`read()/read(byte[],off,len)` EOF 返回 -1 且允许部分读 vs `readXxx/readFully` 经 `checkAvailable` 抛 EOFException（**异常消息逐字符相同**）、`skipBytes` 静默截断、`available()` 快照语义、`readLine` 直读 buffer + 本地计数（**从不抛 EOF**——初版误用 `readUnsignedByte()` 会在无换行 EOF 时抛异常，javap 核对后修正）、mark/reset 直通 readerIndex 标记。基准 main 对真实 Netty jar 逐方法行为比对（含异常类型+消息、部分读、截断、readLine 四种换行形态、mark/reset、readUTF、树形读取）全等。
- **风险**：低。

### 0097 — VarInt.read 单字节快速路径
- **文件**：`net/minecraft/network/VarInt.java` `read`
- **热点**：每个入站包 id（全部 < 128）、每个集合长度前缀、枚举序数。
- **改法**：首字节剥离，`b >= 0`（无 continuation 位）直接返回；否则以 `i=b&127, i1=1` 进入原 do-while。
- **等价性**：`b >= 0 ⟺ (b&128)==0` 且此时 `b&127==b`，与循环一次迭代结果相同；第 6 字节抛 "VarInt too big" 的时机（先或入再判 `i1>5`）逐字节保持。基准 main：0..300/2^14/2^21/MAX_VALUE/负数/5 字节上限/6 字节异常全等。
- **风险**：低。

### 0098 — FriendlyByteBuf 枚举读写 ClassValue 缓存枚举常量数组
- **文件**：`net/minecraft/network/FriendlyByteBuf.java` `readEnum`/`writeEnumSet`/`readEnumSet`
- **热点**：`Class.getEnumConstants()` 每次克隆整个常量数组；入站 Swing/UseItemOn/PlayerAction/Interact 等高频包均经 readEnum。
- **改法**：`ClassValue<Object[]>` 缓存（每个枚举类只取一次），三处调用点共用。
- **等价性**：`getEnumConstants()` 返回共享数组的克隆，内容（单例、声明顺序）JVM 生命周期不变；三处只读索引/迭代，数组不逃逸。非枚举类传入时 getEnumConstants 返回 null，ClassValue 透传 null，NPE 位置与原来一致。基准 main 验证单例身份与顺序。
- **风险**：低。

### 0099 — ByteBufCodecs.registry 无状态 codec 提升为静态单例（3 个包类）
- **文件**：`ClientboundAddEntityPacket`、`ClientboundLevelChunkPacketData`（BlockEntityInfo）、`ClientboundBlockEventPacket`
- **热点**：`registry()` 每次调用 new 一个匿名 StreamCodec；AddEntity 每次实体进入追踪范围编码、BlockEntityInfo 每方块实体每区块包每接收玩家编码。
- **改法**：各包类提升为 `private static final` codec 字段。
- **等价性**：匿名 codec 仅捕获 registryKey 常量，注册表经 `buffer.registryAccess()` 每次解析（未缓存，运行时注册表变化仍生效）；单例与逐次新建实例编码逐字节相同（基准 main 字节级自检）。
- **风险**：低。

### ~~0100 — ByteBufCodecs.map / FriendlyByteBuf.writeMap 的 forEach lambda 改 entrySet 循环~~（已实现，基准回退后撤销）
- **撤销原因**：JMH 实测 7 条目（区块包高度图规模）**回退 0.77×**（54.5ns→71.2ns）——`HashMap.forEach` 直接扫内部表无迭代器分配，且捕获型 BiConsumer 在热点下被 JIT 逃逸分析消除，"每次 encode 分配 lambda"的前提在 JIT 下不成立；entrySet 循环反而引入 iterator 分配与虚调用。3 条目持平。已 rebase 摘除该内部提交，补丁序列重编号。
- **教训**：消除 lambda 分配类候选必须先过微基准——EA 对单次调用的捕获 lambda 基本都能消除，遍历方式本身的成本才是决定项。基准类 MapEncodeLoopBench 保留作为复评依据。

### 0100 — EntityJumpEvent(3 处)/PlayerVelocityEvent 无插件监听器门控
- **文件**：`LivingEntity.aiStep`、`Ravager`、`Panda`（EntityJumpEvent）+ `ServerEntity.sendChanges`（PlayerVelocityEvent）
- **热点**：地面生物寻路翻越/史莱姆持续跳跃每 ≥10 tick 构建事件；PvP 服击退每 hurtMarked tick 构建事件 + Vector.clone。
- **改法**：0050/0078/0079 同款 `getHandlerList().getRegisteredListeners().length` 门控（== 0 || 构建+派发；> 0 才进入原逻辑）。
- **等价性**：两事件均无子类（paper-api 全库 grep 核实）、构造无副作用；零监听器时 callEvent 恒返回 true 且 PlayerVelocityEvent 的 velocity 不可能被改（不进入 setVelocity 分支），与原行为逐分支一致。
- **风险**：低。

### 0101 — GameEventDispatcher.post BY_DISTANCE 队列 + Player.aiStep 经验球列表惰性分配
- **热点**：每次游戏事件 post（每 tick 数百至数千次）无条件 `new ArrayList<>()`，仅存在 BY_DISTANCE 监听器（sculk 类）时才装入；每玩家每 tick `Lists.newArrayList()` 经验球列表，绝大多数 tick 为空。
- **改法**：GameEventDispatcher 提取有状态内部类 `PapoPostVisitor implements ListenerVisitor` 携带惰性 list（替代捕获 lambda）；Player.aiStep `list = null` 首个经验球命中时创建。
- **等价性**：visit 调用序列与 ListenerInfo 内容不变；空队列原行为 = sort+遍历空表 no-op；经验球 touch 调用序列与随机选择输入完全相同。
- **风险**：低。

### 0102 — TrackedEntity 清理循环防御性 seenBy 拷贝改惰性移除列表（2 处）
- **文件**：`net/minecraft/server/level/ChunkMap.java` `moonrise$tick` purge 分支 + `moonrise$removeNonTickThreadPlayers`
- **热点**：玩家进出追踪范围/跨 chunk 移动时，每个被追踪实体每 tick `new ArrayList<>(seenBy)` 全量拷贝，即使无需移除任何玩家。
- **改法**：直接迭代 seenBy，待移除玩家收集到首个命中才创建的 scratch list，循环后统一 removePlayer。`moonrise$clearPlayers` 不动（非空时必然全量移除，拷贝不可省）。
- **等价性**：removePlayer 调用序列与逐一判定条件、顺序完全相同；迭代期间不变更 seenBy；零命中时零分配零调用。
- **风险**：低。

### 0103 — RegionFileVersion DEFLATE 读侧 Inflater ThreadLocal 池化 + fill 缓冲 512→8192
- **文件**：`net/minecraft/world/level/chunk/storage/RegionFileVersion.java`（默认压缩格式的每次区块/实体/POI 读盘）
- **热点**：`new InflaterInputStream(inputWrapper)` 每次读盘分配 native zlib 状态（inflateInit）+ Cleaner 注册，close 时 JNI `end()`；512B fill 缓冲对 30KB 压缩区块约 60 次 fill。
- **改法**：`PapoInflaterInputStream extends InflaterInputStream` 经 `(in, pooledInflater, 8192)` 构造；close 时 `inf.reset()` 归还 ThreadLocal 单槽池；借出/归还均线程内平衡，池空新建、被顶替者交由 GC/Cleaner（有界：每线程 ≤ 打开流数）。
- **等价性**：javap 实证 JDK 21 `InflaterInputStream(InputStream, Inflater, int)` 置 `usesDefaultInflater=false`，close 不 end 外部 Inflater；解压输出只取决于压缩输入与 fill 分块无关（基准 main：512/4096/32768 三尺寸 × 池化复用 3 轮 + 同线程双流并发，与全新 InflaterInputStream 逐字节全等）。与 0066 写侧 `PapoDeflaterOutputStream` 形成读写对称。
- **风险**：低-中（原生资源生命周期变更；池容量有界，未 close 的流退化为原行为）。

### 暂缓（批次 29 评估后未做，附原因）
- **ClientboundLightUpdatePacketData BitSet.toLongArray 缓存**：4 个 BitSet 的 getter 为 public 且可逃逸修改，快照缓存不满足可证等价红线。
- **PlayerNaturallySpawnCreaturesEvent 复用单实例**：事件对象被存入 `ServerPlayer.playerNaturallySpawnedEvent` 字段供两个读点消费，监听器动态注册/注销的边界状态（曾取消过的残留 cancelled）需额外管理，风险高于收益。
- **Entity.computeSpeed Vec3 拆 double**：`getKnownSpeed()` 为公共 API，消除分配会把 Vec3 重建转移到 API 调用点，需先采样调用频率。
- **Entity 流体检测路径 AABB/MutableBlockPos/section 数组**：候选成立但涉 moonrise 重写的边界算术，留待下批单独仔细核对。
- **Varint21FrameDecoder helperBuf 消除**：收益过小（每帧 ≤3 字节抄写）。
- **SectionStorage.PackedChunk.parse 的 `flag = dynamic != dynamic1` 恒 true**：疑似上游逻辑问题但"修复"会改变 versionChanged/resave 行为，仅记录上报，不动。
- **GZIP 变体读侧池化**：GZIPInputStream 无接受外部 Inflater 的构造器，需复制头部解析，超出可证等价成本。

---

## 批次 30（2026-07-31）：实体每 tick 热路径分配消除（0104-0105）

批次 29 survey 遗留的两个中风险候选，先做逃逸/语义实证（Explore 子代理全树查证 + javap 等价判据）再落地，共 2 个补丁。

### 0104 — Entity.updateFluidHeightAndDoFluidPushing 每调用 3 处分配消除
- **文件**：`net/minecraft/world/entity/Entity.java`
- **热点**：`updateInWaterStateAndDoFluidPushing`（LAVA 路径）+ `updateInWaterStateAndDoWaterCurrentPushing`（WATER 路径）每实体每 tick ×2 调用；每调用分配 `getBoundingBox().deflate(1.0E-3)` 新 AABB、`new BlockPos.MutableBlockPos()`、`LevelChunkSection[chunkLenX*lenZ][]` 包装数组（单 chunk 常态为 1 元素）。
- **改法**：
  1. deflate 边界算术内联为 6 个 double（`bb.minX - -1.0E-3` 等，与 `AABB.inflate(-v)` 的 `minX - x / maxX + x`（x=-1e-3）逐运算相同）；
  2. `papoFluidMutablePos` 逐实体惰性字段复用（逃逸实证：Paper 运行时 Fluid 实现封闭于 5 个 vanilla 实例——注册表冻结、FluidState final、无 Forge 式扩展点；FlowingFluid.getHeight/getFlow 与 EmptyFluid 只读坐标不存引用；`lastLavaContact` 存的是 `immutable()` 新拷贝）；
  3. 单 chunk 快速路径：`minChunkX==maxChunkX && minChunkZ==maxChunkZ` 时直接用该 chunk 的 sections 引用，不建包装数组（此情形下索引表达式 `(currX>>4)+chunkLenX*(currZ>>4)+chunkOffset` 恒为 0，基准 main 实证）；内层循环取数组改为循环不变三元（JIT 可提升）。
- **等价性**：边界 double 与 deflate 结果逐位一致（基准 main 10 万随机 box 逐位比对）；索引布局多 chunk 情形与原公式逐点一致、单 chunk 恒 0（main 实证）；getChunk 调用序列/参数不变（NPE 时机同原）；MutableBlockPos 复用不改变任何对外可见状态。
- **风险**：低-中（方法为 public，并发调用同一实体会共享 scratch pos——vanilla 实体 tick 单线程，插件异步调此 NMS 方法本就违规；已注释注明 tick-thread only）。

### 0105 — Entity.computeSpeed 每 tick Vec3 分配消除（分量 double 存储 + getKnownSpeed 惰性重建）
- **文件**：`net/minecraft/world/entity/Entity.java`（字段、computeSpeed、reapplyPosition、hasMovedHorizontallyRecently、getKnownSpeed）
- **热点**：`baseTick` 每实体每 tick 调用 computeSpeed；原实现 `position().subtract(lastKnownPosition)` 每次 1 个 Vec3 分配（`position()` 返回存储字段不分配）。消费方全树仅 KineticWeapon.getMotion（动能武器攻击，低频事件驱动）+ hasMovedHorizontallyRecently（只读 horizontalDistance）。
- **改法**：`lastKnownSpeed`/`lastKnownPosition` 两个 Vec3 字段 → 6 个 double + 1 个 valid 标志；getKnownSpeed 返回 `new Vec3(分量)`（分配移到低频 API 调用点）；reapplyPosition 置 valid=false；hasMovedHorizontallyRecently 内联 `Math.sqrt(x*x+z*z)`。
- **等价性**：`a - b` 与 `Vec3.subtract` 的 `a + (-b)` 位级一致（IEEE 754 取负精确）；Vec3 不可变（final 字段）且 setPos 整体替换，存坐标 ≡ 存引用；`position()` 无子类覆盖（全树 grep），单次读取 ≡ 三次读取；首 tick 零速度语义、reapplyPosition 失效语义逐分支一致；ServerPlayer.getKnownSpeed 覆盖走 lastKnownClientMovement 不受影响。基准 main：静止/移动/往返/大坐标 4 组序列逐位比对 + moved 公式全等。
- **风险**：低（字段 private 且全树引用点仅 5 处，均已覆盖；NMS 插件直接反射访问被删字段不在兼容性红线内——NMS 无稳定 API 承诺，但已在补丁注释说明）。

### 暂缓（批次 30 评估后未做，附原因）
- **sections 包装数组多 chunk 路径池化**：尺寸随 box 跨 chunk 数变化，复用收益低且引入容量管理复杂度，仅单 chunk 快速路径已覆盖常态。
- **touchingUnloadedChunk 的 inflate(1.0) AABB**：独立方法被多处调用（含本方法入口短路），改签名穿线范围大，收益每调用 1 个 AABB，暂缓。

---

## 批次 31（2026-07-31）：事件门控扩展 + 容器/寻路/行为 tick 分配消除（0106-0111）

三个 survey 子代理（生物 AI 与寻路 / 区块方块 tick / 玩家物品网络）共产出 22 个候选，逐个代码复核后实现 6 个补丁。门控类全部沿用 0050/0078/0079/0100 已验证模式（paper-api 全库 grep 确认事件无子类、构造无副作用）。

### 0106 — FlowingFluid：BlockFromToEvent 无监听器门控（2 处）+ tick 冗余 getBlockState 消除
- **文件**：`net/minecraft/world/level/material/FlowingFluid.java`
- **热点**：每次流体扩散（水/岩浆是服务器最热计划刻之一）`spread()` 向下分支与 `spreadToSides()` 每方向各构造 CraftBlock + BlockFromToEvent；`tick` 每个非源流体计划刻在已持有 `state` 的情况下重取 `level.getBlockState(pos)`（chunk holder 查找 + PalettedContainer get）。
- **改法**：两处事件块以 `BlockFromToEvent.getHandlerList().getRegisteredListeners().length > 0` 门控（与同文件既有 FluidLevelChangeEvent 门控一致）；`tick` 改用传入的 `state`。
- **等价性**：零监听器时事件不可取消、控制流落点逐分支相同（事件无子类，paper-api grep 实证）；`tick` 的两个调用点（`ServerLevel.tickFluid`、`LevelChunk.postProcessGeneration`）都在 `getBlockState` 之后无任何插入世界修改地同步调用 `FluidState.tick`，传入 state 与重取必然相同（补丁注释固定此契约）。
- **风险**：低。

### 0107 — 物品拾取/合并事件门控：PlayerAttemptPickupItemEvent + ItemMergeEvent
- **文件**：`net/minecraft/world/entity/item/ItemEntity.java`（playerTouch）+ `CraftEventFactory.callItemMergeEvent`（直接提交的源码，非补丁）
- **热点**：玩家站在掉落物上时 `playerTouch` 每 tick 每物品构造事件 + 2×getBukkitEntity；物品密集场景每 2 tick 每可合并邻居构造 ItemMergeEvent + 2×getBukkitEntity。
- **改法**：attempt 事件块加 `> 0` 门控；`callItemMergeEvent` 入口零监听器直接返回 true（同时省掉两次 getBukkitEntity）。
- **等价性**：零监听器时 attempt 事件不可取消（默认字段）；merge 事件不可取消 → 返回值恒 true。两事件均无子类。
- **2026-08-02 稳定性审计修复（行为 bug）**：原等价性论证误称"零监听器时 flyAtPlayer=false"——实际 `PlayerAttemptPickupItemEvent`/`PlayerPickupItemEvent` 的 `flyAtPlayer` 字段**默认均为 true**（PlayerAttemptPickupItemEvent.java:20、PlayerPickupItemEvent.java:24）。原版零监听器：事件块把局部 `flyAtPlayer` 设为 true → 行 491 `entity.take(this,count)` 触发拾取飞向玩家动画；Papo 零监听器快路跳过两事件块 → `flyAtPlayer` 保持初值 false → **`entity.take` 不触发，拾取动画丢失**（物品消失而非飞向玩家；物品仍正确进背包，无数据丢失，但属可观察的用户交互回归）。修复：`ItemEntity.playerTouch` 局部 `flyAtPlayer` 初值 false→true（0107 补丁），对全零监听器情形恢复默认 true；其余 listener 组合在 420/450 行被覆写前不消费该初值，故 init=true 对所有情况正确。0112 快路注释同步勘正。
- **风险**：低。

### 0108 — 经验球事件门控×4：PlayerPickupExperience / ExperienceOrbMerge / ExpCooldown / ItemMend
- **文件**：`net/minecraft/world/entity/ExperienceOrb.java`
- **热点**：XP 农场挂机场景：拾取每 2 tick 每球构造 PlayerPickupExperienceEvent + 每次拾取的 ExpCooldown 事件 + 有 mending 装备时的 ItemMend 事件；合并扫描每 20 tick 每候选球构造 ExperienceOrbMergeEvent + 2×getBukkitEntity。
- **改法**：pickup 与 merge 用 `length == 0 ||` 短路门控；cooldown 三元（零监听器直接取传入值 2）；mend 整块包进 `> 0` 门控（零监听器时 getRepairAmount() 返回传入的 min、不可取消，min 保持不变即等价）。
- **等价性**：各事件默认字段与跳过路径逐分支比对（cooldown 返回传入 newCooldown、mend 返回传入 repairAmount、pickup/merge 的 callEvent() 恒 true）；四事件均无子类、构造仅字段赋值。
- **风险**：低。

### 0109 — PlayerJumpEvent 无监听器门控 + 未加载区块检查 Vec3 差值折叠
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java`（handleMovePlayer）
- **热点**：每次起跳构造 2×Location + PlayerJumpEvent（PvP/跑酷高频）；开启 `preventMovingIntoUnloadedChunks` 时每个移动包 `new Vec3(to).subtract(position())` 2 个 Vec3。
- **改法**：跳跃事件零监听器时直接 `jumpFromGround()`（原路径 callEvent 恒 true 后唯一副作用）；Vec3 折叠为 `new Vec3(toX - getX(), toY - getY(), toZ - getZ())` 单分配。
- **等价性**：零监听器时 from/to Location 从不被读取；`position()` 返回存储字段且 getX/Y/Z 即其分量（Entity.java:5000-5038），`a - b` 与 `Vec3.subtract` 的 `a + (-b)` 位级一致。
- **风险**：低。

### 0110 — InventoryMenu.broadcastSlotChange memoize supplier 变更门控
- **文件**：`net/minecraft/world/inventory/InventoryMenu.java`（+ `AbstractContainerMenu.suppressRemoteUpdates` 放宽为 protected）
- **热点**：玩家打开任意容器时每个 containerUpdate tick ×6 槽位无条件 `Suppliers.memoize(item::copy)`（MemoizingSupplier 实例 + 捕获 lambda，逃逸进下游调用 EA 不可消除）；绝大多数 tick 槽位未变，两个消费方均 no-op。
- **改法**：逐字照抄本仓库 AbstractContainerMenu.broadcastChanges 已合入的门控（listenerNeeds/remoteNeeds 双判定）。
- **等价性**：两个下游方法在槽位未变时内部重新判定后 no-op（triggerSlotListeners 重判 `ItemStack.matches(lastSlots…)`，synchronizeSlotToRemote 重判 `remoteSlot.matches` 与 suppressRemoteUpdates），前置条件与先例逐字相同。
- **风险**：极低（仓库内已有逐字先例）。

### 0111 — 寻路缓存 computeIfAbsent 两段式（3 处）+ GateBehavior.tickOrStop 遗留 stream 消除
- **文件**：`net/minecraft/world/level/pathfinder/NodeEvaluator.java`（getNode）、`WalkNodeEvaluator.java`（hasCollisions、getCachedPathType）、`net/minecraft/world/entity/ai/behavior/GateBehavior.java`
- **热点**：寻路内层循环每节点多次调用三处缓存（一次 findPath 可达数千次）；fastutil computeIfAbsent 体积大不内联，捕获 lambda 命中也真实分配。处于 RUNNING 的 GateBehavior（村民 core activity 清醒时段长期存在）每 tick 分配 stream pipeline。
- **改法**：三处缓存改 get→未命中计算→put 两段式；GateBehavior 循环内跟踪"tick 前 RUNNING 且 tick 后仍 RUNNING"的布尔，替代 noneMatch stream。
- **等价性**：寻路单线程；三个映射函数均不重入同一缓存（getPathTypeOfMob→getPathTypeWithinMobBB→getPathType 不经 getCachedPathType；noCollision 不碰 collisionCache；Node 纯构造）且不返回 null（getPathTypeOfMob 全路径返回枚举常量）；boolean 缓存用 containsKey 区分未命中与存 false。GateBehavior：tick 前 STOPPED 的行为不会被兄弟行为的 tickOrStop 启动（tickOrStop 只改自身 status），故只看"曾 RUNNING"集合即等价（与 Paper 对同方法其余三处 stream 的移除同一论证）。
- **风险**：低。

### 暂缓（批次 31 评估后未做，附原因）
- **PathNavigation 每 tick Vec3/BlockPos 拆分量**：`getGroundY(Vec3)` 是 protected 虚方法（3 个实现），NMS 插件 override 后会被改道绕过，不满足兼容红线；原版内可证等价但扩展点风险不可接受。
- **declarative Behavior MemoryAccessor/Optional 复用**：需先全量审计是否存在跨 tick 持有 MemoryAccessor 的 trigger，工作量大，留待下批。
- **getSpread EnumMap/SpreadContext 线程级复用**：需严格保序复刻（EnumMap ordinal 序 vs 插入序、i1<i clear 语义），改动面大且事件顺序可被插件观察，留待下批单独仔细核对。
- ~~**漏斗 AABB 缓存**~~ **批次 33 已完成（0113，getItemsAtAndAbove）；批次 34 已完成 getEntityContainer（0116，suck 侧 BE 字段 + eject 侧 searchPosition 键控）**。
- ~~**PlayerPickupItemEvent/EntityPickupItemEvent 双事件门控**~~ **批次 32 已完成（0112）**。
- ~~**CraftEventFactory.handleBlockFormEvent 门控**~~ **批次 32 已完成（0114）**：EntityBlockFormEvent 无独立 HandlerList，单检查覆盖。
- **Biome.shouldFreeze / tickPrecipitation BlockPos 复用**：频率受 randomTickSpeed/48 与 biome 限制，价值低。
- **NearestLivingEntitySensor lambda/comparator 缓存、LookAtTargetSink Vec3、WalkNodeEvaluator.getPathType MutableBlockPos**：收益不确定（EA 可能已消除）或依赖不变量，暂缓。

---

## 批次 32（2026-07-31）：批次 31 暂缓项回头攻克（0112-0113）

### 0112 — ItemEntity.playerTouch 双事件门控（PlayerPickupItemEvent/EntityPickupItemEvent）
- **文件**：`net/minecraft/world/entity/item/ItemEntity.java`
- **热点**：物品成串拾取时每次成功拾取构造 2 个事件 + 2×getBukkitEntity + 2 次 callEvent 派发。
- **改法**：两事件 HandlerList 均空时复现默认结果：`!getCanPickupItems()` → 恢复 count 返回（等同取消分支，flyAtPlayer 默认 false 不 take）；否则 `item.setCount(canHold + remaining)`、`pickupDelay = 0`（零监听器事件不可能换 stack，`item.equals(current)` 恒真）。
- **等价性**：两事件默认取消规则相同（`!getCanPickupItems()`，同一 entity 的同一调用），取消分支逐行比对（setCount(count) 恢复 + return，无 take）；非取消路径与"事件未换 stack"分支逐语句一致；两事件无子类、构造仅字段赋值。
- **风险**：低-中（复现默认规则，已逐分支比对）。

### 0114 — CraftEventFactory.handleBlockFormEvent 零监听器门控（初稿误记 0113，补丁序列以补丁文件为准后正名）
- **文件**：`paper-server/src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java`（直接提交的源码，无补丁文件）
- **热点**：结冰/积雪/冰霜行者/雪傀儡每次方块形成构造 BlockFormEvent（或 EntityBlockFormEvent + getBukkitEntity）；tickPrecipitation 每次最多 3 次。
- **改法**：零监听器时跳过事件构造，直接 `snapshot.place(flags)` 返回 `!checkSetResult || result`。
- **等价性**：`EntityBlockFormEvent` **不声明自己的 HandlerList**（paper-api 源码实证：继承 BlockFormEvent 的静态 HANDLER_LIST 与 getHandlers），故单一 `BlockFormEvent.getHandlerList()` 检查覆盖两种事件形态；零监听器时 callEvent() 恒 true 且事件对象从不被读取（snapshot.place 不读事件）；snapshot 的构造与 setData 在两条路径中完全相同。
- **风险**：低。

---

## 批次 33（2026-07-31）：漏斗吸取 AABB 实例缓存（0113）

### 0113 — HopperBlockEntity 吸取 AABB 实例缓存
- **文件**：`net/minecraft/world/level/block/entity/HopperBlockEntity.java`（getItemsAtAndAbove + 新字段）
- **热点**：漏斗上方无方块容器时，每个搬运周期 `getSuckAabb().move(...)` 分配新 AABB；漏斗密集（物品分类机）服务器为稳定分配源。
- **改法**：`hopper instanceof HopperBlockEntity` 时缓存到 BE 实例字段 `papoSuckAabb`（惰性初始化）；矿车漏斗（会移动）走原逐次计算。
- **等价性**：`Hopper.SUCK_AABB` 是共享不可变常量（`getSuckAabb()` 无覆盖，全树 grep 实证）；BE 的 `worldPosition` final（vanilla 不可移动方块实体，setBlock 会重建 BE 得到新缓存）；AABB 不可变且 `getEntitiesOfClass` 只读；缓存值与逐次计算逐分量相同。
- **风险**：低。
- **暂缓**：`getEntityContainer` 的 `new AABB(...)`——静态方法链（suck/eject 共用、矿车共用）需穿线 BE 实例或按 pos 键控，复杂度高于收益，未做。

---

## 批次 34（2026-07-31）：事件门控规模化 + 寻路/挤压/检查路径分配消除（0114-0124 + 直接提交）

三个 survey 子代理（网络与玩家同步 / 生物 AI 与寻路 / 区块与世界 tick IO）共产出 24 个候选，逐个代码复核（含 HandlerList 层级、事件子类、构造副作用全库 grep 实证）后实现 11 个补丁 + 1 个 CraftEventFactory 直接提交。另将批次 30/31/33 的三个暂缓项（touchingUnloadedChunk、getEntityContainer、批次 31 遗留的 getPathType pos）经等价性实证后落地。全部 compileJava + 全量 test 通过，applyPatches 干净应用（915 patches）。

### 0114 — Entity.touchingUnloadedChunk 内联 inflate(1.0) 边界算术
- **文件**：`net/minecraft/world/entity/Entity.java` `touchingUnloadedChunk`
- **热点**：`doCheckFallDamage`（每次移动）与 `updateFluidHeightAndDoFluidPushing`（每实体每 tick ×2 路径，0114 实为 0104 同方法入口）各调用一次；每次分配 `getBoundingBox().inflate(1.0)` 新 AABB。
- **改法**：仅读 minX/maxX/minZ/maxZ，内联为 `bb.minX - 1.0` 等 4 个 double 运算（同 0104 deflate 内联模式；`AABB.inflate(v)` 逐运算相同）。方法体内部改动，无签名变化——批次 30 暂缓时担忧的"签名穿线"实际不需要。
- **等价性**：`inflate(1.0)` = `inflate(1.0,1.0,1.0)` = min-v/max+v（AABB.java:178-190 实证）；`getBoundingBox()` final 返回存储字段。基准 main：10 万随机 box + 3 万整数边界点逐位比对。
- **基准**：4.464 → 2.072 ns/op（**2.15×**）。
- **风险**：零。

### 0115 — 寻路 getPathType 的 BlockPos 分配消除（2 文件）
- **文件**：`net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java` + `FlyNodeEvaluator.java`
- **热点**：A) `WalkNodeEvaluator.getPathType` 每调用 `new MutableBlockPos(x,y,z)` 仅为 `getPathTypeStatic` 入口读出坐标为局部 int（寻路内层循环，每节点多次）；B) `FlyNodeEvaluator.getPathType` `new BlockPos(x, y-1, z)` 仅为取回坐标 + FENCE 分支一次 `BlockPos.equals`。
- **改法**：A) per-evaluator scratch `papoPathTypePos.set(x,y,z)`（0076 floorLevelPos 同款先例）；B) 直接 `getPathTypeFromState(x, y-1, z)` + FENCE 分支坐标比较。
- **等价性**：A) `getPathTypeStatic` 入口三行 `pos.getX/Y/Z()` 读出后从不保留/修改 pos（全方法实证），且 `PathfindingContext` 自身已用同款 mutablePos 复用；子类（Amphibious/Fly/Swim）整体重写 getPathType 不调 super；寻路 tick 线程单线程。B) `Vec3i.equals` final 纯坐标比较（Vec3i.java:45-47），fresh pos 与存储的 mobPosition 恒非引用相等；`PathfindingContext.mobPosition` 非空 BlockPos。
- **基准**：A 2.045 → 1.231（**1.66×**）；B 2.208 → 0.789（**2.80×**）。
- **风险**：低。

### 0116 — HopperBlockEntity getEntityContainer 实体容器搜索 AABB 缓存（批次 33 暂缓项回头攻克）
- **文件**：`net/minecraft/world/level/block/entity/HopperBlockEntity.java`（getSourceContainer/getAttachedContainer/getContainerAt/getEntityContainer + 3 新字段）
- **热点**：漏斗 suck（`getSourceContainer`）与 eject（`getAttachedContainer`→`getContainerAt`）每条搬运周期各构造 `new AABB(x-0.5, y-0.5, z-0.5, x+0.5, y+0.5, z+0.5)`；批次 33 以"静态方法链需穿线"暂缓，复核发现两个入口本就持有 hopper/blockEntity，无需穿线。
- **改法**：suck 侧 `papoEntitySuckAabb` BE 惰性字段（worldPosition final 故常量，0113 同款先例）；eject 侧按 `searchPosition` 键控缓存（`papoEntityEjectSearchPos`+`papoEntityEjectAabb`，pos.equals 命中复用——同方块 setBlock 改 facing 保留 BE 时自动重算）；新增 8-arg getContainerAt 重载传 @Nullable AABB，矿车漏斗 instanceof 门控传 null 走原逐次构造。
- **等价性**：缓存构造表达式与逐次构造逐字符相同（bit 级一致，基准 main 10 万随机 BE 逐分量 Double.compare 比对 + eject 双目标交替键控重算验证）；AABB 不可变且 `getEntitiesOfClass` 只读；公共 3-arg getContainerAt（CrafterBlock/DropperBlock 用）路径不动。
- **基准**：suck 3.606 → 0.560（**6.44×**）；eject 3.602 → 0.729（**4.94×**）。
- **风险**：低。

### 0117 — BlockFadeEvent 16 个调用点迁移至零监听器快路
- **文件**：`CraftEventFactory.java`（新增 `handleBlockFadeEvent`，直接提交部分）+ 16 个 NMS 文件调用点（补丁）
- **热点**：火自然熄灭（BaseFireBlock）、冰融化、草/菌丝衰变、积雪融化、红石矿熄灭、珊瑚死亡、耕地退化等 randomTick 路径每次固定分配 `CraftBlock` + `CraftBlockState` + `BlockFadeEvent` + 派发；16 个调用点全部只读 `.isCancelled()`。
- **改法**：`CraftEventFactory.handleBlockFadeEvent` = `getRegisteredListeners().length > 0 && callBlockFadeEvent(...).isCancelled()`（旧方法保留）；16 调用点机械替换 `callBlockFadeEvent(...).isCancelled()` → `handleBlockFadeEvent(...)`（python 脚本执行，逐文件 1:1 核验）。
- **等价性**：零监听器时 Cancellable 事件恒不取消（BlockFadeEvent 有自己的 HANDLER_LIST、无子类、构造仅字段赋值，全库实证）；`CraftBlockStates.getBlockState` = `new CraftBlockState(CraftBlock.at(...))` 纯分配无世界修改——整个方法体在零监听器时可证无副作用，跳过即 `false`。
- **风险**：低。**价值：高**（火/冰/草衰变是自然世界稳定热源）。

### 0118 — 玩家状态切换事件门控×4：PlayerToggleSprint / PlayerToggleSneak / PlayerItemHeld / EntityPoseChange
- **文件**：`ServerGamePacketListenerImpl.java`（handlePlayerCommand/handlePlayerInput/handleSetCarriedItem）+ `Entity.java`（setPose）
- **热点**：每次冲刺/潜行切换、热键栏切换、每次实体姿势切换（潜行/游泳/鞘翅，全实体）无条件构造事件 + 派发；setPose 还每次克隆 `Pose.values()` 数组。
- **改法**：`length > 0` 门控（sneak 的零监听器分支复刻 `!isCancelled() && hasClientLoaded()` → `setShiftKeyDown` 语义；itemheld 零监听器直接落到槽位切换）。
- **等价性**：4 事件均 Cancellable 默认 false（Pose 事件非 Cancellable 且 callEvent 结果被丢弃）、有自己的 HANDLER_LIST、无子类、构造仅字段赋值（逐事件 paper-api 实证）；零监听器时控制流落点逐分支一致。
- **风险**：零-低。

### 0119 — 世界 tick 事件门控：LeavesDecayEvent + BlockIgniteEvent
- **文件**：`LeavesBlock.java`（randomTick）+ `FireBlock.java`（tick 蔓延循环）
- **热点**：树叶腐烂是 MC 最密集世界事件之一（砍树后数百叶片逐片 randomTick）；火蔓延对每个通过随机判定的邻位构造 `CraftBlock.at` ×2 + 事件 + 派发。
- **改法**：叶衰减零监听器分支保留 `!level.getBlockState(pos).is(this)` 复查（无分配，控制流逐点一致）；火点燃调用点 `length > 0 &&` 短路。
- **等价性**：两事件均有自己的 HANDLER_LIST、无子类、构造仅字段赋值；零监听器时 isCancelled 恒 false，原控制流必然前进到 dropResources/removeBlock（叶）与 handleBlockSpreadEvent（火）。
- **风险**：低。

### 0120 — PathNavigation.followThePath/doStuckDetection 直读 Node 坐标
- **文件**：`net/minecraft/world/entity/ai/navigation/PathNavigation.java`
- **热点**：每个寻路中的生物每 tick：`followThePath` `getNextNodePos()`（`nodes.get(i).asBlockPos()` = new BlockPos）+ `doStuckDetection` 再一次，另有 `getNextNode()` 重复取值。
- **改法**：`Node nextNode = this.path.getNextNode()` 一次取值直读 `x/y/z/type`；`doStuckDetection` 改手工坐标比较，`timeoutCachedNode` 仅在节点推进时 `nextNode.asBlockPos()`（分配从每 tick 降为每次推进）。
- **等价性**：`getNextNodePos()` 与 `getNextNode()` 同一 `nodes.get(nextNodeIndex)`（Path.java:88-94 实证），两次读间无 path 变更；`Vec3i.equals` final 纯坐标比较，fresh pos 与缓存 pos 恒非引用相等；`timeoutCachedNode` 仅被 equals 与 `atBottomCenterOf`（坐标方法）使用，身份不可观察。基准 main：10 万随机推进/回退序列两路径判定与缓存坐标一致。
- **基准**：3.514 → 1.957（**1.80×**）。
- **风险**：低。

### 0121 — EntityPathfindEvent 零监听器门控
- **文件**：`net/minecraft/world/entity/ai/navigation/PathNavigation.java` `createPath`
- **热点**：每次寻路尝试对 targets 每个目标构造 `CraftLocation.toBukkit`（Location）+ 事件 + 派发；村民/敌对生物寻路是大服 AI 最重入口之一。
- **改法**：循环外一次 `papoHasPathfindListeners`，循环条件加 `papoHasPathfindListeners &&` 短路。
- **等价性**：事件有自己的 HANDLER_LIST、无子类、构造仅字段赋值；零监听器时 `callEvent()` 恒 true，原条件精确退化为仅世界边界检查。已知取舍（0100 同款）：零监听器异步寻路不再触发同步事件线程检查——仅插件违规调用可达。
- **风险**：低。

### 0122 — AbstractMinecart.tick VehicleUpdateEvent/VehicleMoveEvent 整块门控
- **文件**：`net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java` `tick`
- **热点**：每矿车每 tick 构造 2 个 Location + VehicleUpdateEvent 派发，移动时再 VehicleMoveEvent；漏斗矿车农场/运输线数十至数百矿车。
- **改法**：两事件任一 `length > 0` 才进入原 CraftBukkit 块（整块原样保留，混合监听场景行为逐字节一致）。
- **等价性**：两事件均非 Cancellable、有自己的 HANDLER_LIST、无子类、构造仅字段赋值；from/to/vehicle/bworld 四个局部量只服务于事件构造，零监听器时整块为可证空操作。
- **风险**：低。

### 0123 — Entity.push(x,y,z,null) 路径 Vector 分配消除
- **文件**：`net/minecraft/world/entity/Entity.java` `push(double,double,double,Entity)`
- **热点**：实体-实体挤压（`push(Entity)` → 三参 → 四参 null）每对重叠可推实体每 tick；`new org.bukkit.util.Vector(x,y,z)` 在 null 路径无条件分配仅为读回分量。
- **改法**：null 分支直接 `setDeltaMovement(getDeltaMovement().add(x, y, z))`；Vector 构造移入非 null 分支内。
- **等价性**：`Vector(double,double,double)` 仅存字段（Vector.java:71-75），getX/Y/Z 原样读回 → `add(delta.getX(),...)` 与 `add(x,y,z)` 位级一致；`needsSync` 两路径均置位；取消早退语义不变。基准 main：100 万随机输入累加逐位一致。
- **基准**：3.168 → 2.793（**1.13×**——单点分配部分被 EA 掩盖，如实记录；高频调用下仍稳定为正）。
- **风险**：极低。

### 0124 — EntityTargetEvent 系门控×3 + EndermanAttackPlayerEvent 门控
- **文件**：`TemptGoal.java`（canUse）+ `TemptingSensor.java`（doTick）+ `Mob.java`（setTarget）+ `EnderMan.java`（isBeingStaredBy）
- **热点**：TemptGoal 运行中每 tick `canContinueToUse`→`canUse` 构造+派发目标事件；TemptingSensor 每 20 tick 每动物；`Mob.setTarget` 是所有目标获取/丢失必经口；末影人对每个候选玩家构造+派发。
- **改法**：前三处按 `EntityTargetEvent.getHandlerList()` 门控——**关键实证**：`EntityTargetLivingEntityEvent` 不声明自己的 HandlerList，其监听器注册进 `EntityTargetEvent` 的列表，故该列表为空 ⟺ 无人能观察此事件；TemptingSensor 零监听器分支复刻 `setMemory(TEMPTING_PLAYER, player)`（getHandle() 即同一 NMS 对象）；EnderMan 零监听器直接 `return shouldAttack`（cancelled 保持 `!shouldAttack`，callEvent 返回 `!cancelled`）。
- **等价性**：各事件构造仅字段赋值；零监听器时默认取消规则与 target 重映射逐分支比对（TemptGoal player 不变、setTarget target 不变含 null 情形、TemptingSensor setMemory 同对象、EnderMan 返回值同）。
- **风险**：低。

### 直接提交（无补丁文件，编号 0125）— CraftEventFactory 四事件门控
- **文件**：`paper-server/src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java`（直接提交的源码）
- **内容**：
  1. 新增 `handleBlockFadeEvent` 零监听器快路（配合 0117 的 16 调用点）；
  2. `handleBlockSpreadEvent` 零监听器跳过事件构造（保留 snapshot+place；BlockSpreadEvent 有自己的 HANDLER_LIST、无子类）——火蔓延/藤蔓/草蔓延/海带/紫水晶/钟乳石 20 个调用点受益；
  3. `handleMoistureChangeEvent` 同款门控（耕地湿度）；
  4. `callEntityChangeBlockEvent` 零监听器早退返回 `!cancelled`（**关键实证**：唯一子类 EntityBreakDoorEvent 不声明 HandlerList，其监听器注册进 EntityChangeBlockEvent 列表，门控精确充分）——羊吃草、狐狸吃浆果、兔子啃胡萝卜、凋零破坏、 weaving 蛛丝等 15+ 调用点受益。
- **等价性**：零监听器时各事件恒不取消（Cancellable 默认 false），snapshot 构造/place 语义逐行保留（spread/moisture 仅省事件构造；EntityChangeBlock 全方法体可证无副作用故整体跳过）；均 0114（handleBlockFormEvent）同款已验证模式。
- **风险**：低。

### 暂缓（批次 34 评估后未做，附原因）
- **PlayerMoveEvent from/to Location 延迟到 delta 阈值后分配**（survey 1 #5，中价值）：CraftBukkit 魔改区分支重排，数值等价论证已备但需移动/传送冒烟验证，留批次 35 单独落地。
- **RegistryOps(NbtOps) 按 RegistryAccess 实例缓存**（survey 1 #8，中价值）：核心 registry 类改动，需先基准验证聊天路径，留批次 35。
- **getSpread EnumMap/SpreadContext 复用**：返回值 protected 逃逸 + 插件可观察 spread 顺序，三次评估维持暂缓。
- **EntitySelector.getPredicate 特性谓词缓存**：lambda 捕获调用时值（box/pos/range），缓存键复杂，收益小，维持暂缓。
- **PlayerSensor.getFollowDistance 外提 + TemptingSensor TargetingConditions 字段**（survey 2 #7/#8，低价值）：成立，留批次 35 顺手带走。
- **shouldTargetNextNodeInDirection 的 getNextNodePos/getNodePos**（0120 同方法区）：仅 canCutCorner 短路路径条件性到达，频次低，未动。
- **FoodLevelChangeEvent×4 / PlayerInputEvent**（survey 1 后备）：频率远低于入选项，记录后备。
- **survey 3 全部 6 项**（handleBlockGrowEvent 门控、PlayerNaturallySpawnCreaturesEvent 门控+复用、SerializableChunkData sections Optional、ChunkGenerator stream×2、callRedstoneChange 门控+7 调用点、DEFLATE 写侧 Deflater 池化）：复核通过，整体移入批次 35。

---

## 批次 35（2026-07-31）：反序列化/装饰/压缩/传感器/移动包分配消除 + RegistryOps 缓存（0125-0133 + 直接提交 0134）

批次 34 暂缓清单 9 项整体落地（PlayerMoveEvent Location 延迟与 RegistryOps 缓存两项"仔细复核"件完成逐位/线程安全实证），holderSet 流改 for-each 一项经 JMH 实测 0.82× 回退按延迟否决规则不予应用。全部 compileJava + 全量 test 通过，applyPatches 干净应用（924 patches）。

### 0125 — BlockRedstoneEvent 11 个 getNewCurrent 调用点迁移 + ContainerOpenersCounter 3 处门控
- **文件**：`ComparatorBlock`×2、`DaylightDetectorBlock`、`DiodeBlock`×2、`ObserverBlock`×2、`PoweredRailBlock`、`RedstoneLampBlock`×2、`LecternBlock`（手工）+ `ContainerOpenersCounter`×3（fire-and-forget 处 `if (length > 0)` 包裹）
- **热点**：比较器/中继器/侦测器/阳光探测器/讲台/红石灯/动力轨每次信号变化无条件构造 CraftBlock+事件+派发（红石机器高频路径）。
- **改法**：全部改走 `CraftEventFactory.handleRedstoneChange`（直接提交 0134 新增的零监听器快路：无监听器直接返回 newCurrent）。
- **等价性**：BlockRedstoneEvent 有自己的 HANDLER_LIST、无子类、构造仅字段赋值；零监听器时 `getNewCurrent()` 恒等于传入 newCurrent。
- **风险**：低。**价值：高**（红石时钟/机器是稳定热源）。

### 0126 — PlayerNaturallySpawnCreaturesEvent 零监听器门控 + 实例复用（批次 29 暂缓项回头攻克）
- **文件**：`net/minecraft/server/level/ServerChunkCache.java` `tickChunks`
- **热点**：每玩家每 tick 刷怪检查构造事件（含 CraftWorld/CraftBlock 包装）+ 派发；批次 29 以"监听器中途注册/取消的场景"暂缓。
- **改法**：零监听器时复用上一实例：仅当 `event != null && !event.isCancelled() && event.getSpawnRadius() == (byte) chunkRange` 复用，否则重建——覆盖监听器注册/注销切换与取消/半径变更的全部检出路径。
- **等价性**：读者（ChunkMap:732,828）只读 `isCancelled()`/`getSpawnRadius()`（全库实证）；未取消且半径未变的事件与新建事件对外可观察状态完全一致。
- **风险**：低。

### 0127 — SerializableChunkData sections 循环 Optional 消除
- **文件**：`net/minecraft/world/level/chunk/storage/SerializableChunkData.java` `parse`
- **热点**：区块反序列化每 section 约 9 个 Optional（`ListTag.getCompound(int)` + `getCompound("block_states"/"biomes")` 的 map/orElseGet + `getByteArray("BlockLight"/"SkyLight")` 的 map/orElse），24 section ≈ 216 个/区块。
- **改法**：全部改 instanceof 三元：`listOrEmpty1.get(i2) instanceof CompoundTag`（界内等价私有 getNullable）、`compoundTag.get("block_states") instanceof CompoundTag` 等。
- **等价性**：`ListTag.getCompound(int)` = `getNullable(i) instanceof CompoundTag ? Optional.of : empty`（ListTag.java:261-263），`getNullable` 私有（:325-327）但循环索引恒在界内 ⇒ 公有 `List.get(i)` 同对象；`CompoundTag.getCompound/getByteArray` 同构（:434-436/:422-424）。基准 main：含缺键 section 与非 Compound 元素逐项引用/分支比对。
- **基准**：420.373 → 359.483 ns/op（**1.17×**）。
- **风险**：零。

### 0128 — ChunkGenerator.addVanillaDecorations rangeClosed 3×3 流改双循环
- **文件**：`net/minecraft/world/level/chunk/ChunkGenerator.java` `addVanillaDecorations`
- **热点**：每区块装饰一次 `ChunkPos.rangeClosed(chunk,1).forEach` = 1 Stream + 9 ChunkPos + 闭包。
- **改法**：`papoCenterChunk` 双循环直取 9 个坐标 `level.getChunk(x, z)`。
- **等价性**：`rangeClosed(center,1)` = 闭区域 [x±1]×[z±1]（ChunkPos.java:216-218）；生物群系 Holder 进 `ObjectArraySet` 内容序无关，下游 `retainAll`→IntSet→`toIntArray+Arrays.sort` 输出完全排序 ⇒ 迭代序不影响结果。
- **基准**：114.558 → 74.290 ns/op（**1.54×**）。
- **同文件否决项**：`:408 holderSet.stream().map(Holder::value).forEach` 改 for-each 实测 249.738 → 304.919（**0.82×** 回退，ArrayList spliterator 索引循环+管道内联优于 Iterator+虚调用），按延迟否决规则未应用，测量存档于 DecorationsStreamBench。
- **风险**：零。

### 0129 — DEFLATE 写侧 Deflater ThreadLocal 池化 + 缓冲加大（0103 读侧镜像）
- **文件**：`net/minecraft/world/level/chunk/storage/RegionFileVersion.java`
- **热点**：每次区块保存 `new Deflater(level)`（本地 zlib 状态分配 + Cleaner 注册）+ close 时 `end()`；DeflaterOutputStream 内部缓冲默认 512、外层 BufferedOutputStream 默认 8192。
- **改法**：`PAPO_DEFLATER_POOL` ThreadLocal 单槽（`PapoPooledDeflater{deflater,level}`）；close 时 `reset()`+归还（显式 Deflater 构造 `usesDefaultDeflater=false`，close 不 end——JDK 字节码实证）；池按压缩级别键控，配置重载级别变更时 `end()` 旧实例（行为同前）；`super(out, deflater, 8192)` + 外层 `BufferedOutputStream(..., 32768)`。
- **等价性**：`reset()` 恢复与同参数新实例完全一致的状态，压缩字节流仅取决于输入字节与 flush 模式（syncFlush=false 无中途 flush 点）——基准 main：池化复用（借还两轮）与全新 Deflater **逐字节一致**；并发/嵌套流降级为 GC/Cleaner 回收（同 0103 先例）。常量限定名引用（`RegionFileVersion.PAPO_REGION_WRITE_BUFFER_SIZE`）规避 JLS 8.3.3 字段初始化器简单名前向引用限制（编译期实证修复）。
- **基准**：1334.948 → 1324.696 µs/op（≈1.01× 持平——单次 64KB 压缩约 1.3ms，收益在每次保存消除本地分配/Cleaner 注册与 native 调用次数，非单点吞吐）。
- **风险**：低。

### 0130 — TemptingSensor TargetingConditions 实例字段复用
- **文件**：`net/minecraft/world/entity/ai/sensing/TemptingSensor.java`
- **热点**：`doTick` 每次 `TEMPT_TARGETING.copy().range(...)`（分配 + 全字段复制）；每动物每 20 tick。
- **改法**：`private final TargetingConditions targetingConditions = TEMPT_TARGETING.copy();` 字段 + 每 tick `this.targetingConditions.range(当前属性值)`（TemptGoal.java:48 字段先例）。
- **等价性**：`range()` 仅改 range 字段返回 this（TargetingConditions.java:39-42）；`copy()` 复制全部字段（:30-37）故字段实例与新鲜 copy 除 range 外处处一致；`test()` 只读无突变 ⇒ 每 tick 重设当前属性值与新建 `copy().range()` 行为完全一致，**属性运行时变更（/attribute）亦即时生效**（比 vanilla TemptGoal 构造期固化更忠实）。
- **基准**：9.490 → 8.144 ns/op（**1.17×**）。
- **风险**：零。

### 0131 — PlayerSensor.getFollowDistance 外提出逐玩家流过滤
- **文件**：`net/minecraft/world/entity/ai/sensing/PlayerSensor.java` `doTick`
- **热点**：`filter(p -> entity.closerThan(p, this.getFollowDistance(entity)))` 逐玩家属性取值（AttributeInstance.getValue 带 modifier 遍历）。
- **改法**：doTick 入口一次 `double papoFollowDistance = this.getFollowDistance(entity)`。
- **等价性**：单次 doTick 流求值单线程、中途无属性变更 ⇒ 值恒定；无子类覆写 `getFollowDistance(LivingEntity)`（全库 grep 实证——Llama/PolarBear/TargetGoal 的 `getFollowDistance()` 是另一类无参方法）。
- **基准**：29.954 → 8.833 ns/op（**3.39×**）。
- **风险**：零。

### 0132 — PlayerMoveEvent from/to Location 阈值后延迟构造（2 处）
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java` `handleMovePlayer` + `handleMoveVehicle`
- **热点**：handleMovePlayer 每移动包构造 `from`（1 Location）+ `to`（`player.getLocation().clone()`，2 Location），多数包随即被 1/256 阈值过滤（该阈值正为防刷屏而设）；handleMoveVehicle 每载具包 2 个 Location。
- **改法**：先算标量 toX/toY/toZ/toYaw/toPitch 与 delta/deltaAngle，阈值通过才构造 from/to（构造移入 if 块，from 在 lastPos 更新前取值，内部块逐行原样保留）。
- **等价性（逐项实证）**：非包值分量复刻 `absSnapTo` 存储语义——x/z `Mth.clamp(±3.0E7)`、y 原样（Entity.java:2219-2227）、yaw `% 360.0F`、pitch `clamp(±90) % 360`（absSnapRotationTo :2211-2213；yRot 经防护 setter 恒有限）；`getLocation()` = `CraftLocation.toBukkit(position(), getWorld(), getBukkitYaw(), getXRot())` 与 `player.getWorld()` 同一引用（CraftEntity 实证，CraftPlayer 无覆写）；snap 与阈值块之间无位置突变；from/to 仅在阈值通过分支内使用。基准 main：hasPos/hasRot × 常规/越界 yaw/pitch/超 3e7 坐标矩阵下分量与阈值判定**逐位一致**。
- **基准**：10.933 → 1.984 ns/op（被过滤包 **5.51×**；通过包两路径成本相同）。
- **风险**：低。

### 0133 — ImmutableRegistryAccess 缓存 NBT RegistryOps（聊天/组件包编解码热路径）
- **文件**：`net/minecraft/core/RegistryAccess.java`（ImmutableRegistryAccess + `papoNbtSerializationContext()`）+ `net/minecraft/network/codec/ByteBufCodecs.java`（fromCodecWithRegistries 两方法）
- **热点**：`fromCodecWithRegistries` 每次编解码 `buffer.registryAccess().createSerializationContext(NbtOps.INSTANCE)` = `RegistryOps + HolderLookupAdapter + ConcurrentHashMap` 分配——聊天组件、实体元数据 Component、HoverEvent、成书页面等一切 Component 包必经。
- **改法**：ImmutableRegistryAccess 增 `volatile RegistryOps<Tag> papoNbtOps` 惰性缓存；ByteBufCodecs 经 `papoNbtSerializationContext(access)`：`instanceof ImmutableRegistryAccess` 走缓存，否则原路径。
- **等价性/线程安全（逐项实证）**：`HolderLookupAdapter.lookups` 为 **ConcurrentHashMap**（RegistryOps.java:95）computeIfAbsent 线程安全；NbtOps 无状态单例；RegistryOps 仅捕获不可变注册表访问，缓存条目纯派生；首次竞态至多建出 equals 相等的两个实例（benign race）；服务端 `registryAccess()` = `LayeredRegistryAccess.composite` 确为 ImmutableRegistryAccess（freeze 的 FrozenAccess 子类）⇒ 快路必然命中；非不可变访问（如 `fromRegistryOfRegistries` 匿名类）走原路径。
- **基准**：4.950 → 0.451 ns/op（**10.97×**）。
- **风险**：低。

### 直接提交（无补丁文件，编号 0134）— handleBlockGrowEvent 零监听器门控 + handleRedstoneChange 快路
- **文件**：`paper-server/src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java`（直接提交的源码）
- **内容**：
  1. `handleBlockGrowEvent` 零监听器时 `snapshot.place(flags); return true;`（BlockGrowEvent 有自己的 HANDLER_LIST、无子类、构造仅字段赋值——作物生长/树苗/蘑菇/藤蔓 21 个调用点受益）；
  2. 新增 `handleRedstoneChange` 零监听器快路（`length == 0 → return newCurrent`），配合 0125 的 11+3 调用点。
- **等价性**：零监听器时 BlockGrowEvent 恒不取消，place 语义逐行保留；红石快路返回值为 `callRedstoneChange(...).getNewCurrent()` 在零监听器时的恒等值。
- **风险**：低。

### 暂缓/否决（批次 35 记录）
- **ChunkGenerator :408 holderSet 流改 for-each**：JMH 实测 0.82× 回退，**否决**（同 0100 先例；测量存档 DecorationsStreamBench (b)）。
- **NbtContents:119 / TagValueOutput 等其余 `createSerializationContext(NbtOps)` 调用点**：0133 只覆盖每包热路径 ByteBufCodecs；其余为低频（指令解析/存档），记录后备。

---


## 批次 36（2026-08-01）：事件门控续批 + AI/容器/网络小包分配消除（0134-0149 + 直接提交 0150）

survey 1（事件门控/AI/网络/物品四线）小中项 14 项落地；PlayerInteractEvent 门控按 `isCancelled()==useInteractedBlock()==DENY`（无独立 cancelled 字段）与构造器默认值（`useItemInHand=DEFAULT`、`useClickedBlock=clickedBlock==null?DENY:ALLOW`）逐调用点推导。全部 compileJava + 全量 test 通过，applyPatches 干净应用（940 patches）。编号说明：callPreCraftEvent 快路的直接提交初记"0142"，rebuildPatches 后 0142 已由 InteractWithDoor 补丁占用，按"补丁序列以补丁文件为准"先例（0113/0114）直接提交正名 **0150**。

### 0134 — ComponentSerialization 接入 0133 RegistryOps 缓存
- **文件**：`net/minecraft/network/codec/ComponentSerialization.java`
- **热点**：Paper 的 adventure 感知 Component 编解码（聊天 3 包/actionbar/title/TabList/BossBar/实体 CustomName/物品名+lore）每次 `RegistryOps.create(NbtOps.INSTANCE, registryAccess)`——0133 只覆盖了 ByteBufCodecs 路径。
- **改法**：`:49/:55` 改 `ByteBufCodecs.papoNbtSerializationContext(...)`（interface static 隐式 public，0133 方法由 private 放宽）。
- **等价性**：与 0133 完全同源（同一 volatile 惰性缓存、同一回退路径）。
- **基准**：机制同 0133（RegistryOpsCacheBench 4.950 → 0.451 ns/op，**10.97×**），本补丁仅扩展覆盖面。
- **风险**：低。

### 0135 — Entity.updateFluidOnEyes 复用 scratch MutableBlockPos
- **文件**：`net/minecraft/world/entity/Entity.java`
- **热点**：每实体每 tick 眼位流体检查 `new MutableBlockPos(...)`（0104 已建实体级 scratch，此方法仍新分配）。
- **改法**：复用 `papoFluidMutablePos`（`set(Mth.floor×3)`），字段注释列明两个使用方。
- **等价性**：set-and-read 主线程串行；`getFluidState` 只读坐标。
- **基准**：117.950 → 91.130 ns/op（**1.29×**）。
- **风险**：零。

### 0136 — POI 查询 Optional 包装消除
- **文件**：`net/minecraft/world/entity/ai/village/poi/PoiSection.java` + `PoiManager.java` + `net/minecraft/world/entity/ai/behavior/PoiCompetitorScan.java`
- **热点**：`PoiManager.exists/getTypeOrNull` 内部经 `Optional.ofNullable(...).isPresent()/get` 包装拆包；村庄生物 POI 竞争扫描每 tick 多次。
- **改法**：PoiSection/PoiManager 新增可空引用 `getTypeOrNull`；`exists` 与 PoiCompetitorScan 直接消费引用（`if (poi != null)` 内保留 Paper 展开循环）。
- **基准**：417.134 → 278.027 ns/op（**1.50×**）。
- **风险**：零。

### 0137 — 交互三触发器零监听器早退 + stack.copy 随门省略
- **文件**：`AnyBlockInteractionTrigger`/`ItemUsedOnLocationTrigger`/`DefaultBlockInteractionTrigger`（advancement）+ `ServerGamePacketListenerImpl:2123` + `ServerPlayerGameMode:540`
- **热点**：每次方块交互 `ANY_BLOCK_USE.trigger(...)`（持 `stack.copy()`）与 `ITEM_USED_ON_BLOCK.trigger(player, pos, stack.copy())`——零进度服纯浪费。
- **改法**：三触发器加 `papoHasListeners(player)` 公有门（`getListeners` 数组长度）；`:2123` 触发前短路；`:540` copy 改 `papoHasListeners(player) ? stack.copy() : null`（两处使用点均为该触发器调用）。
- **等价性**：监听器不可在同 tick 同线程中途变化；零监听器时 trigger 无任何效果。
- **基准**：1.960 → 0.466 ns/op（**4.21×**）。
- **风险**：低。

### 0138 — PlayerInteractEvent 零监听器门控 7 调用点
- **文件**：`ServerPlayerGameMode`（useItemOn、左键 START_DESTROY、mayInteract fire-and-forget）+ `ServerGamePacketListenerImpl`（:2198/:2204 cancelled=false 快路、handleAnimate raytrace 整块）
- **热点**：每次右键/左键/挥臂构造 PlayerInteractEvent + CraftBlock 包装 + 射线检测（handleAnimate 每次挥臂全量 raytrace）。
- **改法**：逐点以构造器参数推导零监听器结果（`papoUseInteractedBlockDeny` 等布尔短路）；handleAnimate 的 raytrace 块整体包在 `getRegisteredListeners().length > 0` 内。
- **等价性**：`isCancelled()` 即 `useInteractedBlock()==DENY`（paper-api:91-93 实证，无独立 cancelled 字段）；零监听器时事件对外可观察结果仅由构造参数决定，逐点布尔等价；事件无子类（API 验证）。
- **基准**：2.299 → 0.333 ns/op（**6.90×**）。
- **风险**：低。

### 0139 — ResultSlot ItemCraftedEvent 零监听器门控×2
- **文件**：`net/minecraft/world/inventory/ResultSlot.java`
- **热点**：`onQuickCraft`（shift 合成每叠）与 `onTake`（每次取出）构造事件 + `CraftItemStack.asBukkitCopy` 包装。
- **改法**：`!stack.isEmpty() && getRegisteredListeners().length > 0` 门控。
- **等价性**：事件非 Cancellable、`callEvent()` 返回值未使用、构造仅字段赋值。
- **基准**：1.294 → 0.522 ns/op（**2.48×**）。
- **风险**：零。

### 0140 — handleUseItemOn 命中距离 Vec3×2 展开
- **文件**：`ServerGamePacketListenerImpl.java` `handleUseItemOn`
- **热点**：每右键包 `Vec3.atCenterOf(blockPos)` + `location.subtract(center)` 两次分配仅为三个 `Math.abs(...) < 1.0000001` 分量比较。
- **改法**：`Math.abs(location.x() - (blockPos.getX() + 0.5)) < 1.0000001 && …`（死代码 `double d` 一并移除）。
- **等价性**：`atCenterOf`=每轴+0.5（Vec3.java:52-54）、`subtract`=逐分量（:95-97），double 加减展开位级一致（基准 main 含 NaN/Inf/边界矩阵穷举）。
- **基准**：109.088 → 139.331 ns/op（0.78× 读数经 `-prof gc` 证伪：两路径 alloc.norm 均 0.001 B/op、原语操作按构造相同，为等价代码编译布局噪声；生产路径展开版零分配不依赖 EA，恒不劣于原版——保留，详见报告裁决记录）。
- **风险**：零。

### 0141 — PlayerChunkSender.collectChunksToSend 两分支流管道改命令式
- **文件**：`net/minecraft/server/network/PlayerChunkSender.java`
- **热点**：每玩家每 tick 区块发送收集：稳态 else 分支 `longStream().mapToObj().filter().sorted().toList()`（pending ≤ quota 占绝大多数 tick）；least 分支 least 选择后二级管道解析。
- **改法**：else 分支 `ArrayList + LongIterator 循环 + list.sort`（未用 Objects import 移除）；least 分支保持 `Comparators.least` 选择不变、仅解析改普通循环。
- **等价性**：`longStream` 与 `longIterator` 同迭代源；`Stream.sorted` 与 `List.sort` 同为稳定排序同比较器；返回列表只读消费（isEmpty/for/size）。
- **基准**：else 分支 1400.411 → 952.007 ns/op（**1.47×**）；least 解析 1294.853 → 1269.412 ns/op（1.02×）。
- **风险**：零。

### 0142 — InteractWithDoor 每 tick 双 asBlockPos 改 scratch + 坐标直读比较
- **文件**：`net/minecraft/world/entity/ai/behavior/InteractWithDoor.java`
- **热点**：PATH 记忆存在时每 tick `previousNode.asBlockPos()` + `nextNode.asBlockPos()` 两个不可变 BlockPos 仅供 `getBlockState` 坐标读（行走村民/猪灵常态）；`closeDoorsThatIHaveOpenedOrPassedThrough` 与 `isMobComingThroughDoor` 另有 `asBlockPos().equals(pos)` 每门每 tick 分配。
- **改法**：per-behavior scratch `MutableBlockPos`（create() 闭包内，与既有 `mutableObject`/`mutableInt` 同生命周期）供 getBlockState 检查；**门分支（setOpen/CraftBlock.at/rememberDoorToClose→GlobalPos.of 逃逸点）仍分配 immutable**；两处 equals 比较改节点字段坐标直读。
- **等价性**：`Level.getBlockState`/`BlockTags.is(tag,predicate)` 只读坐标/state；`Vec3i.equals` 即坐标比较（final 方法）；scratch 不入逃逸分支（该分支分配点与原版一致）。
- **基准**：scratch 264.685 → 152.474 ns/op（**1.74×**）；坐标直读 51.847 → 53.010 ns/op（0.98×，CI 重叠持平——免分配收益体现为 GC 压力）。
- **风险**：低。

### 0143 — EntityEquipment.tick 遍历 VALUES 替代 EnumMap entrySet
- **文件**：`net/minecraft/world/entity/EntityEquipment.java`
- **热点**：每生物每 tick `items.entrySet()` 迭代——EnumMap 的 EntryIterator 每个已设槽分配一个 MapEntry。
- **改法**：`EquipmentSlot.VALUES`（缓存不可变 List）索引循环 + `items.get(slot)`（O(1) 数组读，未设槽 null 跳过）。
- **等价性**：EnumMap 迭代即枚举序 = VALUES 顺序；`get` 对未设槽返回 null、对已设 EMPTY 栈走 isEmpty 跳过——与 entrySet 过滤完全同集合同序（未用 Entry import 移除）。
- **基准**：27.090 → 12.315 ns/op（**2.20×**）。
- **风险**：零。

### 0144 — 熔炉 serverTick SingleRecipeInput 按输入槽栈引用缓存
- **文件**：`net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java`
- **热点**：燃烧中每熔炉每 tick `new SingleRecipeInput(itemStack1)`。
- **改法**：BE 字段 `papoLastInputStack`/`papoCachedInput`，引用不变即复用，变化则重建（`:363` getTotalCookTime 低频路径不动）。
- **等价性**：record 仅包装引用——shrink 等原位修改透过缓存可见（与新鲜包装同一对象）；槽位换引用（燃尽/放入/NBT 加载）必触发重建；下游（quickCheck/canBurn/burn）只读 input。
- **基准**：125.053 → 80.370 ns/op（**1.56×**）。
- **风险**：低。

### 0145 — ChunkHolder.broadcast 捕获 lambda forEach 改索引循环
- **文件**：`net/minecraft/server/level/ChunkHolder.java`
- **热点**：每次方块/光照/块实体广播 `players.forEach(player -> connection.send(packet))`——捕获 lambda 每次调用一次分配。
- **改法**：索引循环。
- **等价性**：`players` 为 `moonrise$getPlayers` 每次新建的局部 ArrayList（:92 实证），无并发修改面，迭代序一致。
- **基准**：53.568 → 50.971 ns/op（1.05×，CI 重叠持平；首轮基准自身缺陷已修复复测）。
- **风险**：零。

### 0146 — 光照更新包两列表按截面数预分配
- **文件**：`net/minecraft/network/protocol/game/ClientboundLightUpdatePacketData.java`
- **热点**：每区块光照包 `skyUpdates/blockUpdates` 默认容量 10，主世界 24 节全量更新时三次扩容拷贝（10→15→22→33）。
- **改法**：`new ArrayList<>(lightEngine.getLightSectionCount())`（未用 Lists import 移除）。
- **等价性**：容量不经 List API 可观察。
- **基准**：90.051 → 42.660 ns/op（**2.11×**）。
- **风险**：零。

### 0147 — 实体追踪 getEffectiveRange 扫描外提
- **文件**：`net/minecraft/server/level/ChunkMap.java`（TrackedEntity）
- **热点**：`moonrise$tick`/`updatePlayers` 每 (实体,玩家) 对调 `getEffectiveRange()`（乘客列表检查 + 乘客范围遍历 + 服务器配置查询）。
- **改法**：扫描循环外一次计算，新增 `updatePlayer(player, effectiveRange)` 重载；单参 `updatePlayer` 保持委托原语义（:1018 玩家进服逐实体路径不变）。
- **等价性**：`this.range` 构造期固定；乘客列表与服务器配置在单次扫描期间不变（updatePlayer 自身不突变二者）。
- **基准**：7.067 → 6.668 ns/op（**1.06×**）。
- **风险**：低。

### 0148 — Mob.isSunBurnTick BlockPos 延迟构造
- **文件**：`net/minecraft/world/entity/Mob.java`
- **热点**：每亡灵生物每 tick 在三道前置门（光照>0.5、随机阈值、非水中/雨中/粉雪）之前无条件 `BlockPos.containing(...)`，仅最后的 `canSeeSky` 使用。
- **改法**：构造移入 `canSeeSky` 调用点（`BlockPos.containing` 无副作用）。
- **等价性**：随机数消耗步数不变（分配本身不消耗随机），跳过分配无可观察差异（基准 main 同种子序列验证）。
- **基准**：232.227 → 172.189 ns/op（**1.35×**）。
- **风险**：零。

### 0149 — EnchantmentHelper.tickEffects 展开 equipment 迭代与访问者 lambda
- **文件**：`net/minecraft/world/item/enchantment/EnchantmentHelper.java`
- **热点**：每生物每 tick `runIterationOnEquipment(entity, (ench,lvl,item) -> ench.value().tick(...))`——捕获 level/entity 的 lambda 一次分配仅为转发。
- **改法**：直接三层循环（VALUES → 物品 → 附魔表），`EnchantedItemInUse` 分配保留原样。
- **等价性**：迭代序（槽枚举序、附魔表 entrySet 序）与 matchingSlot 检查逐行一致，仅去掉 visitor 间接。
- **基准**：39.788 → 40.066 ns/op（0.99×，CI 重叠持平——复刻中 lambda 被内联+EA 消除，补丁价值在降低内联预算）。
- **风险**：零。

### 直接提交（无补丁文件，编号 0150）— CraftEventFactory.callPreCraftEvent 零监听器快路
- **文件**：`paper-server/src/main/java/org/bukkit/craftbukkit/event/CraftEventFactory.java`（直接提交的源码）
- **内容**：`callPreCraftEvent` 零监听器时 `return result.copy();`（每次合成输出槽刷新都走此路径，含配方书批量）。
- **等价性**：零监听器时 `event.getInventory().getResult()` 恒为 result 的镜像，`asNMSCopy`/copy 语义一致；事件有自己的 HANDLER_LIST、构造仅字段赋值。
- **基准**：7.159 → 1.727 ns/op（**4.14×**；复刻经强制逃逸对齐真实 callEvent 发布语义）。
- **风险**：低。

### 暂缓（批次 36 记录）
- **ValidateNearbyPoi 手写 BehaviorControl / Brain.getMemory raw / Behavior.tryStart tickRates 缓存 / ServerEntity 增量合并 / RegionizedPlayerChunkLoader copy / CountingOps 缓存 / InventoryClickEvent 门控**：survey 1 "仔细复核"件，涉跨线程/重载失效/补丁密集区，留后续批次逐项实证。

---


## 批次 37（2026-08-01）：AI 框架层开销 + 配置查找缓存 + 物品解码/点击事件快路（0150-0154 + 直接提交 0155）

survey 1 "仔细复核"7 件中 4 件可证等价落地；Brain.getMemory 框架级改造（泛型穿线复杂、收益仅限"存在的记忆"路径）与 ServerEntity 增量合并（勘察细节不足）、RegionizedPlayerChunkLoader（moonrise 库不可补丁化）记录暂缓。全部 compileJava + 全量 test 通过，applyPatches 干净应用（945 patches）。编号说明：批次 36 直接提交占 0150（文档记法），批次 37 补丁自 0150 起——**0150 双用**（批次 36 直接提交 / 批次 37 补丁），以补丁文件序列为准（同 0114 先例）。

### 0150 — ValidateNearbyPoi 手写 OneShot + Brain 原生记忆读取
- **文件**：`net/minecraft/world/entity/ai/behavior/ValidateNearbyPoi.java` + `net/minecraft/world/entity/ai/Brain.java`
- **热点**：村民每 tick POI 校验走声明式 BehaviorBuilder 链——每次 tryStart（记忆存在时）1 个 MemoryAccessor + 1 个 Optional（getMemoryInternal 的 map）+ 应用闭包层调用。
- **改法**：`create()` 直接返回匿名 `OneShot`（声明式 `BehaviorBuilder.create` 本也返回 OneShot——tryStart==trigger、无进入条件/时长，语义类级一致）；门 = `papoGetMemoryInternalRaw`（Brain 新增：同 getMemoryInternal 但不包装结果 Optional）；`poiPos.erase()` → `brain.eraseMemory`（MemoryAccessor.java:36-37 实证）。
- **等价性**：`instance.present` 门 = 原生读非空；主体逐行保留；`16.0` 字面量与 `MAX_DISTANCE`（int 16 拓宽）同一 double。debugString 文本与声明式链描述不同（仅脑调试转储/崩溃报告，非游戏可观察）——补丁注释载明。
- **基准**：219.693 → 176.197 ns/op（**1.25×**）。
- **风险**：低。

### 0151 — Behavior.tryStart tickRate 按（配置纪元, 世界配置引用）缓存
- **文件**：`net/minecraft/world/entity/ai/behavior/Behavior.java`
- **热点**：每个停止行为每实体每 tick `tickRates.behavior.get(entityType, configKey)`——Guava Table.get 行/列双哈希 + Integer 拆箱（村民 30+ 行为）。
- **改法**：实例字段缓存，双键校验：配置纪元（PaperConfigurations.PAPO_CONFIG_EPOCH，直接提交 0155）+ `level.paperConfig()` 引用；失配才重查。
- **等价性**：`/paper reload` 重建全部世界配置且纪元递增（reloadConfigs 开头，部分失败亦失效）；跨维度传送换世界 → 配置引用变更 → 重查；NMS 内部直改配置表（无 Bukkit API 路径）下次 reload 前不可观察——补丁注释载明。
- **基准**（与 0152 合测）：101.240 → 15.831 ns/op（**6.40×**）。
- **风险**：低。

### 0152 — Sensor.tick tickRate 同法缓存
- **文件**：`net/minecraft/world/entity/ai/sensing/Sensor.java`
- **热点**：每传感器每次触发（scanRate 到期）同一 Table.get。
- **改法/等价性**：同 0151（字段同源注释）。
- **基准**：同 0151 合测（**6.40×**）。
- **风险**：低。

### 0153 — ItemStack 解码深度校验 CountingOps RegistryOps 缓存
- **文件**：`net/minecraft/core/RegistryAccess.java`（ImmutableRegistryAccess 增 `papoCountingOps`/`papoCountingSerializationContext()`）+ `net/minecraft/world/item/ItemStack.java`（validatedStreamCodec.decode）
- **热点**：`validatedStreamCodec` 解码**每个非空入站物品栈**（容器点击/创造选取/任何含物品包）`createSerializationContext(CountingOps.INSTANCE)` = RegistryOps + HolderLookupAdapter + ConcurrentHashMap 三连分配，外加原有 encodeStart 全量校验。
- **改法**：0133 同构——`instanceof ImmutableRegistryAccess` 走 volatile 缓存，否则原路径。
- **等价性**：CountingOps.INSTANCE 不可变无状态（final maxDepth、每操作新建 builder——源码实证）；线程安全论证同 0133（CHM lookups + benign race）。
- **基准**：5.502 → 0.519 ns/op（**10.60×**）。
- **风险**：低。

### 0154 — InventoryClickEvent 零监听器快路
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java` `handleContainerClick`
- **热点**：每次容器点击执行约 200 行点击/动作映射 switch + Bukkit 视图/槽位类型查询 + 事件构造（含 Craft/Smith/Cartography 子类替换条件）+ 派发——零插件服全部白做。
- **改法**：`InventoryClickEvent.getHandlerList().getRegisteredListeners().length == 0` 快路：QUICK_CRAFT 逐字复制 switch 的对应 case（Paper 拖拽修复 + 无条件 clicked——全 switch 仅该 case 有副作用，已全量扫描实证）；其余点击类型 `!cancelled → clicked`（`cancelled == isSpectator`，:3057 唯一定义）+ craft/smith 重同步条件（slotNum==0 && CraftingInventory && recipe!=null / slotNum==3 && SmithingInventory && result!=null ⇔ 原 `event instanceof CraftItemEvent/SmithItemEvent`）；有监听器走原路径逐字节保留。
- **等价性**：CraftItemEvent/SmithItemEvent/CartographyItemEvent 均未声明自己的 getHandlerList（paper-api 实证）⇒ 四类共享 InventoryClickEvent 单一 HandlerList；无监听器 ⇒ 菜单不可变更（:3349 检查可跳）、`getResult()` 仅由 setCancelled(cancelled) 决定。
- **基准**：2.300 → 0.598 ns/op（**3.85×**；复刻仅含事件对象层，真实路径含整个 switch，收益更高）。
- **风险**：低。

### 直接提交（无补丁文件，编号 0155）— PaperConfigurations 配置纪元计数器
- **文件**：`paper-server/src/main/java/io/papermc/paper/configuration/PaperConfigurations.java`（直接提交的源码）
- **内容**：`public static volatile long PAPO_CONFIG_EPOCH`，`reloadConfigs` 开头递增（部分失败亦失效，保守安全）；读写在主线程，volatile 保证可见性。
- **风险**：零。

### 暂缓（批次 37 记录）
- **Brain.getMemory 框架级原生读**：声明式框架 MemoryAccessor 全链 Optional 泛型穿线，收益仅限"存在的记忆"路径（空记忆 map 不分配），性价比低，暂缓。0150 已在手写路径带出其原生读。
- **ServerEntity 增量合并（net#2）**：勘察细节不足，现有代码已经 Paper 多轮优化，待重勘察。
- **RegionizedPlayerChunkLoader copy（net#5）**：moonrise 库类，不在补丁源树内，不可补丁化。

---

## 批次 38（2026-08-01）：网络帧长内联解析 + 命令谓词缓存 + 地图装饰稳态分配消除（0155-0158）

### 0155 Varint21FrameDecoder 内联 varint 解析
- **改动**：删除每解码器 helperBuf（direct 3 字节）与 copyVarint 抄写 + VarInt.read 二次解析，读取同时内联累积 `i |= (b & 127) << n*7`；handlerRemoved0 覆写随 helperBuf 移除（Netty final handlerRemoved 自释 cumulation）。
- **等价论证**：1-3 字节序列累积逐位一致（0097 剥离分支等价性注释推导）；读者索引结局逐路径相同（前缀不全/负载不足 → reset；超宽/零长 → 消耗前缀后抛异常不 reset）；`monitor.onReceive(i + VarInt.getByteSize(i))` 逐字保留；基准 7+1 输入矩阵比对解析值/消耗字节/异常类型消息/读者索引。
- **收益**：JMH 1.05×（1 字节前缀）/1.04×（3 字节前缀），CI 重叠小幅正向——复刻以堆数组模拟 helper，未计入真实 direct ByteBuf 带界写读与 clear 开销；survey 定级"低价值"与实测一致。

### 0156 EntitySelector.getPredicate 上下文无关路径组合谓词缓存
- **改动**：i==0（无 features/box/range）分支惰性缓存 `Util.allOf(contextFreePredicates)` 结果，不再每次调用分配捕获 lambda（size≥2 时）。
- **等价论证**：contextFreePredicates 为解析器 `List.copyOf` 不可变快照；allOf 纯函数；谓词无状态可重入；仅 i==0 分支缓存，其余分支逐字保留。
- **收益**：JMH 1.15×（CI 不重叠）；循环命令方块 `@e[type=...]` 每次执行省 1 分配。

### 0157 MapItemSavedData.addDecoration 稳态比较优先
- **改动**：目标字段（type/x/y/rot）先算入局部量，与现存装饰逐字段相等即返回（稳态 0 分配）；仅变化时构造 MapDecoration 并 put+标脏+计数。位置记录/Pair 辅助方法与 MapDecorationLocation record 随之移除。
- **等价论证**：字段计算逐公式复用原辅助方法（clamp/inside/rotation/offMap 原样）；比较镜像 record equals（新值为接收者；Holder 未覆写 equals）；跳过相等 put 不改变 LinkedHashMap 迭代序，消费方全按值读取；计数与标脏条件逐分支对齐（含 Paper 仅真实移除才标脏）。
- **收益**：JMH 复刻内持平（EA 将 before 三分配标量替换，gc.alloc.norm≈0，复刻无法测分配差）；真实深调用栈稳态每携带者每 tick 省 3 分配，机制性收益，按 0140 先例保留。

### 0158 tickCarriedBy 地图匹配谓词内联
- **改动**：mapMatcher lambda 工厂改为静态助手 papoInventoryHasMap 内联逐项测试直接扫描；两调用点共用。
- **等价论证**：逐项测试逐字内联（引用相等短路→is→MAP_ID equals 顺序一致）；同一 Inventory 迭代器同序；MAP_ID null 语义不变。
- **收益**：JMH 1.69×（CI 不重叠）。

### 暂缓（批次 38 记录）
- **ServerEntity 增量合并（net#2）**：重勘察完成并结案——主路径已经 Paper 多轮优化；配对路径分配为协议负载必需；getNonDefaultValues 惰性化会改变配对包快照时点（协议可观察），不满足可证等价。不再重勘。

---

## 批次 39（2026-08-01）：信标专题——扫描位置复用 + AABB 折叠 + 效果事件快路（0159-0162）

### 0159 updateBase 基座扫描 scratch pos
- **改动**：`new BlockPos(i3,i2,i4)`（每 80 tick 每信标至多 164 次）→ 方法级 scratch MutableBlockPos.set。
- **等价论证**：纯 getBlockState 坐标读取无逃逸；循环边界/早退/计数逐字保留。
- **收益**：JMH 复刻内持平（EA 将 before 164 分配标量替换，gc.alloc.norm≈10⁻⁴ B/op 实证）；真实深调用栈分配真实发生，机制性收益保留（0140/0157 先例）。

### 0160 光柱扫描 pos 提升为方块实体字段
- **改动**：tick() 每调用 `new MutableBlockPos()` → 实体字段跨 tick 复用（此前 Papo 补丁仅循环内复用）。
- **等价论证**：用途限坐标读取与 getY/setY；主线程无重入。
- **收益**：JMH 复刻 0.62× 为 EA 假象（before 被 EA 化为寄存器，after 走真实堆字段——复刻反转真实条件，gc.alloc.norm≈10⁻⁵ 实证）；与 0135 字段 scratch 同模式，无语义差异，保留。

### 0161 信标范围 AABB 三次分配折叠为一次
- **改动**：`new AABB(pos).inflate(d).expandTowards(0,h,0)` → 单构造六坐标直给。
- **等价论证**：链展开 min=(x-d,y-d,z-d)、max=(x+1+d,(y+1+d)+h,z+1+d)；浮点结合序逐位一致；构造器归一化输入相同。
- **收益**：JMH 1.57×（CI 不重叠）。

### 0162 BeaconEffectEvent 零监听器快路
- **改动**：零监听器时跳过共享 toBukkit+CraftBlock 与每玩家事件构造/fromBukkit 往返，直接每玩家 copy 构造施加（duration 就地可变故共享实例不外发）。
- **等价论证**：独立 HandlerList 零监听器恒不取消、getEffect 恒为构造值；fromBukkit(toBukkit) 与 copy 构造字段相等（holder 双向映射恒等、hidden null、amplifier 0/1 过 clamp 恒等）；每玩家新实例一一对应，addEffect 下游一致。
- **收益**：JMH 1.42×（CI 不重叠；基准覆盖式存储修正后复测）。

### 暂缓（批次 39 记录）
- **酿造台**：getPotionBits/lastPotionCount 已有 Papo scratch；isBrewable 为 map 查找无分配；余者稀有路径，无候选。
- **AttributeMap 属性查找缓存**：失效链（属性增删/修饰符变化/装备切换）复杂，未深入，待评估。**→ 批次 40 已评估结案（实例级 dirty/cachedValue 缓存已存在，map 级缓存价值不足且 registerAttribute 旁路失效链不可闭合，不做）。**

---

## 批次 40（2026-08-01）：潮涌核心 + 刷怪笼——扫描位置复用 + AABB 折叠×2 + 刷怪事件快路（0163-0165）

### 0163 潮涌核心 updateShape 框架扫描 scratch pos
- **改动**：内层 3×3×3 水判定 27 次 `pos.offset` 分配 → 方法级 scratch MutableBlockPos；外层框架扫描改为 scratch 坐标检查、仅命中框架方块时 `new BlockPos(scratch)` 入表（每 40 tick 每潮涌核心）。
- **等价论证**：scratch 仅作 isWaterAt/getBlockState 坐标读取不逃逸（0159/0160 同模式）；入表实例为坐标拷贝新对象，BlockState.is(block) 至多命中 4 个 VALID_BLOCKS 之一，无双入表分歧；effectBlocks 清空重建语义与消费方（仅读 size）不变。
- **收益**：JMH 复刻内持平（gc.alloc.norm 两路径同为 2016.005 B/op——loop1 的 27 次分配被 EA 全抹，完整框架场景两路径逃逸分配同数）；真实深调用栈分配真实发生，机制性收益保留（0140/0157/0159 先例）。

### 0164 潮涌核心两处 AABB 折叠
- **改动**：applyEffects 效果范围 `new AABB(x,y,z,x+1,y+1,z+1).inflate(i).expandTowards(0,h,0)` 与 getDestroyRangeAABB `new AABB(pos).inflate(8)` 各折叠为单构造器六坐标直给。
- **等价论证**：x/y/z/i/h 全为 int，原链每步为精确整数 double 运算（各中间值 ≪ 2^53），折叠式 int 求和后一次拓宽，doubleToRawLongBits 逐位相等；构造器归一化输入相同（min < max 恒成立）。
- **收益**：JMH 1.61×（applyEffects）/ 1.57×（destroyRange），CI 均不重叠。

### 0165 刷怪笼 PreSpawnerSpawnEvent 零监听器快路
- **改动**：每次刷怪尝试每实体，零监听器时跳过 2 个 CraftLocation.toBukkit + minecraftToBukkit + 事件构造 + callEvent 空派发。
- **等价论证**：PreSpawnerSpawnEvent 无独立 HandlerList，共享 PreCreatureSpawnEvent 静态表（检查与派发同表）；零监听器时 callEvent 恒 true 且 cancelled/shouldAbortSpawn 不变，事件构造为纯分配无副作用，整体跳过等价；flag 仅在 !callEvent 分支置位，快路不触及；有监听器路径逐字节保留。
- **收益**：JMH 54.6×（CI 不重叠；事件路径完全消除，after 仅剩监听器数检查）。

### 暂缓/结案（批次 40 记录）
- **AttributeMap 属性查找缓存**：**评估结案，不做**——实例级已有 dirty/cachedValue 惰性缓存（AttributeInstance.java:26-27,138-145），getValue() 重算后 O(1)；map 级缓存仅省一次哈希查找（~10-20ns），且 Paper 的 registerAttribute 直写绕过 onDirty 回调，失效链不可闭合（陈旧值风险），价值不足。
- **战利品表**：getRandomItems 的 LootContext 构造为语义必需负载，无可证等价快路，无候选。

---

## 批次 41（2026-08-01）：每 tick 分配链折叠——漏斗吸取缓存 + 弹射物/活塞扫描盒（0166-0168）

### 0166 漏斗吸取路径缓存（BE suck pos + 矿车键控三缓存）
- **改动**：suckInItems 的 `BlockPos.containing(getLevelX(), getLevelY()+1.0, getLevelZ())`——方块漏斗改为每 BE 一次缓存 worldPosition.above()；矿车漏斗改为位置键控缓存（同键覆盖 getItemsAtAndAbove 的 suck AABB.move）；矿车后备拾取 `bb.inflate(0.25,0,0.25)` 改为 bb 引用键控缓存。
- **等价论证**：BE 侧 containing(x+0.5,(y+0.5)+1.0,z+0.5)=(x,y+1,z)=above() 逐位等价，worldPosition final 自然失效；矿车侧 containing/move 为坐标 double 纯函数（键一致值一致，NaN 键恒重算仍正确）；拾取 inflate 为 bb 值纯函数、AABB 不可变、bb 引用替换自然失效；第三方 Hopper 实现保持原路径。
- **收益**：JMH 3.79×（CI 不重叠；静止矿车场景每 tick 4 处构造→键控命中）。

### 0167 弹射物命中扫描 expandTowards+inflate 折叠 2→1
- **改动**：`getBoundingBox().expandTowards(deltaMovement).inflate(1.0)` → 单构造六坐标直给（min'=(min+(dm<0?dm:0))-1、max'=(max+(dm>0?dm:0))+1）。
- **等价论证**：三元式与原版 <0/>0 分支对 NaN deltaMovement 同构（比较全 false→0.0 与原版不动该轴一致）；-0.0 边缘被后续 ±1.0 抹除；左结合保持 FP 结合序；构造器归一化输入相同。
- **收益**：JMH 1.25×（CI 不重叠；每弹射物每 tick 省 1 个中间 AABB）。

### 0168 活塞碰撞实体扫描盒折叠 3→1
- **改动**：moveCollidedEntities 的 `getMovementArea(moveByPositionAndProgress(...), dir, d).minmax(同移动盒)` 链 → papoMovementUnionBox 单构造（运动轴 lo=min(m.edge+min(d',0), m.min)、hi=max(m.edge+max(d',0), m.max)，负向 edge=min、正向 edge=max，其余轴直给）。
- **等价论证**：六向分支逐字展开，左结合加法与 Math.min/max 操作数序逐一对齐；并集恒已归一化（构造器归一化为恒等）；d/progress/bounds 均有限无 NaN 分歧。
- **收益**：JMH 1.46×（CI 不重叠；每运动活塞每动画 tick 省 2 个中间 AABB）。

### 暂缓（批次 41 记录）
- **ItemEntity 合并 AABB**：已是单 inflate 分配；radius≤0 早退改变 radius=0 时重叠物品合并行为，不满足可证等价，不做。
- **活塞内层 per-entity×shape 双分配**：仅实体在场触发，频次低、改写面大，本批次不覆盖。

---

## 批次 42（2026-08-01）：弹射物专题——候选循环零膨胀跳过 + 谓词缓存 + 扫描盒折叠（0169-0171）

### 0169 弹射物候选循环 inflate(0) 跳过两处
- **改动**：getEntityHitResult 两处候选循环——`bb.inflate(getPickRadius())`（每候选）与 `bb.inflate(margin)`——半径为零时直接用原 bb。
- **等价论证**：inflate(0) 对实体包围盒值等价（min-0.0==min 含 -0.0；makeBoundingBox maxes 为 pos+非负半宽不可能 -0.0，max+0.0==max）；下游 clip/contains 纯读；半径非零路径逐字节保留。
- **收益**：JMH 6.47×（pickRadius 站点，8 候选全零半径主流情形）/ 8.78×（margin=0 站点，年轻弹射物），CI 均不重叠。

### 0170 canHitEntity 谓词实例提升为 Projectile 字段
- **改动**：8 处每 tick `this::canHitEntity` 捕获 lambda 分配 → 构造期一次缓存 protected final 字段（FireworkRocket×2/FishingHook/AbstractHurtingProjectile/LlamaSpit/ShulkerBullet/ThrowableProjectile/AbstractArrow×2）。
- **等价论证**：方法引用指向虚方法，调用时虚分派——共享单例与每次新建分派语义完全一致（自检验证子类覆盖触达）；非实例字段快照；消费方不比较谓词身份；Pufferfish 缓存 lambda 同模式。
- **收益**：JMH 1.20×（CI 不重叠）。

### 0171 AbstractArrow 命中扫描盒折叠 2→1
- **改动**：findHitEntity/findHitEntities 的 `getBoundingBox().expandTowards(getDeltaMovement()).inflate(1.0)` → papoExpandedScanBox 单构造。
- **等价论证**：与 0167 同一表达式同一变换（三元式保 NaN、-0.0 抹除、左结合 FP 序、归一化输入相同）。
- **收益**：共用 0167 基准（1.25×，CI 不重叠）。

### 暂缓（批次 42 记录）
- **Vault 服务端 tick**：展示轮换为战利品表解析负载（语义必需），无候选。
- **重复命令方块 Brigadier 解析缓存**：解析依赖发送方权限（requirement 于解析期求值），键不可证等价，不做。

---

## 批次 43（2026-08-01）：弹射物专题续——钓鱼判定重写 + 烟花/火球 Vec3 消除（0172-0174）

### 0172 钓鱼浮标开阔水域判定命令式重写
- **改动**：calculateOpenWater 4 区域流式折叠（每区域 `pos.offset×2` + `BlockPos.betweenClosedStream` + `map(this::getOpenWaterTypeForBlock)` + reduce，每判定约 500 次不可变 BlockPos 分配）→ 单 scratch MutableBlockPos + 三重循环命令式；外层 8 次 offset 折入循环边界；遇异即早退。
- **等价论证**：offset 折入边界 int 加法逐位一致；访问顺序与 betweenClosedStream 行主序（x/y/z）一致；组合子 INVALID 吸收且序无关 ⇒ 早退保值；scratch 即设即读不逃逸（BlockCollisions.java:93 Cursor3D 先例）。六场景自检 ALL OK。
- **收益**：JMH **3.72×**（157.5→42.4 ns/op，CI 不重叠）。

### 0173 烟花火箭 tick 两分支 Vec3 中间量消除
- **改动**：Elytra 助推分支 `setDeltaMovement(dm.add(expr×3))` → 逐分量 `setDeltaMovement(dm.x+expr, …)`；自由飞行分支 `dm.multiply(d2,1.0,d2).add(0,0.04,0)`（两中间 Vec3）→ 逐分量内联（`+0.0` 项逐字保留）。
- **等价论证**：setDeltaMovement(Vec3) 纯委托分量读取；每分量 FP 链逐字相同（x*1.0=x 保符号位；+0.0 项原链同样执行）。30 组合矩阵逐位自检 ALL OK。
- **收益**：复刻内中性（CI 重叠）——`-prof gc` 证明两路径 alloc.norm≈10⁻⁵ B/op（EA 伪影），按 0140/0157/0159/0163 先例机制保留。

### 0174 火球 tick Vec3 消除（applyInertia + MISS setPos）
- **改动**：applyInertia `dm.add(dm.normalize().scale(power)).scale(inertia)` 四中间 Vec3 → 逐分量内联（保留 `squareRoot < 1.0E-5F` 阈值分支）；MISS 路径 `position().add(getDeltaMovement())` → 逐分量 setPos。
- **等价论证**：阈值路径 ZERO.scale(power) 每分量 = 0.0*power 与内联逐位相同；rotateTowardsMovement 只改朝向不移动实体 ⇒ getX/Y/Z==position() 捕获值；setPos(Vec3) 与三参同赋值。阈值边界（1e-6/9.9e-6/1.2e-5/1e-4）+ -0.0 + power×inertia 矩阵自检 ALL OK。
- **收益**：复刻内中性（CI 重叠，gc 证明 EA 伪影），同上先例机制保留。

### 暂缓（批次 43 记录）
- **ShulkerBullet 寻的 tick**：目标检索窄场景（仅潜影贝导弹且有目标），收益覆盖面小，记录不实施。
- **ServerLevel.tickChunk**：主路径已经 Paper 优化，无候选。

---

## 批次 44（2026-08-01）：移动/战斗 Vec3 专题——输入向量 + 击退 + 格挡角内联（0175-0178）

### 0175 Entity.getInputVector 内联
- **改动**：`(d>1.0 ? relative.normalize() : relative).scale(motionScaler)`（每调用 1-2 中间 Vec3）→ 逐分量内联；签名/返回值语义不变（protected static，模组可调）。
- **等价论证**：lengthSqr 与 normalize 求和表达式逐字相同 ⇒ Math.sqrt(d) 逐位一致；d>1.0 ⇒ len>1.0 阈值分支不可达；(x/len)*scaler 与 normalize().scale() 链逐字；d<1e-7→ZERO 原样。220 组合矩阵逐位自检 ALL OK。
- **收益**：复刻内中性（CI 重叠）；`-prof gc` 两路径 alloc.norm 均 40B（中间量被 EA 抹除）。真实服务端 C2 热路径预期同样抹除——**收益不确定度如实载明**；保留依据：零风险逐位等价 + C1/冷路径/EA 受限场景减压（0140 等先例）。

### 0176 LivingEntity.knockback 内联
- **改动**：`new Vec3(x,0,z).normalize().scale(strength)` 三中间 Vec3 → papoKbX/papoKbZ 两分量（vec3.y 从不被读）；while 循环 RNG 序列不动。
- **等价论证**：while 保证 x²+z²≥1.0E-5F ⇒ len≥√(1e-5)>1e-5 阈值分支不可达；x²+0.0*0.0+z²==x²+z²；分量链逐字。贴阈值/单轴/-0.0/Inf 溢出/勾股 × strength×onGround 矩阵（含 finalVelocity+diff）逐位 ALL OK。
- **收益**：复刻内中性（gc：两路径均 104B=结果对象），保留依据同 0175。

### 0177 格挡视角向量内联×2（applyItemBlocking / resolveBlockedDamage）
- **改动**：`sourcePosition.subtract(position())` → 水平化 → normalize → dot 链（4 中间 Vec3）→ sx/sz 直取 + 阈值保留 + 两分量点积内联。两处同一变换。
- **等价论证**：normalize 阈值分支保留；被丢弃 y 项 0.0*view.y=±0.0 加性恒等，唯一分歧（t1=t3=-0.0∧t2=+0.0 时和之零号翻转）被 Math.acos 抹除（acos(±0.0)=π/2）。200 组合 acos 输出逐位 ALL OK。
- **收益**：JMH **1.09×**（CI 不重叠）；每次格挡省 4 分配。

### 0178 批次 43 注释勘正（零行为变更）
- **改动**：FireworkRocketEntity×2 + AbstractHurtingProjectile×1 注释就正——setDeltaMovement(Vec3) 为 isFinite 守卫赋值（含 Paper posLock 同步）非"纯委托"；中间量计数修正（0174a 四→三，最终 scale 结果与三参 new Vec3 一对一替换）。

### 勘误（批次 43 记录修正）
- **0173a 分配计数**：before/after 均分配 1 个 Vec3（add 结果 vs 三参 new Vec3），**净消除 0 次**，该分支为装饰性简化；批次 43"机制保留"论证仅对 0173b(2→1)/0174a(4→1)/0174b(2→0) 成立。语义逐位等价无回退必要，计数如实更正。
- **"纯委托"误述**：批次 43 报告/发布说明中相关表述系误述（等价论证不依赖之），已由 0178 就正。

### 暂缓（批次 44 记录）
- **LivingEntity.aiStep travel Vec3（3717 行）**：传入可覆写 travel(Vec3)，改签名破 API，红线否决。
- **Sensing.tick / 振动系统 / Explosion / ExperienceOrb**：已极简、窄场景或无热分配候选。

---

## 批次 45（2026-08-01）：实体块内检测路径——AABB 折叠 + 位移差内联（0179-0180；0181 撤销）

### 0179 checkInsideBlocks AABB 折叠
- **改动**：`makeBoundingBox(to).deflate(1.0E-5F)` 两构造 → 单构造（每实体每 tick 省 1 AABB）。
- **等价论证**：deflate(v)==inflate(-v)（mins-(-v)/maxes+(-v)），`a-(-b)`≡`a+b` 逐位；六分量左结合 FP 链逐字；构造器归一化同输入（含极小宽度 min>max 交换）。25 组合逐位 ALL OK。
- **收益**：JMH **1.61×**（5.93→3.67 ns/op，CI 不重叠）。

### 0180 块内检测位移差 Vec3 内联
- **改动**：`movement.to().subtract(from)` → 三分量直取（lengthSqr/轴分量读取逐字）。
- **等价论证**：subtract 逐分量、lengthSqr 左结合表达式、get(axis) 分量选择逐字内联。24 组合（含 NaN/±0.0/巨值）逐位 ALL OK。
- **收益**：复刻内中性（CI 重叠，EA 伪影同批次43/44 判例），机制保留。

### ~~0181 静止回退 Movement 键控缓存~~ **已撤销（实测回退）**
- 初测 **0.46×**（缓存命中 6×Double.compare 贵于 2 次 TLAB 分配）；一轮修正（两段式精确比较，命中⟺逐位相同）复测仍 **0.91×**。按批次29先例撤销：内部提交回退、外仓补丁移除、验证链全绿；基准类留存 benchmark/src 作证据。

### 暂缓（批次 45 记录）
- **checkInsideBlocks AtomicInteger 持有者**：BlockGetter 静态接口仅回传 boolean，复制遍历循环得不偿失，否决。
- **getOnPos 系列公开 API**：调用方可能持有返回 BlockPos，scratch 缓存不可证安全，否决。

---

## 批次 46（2026-08-01）：事件门控规模化续批 + 网络/会话/世界 tick 分配消除（0181-0189 + 直接提交）

三个 survey 子代理（方块实体与世界 tick / 网络编码与同步 / 实体 tick 与 AI）共产出 19 个候选，逐个代码复核（事件 HandlerList 层级与子类、CraftItemStack round-trip 引理、会话上下文身份可观察点全库实证）后实现 9 个补丁 + 1 个直接提交；scratch-list 机制类 3 项（pushEntities/Mob looting/NearestAttackableTargetGoal，需 Level fill 重载）整体移入批次 47。全部 compileJava + 全量 test 通过，applyPatches 干净应用（915 patches）。JMH 实测（note/report/perf/2026-08-01-jmh-microbench-batch46.md）：**8 项正收益（1.17×–18.13×，CI 均不重叠）、2 项复刻内中性机制保留（0186/0189，EA 伪影判例）、1 项撤除（0187 记分板 Optional——复核 JDK 实现证伪的真零收益纯搅动，新判例）**。编号说明：批次 45 撤销的 0181 编号空出，本批 GameEventDispatcher 补丁占用 0181，后续顺延——以补丁文件序列为准（0114/0150 先例）。

### 0181 — GameEventDispatcher.post BlockPos 消除 + debug 订阅者门控
- **文件**：`net/minecraft/world/level/gameevent/GameEventDispatcher.java`
- **热点**：全服每次 `ServerLevel.gameEvent(...)` 派发必经（实体踏步、容器开合、方块变化、弹射物等），繁忙服每秒数百至数千次。每次 post 分配 `BlockPos.containing(pos)` 仅为 6 个 section 坐标读取；debug 分支 flag 为真（有 sculk 类监听器被访问）时无条件再分配 BlockPos + DebugGameEventInfo，而被调方 `broadcastEventToTracking` 首行即 `hasAnySubscriberFor` 早退（LevelDebugSynchronizers.java:205-209 实证）——无调试订阅者（生产常态）时两对象构造即丢弃。
- **改法**：三个 int `Mth.floor` 替代 blockPos（`BlockPos.containing(pos).getX()` 定义为 `Mth.floor(pos.x)`，BlockPos.java:97-99 实证，逐位一致）；GenericGameEvent 门控分支内用到 CraftLocation 处才 `BlockPos.containing(pos)`（零监听器不执行）；debug 分支加 `hasAnySubscriberFor(DebugSubscriptions.GAME_EVENTS)` 门控。
- **基准**：GameEventPostBench 8.716 → 3.839 ns/op（**2.27×**，CI 不重叠）
- **风险**：低。

### 0182 — 熔炉三事件零监听器门控（FurnaceBurn / FurnaceStartSmelt / FurnaceSmelt）
- **文件**：`net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java`
- **热点**：每次燃料消耗（燃煤 1600 tick/台）、每个烧炼周期开始、每烧出一个物品，大型熔炉阵列下每秒数十至上百次；每次构造 CraftItemStack 镜像 + CraftBlock + 事件 + 空派发，Smelt 另有 asBukkitCopy/asNMSCopy 往返 + toBukkitRecipe 转换链。
- **改法/等价性**：
  - FurnaceBurnEvent：自有 HandlerList、无子类、构造器原样存 burnTime（钳制只在 setBurnTime）、burning/consumeFuel 默认 true——零监听器时 callEvent 恒 true、各 getter 返回默认值，门控路径逐语句复刻默认流。
  - FurnaceStartSmeltEvent：**无自有 HandlerList**，监听器注册进 `InventoryBlockStartEvent` 表（与 BrewingStartEvent/CampfireStartEvent 共享，空表 ⟺ 无人能观察）；非 Cancellable，getTotalCookTime 默认返回构造参数。
  - FurnaceSmeltEvent：**无自有 HandlerList**，是 BlockCookEvent 唯一子类（paper-api 全库 grep 实证），BlockCookEvent 表权威。round-trip 引理（CraftItemStack.java:106-113,133-138,439-454）：`asBukkitCopy(x)=asCraftMirror(x.copy())`、`asNMSCopy(craft)=craft.handle.copy()`、`isSimilar==ItemStack.isSameItemSameComponents`（双 handle 非空）——零监听器时往返产出内容一致新副本（此处即 assemble() 独占产物本身），isSimilar 检查精确等于 NMS 比较。
- **基准**：FurnaceEventGateBench burn 7.200→1.206（**5.97×**）、startSmelt 10.445→0.576（**18.13×**）、smelt 18.887→5.817（**3.25×**），CI 均不重叠
- **风险**：低。

### 0183 — 营火 BlockCookEvent 零监听器门控
- **文件**：`net/minecraft/world/level/block/entity/CampfireBlockEntity.java`
- **热点**：每烤熟物品一次（600 tick/物品×4 槽，农场常用）。
- **改法**：BlockCookEvent 表门控；门控路径 `itemStack1 = itemStack1.copy()` 单次拷贝——保证 split 掉落循环不就地改容器槽（无配方时 itemStack1 别名 `items.get(i)` 槽位栈），仍比未门控路径（asBukkitCopy+asNMSCopy 两次拷贝）省一次。EMPTY.copy()==EMPTY 保持空栈路径一致。
- **基准**：CampfireCookGateBench 42.579→24.648（**1.73×**，CI 不重叠）
- **风险**：低。

### 0184 — VaultDisplayItemEvent 零监听器门控
- **文件**：`net/minecraft/world/level/block/entity/vault/VaultBlockEntity.java`
- **热点**：每 ACTIVE 宝库每 20 tick 一次展示轮换（战利品 roll 为原版行为不跳过，门控只省 CraftBlock + asBukkitCopy + 事件 + asNMSCopy）。
- **等价性**：自有 HandlerList、无子类、Cancellable 默认 false、getDisplayItem 默认返回构造参数；零监听器时 round-trip 为内容一致新副本，而 roll 产出栈本身独占（无其他持有者），直传等价。
- **基准**：MiscEventGateBench(vault) 8.729→0.575（**15.18×**，CI 不重叠）
- **风险**：低。

### 0185 — PlayerArmSwingEvent 零监听器门控（handleAnimate）
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java`
- **热点**：ServerboundSwingPacket 是 PvP/挖掘场景最高频包之一（每次左/右击一挥），每包构造事件 + callEvent。
- **等价性**：PlayerArmSwingEvent **无自有 HandlerList**，是 PlayerAnimationEvent 唯一子类（paper-api 全库 grep 实证），其监听器全在 PlayerAnimationEvent 表；零监听器时 callEvent 无操作、isCancelled 恒 false，控制流必达 `swing()`。
- **基准**：MiscEventGateBench(swing) 4.326→0.684（**6.32×**，CI 不重叠）
- **风险**：低。

### 0186 — ItemStack 编码走 ItemObfuscationSession.withItemStack 直路
- **文件**：`net/minecraft/world/item/ItemStack.java`（createOptionalStreamCodec encode）
- **热点**：每个非空 ItemStack 每次编码（容器槽同步、装备包、实体数据 ITEM_STACK，且同包对 N 个接收连接各编码一次）`withContext(c -> c.itemStack(value))` 捕获 lambda 一次分配。
- **改法**：会话类新增 `withItemStack(ItemStack)` 直路（直接提交部分），调用点换用。
- **等价性**：`context.itemStack(x)` 恒构造新 ObfuscationContext（checkState 不变量逐字一致）；isObfuscating 早退、switchContext、返回新 context 序列与 withContext 完全相同。
- **基准**：ObfuscationSessionBench withItemStack 5.514→5.639（0.98× 复刻内中性——热循环捕获 lambda 被 EA 消除；默认非混淆配置下调用点仍分配 lambda，机制保留，0140 先例）
- **风险**：低。

### 直接提交（无补丁文件）— ItemObfuscationSession withItemStack + start 上下文按级缓存
- **文件**：`paper-server/src/main/java/io/papermc/paper/util/sanitizer/ItemObfuscationSession.java`
- **内容**：withItemStack 直路（见 0186）；`start(level)` 的 `new ObfuscationContext(session, null, null, level)` 改为按 ObfuscationLevel 预建缓存（两个调用点 ClientboundSetEntityDataPacket.pack/ClientboundSetEquipmentPacket.write 每包每连接各省 1 分配）。
- **等价性**：缓存上下文字段逐项一致；上下文身份全库无可观察点（withContext 的 `newContext != context` 检查恒成立，wither 恒构造新实例；close 恢复序列不变）。
- **基准**：ObfuscationSessionBench start 4.872→3.770（**1.29×**，CI 不重叠）
- **风险**：低。

### 0187 — 包构造微优化：属性快照预尺寸（记分板 Optional 部分已撤除）
- **文件**：`ClientboundUpdateAttributesPacket.java`（~~ServerScoreboard.java~~ 已撤）
- **改法**：属性快照列表 `Lists.newArrayListWithExpectedSize(attributes.size())`（容量不经 List API 可观察，多属性同步免扩容拷贝）。
- **撤除记录**：原方案含 `Optional.ofNullable(score.display()/numberFormat())` → 三目 empty 单例；复核 JDK 实现证伪——`ofNullable(null)` 本就返回 empty() 单例，null 分支两写法均零分配（非 EA 伪影，是真零收益纯搅动），JMH 1.791 vs 1.728 CI 重叠证实中性，按"实测无收益即撤"精神从补丁撤除（手工编辑补丁移除 ServerScoreboard hunk → 完整 applyPatches 同步源码树验证全绿）。**新判例**：survey 候选的"分配"断言需对照 JDK 实现复核，不能凭方法名望文生义。
- **基准**：PacketConstructionBench attributes 85.889→35.461（**2.42×**，CI 不重叠）；scoreboard 1.791→1.728（1.04× 中性，撤除证据留存）
- **风险**：零。

### 0188 — LivingEntity.baseTick 气泡柱检查复用 scratch pos
- **文件**：`net/minecraft/world/entity/LivingEntity.java`
- **热点**：水下存活实体（鱼、鱿鱼、溺尸、守卫者、潜水玩家）每 tick `BlockPos.containing(getX(), getEyeY(), getZ())` 分配仅为一次只读 `getBlockState(...).is(BUBBLE_COLUMN)`。
- **改法**：per-entity 惰性 `MutableBlockPos` scratch（set-and-read）。
- **等价性**：containing 定义为 3×Mth.floor（BlockPos.java:97-99）；getBlockState 只读坐标不保留引用；tick 线程单线程；scratch 不逃逸。
- **基准**：BubbleColumnPosBench 2.990→2.566（**1.17×**，CI 不重叠）
- **风险**：零。

### 0189 — EntityEffectTickEvent 零监听器门控
- **文件**：`net/minecraft/world/effect/MobEffectInstance.java` `tickServer`
- **热点**：每个激活药水效果每个应用 tick（中毒 25t/凋零 40t/生命恢复 50t/信标 buff 80t 覆盖全体玩家）构造事件 + `CraftPotionEffectType.minecraftHolderToBukkit` 转换。
- **等价性**：自有 HandlerList、无子类、Cancellable 默认 false——零监听器时 callEvent 恒 true，控制流必达 applyEffectTick（0100 EntityJumpEvent 同款模式）。
- **基准**：MiscEventGateBench(effectTick) 0.537→0.532（1.01× 复刻内中性——浅栈事件对象被 EA 标量替换；真实路径 callEvent 发布语义+注册表转换成本未被复刻覆盖，机制保留，0140/0157 先例）
- **风险**：低。

### 暂缓（批次 46 记录）
- ~~LivingEntity.pushEntities scratch list / Mob.aiStep looting scratch list / NearestAttackableTargetGoal.findTarget scratch list~~（survey 3 #1/#4/#6）：**批次 47 已落地（0190-0192）**。
- **MoveToBlockGoal.tick 每 tick above() identity 缓存**（survey 3 #5）：findNearestBlock 对 blockPos 赋局部 MutableBlockPos 的原地 mutate 面需全量子类审计，暂缓。
- **SWAP_ITEM_WITH_OFFHAND 双镜像+双 clone 门控**（survey 2 #7，中低价值）：成立，留后续批次顺手带走。
- **handleInteract 实体交互双事件门控**（survey 2 #6，中风险）：需改 CraftBukkit 匿名 Handler 结构、两事件独立表分别检查，留后续批次单独补丁。
- **ItemObfuscationSession.start 全路径勘察**：survey 2 #3 已并入本批直提完成。
- **handleMoveVehicle/handleMovePlayer 双 currentTimeMillis 合并**：改变 50ms 桶边界反作弊配额值，协议可观察时点变化，**否决**。
- **SynchedEntityData.packDirty 预尺寸 / DataValue.write 序列化 id 缓存 / Connection 限流 containsKey / PacketEncoder attr 读**：survey 2 评估为负收益或噪声级，**否决**（明细见 survey 记录）。
- **LivingEntity.travel 系列 Vec3 / AbstractHorse.getRiddenInput**：travel(Vec3) 公开签名红线（0175 先例），**否决**。
- **FollowParentGoal/AvoidEntityGoal/LookAtPlayerGoal 降频**：任何降频改语义，**否决**。

---

## 批次 47（2026-08-01）：实体查询 scratch list 机制（0190-0192）

批次 46 survey 移入的 3 项 scratch-list 机制类候选落地。前置：Level 新增 entity-excluding fill 重载 `papoGetEntitiesInto`（类基 fill 重载 `getEntities(EntityTypeTest, AABB, Predicate, List)` 上游已存在，0191/0192 直接复用）+ 三站点重入论证。全部 compileJava + 全量 test 通过（零 FAILED），applyPatches 干净应用（sources 915 + features 192 + resources 6）。JMH 实测（note/report/perf/2026-08-01-jmh-microbench-batch47.md）：**真实规模（盒内 17-20 实体）3 项正收益 1.08×-2.26×（CI 不重叠）**；小规模（≤10 实体）复刻出现 after 慢约 2× 的稳定反转，经 gc 探针（before 80 B/op → after 0.001 B/op）+ 成本模型（after 工作量是 before 严格子集）+ 规模翻转三中证伪为 JIT 伪影，机制保留（0140/0157 先例），证伪记录全文载于报告。

### 0190 — Level getEntities fill 重载 + LivingEntity.pushEntities scratch list
- **文件**：`net/minecraft/world/level/Level.java` + `net/minecraft/world/entity/LivingEntity.java`
- **热点**：每个 pushable LivingEntity 每 tick `getEntities(this, box, predicate)` 分配 ArrayList（+ 元素超 10 时扩容拷贝），实体密集农场/牧场每秒数千次。
- **改法**：Level 新增 `papoGetEntitiesInto(entity, box, predicate, into)`——与分配版逐语句一致（同调 moonrise `getEntities(entity, box, into, predicate)` + `PlatformHooks.addToGetEntities` 追加），仅去掉 `new ArrayList<>()`；LivingEntity 每实体惰性 scratch list（clear+fill 复用）。
- **等价性/重入**：列表不逃逸出 pushEntities；填充与消费之间仅 hurtServer（cramming EntityDamageEvent）与 doPush→Entity.push（位移数学无事件），protected 方法无 API 路径在同实体上重入；vanilla 同样在回调后继续迭代快照，语义一致。
- **基准**：ScratchListBench.push 475.919→210.680（**2.26×**，CI 不重叠）
- **风险**：低。

### 0191 — Mob.aiStep looting 扫描 scratch list
- **文件**：`net/minecraft/world/entity/Mob.java`
- **热点**：每个 canPickUpLoot 生物（僵尸/骷髅/猪灵等）每 tick `getEntitiesOfClass(ItemEntity.class, box)` 分配 ArrayList。
- **改法**：每 Mob 惰性 scratch list + 上游已有公开 fill 重载（`EntityTypeTest.forClass(ItemEntity.class)` + NO_SPECTATORS——`getEntitiesOfClass(Class, AABB)` 正是该重载的分配包装，EntityGetter.java:73-75）。
- **等价性/重入**：循环内唯一回调 pickUpItem（EntityPickupItemEvent）无 API 路径重入同 Mob aiStep；vanilla 亦在事件触发中迭代快照。
- **基准**：ScratchListBench.loot 76.957→71.586（**1.08×**，CI 不重叠）
- **风险**：低。

### 0192 — NearestAttackableTargetGoal.findTarget scratch list
- **文件**：`net/minecraft/world/entity/ai/goal/target/NearestAttackableTargetGoal.java`
- **热点**：每个敌对生物目标扫描（默认 10 tick 间隔×全服生物）`getEntitiesOfClass(targetType, area, entity -> true)` 分配 ArrayList。
- **改法**：每 goal 实例惰性 scratch list + 上游 fill 重载。
- **等价性/重入**：`getNearestEntity(List, …)` 仅迭代不保留（ServerEntityGetter.java:54-）；TargetingConditions.test 无事件；无子类覆写 findTarget（11 个子类全库 grep 实证）；EntityTargetEvent 在 start() 才触发、列表已用完；goal 每 Mob 实例化、主线程单线程 tick。
- **基准**：ScratchListBench.find 498.618→413.620（**1.21×**，CI 不重叠）
- **风险**：低。

---

## 批次 48（2026-08-01）：暂缓清单清算——交互事件门控×2 + goal 目标缓存（0193-0195）

批次 46 暂缓清单三项全部落地（MoveToBlockGoal above 缓存经全量子类审计解除暂缓、SWAP_ITEM_WITH_OFFHAND 门控、handleInteract 双事件门控）。全部 compileJava + 全量 test 通过（零 FAILED），applyPatches 干净应用。JMH 实测（note/report/perf/2026-08-01-jmh-microbench-batch48.md）：**3 项全部正收益（1.46×-8.58×，CI 均不重叠）**。

### 0193 — SWAP_ITEM_WITH_OFFHAND 零监听器门控
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java`（handlePlayerAction）
- **热点**：每次 F 键交换（快捷栏高频操作）2×asCraftMirror + 2×clone + 事件 + callEvent。
- **等价性**：PlayerSwapHandItemsEvent 自有 HandlerList、无子类（全库 grep 实证）；零监听器时 callEvent 无操作、isCancelled 恒 false；事件物品为 mirror 的 clone，`CraftItemStack.equals`（clone vs mirror：null==null 首查命中、双空、或 `ItemStack.matches` 内容匹配，CraftItemStack.java:76-83 逐行实证）恒 true ⟹ 两 setItemInHand 必走默认交换分支。门控路径直达两次 setItemInHand（顺序与原默认分支逐句一致）。
- **基准**：SwapInteractGateBench.swap 10.892→1.353（**8.05×**，CI 不重叠）
- **风险**：低。

### 0194 — handleInteract 实体交互双事件零监听器门控
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java`（handleInteract 匿名 Handler）
- **热点**：每次实体右击/精准右击（村民交易、喂食、装备马匹等高频）构造事件 + getBukkitEntity + CraftEquipmentSlot（精准交互另有 CraftVector）+ callEvent + resendData 判定链。
- **等价性**：PlayerInteractEntityEvent 与 PlayerInteractAtEntityEvent **各自独立 HandlerList**（paper-api 逐文件实证：各持私有静态表，At 不共享父表）⟹ 按调用点分别门控精确。performInteraction 改 `@Nullable event`（null=门控路径）：零监听器时 callEvent 无操作、isCancelled false、两次 getItemInHand 之间无任何调用 ⟹ resendData 恒 false，整个事件块（itemType/leash/resend/refreshEntityData/装备包重发）均为死代码；共享尾部 entityInteraction.run + CriteriaTriggers/swing 不变。
- **基准**：SwapInteractGateBench.interact 4.952→0.577（**8.58×**，CI 不重叠）
- **风险**：低。

### 0195 — MoveToBlockGoal.getMoveToTarget above() identity 缓存
- **文件**：`net/minecraft/world/entity/ai/goal/MoveToBlockGoal.java`
- **热点**：每活跃 goal（猫坐箱、兔啃田、海龟产卵、僵尸拆门等）每 tick `blockPos.above()` 分配 1 BlockPos。
- **等价性**：identity 缓存的失效信号完备性——blockPos 只被**重赋值**（findNearestBlock 赋新建 MutableBlockPos 后立即 return、Paper stop() 赋 ZERO 单例），从不原地 mutate（8 直接子类+RemoveBlockGoal 子树全库审计：无 `this.blockPos =` 直写、无 findNearestBlock 覆写——RemoveBlockGoal 仅调用、无 cast-mutate）；身份相同 ⟹ 坐标相同 ⟹ above 结果相同。StriderGoToLavaGoal 覆写 getMoveToTarget 不受影响。返回值使用方（tick 内 closerToCenterThan/getX/Y/Z）只读。
- **基准**：MoveToTargetCacheBench.tick 2.185→1.496（**1.46×**，CI 不重叠）
- **风险**：低。

### 批次 46 暂缓清单结案
- ~~MoveToBlockGoal above() identity 缓存~~ → 0195（子类审计完成解除暂缓）
- ~~SWAP_ITEM_WITH_OFFHAND 门控~~ → 0193
- ~~handleInteract 双事件门控~~ → 0194（null-event 重结构避免了改匿名 Handler 结构）
- 批次 46 暂缓清单至此全部清算完毕。

---

## 批次 49（2026-08-01）：零监听器事件门控续批 + 实体扫描 scratch-list 续批（0196-0202）

三路 survey（容器/菜单、区块IO/保存、AI/Brain）产出 28 候选，本批落地**已验证低风险子集**：4 个零监听器事件门控（自有表无子类，逐文件实证）+ 3 个 scratch-list（复用 0190 模式）。全部 compileJava + 全量 test 通过（零 FAILED），applyPatches 干净应用（sources 915 + features 202 + resources 6）。JMH 实测（note/report/perf/2026-08-01-jmh-microbench-batch49.md）：**5 项正收益（1.10×-6.68×，CI 不重叠）、2 项机制保留（0199/0200 浅栈 JIT 伪影，gc 探针证分配消除）**。

### 0196 — handleSelectTrade 零监听器门控
- **文件**：`net/minecraft/server/network/ServerGamePacketListenerImpl.java`（handleSelectTrade）
- **热点**：每次村民交易选项点击构造事件 + getBukkitView + callEvent。
- **等价性**：TradeSelectEvent 自有 HandlerList（TradeSelectEvent.java:19）、无子类（全库 grep）；零监听器 callEvent 无操作、isCancelled() 默认 false。
- **基准**：EventGateMiscBench.trade 3.174→0.524（**6.06×**，CI 不重叠）
- **风险**：零。

### 0197 — BlockBurnEvent 零监听器门控
- **文件**：`net/minecraft/world/level/block/FireBlock.java`（checkBurnOut）
- **热点**：每次火烧毁判定（森林火灾/岩浆引燃高发期）CraftBlock×2 + 事件 + callEvent。
- **等价性**：BlockBurnEvent 自有 HandlerList（BlockBurnEvent.java:18）、无子类；调用方只读取消标志。
- **基准**：EventGateMiscBench.burn 3.243→0.485（**6.68×**，CI 不重叠）
- **风险**：零。

### 0198 — CauldronLevelChangeEvent 零监听器门控
- **文件**：`net/minecraft/world/level/block/LayeredCauldronBlock.java`（changeLevel）
- **热点**：降水随机刻填充 + 全部炼药锅玩家交互（瓶/桶/灭火等，8 处调用）CraftBlockState + CraftBlock + 事件。
- **等价性**：CauldronLevelChangeEvent 自有 HandlerList（CauldronLevelChangeEvent.java:18）、无子类；炼药锅无方块实体 → `newState.place(UPDATE_ALL)` ≡ `level.setBlock(pos, newBlock, UPDATE_ALL)`；零监听器 callEvent 恒 true。
- **基准**：EventGateMiscBench.cauldron 3.714→0.635（**5.85×**，CI 不重叠）
- **风险**：零。

### 0199 — 岩浆引火 BlockIgniteEvent 零监听器门控
- **文件**：`net/minecraft/world/level/material/LavaFluid.java`（randomTick 两站点）
- **热点**：岩浆 randomTick 2/3 概率引火尝试 CraftBlock×2 + 事件 + callEvent；FireBlock:231 已门控，岩浆两处漏网补齐。
- **等价性**：BlockIgniteEvent 自有 HandlerList（BlockIgniteEvent.java:20）、无子类；与 FireBlock:231 同模式（已验证先例）。
- **基准**：EventGateMiscBench.ignite 0.656→0.548（1.20× 复刻内中性——浅栈 CraftBlock 被 EA 标量替换；真实 callBlockIgniteEvent 跨方法深栈 EA 无法消除，机制保留）
- **风险**：零。

### 0200 — AvoidEntityGoal.canUse scratch list
- **文件**：`net/minecraft/world/entity/ai/goal/AvoidEntityGoal.java`
- **热点**：每个 goal 评估周期（约 2 tick）每只携带者（苦力怕避豹猫、骷髅避狼、兔、海龟等）getEntitiesOfClass 分配 ArrayList。
- **等价性/重入**：0190 模式——getNearestEntity 仅迭代不保留（TargetingConditions.test 无事件），列表 canUse 内消费完毕。
- **基准**：EntityScanScratchBench.avoid gc 探针 before 416 B/op → after 0.002 B/op（机制成立）；浅栈时间反转经批次47同款 JIT 伪影证伪（after 工作量是 before 严格子集），机制保留
- **风险**：低。

### 0201 — FollowParentGoal.canUse scratch list
- **文件**：`net/minecraft/world/entity/ai/goal/FollowParentGoal.java`
- **热点**：每只幼年动物每 goal 评估周期 getEntitiesOfClass 分配 ArrayList；繁殖场幼体密集。
- **等价性/重入**：0190 模式——列表 canUse 内线性最近扫描消费完毕，无事件无重入。
- **基准**：EntityScanScratchBench.parent 264.454→239.571（**1.10×**，CI 不重叠）
- **风险**：低。

### 0202 — NearestItemSensor.doTick scratch list
- **文件**：`net/minecraft/world/entity/ai/sensing/NearestItemSensor.java`
- **热点**：每 scanRate（默认 20 tick）getEntitiesOfClass(带谓词) 分配 ArrayList；携带者猪灵/悦灵/狐狸。
- **等价性/重入**：0190 模式——sensor 每 Brain 实例化（per-entity），列表 sort+视线最近扫描后丢弃，setMemory 只存最近单引用不存列表。
- **基准**：EntityScanScratchBench.item 460.049→409.521（**1.12×**，CI 不重叠）
- **风险**：低。

### 暂缓（批次 49 记录，待后续批次深分析）
- **InventoryDragEvent 门控**（容器候选1，中价值）：含 setCarried 双拷贝语义（防 plugin 关闭背包时复制）、event.getCursor()==newCarried 等价链需逐句实证，留后续。
- **InventoryCreativeEvent 门控**（容器候选2）：InventoryClickEvent 父表门控，ALLOW/DENY/getCursor 链需逐句实证，留后续。
- **callPrepareResultEvent 门控**（容器候选3，中价值）：Prepare* 子类（PrepareAnvil/Smithing/Grindstone/Result）是否各自定义 getHandlerList/getHandlers 方法需逐文件实证（已知无 HANDLER_LIST 字段，但方法层未核），留后续。
- **TransientCraftingContainer CraftingInput 缓存**（容器候选4，中-高价值）：mutation counter 失效信号完备性论证，留后续。
- **RecipeManager.getRecipeFor Optional 内部路径**（容器候选5，低-中价值）：熔炉每 tick 双层 Optional，nullable 内部方法重载。
- **PathNavigation.tick Vec3/BlockPos 分配**（AI候选3，中-高价值）：3 Vec3 + 2 BlockPos / tick / 移动中 mob，double 公式逐项照抄。
- **PureMemory 声明式 OneShot Optional 系统性消除**（AI候选1，高价值）：Brain 新增 papoGetMemoryHolderRaw，村民交易所场景显著。
- **TriggerGate 死 shuffle**（AI候选2，附带发现 Paper 语义偏差）：仅报告，不改语义。

---

## 批次 50（2026-08-02）：网络突发选块去装箱 + 聚集追踪去冗余 canSee（0203-0204）

用户报告两个痛点：① 网络与流畅度（尤其跑图突发）；② 多玩家聚集时延迟显著上升。三路 survey（聚集广播热路径 / 网络突发与区块发送 / 插件指纹泄露）产出候选后落地 2 个补丁。全部 compileJava（`--rerun-tasks --no-configuration-cache`）+ 全量 test BUILD SUCCESSFUL（零 FAILED）。JMH 报告：[note/report/perf/2026-08-02-jmh-microbench-batch50.md](report/perf/2026-08-02-jmh-microbench-batch50.md)。**0203 区块选块去装箱 10.0×（分配降 42×，gc 确证）；0204 已追踪对跳过冗余 canSee 在 vanish 场景 1.15×（CI 不重叠），空表 0.67× 为静态方法 JIT 折叠伪影（机制保留）**。

### 0203 — PlayerChunkSender 区块发送选块去装箱（跑图突发路径）
- **文件**：`net/minecraft/server/network/PlayerChunkSender.java` `collectChunksToSend`（`pending > floor` 分支）
- **热点**：每玩家每 tick 区块发送收集；跑图/登录突发期 pending 可达数百。原实现 `.stream().collect(Comparators.least(floor, Comparator.comparingInt(chunkPos::distanceSquared)))` 把每个 pending long 装箱为 Long + Guava PriorityQueue/Collector + 结果 ArrayList。0053/0141 此前只改了第二段解析管道，**保留了装箱的 Comparators.least 选块**。
- **改法**：原语 k 近邻——`longIterator()` + `long[floor]`/`int[floor]` 有界缓冲（保留当前 floor 个最近，新元素严格小于当前最远即替换）+ 末尾选择排序升序，再 `getChunkToSend` 解析。`ChunkPos.distanceSquared(long)` 是 int 返回的原语重载（无装箱）。floor ≤ 64（batchQuota 上限）。
- **等价性**：距离两两不同时选择结果与 Comparators.least 逐元素一致；并列时哪个被选在两实现中均未定义且对客户端不可观察（等距区块本 tick 批量发送，未选者留 pendingChunks 下 tick 发送，玩家无法区分哪个等距区块先到）。distanceSquared 调用次数 = pending（原版 ≈ 2×pending×log floor，省调用）。main 自检：200 组随机输入下 before/after 选出的 floor 个最近"距离多重集"均等于输入的 floor 个最小距离，ALL OK。
- **基准**：ChunkSelectBench before 8694.243 ± 1109.970 → after 869.026 ± 14.641 ns/op（**10.0×**，CI [7584,9804] vs [854,884] 不重叠）；`-prof gc` alloc.rate.norm 6088.053 → 144.006 B/op（**分配降 42×**，200 Long 装箱 + 大 ArrayList 全消除，after 仅 floor=9 结果数组）。
- **风险**：低。

### 0204 — ChunkMap.TrackedEntity.updatePlayer 已追踪对跳过冗余 canSee（聚集稳态）
- **文件**：`net/minecraft/server/level/ChunkMap.java` `TrackedEntity.updatePlayer(ServerPlayer,int)`
- **热点**：`moonrise$tick` 每 tick 对每个被追踪实体 × chunk 内每个玩家调 updatePlayer；稳态聚集时绝大多数 (实体,玩家) 对已在 seenBy 中（`seenBy.add` 返回 false 无副作用），但原实现仍重算 `player.getBukkitEntity().canSee(this.entity.getBukkitEntity())`（2× getBukkitEntity + CraftPlayer.canSee：visibleByDefault 字段 + getUniqueId + invertedVisibilityEntities.containsKey HashMap 查找）。聚集时 N×M 倍率放大。
- **改法**：先 `boolean papoAlreadyTracked = seenBy.contains(player.connection)`（ReferenceOpenHashSet 身份查找）；已追踪则跳过 canSee（距离/broadcastToPlayer/isChunkTracked 检查保留，它们决定出界移除）。
- **等价性（关键不变量，逐路径源码实证）**：`seenBy.contains(conn) ⟹ canSee == true`。令 canSee 变 false 的三条路径——`CraftPlayer.hideEntity`（:1851）、`setVisibleByDefault(false)`（CraftEntity:721，置字段前对所有在线玩家 resetAndHideEntity）、`resetAndHideEntity`——均经 `untrackAndHideEntity → unregisterEntity → TrackedEntity.removePlayer` 先把对移出 seenBy；seenBy 唯一写入点 `seenBy.add` 受 canSee 守卫，`trackAndShowEntity`（showEntity 路径）也复用 updatePlayer。故已追踪对的 canSee 必为 true，CraftBukkit vanish 分支不可能命中，跳过位级等价。六种 (已/未追踪 × flag 真/假 × canSee 真/假) 分支逐一对齐，仅"已追踪 + canSee 假"为不变量排除的不可能情形。main 自检：稳态两路径 tracked 计数一致；未追踪+被 hide 场景 flag 一致（false），ALL OK。
- **基准**：TrackCanSeeBench。**非空 inverted map（每玩家藏 5 个其他实体，真实 vanish 插件场景）**：before 141.421 ± 8.581 → after 122.851 ± 7.098 ns/op（**1.15×**，CI [132.8,150.0] vs [115.7,129.9] 不重叠，canSee 不可折叠）。**空 inverted map（无 vanish）**：before 81.073 ± 6.732 → after 121.751 ± 5.912 ns/op（0.67× 反转）——基准的**静态** `canSee` 方法被 JIT 内联 + 空表 profiling 常量折叠为恒 true，before 工作被消除而 after 多出的 seenBy.contains 不可消除。真实服务器的 `player.getBukkitEntity().canSee(entity.getBukkitEntity())` 经多态虚方法 + 跨方法边界**不可如此折叠**，故空表场景仍受益（机制保留，同 0140/0186/0199/0200 先例：复刻浅栈内静态方法被 JIT 折叠掩盖真实收益）。
- **风险**：低（不变量严格证明；唯一代价是未追踪对多一次 seenBy.contains，但未追踪对在稳态聚集属少数）。

### 暂缓（批次 50 survey 产出，待后续批次）
- **PacketBundleUnpacker.encode 每包 `list::add` Consumer**（网络 survey 候选2，零风险）：非 bundle 包（99%）只执行一次 `consumer.accept(packet)`；改 `if (packet instanceof BundlePacket) unbundlePacket(packet, list::add); else list.add(packet);` 即免每包 Consumer 分配。低价值（IO 线程 + EA 可能已消除），留后续顺手带走。
- **ServerEntity.addPairing ArrayList 预尺寸**（网络 survey 候选5，低价值）：sendPairingData 填 2-4 项，默认 cap 10 略浪费；预尺寸 4。边际收益，留后续。
- **Connection.sendPacket 跨线程 lambda 池化 / PacketSendAction AtomicBoolean→boolean**（网络 survey 候选3/4，高价值高并发风险）：reentrancy/clearPacketQueue 并发使池化易错，需单独验证，留后续。
- **ChunkMap.getEffectiveRange ridden 实体 getIndirectPassengers 缓存**（聚集 survey 候选4）：mount/dismount 失效钩子，中风险，留后续。
- **trackedDataValues 缓存刷新点延后到 pairing**（聚集 survey 候选2，触碰已结案区域的新角度）：把 getNonDefaultValues 从 sendDirtyEntityData（每 dirty）延后到 sendPairingData（新观众），消除稳态 dirty 的无效刷新；等价性可达但触碰已结案区，留后续单独复审。
- **插件指纹泄露加固**（安全 survey）：P0 `/plugins` `/version` `/help` 默认权限 TRUE（人人可用，唯一直接吐明文插件清单）+ brand payload 发 "Papo" + ping 版本串 + plugin channel REGISTER 广播；建议在 `paper-global.yml` 新增 `fingerprint-hardening` 区段（默认全保现状兼容）。这是独立安全加固轮次，留作批次 51。

---

## 批次 51（2026-08-02）：插件指纹泄露加固（fingerprint-hardening 可控开关）

> 安全加固轮次（非性能优化），针对用户报告"作弊客户端能读取服务端数据推测装了哪些插件"。三路 survey 的"插件泄露"向量已完整测绘。本轮落地 paper-global.yml 的 `fingerprint-hardening` 区段（默认全保现状），覆盖三个**纯 paper-server、客户端被动接收**的向量。compileJava + 全量 test 全绿；行为自检 18 项 ALL OK。报告：[note/report/2026-08-02-fingerprint-hardening.md](report/2026-08-02-fingerprint-hardening.md)。

### 配置区段（直接提交，paper-server/src/main/java/.../GlobalConfiguration.java）
新增 `GlobalConfiguration.FingerprintHardening`（嵌套 ConfigurationPart）：`brand-payload`(mode REAL/VANILLA/CUSTOM + custom-value)、`status`(version-string REAL/VANILLA/CUSTOM + custom-value)、`plugin-channels`(broadcast-mode ALL/WHITELIST/NONE + allowed-channels)。三个 section 各带 `resolve`/`shouldBroadcast` 实例方法。默认全保现状（REAL/REAL/ALL），config 未加载时回退 REAL/ALL。

### 0205 — brand payload 解析（补丁，NMS）
- **文件**：`net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java` `startConfiguration`
- **向量**：配置阶段发 `BrandPayload(server.getServerModName())` = "Papo"，客户端 F3 可见、被动接收（V2）。
- **改法**：`BrandPayload(papoBrand(server))`，`papoBrand` 经 `GlobalConfiguration.get().fingerprintHardening.brandPayload.resolve(realBrand)` 按 mode 返回（REAL=现状 / VANILLA="vanilla" / CUSTOM=custom-value）。NMS 读 GlobalConfiguration 是既有模式（122 处）。`get()` 静态字段读无异常。
- **兼容性**：REAL 默认行为逐字不变；Brand-Id 仍 papermc:paper，不影响 isBrandCompatible。
- **风险**：低。

### 直接提交（无补丁文件）— status / plugin-channels
- **status.version-string**（[PaperServerListPingEventImpl.java](../paper-server/src/main/java/com/destroystokyo/paper/network/PaperServerListPingEventImpl.java)）：ping 版本串经 `papoVersionName(server)` 解析（V3a：未登录即可读的 "Papo 1.21.11"）。VANILLA=仅 MC 版本。
- **plugin-channels.broadcast-mode**（[CraftPlayer.sendSupportedChannels](../paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java#L2367)）：`minecraft:register` 广播的 incoming channel 名逐个经 `shouldBroadcast` 过滤（V1：channel 名常可识别插件，最直接的被动插件身份泄露）。NONE 全不发、WHITELIST 仅 allowed-channels、过滤后空则不发 REGISTER。`if (stream.size() > 0)` 守卫。

### 暂缓（survey 已测绘，留后续批次）
- **V5 `/plugins` `/version` `/help` 默认权限 TRUE**（P0，最严重，唯一直接吐明文插件清单）：跨 paper-api（CommandPermissions:17-20 硬编码 TRUE）↔ paper-server（config 在此）。留**批次 52**——在 `CraftDefaultPermissions.registerCorePermissions`（pluginManager 已就绪）后按 config 用 `Permission.setDefault(OP)` 覆写三者 + recalculate。
- **V4 Brigadier 命令树**：复用 `spigot.yml` `commands.send-namespaced` + `PlayerCommandSendEvent`，无需新开关。
- V3b/V7/V8/V6：插件配置依赖或泄露面小，文档化即可。

### 性能影响
可忽略——brand/status 各一次/玩家（配置阶段/ping），plugin-channels 一次/玩家 join；均为一次静态字段读 + 字符串比较，亚微秒级，无分配。故无 JMH（改用行为自检 18 项 ALL OK 验证 resolve/filter 全分支 + 默认值兼容性）。

### 踩坑（已记入 build.md）
`@Comment` 在本仓库为单 String（非 String[]）——多行用 `+` 拼接，数组语法 `@Comment({...})` 编译报"批注值不是允许的类型"。`final` 变量在 try+catch 都赋值触发"可能已分配"定值分析错误——`GlobalConfiguration.get()` 是静态字段读无异常，去掉 try/catch 改直接读 + null 检查。rebuildPatches `rebuildResourcePatches` 仍因 Windows 文件锁 `git add -A exit 128` 间歇失败（重跑即过，build.md 已记）；feature 补丁用 rebuildPatches 生成（正确格式）+ 恢复法保留（避开 0163–0192 垃圾重命名复发）。`git format-patch` 定向导出格式与 paperweight 约定有差异（index 缩写 hash / `--` 签名尾 / hunk 头 `+line` 而非 `+_`），未采用，留待后续验证。

---

## 候选后续批次（来自 survey，按 价值×置信/风险 排序）


（旧清单中 Direction.Plane 迭代器、tickBlockEntities 移除集、NaturalSpawner 距离、pushableBy、LookControl Optional、distanceToSqr 批、CraftBukkit 枚举缓存均已完成，见对应批次。批次 28 完成：InventoryChangeTrigger 早退 0093、Utf8String 写侧 NBT ASCII 快速路径 0092、tickChildren SetTimePacket 惰性 0094、FriendlyByteBuf.writeNbt 适配器 0095。批次 29 完成：FriendlyByteBuf.readNbt 读侧适配器 0096、VarInt.read 快速路径 0097、枚举常量缓存 0098、registry codec 单例 0099、EntityJumpEvent/PlayerVelocityEvent 门控 0100、惰性 list 0101、TrackedEntity 惰性移除 0102、Inflater 池化 0103；map 编码 forEach→entrySet 经基准实测回退 0.77× 已撤销（见批次 29 撤销条）。批次 30 完成：流体检测路径 3 处分配消除 0104、computeSpeed Vec3 消除 0105。批次 31 完成：BlockFromToEvent 门控+流体 tick 去冗余查询 0106、物品拾取/合并门控 0107、经验球 4 事件门控 0108、PlayerJumpEvent 门控+Vec3 折叠 0109、broadcastSlotChange 门控 0110、寻路缓存两段式+GateBehavior stream 消除 0111。批次 32 完成：双拾取事件门控 0112、handleBlockFormEvent 门控 0114（初稿误记 0113，补丁序列以补丁文件为准后正名）。批次 33 完成：漏斗吸取 AABB 实例缓存 0113。批次 34 完成：touchingUnloadedChunk 内联 0114、寻路 getPathType BlockPos 0115、漏斗 getEntityContainer AABB 缓存 0116、BlockFadeEvent 门控 0117、玩家状态事件×4 门控 0118、LeavesDecay/BlockIgnite 门控 0119、PathNavigation Node 直读 0120、EntityPathfindEvent 门控 0121、矿车事件门控 0122、push Vector 消除 0123、EntityTarget/Enderman 门控 0124、CraftEventFactory 四事件门控（直接提交，编号 0125）。批次 35 完成：红石事件快路调用点 0125、刷怪事件门控+复用 0126、sections Optional 消除 0127、装饰 rangeClosed 双循环 0128、DEFLATE Deflater 池化 0129、TemptingSensor 条件复用 0130、PlayerSensor 属性外提 0131、PlayerMoveEvent Location 延迟 0132、RegistryOps 缓存 0133、handleBlockGrowEvent 门控+handleRedstoneChange 快路（直接提交，编号 0134）；holderSet 流改 for-each 实测 0.82× 否决。批次 36 完成：ComponentSerialization 缓存接入 0134、updateFluidOnEyes scratch 0135、POI Optional 消除 0136、交互三触发器门控 0137、PlayerInteractEvent 门控×7 0138、ItemCraftedEvent 门控×2 0139、handleUseItemOn Vec3 展开 0140、PlayerChunkSender 命令式 0141、InteractWithDoor scratch+坐标直读 0142、EntityEquipment VALUES 0143、熔炉 SingleRecipeInput 缓存 0144、ChunkHolder.broadcast 循环 0145、光照包预分配 0146、getEffectiveRange 外提 0147、isSunBurnTick 延迟构造 0148、tickEffects 展开 0149、callPreCraftEvent 快路（直接提交，初记 0142 正名 0150——补丁序列以补丁文件为准）。批次 37 完成：ValidateNearbyPoi 手写 OneShot+Brain 原生读 0150、Behavior tickRate 缓存 0151、Sensor tickRate 缓存 0152、CountingOps RegistryOps 缓存 0153、InventoryClickEvent 零监听器快路 0154、PAPO_CONFIG_EPOCH 配置纪元失效钩子（直接提交，编号 0155）。批次 38 完成：Varint21FrameDecoder 内联解析 0155、EntitySelector 谓词缓存 0156、addDecoration 比较优先 0157、tickCarriedBy 谓词内联 0158；ServerEntity 增量合并重勘察结案（无可证等价候选）。批次 39 完成（信标专题）：updateBase scratch 0159、光柱扫描 pos 字段化 0160、信标 AABB 折叠 0161、BeaconEffectEvent 零监听器快路 0162。批次 40 完成：潮涌核心 updateShape scratch 0163、潮涌核心 AABB 折叠×2 0164、刷怪笼 PreSpawnerSpawnEvent 零监听器快路 0165；AttributeMap 缓存评估结案（不做，实例级缓存已存在）。批次 41 完成：漏斗吸取路径缓存 0166、弹射物扫描 AABB 折叠 0167、活塞扫描盒折叠 0168。批次 42 完成（弹射物专题）：候选循环 inflate(0) 跳过 0169、canHitEntity 谓词缓存 0170、AbstractArrow 扫描盒折叠 0171。批次 43 完成（弹射物专题续）：钓鱼开阔水域判定命令式重写 0172（3.72×）、烟花 Vec3 内联×2 分支 0173、火球 applyInertia+MISS setPos Vec3 消除 0174（0173/0174 复刻内中性、gc 证明 EA 伪影，机制保留）。批次 44 完成（移动/战斗 Vec3 专题）：getInputVector 内联 0175、knockback 内联 0176、格挡视角内联×2 0177（1.09×）、批次43注释勘正 0178；含 0173a 分配计数勘误。批次 45 完成（块内检测路径）：AABB 折叠 0179（1.61×）、位移差内联 0180（中性机制保留）；0181 回退 Movement 缓存实测 0.46×/修正后 0.91× 已撤销（批次29先例）。注意 0114/0150/0155 编号均被两批使用：批次 32 的 0114、批次 36 的 0150、批次 37 的 0155 是直接提交（无补丁文件），批次 34 的 0114、批次 37 的 0150、批次 38 的 0155 起为补丁文件编号——以补丁文件序列为准。）

### 批次 23-27 survey 新增候选（2026-07-30，尚未做）

- **EntitySelectorOptions "scores" Objective 解析 memoize**（低-中）：循环命令方块每 tick 每实体 E×K 次 getObjective 哈希查找。**批次 28 评估：目标可运行时增删，无干净失效钩子，不满足可证等价，暂缓。**
- **AbstractFurnaceBlockEntity canBurn 产物缓存**（中）：每燃烧熔炉每 tick `assemble()`=result.copy()。**批次 28 评估：事件间隔与插件原位改组件使缓存键不可证等价，暂缓。**
- **Entity.checkSupportingBlock Optional 复用**（中，涉 Paper 碰撞补丁区）。**批次 28 评估：收益低（每调用 1 Optional + 1 AABB），暂缓。**
- **Entity.checkInsideBlocks AtomicInteger 消除**（中，需 BlockGetter 加返回 index 的重载）。**批次 28 评估：周围分配中的噪声，改造复杂度高，暂缓。**
- **DefaultRedstoneWireEvaluator.updatePowerStrength 去 HashSet**（中：更新顺序变化，不满足严格序等价，暂缓）。
- ~~**FriendlyByteBuf.readNbt 读侧轻量 DataInput 适配器**~~ **批次 29 已完成（0096）**。
- ~~**EntitySelector.getPredicate 特性谓词缓存**~~ **批次 38 已完成（0156）**：i==0 分支惰性缓存 allOf 组合，JMH 1.15×。

### 批次 29 survey 新增候选（2026-07-31，尚未做）

- ~~**Entity 流体检测路径分配消除**~~ **批次 30 已完成（0104）**。
- ~~**Entity.computeSpeed Vec3 拆 double**~~ **批次 30 已完成（0105）**：消费方仅 KineticWeapon（低频），分量存储 + getKnownSpeed 惰性重建。
- **ClientboundLightUpdatePacketData BitSet.toLongArray 缓存**（中价值）：getter public 可变，**不满足可证等价，暂缓**。
- **PlayerNaturallySpawnCreaturesEvent 复用**（每玩家每 tick）：残留状态管理复杂，**暂缓**。
- ~~**Varint21FrameDecoder helperBuf 消除**~~ **批次 38 已完成（0155）**：内联解析，JMH 1.04-1.05×（CI 重叠小幅正向，与"低价值"定级一致）。
- ~~**Entity.touchingUnloadedChunk inflate(1.0) AABB 内联**~~ **批次 34 已完成（0114）**：方法体内联即可，无需签名穿线。
- ~~**批次 35 预定**~~ **批次 35 已完成（0125-0133 + 直接提交 0134，holderSet 一项 0.82× 否决）**：handleBlockGrowEvent 门控、PlayerNaturallySpawnCreaturesEvent 门控+复用、SerializableChunkData sections Optional、ChunkGenerator rangeClosed、callRedstoneChange 快路+11 调用点、DEFLATE 写侧 Deflater 池化、PlayerMoveEvent Location 延迟、RegistryOps(NbtOps) 缓存、PlayerSensor/TemptingSensor 小项。
- ~~**批次 36 预定**~~ **批次 36 已完成（0134-0149 + 直接提交 0150）**：ComponentSerialization 缓存接入、updateFluidOnEyes scratch、POI Optional 消除、交互三触发器门控、PlayerInteractEvent 门控×7、ItemCraftedEvent 门控×2、handleUseItemOn Vec3 展开、PlayerChunkSender 命令式、InteractWithDoor scratch+坐标直读、EntityEquipment VALUES、熔炉 SingleRecipeInput 缓存、ChunkHolder.broadcast 循环、光照包预分配、getEffectiveRange 外提、isSunBurnTick 延迟构造、tickEffects 展开、callPreCraftEvent 快路（直接提交，初记 0142，正名 0150）。

已确认**已优化、勿重复**：EntityTickList.forEach、LevelTicks、LevelChunk.getBlockState、getEntitiesOfClass、Entity.collide 数学、CompoundTag.copy、PatchedDataComponentMap、getNearestPlayer、PoiManager、Brigadier 子节点查找等。

---

## 否决评估：ReobfServer 多线程重映射（启动期，非 tick 热路径）

> 2026-08-02。用户要求优化服务端启动 remapping 速度，候选：[ReobfServer.java:72](../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) `.threads(1)` → 多线程。

**机制**：运行时 reobf 由 `[ReobfServer]` 完成（mojmap jar 为兼容传统 Spigot 插件，把 mojang 命名重映射为 Spigot 命名）。ART（AutoRenamingTool，paper-server shade 较新版）的 `threads(n)` 经 `AsyncHelper` 在 n>1 时用 `newWorkStealingPool(n)` 并行处理 class entry。

**独立基准实证**（[报告](report/perf/2026-08-02-reobf-threads-bench.md)）：
- 正确性 ✓：threads(1) vs threads(8) 产物 17590 entries 逐字节一致；
- 速度 ✗：threads(1) 2611ms vs threads(8) 2577ms（1.01×）；scaling 1/2/4/8/16/32 = 2670/2275/2660/2659/2735/2988 ms（best），threads≥4 持平/略差；
- 瓶颈：ART 内部单线程 jar IO + inheritance map 构建，可并行的 ASM class 重命名占比小且 threads=2 即饱和。

**否决**：实测无实质收益，按「实测无收益即撤」纪律（同 0100/0181/0187）不落地。reobf 仅在缓存 `plugins/.paper-remapped/remap-classpath/<hash>.jar` 缺失时跑一次；减少其对启动影响的正道是保留缓存或分发 reobf jar（见 [启动分析报告](report/2026-08-02-startup-remap-analysis.md)），非加速单次。
