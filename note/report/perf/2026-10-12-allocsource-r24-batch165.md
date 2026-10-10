# R24（批次 165）：堆外面归因 + 分配源归因与收割——SurfaceRules 热路径零分配化，→0.93.0

日期：2026-10-12　版本状态：0.92.0 → **0.93.0（补丁 0279，代码轮）**　工具：`benchmark/alloccensus-r24.sh`（NMT+JFR 普查腿）、`benchmark/msptload-r24legs.sh`、单测 `PapoSurfaceRulesAllocTest`（5 用例入套件）

## 1. 缺口与本轮目的

内存主线至 0.92.0 已四层穷尽结构收割面（R11 三轴收敛/R19 直对/R20 盲区/R21 状态级），R22 长时生长全绿。两个未量化面：(a) **堆外/直接内存**从未归因（NMT 分类构成、Metaspace 155MB、Netty direct 是否存在可收敛驻留）；(b) R22 观察到年轻代再填充突发（±41% raw used 摆动、eden 扩张至 >1GB），其**分配源**从未做过站点级归因——分配率降低同时服务内存（瞬态占用/GC 自适应）与 CPU（GC 线程工时）两面，属多方面协作复合优化。指令预期候选（chunk 包序列化/光照/DFU 转码）逐一对账见 §6。

## 2. 方法

**NMT 腿**：`MemSoakVerify` 同场景（12 bot/seed papo90/视距 10）附加 `-XX:NativeMemoryTracking=summary`；boot 后 baseline，驻留期 summary.diff + summary + VM.metaspace + Thread.print + GC.heap_info。

**JFR 分配普查**：流送峰窗（行走 t≈30-90s）`JFR.start settings=profile duration=60s jdk.ObjectAllocationSample#threshold=1ms`；`jfr print --stack-depth 8` 导出 9,252 事件；按 objectClass/首个业务帧/线程三键聚合。**计数口径**（判例见 §7）：`jdk.ObjectAllocationSample` 按线程累计字节过界触发，事件权重=该线程自上次采样以来的**全部**分配量、归到过界瞬间的站点——单站点巨权事件不可归因，站点可信度以**事件计数**为准（阈值 ≥20），权重和仅作区间估计（原始与 >100MB 事件剔除双口径并列）。

## 3. 面 (a)：堆外归因——判负

- **Class/Metadata**：+71MB 运行期增长全部为惰性类加载（类空间 committed 25.5MB、非类元数据 ~131MB），代码库常数，无逐内容可收敛项。
- **Code (JIT)**：+65MB，ReservedCodeCache 封顶约束内，服务于性能本面。
- **Thread**：129-130 线程 = R1 池 sizing 既有形态，committed 远小于 reserved。
- **Other（Netty direct 等）**：~13.5MB，量级不构成收割面。
- **Java Heap**：Xms=Xmx 固定 4G（设计如此）；驻留期 eden 535 区 1.07GB 为分配压力下 G1 自适应扩张（见面 (b)）。

**判定：无可收敛堆外驻留**。全部堆外面为代码常数（类/JIT）、有界池（线程）或量级不成立（Netty）。

## 4. 面 (b)：分配源普查（0.92.0 基线）

窗口总量：9,252 事件 / 估重 26,681MB/60s（原始口径估 ~444MB/s；剔除 13 个 >100MB 巨权事件后 16,009MB ≈ 267MB/s——原始口径受暖机巨权抬升，保守取净化口径）。**按线程**：Paper Common Worker #0-11 各 1.3-3.3GB（世界生成 worker，占 ~90%+）+ Server thread 1.46GB/624 事件。计数口径 top 站点（≥100 事件）：

