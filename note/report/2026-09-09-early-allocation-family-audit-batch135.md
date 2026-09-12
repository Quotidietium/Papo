# 早期分配消除族对抗审计（批次 135，2026-09-09）

> **批 138 勘误（2026-09-12）**：本报告范围头声明 0156-0204 全覆盖，但
> **0186（ItemStack encode 走 ItemObfuscationSession.withItemStack）不在任何
> 分组清单/段落内**（§10 网络侧微优化清单为 0052/0053/0127/0133/0134/0141/
> 0146/0187/0203，无 0186；其余分组亦无）。批 137 §9 曾将 ItemObfuscationSession
> 错误映射到"本报告 §9 已审"。0186 及其 src/main 侧改动（withItemStack 直路 +
> start() 每级别上下文缓存）已由批 138 首次真审闭合（零缺陷：no-op 分支/新鲜
> 上下文/switch-restore 逐句恒等，record 不可变 + per-thread 无共享）。
> 见 [2026-09-12-fork-baseline-modification-audit-batch138.md](2026-09-12-fork-baseline-modification-audit-batch138.md)。

> 范围：批 132（R4 红石族）、批 133（R1 出站包管线/登录族）、批 134（入站读侧/
> 容器交互族）之后的第四面——**早期分配消除族**共 145 个补丁：0040-0065
> （NBT/实体/区块/事件分配消除；0040 已入批 134 §7 复核）、0069-0074/0076-0083/
> 0086/0088-0090（微优化杂项；0066-0068/0075/0084/0085/0087/0091-0103/0137-0140/
> 0153-0155 已入批 134）、0104-0136/0141-0152（移动/AI/传感器/事件路径）、
> 0156-0204（AABB 收敛/scratch 复用/零监听器门/vec3 内联）。
> 至此 Papo 自有补丁（0001-0266）四个家族全部获得对抗审计覆盖。
> （批 136 勘误：本句与四家族报告显式范围清单核对不实——0205-0212 与 0230-0240
> 共 19 个补丁不在 132/133/134/135 任一范围内，批 136 已补审闭合。）
> 对齐流目标：长期高负载多用户下的 scratch 复用逃逸/重入、零监听器门的事件
> 语义等价（含取消分支副作用）、折叠数学的逐算子一致性、不可信输入面、
> 内存界。方法：逐补丁对已应用源码树对抗读 + 对 vanilla 语义的构造性等价
> 核对 + Bukkit API HandlerList 权威性逐表定性（继承静态链/子类同表）。
> 已接受缺陷（2026-09-03 用户决断）0267/0268/0269 只核对未复发，不重报。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | NBT 写/合并迭代（0040/0047） | **闭合**（fastIterator entry 不逃逸；0040 批 134 §7 已复核，0047 同模式：put 的是 tag.copy()，键为不可变 String） | §1 |
| 2 | 实体运动/碰撞/磁吸（0041/0042/0049/0104/0105/0114/0135/0176/0180） | **闭合**（碰撞列表 tick 线程序列复用、mutable 不逃逸证明：getHeight/getFlow 只读坐标、lastLavaContact 存 immutable() 拷贝；absSnapTo clamp/%360 公式逐项对账；knockback while 环卫排除 normalize 零支路） | §2 |
| 3 | 容器/菜单/酿造（0043/0051/0063/0110/0144） | **闭合**（0051/0110 门控判据与被调方法内部判据逐字同一——ItemStack.matches 静态同参、RemoteSlot.matches 同接收者；0063 首跳等值跳过经 LevelChunk 同态短路证明；0144 引用键缓存对原位变更透明） | §3 |
| 4 | 区块 tick/刷怪/流体（0044/0045/0046/0106） | **闭合**（0044 size()>1 跳 removeAll 经"列表永不插入 null"全库核对；0106 tick 的 state 复用经 ServerLevel.tickFluid/LevelChunk.postProcessGeneration 两调用点即取即用证明） | §4 |
| 5 | 零监听器门家族（0050/0078/0079/0107-0109/0117-0119/0121-0126/0132/0162/0165/0181-0185/0189/0193-0199） | **1 处真实缺陷（F4，已修）**；其余闭合——门键权威性逐表定性：无自有表的子类（EntityTargetLivingEntityEvent→EntityTargetEvent、PreSpawnerSpawnEvent→PreCreatureSpawnEvent、PlayerArmSwingEvent→PlayerAnimationEvent、FurnaceStartSmeltEvent→InventoryBlockStartEvent、FurnaceSmeltEvent→BlockCookEvent）按父表门控正确；自有表子类（PlayerInteractAtEntityEvent、PlayerArmorStandManipulateEvent、PlayerPickupArrowEvent、FurnaceSmeltEvent 的注册面）各自分立，AT 变体按 AT 自有表门控正确 | §5 |
| 6 | AABB/盒折叠家族（0113/0116/0161/0163/0164/0166-0169/0171/0179） | **1 处真实缺陷（F3，已修）**；其余闭合——expandTowards 三元形（非 Math.min）逐字节对齐、PistonMath 六向 union 操作数序同序、0166 位置键缓存 NaN 永不匹配即重算、Hopper.SUCK_AABB 常量与 worldPosition final 证明 | §6 |
| 7 | vec3 内联家族（0058/0060/0069/0081/0173-0177/0180） | **闭合**（FP 链逐算子：normalize sqrt 表达式同序、1.0E-5F 阈值分支保持、±0.0 项被 acos/±1.0 吸收论证；0081 int 溢出面经 post() 半径≤80 有界排除——撕裂场景不可达） | §7 |
| 8 | AI/大脑/传感器/寻路（0048/0055/0072/0076/0111/0115/0120/0130/0131/0142/0143/0147-0152/0192/0195/0200-0202） | **闭合**（LookControl 布尔协议全库无其他覆写者；Brain 活迭代与 vanilla startEachNonRunningBehavior 同构（行为集成员运行期不变）；0151/0152 epoch+引用双键经 PAPO_CONFIG_EPOCH reload 递增点核对；0147 getEffectiveRange 无每玩家状态、提升合法；scratch 列表重入面逐一排除） | §8 |
| 9 | 事件路径命令式改写（0069/0070/0127/0157/0158/0172/0188/0117 辅助） | **1 处真实缺陷（F2，已修）**；其余闭合——0070 与应用树 collectArguments 全节点扫描精确一致；0157 record 等价镜像（跳过等值 re-put 经 LinkedHashMap 保位+值语义不可分证明）；0158 Inventory.contains(Predicate) 即本迭代器 | §9 |
| 10 | 网络侧微优化（0052/0053/0127/0133/0134/0141/0146/0187/0203） | **闭合**（RegistryOps 不可变访问缓存并发安全（ConcurrentHashMap 适配器+volatile 良性竞态）；0203 k-nearest 平局选择双方均未指定且批次内不可观察论证；0129 Deflater 单槽池沿批 134 §8 判例——double-close 仅理论不可达，调用点均单次关闭） | §10 |
| 11 | 内存界 + 已接受缺陷状态 + 不可信输入面 | 无新增无界结构（scratch 字段全部每实体/每目标一个、clear 复用；Identifier toString 缓存每实例一个引用）；0267/0268/0269 决断维持未复发；全部零监听器门只读静态 HandlerList/不可信输入不可达 | §11 |
| 12 | 构建验证 | check_patch_counts ALL OK + applyPatches 全量重放 EXIT=0 源码树同步（四处修复在树）+ compileJava --rerun-tasks BUILD SUCCESSFUL | §12 |

