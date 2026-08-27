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

## 批次 52（2026-08-02）：V5 命令默认权限加固（闭合插件泄露主题）

补齐批次 51 fingerprint-hardening 暂缓的第 4 个开关，闭合插件指纹泄露主题。无新补丁（两处均为 src/main 直接提交）。compileJava + 全量 test 全绿；行为自检扩到 25 项 ALL OK。报告：[note/report/2026-08-02-fingerprint-hardening-commands.md](report/2026-08-02-fingerprint-hardening-commands.md)。

### 直接提交 — `fingerprint-hardening.commands.player-visible-defaults`（V5）
- **文件**：`GlobalConfiguration.FingerprintHardening.Commands`（新增 section，`playerVisibleDefaults` String true/op/false + `papoResolveDefault()` → PermissionDefault）+ `CraftDefaultPermissions.registerCorePermissions`（新增 `papoOverrideCommandVisibility()`）。
- **向量**：`CommandPermissions`（paper-api）把 `bukkit.command.plugins/version/help` 默认设为 TRUE（人人可用），是唯一**直接吐完整明文插件清单**的向量（主动，但默认权限 TRUE 使任意改装客户端可触发）。
- **改法**：`CommandPermissions.registerPermissions(parent)` 之后，按 config 用 `Permission.setDefault(def)` 覆写三者 + `recalculatePermissibles()`；三者经 `Bukkit.getPluginManager().getPermission(name)` 取得（DefaultPermissions 经 `addPermission` 注册进 PluginManager）。时机在 `CraftServer.enablePlugins`（pluginManager 已就绪、无在线玩家），安全。
- **等价性/兼容性**：`true`（默认）与 0.27.0 逐字一致；config 未加载回退 TRUE。`op`/`false` 仅改这三条命令的**默认**权限，OP 经附加/显式赋权不受影响。**仅启动时应用，改 config 需重启**（与其余三个 live 读取的开关不同，文档注明）。
- **风险**：低（切 `op` 后普通玩家失去 `/plugins`/`/ver`/`/help`，老服依赖需自查）。
- **性能**：启动一次性 setDefault+recalculate，运行时零开销。

### 插件泄露主题闭合
fingerprint-hardening 现覆盖 survey 全部主要向量：V1 plugin-channels（被动）/ V2 brand（被动）/ V3a status（被动）/ V5 commands（主动）。V4 Brigadier 命令树复用既有 `spigot.yml` `commands.send-namespaced`，无需新开关。

---

## 批次 53（2026-08-02）：实体数据同步 trackedDataValues 刷新延后到 pairing（0206）

回到性能主线（用户指示优先网络，本批为网络发送管线——实体数据包服务端准备——的 CPU 优化）。compileJava + 全量 test 全绿；JMH 报告：[note/report/perf/2026-08-02-jmh-microbench-batch53.md](report/perf/2026-08-02-jmh-microbench-batch53.md)。**per-dirty-tick 2.16×（CI 不重叠）**。

### 0206 — ServerEntity trackedDataValues 缓存刷新延后（实体数据包发送管线）
- **文件**：`net/minecraft/server/level/ServerEntity.java`（字段/构造/sendDirtyEntityData/sendPairingData）
- **热点**：`sendDirtyEntityData` 每次 dirty 都刷新 `trackedDataValues = getNonDefaultValues()`（全量扫描实体全部数据项，常 20-40 项 + 分配 List），但该字段只在 `sendPairingData`（新观众加入）被读。稳态聚集（战斗/药水效果）下 dirty 频繁、新观众稀少，刷新纯浪费。
- **改法**：删除字段 + 构造初始化 + dirty 刷新；`sendPairingData` 即时 `entity.getEntityData().getNonDefaultValues()` 计算。
- **等价性（与"ServerEntity 增量合并结案"是不同优化）**：dirty DELTA 包仍由 sendDirtyEntityData 经 packDirty 产生发送（行不变）；full 快照仍由 sendPairingData 在新观众时发送，仅改即时计算。**协议时点完全不变**。getNonDefaultValues 读当前值，实体数据仅经 set() 变更，故 pairing 即时算 = 原 last-refresh 字段值；null（全默认）语义保留。
- **基准**：EntityDataPairingBench before 62.524 ± 8.693 → after 28.915 ± 1.798 ns/op（**2.16×**，CI 不重叠）。
- **风险**：低（机制仅移除冗余刷新，不改协议）。

---

## 批次 54（2026-08-02）：网络 pivot — Connection 出站队列 PacketSendAction 去分配（0207）

按用户"优先网络性能"指示，本批起聚焦网络出站路径。compileJava + 全量 test 全绿；JMH 报告：[note/report/perf/2026-08-02-jmh-microbench-batch54.md](report/perf/2026-08-02-jmh-microbench-batch54.md)。**per-queued-packet 2.03×（CI 不重叠）**。

### 0207 — PacketSendAction 消除 delegate lambda + AtomicBoolean→boolean
- **文件**：`net/minecraft/network/Connection.java`（WrappedConsumer + PacketSendAction）
- **热点**：`send` 的非 canSendImmediate 分支（突发负载下 queue 非空时命中）每排队包 `new PacketSendAction` 分配 3 对象：PacketSendAction + delegate lambda（`connection -> connection.sendPacket(packet,listener,flush)` 捕获三元组）+ AtomicBoolean（consumed）。
- **改法**：PacketSendAction 加 listener/flush 字段 + override `accept` 直调 sendPacket（免 delegate lambda，`super(null)`）；WrappedConsumer.consumed 由 AtomicBoolean 降为 boolean。
- **等价性（线程安全）**：`tryMarkConsumed`/`isConsumed` 仅在 `processQueue`（:534/:546）调用；processQueue 经 flushQueue（:507 主线程 play / :511 synchronized login）单线程访问，同 Connection 不并发 → CAS 非必要，boolean 安全。tryMarkConsumed 首次 true/再 false 与 CAS 语义一致。accept 调用序列逐字一致（原 delegate→sendPacket，新直调 sendPacket 同参）。`WrappedConsumer(action)`/`WrappedConsumer(Connection::flush)` 仍用非空 delegate 不受影响。
- **基准**：PacketSendActionBench before 5.379 ± 0.314 → after 2.651 ± 0.245 ns/op（**2.03×**，CI 不重叠）；每排队包 3 对象→1 对象。
- **风险**：低（单线程不变量已实证；accept 调用序列逐字一致）。

### 网络 pivot 后续
- **高风险高价值（需授权 + live 验证）**：`Connection.sendPacket` 行451 `execute(() -> doSendPacket(...))`——主线程发包每包分配 lambda（网络出站最高频分配点）。消除会让 `sentPackets++`（普通 int）跨线程，需 live 压测。auto-driven 不盲改。
- 待评估：PacketBundleUnpacker 每包 Consumer（零风险低价值）、VecDeltaCodec base 缓存（零风险低价值）。

---

## 批次 55（2026-08-02）：网络 pivot 续 — PacketBundleUnpacker 非 bundle 包免 Consumer（0208）

compileJava + 全量 test 全绿；JMH 报告：[note/report/perf/2026-08-02-jmh-microbench-batch55.md](report/perf/2026-08-02-jmh-microbench-batch55.md)。**1.23×（CI 不重叠，EA 未消除）**。

### 0208 — PacketBundleUnpacker.encode 非 bundle 包免 list::add Consumer
- **文件**：`net/minecraft/network/PacketBundleUnpacker.java` `encode`
- **热点**：IO 线程每出站包 `this.bundlerInfo.unbundlePacket(packet, list::add)`——`list::add` 是捕获局部 list 的方法引用，每求值分配一个 Consumer，经虚调用传入。非 bundle 包（99%）实际只执行 `consumer.accept(packet)`=`list.add(packet)`。
- **改法**：`if (packet instanceof BundlePacket) unbundlePacket(packet, list::add); else list.add(packet);`。
- **等价性**：unbundlePacket 对非 bundle 包走 else `consumer.accept`==list.add（type()≠bundle type）；bundle 走 unbundlePacket（内部精确判定）。`instanceof BundlePacket` 路由结果逐字一致，仅免非 bundle 路径的 Consumer 分配。
- **基准**：BundleUnpackerBench before 4.504 ± 0.291 → after 3.669 ± 0.241 ns/op（**1.23×**，CI 不重叠）。EA 未消除（CI 不重叠证真实，非 0155 那种 EA 伪影）。
- **风险**：零。

### 网络 pivot 状态评估
已落地网络出站路径：0203 区块选块去装箱（10×）、0206 实体数据同步（2.16×）、0207 PacketSendAction 去分配（2.03×）、0208 帧编码免 Consumer（1.23×）。**剩余安全网络点价值低**（VecDeltaCodec 实体位置包 base 缓存，零风险低价值）。**最高价值网络点 `Connection.sendPacket` 行451 send-lambda 仍需授权 + live 并发验证**（消除会让 sentPackets++ 跨线程）。建议：要么授权攻坚 send-lambda，要么网络主线闭合、转回聚集/其他。

---

## 批次 56（2026-08-02）：网络 pivot — Connection.sendPacket 消除 send-lambda（0209，高风险用户授权）

用户授权攻坚最高价值网络点。compileJava + 全量 test 全绿；JMH 报告：[note/report/perf/2026-08-02-jmh-microbench-batch56.md](report/perf/2026-08-02-jmh-microbench-batch56.md)。**1.29×（CI 不重叠），主价值是消除网络出站最高频 per-packet lambda 分配（GC 压力，微基准测不出）**。**线程改动推理验证（用户接受无 live 压测）**。

### 0209 — Connection.sendPacket 消除 per-outbound-packet lambda
- **文件**：`net/minecraft/network/Connection.java` `sendPacket`
- **热点**：主线程发包（非 netty event loop，服务端发包常态）原每包 `execute(() -> doSendPacket(...))` 分配 lambda——网络出站最高频分配点。
- **改法**：去掉 inEventLoop 分支与 execute(lambda)，无条件直调 `doSendPacket`。
- **线程安全论证（逐项）**：`channel.write/writeAndFlush`+`addListener` netty 跨线程安全且按 channel 串行化保序；`getPlayer()` 读 volatile packetListener；`isConnected()` 读 channel（send():403 已同模式）；`sentPackets++` 在 sendPacket 调用方线程**不在** doSendPacket，消除 lambda 不改其语义；异常路径 disconnect 是既有跨线程模式。详见报告表格。
- **基准**：SendLambdaBench before 1.065 ± 0.087 → after 0.827 ± 0.047 ns/op（**1.29×**，CI 不重叠）。EventLoop 经接口虚调用建模防 EA 消除 lambda。
- **价值定位**：微基准 modest，真实收益是 per-outbound-packet 分配消除降低高吞吐下 GC 压力（微基准小堆无压力测不出）。
- **残留风险**：未做 live 压测（用户知悉）；极端并发下异常-disconnect 路径理论竞态罕见且在消亡连接；单补丁可独立 revert。
- **风险**：中（线程改动推理验证，非 live 实证）。

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

---

## 批次 57（2026-08-02）：稳定性修复——实体爆炸卡服防护 + 回退 0209

> 性质：**稳定性/防护修复**（非 JMH 性能优化）。触发：用户报告领地（Residence）保护区域破坏草丛 + 掉种子插件在 BlockBreakEvent 被取消时仍无限 `world.dropItem` 生成种子 ItemEntity → 实体爆炸 → 主线程卡死（玩家均超时，经确认非异常崩溃、无堆栈）。详见 [release 0.32.1](release/0.32.1.md)。

### 根因（逐行确认，排除 Papo 回归）
1. `ServerPlayerGameMode.destroyBlock`：BlockBreakEvent 取消时走"不掉落、不破坏、只发 BlockUpdate 纠正客户端"——左键破坏路径 **Papo 未改**（门控只改右键 interact）。
2. 种子是插件自己 `dropItem`（绕过 `captureDrops`），Papo 无法干预插件。
3. ItemEntity 合并/拾取/tick 全原版，**Papo 未破坏合并**（`callItemMergeEvent` 零监听器快路返回 true=允许合并）。
4. **关键防护缺失**：Moonrise tick 所有激活实体，无 Spigot 的 `max-tick-time.entity`、无任何 per-chunk/per-type 实体上限（`spawn-limits` 不管 ItemEntity——MISC 被 `CraftSpawnCategory` 排除；`altItemDespawnRate` 是时间限制非数量）。
5. 是 Papo 与原版 Paper 共有的防护缺失，非 Papo 回归。

### 0209（新）— per-chunk ItemEntity 数量上限（config-gated 默认 -1 关闭）
照抄 Moonrise 既有的同构参考实现 `enderPearlChunkCount`（per-chunk 末影珍珠计数，[ServerEntityLookup.java](../paper-server/src/minecraft/java/ca/spottedleaf/moonrise/patches/chunk_system/level/entity/server/ServerEntityLookup.java)）：
- `ServerEntityLookup` 加 `papoItemEntityChunkCount`（`Long2IntOpenHashMap`），在 `addEntityCallback`/`removeEntityCallback`/`entitySectionChangeCallback` 三处维护（统一咽喉点，每实体恰好一次，覆盖全部 7 条 discard + 卸载 + 换维度）。
- `ServerLevel.addEntity` 在 captureDrops 块之后、`addNewEntity` 之前检查：目标 chunk ItemEntity 计数 ≥ cap 时丢弃新 item（返回 false）。**放 captureDrops 之后**使合法方块破坏掉落（被捕获）不受影响，只约束未捕获掉落（如插件 `dropItem`）。
- 配置 `paper-world.yml`：`entities.spawning.item-entity-limit-per-chunk`，默认 -1（兼容红线，vanilla 行为）。
- **等价性**：默认 -1 时 `addEntity` 整块跳过（`cap >= 0` 守卫），行为同原版；计数器维护是纯增量（不动原 enderPearl 逻辑）；跨 chunk 移动已处理，极端边界计数可能不归零但 add 检查严格性保证防护有效（计数偏低=更宽松不误杀）。

### 回退原 0209（send-lambda 消除）— voidPromise 跨线程缺陷
原 0209 把 `doSendPacket` 从 event-loop 移到调用方线程，而 `doSendPacket` 用 `this.channel.voidPromise()`（[Connection.java:483/485](../paper-server/src/minecraft/java/net/minecraft/network/Connection.java)）——netty 要求 `voidPromise()` 仅 event-loop 线程使用（无同步保护），0209 破坏此前提，构成跨线程并发缺陷。0.32.0 release note 自标"未做 live 压测；单补丁可 revert"。本批回退，恢复 `sendPacket` 的 `inEventLoop()` 判定，`voidPromise` 回到 event-loop 安全使用。无功能损失（仅恢复一个 per-outbound-packet lambda 分配）。**注**：该缺陷症状为 netty 异常/断连，不匹配本次"主线程静默卡死"——本次卡死根因是实体爆炸（新 0209 防护），回退原 0209 是顺带消除一个确凿隐患。

---

## 批次 58（2026-08-20）：网络带宽 + 出站编码延时（三路 survey 交叉印证）

主题：**带宽（更少冗余出站字节）+ 延时（出站编码路径 CPU/分配/尾延迟）**。三路 survey（压缩管线 / 出站包量冗余 / flush 调度延时）交叉印证后落地 7 个补丁（0210-0216）。全部 compileJava BUILD SUCCESSFUL、三个自检 main 全部 ALL OK、applyPatches 干净应用（216 feature 补丁）。JMH 报告：[note/report/perf/2026-08-20-jmh-microbench-batch58.md](report/perf/2026-08-20-jmh-microbench-batch58.md)。

