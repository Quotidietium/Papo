# R38（批次 179）：批178 三序守护→Ap2(ADD) fillArray 组合温度数组收割（→0.104.0）

日期：2026-10-12 ｜ 轮次：R38 ｜ 判定：**守护闭环（零回归）+ 收割**（0.103.0 → 0.104.0）

## 红线轴：批 178（水体/气候/特征五面）MSPT 守护

0.103.0 vs 0.104.0 主/倒/钉三序 + **钉死同序复跑**（msptload-r38legs.sh，win5s=min 地板）：

| 对 | 序 | B≤A 槽数 | 备注 |
|---|---|---|---|
| 主跑 | A→B | **6/6** | 全槽 −0.4..−3.0；boot 20.1→18.6s |
| 倒序 | B→A | **5/6** | 槽1（boot 坡流送首槽）+0.8 翻号；其余 −0.1..−1.5 |
| 钉死对1 | A→B | 0/6 | +0.4..+2.0——**同序复跑不重现（下行）** |
| 钉死对2（同序复跑） | A→B | **6/6** | −0.3..−1.2；复跑窗整机更慢（A-103p 自身槽2 4.3→6.6 漂移），窗内 B 仍全槽更优 |

判读：**无任何槽在两对以上同号劣化**；钉死对1 是连续第 5-6 条重腿后的整机漂移瞬态（同序复跑
即反转=瞬态，R23 判例强化：真系统差同序必复现）。改动全在 gen worker 线程、无 tick 机械通道
（fillArray 不在 server thread 栈上）。三对全优 + GC 机制方向一致（分配削减→流送窗 GC 压力
降低→tick 地板下降），**零回归成立且方向性改善**。绝对量级 4-9ms@12 bot 流送距 50ms 预算
一个量级。

## 立项：R37 判负维持的 Ap2 面以新机制角度重开

R37 普查判负维持"Ap2 1300 trips（数值管线本体）"。本轮重开论证：

- **面**：`DensityFunctions.Ap2.fillArray` ADD 臂每次调用 `new double[array.length]`——
  每区块噪声格填充时每 ADD 节点每 cell 一个临时数组。本次配对腿 A 侧实测 **1,347 trips/60s
  流送窗、raw 3,418.6MB / >100MB 过滤后 1,405.9MB**——榜首面，大于其余全部机械面之和。
- **恒等论证**（R37"结果对象 vs 查询载体"判别式的正面应用）：临时数组被
  `argument2.fillArray` 全写、在同一调用内消费、**从不逃逸**——它是查询载体而非结果对象。
  之前三轮判负（R24/R34/R37）的理由"数值管线本体"对"调用方持有的结果对象"成立，对
  "同调用消费的载体"不成立。
- **唯一别名险=嵌套**：密度树可以把 Ap2(ADD) 嵌在自己的 argument2 里，内层调用需要独立的
  活 temp——单 ThreadLocal 实例不够，**活深度可以大于一**。
- **等价复用的通式**：每线程 × 每深度一个槽（`double[][] slots` 按需增长，深度上界=树形
  状）；**每次 push 对已用前缀 `Arrays.fill(slot, 0, length, 0.0)`**——与新鲜数组逐位恒等
  （fillArray 契约下 argument 全写，但违约留下未写索引时两态都读 0.0，SparseFill 违约测试
  正面覆盖）；`try/finally pop` 保证 argument2 抛异常时深度解栈。

## 收割（补丁 0292）

`DensityFunctions.PapoAddTempStack`（静态嵌套类）：`ThreadLocal<PapoAddTempStack>` +
`double[][] slots` + `int depth`；`push(length)` 取当前深度槽（不足则新配/扩容）并清零前缀、
深度自增，`pop()` 深度自减。ADD 臂 `new double[...]` → `push`/`try{...}finally{pop}`。
MUL/MIN/MAX 臂走逐点 `compute(contextProvider.forIndex(i))` 无临时数组——面完备，无同型遗漏。

## 验证

- **恒等单测**（`PapoAp2FillArrayTest` 6 用例，已注册 PapoRegressionTestSuite 并实测执行）：
  精确组合算术（经公共工厂 TwoArgumentSimpleFunction.create）；序贯两次调用无残留；7 层嵌套
  ADD 树各深度独立 temp；深槽承接短请求时前缀清零；argument2 抛异常后深度解栈（后续嵌套
  填充仍正确）；SparseFill 违约（隔 stride 写）下零前缀=新鲜数组语义平权。