## §1 NBT 写/合并迭代（0040/0047）

0040 已在批 134 §7 审过。0047 merge 用同模式 fastIterator：entry 不逃逸
（put 的是 tag.copy()，键为不可变 String）；`this` 与 `other` 为同一对象时
的 CME 暴露与 vanilla keySet+get 路径相同（重入合并 vanilla 同样未定义）。
Object2ObjectOpenHashMap instanceof 分支回落 entrySet().iterator() 覆盖非
fastutil 构造（如不可变包装）。闭合。

## §2 实体运动/碰撞/磁吸

- **0049** 四个碰撞列表提升为每实体字段：collide() 内无重入（CollisionUtil
  纯消费、无回调进入 collide），Moonrise 区域化 tick 下单实体单线程；
  列表滞留一 tick 的引用界可忽略（AABB 纯 double，VoxelShape 多为共享）。
- **0104** papoFluidMutablePos 逃逸面全查：FluidState.getHeight/getFlow 仅
  读坐标（运行期仅 FlowingFluid/EmptyFluid 两实现），lastLavaContact 存
  mutablePos.immutable() 拷贝（Entity.java:4906）；单 chunk 快路的
  getChunk(FULL,false) 空返回 NPE 语义与原路径一致。
- **0105** 组件化速度：a-b 与 Vec3.subtract 的 a+(-b) 位等（IEEE 取负精
  确）；getKnownSpeed 惰性重建，Vec3 不可变无别名面。