### survey 关键否定结论（先记录，避免重复勘察）
- **压缩层"带宽优化"在字节等价约束下不存在**：线上字节由内容 + level（paper-global `misc.compression-level`）+ threshold（server.properties）完全决定，后两者已是既有配置旋钮。压缩层可动的只有 CPU/分配/正确性（D1-D3 即此）。
- **TCP_NODELAY 已设**（ServerConnectionListener.java:92）；**每玩家每 tick 恰一次 flush**（vanilla suspend/resume 语义，tickChildren:1755/1858）；FlushConsolidationHandler 默认参数已最优（256/true，netty 4.2.7 字节码核实）；`paper.explicit-flush` 默认关。**实体追踪序列 bundle 化否决**（协议可观察插入 delimiter，且 tick 尾单 flush 已 writev 合并，无 syscall 收益）。
- **实体同步/光照/区块/容器/粒子/BossBar 主干全部已有变更门控或位掩码压缩**（survey 逐文件核实），无"整类大流量重复发送"级目标；可做的等价带宽项集中在记分板域（C1-C3）。
- ensureCompatible 在服务端正常路径（direct buffer）全程 retain 零拷贝；解码侧精确分配——**不存在想当然的 heap→direct 转拷**（netty 4.2 preferDirect 默认已翻转，javap 实证）。

### 0210 — PlayerTeam 九个 setter 等值门控（C1，带宽）
- **文件**：`net/minecraft/world/scores/PlayerTeam.java`（setDisplayName/setPlayerPrefix/setPlayerSuffix/setAllowFriendlyFire/setSeeFriendlyInvisibles/setNameTagVisibility/setDeathMessageVisibility/setCollisionRule/setColor）
- **热点**：TAB/记分板类插件常周期性重设 prefix/suffix/displayName——原实现九个 setter 全部**先赋值、无条件** `scoreboard.onTeamChanged(this)`，下游 `ServerScoreboard.onTeamChanged` → `broadcastAll(METHOD_CHANGE 参数包)` 发给记分板全体玩家 + `updateTeamWaypoints` + setDirty。等值重设的包对客户端是幂等重放，纯浪费带宽。
- **改法**：每个 setter 开头等值短路。布尔/枚举直接 `==`；Component 用**实例守卫 + 内容相等**双条件：`name != this.displayName && name.equals(this.displayName)` 才跳过——**同实例重设保持广播**（NMS MutableComponent 原位变异后靠重设刷新的手段保持 vanilla 奇偶性；adventure/bukkit API 每次构造新实例，等值新实例被跳过时客户端已持有相等内容，可证状态等价）。prefix/suffix 的 null→EMPTY 归一化在等值判定内完成。
- **等价性**：`Scoreboard.onTeamChanged` 基类空实现；值相同 → 客户端 team 状态逐字节不变；跳过的 setDirty 不改变存档内容（值没变）；`unpackOptions` 加载期连调两个 setter 的 0 广播在无接收者时点无观察面。自检（ScoreboardGateSelfCheck）：等值新实例 0 广播/真变更一致/同实例仍广播/null 归一化/unpackOptions 默认 flags 全场景 ALL OK。
- **风险**：低（唯一可观察差异是抓包插件看到更少的幂等包；Bukkit API 不承诺"set 即发包"）。
- **价值**：中（TAB 插件重设周期 × 全体玩家 × 50-150B/包）。

### 0211 — Objective 四 setter + Scoreboard.numberFormatOverride 等值门控（C2，带宽）
- **文件**：`net/minecraft/world/scores/Objective.java`（setDisplayName/setRenderType/setDisplayAutoUpdate/setNumberFormat）+ `net/minecraft/world/scores/Scoreboard.java`（numberFormatOverride）
- **改法**：同 0210 同款门控（Component 实例守卫 + equals；枚举/布尔 ==；NumberFormat 为 record 实现 Objects.equals）。setDisplayName 等值时**同时跳过 formattedDisplayName 重算**。numberFormatOverride 采用同文件 display() setter 的 CraftBukkit 既有门控形态（`mutableBoolean.isTrue() || !Objects.equals(...)`，新建 score 仍必发）。
- **等价性**：`Scoreboard.onObjectiveChanged` 基类空实现；仅 trackedObjectives（挂显示槽）时广播；值相同 → 客户端侧边栏状态不变。自检全场景 ALL OK。
- **风险**：低。

### 0212 — ServerScoreboard.setDisplayObjective 同包双发去重（C3，带宽）
- **文件**：`net/minecraft/server/ServerScoreboard.java`
- **热点**：旧 objective 仍显示于其他槽（`getObjectiveDisplaySlotCount > 0`）且新 objective 已 tracked 时，原实现把**同一个** `ClientboundSetDisplayObjectivePacket(slot, objective)` 连发两次（L97 与 L105，同 slot 同 objective，逐字节相同）。
- **改法**：布尔 `papoBroadcastDisplay` 记录本次调用是否已发过 `(slot, objective)`，第二处跳过。其余路径（未追踪新 objective 的 startTracking / 旧 objective 不再显示的 stopTracking / 同值重设刷新）包数与 vanilla 完全一致。
- **等价性**：两包逐字节相同、同 tick 相邻应用，客户端"设置槽位显示"幂等——发一次终态相同。自检：双发路径恰少 1 包且内容相同、末状态全场景一致。
- **风险**：极低。

### 0213 — Varint21LengthFieldPrepender 精确预分配（A1，延时）
- **文件**：`net/minecraft/network/Varint21LengthFieldPrepender.java`
- **热点**：每出站包（压缩与未压缩路径都是）帧编码。原版未覆写 allocateBuffer → `ioBuffer()` 256B 起步 + 首个 `ensureWritable(3+n)` 触发一次池化重分配（跳 2 的幂，超额）。
- **改法**：覆写 `allocateBuffer` → `ioBuffer(3 + msg.readableBytes())`（@Sharable 无状态覆写，只读 msg）。帧字节逐字节不变（自检 ALL OK）。
- **基准**：1526 ± 1251 → 670 ± 5 ns/op（均值 2.28×；**方差崩缩 ±1251→±5 是主价值**——池化重分配双峰消失，每包成本确定化，尾延迟收益大于均值收益）。
- **风险**：零。

### 0214 — PacketEncoder 按包类尺寸提示（A2，延时）
- **文件**：`net/minecraft/network/PacketEncoder.java`
- **热点**：每出站包序列化。原版 256B 起步，codec 渐进写入触发增长链（每次增长 = 池化 reallocate + 已写前缀整体拷贝）；区块/光照包（压缩前 30-90KB）跑图/登录突发期每包 ~8 次增长拷贝（累计额外 memcpy ≈ 包大小）。
- **改法**：per-handler `Object2IntOpenHashMap<Class<?>>`（每连接每协议新建，单 event loop 无并发）缓存"上次编码尺寸 ≥8KB 的包类"，allocateBuffer 命中提示即分配到位；编码完成记录。不淘汰（只有结构性大包类会进表；同类小包后续超额分配是池化 size-class miss，绝不拷贝）。
- **等价性**：容量不上线、编码字节不变（自检 ALL OK）；JVM 退出前表尺寸受大包类数约束（几十个）。
- **基准**：2329 ± 51 → 1592 ± 5 ns/op（**1.46×**，32KB 包模型，CI 不重叠；真实区块包更大收益更高）。
- **风险**：零。

### 0215 — CompressionEncoder 输出按 DEFLATE 实测上界分配（D1，延时/尾延迟）
- **文件**：`net/minecraft/network/CompressionEncoder.java`
- **热点**：压缩路径输出缓冲原版 `n + 1`——对不可压缩 payload（DEFLATE 转存储块）放不下：native libdeflate（生产 Linux）`deflate` 返回 0 → 容量翻倍 + **整个输入重新压缩一遍**（LibdeflateVelocityCompressor.deflate 字节码实证 resize-retry 循环）；JDK 回退（Windows）ensureWritable(8192) 续压。
- **改法**：初始容量 `n + n/2048 + 32`。
- **判例（首版公式被自检证伪）**：首版按"5B/65535B 存储块"理论推导 `n + n/4096 + 16`——DeflateBoundBench 自检在 256KiB 随机数据实测膨胀 +86 > 界 +80，**BOUND INSUFFICIENT**。实测规律（JDK zlib level 6，随机输入 64KiB-4MiB 四点）：膨胀 = **5B/16384B 窗口 + 6B**（`n + 5*ceil(n/16384) + 6`，zlib 头 2 + adler 4；zlib 的 lit_bufsize=16384 决定块节奏）。修正版以 ~1.6× 裕量覆盖两后端。**教训：理论上界推导必须过实测校验。**
- **等价性**：容量仅分配策略，线上字节与帧格式逐字节不变；重试路径保留为兜底。自检（修正后 ALL OK）：1KiB-1MiB 全尺寸 after 零扩容、before 每尺寸都触发重试（触发面实证）。
- **基准**：本机（JDK 回退）持平 1.004×（预期——收益在 native 重压缩尖峰消除，本机无 native 测不到）。
- **风险**：零。

### 0216 — setupCompression 重跑复用 + compression level clamp（D2+D3，正确性）
- **文件**：`net/minecraft/network/Connection.java` + `CompressionDecoder.java`（新增 package-private `papoCompressor()`，同包直取）
- **D2 泄漏修复**：重复 `setupCompression(threshold≥0)` 时，新建的 VelocityCompressor 被 decoder 的 setThreshold（仅在自身 compressor 为 null 时收新）与 encoder 的 setThreshold（从不收新）**双双拒收** → 原生 deflate/inflate 上下文孤儿泄漏。修复：decoder 已持有活实例时直接复用（不再创建新的）。默认路径（每连接一次）行为不变。
- **D3 clamp**：`misc.compression-level` 配 10-12 在 JDK 回退平台（无 native，如 Windows）`new Deflater(10)` 抛 IAE → **登录即断连**。修复：create 抛 IAE 时以 clamp 到 [1,9] 的级别重试。合法配置（native [1,12] / 回退 [-1,9]）逐字不变，仅原本崩溃路径改变。
- **风险**：零-低。

### 附：0163-0180 垃圾重命名第 4 次复发 → 根除（工程项）
本批 rebuildPatches 再次触发 0163-0180（实际 0055-0180 共 127 个）中文 Subject 头 → 垃圾重命名。按 build.md 恢复法恢复后，本轮**执行了留置两批的根除项**：[note/fix_patch_subjects.py](fix_patch_subjects.py) 把 127 个补丁的 RFC2047 编码 Subject 头改写为与文件名 slug 往返闭合的干净英文（字节级只动头部 Subject 区）→ applyPatches 重建内部仓库（干净 subject 流入内部提交）→ rebuildPatches 验证文件名稳定。此后全量 rebuildPatches 不再复发（验证记录见 build.md 2026-08-20 条目）。

---

## 批次 59（2026-08-20）：零拷贝出站帧化（0217）

主题延续：网络带宽 + 延时。批次 58 暂缓清单中 survey1 候选 2（帧头合并免拷贝）的结构性落地。compileJava BUILD SUCCESSFUL、EmbeddedChannel 自检 ALL OK（字节级矩阵 + varint 全档位 + 万次引用计数）、applyPatches 干净应用（217 feature 补丁）、全量 test BUILD SUCCESSFUL。JMH 报告：[note/report/perf/2026-08-20-jmh-microbench-batch59.md](report/perf/2026-08-20-jmh-microbench-batch59.md)。

### 0217 — 零拷贝出站帧化：headroom 前缀 + 身份标记直通
- **文件**：新增 `net/minecraft/network/PapoOutboundFraming.java` + `PacketEncoder.java` + `CompressionEncoder.java` + `Varint21LengthFieldPrepender.java`
- **热点**：出站三阶段每包的整包拷贝——① PacketEncoder 产出（0214 已预分配）；② CompressionEncoder 低于阈值路径把整包拷入新 direct buffer（VarInt(0)+payload）；③ prepender 再把整包拷入帧 buffer（帧长 varint + payload）+ 一次池化分配。threshold=256 默认下，绝大多数小包（实体移动/音效/心跳）走 ②+③ **双拷贝双分配**；大包（区块）走 ③ 一拷贝。
- **改法**：
  1. `PapoOutboundFraming.HEADROOM = 6`（帧长 varint ≤3 + 数据长 varint ≤3）；PacketEncoder 的 allocateBuffer 预留 headroom（`setIndex(6,6)`，readableBytes 仍等于内容尺寸——finally 的 PacketTooLarge 检查与一切下游消费者均从 readerIndex 读），encode 成功后经 channel attr 发布 buffer 身份（仅当 prepender 是 Varint21LengthFieldPrepender，防 memory 连接的 LocalFrameEncoder 侧滞留引用）。
  2. CompressionEncoder 覆写 `write`：身份匹配 → 低于阈值路径**原地写 1 字节数据长 varint（0）进 headroom 并直通**（0 拷贝 0 额外分配）；≥阈值路径产出带 headroom 的压缩输出（VarInt(i)+压缩数据从 6 起写）再发布直通。身份不匹配（插件注入的外来 buffer/陈旧标记）→ 清标记走原版拷贝 encode。
  3. prepender 覆写 `write`：身份匹配 → 帧长 varint **右对齐回填进 headroom**（`writeVarIntBackwards`，字节发射与 VarInt.write 逐位一致）+ readerIndex 回拨直通（0 拷贝 0 分配）；不匹配 → 原版拷贝 encode（0213 的精确预分配仍在）。
- **等价性（逐项）**：线上字节 = [帧长 varint][数据长 varint(0 或 i)][载荷]，两路径逐字节一致（EmbeddedChannel 真实 netty write 管线自检：尺寸 {1,100,255,256,257,1K,4K,16K,100K} × {随机,全零} 矩阵全等 + 帧结构可解析）；三阶段均在 channel 单 event loop 的同一次 write 遍历内执行，attr 无并发（顺序：encoder→compress→prepender，Connection.configureSerialization:698-699 + setupCompression addAfter("prepender","compress") 的装配序实证）；CipherEncoder 的 `NativeVelocityCipher.process` 以 `memoryAddress()+readerIndex()` 起算原地加密 readable 区（javap 实证），headroom 前缀不参与加密 ✓；无 cipher（离线模式）时 socket write 从 readerIndex 起 ✓；引用计数：直通=所有权转移（netty invokeWrite0 对同步下游异常自行 release 传递中的 msg，javap/netty 源码语义），异常路径 finally 恰好一次 release（初版有双重释放缺陷，引用计数审计后重写为所有权内聚结构）。
- **回退面**：身份不匹配全量回退旧拷贝路径——插件注入中间 handler 换 buffer、协议切换瞬态、任何未预期形态都退回 vanilla 行为。
- **基准**：OutboundFrameBench（EmbeddedChannel 真实 netty write 管线）belowThreshold（100B）469.7 ± 5.1 → 285.7 ± 10.3 ns/op（**1.64×**，CI 不重叠——每包省 2 次全量拷贝 + 2 次池化分配，绝大多数游戏包走此路径）；aboveThreshold（16KB 随机）均值正向 1.07× 但 CI 重叠（压缩 ~116µs 主导，拷贝节省在噪声内，如实记录；大包主收益已在 0214/0215）。
- **风险**：低-中（管线结构改动但回退面完备、字节等价有真实 netty 管线自检实证；引用计数经万次压力自检）。

### 踩坑（已记 build.md）
- EmbeddedChannel 构造参数序 = head→tail，出站遍历为 tail→head——**装配顺序必须与真实管线 list 序一致**（[prepender, compress, encoder]），首版装反导致 compress/prepender 全部直通、自检立刻抓包失败。
- netty 4.2 的 MessageToByteEncoder 在 **netty-codec-base**（非 netty-codec）；benchmark 依赖与 run.sh 已补 netty-transport + netty-codec-base。

---

## 批次 60（2026-08-20）：批量分派否决 + send 快路径重排 + pairing 预尺寸（0218-0219）

主题延续。本批主体是**否决评估**（B1 批量 event-loop 分派——两轮实测 + 机制证伪后撤回），落地 2 个小补丁。JMH 报告：[note/report/perf/2026-08-20-jmh-microbench-batch60.md](report/perf/2026-08-20-jmh-microbench-batch60.md)。

