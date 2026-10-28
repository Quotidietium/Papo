# R35（批次 176）：批175 三序守护→PlacementModifier 流契约族接口级收割（→0.101.0）

日期：2026-10-12 ｜ 轮次：R35 ｜ 判定：**守护闭环（零回归）+ 接口级收割**（0.100.0 → 0.101.0）

## 红线轴：批 175（CubicSpline 流形态消面）MSPT 守护

0.99.0 vs 0.100.0 主/倒/钉三对（msptload-r35legs.sh，钉模式随 r34 脚本化）。
地板（win5s=min）：主序 B≤A 3/6、倒序 3/6（槽 4/5/6 翻号、同号者 ≤0.7 且方向
分裂）、钉死 4/6（幅度 ≤0.6）。**无任何槽位呈三序同号劣化**；R34 记录的 stream
槽 3 同号模式在本轮主序反号（−0.7）——支持其噪声判读。批 175 改动全在 gen worker
线程，无 tick 机械通道。**零回归结论成立**。判例再确认：跨轮不重现的同号=噪声
（单轮三序同号才构成候选信号，还须机制）。

## 0.100.0 普查残余归因（results/alloc-20261011-141228，7,817 样本）

**立项：PlacementModifier.getPositions 流契约族 236 Head trips**（R34 判负面复审
——本轮按指令做接口级重写的封闭性/红线收益比评估）：

- **封闭性成立**：`getPositions(ctx, random, pos)` 全仓**唯一**调用者=
  `PlacedFeature.placeWithContext`（flatMap 链驱动）；全部 19 个实现位于单一包；
  codec 面零触碰（序列化的是修饰器类型不是位置流）；PlacementModifier 是抽象类，
  **新增具体方法=二进制兼容**（无 default-method 冲突面）。
- **恒等可证**：flatMap 链的顺序语义=深度优先（每层内按发射序全展开再进下一输入），
  随机消费序=每修饰器 getPositions 调用时 eagerly 采样——DFS 驱动逐镜像。关键
  不变量：**多重发射修饰器的全部随机量先于首个发射被消费**（vanilla 的
  Stream.builder/IntStream.range 在 getPositions 内部急切构建）。
- **复审判负维持**：Ap2.fillArray 1,190（数值管线本体）、NoiseChunk wrap λ 712+
  HolderDirect/HolderHolder/Marker/MulOrAdd 族（per-chunk 密度树重物化架构）、
  Xoroshiro/Gaussian 族（SurfaceRules 逐列种子红线）。

## 收割（补丁 0288）

- **`PlacementModifier.forEachPosition(ctx, random, pos, Consumer)` 双路**：默认
  实现=getPositions().forEach（**插件兼容路径**——旧面编译的子类零感知）；vanilla
  侧 11 处 override 收束为 3 组：PlacementFilter **final** emit-if（盖 5 个过滤器，
  复用输入 BlockPos 零分配）、RepeatingPlacement 循环（盖 4 个计数族，count 先采样
  再发射）、7 个直接子类逐一镜像（CountOnEveryLayer 保持先全采样后发射；EnvironmentScan
  控制流与谓词调用序逐行同形，发 mutable 同 vanilla 别名语义）。
- **`PlacedFeature` 驱动重写**：flatMap 链 → `PapoPlacementWalk` 深度优先走查
  （每次放置调用一个走查对象、每发射位置零驱动侧分配；严格顺序发射契约下单实例
  depth 记账安全，嵌套 PlacedFeature 放置走自己的走查）。

## 验证

- **恒等单测 2 件**（入套件）：
  - `PapoPlacementDriverEquivalenceTest`：**驱动序等价**——fake 修饰器只 override
    getPositions（走默认 forEach 路径），内联 vanilla flatMap 链 oracle vs 真驱动
    papoWalkPositions：发射序列逐位相等 + **跑后随机残余态相等**（nextLong 对拍，
    捕获任何随机消费序分歧）；覆盖空链/单修饰器各 arity/多重发射居中/三层链×多
    seed。自查纠偏：fake 有 calls 计数器，两侧必须**双建实例**（首版复用实例计数
    串台）；硬编码叶数期望须按"单一 origin 经首档位展开"计算（3 非 4——链 oracle
    与驱动一致，红灯暴露的是测试自身）。
  - `PapoModifierForEachTest`：逐 impl 对拍（同 seed 双随机上 forEach 发射表 ==
    getPositions 流表 ⇒ 随机消费等价）：InSquare/HeightRange（absolute 锚不触
    context，null 安全）/RandomOffset/Count/Rarity/Fixed（同块命中与远离两案）/
    PlacementFilter 与 RepeatingPlacement 以匿名子类盖结构性收束/默认路径委托/
    真驱动混合 override+默认路径链。level 绑定面（Heightmap/EnvironmentScan/
    CountOnEveryLayer/shouldPlace 过滤器）以镜像构造论证+驱动序测试覆盖。
- **链门**：rebuildPatches / applyPatches / `:paper-server:test` / compileJava
  （--rerun-tasks）全 EXIT=0 + BUILD SUCCESSFUL；0.101.0 出包。自查纠偏：首轮
  test 绿是**假绿**——新测试未注册进 *TestSuite 聚合器（该 task 只跑聚合器），
  补注册后重跑（判例：套件外的新测试=未执行）。
- **普查配对腿**（同 seed 流送窗 60s）：placement 流机械**归零**——Head-in-stack
  235→0、StreamBuilderImpl←Stream.of 74→0、ArraySpliterator 31→0、InSquare
  getPositions 面 28→0；替代 PapoPlacementWalk 仅 **15 trips**（每次放置调用一
  个）；唯一"新面"=BlockPos 值本身在 forEachPosition 位点的平移（28→26，数据非
  机械）；placement-in-stack 总量 627→301（残余=BlockPos 值与 IntProvider 等
  数据面）；**无 ≥20 新机械面**；门双绿（logErrors=0）。

## 判例

1. **流契约接口的零分配收割形态**：不动旧签名（二进制兼容红线），加"遍历双路"
   （默认实现桥接旧面），驱动端换走查——三件套缺一不可（只改 impl 不改驱动=面
   不动；只改驱动不改 impl=面半灭）。
2. **多重发射修饰器的随机时序不变量**：消流时每个 impl 必须保持"全部随机采样
   先于首个发射"，否则下游修饰器的随机流交错=世界分叉（红线）。
3. **套件外的新测试=未执行**：聚合器型 test task 的绿灯不含未注册测试——新测试
   落地当轮必须核对注册。
4. 跨轮不重现的同号=噪声（R34 槽 3 在 R35 主序反号佐证；单轮三序同号才构成候选
   信号，还须机制）。

内存账（同 seed 流送窗，计数口径）：placement 流机械 ≈370 trips（可见 Head 236+
StreamBuilder 74+Spliterator 31+IntStream 阶段）→ **0**；替代 15；总样本
7,817→8,152（配对波动）；驻留无变化（纯瞬态）。版本 0.101.0。
