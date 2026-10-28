# R37（批次 178）：批177 三序守护→水体/气候/特征五面收割（→0.103.0）

日期：2026-10-12 ｜ 轮次：R37 ｜ 判定：**守护闭环（零回归）+ 收割**（0.102.0 → 0.103.0）

## 红线轴：批 176/177（谓词 scratch+OreFeature 提升）MSPT 守护

0.101.0 vs 0.102.0 主/倒/钉三对（msptload-r37legs.sh）。地板（win5s=min）：主序
B≤A 2/6（劣化 ≤+0.3）、**倒序 B≤A 5/6（零劣化，1 平）**、钉死 B≤A 0/6（+0.1..+0.4）。
逐槽跨序：无三序同号劣化槽（唯一候选槽5：+0.3/0.0/+0.1，幅度远小于腿内摆幅
1.8-2.5ms、无机制、倒序平——R34 判例噪声）。**第二腿劣势伪影**：A-先-跑两对
（主/钉）的后跑腿系统性吃亏（钉死 6/6 全 +0.1..+0.4），B-先-跑的倒序里后跑腿
（A）反转吃亏 5/6——时间趋势（热身/缓存累积）以"谁后跑谁亏"呈现，换序即反转。
批177 全在 gen worker 无 tick 通道，**零回归结论成立**。

## 0.102.0 普查残余归因（results/alloc-20261011-202139，7,830 样本）

新角度五立项（全部封闭+恒等可证）：

1. **Aquifer MutableDouble 121 trips/134MB**——computeSubstance 每调用 new
   MutableDouble(NaN) 作 calculatePressure 的 barrierNoise 懒记（NaN 哨兵，至多
   3 读）。**所有权即线程 confinement**：NoiseBasedAquifer 每 NoiseChunk 一实例、
   其全部调用者（barrier 填充 λ、雕刻器）在该区块的单生成 worker 上——直接
   实例字段化+入口重置哨兵，比 ThreadLocal 先行条件更强。
2. **SinglePointContext 77+33 trips**——Climate.Sampler.sample 全部经
   MultiNoiseBiomeSource.getNoiseBiome←fillBiomesFromNoise（每 4×4 生物群系列）；
   WorldCarver.getCarveState 每雕刻块。纯 3-int record；全仓无 instanceof/equals/
   hashCode 观察 FunctionContext；六个气候密度函数与 computeSubstance 内的
   barrierNoise.compute 全部坐标只读同步；无密度函数回调 sample（无重入）→
   PapoContextScratch 线程局可变替身。
3. **GeodeFeature LinkedList$ListItr 110 trips/113MB**——betweenClosed 外层可达
   33³ 位 × 两个 LinkedList 内层增强 for = 每 geode ~7 万迭代器分配。列表追加后
   仅按插入序迭代 → ArrayList 化+索引循环（R33 Beardifier 同型）。
4. **MonsterRoomFeature offset 58 trips**——首段三重循环每房间 ~294 次纯查询
   （getBlockState/isEmptyBlock，above() 因 Paper 防御性覆写返回新 immutable 不
   自变）→ 线程局 MutableBlockPos 置值即查；**二/三段建造循环保持 immutable**：
   below() 查询后仍要用原位 setBlock，mutable 自变会错位。
5. **PapoWireDirtyTracking 27 trips（自有代价面）**——每 mark() 新分配
   MutableBlockPos（两路径）+ ChunkAccess[4]（markLevel）→ ThreadLocal 化+入口
   Arrays.fill 清槽（前次调用的区块引用不得泄入新网格基）；每次访问前 set 重
   导位置，同线程重入亦安全。

**判负**：FlowingFluid relative 76（tick 路径递归 getSlopeDistance——递归各深度
位置须独立存活，深度索引 scratch 侵入性高且是 tick 红线面）；carveEllipsoid
MutableBlockPos 20（每 ellipsoid 两游标摊销，已正确复用）/getWorldPos 44（**结果
对象 API**——调用方持有返回值）；NoiseInterpolator.allocateSlice 147（填充目标
数组本体）；PacketEncoder 149（memo 快照=既优化代价面维持）；OreFeature double[]
65（放置本体维持）；集合机械散布（ArrayList ctor 104/copyOf 80/ArrayItr 50/
ImmutableListItr 49——单调用者全 <阈）；数据面（HashMap/BitSet/SimpleBitStorage/
Long2ObjectOpenHashMap/SWMRNibble/DirectByteBuffer.slice）；Moonrise
findEligibleGroups 43（上游调度器）；判负维持：Ap2 1300、wrap λ 766、mapAll 族、
RNG 族（种子派生红线）。

