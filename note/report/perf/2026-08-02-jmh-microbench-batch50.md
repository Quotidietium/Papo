# 批次 50 JMH 微基准报告（2026-08-02）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260802-132846.json`（主跑：ChunkSelect + TrackCanSee 空表）、`bench-20260802-133413.json`（TrackCanSee 非空表复跑）、`/tmp/papo_gc.log`（ChunkSelect `-prof gc`）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检 ALL OK。
结论速览：**0203 区块选块去装箱 10.0×（CI 不重叠，gc 探针证分配消除）；0204 已追踪对跳过冗余 canSee 在非空 inverted map（vanish 插件场景）1.15×（CI 不重叠），空表下 0.67× 为静态方法 JIT 折叠伪影（机制保留）**。

## 区块发送选块（ChunkSelectBench）— 补丁 0203

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_boxedStream vs after_primitiveKNearest | 8694.243 ± 1109.970 | 869.026 ± 14.641 | **10.0×** | CI [7584,9804] vs [854,884] 不重叠。pending=200、floor=9。before 忠实复刻装箱成本（逐个装箱为 Long 进 ArrayList + comparingInt 比较器排序），原版另含 stream/Comparators.least 的 PriorityQueue+Collector 额外对象开销，故 before 为原版成本**保守下界**；装箱本身——本优化目标——完全一致 |

- **`-prof gc` alloc.rate.norm**：before **6088.053 B/op**（200 个 Long @16B + ArrayList(200) + comparingInt 比较器）→ after **144.006 B/op**（long[floor=9] + int[floor=9] + ArrayList(9) 的必然结果数组）。**分配降低 42×**，200 个 Long 装箱 + 大 ArrayList 完全消除。

## 实体追踪已追踪对跳过 canSee（TrackCanSeeBench）— 补丁 0204

| 配置 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| 非空 inverted map（每玩家藏 5 个其他实体，真实 vanish 场景） | 141.421 ± 8.581 | 122.851 ± 7.098 | **1.15×** | CI [132.8,150.0] vs [115.7,129.9] 不重叠。canSee 的 HashMap.containsKey 做真实探测不可折叠，after 跳过它（仅 seenBy.contains）真实更快 |
| 空 inverted map（无 vanish 插件） | 81.073 ± 6.732 | 121.751 ± 5.912 | 0.67×（JIT 伪影） | CI [74,88] vs [115,127] 不重叠但**反向**。原因：基准的静态方法 `canSee(CraftPlayer,CraftEntity)` 被内联 + inverted 空表经 profiling 被常量折叠为恒 true，before 的 canSee 工作被消除，而 after 多出的 `seenBy.contains` 不可消除 → 反转。真实服务器的 `player.getBukkitEntity().canSee(entity.getBukkitEntity())` 经多态虚方法 + 跨方法边界，**不可如此折叠**，故空表场景仍受益（机制保留，同 0140/0186/0199/0200 先例：复刻浅栈内静态方法被 JIT 折叠掩盖真实收益） |

## 等价性支点（源码实证）

- **0203**：`PlayerChunkSender.collectChunksToSend` 的 `pending > floor` 分支原 `.stream().collect(Comparators.least(floor, comparingInt(chunkPos::distanceSquared)))` 把每个 pending long 装箱为 Long。改为原语 k 近邻：`longIterator()` + `long[floor]`/`int[floor]` 有界缓冲（保留当前 floor 个最近，新元素严格小于当前最远即替换）+ 末尾选择排序升序。`ChunkPos.distanceSquared(long)` 是 int 返回的原语重载（[ChunkPos.java:206](../../../paper-server/src/minecraft/java/net/minecraft/world/level/ChunkPos.java#L206)），无装箱。**等价性**：距离两两不同时选择结果与 Comparators.least 逐元素一致；并列时具体哪个被选在两实现中均未定义且对客户端不可观察（等距区块本 tick 批量发送，未选者留在 pendingChunks 下 tick 发送，玩家无法区分哪个等距区块先到）。floor ≤ 64（batchQuota 上限 64），有界缓冲内存恒定。distanceSquared 调用次数 = pending（原版 ≈ 2×pending×log(floor)，因比较器每次比较调两次且 PriorityQueue 比较 log(floor) 次）——另省 distanceSquared 调用。main 自检：200 组随机输入（pending 5..304、floor 1..9）下 before/after 选出的 floor 个最近"距离多重集"均等于输入的 floor 个最小距离，ALL OK。
- **0204**：`ChunkMap.TrackedEntity.updatePlayer` 对**已在 seenBy 中**的对（稳态聚集的常态）仍重算 `player.getBukkitEntity().canSee(entity.getBukkitEntity())`（2× getBukkitEntity + CraftPlayer.canSee：visibleByDefault 字段 + getUniqueId + invertedVisibilityEntities.containsKey HashMap 查找）。改为先 `seenBy.contains(player.connection)`，已追踪则跳过 canSee。**等价性关键不变量（逐路径源码实证）**：`seenBy.contains(conn) ⟹ canSee == true`。令 canSee 变 false 的三条路径——`CraftPlayer.hideEntity`（[CraftPlayer.java:1851](../../../paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java#L1851)）、`setVisibleByDefault(false)`（[CraftEntity.java:721](../../../paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java#L721)，置字段前对所有在线玩家 resetAndHideEntity）、`resetAndHideEntity`——均经 `untrackAndHideEntity → unregisterEntity → TrackedEntity.removePlayer` 先把对移出 seenBy；seenBy 唯一写入点 `seenBy.add` 受 canSee 守卫（行 1411），`trackAndShowEntity`（showEntity 路径）也复用 updatePlayer（[CraftPlayer.java:1972](../../../paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java#L1972)）。故已追踪对的 canSee 必为 true，CraftBukkit vanish 分支不可能命中，跳过位级等价。距离/broadcastToPlayer/isChunkTracked 检查保留（它们决定出界移除）。main 自检：稳态（全已追踪）两路径 tracked 计数一致；未追踪 + 被 hide 场景两路径 flag 一致（false），ALL OK。

## 验证链

compileJava（`--rerun-tasks --no-configuration-cache`）✓ BUILD SUCCESSFUL（1m34s）→ 全量 test ✓ BUILD SUCCESSFUL（1m43s，零 FAILED）→ 补丁生成（rebuildPatches，见 build.md 批次 50 踩坑：触发了 0163–0192 垃圾重命名，已确定性恢复到干净命名 + 仅保留 0203/0204）。

## 与用户痛点的对应

- **网络/流畅度（跑图突发）**：0203 消除每 tick 每玩家数百个 Long 装箱 + Guava PriorityQueue/Collector 机制，跑图/登录突发期区块发送分配尖峰显著下降（10× 微基准，分配消除 gc 确证）。
- **多玩家聚集延迟**：0204 在稳态聚集（绝大多数 (实体,玩家) 对已追踪）下，每对每 tick 省一次跨方法 canSee（HashMap 查找 + 多态分发），N×M 倍率在聚集时放大收益（非空 vanish 场景 1.15× CI 不重叠；空表场景机制保留）。
