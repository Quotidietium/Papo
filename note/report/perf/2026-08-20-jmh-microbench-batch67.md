# 批次 67 JMH 微基准报告（2026-08-20）— 红石消费代码域（0232-0234，编号以补丁文件为准）

计划刻/BE tick 域 survey 定案：刻容器层（LevelTicks/LevelChunkTicks/tickBlockEntities/ticker 包装）经 Vanilla+Paper+Moonrise+Papo 叠加已封闭（两项结构候选否决留档）；价值集中在红石消费代码的四处。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 0232 — 红石粉 BlockRedstoneEvent 双站点零监听器快路 + HashSet 桶序复刻（同补丁，本批主项）

- **背景**：0125/0134 迁移了 11 个红石事件调用点，**漏了恰好是默认 VANILLA 评估器下频率最高的两个**：`DefaultRedstoneWireEvaluator.updatePowerStrength:24-30`（每次粉功率变化）与 `RedStoneWireBlock.calculateCurrentChanges:305-309`。零监听器时每次粉功率变化白建 CraftBlock + 事件 + 空派发。
- **改法**：两站点改走已有 `CraftEventFactory.handleRedstoneChange` 快路（与已落地 11 站点逐字同型；有监听器路径完全一致；零监听器时返回传入值恒等）。
- **风险**：低（同型先例 11 处）。红石机器是真实世界持续负载大户，粉功率变化是红石机器的核心高频事件。

## 2. 0232（续）— DefaultRedstoneWireEvaluator 去 HashSet（桶序复刻，与事件门控同一补丁）

- **热点**：每次粉功率变化 `Sets.newHashSet()` 装 {pos}∪{pos+6向} 后迭代——7 位置恒互异（set 纯开销）、恒不扩容（7<12）、恒不树化（<8/bin）→ 约 9 分配/次。
- **关键否决**：**LinkedHashSet 不可用**——其迭代序是插入序 ≠ HashMap 桶序，会改变 7 次 updateNeighborsAt 的顺序 → subTickOrder 排列改变 → 红石可观察差异（批次 23-27 两次暂缓的判定维持，但理由更正：不是"顺序变化"一票否决，而是**插入序≠桶序**）。
- **改法**：桶序直复刻——对 7 候选位置按 `spread(Vec3i.hashCode) & 15` 计桶，按桶 0..15 升序 + 桶内插入序（pos, DOWN,UP,NORTH,SOUTH,WEST,EAST）扫描迭代。邻更新顺序逐字节不变。
- **穷尽自检**：**1,000,000 随机位置**（常规/负坐标/哈希位翻转敏感区/int 极值邻域四类）对拍真实 HashSet 迭代序——**逐元素全等 ALL OK**。
- **基准**：93.895 ± 12.169 → 56.011 ± 11.419 ns/op（**1.68×**，CI [81.7,106.1] vs [44.6,67.4] 不重叠）。

## 3. 0233 — 红石火把 tick 事件惰性化 + 门控

- **热点**：`RedstoneTorchBlock.tick` 每 scheduledTick **无条件**构造 CraftBlock + BlockRedstoneEvent，但事件只在转换分支派发——holding-state tick（火把时钟的常态等待期）从不派发，纯白建。
- **改法**：两转换分支改 `handleRedstoneChange`（构造+派发只在真转换时发生 + 零监听器跳过；有监听器时派发的事件字段逐字一致——原路径 setNewCurrent(target) 后 getNewCurrent() ≡ 返回值）。holding-state tick 分配归零，转换 tick 获得门控收益。

## 4. 0234 — 比较器 getItemFrame facing 谓词静态缓存

- **热点**：实心导体输入路径每次 `itemFrame -> itemFrame.getDirection() == facing` 捕获 lambda（逃逸进 getEntitiesOfClass 虚调用，EA 不可消除）。
- **改法**：0170 canHitEntity 同款静态按 Direction.ordinal 缓存（虚分派语义恒等）。AABB 依赖 pos（方块单例）不缓存。价值中低、风险零。

## 否决留档（survey 结论）

- **probe record 消除**（两次暂缓的 "LevelChunkTicks probe"）：probe 不逃逸 contains，EA 大概率标量替换；结构改法需 widen UNIQUE_TICK_HASH，误判会抑制 scheduleTick → 红石破坏。**结案不做**（除非未来 JMH 实证 EA 未消除）。
- **LevelTicks.collect 结构改造**（时间桶索引替全扫描）：扫描是紧 long 循环（微秒级），刻序等价难证。**否决**。
- 刻容器层/BE ticker 包装/tickBlockEntities：已封闭（Paper+Moonrise+Papo 0044 等叠加），无候选。

## 验证链

compileJava BUILD SUCCESSFUL → 1M 对拍自检 ALL OK → JMH → rebuildPatches（0232-0234）→ applyPatches → 全量 test（见 optimizations.md 批次 67 记录）。