### 否决评估：主线程每包 eventLoop().execute 批量化（未落地，证据留档）
- **候选**：sendPacket 非 event-loop 分支每包 `execute(lambda)` → MPSC 队列 + 每突发一次排水任务（CAS 边界；event-loop 发送者保持 vanilla inline；每条目独立异常隔离）。实现完成、自检 ALL OK 后被实测否决。
- **两轮实测**（BatchedDispatchBench，真实 NioEventLoopGroup(1)，32 包突发）：CLQ 版 **回退 4.8×**（462→2209ns，跨线程 CAS+每元素 Node 分配）；换 netty shaded MpscChunkedArrayQueue 修正版仍**劣化 1.49×**（534→793ns，CI 部分重叠）。
- **机制前提证伪（关键）**：原假设"每包 execute = 每包一次 selector 唤醒"。复查 netty：`NioEventLoop.wakeup` 带 CAS 守卫（每 park 窗口至多一次），event loop 以 64 任务/批处理任务队列——vanilla 的逐包任务机制早已被 netty 内部摊销，可省的只剩每包一个 TLAB 便宜的 lambda。
- **判例**：①"消除每任务开销"类候选必须先核实运行时是否已内建摊销（纸面逐包成本可能早就不存在）；②跨线程队列选型必须实测（CLQ vs MPSC 差 4.8×）；③与 0209 同类的跨线程复杂度，实测无收益一律撤。
- 内部提交已摘除（rebase --onto），基准类与两轮结果留档作复评依据。

### 0218 — Connection.send 立即发送判定析取重排
- **文件**：`net/minecraft/network/Connection.java` `send`
- **热点**：立即发送判定 `canSendImmediate(...) || (isMainThread && isReady && queueEmpty && noExtra)`——play 阶段主线程发送（常态）时，**非白名单包**（实体移动/数据/方块/区块更新，绝大多数流量）先走完 ~20 个 instanceof 全 miss 才落到恒真主线程臂（InnerUtil.java:916-935）。
- **改法**：两臂均为无副作用纯谓词且导向同一 sendPacket 调用，求值顺序不可观察——廉价主线程臂（线程检查+3 字段读）提前短路。异步线程发送路径行为逐字不变。
- **基准**：0.923 ± 0.006 → 0.534 ± 0.004 ns/op（**1.73×**，CI 不重叠；绝对节省 ~0.4ns/包，JIT 后 instanceof 链本身已是亚 ns 类比较——纯重排零风险白捡）。布尔等价矩阵自检 ALL OK。
- **基准判例**：首版 static final 常量载荷被 JIT 整链常量折叠（伪平 0.48ns）；输入经 @State 非终态字段后真实差异显现——谓词类基准输入必须经 state 字段。
- **风险**：零（纯求值序重排）。

### 0219 — ServerEntity.addPairing 配对包列表预尺寸（批次 50 遗留项清账）
- **文件**：`net/minecraft/server/level/ServerEntity.java` `addPairing`
- **改法**：`new ArrayList<>()`（容量 10）→ `new ArrayList<>(4)`——sendPairingData 常态产出 2-4 个包，区块加载突发期每次实体配对省超额分配。容量不经 List API 可观察。
- **风险**：零。

---

## 批次 61（2026-08-20）：入站零拷贝帧提取 + 死仪表门控 + tick 尾微项（0220-0224，编号以补丁文件为准）

两路新 survey（入站包处理路径 / tick 尾 flush 窗口 + 带宽监控接线）驱动。JMH 报告：[note/report/perf/2026-08-20-jmh-microbench-batch61.md](report/perf/2026-08-20-jmh-microbench-batch61.md)。

### survey 关键否定结论（勿重复勘察）
- **入站主线程投递已摊销**：Paper `scheduleIfPossible` 直接传 packet+listener（无逐包 Runnable/execute 任务），`pollTask` 集成（tick 间等待循环/tick 首双段排空）+ unpark 投机唤醒使处理连续化——不存在批次 60 型批量化机会。
- **RunningOnDifferentThreadException 零成本**：单例 + 构造时 setStackTrace(空) + fillInStackTrace 覆写返回 this——与 JIT OmitStackTraceInFastThrow 无关。
- **tick 尾窗口在 58-60 后分配维度基本干净**：flushChannel 正常路径无 WrappedConsumer（垂死连接才走）；PlayerList.broadcast 热路径索引循环零分配；bundle 方向已闭合。

### 0220 — PlayerChunkSender k-近邻 scratch 数组字段化
- **文件**：`net/minecraft/server/network/PlayerChunkSender.java`（collectChunksToSend 突发分支）
- **改法**：0203 引入的原语 k-近邻每次调用 `new long[floor]`/`new int[floor]` → 实例字段 + grow-on-demand（每 player 单实例、单主线程调用点、无重入；仅 [0, sel) 被读且每次全量重写，stale 数据不可达）。
- **等价性**：选择/排序逻辑逐行不动，仅缓冲复用；跑图/登录突发期每 player-tick 省 2 个 ≤64 长数组。
- **风险**：零。

### 0223 — Connection flush 任务缓存
- **文件**：`net/minecraft/network/Connection.java`（flush）
- **改法**：非 eventLoop 分支每 tick 每 player 捕获 `() -> this.channel.flush()` → 每连接一个 `Runnable` 字段。执行时读 channel（channelActive 早于任何 flush 设置）与原 lambda 逐字一致；任务在 event loop 上重入 flush() 的 inEventLoop 快路。
- **风险**：零。

### 0221/0224 — 死仪表门控（0221 PacketProcessor 主体 + 0224 Connection.tick 伴随半部）
- **文件**：`net/minecraft/network/PacketProcessor.java`（0221）+ `Connection.java`（tick，0224）
- **热点**：Paper "detailed watchdog information" 簿记——**主线程每入站包** 2 个 ConcurrentLinkedDeque Node 分配 + 3 次 CAS；全仓库零读取方（getCurrentPacketProcessors/getTotalProcessedPackets 无任何调用，watchdog 线程不读包状态）。
- **改法**：`static final boolean PAPO_TRACK_PACKET_PROCESSING = false` 包住三处写入，JIT 移除；字段/getter 保留形状。
- **基准**：15.701 → 0.258 ns/op（**~15ns/包**，主线程直接减负）。
- **风险**：低（唯一理论面是外部插件反射读内部字段——非 API 无兼容承诺）。

### 0222 — Varint21FrameDecoder 帧提取 readBytes → retainedSlice（入站零拷贝）
- **文件**：`net/minecraft/network/Varint21FrameDecoder.java`
- **热点**：原版对每一入站帧分配新 buffer + memcpy 全部载荷——全部入站流量在 splitter 处完整拷贝一次。
- **改法**：`retainedSlice + skipBytes`（netty LTFBD.extractFrame 同型）。下游只读已核（CompressionDecoder 仅索引/释放、PacketDecoder codec 只读）；父 cumulation 引用计数存活至同链最后 slice 释放；协议切换窗口 FlowControlHandler 排队至多钉住一个读批次（LTFBD 同语义）。
- **基准**：gc.alloc.rate.norm **6774.8 → 704.0 B/op（9.6× 分配消除）**；紧密循环时间 93× 为 GC 摊销放大（诚实定位：每帧省 1 分配 + 1 memcpy，真实收益为与入站流量成正比的 GC 压力削减）。自检（帧内容一致/半帧累积/万帧引用计数）ALL OK。
- **风险**：低-中（池化内存共享语义变化对注入管线的外部插件不可见；LTFBD 工业先例）。

### 暂缓/否决（批次 61 survey）
- ~~**出站带宽监控**~~ **批次 62 已交付（0225 + /paper netstat 直提交）**。
- RegistryFriendlyByteBuf 复用（~130 codec 逃逸审计）、ListenerAndPacket 池化（jctools 依赖+队列语义保持）：低价值高风险，不做。
- 零写入跳过 flush 任务（插件直写 channel 的延迟边界）：中风险暂缓。

---

## 批次 62（2026-08-20）：/paper netstat 出站/入站带宽监控（0225，纯观测）

批次 61 survey 预定的独立交付。报告：[note/report/perf/2026-08-20-jmh-microbench-batch62.md](report/perf/2026-08-20-jmh-microbench-batch62.md)。

### 0225 — 每连接 wire 字节计数 + 帧编解码接线（补丁）
- **文件**：`Connection.java`（计数器字段 + tickSecond 快照 + getter + configureSerialization 穿线）+ `Varint21LengthFieldPrepender`（两条帧化路径计数）+ `Varint21FrameDecoder`（入站对称计数）+ `ServerConnectionListener`（连接创建上移传计数器）
- **机制**：出站计 `载荷+帧长 varint`（压缩后/加密前 = 精确 wire 字节）；入站同式对称；每连接 AtomicLong（eventLoop 无竞争 add）+ tickSecond 每秒 getAndSet 快照（与 averageSentPackets 同相位）+ 累计总量。
- **等价性**：纯观测（计数器无行为耦合）；configureSerialization 三调用点全更新（服务端/客户端接入、内存连接不计）；连接创建上移 inert。自检（NetstatCounterSelfCheck）：出站 7 尺寸计数==wire 字节、入站多帧+半帧、窗口清零守恒 ALL OK。
- **风险**：零-低。

### 直接提交 — NetstatCommand + PaperCommand 注册
- `/paper netstat [topN|all]`（默认 top 10 按出站 B/s 降序）：全服 out/in B/s + totals + 每玩家 out/in B/s、pkt/s（复用 averageSentPackets）、累计。权限 `bukkit.command.paper.netstat`（Paper 既有自动注册，OP 默认）。
- **价值**：带宽主题的度量基础——服主可直接看到每玩家真实线上字节（压缩后），为后续带宽优化（如压缩级别调优、异常流量定位）提供数据。

---

## 批次 63（2026-08-20）：压缩级别选型指南（带宽主题运营收尾，无代码变更）

主题五批代码闭合后的运营闭环：netstat（测）→ compression-level（调）→ 本指南（怎么调）。报告：[note/report/perf/2026-08-20-compression-level-guide.md](report/perf/2026-08-20-compression-level-guide.md)。

### 核心数据（CompressionLevelBench，三类代表性载荷 × level 1/3/6/9）
- **区块型载荷（出站大头）level 6→9 压缩比零增益（4.90×→4.88×）而 CPU 2.5×**——调高级别是纯 CPU 浪费。
- **level 3 是 CPU 高效前沿**：省 66% 压缩 CPU，仅 +9% 字节（4.43× vs 4.90×）。
- 光照/低熵载荷对 level 完全不敏感（1.69-1.72× 持平）。
- 诚实边界：JDK 回退后端绝对时间不可套用 libdeflate（快约一个量级）；相对趋势两后端间可迁移。
- roundtrip 完整性自检 ALL OK。

### 暂缓（批次 58 survey 产出，留批次 59）
- **帧头合并免拷贝**（survey1 候选2，结构性大头）：CompressionEncoder 预留 3 字节头 + 帧长回填，prepender 对已帧化 buffer 直通——需管线结构改动与 marker 机制（channel attr 或包装类），config 门控，留批次 59 专项。
- **主线程每包 eventLoop().execute → 按 tick 批量提交**（survey3 B1，高价值）：与已回退的 0209 同区域（跨线程 write 语义），但 drain task 在 event loop 内执行 → voidPromise 安全；需 config 门控 + 逐包 promise/listener 语义保持，留批次 59 专项。
- **出站带宽监控 handler**（survey1 候选7，纯观测）：BandwidthDebugMonitor 仅客户端入站；服务端出站计数 handler 可为后续带宽优化提供数据，留后续。
- **CompositeByteBuf 免拷贝帧**：否决——native cipher DIRECT_REQUIRED，composite 会强制 cipher ensureCompatible 整包拷贝，加密连接下收益归零。
- **压缩移出 event loop**：否决——0209 回退教训；大包 level-6 尖峰靠 0215 上界缓解。

---

## 批次 64（2026-08-20）：加入链路静态包缓存 + 双重读盘去重（0226/0227）

加入链路（登录→配置→出生就绪）survey 定案三候选落地（缓存对 tags+注册表为一件补丁）。报告：[note/report/perf/2026-08-20-jmh-microbench-batch64.md](report/perf/2026-08-20-jmh-microbench-batch64.md)。

### 0226 — join 静态配置包（tags + 注册表）按 reload 纪元缓存
- **文件**：新增 `PapoJoinPacketCache.java` + `SynchronizeRegistriesTask.java` + `PlayerList.java`（reloadTagData）
- **热点**：每 join 主线程重建 ClientboundUpdateTagsPacket（vanilla 实测 625 tag / 4377 条目 id 查找）+ 24 个 ClientboundRegistryDataPacket（371 条目；**known-packs 不匹配客户端（典型 ViaVersion）全量 NBT 编码数毫秒 + ~100KB wire**）。内容两次 reload 间逐字节恒定（失效收口唯一：reloadTagData）。
- **改法**：静态缓存（tags 包 + 两分支注册表包列表）；reloadTagData 以新广播实例换缓存并清注册表缓存（免费兜底 worldgen 理论不可变）。
- **等价性**：包对象跨玩家共享为 vanilla 既有行为（broadcastAll 同实例先例）；两包未覆写 Paper per-send 钩子（grep 实证默认 no-op）；时点不变。自检（构建一次/复用同实例/reload 失效重建）ALL OK。
- **基准**：模型 210,270 → 0.96 ns/op（**~0.21ms/join 主线程消除**，survey 实测构建估 0.3-1ms；Via 分支另省数毫秒+100KB）。
- **风险**：低（残留：插件运行期直改注册表 tag 内部结构——树内 API 无此能力）。

### 0227 — PrepareSpawnTask 双重 loadPlayerData 去重
- **文件**：`PrepareSpawnTask.java`
- **热点**：start() 与 spawn() 同一 join 各一次 loadPlayerData（磁盘读+gzip+NBT 解析+datafix 全树，主线程 ×2；上游 vanilla 同病）。两处消费均只读。
- **改法**：task 字段缓存首次结果（含 empty 情形——join 中途不会凭空出现 .dat）。
- **基准**：gzip 模型 2.01×（6,061→3,008 ns）；真实场景含磁盘+datafix 节省更大（背包重玩家毫秒级）。
- **风险**：低（非等价残余：join 中途 .dat 被外部改写——病态场景）。

### 记录项（红线外，上游行为）
- sendLevelInfo 双发（Paper 提前块+vanilla 保留处）→ 2 份 border/time/spawn/天气包；initInventoryMenu 双发（46 槽全量 ×2，且 join-kit 可变不可去重）；VERIFYING tick 门（0-50ms）；maxJoinsPerTick=5；配置任务串行 RTT 前置（重叠可省 RTT 但提前 PlayerSpawnLocationEvent 时点，中风险不做）。
- 排除项：recipe book（per-player）、UpdateRecipes（上游已预建）、命令树（已异步池）、出生点搜索（已异步）、编码层跨玩家字节缓存（与 0217 headroom 机制冲突且对象级缓存已拿走收益）。

---

## 批次 65（2026-08-20）：容器/菜单域暂缓清单回头攻克（0228/0229 + 直提交 + 两项否决）

批次 49 暂缓清单五项经专项 survey 逐句实证后**三落地两否决**。报告：[note/report/perf/2026-08-20-jmh-microbench-batch65.md](report/perf/2026-08-20-jmh-microbench-batch65.md)。

### 直提交 — callPrepareResultEvent 零监听器快路（PrepareResult 全族）
- **文件**：`CraftEventFactory.java`（直提交）
- **门控键**：`PrepareInventoryResultEvent.getHandlerList()`——全族唯一表（Anvil/Grindstone/Smithing 与 Paper 变体均不声明自己的表，paper-api 逐文件实证；PrepareItemCraftEvent 独立表已门控）。覆盖 7 个调用点。
- **快路**：零监听器 → broadcastChanges + return。跳过 setItem 回写三点论证：值恒等 + ResultContainer.setChanged 空方法 + vanilla 本无此写。铁砧改名每击键 + 各结果菜单每次输入变化省 ~3 分配。
- **风险**：低。

