# R34（批次 175）：批174 三序守护→0.99.0 普查残余归因→CubicSpline 流形态消面收割（→0.100.0）

日期：2026-10-12 ｜ 轮次：R34 ｜ 判定：**守护闭环（零回归）+ 收割**（0.99.0 → 0.100.0）

## 红线轴：批 174（Climate scratch + Beardifier 索引循环）MSPT 守护

0.98.0 vs 0.99.0 主序+倒序+钉年轻代（-XX:NewSize=MaxNewSize=1G）三对（
msptload-r34legs.sh，新增 pinned 模式入库）。地板（win5s=min）：主序 B≤A 3/6、
倒序 4/6、**钉死 5/6（含全部 dwell 槽 B 更优）**。多数槽方向利 B；唯一同号模式在
stream 槽 3（min +2.1/+1.0/+1.0，avg 仅 +0.2/+0.2/+0.7）——**无 tick 路径机械通道**
（批 174 全在 chunk-gen worker 线程、严格减分配），且腿内槽间摆幅（1.5ms）同量级。
判读为噪声地板 + 良性相位效应：减分配→gen 吞吐略升→流送峰值窗 CPU 竞争略强，dwell
（交互稳态）三槽全优佐证。**零回归结论成立**，槽 3 观察如实入档。判例维持：符号
翻转跨序=噪声；同号但幅度 < 腿内摆幅且无机械通道 + 多数槽反向 = 仍判噪声（R24 #5
"+0.34ms 两序复现**且有机制**"才是真回归全条件——同号本身不充分）。

## 0.99.0 普查残余归因（results/alloc-20261011-130010，6,314 样本）

帧级判定（in-stack 业务面匹配）：

**立项：`CubicSpline$Multipoint` 单簇 ≈459 trips**——三个分配面同一机械位点：
- `ListItr` 97 trips（in-stack，create() 的 enhanced-for）；
- 流机械：`ReferencePipeline$Head`+`$3` 阶段+`NodeBuilder`+`toArray Object[]`
  （mapAll 的 `values().stream().map().toList()`）；
- `RandomAccessSpliterator` 34 trips（同流）。
路径：每个生成区块重包密度树（NoiseChunk wrap visitor）→ 每个样条节点重建 →
mapAll 流 + create 迭代器。**封闭单文件 + 恒等可证**（列表内容与序不变，record 对
values 只读持有，codec 路径本来就用 Guava ImmutableList——具体 List 实现非可观察
契约）。

**判负（维持/新增）**：
- NoiseChunk.wrap λ 516 + HolderDirect 234 + Marker/Mapped/MulOrAdd 族——per-chunk
  密度树重物化架构面，R24 判负维持。
- Xoroshiro/Gaussian 469——SurfaceRules 逐列 `PositionalRandomFactory.at()`，R24
  判例 #4 种子派生绝对红线维持。
- **新判负：PlacementModifier.getPositions 流契约族**（B 腿残余 236 Head 的全部
  来源：HeightRange/InSquare/PlacementFilter/RandomOffset 逐位置 `Stream.of(pos)`）
  ——接口契约级散射（ dozen 修饰器类），封闭单文件门槛不过。
- Aquifer MutableDouble 72——数值管线本体，<阈。

**判例（工具）**：单代表栈外推会被栈截断误导——深栈事件的业务帧可能落在截断线
之后，in-stack 计数是**下界**不是全量（本轮"Head 301 全属 CubicSpline"的初判实为
~99 可见 + 截断隐藏；簇的精确边界以配对腿差分定）。

## 收割（补丁 0287）

`Multipoint.mapAll`：`values().stream().map(...).toList()` → 按原序填
`new ArrayList<>(size)`（流机械全套消失，替代路径自身仅 47 trips）；`create()` 的
enhanced-for → 索引循环（ListItr 消失）。

## 验证

- **恒等单测**（`PapoCubicSplineMapAllTest`，3 用例，一次全绿）：①对 legacy 流形式
  oracle 逐项断言（parityString/min/max/apply×10 采样点含越界与嵌套子样条区间）；
  ②**双重 mapAll 复合**——第二次 mapAll 跑在新 ArrayList 结果上，证明列表实现变化
  对所有消费者不可观察；③mapAll 不改原树（parityString 前后一致）。嵌套
  CoordFunc 测试类带内容 toString（parityString 可比性）。
- **链门**：rebuildPatches / applyPatches / `:paper-server:test` / compileJava
  （--rerun-tasks）全 EXIT=0 + BUILD SUCCESSFUL；0.100.0 出包。
- **普查配对腿**（同 seed 流送窗 60s）：CubicSpline 簇全灭——ListItr 97→0、Head
  （CubicSpline-in-stack）99→0、Spliterator 34→0、$3 85→0、NodeBuilder 40→0、
  toArray 49→0；替代路径 ArrayList←mapAll 47 trips（有界、低于被消面一个量级）；
  合法结果对象（Multipoint←create，行号平移）71→88 为 gen 量配对波动；**无 ≥20
  新面**；门双绿（logErrors=0）。

内存账（同 seed 流送窗，计数口径）：簇 ≈459 trips → 0 + 47 替代（净 −412）；
总样本 6,314→7,817（配对波动与历轮同量级）；驻留无变化（纯瞬态）。版本 0.100.0。