- **0114/0132/0176/0180** 内联 inflate/expand/delta 全部逐算子对账（见
  §7 判据）；0132 的 to-Location 标量公式与 Entity.absSnapTo/absSnapRotationTo
  逐项一致（x/z clamp ±3.0E7、y 不 clamp、yaw%360、pitch clamp±90 后%360），
  hasPos/hasRot 分支矩阵覆盖。
- **0041/0042** trivial 等价（0042 的死分配经 Paper 重写路径 return ret 证明）。

## §3 容器/菜单/酿造

- **0051/0110**：门控判据 `!ItemStack.matches(lastSlots.get(i), item)` /
  `!suppressRemoteUpdates && !remoteSlots.get(i).matches(item)` 与
  triggerSlotListeners/synchronizeSlotToRemote 内部判据**逐字同一**（静态
  matches 同参、RemoteSlot.matches 同接收者同参）；memoize 惰性使无消费
  时零拷贝，与原路径"恒建 supplier 但无人 get"等价。
- **0063**：potionBits scratch 复用 + lastPotionCount 独立化；首次空台面
  跳过 updateBlockState 经 LevelChunk.setBlockState 同态短路（blockState ==
  现态即 no-op）证明不可观察；getPotionBits 唯一调用点 serverTick 即用即弃。
- **0144**：SingleRecipeInput 按槽内栈**引用**键缓存——原位变更（shrink）
  经包装器可见、整栈替换换引用即重建；record 等价使任何以 equals 为键的
  下游（CachedCheck）无感。

## §4 区块 tick/刷怪/流体

- **0044**：跨 tick 复用 removal 集合 + `size()>1` 跳过 removeAll——全库
  核对 blockEntityTickers 无 null 插入路径（addBlockEntityTicker 走 pending
  交换，1401-1407），null 哨兵仅为 Paper 防御；tick 循环内出现 null 本就
  NPE（isRemoved 直接解引用），跳过不弱化任何可达行为。
- **0045**：distToCenterSqr 标量替换与 closerToCenterThan(Vec3,r) 同式
  （24²=576.0 精确）；同 chunk 快路 == ChunkPos.equals 真分支。
- **0046**：blockState.getFluidState() 与 level.getFluidState(pos)（=chunk
  查询后取 state.getFluidState()）值同源。
- **0106**：tick 的 state 参数复用——ServerLevel.tickFluid 先
  getBlockState(pos) 即刻传入、LevelChunk.postProcessGeneration 同迭代取，
  两个调用点之间无世界变异；BlockFromToEvent 门沿批 134 已判决模式。

## §5 零监听器门家族（22 门 + 事件语义等价）

**F4（已修）**：0112 双拾取事件零监听器快路的 `!getCanPickupItems()` 取消
分支漏掉 vanilla 会执行的 `entity.take(this, count)`（飞行动画）。
`bukkitPickUpLoot` 对**玩家**可由插件置 false（CraftLivingEntity.getCanPickupItems
对非 Mob 走该字段），可达；修复为取消分支补 take，与原路径（flyAtPlayer 从
事件默认 true）逐句对齐。0107 早期已自修 flyAtPlayer 初值 true，本轮补齐
其对偶遗漏。

**门键权威性核对**（本轮重点，全部经 paper-api 源文件定性）：
- 无自有 getHandlerList 的子类，监听器注册进父表、发射亦查父表（继承静态
  方法）：EntityTargetLivingEntityEvent、PreSpawnerSpawnEvent、
  PlayerArmSwingEvent、FurnaceStartSmeltEvent、FurnaceSmeltEvent——对应
  门全部按父表键（0124/0165/0185/0182-startsmelt/0182-smelt/0183）正确。
