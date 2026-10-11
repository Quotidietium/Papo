# R33（批次 174）：0.98.0 稳态普查残余帧级归因→Climate 检索 scratch + Beardifier 索引循环收割（→0.99.0）

日期：2026-10-12 ｜ 轮次：R33 ｜ 判定：**收割**（0.98.0 → 0.99.0）+ 残余面批量判负

## 普查残余帧级判定（0.98.0，results/alloc-20261011-114728，8,391 样本）

R32 收割后的剩余未归因面，按 R24 方法论（计数口径 ≥20、top-frame=分配位点、
in-stack 匹配业务面）逐一判定：

**立项 1：`Climate$TargetPoint.toParameterArray` 165 trips**（`long[]` ← toParameterArray
位点）。喉位论证：全仓唯一调用者是 `ParameterList.search`——即生物群系采样的必经
检索入口（每个噪声样本、每个 section 填充都要走）；数组本体是 RTree 搜索的**只读
输入**，不逃逸 `root.search`，距离度量是纯算术不回调采样——ThreadLocal scratch 的
全部安全性前提成立（每线程同时至多一个 search，无重入）。

**立项 2：`Beardifier.compute` 228 trips**（`ImmutableCollections$ListItr`）。全 228
都是 enhanced-for 迭代器分配：compute() 对结构邻接区块的**每个噪声格**各跑一次，
每次迭代两条不可变 List（pieces/junctions）各造一个迭代器。机械上"同序同算术"的
索引循环即可归零。

**判负（维持/新增）**：
- `StructureTemplate.processBlockInfos/getJigsows` 族——vanilla 结构模板放置架构
  （每次放置的处理管线本体），非封闭单文件、簇级份额不足，R24 立项门槛不过。
- `OreFeature.doPlace`（A 腿 176/B 腿 53 trips，λ 归一后同面）——逐矿石位放置的
  判定循环本体，与 `MutableBlockPos.immutable`/`EnvironmentScanPlacement` 同属
  vanilla 散布小面。
- `NoiseChunk.wrap/mapAll` 架构面——复审维持 R24 判负：per-chunk Marker 绑定是
  世界生成核心架构，动它=重写噪声管线（红线外收益比）。

## 收割（补丁 0286）

- **Climate**：`PAP0_SEARCH_SCRATCH = ThreadLocal.withInitial(long[7])`（每线程一份，
  惰性首次分配后零分配），`search()` 逐字段填充六坐标+尾 0——与旧
  `toParameterArray()` 逐值恒等；`findValueBruteForce` 走 `fitness()` 不经此路，
  不受影响。
- **Beardifier**：两条 enhanced-for → 索引循环（注释记录 228-trip 依据；同序、
  同算术、零分配）。

## 验证

- **恒等单测 2 件**（均已注册 `PapoRegressionTestSuite`）：
  - `PapoClimateScratchTest`：32 轮 × 5 个交错查询点 × 2 个 ParameterList 实例
    （共享静态 scratch 的泄漏探头）对 `findValueBruteForce` oracle 逐次
    assertSame + 近点语义断言。
  - `PapoBeardifierIndexedLoopTest`：五个 TerrainAdjustment 臂逐臂公式断言 +
    junction + 核外零 + 可加性 + null 盒/盒外早退零，oracle 与产物
    **逐调用同形**（BEARD_KERNEL 由初始化表达式
    `Math.pow(Math.E, -lengthSquared/16)` 精确复刻，含 float 存储位）。
- **测试自身三轮纠偏（判例素材，非产物缺陷）**：①`Parameter` 是
  `record(long min, long max)`，无单参构造；②middle=(5000×6) 恰为两入口点的
  **精确等距平局**，树搜索与暴力法各自合法破平局，assertSame 必炸——改为非对称
  破平局坐标；③BEARD_THIN 的 **i5 取值臂**走 `BURY, BEARD_THIN -> i4` 而贡献
  公式臂才与 BEARD_BOX 共享——首版断言错把它当全共享，红灯暴露的恰是逐臂断言的
  判别力（同时纠正 i5=4 而非 5 的手算滑误：64−60=4）。
- **链门**：rebuildPatches / applyPatches / `:paper-server:test` / compileJava
  （--rerun-tasks）全部 EXIT=0 + BUILD SUCCESSFUL；0.99.0 出包。
- **普查配对腿**（同 seed 流送窗 60s，alloccensus-r32.sh 换 jar）：
  - Climate 面：**165 → 0 trips（面消灭）**；
  - Beardifier 面：ListItr×compute-in-stack **228 → 0（面消灭）**（ListItr 总量
    372→124，残余属其他迭代位点；B 腿 any-Beardifier-in-stack 仅剩 15 trips 调用侧
    他类小面，<阈值）；
  - 替代路径 `PAP0_SEARCH_SCRATCH` 位点自身 **0 采样**（每线程一次性分配低于采样
    阈值）；**无 ≥20 的新面孔**；门双绿（logErrors=0）。

## 判例

1. **oracle 断言与平局**：assertSame 级别的"同一最近点"断言要求测试输入无精确
   平局——等距输入下两个都正确的实现也会合法分叉；构造 oracle 输入先算距离。
2. **公式复刻必须逐调用同形**：kernel 期望值用 `Math.pow(Math.E, ·)` 而非
   `Math.exp(·)`（后者不保证逐位恒等），连同 float 存储位一起复刻，delta=0 断言
   才成立。
3. **双 switch 的臂错位**：取值臂（i5 怎么来）与公式臂（贡献怎么算）是两个独立
   switch，共享公式不共享取值——逐臂断言是唯一能捕获臂间错位的形态。

内存账（同 seed 流送窗，计数口径）：目标两面 165+228 trips → 0+0；总样本
8,391→6,314（配对波动与 R31/32 对同量级）；驻留无变化（纯瞬态分配）。版本 0.99.0。