| 站点（首业务帧/类） | 事件数 | 占比 | 归因 |
|---|---|---|---|
| `Suppliers.memoize` 族（+Context$Lambda 733 +Supplier ctor 374⊂590） | 590+733 | 14.3% | **SurfaceRules$Context.updateY 每调用建 lambda+memoize 壳**（SurfaceSystem.buildSurface 逐方块） |
| `Iterators.forArrayWithPosition`→ArrayItr | 786 | 8.5% | **SequenceRule.tryApply 增强_for 每求值建迭代器**（嵌套序列级） |
| `DirectMethodHandle.allocateInstance` | 1,392 | 15.0% | record 构造器分散（BlockPos/树节点/MulOrAdd 等全域） |
| `DensityFunctions$HolderHolder.mapAll`（+Marker 183+ShiftedNoise 65+MulOrAdd 129+TwoArg 100+Mapped 73+Multipoint 65+SplineCoord 49+Holder$Direct 257+NoiseChunk$Lambda 494） | ~1,915 | 20.7% | **密度树 per-chunk mapAll 结构拷贝**（§5 判负面） |
| `Ap2.fillArray` | 768 | 8.3% | 逐单元格密度填充（vanilla 数值管线） |
| `XoroshiroRandomSource.<init>` 族 | 221+144+91+46 | 5.4% | per-feature RNG（种子派生链） |
| `PacketEncoder.encode` | 334 | 3.6% | 残余包编码（批 141 已收割主体） |
| `ImmutableCollections$AbstractImmutableList.iterator`+stream 族 | 309+180+75+56+47+44 | 7.7% | 流/不可变列表迭代器（CubicSpline.mapAll/decoration 等） |

**巨权伪影实证**：`ChunkHolderManager.tick:848 fastIterator` 曾在权重口径显示 596MB(2.2%)——实为**单个 595.7MB 权重事件**（02:40:35.608，Server thread 自上次采样以来全部分配过界瞬间恰落 MapEntry）；该站点全部 3 事件合计权重与计数均不成立 → 判负（伪影判例 §7）。

## 5. 立项收割：批次 165（SurfaceRules 双站点，→0.93.0）

**可收面**：updateY 族（1,323 独立事件：lambda 733+supplier 590）+ ArrayItr（786）= 2,109 事件 = **22.8% 全服 trips**，全部位于世界生成 worker 的逐方块表面规则求值路径，**单文件封闭**（`SurfaceRules.java`；`biome` 字段仓外零引用、Context 仅 SurfaceSystem 两处构造）。

**实现（补丁 0279）**：
1. `Context.updateY` 手工 memoization：`Supplier<Holder<Biome>> biome` 字段 → `Holder<Biome> biome + boolean biomeComputed` + `biome()` 访问器；`updateY` 只失效标志。两处文件内消费点（BiomeConditionSource L188 / TemperatureHelperCondition L427）改调 `biome()`；`Suppliers`/`Supplier` 导入移除。
2. `SequenceRule.tryApply` 增强 for → 索引循环（`get(i)` 对一切可达 List 实现O(1)，序与短路恒等）。

**行为恒等证明**（单测 5 用例钉死）：(i) 惰性——updateY 不触发 getter，首次 `biome()` 才计算（与 vanilla λ 延迟 apply 同构）；(ii) 每 updateY 窗口至多一次——重复调用复用同一 Holder；(iii) 坐标首用时读取——vanilla λ 捕获 updateY 参数，本实现读 `this.blockX/Y/Z`，两个调用点（buildSurface L148 列循环 / SurfaceSystem L200 单求值）序列均为 updateXZ→updateY→tryApply 且窗口内无字段突变（blockY 由 updateY 同参即赋，blockX/Z 列内稳定），值逐点相等；(iv) 抛出重试——标志仅在 apply 成功后置位，与 Guava memoize 只缓存成功结果一致；(v) SequenceRule 空序列/全 miss/首非空短路序恒等。**世界生成结果恒等另有运行时证据**：普查腿直方图密度树驻留（HolderHolder 1,098/Holder$Direct 1,665/Multipoint 525）两版**逐位相等**。

## 6. 验证（0.92.0 vs 0.93.0）