- 自有表的类型各自分立：PlayerInteractAtEntityEvent（0194 的 AT 变体按 AT
  自有表门控，正确）；PlayerArmorStandManipulateEvent/
  PlayerPickupArrowEvent 在独立发射点，不影响本族门。
- **0182 熔炉三门**：FurnaceBurnEvent 自有表零监听器投影与快路逐句对齐
  （getBurnTime=构造参数即 getBurnDuration 同式、isBurning/willConsumeFuel
  默认 true）；FurnaceSmeltEvent 快路以 result.copy()/grow/isSameItemSameComponents
  复刻 asBukkitCopy→asNMSCopy 往返（CraftItemStack 往返引理，0183 同）。
- **0183 营火**：itemStack1 可能别名槽内栈（无配方时 orElse 原样），快路
  付一次 copy() 保证 split 循环不原位改容器槽——往返两拷贝省一。
- **0126**：按玩家复用事件实例，仅当实例恰处默认态（未取消且半径==chunkRange）
  才复用，否则重建；读者（ChunkMap）只读两字段。
- **0124**：Mob.setTarget/TemptGoal/TemptingSensor/EndermanAttackPlayerEvent
  四门共享 EntityTargetEvent 表键正确（含 EntityTargetLivingEntityEvent 无
  自有表定性）；零监听器下 getTarget() 恒构造值使 target 不变的论证成立。
- 其余门（0050/0078/0079/0108/0118/0119/0121-0123/0132/0162/0181/0184/
  0189/0193-0199）零监听器投影逐句等价；0121 的异步路径线程检查差异沿批
  134 对 0100 的已判决 tradeoff（合规用法不可达）。
- **0117**：handleBlockFadeEvent = 零监听器返 false（未取消）+ 有监听器走
  原调用，16 个调用点反演算一致。

## §6 AABB/盒折叠家族

**F3（已修）**：0179 把 checkInsideBlocks 的
`makeBoundingBox(to).deflate(1.0E-5F)` 折叠为基础 EntityDimensions 公式，
但 `Entity.makeBoundingBox(Vec3)` 被 **Interaction / Shulker（贴面朝向 +
peek 进度盒）/ AbstractWindCharge（y-0.15 偏移盒）** 三处覆写——折叠绕过
虚分派使这些实体的方块内效果扫描（气泡柱/蜘蛛网/细雪/水推/传送门等
InsideBlockEffect 路径）用了错误盒子。回退为保留虚调用的原式；补丁留档
注释（编号不回收）。**判例：折叠"纯函数"调用链前必须核对该虚方法在整个
类层次的覆写面——数学等价不构成正确性，"无覆写"这一结构事实才构成。**

其余闭合：
- **0167/0171**：expandTowards 在应用树为**三元形**（x<0 加 min 侧/x>0 加
  max 侧，NaN 两分支皆不入），折叠的三元形逐字节一致；-1.0/+1.0 常量吸收
  -0.0 边。
- **0168**：PistonMath 六向 + minmax 的操作数**同序**（area 在前 moved 在
  后）逐向核对，getExtendedProgress/progress/delta 链同参。
- **0169**：inflate(0) 值等价论证（实体盒 max = pos+非负半宽永不为 -0.0，
  min-0.0 保持），返回实体自身盒只读无变异。
- **0113/0116/0166**：Hopper.SUCK_AABB 为接口常量（HopperBlockEntity 不覆
  写）、worldPosition final；0166 矿车漏斗按位置双键缓存（NaN 永不匹配即
  重算）、取物 AABB 按 bb 实例键（setPos/移动天然失效）。
- **0161/0163/0164**：折叠式与链式左结合序一致（含 (y+1)+d+h 关联序、
  int 项一次性展宽精确）。

## §7 vec3 内联家族

- **0058/0069/0081**：distToCenterSqr/坐标化 argmin/整型化距离——0081 的
  int→long 算术升级经 post() 半径界（≤80 块）排除 vanilla int 溢出撕裂面
  （46km 级 delta 不可达）。
