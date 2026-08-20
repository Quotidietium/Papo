# 批次 68 JMH 微基准报告（2026-08-20）— 自然刷怪与 despawn/merge 域（0235-0237）

刷怪/despawn 域 survey 定案：随机数序列红线全图测绘（level.random 全消耗点 + SHARED_RANDOM + 到达控制流）；落地三项，四项候选按论证留档。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 0235 — NaturalSpawner PreCreatureSpawnEvent 零监听器门控（本批主项）

- **热点**：`isValidSpawnPostitionForType`（NaturalSpawner.java:379-388）每通过距离检查的候选（MONSTER 每 tick 每刷怪 chunk×3 组×每候选）无条件 `CraftLocation.toBukkit`（new Location）+ `new PreCreatureSpawnEvent` + callEvent 空派发——**自然刷怪循环的最高频事件站点**，0165 只门控了刷怪笼侧。
- **改法**：BaseSpawner 同型门控（共享 PreCreatureSpawnEvent 静态 HandlerList；零监听器 callEvent 恒 true、shouldAbortSpawn 仅 false 路径读取 → 恒 SUCCESS/FAIL 路径）。
- **红线**：事件块**不消耗随机**，门控不影响任何 level.random 消耗点到达序列 ✓。
- **风险**：低（同型先例 0165 + 全库 ~40 处门控）。

## 2. 0236 — ItemEntity / ExperienceOrb merge 扫描去分配

- **热点**：每次 merge 扫描（物品每 2-40 tick、XP 球每 20 tick；密集农场局部 O(n²)）分配：`EntityTypeTest.forClass` 匿名类 + 结果 ArrayList + 捕获谓词 lambda（物品侧还有循环体与谓词的重复 `isMergable()`）。
- **改法**：静态无状态 EntityTypeTest 单例 + 每实体惰性 scratch list/谓词（fill-then-iterate 与原语义逐字同；tryToMerge/merge 无重入）+ 删重复谓词（邻体状态在 fill 完成到循环体之间不可能变化：tryToMerge 只触碰 this 与当前元素）。
- **基准**（SpawnScanBench）：

| 指标 | before | after |
|---|---|---|
| avgt ns/op | 110.837 ± 3.800 | 110.650 ± 0.474（时间中性——浅栈 EA 伪影） |
| **gc.alloc.rate.norm（1 fork -prof gc）** | **280.001 B/op** | **0.001 B/op** |

**每次扫描省 280 B 真分配**（接口虚分发下 EA 未消除）——密集农场 GC 压力直接削减。自检（同集合同序）ALL OK。

## 3. 0237 — despawnRanges 按 category.ordinal() 扁平化

- **热点**：`Mob.checkDespawn` **每 mob 每 tick**（EAR 判定之前，不受 inactive 豁免）`despawnRanges.get(category)` HashMap 查找。
- **改法**：配置 `@PostProcess precomputeDespawnDistances`（既有钩子）处按 ordinal 建并行数组（map 由全类别播种、@MergeMap 保键 → 每 ordinal 必有项；数组与 map 同点重建，reload 语义天然一致）；Mob 侧数组直索引。
- **基准**：before_mapGet 1.276 ± 0.023 → after_arrayIndex 0.340 ± 0.007 ns/op（**3.75×**，CI 不重叠）。自检（全 16 类别查值一致）ALL OK。
- **风险**：零（查表替换；配置序列化仍走权威 map，数组 transient）。

## 留档候选（survey 论证完备，未落地）

- **D 首候选 canSpawnMobAt 跳过**（spawnerData 刚从 mobsAt(P) 抽出后对同一 P 的 contains 恒真）：需 pos 相等守卫；中价值，留后续。
- **E createState 的 spawn-cost biome 记忆化**：记忆化只省 Map get（biome quart 查找是主体），收益打折需实测，留后续。
- **F spawn 循环 getNearestPlayer 改 NearbyPlayers 空间查询**（高价值 O(P)→O(1)）：需 NO_SPECTATORS/无界半径语义对齐 + 最近**距离**平局等价论证，中高风险，留专项。**checkDespawn 侧同思路不可证等价**（dy/dxz 分量 + removeWhenFarAway 精确距离 + SHARED_RANDOM），否决。

## 红线图（survey 测绘，留档防误改）

- level.random 全消耗点及到达控制流：ServerChunkCache.java:593（seed）、NaturalSpawner.java:258/262-263/280/301/431/433、Monster.java:87/96、Slime.java:316——检查链（:389-399）中不消耗随机的检查互移安全，相对 checkSpawnRules 移动改变消耗面。
- SHARED_RANDOM：Mob.checkDespawn 的 nextInt(800)（全局序列）；ExperienceOrb.tryMergeToExisting 的 level.random.nextInt。
- 默认配置事实：perPlayerMobSpawns=true → LocalMobCapCalculator 为死路径（其现代化候选 G 价值极低不做）。

## 验证链

compileJava BUILD SUCCESSFUL → 自检 ALL OK → JMH + gc 探针 → rebuildPatches（0235-0237）→ applyPatches → 全量 test（见 optimizations.md 批次 68 记录）。