## 收割（补丁 0290 + src/main 直改）

- Aquifer.java：papoPressureMemo 实例字段+入口 setValue(NaN)（calculatePressure
  签名与三调用点零改动）。
- PapoContextScratch.java（新，levelgen 包）：public 线程局 Holder implements
  FunctionContext（blockX/Y/Z 可变+set；getBlender 继承默认）；Climate.Sampler.
  sample 与 WorldCarver.getCarveState 两构造点换 singlePoint(x,y,z)。
- GeodeFeature.java：两列表 newArrayList+两内层索引循环（同序同算术）。
- MonsterRoomFeature.java：SCAN_SCRATCH 线程局游标盖首段扫描。
- PapoWireDirtyTracking.java（src/main/java 直版本）：SCAN_SCRATCH+SLOT_CACHE
  线程局化，markLevel 入口清槽。

## 验证

- **恒等单测**（`PapoContextScratchTest` 4 用例，已注册套件）：坐标精确（含
  MIN/MAX/大四分位值）、200 轮序贯覆写（后 set 全覆盖前 set，无跨调用泄漏）、
  三线程 latch 交错（各线程 holder 隔离，主线程在他线程置位后仍读己值）、
  getBlender 默认继承。接线单行替换由 helper 测试+链门+普查配对腿覆盖。
- **链门**：rebuildPatches/applyPatches/`:paper-server:test`（PapoContextScratchTest
  tests=4 failures=0 errors=0 实测执行确认）/compileJava（--rerun-tasks）全
  EXIT=0+BUILD SUCCESSFUL；0.103.0 出包。两次编译自查纠偏：ThreadLocal.get()
  赋给 BlockPos 声明后 set() 不可见（须声明 MutableBlockPos）；测试里
  assertTrue(latch.await()) 触发 javac "void 类型不允许"单错（根因未明）改语句位
  await() 通过。
- **普查配对腿**（同 seed 流送窗 60s，JFR threshold=1ms 计数口径）：MutableDouble
  ←computeSubstance **121→0**；SinglePointContext←sample **77→0**、←getCarveState
  **33→0**；LinkedList$ListItr **110→0**（Geode-in-stack 112→1）；MonsterRoom
  offset **58→0**；PapoWireDirty **27→0**；上游同步塌缩（Climate.sample-in-stack
  88→2、computeSubstance-in-stack 134→10）；**λ 身份哈希归一后无 ≥20 新面**
  （SpreadContext λ 20=流体预存面采样波动，其族本轮判负）；A 7,830/B 5,069 总量
  配对波动（收割面占 A ~426 直接 trips+上游）。腿遭会话暂停杀于脚本尾部——
  **JFR 窗已闭合+logErrors=0+MemSoak.top 直方图已打印，按工件门判完整不重跑**
  （R36 判例应用）。

## 判例

1. **所有权即线程 confinement**：per-call 闭包捕获 holder（NaN 哨兵/盒子）在
   "对象本身被单线程独占"（每实例一区块一 worker）时直接字段化，无需 ThreadLocal
   ——先行条件更强、代码更短。
2. **结果对象 vs 查询载体判别式**：普查分配面里"方法返回值即分配"（getWorldPos、
   FeaturePlaceContext）且调用方持有=语义载体判负；"分配只喂同步坐标只读查询"
   （SinglePointContext、扫描位）=载体可替换。
3. **第二腿劣势伪影**：守护对内时间趋势以"谁后跑谁亏"呈现——A 先跑两对的后跑
   腿系统性吃亏、B 先跑对反转；判别法=劣化槽集中于后跑腿且换序即反转（勿将
   钉死 6/6 读作回归信号）。
4. **Paper 防御性覆写实测**：MutableBlockPos.offset/relative/multiply/rotate 返回
   新 immutable 而非自变（与 vanilla 直觉相反）——scratch 复用因此更安全，但
   mutable 上的 above()/below() 仍分配（小残面）。

内存账（同 seed 流送窗）：目标五面 ~426 直接 trips（≈450MB 采样权重）→ 全零；
驻留无变化（纯瞬态+每实例一 memo 字段 ~16B×活跃 aquifer）。版本 0.103.0。
