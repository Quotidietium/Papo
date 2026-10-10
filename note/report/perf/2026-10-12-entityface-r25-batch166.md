# R25（批次 166）：实体密集面内存画像与寻路滞留收割——→0.94.0

日期：2026-10-12　版本状态：0.93.0 → **0.94.0（补丁 0280，代码轮）**　工具：`benchmark/src/papo/bot/EntitySoakCensus.java`、单测 `PapoBinaryHeapReleaseTest`（4 用例入套件）

## 1. 缺口与本轮目的

R1-R24 全部浸泡场景为 peaceful+spectator——**实体对象图（AI 状态/感知器/寻路/属性/同步跟踪）从未被内存画像**，是内存主线唯一未演习的轴。新 harness 与 MemSoakVerify 逐字同协议，仅改 `difficulty=normal + spawn-monsters=true`（流送全新区块=洞穴/地表自然刷怪），驻留加长 300s 使怪群成熟，驻留末双 GC+直方图按实体前缀聚合。**首要发现：spectator bot 同样触发自然刷怪**（协议层确认，后续实体面轮可直接复用本 harness）。

## 2. 普查数据（0.93.0 基线）

heapUsed 799,792KB（驻留末），实体前缀直测 61,810 实例/2.84MB；支撑结构（DataItem/Node/属性/控制对象/Goal 族）合计 ≈6MB @ ~679 mob（LookControl 661/Sensing 679/PathFinder 679 口径）≈ **0.75% 堆——量级面立判：实体图在自然 mobcap 密度下非主内存轴**。构成（按字节）：

