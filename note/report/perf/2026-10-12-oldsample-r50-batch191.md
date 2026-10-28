# R50 老年代驻留的分配站点归因轴（长时相变窗）——判负闭合（批次 191，2026-10-12）

**版本保持 0.110.0（无代码变更；轴判负闭合轮，R48/R49 同型）**

## 1. 背景与选轴

R38 判例六度应用 + 证据等级升级：R24-R49 分配普查八域全部测"分配站点"
（年轻代垃圾近乎免费），R5-R22 驻留普查只有类级直方图——**从未测存活/晋升
归因**：哪些分配站点产生的对象最终驻留老年代（真实内存压力）。
`jdk.OldObjectSample` 携带 allocationTime/objectAge/分配栈=驻留可直接归因
到分配站点+路径。这是比"新场景窗"更高一档的证据等级（测量维度本身升级，
而非场景叠加）。

## 2. 工具与腿

- **oldsum.py**（入库）：OldObjectSample 聚合器（cutoff 过滤 boot 常量、
  depth-N 站点键、类汇总、in-stack 判定辅助）。
- **alloccensus-r50old.sh**：单发现腿（12 bot 标准 MemSoakVerify 形态 walk
  150s+驻留 22min；JFR settings=profile 全程 1500s，boot+60s 起；门=端口
  独占门+MemSoak.gate exit 0 logErrors=0 全绿）。
- 探针轮（5min 短录）：验证事件结构（allocationTime/objectAge/全分配栈在
  场、cutoff 可行性）。

**工程判例：不可并行两个 harness**——首轮探针服务器未退即启动腿，同端口
25599 互踩（探针 bots SocketException 连锁→探针 finally stop 杀共享连接→
腿注定失败）；正式腿加端口独占门（jcmd 扫残留 harness/服务器即中止）。

## 3. 发现与面判定（32 样本全归因）

25min 长窗 OldObjectSample 共 32 样本（采样稀疏=老年代晋升质量本身极小，
与 R12/R22 老代 ±0.4% 无生长一致），**全部落入三类既有判例清单，零未解释
面**：

| 站点族 | 样本 | 判定 |
|---|---|---|
| chunk 系活态映射（Moonrise SynchronisedLong2ObjectMap rehash/ChunkEntitySlices/NearbyPlayers$TrackedChunk/LevelTicks.addContainer） | 6+ | 语义活态：追踪结构即区块在场状态本体（上游 Moonrise+vanilla tick 调度），卸载即回收（R12 周期腿实证） |
| palette 增长数组（PalettedContainer.onResize→SimpleBitStorage long[256]×7/long[342]） | 8+ | 数据内容本体：walk 相生成时 palette 随方块放置增长，晋升=区块内容驻留（R13 熵下界域，变长=用户终局） |
| 生成期状态（CarvingMask BitSet long[1536]×5/PapoPosTypeSet.resize） | 6+ | 语义状态：雕刻掩码/计划刻集合随 chunk 活（PapoPosTypeSet=R6 自有优化的既定形态） |
| 光照引擎有界 scratch（StarLightEngine.resizeIncreaseQueue/SWMRNibbleArray.updateVisible） | 6+ | 常驻 scratch=引擎线程实例持有，容量有界（R8 光照域既定形态） |
| TL scratch（CompoundTag lambda static byte[8192]） | 1 | R30 papoReadUtf 有界 TL 缓存=既定代价面 |
| 上游诊断（spark node CHM） | 1+ | R41 剥离族维持 |
| 寻路缓存（FlyNodeEvaluator map） | 1 | vanilla 有界缓存 |

**关键正交验证：R5-R22 已收割面零回流**——PapoShapeCache/palette 规范化
Data/容器共享/SWMR 冻结壳/finalverify 常量表在晋升样本中全不出现：收割
消掉的对象从未晋升老年代（分配即死的面本来就不入老年代；驻留收割面则
被规范化为共享单例=不再随站点分配）。**分配收割与驻留收割在存活维度上
互不回流，双轴闭合的存活维确认。**

## 4. 判负闭合论证（四支柱）

1. **零未解释面**：32/32 样本全部归入语义活态/数据内容/有界 scratch/上游
   四类既有清单——本轴零新机械面。
2. **量级**：25min 稳态窗采样老年代字节 ~0.1-0.2MB——老年代晋升质量本身
   极小（与 R12 十样本 ±0.4%、R22 常量表逐位稳定互证）；无 mass 可收。
3. **语义面不可消除**：chunk 映射/palette 内容/光照数据/生成状态=区块
   在场语义本体，卸载即回收（周期腿实证）；scratch 有界。
4. **方法学边界声明**：OldObjectSample 采样稀疏（非穷尽），但本轴结论不
   依赖穷尽——质量上界已被 R12/R22 独立封闭（±0.4% 无生长），本轴提供的是
   归因分辨率升级：上界内的晋升者全数语义/有界，无一可收。

**结论：老年代驻留的分配站点归因轴判负闭合**（第九域=存活归因维度），
与 R48/R49 构成连续三档证据等级升级（新场景轴×2+测量维度轴×1）全部确认
既有闭合。内存战役红线内收敛结论在存活维度获得最终确认。版本保持 0.110.0。

## 5. 工件

- 聚合器：`benchmark/oldsum.py`（OldObjectSample；R24 计数/权重判例不适用，
  本轴以字节+归因为准）
- 腿脚本：`benchmark/alloccensus-r50old.sh`（含端口独占门）
- 数据：`results/allocold-*-B-110old/`（old.jfr+oldsum 聚合）
- 探针：`results/oldsample-probe/olds.jfr`、`results/r50-probe.log`（事故轮）