### 0228 — InventoryCreativeEvent 零监听器快路
- **文件**：`ServerGamePacketListenerImpl.java`（handleSetCreativeModeSlot）
- **门控键**：InventoryClickEvent 父表（子类无自有表，与 0154 同键）。快路 `itemStack = packet.itemStack().copy()`（asNMSCopy(asBukkitCopy(x)) ≡ x.copy() 逐例恒等）。
- **基准**：模型 2.41×（真实含派发+switch 更优）。**风险**：低。

### 0229 — InventoryDragEvent 零监听器快路（doClick QUICK_CRAFT）
- **文件**：`AbstractContainerMenu.java`
- **门控键**：自有表无子类。快路逐句保留两段 setCarried（预写=防复制机制、末写=原路径行为，值恒等）与 view.setItem 循环；仅省 eventMap+事件+派发。
- **基准**：9 槽模型 1.88×（CI 极窄；快路保留必需赋值，差值即事件侧机制成本）。**风险**：低。

### 否决一 — RecipeManager nullable 内部核（实测中性即撤，新判例）
- gc 探针实证 before/after 同为 16.000 B/op——**中间 Optional 跨方法但被内联，EA 直接消除**，手工拆包零收益。
- **判例**：跨方法但被内联的 Optional 包装链不值得手工拆（0187 同判）。已实现后撤回，基准留档。

### 否决二 — TransientCraftingContainer → CraftingInput 缓存（失效信号不可闭合）
- 三实证漏洞：vanilla `ServerPlaceRecipe.java:184` 的 `grow()` 原位变异网格活栈（不经 setItem）；插件经 CraftInventory 镜像/`getContents()` 活 list 原位改；缓存键别名同批栈 → equals 校验恒真。
- 对照：熔炉 SingleRecipeInput 引用键缓存（0144）可行恰因纯包裹无预计算。**不做**。

---

## 批次 66（2026-08-20）：寻路 tick 内联 + Present 记忆 raw 读（0230/0231）

批次 49 AI 域两项 + 批次 50 聚集域一项实证后落地两补丁一否决。报告：[note/report/perf/2026-08-20-jmh-microbench-batch66.md](report/perf/2026-08-20-jmh-microbench-batch66.md)。

### 0230 — PathNavigation tick 纯分量内联（+ FlyingPathNavigation + Path.papoGetNode）
- **文件**：`PathNavigation.java` + `FlyingPathNavigation.java` + `Path.java`（加法式访问器）
- **改法**：shouldTargetNextNodeInDirection 全内联（Node 直读 + 逐分量，FP 序/1.0E-5F 守卫/NaN 流穿照抄，0173-0180 模式）；基类 else-if 分支与飞行 tick 的 getNextEntityPos 调用点内联（getEntityPosAtNode 公式逐字：`node.x + (int)(bbWidth+1.0F)*0.5`，int 强转在乘 0.5 前）；getGroundY 体内 MutableBlockPos + move(DOWN)/move(UP)（连 below() 分配也省）。
- **红线保持**：getTempMobPos（abstract+逃逸字段）、canMoveDirectly/getGroundY 虚实参（批次 31 判定）不动。
- **基准**：模型 EA 中性（两路径 ≈10⁻⁴ B/op）——按 0175/0176/0180 先例机制保留（严格少工作 + 布尔矩阵自检 ALL OK）；每移动中 mob 每 tick 稳态省 1-4 Vec3 + 2-4 BlockPos，飞行另 2 Vec3。残余 getFloorLevel 内 below() 留二期。
- **风险**：低。

### 0231 — PureMemory Present 记忆 raw 读（gc 探针一票裁决通过）
- **文件**：`BehaviorBuilder.java`（PureMemory.tryTrigger 一处，~20 行）
- **热点**：声明式行为链 Present 条件 present 读每 tick 分配结果 Optional（村民交易所 5-10 次/tick/村民）。
- **裁决**：MemoryOptionalProbe 复刻（map 查找+3 实现轮换虚分发+逃逸 Accessor）实测 **28→20 B/op（8 B 真分配差）+ 1.8×**——Optional 未被 EA 消除，过批次 65 硬门。
- **等价性**：三态塌缩恰好等价（Present.createAccessor 对 unregistered/absent 均返 null；MemoryCondition 三实现为 final record 无第三方）；Absent/Registered 原路。
- **风险**：低。明确不做：MemoryAccessor/IdF 系统性消除（63 声明点，越红线）。

### 正式否决 — getEffectiveRange ridden 缓存（批次 50 暂缓 → 否决）
- 失效链测绘完成（addPassenger/removePassenger 双写点 + vehicle 链 + sendChanges diff 兜底）可闭合，但 0147 后非 ridden 实体（>99%）已 O(1) 快路，ridden 实体全服个位数——中风险换边际收益，不做。设计留档（载具农场场景可启用）。

---

## 批次 67（2026-08-20）：红石消费代码域（0232-0234，编号以补丁文件为准）

计划刻/BE tick 域 survey 定案：刻容器层已封闭（两项结构否决留档），落地红石消费代码四项。报告：[note/report/perf/2026-08-20-jmh-microbench-batch67.md](report/perf/2026-08-20-jmh-microbench-batch67.md)。

### 0232 — 红石粉 BlockRedstoneEvent 双站点零监听器快路 + HashSet 桶序复刻（同补丁；0125/0134 漏网补齐）
- DefaultRedstoneWireEvaluator.updatePowerStrength + RedStoneWireBlock.calculateCurrentChanges——默认 VANILLA 评估器下**最高频红石事件源**；改走已有 handleRedstoneChange 快路（与已落地 11 站点逐字同型）。风险低。

### 0232（续）— 粉评估器去 HashSet（桶序复刻，非 LinkedHashSet；与事件门控同一补丁）
- 7 位置恒互异/不扩容/不树化 → HashSet 纯开销（~9 分配/次粉功率变化）。**LinkedHashSet 否决**（插入序≠桶序改邻更新顺序）；桶序直复刻（spread(hash)&15 升序 + 插入序平局），**1M 随机位置穷尽对拍真实 HashSet 迭代序 ALL OK**；JMH 1.68×（CI 不重叠）。
- 判例补正：批次 23-27 的"HashSet→LinkedHashSet 暂缓"判定理由更正为"插入序≠桶序"（一票否决的是 LinkedHashSet 形态，桶序复刻可行）。

### 0233 — 红石火把 tick 事件惰性化 + 门控
- holding-state tick（时钟常态等待期）原本无条件构造 CraftBlock+事件却从不派发 → 两转换分支改 handleRedstoneChange（真转换才构造+派发；字段逐字一致）。

### 0234 — 比较器 getItemFrame facing 谓词静态缓存（0170 模式）
- 实心导体输入路径的捕获 lambda → 按 Direction.ordinal 静态缓存；AABB 依赖 pos 不缓存。

### 否决留档
- probe record 消除（LevelChunkTicks probe，两次暂缓）：probe 不逃逸 contains（EA 大概率消除），结构改法误判会抑制 scheduleTick → 红石破坏。结案不做。
- LevelTicks.collect 结构改造：紧 long 循环微秒级，刻序等价难证。否决。
- 刻容器层/BE ticker/ tickBlockEntities：已封闭无候选。

---

## 批次 68（2026-08-20）：自然刷怪与 despawn/merge 域（0235-0237）

刷怪/despawn 域 survey 定案（随机序列红线全图测绘 + 默认配置事实核实），落地三项，D/E/F 留档。报告：[note/report/perf/2026-08-20-jmh-microbench-batch68.md](report/perf/2026-08-20-jmh-microbench-batch68.md)。

### 0235 — NaturalSpawner PreCreatureSpawnEvent 零监听器门控
- 自然刷怪循环最高频事件站点（每通过距离检查的候选 × MONSTER 每 tick）；BaseSpawner 0165 同型；事件块不消耗随机（门控不影响序列）。

### 0236 — ItemEntity/ExperienceOrb merge 扫描去分配
- 静态 EntityTypeTest 单例 + 惰性 scratch/谓词 + 删重复 isMergable（邻体状态在 fill→loop 间不可变：tryToMerge 只触碰 this 与当前元素）。**gc 实证 280 B/op/次扫描真分配消除**（虚分发下 EA 未消除）；时间中性为浅栈伪影，收益为密集农场 GC 压力。

### 0237 — despawnRanges 按 ordinal 扁平化
- Mob.checkDespawn 每 mob 每 tick 的 HashMap.get（EAR 之前不受豁免）→ @PostProcess 建数组直索引（map 全类别播种 + @MergeMap 保键 → 每 ordinal 必有项；同点重建 reload 语义一致）。JMH 3.75×（CI 不重叠）。

### 留档与红线
- 留档：D 首候选 canSpawnMobAt 跳过（pos 相等守卫）；E spawn-cost biome 记忆化（收益打折需实测）；F getNearestPlayer→NearbyPlayers 空间查询（高价值中高风险，需 NO_SPECTATORS/半径/平局三重论证——checkDespawn 侧同思路**不可证等价**已否决）。
- 红线图：level.random 全消耗点 + SHARED_RANDOM（Mob.nextInt(800)）+ 到达控制流——任何检查相对 checkSpawnRules 移动改变消耗面。
- 默认配置事实：perPlayerMobSpawns=true → LocalMobCapCalculator 死路径（G 不做）。

---

## 批次 69（2026-08-20）：伤害/战斗管线域（0238-0240）

伤害域 survey 定案：**本 fork 事件门控未覆盖的最大高频集群**（EntityDamageEvent 族从未门控；CooldownReset/PreAttack/ItemDamage/Velocity 第二站点/双 Knockback 全裸奔）。报告：[note/report/perf/2026-08-20-jmh-microbench-batch69.md](report/perf/2026-08-20-jmh-microbench-batch69.md)。

### 0238 — 四组战斗事件零监听器门控（本批主项）
- PlayerAttackEntityCooldownResetEvent（LivingEntity:2510，每次玩家近战命中）+ PrePlayerAttackEntityEvent ×2（Player.attack/stabAttack）+ PlayerVelocityEvent 第二站点（Player:1223，0100 遗漏补齐）+ PlayerItemDamageEvent/EntityDamageItemEvent（ItemStack.hurtAndBreak，每击最多 4-5 发）。
- 各事件自有表无子类（paper-api 逐文件实证）；零监听分支逐字复刻（0100/0165 同型）；getAttackStrengthScale 等构造参数为纯计算。
- **每击最多 8 个事件构造 + Craft 包装归零**（PvE 战斗服）。

### 0239 — 横扫 DamageSource 副本提出循环
- doSweepAttack 循环内逐目标 knownCause copy → 循环外共享一份（常量实参值恒等不可变副本，hurtServer 只读）。getEnchantedDamage 仍用原 damageSource。

### 0240 — EntityDamageEvent 构造器 Preconditions stream→循环（paper-api 直提交）
- 全族最高频事件构造器的两条 stream 校验管线改循环；异常类型+消息逐字保持（自检对拍）。JMH 2.74×（CI 不重叠）。

### 留档（单独立项）
- **EntityDamageEvent 族全量快路**（每伤害实例 ~40-50 对象）：需 ①8 lambda 体抽私有方法的传值通道重构 + ②lastDamageCause 惰性物化（插件可从无关事件读 getLastDamageCause 观察差异——严格等价方案保留 nms DamageSource+9 double 载体）。侵入 LivingEntity 主链+CraftEntity，单独立项评审。
- EntityKnockbackEvent 双事件门控：需逐分量复刻 (cv+kb)-cv FP 往返 + 双表同空判据（层级已实证）。
- 非生物伤害事件门控（ArmorStand/ItemFrame/ItemEntity 燃烧等 9 站点）：与族级快路共用方案。
- 否决：仅门控 callEvent 派发（零监听已是空数组遍历）；CombatRules 标量；Pair 内联（虚签名红线）；totem/Resurrect（低频）。

---

## 批次 70（2026-08-22）：多观众区块包构造缓存（0241）

主题：**多玩家网络稳定**——区块发送主路径的每观众全量重序列化消除。勘察发现 `PlayerChunkSender.sendChunk` 对每个观众 `new ClientboundLevelChunkWithLightPacket(chunk, ...)` 完整重序列化（heightmaps clone + 24 section 调色板序列化 + buffer 分配 + BE 列表 + 光照 memcpy）：N 玩家同 chunk = N 次全量序列化，登录/跑图突发/群体迁移时主线程毫秒级放大。Paper 在 `FeatureHooks.sendChunkRefreshPackets`（同 tick 刷新路径）已按 `shouldModify` 缓存并跨玩家共享同一 packet 实例——共享语义有仓库先例，但该缓存不跨 tick、不覆盖主发送路径。报告：[note/report/perf/2026-08-22-jmh-microbench-batch70.md](report/perf/2026-08-22-jmh-microbench-batch70.md)。

### 0241 — LevelChunk 序列化负载缓存（heightmaps + buffer 跨观众复用）
- **文件**：`net/minecraft/world/level/chunk/LevelChunk.java`（版本计数 + 缓存字段 + 四 bump 位点）+ `net/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket.java`（papoCreateCached 工厂 + 轻量构造器）+ `net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData.java`（缓存复用构造器，BE 填充提取共用）+ `net/minecraft/server/network/PlayerChunkSender.java`（!shouldModify 路由）。
- **改法**：缓存 chunk data 中只随 chunk 变更的两段（heightmaps Map + 序列化 byte[] buffer），以 `papoChunkDataVersion`（AtomicLong，四处 bump：setBlockState 变更分支 / setBlockEntity / removeBlockEntity / setBiome 覆写）失效；**BE 标签与光照数据逐包新鲜构造**（BE 内容与光照的变更无 chunk 级信号——vanilla 语义=构造时读取当前值，缓存会让后加入观众拿到永久陈旧数据，红线不可越）。仅 `shouldModify == false`（anti-xray 关闭，Paper 默认 `enabled=false`；或玩家有 bypass）走缓存，否则原路径逐字保留。
- **等价性**：info=null 路径 `modifyBlocks(packet, null)` 对两种控制器实现均恰为 setReady(true)（逐源码实证），新构造器直接置 ready=true；heightmaps/buffer 构造后不可变（NO_OPERATION 路径 buffer 无人改写；anti-xray 实例化路径被 shouldModify 门排除）；packet 跨玩家共享为 vanilla 既有行为；线上字节逐字节相同（带宽中立）；缓存读写仅主线程（sendChunk 两调用点均主线程 tick），AtomicLong 防御 worldgen 线程经 ImposterProtoChunk 的未满 chunk 写入；缓存随 chunk 实例 GC，仅存在于被发送过的 chunk。
- **基准**：ChunkPacketCacheBench 模型（8 非空 section/24、12 项调色板、6 高度图、4 BE）：before 2597.676 ± 271.850 → afterHit **24.623 ± 0.363 ns/op（≈105×，CI 完全分离）**；afterMiss 2776.583 ± 274.059（与 before CI 重叠）；`-prof gc` 分配 **43992 → 248 B/op（177×）**。N 观客同 chunk 的主线程序列化总量 N 次 → 1 次。
- **风险**：低（失效信号四处审计闭合；BE/光照新鲜性硬约束满足；anti-xray 门控保守回退；内存面=被发送 chunk 的序列化负载，典型数 MB）。
- **暂缓**：FeatureHooks 刷新路径接入（已有同 tick per-call 缓存，增量小）；EmptyLevelChunk 死亡玩家路径（单次空 chunk）；光照数据跨 tick 缓存（光照引擎无干净 per-chunk 失效信号——增量广播只达当时观众，不能作失效源）。

---

## 批次 71（2026-08-22）：共享区块包实例 + 压缩输出 memo（0242，复合优化）

