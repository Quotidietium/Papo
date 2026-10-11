# R39（批次 180）：批179 三序守护→矿石/模板放置机械面三面收割（→0.105.0）

日期：2026-10-12 ｜ 轮次：R39 ｜ 判定：**守护闭环（零回归）+ 收割**（0.104.0 → 0.105.0）

## 红线轴：批 179（Ap2 组合温度数组）MSPT 守护

0.104.0 vs 0.105.0 三对（msptload-r39legs.sh，win5s=min 地板）：

| 对 | 序 | B≤A 槽数 | 备注 |
|---|---|---|---|
| 倒序（污染窗） | B→A | 1/6 | B 腿启动于人工终止残留任务后 ~1 分钟，整腿水位偏高（+0.1..+2.0），**如实入档按污染窗降权** |
| 主跑（干净） | A→B | 3/6 | 2 平；B 劣化仅槽1 +0.8（boot 坡）槽5 +0.2；槽3 B −3.0 来自 A 腿内 72ms 瞬态尖峰 |
| 钉死 | A→B | **5/6** | −0.3..−0.5 全优，槽3 +0.1 |

判读：**无任何槽在两对以上同号劣化**（boot 坡槽1 在钉死对反转 −0.3；槽3/4/5 跨对符号乱翻）；
全部幅度 < 同二进制跨腿摆幅（A 槽3 四腿 8.7-10.7）；改动全在 gen worker（矿石/结构放置），
无 tick 机械通道。**零回归成立**。佐证：B 同窗多载 35% 区块（4,389→5,941）= 世界生成吞吐
提升方向与分配削减机制一致。

## 0.104.0 普查残余归因（results/alloc-20261012-020111，4,550 样本）

R38 收割后 B-104 普查（6,491 样本）top 余面逐项判定，立项三面（全封闭+恒等可证）：

1. **Long2ObjectOpenHashMap ← BulkSectionAccess.<init> ← OreFeature.doPlace 71 trips**
   ——每矿石放置 new 一个段缓存映射。**全仓唯一使用者是 OreFeature.doPlace**，其
   try-with-resources 使实例每线程严格顺序存活（活深度恒为 1）→ 线程局**单槽池**：
   构造借槽（槽空则回落新映射=vanilla 同形）、close() 先 release 全部段再 clear 再还槽。
   **fastutil clear() 清引用字节码实证**（Arrays.fill(key,0)+Arrays.fill(value,null)——
   区别于 R25 判例的 vanilla BinaryHeap 只置零 size）；**双重 close 守卫**（papoClosed）——
   第二次 close 为无操作，比 vanilla 的双 release 更防御，且消除"陈旧实例 close 清掉
   借出中映射"的跨实例危害路径。
2. **Iterators.forArrayWithPosition ← RegularImmutableList.listIterator ←
   RuleProcessor.processBlock 50 trips**——每模板每块在不可变规则表上开迭代器 →
   索引循环（get(i) 是数组访问；同序同早退）。位置种子 RNG 构造不动（种子派生红线）。
3. **processBlockInfos 每块 iterator()（ArrayList$Itr，阈下同型面）**——处理器列表
   迭代器索引循环化（机械完成本族；null/hasNext 求值序交换不可观察）。

判负维持：NoiseChunk.wrap/mapAll 全族（per-chunk 密度树重物化架构）、Xoroshiro 513+
LegacyRandom 166+RandomSource.create←RuleProcessor 38（RNG 种子派生绝对红线）、
allocateSlice 125（填充目标数组本体）、PacketEncoder 105（memo 代价面）、OreFeature
doPlace 46-93（**放置本体**：double[]/数据面，工作量缩放）、processBlockInfos 物化 34-49
（结果列表+StructureBlockInfo 记录=R33 判负维持）、carveEllipsoid、getWorldPos（结果对象）、
findEligibleGroups（Moonrise 上游）、BitSet/SimpleBitStorage/HashMap/ArrayList.grow（数据/
散布机械，单点 1-3 trips）。

## 收割（补丁 0293）

- BulkSectionAccess.java：PAPO_SECTION_MAP_POOL 线程局单槽（包可见供测试）+构造借槽
  /close 还槽（release→clear→空槽才存，嵌套实例的映射被丢弃走 GC）+papoClosed 双 close 守卫。
- RuleProcessor.java：规则表索引循环。
- StructureTemplate.processBlockInfos：处理器列表索引循环。

## 验证

- **恒等单测**（`PapoBulkSectionAccessPoolTest` 5 用例 + `PapoRuleProcessorIterationTest`
  4 用例，均已注册套件实测执行）：顺序生命周期借用清空映射（第二次 getChunk/重新 acquire
  计数断言）；**嵌套实例独立记账**（各自 acquire、close 平衡、还槽互不覆盖——跨实例串话
  致命场景正面覆盖）；双 close 恰好 release 一次且池可继续服务；异常路径 try-with-resources
  还槽；槽线程隔离（worker 线程自有槽，主线程槽不受扰）；规则列表序+首中早退+未咨询后续+
  输出态精确+无匹配/空表直通。
- **链门**：rebuildPatches（0293 生成）/applyPatches/`:paper-server:test --rerun-tasks`
  （9,063 tests 0 failures 0 errors，新测试 5+4 在场）/compileJava 全 EXIT=0+BUILD
  SUCCESSFUL；0.105.0 出包。
- **普查配对腿**（alloccensus-r39legs.sh+allocsum.py）：`Long2ObjectOpenHashMap.<init>←
  BulkSectionAccess` **37→0**；`forArrayWithPosition←RuleProcessor.processBlock` **37→0**；
  替代面 `PAPO_SECTION_MAP_POOL` **0 trips**（借/还零分配）；数据面工作量缩放如实入档
  （doPlace 46→93、物化 34→49，随 B 同窗多载 35% 区块）；驻留按区块 114.7→104.2KB（−9%，
  方向与瞬态削减一致）。

## 判例

1. **单槽借还池=顺序生命周期复用面的通式**：唯一使用者+try-with-resources ⇒ 活深度恒 1，
   无需 R38 的深度槽栈；三支柱=借空槽（空映射不变量只在 close 处建立）、close 先 release
   后 clear 再还、双 close 守卫（防御 vanilla 本就存在的双 close bug 并消除跨实例危害）。
2. **清引用先实证字节码**：fastutil clear() 与 vanilla BinaryHeap.clear() 行为相反（前者
   key/value 双 fill，后者只置 size）——复用任何容器前按 R25 判例查真实实现，不按类名直觉。
3. **杀任务残留污染窗判读**：人工终止后台腿任务后紧邻的测量腿整腿水位偏高，与其后干净对
   相比同向不可信——污染窗腿降权入档、补干净对定案；终止腿任务前先收走其子进程（端口/
   世界目录残留会在 1 分钟内毒化后续腿）。
4. **工作量缩放面与机械面分开记账**：吞吐提升后数据驱动站点（矿石放置、模板物化）trips
   随区块数上升是**好信号**的副产品，归一按区块/按事件总量判定，不与机械收割面混读。

内存账（同 seed 流送窗）：目标面 37+37 trips（~75MB 采样权重）→ **0**；驻留按区块 −9%
（窗内瞬态垃圾减少）。版本 0.105.0。