- **构建链**：rebuildPatches → applyPatches → compileJava `--rerun-tasks` → createPapoJar 全部 BUILD SUCCESSFUL EXIT=0（0279 入库）；`:paper-server:test` 套件（82+5）全绿。
- **分配 A/B（同窗同参普查腿，门均 logErrors=0 exit=0）**：目标族 **归零**（Context$Lambda 733→0、memoize 590→0、Supplier 590→0；ArrayItr 786→33，残 33 为结构模板/waypoint 非本面站点）；总 trips 9,252→8,123（**−12.2%**，无偏计数估计）；估重 26,681→16,866MB（原始 −36.8%，净化 −35.3%）——保守主张取计数口径，字节削减区间 −12%~−37%。
- **内存对（同相位 measure 点 + 驻留 GC.heap_info）**：heapUsed 619,579→622,728KB（+0.5%，raw 含 young）；**旧代活集 675,083→674,201KB（−0.13%）**；Metaspace 156,699→156,704KB（同量）；dwell raw used 差全部为 young 瞬时占用（B 采样落 eden 1,127 区填充中段，R22 判例：raw used 非门）。
- **延迟对（msptload-r24legs.sh + 两步对照，四段证据链）**：
  1. **主证据**：行走场景六槽配对三跑两序（run1 主序/run2 倒序/run3 主序），池化 18 样本 A 5.98 vs B 6.32 = **+0.34ms（+5.7%）**，14/18 槽正向、两序同向复现——按 R23 判例不可判瞬态，必须归因。
  2. **机制排除对照（零生成对）**：spread=0（bots 驻留出生点，0279 代码完全不执行）：主序 +0.16、**倒序 5/5 槽逐位相等（1.3/1.3/1.3/1.3/1.2）**——不运行的代码不能造成回归，且测量链在无扰动时可完美稳定。
  3. **GC 窗口证据**：JFR 流送窗 B 侧 young GC 7→5 次、GCPhasePause 总量 310→214ms（−31%）——GC 面严格更优，与"补丁增加 GC 成本"互斥。
  4. **机制隔离（钉年轻代对）**：`JDK_JAVA_OPTIONS=-XX:NewSize=1G -XX:MaxNewSize=1G`（消除 G1 自适应 eden 分叉；普查腿实测 A 自然 eden 535 区 1.07GB vs B 1,127 区 2.2GB）：**B 六槽全快（−0.2/−0.2/−0.3/−0.6/−0.6/−0.8ms，均值 −0.45）**。
  
  **归因结论**：未钉配置的 +0.34ms = 分配削减 −35% 后 **G1 自适应把 eden 扩大一倍（吞吐启发式）**的摊销效应，不是补丁计算成本（w1m-min 全平=计算地板无差佐证）；年轻代等化后分配削减的 GC 红利显现为 B 全面更快。TPS 全程 20.0，绝对 6.3ms 距 50ms 预算 8×。本改在两条配置下均不构成红线回退（未钉=GC 启发式权衡响应 0.7% 预算；钉=−7% 改善）。

  | 槽 | 相位 | run1 A/B | run2 A/B | run3 A/B | 钉1G A/B |
  |---|---|---|---|---|---|
  | s1 | 流送 30s | 8.6/8.3 | 8.6/8.3 | 8.1/8.6 | 8.4/7.8 |
  | s2 | 流送 75s | 4.4/4.5 | 4.3/4.5 | 3.8/4.4 | 4.3/4.1 |
  | s3 | 流送 120s | 6.3/6.6 | 6.4/6.7 | 5.8/6.5 | 6.4/5.8 |
  | s4 | 驻留 15s | 7.7/8.0 | 6.9/8.1 | 7.2/7.7 | 7.8/7.0 |
  | s5 | 驻留中期 | 4.9/5.5 | 4.5/5.3 | 5.0/4.9 | 5.1/4.8 |
  | s6 | 驻留末期 | 5.0/5.3 | 4.8/5.3 | 5.3/5.2 | 5.2/5.0 |