主题：**多玩家网络稳定**——多观众区块发送的每观众**主导成本（DEFLATE）**消除。批次 70 勘察结论：对逐字节相同的 chunk 包，每连接各自压缩（0129 实测 64KB level-6 ≈ 1.3ms/次，Windows JDK 回退 ≈ 220µs/41KB）。本批以两件复合改造消除重复压缩：①BE-free 且 (chunk 数据版本, 光照版本) 未变时跨观众**共享同一 packet 实例**；②共享实例携带**压缩输出 memo**（首连接压缩后快照 `[数据长 varint][压缩负载]` 段，后续连接 memcpy 直放）。光照版本信号 = `ChunkHolder.sectionLightChanged` 递增计数（由光照引擎 `onLightUpdate` 驱动——vanilla LayerLightSectionStorage 与 moonrise StarLightEngine 每个变更 section 都经 `ServerChunkCache.onLightUpdate` 投递主线程，与 vanilla 增量光照广播同源同完备性；bump 在无观众早退之前）。报告：[note/report/perf/2026-08-22-jmh-microbench-batch71.md](report/perf/2026-08-22-jmh-microbench-batch71.md)。

### 0242 — ChunkHolder 共享 chunk 包 + PapoSharedWireMemo 压缩 memo
- **文件**：新增 `net/minecraft/network/PapoSharedWireMemo.java`（单槽 memo：threshold 戳 + level 纪元 + volatile 段发布）+ `ChunkHolder.java`（papoLightVersion + sectionLightChanged bump + 共享包条目 papoGetSharedChunkPacket）+ `ClientboundLevelChunkWithLightPacket.java`（共享工厂改走 holder；memo 字段 + Carrier 实现）+ `PacketEncoder.java`（memo attr 发布，0217 通道复用）+ `CompressionEncoder.java`（memo 咨询/填充，above-threshold 路径）+ `Connection.java`（setupCompression level 纪元登记）+ `Varint21LengthFieldPrepender.java`（无压缩 handler 连接的 memo attr 兜底清理）+ `ClientboundLevelChunkPacketData.java`/`LevelChunk.java`（跨包访问放宽/版本 getter）。
- **等价性**：memo 段 = 同一 deflate 调用原样快照、自描述（内含未压缩长度），命中/未命中线上字节逐字节相同（基准 8 观众逐观众全等实证）；压缩级别不进协议（任意 zlib 流解码端无关级别），level 变更经纪元失效、threshold 戳不匹配不命中；BE-free 在 chunk 版本不变期间恒成立（BE 增删即 bump）；光照亚 tick 窗口（调度线程写入+通知排队）由排队通知的增量 ClientboundLightUpdatePacket 同 tick 自愈（通知必 mark 本 holder 广播过滤器并达新观众）；共享实例不可变、memo volatile 发布多 event loop 并发安全；0217 headroom/身份/引用计数结构逐行同构，外来 buffer 走原路径。
- **基准**：SharedChunkWireBench（41,189B chunk 型载荷，压缩比 3.99× 贴近实测 4.9×，JDK Deflater 真实压缩）：before 223,680.700 ± 4,933 → afterHit **3,509.720 ± 87 ns/op（≈63.7×/观众，CI 完全分离）**；afterFill 与 before CI 重叠（快照开销噪声级）。20 观众 × 64 chunk 突发 ≈ 282ms CPU 消除。
- **风险**：低-中（触碰出站管线但机制挂接在 0217 已验证的身份通道上；字节等价有真实 Deflater 对拍实证；光照亚 tick 窗口自愈且与 vanilla 增量语义一致）。
- **暂缓**：BE-containing chunk 共享（BE 内容无信号，红线否决——走批次 70 per-观众路径）；编码阶段 memo（次级 ~10-20µs，留档）。

---

## 批次 72（2026-08-22）：光照增量广播压缩 memo（0243）

主题：**多玩家网络稳定**——0242 的压缩 memo 扩展到**光照增量广播域**。`ChunkHolder.broadcastChanges` 把同一个 `ClientboundLightUpdatePacket` 实例发给每 chunk 全部追踪玩家（黄昏/黎明光照传播期：每 tick 多 chunk × N 玩家），每连接对相同 10-40KB 各自 DEFLATE。本批单文件改动：`ClientboundLightUpdatePacket` 实现 `PapoSharedWireMemo.Carrier` 并构造时挂 memo（PacketEncoder/CompressionEncoder 的 0242 机制原样生效）。报告：[note/report/perf/2026-08-22-jmh-microbench-batch72.md](report/perf/2026-08-22-jmh-microbench-batch72.md)。

### 0243 — ClientboundLightUpdatePacket 广播压缩 memo
- **等价性**：同实例广播为 vanilla 既有行为（broadcast(players, packet)）；包不可变、codec locale 无关 ⇒ 各连接编码字节相同，memo 段可逐字节重放；包为每次 broadcast 新建（瞬态），memo 随包 GC 无长期内存面；threshold/level 纪元/竞态/0217 身份通道/外来 buffer 回退全部沿用 0242 已论证机制。单观众场景填充开销 ~3µs（相对其 deflate ~311µs 为 1%）。
- **基准**：LightBroadcastBench（32,770B 光照型载荷，压缩比 1.98×——高熵，deflate 更贵）：before 311,336.888 ± 9,197 → afterHit **4,462.938 ± 256 ns/op（≈69.8×/观众，CI 完全分离）**；afterFill 与 before CI 重叠。黄昏传播期 50 chunk × 20 玩家外推 ≈ 292ms/s CPU 消除。
- **风险**：低（纯 Carrier 接入，无新机制面；自检 20 观众字节全等）。
- **暂缓**：编码阶段 memo（次级成本，light 瞬态无内存顾虑优先、chunk 驻留需内存权衡，留档）。

---

## 批次 73（2026-08-22）：编码阶段 memo + SectionBlocksUpdate 广播共享（0244）

主题：**多玩家网络稳定**——多观众出站冗余全面清算（0242/0243 后把每观众出站成本打到 memcpy 地板）。两项：①`PapoSharedWireMemo` 增**编码字节槽**（首连接 codec 走查原样快照，后续连接 writeBytes 直放；仅瞬态广播包 arm——light/section 随包 GC，chunk 驻留实例不 arm 因 ~40KB 快照钉整个 chunk 生命周期）；②`ClientboundSectionBlocksUpdatePacket` 接入 Carrier（压缩+编码双 memo；broadcastChanges 同实例发给每 chunk 全部追踪玩家，大红石/TNT 批量更新域）。**否决**：Explode 包共享——构造参数含 per-player 击退向量（ServerLevel:1991 逐玩家构造），不可共享。报告：[note/report/perf/2026-08-22-jmh-microbench-batch73.md](report/perf/2026-08-22-jmh-microbench-batch73.md)。

### 0244 — 编码 memo + SectionBlocksUpdate 双 memo
- **文件**：`PapoSharedWireMemo.java`（papoEncoded 槽 + papoCreateEncodeArmed 工厂）+ `PacketEncoder.java`（编码路径 Carrier 分支：命中 writeBytes / 未命中 codec+快照）+ `ClientboundSectionBlocksUpdatePacket.java`（Carrier + 双 memo）+ `ClientboundLightUpdatePacket.java`（补 arm 编码 memo）。
- **等价性**：快照取自首次编码同一 buffer 的 getBytes，命中路径 writeBytes 到同一 writerIndex 起点——逐字节相同；PacketTooLarge 检查两路径同 readableBytes；arm 门槛=codec 确定且 locale 无关（两包逐项核验）；volatile 单写发布 + 包不可变 ⇒ 竞态双填 benign；单观众填充损失 ~1-3µs（相对压缩 ~30µs 噪声）。
- **基准**：SectionBroadcastBench（480 变更小调色板+聚簇模型，编码 1,924B/压缩比 1.58）：before 34,364.342 ± 1,093 → afterHit **4,530.736 ± 802 ns/op（≈7.6×/观众，CI 分离）**；afterFill 与 before CI 重叠。首轮全随机模型 ratio=0.99 被自检证伪（真实批量更新方块 id 来自小调色板）——载荷模型经真实形态修正。
- **风险**：低（编码路径分支局部、双 memo 机制 0242 已验；自检 20 观众字节全等）。
- **留档**：chunk 共享实例编码 memo（+40KB/chunk 驻留内存权衡，未做）；explode（per-player 向量否决）——**多观众出站冗余面至此封闭**。

---

## 批次 74（2026-08-22）：join 静态大包构造缓存 + 双 memo（0245）

主题：**多玩家网络稳定（join 突发域）**。0226 缓存了 tags/registry 包构造但编码+压缩仍逐 join；`ClientboundUpdateRecipesPacket` 每 join 重新构造（PlayerList:199）~100-300KB。本批：①recipes 包 join 静态构造缓存（0226 模式，失效信号=RecipeManager.finalizeRecipeLoading 唯一赋值点→reloadResources→reloadRecipes 清缓存，addRecipe/removeRecipe/datapack reload 全经此链）；②tags/registry/recipes 三族 join 静态包 arm 双 memo（静态实例驻留，快照一次性 ~200-500KB）；③两个 record 包类改同形态 final class（record 不能声明实例字段；grep 实证无 equals/hashCode 消费者）。报告：[note/report/perf/2026-08-22-jmh-microbench-batch74.md](report/perf/2026-08-22-jmh-microbench-batch74.md)。

### 0245 — recipes 构造缓存 + 三族 join 静态包双 memo
- **文件**：`ClientboundUpdateRecipesPacket.java`/`ClientboundRegistryDataPacket.java`（record→final class + Carrier）+ `ClientboundUpdateTagsPacket.java`（class + Carrier）+ `PlayerList.java`（papoRecipesPacket 缓存 + join/reloadRecipes 路由 + reloadTagData arm）+ `SynchronizeRegistriesTask.java`（缓存构建处 arm）。
- **等价性**：包内容两次 finalizeRecipeLoading 间恒定（唯一赋值点实证，reload 整体替换字段）；跨玩家共享单实例为 vanilla 既有行为（reloadRecipes 本就单实例广播）；三类 codec 确定性 locale 无关（registryAccess 全连接同源）；memo 沿用 0242/0244 全部论证（threshold 戳/level 纪元/自描述段/配置阶段压缩已启用）；record→class 同构造形状同访问器名。
- **基准**：JoinPacketMemoBench（1200 配方×3 栈×8 组件词模型，91.7KB/ratio 2.02；首轮纯 varint 模型被自检带外证伪后按 ItemStack 组件形态修正）：before 7,229,623.601 ± 767,635（**~7.2ms/join**）→ afterHit **167,837.986 ± 9,504 ns/op（≈43×/join，CI 分离）**；afterFill 与 before CI 重叠。50 玩家 join 突发外推 ≈ 355ms CPU 消除。
- **风险**：低（构造缓存失效链完备实证；memo 机制三代同源；自检 10 join 字节全等）。
- **留档**：sendLevelInfo/initInventoryMenu 双发（批次 64 红线外维持）；recipe book/advancement 初始包（per-player 状态不可共享）。

---

## 批次 75（2026-08-22）：join 玩家信息广播 memo（0246）

主题：**多玩家网络稳定（join 突发域收尾）**。每次 join，同一 `ClientboundPlayerInfoUpdatePacket` 实例（新玩家条目 ~0.5-1.5KB 超阈值）发给每个在线玩家——每连接各自编码+DEFLATE；unlisted 变体更在循环内重复构造同参数包。本批：两站点 arm 双 memo + unlisted 构造惰性提出循环外。报告：[note/report/perf/2026-08-22-jmh-microbench-batch75.md](report/perf/2026-08-22-jmh-microbench-batch75.md)。

### 0246 — PlayerInfoUpdate join 广播双 memo + unlisted 提出
- **文件**：`ClientboundPlayerInfoUpdatePacket.java`（Carrier + memo）+ `PlayerList.java`（join 主包 arm / unlisted 惰性提出 + arm）。
- **等价性**：主包 vanilla 本就单实例循环发送；unlisted 构造参数 (player,false) 与循环变量无关（逐行核验），提出后各接收者收同一实例（与主包同构），惰性构造保持零观众零构造语义；memo 机制沿用 0242/0244；其余构造点（latency/gamemode/hat）memo null 零影响。
- **基准**：PlayerInfoBroadcastBench（单玩家条目 737B/ratio 1.20；首轮 126B 模型被自检带外证伪后按真实签名规模修正）：before 12,206 ± 1,963 → afterHit **1,412.870 ± 99.941 ns/op（≈8.6×/观众，CI 分离）**；30 观众 join ≈ 0.33ms + unlisted 构造归一。
- **风险**：低（单类两站点；自检 30 观众字节全等）。
- **同批否定结论（留档）**：入站解压域无优化点（两路径均按声明大小精确分配）；UPDATE_LATENCY 不可共享（canSee 视角 per-target）；UPDATE_HAT/GAME_MODE 小包不压缩不扩展；join 域剩余均 per-player 内容或红线外——**join 冗余面封闭**。

---

## 批次 76（2026-08-22）：实体同步域三项（0247）

主题：**多玩家网络稳定（实体同步域）**——稳态出站流量大头的 CPU/分配面。survey 子代理系统扫描（ServerEntity/TrackedEntity/ChunkMap 追踪路径）产出三候选落地。报告：[note/report/perf/2026-08-22-jmh-microbench-batch76.md](report/perf/2026-08-22-jmh-microbench-batch76.md)。

### 0247 — 无观众跳过包构造 + seenBy 双探测短路 + 矿车 Vec3 内联
- **文件**：`ServerEntity.java`（papoHasViewers/papoHasRecipients 守卫 11 构造站点 + 矿车内联）+ `ChunkMap.java`（`!papoAlreadyTracked && add` 短路）。
- **改法/等价性**：①无观众（trackedPlayers 即 seenBy 同引用）时只跳 `new`——base/lastSent 系列/teleportDelay/flag3/flag4/packDirty 清 dirty/attributesToSync.clear/injectScaledMaxHealth 逐行保留；AndSelf 站点以 `papoHasViewers || entity instanceof ServerPlayer` 守卫（玩家自收保留）；空集广播零字节零事件零时序变化；ItemFrame 路径已有 isEmpty() 门控先例；forceStateResync 交互安全（仅 onPlayerAdd 置位）。②contains 与 add 之间无 seenBy 写入，稳态 add 可证 no-op，跳过不可观察。③`a-b` 与 subtract 的 `a+(-b)` 位级一致、lengthSqr 左结合表达式树相同（10 万点自检）。
- **基准**：EntitySyncNoViewerBench：A 无观众包构造 76.295 ± 1.840 → **12.215 ± 0.465 ns/op（6.2×，CI 分离）**；B 双探测 3.563 ± 0.207 → **1.916 ± 0.114（1.86×，CI 分离）**；C 矿车 Vec3 CI 重叠（EA 伪影判例——复刻内 Vec3 被标量替换，真实深栈分配真实发生，机制保留）。外推：500 追踪范围外实体 ~64µs/tick 纯垃圾消除 + 10k pair ~16µs/tick。
- **风险**：低（机械守卫 + 状态机逐行保留；全量 test 绿）。
- **survey 否定留档**：实体稳态包全部 <256B 阈值（memo 不适用）；空闲实体无每 tick 分配；broadcast 迭代器换 forEach 无净收益；per-pair 辅助调用全字段读；火球 bundle 去 delimiter 改 wire 字节否决——**实体同步域封闭**。

---

## 批次 77（2026-08-22）：同序列 pairing 包共享（0248）

主题：**多玩家网络稳定（实体配对域）**——多观众冗余链在 pairing 侧的最后一块。新实体注册（`addEntity → TrackedEntity.updatePlayers(全体玩家)`）在单线程连续循环中对多个玩家 addPairing：`sendPairingData` 全部内容仅依赖实体状态，窗口内不变 ⇒ 每观众重复构造逐字节相同的 2-6 个包。报告：[note/report/perf/2026-08-22-jmh-microbench-batch77.md](report/perf/2026-08-22-jmh-microbench-batch77.md)。