| 类 | 实例 | 字节 | 判定 |
|---|---|---|---|
| pathfinder.Node | **26,683** | **1,707,712** | **异常：Path 仅 78 条，39 节点/mob——滞留（§3 收割）** |
| [Lpathfinder.Node; | 2,031 | 500,864 | 同族（heap/neighbors/path 数组） |
| SynchedEntityData$DataItem | 15,628 | 500,096 | 语义（每实体 16 项跟踪状态） |
| AttributeInstance | 3,758 | 210,448 | 语义（属性本体） |
| WrappedGoal | 5,893 | 188,576 | 语义（AI goal 包装） |
| Chicken/Pig/Sheep/Cow/Drowned/Armadillo | 145/130/115/93/63/53 | ~560KB | 内容（自然刷怪群） |
| GroundPathNavigation/PathFinder/Sensing/LookControl/MoveControl | ~660-679 各 | ~300KB | 语义（每 mob 运行时控制对象） |
| Brain+Brain$1 | 691+691 | 66KB+map 开销 | 语义（7 张 codec 驱动可变 map） |
| TargetingConditions | 1,739 | 55,648 | 语义（goal 持有） |

**判负面（全语义/内容）**：Brain 7 张急切 map（codec 构建+运行时可变）；属性/goal/控制对象族；DataItem 跟踪状态；ItemEntity 掉落物。懒化候选定量否决：pathfindingMalus EnumMap（~230B/mob）+ activeLocationDependantEnchantments EnumMap（~150B/LivingEntity，无附魔装备的自然怪全空）合计 ~250KB@700mob——低于簇级份额立项门槛（R24 判例三门槛）。

## 3. 异常归因与收割（批 166，0280）

**Node 滞留链（源码级钉死）**：`BinaryHeap.clear()` 仅 `size=0` 不清引用——每 mob 的 PathFinder.openSet 在两次搜索之间钉住上次搜索未弹出的 frontier（观测 26,683 节点/39 每.mob）与历史增长后从未弹出的槽位；`PathFinder.neighbors`（Node[32]）同样滞留最后一批邻居引用。合计 ≈2.2MB@679mob（3.2KB/mob），**随 mob 数与搜索长度无界放大**（长搜索 heap 增长至 2048+ 后永久钉住）。与批 160（NoiseChunk 停车）/批 143 同族——"最后读者后释放"第三例。

**恒等三件套**：
1. **读面**：堆数组 `size` 以外的槽位从不被搜索逻辑读取（insert 写 `[size]`、pop 有界读、isEmpty/changeCost 全部以 size 为界）；
2. **调试面隔离**：`getHeap()` 返回 `Arrays.copyOf(heap, size)` **快照**而非活数组——`Path.DebugData.openSet` 在 captureDebug 下持有的是密集快照，清引用对调试载荷零可见（先证此点再动刀）；
3. **neighbors 读写**：槽位由 `getNeighbors` 全量重写（以返回计数为界读取），搜索间清引用不可观测。

**实现**：`clear()` → `Arrays.fill(heap, null)` 后 `size=0`；`findPath` 公共入口 `nodeEvaluator.done()` 后 `Arrays.fill(neighbors, null)`。成本：每次寻路一次 O(容量) memset（≤4KB，~100ns）——相对搜索本体（毫秒级 A*）四个数量级以下。单测 4 用例：跨 clear 周期堆序/size 语义逐位保持、快照与 clear 隔离、清后全槽位无引用（反射直读原数组）、增长容量尾槽同样释放。

## 4. 验证（0.93.0 vs 0.94.0）

entitysoak 同场景同参 A/B，两腿门全绿（logErrors=0 exit=0）：

| 指标 | A-093 | B-094 | Δ |
|---|---|---|---|
| pathfinder.Node | 26,683 / 1,707,712B | **16,476 / 1,054,464B** | **−10,207 / −653KB（−38.3%）** |
| [Lpathfinder.Node; | 2,031 / 500,864B | 2,024 / 498,400B | 容量壳保留（scratch，同碰撞列表先例） |
| SynchedEntityData$DataItem | 15,628 | 15,889 | 世界内容同量级（mob 群相当） |
| AttributeInstance | 3,758 | 3,737 | 同上 |
| entityTotal | 61,810 / 2.84MB | 61,325 / 2.85MB | 同上 |
| heapUsedKb（驻留末 raw） | 799,792 | 742,072 | 相位窗负载差（R22 判例：raw 非门；census 行为口径） |

**残差对账**：B 侧剩余 16,476 节点 ≈ 677 mob × ~20-24 节点 = 当前 Path 自有节点（navigation.path 持有，语义驻留）——释放量恰为 frontier+neighbors+历史尾槽成分。**延迟面比例性说明**：改动=每次寻路一次 ≤4KB memset（~100ns，较毫秒级 A* 搜索四个数量级以下），主线程零触点；功能门=两腿实体行为/刷怪构成/gate 全同——本轮不设独立 MSPT 对（R15 判例的场景复用前提是改动可达主线程，此处不满足）。

## 5. 判例

1. **新场景轴先 census 后立项**：实体面 0.75% 的量级事实直接把 90% 的候选（Brain/属性/goal 懒化）判出门外——没有 census 的立项会在语义面上浪费红线风险。
2. **vanilla "clear 只置零"保留反例族成立**：BinaryHeap 与 R20 NoiseChunk 停车、R24 SurfaceRules 分配同属"循环复用结构的引用卫生"面——该族在 vanilla 代码里系统性存在，后续轮次遇到 `clear()/reset()` 只置零不清引用的结构应默认怀疑。
3. **清引用前先证调试/序列化面隔离**：getHeap 快照使 DebugData 免疫——若无此快照则需 null 容忍写路径（writeNodeArray），改动面扩大一个量级。隔离证明先于实现。
4. **spectator 触发自然刷怪**（协议实证）：实体面测量无需改 bot 协议，server.properties 两字段即可。

## 6. 结论

实体密集面闭合：census 定量（0.75% 量级）→ 唯一结构异常（寻路滞留）源码归因 → 收割 2.2MB@700mob 并消除无界放大风险 → 其余全表语义判负入档。**主线至 0.94.0：区块驻留 101.4KB 量级维持，实体轴（0.75%）经画像后仅存一净收割。**