- **0173/0174/0175/0176/0177/0180**：normalize 的
  `sqrt(x*x+y*y+z*z) < 1.0E-5F ? ZERO : 分量/len` 逐处保持（含 0174 火
  球加速/0175 视线输入/0177 视角点积）；水平化丢弃 y 项的 ±0.0 贡献被
  Math.acos(±0.0)=π/2 吸收；0176 的 while 环卫（x²+z² ≥ 1.0E-5F ⇒ len ≥
  3.16E-3）排除 normalize 零支路；0180 的 axis get 以 switch 还原。
- **0060**：inv = 1.0F/ceil 先 float 后展宽与 vec3.scale(float→double) 同
  值同舍入；step 分量乘除关联序保持。

## §8 AI/大脑/传感器/寻路

- **0048**：hasXRotD/hasYRotD 布尔协议——全库核对 getXRotD/getYRotD 仅有
  Shulker 一处覆写（已同步转换），无外部调用者依赖被保留的 Optional 版
  （委托保持语义）。
- **0055**：Brain 活迭代与 vanilla getRunningBehaviors 的快照差异仅在"行
  为 A 的 tick 使行为 B 停止"时可观察——BehaviorControl 接口无跨行为停
  止路径（理论不可达记录）；行为集成员 addActivity 后运行期不变，无 CME。
- **0111**：GateBehavior anyRunning 融合依赖"兄弟 tick 不能启动已停行为"
  （同上接口事实）；computeIfAbsent→get/put 两段式在单线程寻路 + 无重入
  下等价（三处逐一核对重入面）。
- **0147**：getEffectiveRange 只读 this.range/乘客列表/平台钩子（无每玩
  家状态），sweep 级提升合法；对外 updatePlayer(player) 委托保持逐调语义。
- **0149**：内联与 runIterationOnEquipment/runIterationOnItem 逐句一致
  （EquipmentSlot.VALUES 序、matchingSlot 过滤位、EnchantedItemInUse 每物
  一次）。
- **0150**：OneShot 改写与声明式链的 gate（present）/erase（MemoryAccessor
  → eraseMemory）逐点映射；debugString 文本差异仅在脑调试转储。
- **0151/0152**：tickRate 缓存以 (PAPO_CONFIG_EPOCH volatile 静态,
  level.paperConfig() 引用) 双键——epoch 在 reload 入口递增（PaperConfigurations
  :332，部分失败也失效），主线程读无需额外同步。
- **0130/0131/0142/0143/0195/0200/0201/0202**：TargetingConditions 字段
  复用（range() 纯字段写、传感器每实体一份）、scratch 列表重入面注释与
  本轮独立复核一致（唯一回调 EntityPickupItemEvent/无重入路径）；
  0195 的 above() 恒等缓存依赖 blockPos 只被重赋值（stop()=ZERO、
  findNearestBlock 即返），StriderGoToLavaGoal 整方法覆写不受影响。
- **0115/0072/0076/0120**：MutableBlockPos scratch 经 getPathTypeStatic 入
  口即读坐标快照证明；followThePath 节点字段直读与 asBlockPos 坐标恒等；
  timeoutCachedNode 仅比较与 atBottomCenterOf 消费。

## §9 事件路径命令式改写

**F2（已修）**：0172 钓鱼开放水域扫描——原
`betweenClosedStream(pos.offset(-2,i,-2), pos.offset(2,i,2))` 两角同 y、
每层恰 25 块；改写误为 `minY..minY+4` **五层**（注释自称 125/area 与实际
 vanilla 25/area 相悖——注释数字会说谎）。水面附近上四层（多为空气→
 ABOVE_WATER）与水层混合使 reduce 折叠 INVALID、calculateOpenWater 翻
 false，**开放水域宝藏判定系统性丢失**（数据面：钓鱼战利品表选择）。
修复为 x/z 双循环固定 minY 单层。**判例：命令式重写流式坐标遍历时，遍
历集合必须逐坐标对账——"迭代器同型"不蕴含"坐标域同集"。**

其余闭合：
- **0070**：早退助手的"跳过 null 值后继续扫描"与应用树 collectArguments
  的全节点收集精确一致（该树版本的 of() 本就收集全部非空签名参数）。
- **0157**：MapDecoration 逐字段预比较镜像 record 等价（Objects.equals 分
  量同判）；等值跳过 re-put 经 LinkedHashMap 同键保位 + 值不可分（消费
  者按值读）证明不可观察。