### 0248 — updatePlayers sweep 内 pairing 包共享
- **文件**：`ServerEntity.java`（papoPairingShareDepth/Cache + begin/end + addPairing 复用路径）+ `ChunkMap.java`（updatePlayers try/finally 括起）。
- **等价性**：窗口=单次 updatePlayers 方法内连续循环（无实体 tick 交错，局部可证）；sendPairingData 的 per-player scaled-health 分支在 pairing 路径不可达（updatePlayer 首行 player != entity 守卫；另一调用方 resendPossiblyDesyncedEntityData 不在窗口）；updateDataBeforeSync/detectEquipmentUpdates 幂等；窗口外一切 addPairing 独立构造照旧（缓存 null）；end 时 finally 清缓存；attributes live 集合引用是 vanilla 每观众包本就共享的同一集合，不新增并发面。
- **基准**：PairingShareBench（五段构造模型 × 4 观众）：sweep 692.413 ± 11.853 → 0.564 ± 0.184 ns/op（模型复用为纯引用读，1227× 为模型夸张上界；真实收益=每观众省 (K-1)/K 的 pairing 构造，getNonDefaultValues 扫描+equipment copy+5 包对象 µs 级）。满负荷刷怪 30 新实体/tick × 2-4 观众 → 每 tick 数十次重复构造消除。
- **风险**：低（窗口纪律 try/finally + 深度钳制；自检 4 观众包序列逐项一致；全量 test 绿）。
- **留档**：玩家移动进入范围场景不共享（sweep 段间可能夹实体 tick，tick 内顺序无局部可证性，保守排除）——**配对域封闭**。

---

## 批次 78（2026-08-26）：核心感知线程池自动 sizing（Netty loops + region IO，多核调度系列①）

主题：**多核调度**——固定线程数清算第一轮。spigot.yml netty-threads 默认平 4（大核主机事件循环不足）；paper-global chunk-system.io-threads 的 auto（-1）落到平 1（全部世界 region IO 串行单线程，一个冷读磁盘延迟卡住一切）。新增 `PapoParallelism` 集中 sizing（显式配置永远优先）：netty=cores/4 clamp[4,16]、regionIo=cores/8 clamp[1,4]。报告：[note/report/perf/2026-08-26-iopool-scaling-batch78.md](report/perf/2026-08-26-iopool-scaling-batch78.md)。

### 批次78 — PapoParallelism 核心感知 sizing（直提交，无补丁编号）
- **文件**：新增 `io/papermc/paper/util/PapoParallelism.java` + `MoonriseCommon.java`（ioThreads auto 路由）+ `SpigotConfig.java`（netty-threads 默认值 + 存量 4 提示）。
- **安全性**：AreaDependentQueue 按点区域 (chunkX>>5, chunkZ>>5) 串行化同 region file 访问（ReentrantAreaLock，javap+源码实证）——多 IO 线程只并行化不同 region file，永不引入同文件并发；显式配置逐字优先；spigot.yml 已物化 4 的存量安装不变（INFO 提示）；OSNuma NoOp 回退=availableProcessors（javap 实证）+max(1,·) 防御；netty 空闲循环 park 零成本、下限 4=旧值（≤16 核行为不变）；worker 池 sizing 未触碰。
- **基准**：IoPoolScalingBench（真实 concurrentutil 0.0.8 池+队列，8 region×16 任务，4KiB 真实读+模拟设备延迟）：1 线程 1992ms → 4 线程 450ms（**4.43×**）；自检全绿（per-region 并发≤1 / FIFO / 恰好一次 / 并行度实测兑现=4）。热页缓存变体 1 线程反快（µs 级任务池唤醒开销主导）——收益面=冷读/阻塞 IO 场景，热读无害。
- **同批构建修复**：`PaperConfigurations.defaultFieldProcessors` 返回类型放宽（上游遗留窄类型触发 javac 增量编译的 class 文件签名解析陷阱；二分实验定位源码/二进制解析不对称；私有静态方法零 API 影响）。
- **风险**：低（池数字来源集中化，池实现零改动；adjustThreadCount 为 moonrise 既有 API）。
- **留档**：worker 池 auto 曲线维持 moonrise 既有；netty 规模属容量上限提升无热路径微基准；benchmark 新增 concurrentutil+slf4j 依赖。

---

## 批次 79（2026-08-26）：玩家存档文件管线下放（多核调度②）

主题：**多核调度——主线程阻塞 IO 清算**。玩家保存的 GZIP+三文件写在主线程（增量自动存档 maxPerTick 突发=多 ms tick 尖峰，/save-all 与关服全量阻塞）。新增 `PapoOrderedFileWrites`（src/main 直提交）：per-目标文件 CompletableFuture 有序链 + IO 池执行 + awaitPending/awaitAll。报告：[note/report/perf/2026-08-26-playersave-offload-batch79.md](report/perf/2026-08-26-playersave-offload-batch79.md)。

### 批次79 — 玩家 .dat/stats/advancements 写下放（源补丁×4 + 直提交工具类）
- **文件**：新增 `io/papermc/paper/util/PapoOrderedFileWrites.java` + `PlayerDataStorage.java`（save 快照深拷贝+入队；load awaitPending）+ `ServerStatsCounter.java`（save Json 快照+入队；ctor awaitPending）+ `PlayerAdvancements.java`（save codec 快照+入队；load awaitPending）+ `PlayerList.java`（saveAll(-1) awaitAll）。
- **等价性**：主线程只构建载荷（NBT 树防御性深拷贝；Json 树 detached 新建，任务内同一 GSON 调用=字节逐字节相同）；per-目标链严格串行（旧快照永不覆盖新快照；链失败不阻断后续）；三个加载点读前等待（快速重连读最新，同步语义保持）；全量保存 awaitAll（vanilla 契约保持，增量 fire-and-forget）；池停时同步兜底；haltExecutors 60s 排水双保险。
- **基准**：PlayerSaveOffloadBench（50 玩家×4 save/tick 突发，96KiB 载荷）：主线程 89ms → 2ms（**44.5×**），总墙钟 87→56ms；自检全绿（per-key 串行/最后快照字节对拍/awaitPending 新鲜度/恰好一次）。payload 熵偏低已披露=保守下界；200 人 /save-all ≈ 370ms 主线程阻塞消除。
- **风险**：低-中（写入异步化的全部时序面已闭合：per-key 序、读后写、全量等待、停机排水、拒绝兜底；载荷构建留主线程零语义变化）。
- **留档**：level.dat（下批评估）；逐包 execute 批量化（批次60已否决同域）；实体/POI 卸载序列化（survey #3a，下批主候选）。

---

## 批次 80（2026-08-26）：worker 池默认曲线提升 + level.dat 写下放（多核调度③）

主题：**多核调度**——worker 池（gen/load/light/compression/save 唯一执行池）auto 默认 cores/4 → **cores/2 clamp[2,12]**（探索突发吞吐瓶颈，主线程等区块 future 即 tick 卡顿；worker NORM 优先级在 NORM+2 主线程之下）；level.dat 写下放（批次79 机制复用，boot 路径保持同步）。报告：[note/report/perf/2026-08-26-workerpool-level-data-batch80.md](report/perf/2026-08-26-workerpool-level-data-batch80.md)。

### 批次80 — worker 曲线（直提交）+ level.dat 下放（源补丁×2）
- **文件**：`PapoParallelism.java`（workerThreadCount）+ `MoonriseCommon.java`（auto 来源替换，删未用 import）+ `LevelStorageSource.java`（saveDataTag 快照+入队；makeWorldBackup awaitPending；boot modifyLevelDataWithoutDatafix 保持同步）+ `MinecraftServer.java`（saveAllChunks flush 尾 awaitAll）。
- **等价性**：显式配置与 -D 覆盖逐字优先；曲线 8核2→4/16核4→8/24核+→12（cap 防超订阅）；池实现零改动；level.dat 快照语义保持+深拷贝+per-路径链；备份读前等待；flush 语义 awaitAll+haltExecutors 双保险。
- **基准**：WorkerPoolScalingBench（真实池，96×3.8ms CPU 任务，32核）：8→12 线程吞吐 46→31ms（**1.48×**）；NORM+2 tick 探针在两配置饱和期间 p50/p99/max 偏差全 0ms——**扩容不以流畅度换吞吐直接实证**。自检 ALL OK。
- **同批否定（留档）**：实体 unload 存档序列化下放——`saveEntities` 在 `entityChunk.unload()`（卸载事件+setRemoved）之前同步执行，下放将捕获 post-event 状态≠vanilla 字节，红线否决（NewChunkHolder.java:899→901 证据链）；autosave 路径同理否决。POI 下放可行但收益小不成批。
- **风险**：低（默认值来源替换+机制复用；否定项有源码证据链）。

---

## 批次 81（2026-08-26）：多核调度系列集成冒烟验证（批次78-80 真实服务器实证，无代码变更）

主题：**稳定性验证轮**——发布 jar 真实 boot→worldgen→自动存档→干净关服全生命周期对拍（0.54.0 vs 0.51.0 基线）。报告：[note/report/perf/2026-08-26-smoke-verify-batch81.md](report/perf/2026-08-26-smoke-verify-batch81.md)。

### 批次81 — 集成验证结论
- **sizing 生效实证**：0.54.0 启动日志 8 netty / 12 worker / 4 IO（三批公式全部生效，fresh spigot.yml 物化 netty-threads:8）；0.51.0 对照 4/8/1（旧行为确认）。
- **关服契约逐行核验**：stop → Saving players/worlds → 全部保存 → awaitAll（level.dat）→ RegionFile flush → 池排水 → exit 0；level.dat 产出合法 gzip+NBT；两轮全文零 ERROR/Exception。
- **留档**：杂项池（DIMENSION_DATA_IO_POOL=4 / BACKGROUND cap8 / ioPool 冷路径）运行时负载不足不成批；boot 时间 15.41s vs 15.84s 仅同量级 sanity。多核调度池预算面集成验证封闭。

---

## 多核调度系列域封闭总结（2026-08-26，批次78-81）

**已交付**：池预算集中化（78：netty cores/4[4,16] + regionIO cores/8[1,4]；80：worker cores/2[2,12]，显式配置永远优先）→ 主线程阻塞 IO 清算（79：玩家 .dat/stats/advancements；80：level.dat，per-目标有序链+读后写可见+全量等待）→ 集成验证（81：真实服务器全生命周期对拍，sizing 三值生效实证、零异常）。

**评估后否决/留档**（证据链见各批次报告）：
- 实体 unload 存档序列化下放——`unloadEntity`/`setRemoved` post-event 状态≠vanilla 字节（批次80 留档）。
- 逐包 eventLoop execute 批量化——批次60 两轮实测否决（MPSC 1.49× 劣化），不重开。
- 线程优先级层级——上游既有（main NORM+2 / worker NORM-1），无可为。
- 杂项池 sizing（DIMENSION_DATA_IO_POOL=4 / BACKGROUND cap8 可-D覆盖 / ioPool 冷路径 / ASYNC_EXECUTOR 仅版本检查）——运行时负载不足，不成批。
- **并行世界保存（/save-all 与关服时三世界串行）**——技术上可行（世界间数据结构独立、moonrise 池本就多世界共享、WorldSaveEvent 可先串行触发），但 chunk system close/save 的跨世界共享面审计负担大，而失败模式=存档损坏（红线级灾难）。在"安全性稳定性不可赌"约束下否决，留档供未来有完整验证带宽时重启。
- 宏观 worldgen 压测（真实服 forceload 对拍）——spawn 区块上游已移除、forceload 限 256 完成太快、无完成信号可测；机制级证据（批次80 真实池 1.48× + tick 探针 0 偏差）+ 集成实证（批次81）已构成该层面的完整证据链。

## 批次 82（2026-08-26）：join 读侧下放——登录窗口预取 .dat/stats/advancements（多核调度④）

主题：**多核调度——主线程阻塞读清算**。批次 79 下放了 join 写侧（.dat/stats/advancements 的 gzip+写盘）；读侧仍在主线程同步执行（`PrepareSpawnTask.start()` 的 loadPlayerData = 磁盘读+gzip+NBT 全树解析+datafix；`ServerPlayer` ctor 的 `getPlayerStats`/`getPlayerAdvancements` = JSON 读+解析，两者均主线程实证）。登录流程在 `startClientVerification`（全部认证模式汇合点，AsyncPlayerPreLoginEvent 之后）即拿到最终 GameProfile，距消费点至少一个客户端 RTT——本批把三类读在登录时刻挂到各自文件的 per-target 有序写链上，主线程消费点 join 通常已完成的 future。报告：[note/report/perf/2026-08-26-joinread-prefetch-batch82.md](report/perf/2026-08-26-joinread-prefetch-batch82.md)。

### 0249 — join 读侧预取（登录 RTT 窗口）
- **文件**：`PlayerDataStorage.java`（纯读/纯修复预取体 + 消费）、`PlayerList.java`（stats/adv JSON 预取缓存 + 触发/丢弃入口）、`ServerLoginPacketListenerImpl.java`（触发 + 断连丢弃）、`ServerConfigurationPacketListenerImpl.java`（断连丢弃）、`ServerStatsCounter.java`（ctor 消费）、`PlayerAdvancements.java`（load 消费）+ 直提交 `PapoOrderedFileWrites.enqueueRead`
- **机制（批次84 压测后终态）**：`enqueueRead(Path, Callable)` 把读任务挂在与写相同的 per-Path CompletableFuture 链（**结构性读后写排序**，快速重连语义保持；池线程内零阻塞等待——`awaitPending` 从池线程调用会在单线程 IO 池下死锁，本机制规避）。读任务入队 **NORMAL** 优先级、消费点未完成则 `raisePriority(BLOCKING)` 升级（moonrise `getIOBlockingPriorityForCurrentThread` 同款模式："有人真在等才给抢占权"）；**datafix 不下放**，留在主线程消费点与原版 map 逐字相同——v1 的"datafix 随读下放 + 入队即 BLOCKING"被批次84 的 20-bot 突发压测实测否决（datafix 在 IO/worker 池均与区块管线竞争、BLOCKING 读抢占区块读，端到端慢 250-450ms；终态双向序统计持平略优）。
- **等价性（逐项）**：
  - 副作用（`.offline-read` 重命名、`_corrupted_` 备份）**留在主线程消费点原逻辑位置**——登录中断窗口不提前留副作用（严格等价红线，区别于把整个 load 搬走的粗放方案）；
  - 预取体 = readCompressed + MCDataConverter.convertTag，均纯函数且已被 moonrise worker 并发使用（chunk 加载），线程安全有既有实证；
  - 预取体异常 → `fallbackToSync` → 消费点重跑原同步路径（vanilla 告警/异常行为逐字保持）；池停时 enqueueRead 返回 null 不缓存；
  - 单人游戏主机（isSingleplayerOwner）跳过（数据来自内存 tag）；duplicate-login 场景（同 UUID 在线者可能写 .dat）在原版同样于配置阶段读盘、且该登录必被拒绝，无可观察差异；
  - CraftOfflinePlayer.getStatistic 理论竞争窗口：consume-once 语义保证 future 只被一方消费，另一方走原同步读，数据等价；
  - 登录/配置两阶段断连钩子丢弃未消费预取，无泄漏；`PlayerList.loadPlayerData` 无预取时原路径逐字保留（含 .dat_old 回退链）。
- **基准**：JoinReadPrefetchBench（真实 concurrentutil 池 + 20 人突发）：主线程 **15.45ms → 0.04ms（≈380×）**（读侧口径：file+gzip+parse；datafix 按终态留主线程）；零 RTT 最坏情形 7.35ms（池并行度下恒不劣于同步串行）；饱和探针 BLOCKING 704µs vs NORMAL 253ms（**≈358×**，构成消费点升级机制存在必要性的证据）。自检 5 组 ALL OK。批次84 追加：真实服务器 20-bot 并发 burst 双向序统计持平略优 + 240 join 零异常。
- **风险**：低（结构性排序无死锁面；回退路径完备；副作用时点不变；consume-once + 丢弃钩子闭合）。
- **留档**：datafix 未进基准模型（保守下界）；`getSpread` 等历史暂缓项不变。