- **链门**：applyPatches / `:paper-server:test --rerun-tasks` / compileJava 全 EXIT=0 +
  BUILD SUCCESSFUL；套件 9,054 tests / 0 failures / 0 errors（新测试 6/6 在场）。
  0.104.0 出包，嵌套 jar javap 验 `PapoAddTempStack` 在包（build.md 判例：bundler 类在
  META-INF/versions 内层 jar）。
- **普查配对腿**（alloccensus-r38legs.sh，同 seed 流送窗 60s，JFR threshold=1ms 计数口径，
  聚合器 `benchmark/allocsum.py`——对既有 0.103 腿复现已知基线后启用）：
  `Ap2.fillArray←NoiseChunk.selectCellYZ` **1,347 → 0**；in-stack `Ap2.fillArray`/`
  selectCellYZ` 双双归零；替代面 `PapoAddTempStack` **0 trips**（槽分配一次性、低于阈值）；
  事件总量 7,411 → 6,491（−12.4%）、raw 权重 12,316.6 → 10,521.1MB（−14.6%）。B 腿
  top-40 无 ≥20 新面（新晋可见的 StructurePiece.getWorldPos/ChunkTaskScheduler.schedule
  为 A 腿 top-40 截断线下的预存面浮出）。
- **驻留**：dwell 直方图两腿同型（SavedTick/StructureBlockInfo/LinearPalette/SWMR…）；
  B 同窗多载 1.9% 区块（生成吞吐略升的旁证），按区块归一 103.3→103.8KB（+0.4%，噪声内）
  ——瞬态面收割不改驻留；新增常驻=每 gen worker 线程槽栈（深度 ~树深 × double[cell] ≈
  每线程数 KB）。

## 判例

1. **判负面重开判据**：历史判负（"数值管线本体"）重开须同时满足——新机制通道（载体不逃逸
   + 嵌套深度隔离方案）、恒等论证升级（前缀清零=新鲜数组逐位平权）、面占普查首位（本面
   大于其余机械面之和）。三者齐备才推翻旧判负理由。
2. **每深度槽栈=嵌套自递归复用面的通式**：活深度可大于一的复用面（自嵌套求值）用
   depth-indexed slot stack，比单 ThreadLocal 实例条件更强；push 清前缀 + finally pop 是
   等价性与异常安全的两根支柱。
3. **同序复跑不重现=瞬态（钉死对判读强化）**：钉死首对 0/6 全劣（+0.4..+2.0）在第二轮
   同序复跑中反转为 6/6 全优——连续重腿后的整机漂移以"后跑腿吃亏"呈现；判别法=同序复跑
   （R23"真系统差同序必复现"）+ 同二进制跨腿摆幅覆盖差值（B 槽4 自身三腿摆幅 2.9ms >
   钉死差 1.7ms）+ 无机械通道。
4. **工具沉淀**：`allocsum.py`（jfr print 文本 → 站点计数/权重双口径/in-stack 计数，λ 身份
   哈希归一）入库复用；先对已知腿复现基线再用于判读（工具先对账再使用，R11 判例）。

## 判负维持（本轮普查 top 余面）

NoiseChunk.wrap 782（per-chunk 密度树重物化架构，R24）；Xoroshiro 513+LegacyRandom 166
（RNG 种子派生红线）；mapAll 族全家族（HolderHolder/Marker/TwoArg.create/Mapped/
ShiftedNoise/CubicSpline/Spline$Coordinate，数值树重包架构）；allocateSlice 125（填充目标
数组本体）；PacketEncoder 105（memo 快照=既优化代价面）；OreFeature.doPlace 90（放置
本体）；StructureTemplate 处理族 53+38（vanilla 模板放置架构）；carveEllipsoid 65（游标
已摊销）；findEligibleGroups 37（Moonrise 上游）；BlockPos.offset 48/relative（数据面
散布）；BitSet/SimpleBitStorage/HashMap/Long2Object（数据面）。

内存账（同 seed 流送窗）：目标面 1,347 trips（filtered 权重 1,405.9MB/60s）→ **0**；
事件 −920（−12.4%）。驻留零变化。版本 0.104.0。