- **0158**：Inventory.contains(Predicate) 实现即 `for (ItemStack : this)`
  同迭代器同短路，内联判定逐字同体。
- **0127**：ListTag/CompoundTag instanceof 直判与 Optional 包装同判（含
  getAsByteArray 返回同一后端数组、DataLayer 存引用同 vanilla）。
- **0053/0056/0062/0083/0089/0090/0119-0126/0136/0141/0145**：流→循环/迭代
  器序保持、Collectors.toMap→HashMap 同插入序、PoweredRail 等参数透传一
  致；0083 的未检查转型由谓词 instanceof 保证（调用方只读）。

## §10 网络侧微优化

- **0133/0134**：ImmutableRegistryAccess 的 NBT RegistryOps 每访问缓存
  （volatile + 良性竞态重建等值对象）；HolderLookupAdapter 查找缓存为
  ConcurrentHashMap，共享实例跨线程安全；非不可变访问回落每调用新建。
- **0203**：原始类型 k-近邻——平局边界选择在 Guava Comparators.least 与
  本实现均为未指定，且等距 chunk 本批次先发后发客户端不可分（未选中者
  留 pending 下批发）；floor=0 退化与 least(0) 同空。
- **0052/0146/0187**：容量预置不经 List API 可观察；0052 高度图 HashMap
  插入序=流遇序，序列化字节不变。
- **0129**：写侧 Deflater 单槽池——借还平衡（borrow 即取走）、换级退役、
  close 幂等性缺口沿批 134 §8 判例（double-close 无调用点，Moonrise/
  RegionFile 写路径均单次关闭）；缓冲加大仅改调用分块不改字节流。
- **0057**：无限制 NbtAccounter 短路——getUsage 全库无读者（仅异常消息
  内自用，已不在短路路径上）。

## §11 内存界 + 已接受缺陷 + 不可信输入

- scratch 字段（pos/列表/谓词/事件）全部每实体或每目标恰一个、clear 复
  用，无跨实体共享、无无界增长；Identifier toString 缓存每实例一引用
  （注册表内实例有界）。
- 0267 无界 join 等待 / 0268 locale 冻结 / 0269 缓存常驻：决断维持，本族
  无新增同类结构。
- 不可信输入面：零监听器门全部只读**静态** HandlerList（与玩家输入无
  关）；钓鱼/拾取/容器路径对恶意包的边界与 vanilla 同源（服务端权威判定
  未放松——F2/F4 修复反而收回到 vanilla 语义）。

## §12 构建验证

- `python note/check_patch_counts.py` → ALL OK。
- `./gradlew applyPatches` 全量重放 → BUILD SUCCESSFUL（0064/0112/0172/
  0179 四补丁在列，重放树含全部四处修复）。
- `./gradlew :paper-server:compileJava --rerun-tasks` → BUILD SUCCESSFUL。

## 三判例入库

1. **折叠"纯函数"虚调用链前必须核对类层次覆写面**（F3/makeBoundingBox
   判例）：算术逐位等价是必要非充分——被折叠的调用若是可覆写的实例方法，
   覆写者即静默旁路。下放/折叠补丁的等价证明须包含"无覆写"或"覆写与基
   式同构"的结构性论据。
2. **命令式重写流式坐标遍历时遍历集合逐坐标对账**（F2/betweenClosedStream
   判例）：两角相同的闭区间流是单层 5×5 而非 5×5×5——重写注释里的数字
   （"125 per area"）与原实现数字（25）不符即是缺陷自供；对账对象是坐标
   域本身，不是迭代顺序。
3. **零监听器快路的取消分支同样承载可观察副作用**（F4/entity.take 判
   例，批 134 F1"判据求值时点"判例的取消分支对偶）：快路复刻的是原路径
   的**全部**分支投影——含飞行动画这类"非状态"输出；bukkitPickUpLoot
   这类插件可写状态（对玩家可达）把理论死支变成可达面。

## 工作流备注（本轮新增）

- rebuildPatches 在本环境从**已提交的补丁系列**重建（会覆盖手改中的补丁
  文件）；本轮四处修复沿批 133/134 的"补丁内手改 + applyPatches 全量重
  放"路线完成。手改补丁期间勿混用 rebuildPatches。
