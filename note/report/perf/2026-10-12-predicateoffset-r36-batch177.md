# R36（批次 177）：批176 三序守护→谓词偏移 scratch + OreFeature 访问器提升双面收割（→0.102.0）

日期：2026-10-12 ｜ 轮次：R36 ｜ 判定：**守护闭环（零回归）+ 收割**（0.101.0 → 0.102.0）

## 红线轴：批 176（PlacementModifier forEachPosition 双路）MSPT 守护

0.100.0 vs 0.101.0 主/倒/钉三对（msptload-r36legs.sh）。地板（win5s=min）：主序
B≤A 3/6（幅度 ≤0.5）、**倒序 6/6 全优**、钉死 4/6（含全部 dwell 槽 B 更优）。跨序
翻号 3 槽、无三序同号劣化槽——批 176 全在 gen worker 线程无 tick 通道，
**零回归结论成立**。运维判例：会话中断杀掉后台守护腿（A-100r 只跑到槽 1）——
恢复后按 log 的 gate 行核对完整性再补跑单腿，不盲目重跑整对。

## 0.101.0 普查残余归因（results/alloc-20261011-152709，8,152 样本）

**立项 1：blockpredicates 偏移族 134 trips**（BlockPos ← MutableBlockPos.immutable ←
offset ← `StateTestingPredicate.test` 116 + `HasSturdyFacePredicate.test` 18）——
谓词门控的特征放置对每个候选位置测试 BlockPredicate，`pos.offset(this.offset)`
每次新分配一个 BlockPos。**封闭**：2 文件；**恒等可证**：查询链
（getBlockState/isFaceSturdy）只读坐标不持有 BlockPos（vanilla 特征循环自己就把
mutable 传入同样的查询）；重入不可能（每次使用是"置值即查询"的原子对）。

**立项 2：OreFeature λ 146 trips**——`bulkSectionAccess::getBlockState` 绑定方法
引用在**每候选位置×每目标态**的循环内新建；提升到 doPlace 开头每调用一个，
纯机械恒等（同一接收者的同一绑定引用）。

**判负**：PacketEncoder byte[] 179 trips=共享编码 memo 的快照分配（**既优化的代价
面**——一次快照换 N 连接的 codec 重走，权衡已在前轮评估，重开无净收益证明）；
MutableBlockPos.immutable 其余散布调用者（ticks 7/树 6/实体 4/红石 3，皆 <阈）；
判负维持复审：Ap2.fillArray 1,190（数值管线本体）、NoiseChunk wrap λ 712+
HolderDirect-HolderHolder-Marker-MulOrAdd 族（per-chunk 密度树重物化架构）、
Xoroshiro-Gaussian 族（SurfaceRules 逐列种子红线）。

## 收割（补丁 0289）

- `PapoPredicateScratch`（blockpredicates 包私有）：ThreadLocal MutableBlockPos，
  `offsetBlockState`（置值即查询）与 `offset`（供 HasSturdyFace 的
  getBlockState+isFaceSturdy 两连读，同一 scratch 序贯消费）。
- StateTestingPredicate（final test 盖 MatchingBlocks/MatchingFluids/Replaceable/
  Solid 全部子类）与 HasSturdyFacePredicate 改走 scratch。
- OreFeature.doPlace：访问器方法引用提升出循环巢。

## 验证

- **恒等单测**（`PapoBlockPredicateScratchTest`，2 用例）：BlockGetter 级直接对拍
  ——查询坐标=输入+offset 精确断言（含 ZERO 常量/新建零偏移/负分量/命中与未命中
  态映射）+ 100 轮交错查询证明 scratch 不泄漏前次坐标（记录侧即时拷贝坐标，因为
  传入的可能是共享 scratch 本身）。谓词接线为单行复合（helper 与查询均为坐标
  只读），由 helper 测试+链门+普查配对腿覆盖。**fake 用 java.lang.reflect.Proxy
  只路由 getBlockState**：手写 fake 连撞三级接口面（BlockGetter 的 Paper/Moonrise
  追加方法 getFluidIfLoaded/getBlockStateIfLoaded/getBlockEntity + 父接口
  LevelHeightAccessor 的 getMinY）——判例：Paper 接口面的手写 fake 对接口扩展
  脆弱，Proxy 单方法路由免疫。
- **链门**：rebuildPatches / applyPatches / `:paper-server:test` / compileJava
  （--rerun-tasks）全 EXIT=0 + BUILD SUCCESSFUL；0.102.0 出包。
- **普查配对腿**（同 seed 流送窗 60s）：blockpredicates 偏移族 **134→0**；OreFeature
  λ **146→4**（残余=每 doPlace 一个的合法访问器）；blockpredicates-in-stack 总量
  136→2；MutableBlockPos.immutable 总量 162→14（残余散布小面）；**无 ≥20 新面**；
  门双绿（logErrors=0）。

## 判例

1. **"既优化的代价面"判负**：普查可见的分配不等于可收割——若它是一个已落地权衡
   的成本侧（如 memo 快照），重开需先证明能净赢，否则判负。
2. **Paper 接口面的 fake 用 Proxy**：手写实现类对接口的 Paper/Moonrise 扩展脆弱
   （本轮连撞 4 个追加抽象方法）；Proxy 路由被测单方法，接口再扩展也免疫。
3. 会话中断的后台守护腿：按 gate 行核对完整性，缺则补跑单腿（对结构不变）。

内存账（同 seed 流送窗，计数口径）：目标两面 134+146 trips → 0+4（净 −276）；
总样本 8,152→7,830（配对波动）；驻留无变化（纯瞬态）。版本 0.102.0。