### 直提交 — PapoOrderedFileWrites.enqueueRead（BLOCKING 优先级读链）
- **文件**：[PapoOrderedFileWrites.java](../paper-server/src/main/java/io/papermc/paper/util/PapoOrderedFileWrites.java)
- 结果 future 与 Void 型 per-target 链解耦（chain 任务完成后 always-yield null 续链，读异常不断链）；读计入 pending 计数（awaitAll 排水有界覆盖）；池停时返回 null（读可回退同步，不同于写的同步降级——持久性语义只属于写）。

## 批次 83（2026-08-26）：批次 82 真实服务器端到端冒烟验证（多核调度⑤，无代码变更）

主题：**稳定性验证轮**。构建最小离线模式协议机器人（OfflineJoinBot，纯 JDK socket 零依赖，protocol 774，全部包 ID/字段编码源码实证）对真实专用服做 join 全生命周期对拍（0.55.0 vs 0.54.0）。报告：[note/report/perf/2026-08-26-smoke-verify-batch83.md](report/perf/2026-08-26-smoke-verify-batch83.md)。

### 验证矩阵与结论
- **全绿**：两 jar 各 10/10 join（首 join 空数据=回退路径 / 即时重连=读后写排序实战 / 稳态×8=预取命中路径）、stop exit 0、日志零 ERROR/Exception、playerdata/stats/advancements 产物全部合法（gzip+NBT magic 校验）。
- 端到端稳态 join 墙钟持平（81.6 vs 80.4ms，新玩家文件 KB 级、读成本 µs 级，端到端由区块加载主导）——无回退；机制收益由模型基准量化（380×）。
- **基建沉淀**：OfflineJoinBot + SmokeJoinVerify 为标准 join 路径验证工具（空数据/即时重连/稳态/关服四态矩阵），后续触碰 login/config/spawn/playerdata 的批次直接复用。


## 批次 84（2026-08-26）：join 突发压测——fat 老 .dat × 20 bot 并发，实测驱动批次 82 三轮设计修正（多核调度⑥）

主题：**稳定性压测 + 实测迭代**。批次 83 顺序 join 未覆盖并发；本批 fat .dat 生成器（DataVersion 3700→真实 datafix 全链）+ 20 bot 屏障并发 burst 双向序对拍。**抓到真实并发 bug 并修正批次 82 两项设计**。报告：[note/report/perf/2026-08-26-burst-verify-batch84.md](report/perf/2026-08-26-burst-verify-batch84.md)。

### 抓到的 bug（已修入 0249）
- **getLevelPath HashMap CME**：并发登录时 authenticator 多线程触发 `LevelStorageAccess.getLevelPath` 的普通 HashMap computeIfAbsent → CME → bot 登录失败。修复：stats/adv 目录 PlayerList 构造期主线程一次解析为 final 字段。顺序 join 永不触发——**并发登录必须进验证矩阵**。

### 三轮迭代（0249 内部三次修正，实测否决链）
1. v1 datafix 在 IO 池读任务内 + 读 BLOCKING → 突发慢 250-450ms（IO 线程被 datafix 占住，region 读饿死）；
2. v2/v3 datafix 移 worker 池（HIGHEST→NORMAL）→ 仍慢 270-450ms（与区块反序列化竞争）；现代文件对照（datafix 无步进）仍慢 → 元凶锁定 **BLOCKING 读抢占区块读**；
3. **终态 v5**：datafix 回主线程消费点（与原版逐字相同）+ 读入队 NORMAL + 消费点未完成 `raisePriority(BLOCKING)`（ReadHandle）→ 双向序统计持平且均值均略优 30-50ms，12 轮 240 bot 零门错误。

### 判例（新增）
- 跨池移动 CPU 工作必须看它在和谁竞争（主线程串行=自然限流；移到 IO/worker 池=与区块管线竞争，突发端到端反慢）；主线程收益与端到端总布局两个口径都要看。
- BLOCKING 优先级是"有人真在等"的资源，入队即 BLOCKING = 无人等待时预支抢占权；moonrise 的 raisePriority 等待时升级是正确形态。
- JDK18+ 默认 charset=UTF-8：中文 Windows 下服务器子进程日志须 `-Dfile.encoding=UTF-8` 才可按 UTF-8 读；bot 突断噪声三形态（StacklessClosedChannelException / Connection reset by peer / 中文 reset）为两版本同现的良性项。

## join 存档管线系列封闭总结（2026-08-26，批次79/82-84）

**已交付**：主线程 join 存档 IO 全清算——写侧（79：.dat/stats/advancements gzip+写盘下放，per-目标有序链+读后写可见+全量等待）→ 读侧（82-84 终态：登录窗口预取读+gzip+parse，NORMAL 入队+消费点 raiseToBlocking 升级，datafix 留主线程）→ 验证（83 顺序四态冒烟 + 84 并发突发双向序，协议机器人/四态矩阵/突发压测三件套沉淀为标准基建）。

**实测否决面（证据链见批次84 报告）**：
- datafix 下放（IO 池/worker 池两形态）——均与区块管线竞争，突发端到端慢 250-450ms；
- 读任务入队即 BLOCKING——无人等待时预支抢占权，抢占 spawn 区块读；
- 并发登录直调 getWorldPath——HashMap 缓存 CME（已修：构造期预解析）。

**判例沉淀**：跨池移动 CPU 工作看竞争对象；BLOCKING=有人真在等；主线程收益与端到端两个口径都要看；并发登录必须进验证矩阵。join 存档管线（读写两侧）至此封闭。

## 批次 85（2026-08-26）：专用登录 datafix 池假设检验——否决（第四形态，多核调度⑦）

主题：批次 84 判例推论验证。实现 `PapoLoginDatafixPool`（cores/8 clamp[1,2]，NORM-1 daemon）+ 两阶段链（读 IO 池→fix 专用池）为 0250，burst 实测**比 v4 慢 ~380ms**（消费点在 2 线程修复队列排队 + 主线程失去自然限流导致区块需求扎堆）——四种 datafix 放置形态（IO 池/worker×2/专用池）全部实测劣于主线程，**v4 判例终局加固**；0250 rebase-drop 摘除（批次29 先例），池类删除，撤销后复确认持平偏好（mean −92ms）。报告：[note/report/perf/2026-08-26-dedicated-pool-batch85.md](report/perf/2026-08-26-dedicated-pool-batch85.md)。

### 判例（新增）
- **"自然限流"可能是隐式最优调度**：并行化串行工作后，下游（区块管线）瞬时需求扎堆可吃掉全部收益——判断并行化收益必须看下游吞吐是否弹性。
- /mspt 度量基建勘察：BurstJoinVerify 已内置捕获，但 zh-CN Windows 管道控制台下 ◴ 数字行不输出（MsptProbe 实证）——平台限制留档，Linux/tty 可用。

## 批次 86（2026-08-26）：多核调度系列宏观 worldgen 校准——端到端持平，瓶颈归位主线程（多核调度⑧）

主题：补上批次 78/80 池预算改动的宏观端到端口径（fat .dat Pos 指向远方未生成区块 → 20 bot 并发 join 触发真实 worldgen 突发，现代 DV 隔离 datafix）。结果：**0.55.0（8/4/12 池）vs 0.52.0（4/1/8 池）10+9 轮统计持平（Δ=−27ms）**——join-gen 突发关键链是主线程串行面（20 bot config+placeNewPlayer ~3s 主导）而非 worker 面（49-chunk 生成 Δ~40ms 被淹没）。不否定批次78/80（持续区块需求场景收益成立，机制级证据在），但确立核心洞察：**多核利用率瓶颈常在主线程串行面，后续最高价值面是减少主线程工作而非调池**。报告：[note/report/perf/2026-08-26-worldgen-macro-batch86.md](report/perf/2026-08-26-worldgen-macro-batch86.md)。附带：MakeFatPlayerDat 远坐标覆盖（PAPO_FAT_POS_X/Z）+ BurstJoinVerify 良性门统一（isBenignCloseRace，本机 locale socket 乱码判例：sun.jnu.encoding 层，file.encoding 管不到）。

## 批次 87（2026-08-27）：join 管线相位分解 + prepare_spawn 事件驱动完成（多核调度⑨，稳态 join −57%）

主题：批次 86 判例执行——主线程串行面是瓶颈。包级相位计时（JoinPhaseBench）分解稳态 80ms join：登录 21 + 配置管道 4（批次74 memo 下 26 注册表包仅 ~1ms）+ **46ms 纯 tick 量化**（prepare_spawn 状态机只在 tick 边界推进：区块票据首 tick 才调度 + future 完成等下个 tick 观察，两级量化最坏 ~100ms）+ placeNewPlayer 9。

### 0250 — prepare_spawn 事件驱动完成
- **机制**：start() 末尾 + 三个 future 的 whenComplete 统一经 `server.execute`（主线程任务队列=包处理器与 placeNewPlayer 本就运行的 tick 间窗口）推进同一 tick() 逻辑；**CAS 单发转移**防重入双 finish。
- **初版缺陷（burst 实测抓出后修复）**：whenComplete 在 Preparing.tick() 内联触发 → 内外双重 finishCurrentTask → "current task: join_world, requested: prepare_spawn" 断连；CAS 后 6 轮 burst 120 bot 零复现。
- **等价性**：全路径主线程串行；转移单发；包/事件序列逐连接相同仅提前 ≤2 tick；断连/停机路径 no-op 或常规 tick 兜底。
- **基准**：稳态重连 80→32-35ms（**−57%**，×3 稳定）；四态冒烟稳态 83.5→37.4ms（−55%，10/10 零异常）；fat-dat burst 持平偏好（−73ms 零门错误）；finish-config gap 46→0ms。报告：[note/report/perf/2026-08-27-prepare-spawn-event-driven-batch87.md](report/perf/2026-08-27-prepare-spawn-event-driven-batch87.md)。
- **判例**：① 配置任务状态机 tick 量化是 join 延迟主导项（57%）；② whenComplete 可能在赋值表达式内同步触发——状态机完成转移必须 CAS 单发。

## 批次 88（2026-08-27）：登录相位事件驱动完成——join 总 80→14ms（多核调度⑩）

主题：批次 87 剩余串行面清算。`LoginListener.tick()` 的 VERIFYING→LoginSuccess 转移只在 tick 边界（auth 线程完成后量化等待 0-50ms；bot 回环与 20-tick 锁相致实测中位 21ms 低估随机相位期望 ~25ms）。

### 0251 — 登录完成事件驱动
- **机制**：startClientVerification（auth 线程）置 VERIFYING 后经 `server.execute` 推进 `verifyLoginAndFinishConnectionSetup`，守卫与常规 tick() 逐字相同（disconnecting/cookies/state）；单次执行=主线程串行化+守卫块内转移；停机尾部 executor 拒绝由常规 tick 兜底；dupe-disconnect 罕见路径维持 tick 轮询。
- **等价性**：同方法同守卫仅提前；canPlayerLogin/PlayerLoginEvent 仍主线程；WAITING_FOR_DUPE 语义不变。
- **基准**：登录相位 21→**4ms**；稳态 join **80→14/15ms（−82%）**；四态冒烟稳态 mean **19.0ms（vs 0.54.0 −76%）**；burst 568ms（−~120ms）；fat-dat burst 偏好（p50 −132ms）。全门绿（test/冒烟/相位×2/burst 120 bot）。报告：[note/report/perf/2026-08-27-login-event-driven-batch88.md](report/perf/2026-08-27-login-event-driven-batch88.md)。
- **join 延迟 tick 量化面全部清零**（87+88 总账：登录 21→4、prepare_spawn 46→0、placeNewPlayer 9→8，剩 14ms 为真实工作）。
- **判例**：①"状态机只在 tick 推进"是 join 延迟结构性来源，事件驱动+主线程串行化+守卫内转移=统一安全配方；②验证 bot 回环可能与 tick 锁相，量化等待测量需防相位偏差。

## 批次 89（2026-08-27）：placeNewPlayer 构成分解 + quit 管线 survey——join/quit 战役收束（勘察轮，无代码变更）

主题：批次 88 后剩余 14ms 的最后两块。**placeNewPlayer 7ms 分解**（bot 追踪前 13 个 play 包）：finish-config 处理 + 协议切换 + ~8 初始包 + recipebook/scoreboard/levelInfo + 实体入世界 + PlayerJoinEvent + 广播，单次主线程执行；**join 突发 13 包同一毫秒到达**（suspendFlushing 原子刷新 + 批次74/75 memo 生效，发送侧零开销）——无残余量化、无可安全下放面（事件/实体入世界必须主线程）。**quit 管线 survey**：事件驱动无量化面，写侧已由批次 79 覆盖，NBT 构建留主线程是活状态一致性所系。报告：[note/report/perf/2026-08-27-placeplayer-quit-survey-batch89.md](report/perf/2026-08-27-placeplayer-quit-survey-batch89.md)。

### 判例
- **join/quit 管线多核战役收束**：读侧（82-84）+ 量化面（87-88）清零后，剩余延迟全部是语义必需的主线程工作，进一步压缩需 Folia 级事件/实体模型重写——超出等价性红线。
- 包到达形状测量（bot trace 已内置 12 包短读）是发送管线健康度的廉价验证手段。

## 批次 90（2026-08-27）：稳态 tick 主线程串行面 survey + 相位剖析基建（多核调度⑪）

主题：join/quit 之外的一般性主线程面。新增 **PapoTickProfile**（`-Dpapo.tickProfile=true|1`，默认关=每相位一次静态布尔检查；0252 探针入 tickChildren 六段 + ServerLevel 三段）+ **行走 bot**（MOVE_POS/KEEPALIVE/PING）+ TickSurveyBench（10 bot × 45s 稳态负载）。报告：[note/report/perf/2026-08-27-tick-survey-batch90.md](report/perf/2026-08-27-tick-survey-batch90.md)。

### 稳态相位画像与结论
- **主线程总利用率仅 3-9%**（4.43ms→1.42ms / 50ms tick，两窗口）——与批次 86 宏观校准互证：主线程在真实负载规模非瓶颈；大规模多核效率问题是 world/entity tick 区域化（Folia 级，超红线）。
- worlds 37-39%（tickPending+misc 22-23% 为最大单项=空维度固定成本，上游已有 disable-world-ticking-when-empty 旋钮，运营建议）；connection 4-6%；sendChunks ≈1%（批次 58-77 网络+区块管线的真实负载验证）；functions/players ≈0。
- **无新可安全消除的量化/阻塞面**（稳态各相位在 tick 内联连续执行，无边界等待）。
- 踩坑三判例：Boolean.getBoolean 只认 "true"；多行 println 续行被日志吞（逐行）；bundler jar 类在嵌套 jar。

## 批次 91（2026-08-26）：停机窗口提交竞态加固——离线程文件管线稳定性轮（多核调度⑫）

主题：goal 后半句"多核调度之后的服务器核心稳定性"。批次 79/82 离线程存档管线
（PapoOrderedFileWrites）的停机窗口提交语义从未实证——`isActive()` 预检与 `queueTask`
之间存在 check-then-act 窗口，authenticator 线程登录读预取与 stopServer 尾部
`haltExecutors()` 的 IO 池排空并发可命中。

### 语义实证（HaltSemanticsProbe，concurrentutil 0.0.8 实跑）
- `shutdown(false)` 排空中 **isActive()=true 但 queueTask 抛 ISE**（预检失效=真实竞态窗口）；
  终止后 isActive=false 仍抛 ISE（一元重载同）；