- **指令预期候选对账**：chunk 包序列化=PacketEncoder 68MB/60s 残量（批 141 已收主体，光照/实体包正常编码，不立项）；**光照=计数口径 absent**（LightEngine 无 ≥20 事件站点——批 148/149 光照读路径已零分配化的旁证）；**DFU 转码=absent**（本窗为全新生成非盘载，转码为加载路径；且 R22 已证在途视图为 boot 常量表）。

## 7. 判例

1. **JFR 单站点巨权不可归因**：ObjectAllocationSample 权重=线程自上次过界以来全部分配，巨权事件（595.7MB×1）把 Server thread 全部质量涂到过界瞬间的任意站点（tick:848 fastIterator 曾据此误显 2.2%）；站点可信度必须以计数为准 + 阈值过滤（≥20），权重和只能作区间（原始/净化双口径并列）。
2. **计数与权重是两个估计器**：计数无偏低方差（9,252 样本），权重有界外偏差（区间相关）；二者方向一致时结论硬，仅权重支撑的站点不得立项——R21/R22 的年轻代摆动误读在分配面的同构判例。
3. **分配率收割的立项门槛**：驻留零收益（树随 NoiseChunk 释放，批 143/160 已闭合）+ MSPT 地板余量 10×（R15/R23）时，只有**封闭单文件 + 行为恒等可证 + 簇级份额（>20% trips）**三者齐备才动刀（本轮 SurfaceRules 面）；深入地形生成核心的多文件架构面（密度树 mapAll）判负入档。
4. **种子派生链是绝对红线**：XoroshiroRandomSource/LegacyRandomSource 构造分配（5.4%）是 per-feature RNG 语义本体——任何"缓存/复用 RNG"改写改变世界生成结果，直接出局，与分配份额无关。
5. **分配削减改动的 MSPT A/B 必须隔离 G1 自适应年轻代这一个混杂因子**：分配率 ↓→G1 吞吐启发式扩 eden（535→1,127 区），未钉配置下该布局变化读作 +0.34ms 摊销差（两序复现、形似真回归）；隔离法=零生成对照（补丁不运行时逐位相等）+ `-XX:NewSize=MaxNewSize` 钉死（等化后 −0.45ms 纯赢）。**"回归"可能是 GC 启发式对优化的响应，不是优化成本——未做此隔离前不得下回退结论，也不得 silent 放行**。

## 8. 判负面存档

- **密度树 per-chunk mapAll 结构拷贝（~20.7% trips）**：vanilla 架构——`NoiseChunk` ctor `noiseRouter.mapAll(this::wrap)`+`cacheAllInCell(...).mapAll(this::wrap)` 两遍全树拷贝；`wrapNew` 对 Marker 节点必须逐区块新建（NoiseInterpolator/FlatCache 绑定本区块插值器），组合节点绝大多数位于 Marker 之上，"子树未变则返回 this"的 memoized mapAll 可收份额小而需改 14 个节点类 mapAll；驻留收益为零（树生命周期已闭合）、MSPT 无痛点 → 红线风险/收益比不过，判负。
- **`Ap2.fillArray`/数值管线、BlockPos 族（Server thread 正常实体 tick）、GC/调度器内生分配**：vanilla 语义本体或量级健康。
- **面 (a) 堆外**：§3 判负。

## 9. 结论

R24 双面完成：面 (a) 堆外以 NMT 全分类证据判负（无可收敛驻留）；面 (b) 分配源完成站点级归因（计数口径），收割最大可收簇 SurfaceRules 双站点（22.8% trips → 0，总分配 −12.2% trips/−35% 净化估重），世界生成逐位恒等 + 旧代活集 −0.13% + 延迟面四段证据链闭合（G1 自适应混杂隔离后 B 全面更快 −0.45ms）。**主线至 0.93.0：每区块驻留 101.4KB 量级维持（本改为分配面，驻留设计上零差），年轻代再填充突发源归因闭合；GC 面直接收益 young GC −29%/STW −31%（流送窗）。**