- `halt(false)` 后 **isActive()=true 且返回真 Task 对象但永不调度**（静默丢弃，提交侧不可
  检测；仅 60s 优雅停机超时后触发，接受为残余面=watchdog 强杀同类）；
- CompletableFuture 把 executor 抛出的 ISE 转成异常完成：簿记一致但**写任务被静默丢弃
  （丢档）**，读的 result future 永不完成（消费方 60s get 兜底；stats/advancements 消费方
  是无界 join()=潜在主线程永久挂起，仅关服竞态下可达）。

### 修复（PapoOrderedFileWrites.java，直跟踪源码）
- 写路径 executor 捕获 ISE → **内联降级执行**（与 isActive 预检的同步降级同一契约）；
- 读路径 executor 捕获 ISE → `task.run()` 内联（result future 必然完成，悬挂类整类消除）；
- awaitPending 恢复中断标志；正常路径零行为变化（不抛时与原提交同构）。

### 验证
- HaltRaceBench G1-G5 修复前后对拍全绿（丢档→执行、悬挂→完成、链 poison→健康、中断恢复）；
- 全量 test ✓；四态冒烟 10/10 零异常、稳态 18.4ms（vs 批次88 基线 19.0ms 持平）✓；
  fat-dat 20-bot burst exit 0 零错误 dats ok（p50 3384ms 在 0.57.0 带宽 3348-3478 内）✓；
- **ShutdownRaceVerify**（新增实弹门）：12 bot 错峰 join 突发中 t=+1200ms 下发 stop →
  exit 0、日志零门错误（扣良性突断与上游 POI 披露）、playerdata 每个 .dat gzip+NBT 全
  合法、同目录重启 boot+join+stop 正常；0.59.0 ×3 轮全绿。
- **race 门捕获上游既有竞态（A/B 归属，非 Papo 回归）**：关服尾部 `isStopped()` 后
  `MinecraftServer.scheduleExecutables()`（CraftBukkit 覆写 `super && !isStopped()`）恒
  false → 任意线程 `server.execute` 走 `doRunTask` **内联**——worker 世界生成的
  `updatePOIOnBlockStateChange` POI 检查因此在 worker 上跑，Paper 主线程断言报 ERROR。
  堆栈零 Papo 行；新生成区块无 stale POI，无数据影响；pre-fix 0.58.1 对照 jar 首轮
  复现（Worker #6 同路径）证明先在性。门单独披露计数（upstream-poi-inline）不计失败，
  不做代码修复（改上游停机语义超红线）。
- 报告：[note/report/perf/2026-08-26-shutdown-window-stability-batch91.md](report/perf/2026-08-26-shutdown-window-stability-batch91.md)。

### 判例
- **第三方池停机语义必须实证而非读名**：isActive 在排空期与 halt 后都会"撒谎"；提交侧防御
  以抛出的 ISE 为准（catch→降级），不能依赖 isActive 预检。
- **上游 `isStopped()` 后 `server.execute` 内联执行**（CraftBukkit `scheduleExecutables`
  覆写）：关服尾部任何线程的 execute 都在调用线程跑——含主线程断言的任务必然报 ERROR；
  该行为与 Paper 区块系统 worker 世界生成叠加产生"关服时 POI off-main"ERROR，属上游既有，
  排查同类关服 ERROR 时先看这条内联语义再考虑自身回归。
- CompletableFuture 会把 executor 异常转成 future 异常完成——任务体不运行但 future 有终态，
  "安静失败"只能靠对拍基准抓。
- 停机窗口加固的正确形态是降级而非重试：与既有同步降级契约统一，不引入新调度假设。

## 批次 92（2026-08-26）：空维度旋钮运营文档 + 系列总收束（documentation-only，无代码变更）

主题：批次 90 遗留的 documentation-only 项收尾 + 多核调度系列（78-92）总收束供决断。

### 交付：[note/ops-empty-world-ticking.md](ops-empty-world-ticking.md)
- 源码级逐段核对 `disable-world-ticking-when-empty`（WorldConfiguration.java:501 /
  ServerLevel.java:820-830）：门内=实体段+区块实体+dragonFight（emptyTime≥300 后跳过）；
  门外=计划 tick/突袭/chunkSource 脚手架/时间天气——**旋钮管不到门外大头**。
- **修正批次 90 口径**：22-23% tickPending+misc 的主要构成在门外，旋钮实测收益限于
  entities/blockEntities 段（空维度实体稀少，收益有限）；剩余为上游结构性支出，
  消除需上游重构（超等价红线）。
- 运营建议（何时开/何时别开/unsupported 档位含义/-Dpapo.tickProfile 验证方法）+
  build.md 交叉引用。

### 系列总收束（批次 78-92）：红线内优化空间逐项证据链

**交付面**（每项有报告+基准+验证矩阵）：
- 池预算（78/80/81）与 worker 曲线探索（80）；
- 主线程阻塞 IO 清算：玩家存档写（79）、level.dat（80）、join 读侧预取（82-84）；
- join 管线 tick 量化清零：prepare_spawn 事件驱动（87，−57%）、登录事件驱动（88，
  join 80→14ms，−82%）；
- 稳态画像基建与验证：PapoTickProfile + 行走 bot（90，利用率 3-9%）、
  ShutdownRaceVerify 停机竞态门（91）；
- 停机窗口稳定性：提交竞态加固（91，0.59.0）。

**否决面**（证据链见各批次报告，不再重开）：
- 实体 unload 存档下放（80：post-event 状态≠vanilla 字节）；
- 逐包 execute 批量化（60：1.49× 劣化）；线程优先级（上游已备）；杂项池 sizing
  （负载不足）；专用 datafix 池（85：+380ms）；并行世界保存（跨世界共享面审计负担
  vs 存档损坏红线）；宏观 worldgen 压测（批次 80/81 机制级+集成级证据已闭合）；
- placeNewPlayer/quit 剩余构成（89：语义必需主线程工作）；
- 稳态 tick 串行面（90：3-9% 利用率，无新可安全消除面）；
- 上游既有关服 POI 内联 ERROR（91：A/B 归因上游，修上游停机语义超红线）；
- 空维度固定成本全额消除（92：门外大头属上游结构，旋钮仅覆盖实体段）。

**剩余空间判定**：进一步的多核效率提升需事件/实体模型区域化（Folia 级重写，超出
"默认行为等价可证"红线）或运营 opt-in（已文档化）。红线内已识别面全部做完。

## 批次 93（2026-08-26）：0.59.0 长时稳态复测 + 停机竞态扩样（回归/验证轮，无代码变更）

主题：用户终止决断未达（问询无应答），按常驻循环指令走无新优化面验证轮。10 行走 bot ×
10 分钟（12,000 ticks 连续）：零错误 exit 0；worlds 36-39% / connection 3.5-5.1% /
sendChunks 0.4-1.5% 与批次 90 画像一致；绝对耗时无上漂（稳态 1.0-1.4ms/tick，末段不升
反降）——批次 78-91 全部离线程管线长时稳定。ShutdownRaceVerify 0.59.0 累计 ×5 全 PASS。
报告：[note/report/perf/2026-08-26-soak-regression-batch93.md](report/perf/2026-08-26-soak-regression-batch93.md)。
无版本号变化（0.59.0 保持）；循环终止/继续仍待用户决断（批次 92 总收束已呈交）。

## 批次 94（2026-08-26）：0.59.0 规模放大验证——20 bot 浸泡 + 竞态扩样（回归/验证轮，无代码变更）

主题：去留问询第二次无应答，默认路径规模放大轮。20 bot × 10min（12,000 ticks）：零错误
exit 0；bot 翻倍主线程各相位仅 +4~9%（次线性，worlds 1167→1273us / connection 120→125us /
sendChunks 42→45us），主线程 ~1.3ms/tick（≈2.6% 利用率）——双倍负载增量被离线程管线吸收，
扩展性如设计生效。停机竞态累计 ×7 全 PASS；本轮 0.59.0 首次抽样到上游 POI 内联触发（与
0.58.1 的 1 次双版本对称），归属链最终闭环。报告：
[note/report/perf/2026-08-26-scale-soak-batch94.md](report/perf/2026-08-26-scale-soak-batch94.md)。
无版本号变化（0.59.0 保持）；循环去留仍待用户决断。

## 批次 95（2026-08-26）：0.59.0 随机种子多世界抽样浸泡（回归/验证轮，无服务器代码变更）

主题：去留问询第三次无应答，默认路径跨种子轮（TickSurveyBench 增可选 seed 参数，默认
papo90 口径不变）。papo95seedB 20bot × 10min：零错误 exit 0；相位占比结构跨种子稳定
（worlds 36.7-39.8% / connection 3.2-4.1% / sendChunks 0.4-1.1%），绝对值 worlds
1672us（vs papo90 1273us，+31% 为地形生成方差，主线程利用率仍 ≈3.3%），无新热点。
停机竞态累计 ×9 全 PASS。累计验证矩阵：长时+规模+种子+竞态全绿。报告：
[note/report/perf/2026-08-26-seed-sampling-batch95.md](report/perf/2026-08-26-seed-sampling-batch95.md)。
无版本号变化（0.59.0 保持）；循环去留仍待用户决断。

## 批次 96（2026-08-26）：40 bot 规模上限探测 + 循环终止（验证轮收口，无服务器代码变更）

40 bot × 10min（种子 papo90，max-players 修复后）：零错误 exit 0。规模曲线终值：worlds
1167→1273→1734us（4× bot 仅 1.49×，次线性；40bot 主线程利用率 ≈3.5%）、connection
120→125→360us（连接数线性主导，绝对值微小）、sendChunks 42→45→65us。停机竞态扩样因
用户终止循环不再执行（累计 ×9 全 PASS 为最终样本）。报告：
[note/report/perf/2026-08-26-scale-ceiling-batch96.md](report/perf/2026-08-26-scale-ceiling-batch96.md)。

---

## 批次 97（2026-08-27）：R2 开篇——harness 真并发修复 + 40/80/120/160 规模阶梯（多核调度系列⑬，勘察轮，无服务器代码变更）

主题：批次 96 的"40 bot"实为 commonPool（并行度 31）欠启动的 ~31 有效并发；修复后
（每 bot 专用线程 + bot≥80 堆 4G）完成真并发阶梯。**connection 相位是唯一强超线性面**
（630→1487→3630→5858us，≈N^1.6-2，归因=聚堆玩家追踪广播 N² 对的主线程 send 排队+
flushQueue 排水）；worlds 2.65×/4× 亚线性；160 聚堆 bot 利用率 24.9% 无滞后，外推饱和
≈300-400 聚堆 bot。join 风暴窗口超限 +6.5%→+19.7% 单调。运行环境：共享机协同租户
周期清扫 java.exe（两次击杀、两点位污染），对策=重试+复测+逐窗口 min。报告：
[note/report/perf/2026-08-27-scale-frontier-batch97.md](report/perf/2026-08-27-scale-frontier-batch97.md)。

### 判例
- **规模类 harness 的并发容器必须核并行度**：`CompletableFuture.runAsync` 走 commonPool，
  任务数>并行度时阻塞型任务（bot 行走）直接压制后续任务启动——"N bot"标签不等于 N 并发。
- **共享机上长窗口基准要做污染防御**：外部进程击杀/争抢的症状是无输出死亡（exit 127/1，
  无 hs_err）或窗口间方差拉宽——带完成判定重试 + 干净轮复测 + 逐窗口 min 三件套。
- **乱码错误的第三形态是 U+FFFD**：`Files.readAllLines(UTF_8)` 对 GBK 字节产出替换符序列
  （终端渲染成"锟斤拷"或问号皆不可信）；过滤规则按码位写（`indexOf('\ufffd')`），
  真实异常消息不含 U+FFFD。

---

## 批次 98（2026-08-27）：tick 相位分解探针——批次97 超线性归因修正 + 聚堆密度面发现（多核调度系列⑭，0.60.0）

主题：0253 补丁 + PapoTickProfile 扩展（tracker.maintain/sendChanges、conn.flushQueue/
listenerTick、server.taskDrain、窗口 gcMs；默认关零行为变化）。40/120/160 分解实测：
**flushQueue 排水假设证伪**（3-8us/tick）；批次97 connection 超线性=争抢伪影+真面混合；
真超线性在 **conn.listenerTick 高密度段**（638→1837→7998us，40→120 线性、120→160
4.35×@1.33×，主嫌疑 LivingEntity.pushEntities 挤堆扫描）。批次99 设计：有界早停
push 扫描（两消费者等价可证）。判例：内部仓库半成品 fixup 卡死恢复法、探针死门分支
零数据、测量轮超线性先过方差关、Windows 管道 JVM stdout=GBK。报告：
[note/report/perf/2026-08-27-tick-decomp-batch98.md](report/perf/2026-08-27-tick-decomp-batch98.md)。

---

## 批次 99（2026-08-27）：有界 push 扫描——聚堆密度超线性面消除（多核调度系列⑮，0.60.0→0.61.0）

主题：0254 补丁。`LivingEntity.pushEntities` 的无界密度扫描 → 双目标有界早停
（`papoGetEntitiesBounded` 三层：ChunkEntitySlices/EntityLookup/Level；listTarget=
max(maxEntityCollisions, cramming) + npTarget=cramming）。等价性：消费者只有前 MEC 个
按序 + 两个布尔（size/非乘骑数 vs cramming 阈）+ 随机消耗奇偶——单向蕴含+逆否闭合；
PushScanBench 随机配置 20,000 组对拍全 PASS。性能：JMH 160 密度模型 590→115ns（5.1×）；
真实 160 bot A/B conn.listenerTick 7998→5461us（−32%，min −30%），player.pushEntities
全 160 人仅 135us（0.85us/人）——批次98 密度超线性归因闭环。四态冒烟全绿（push 是
游戏可见行为）。判例：消费者分析先于扫描优化；对拍基准随机流同种子独立（共享流连续
抽取伪装分歧）；早停等价=单向蕴含+逆否证明模式。报告：
[note/report/perf/2026-08-27-pushscan-bounded-batch99.md](report/perf/2026-08-27-pushscan-bounded-batch99.md)。

---

## 批次 100（2026-08-27）：aiStep touch 扫描 scratch 化（多核调度系列⑯，0.61.0→0.62.0）

主题：0255 补丁。`Player.aiStep` 的每玩家每 tick `getEntities(this, aabb)` 新 ArrayList
（160 聚堆 ≈4800 次/tick）→ per-player scratch + fill 重载，谓词逐字 NO_SPECTATORS。
消费者是逐实体 `playerTouch` 多态派发（公开多态点，Mixin 可注入）——**不可有界化**，
分配消除是精确性上限。A/B 中性如实披露（预期量级低于共享机噪声底；无回归信号）。
判例（复发）：端口孤儿的新症状=稳定快速"server_full"×N 拒绝（bot 连到占 25594 的
孤儿），与击杀的无输出死亡症状不同；重试环前置 netstat 查杀端口占用者后即过。
报告：[note/report/perf/2026-08-27-aiStep-scratch-batch100.md](report/perf/2026-08-27-aiStep-scratch-batch100.md)。

---

## 循环终止记录（2026-08-26，用户决断）

多核调度系列（批次 78-96，0.51.0 → 0.59.0）由用户显式决断终止。终态：
- 优化交付面与否决面证据链见批次 92 总收束；验证矩阵终值见批次 96 报告。
- 循环期间 4 次三选一问询无应答，按默认路径完成验证轮 93-95；第 5 次轮首问询后
  用户于批次 96 运行中下达终止指令。
- 分支 perf/multicore 为系列工作分支；本记录后按全局规则合并回 main（不发布
  GitHub release，goal 明确禁止）。

## R2 轮重启记录（2026-08-27，用户决断）

用户于 2026-08-27 重发同一常驻 goal（多核调度优化+完全重写授权）并开设新分支
`perf/multicore-r2`（自 perf/multicore 同点）。R2 系列自批次 97 起，终止记录对 R2 不再生效；
release 禁令沿用（仍不发布）。
