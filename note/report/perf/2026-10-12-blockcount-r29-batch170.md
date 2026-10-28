# R29（批次 170）：盘载反序列化分配面——recalcBlockCounts 计数重写收割（→0.95.0）

日期：2026-10-12 ｜ 轮次：R29 ｜ 判定：**收割**（0.94.0 → 0.95.0）

## 动机

R28 判负时留下的 JFR 数据首次照亮了**盘载反序列化窗**（历轮全新生成浸泡中 absent、
但真实服务器区块获取主路径正是盘载）。帧级归因后窗口第一分配面不是 DFU 引擎本身，
而是区块加载时的方块计数重建：

```
ShortArrays.forceCapacity      334 trips  487MB ┐
ShortArrayList.initFromCapacity  20 trips  317MB ┘→ SimpleBitStorage.moonrise$countEntries
                                                    ← LevelChunkSection.recalcBlockCounts
                                                    ← LevelChunkSection.<init>
                                                    ← SerializableChunkData.parse
```

`moonrise$countEntries` 为**每个 palette id 物化一个坐标 ShortArrayList**（含占绝对
多数的 air/stone 大列表，64→128→…→4096 倍增链全程重分配），而消费方
`recalcBlockCounts` 只需要**每 id 计数**——坐标列表仅对 randomly-ticking 态有用。
帧匹配归因（栈中含 countEntries 帧）合计 366 trips/815.8MB = 60s 窗采样总量 3,842MB
的 **21.2%**。第二面（CompoundTag 流式加载 O2O 默认容量增长链 119 trips/448MB）因
NBT 复合标签流式格式**无计数字段**无法预算容量，判负（格式即兼容红线）。

## 收割（补丁 0281）

四处编辑，语义恒等：

- `BlockCountingBitStorage` 接口新增 `moonrise$countByIndex(int[] counts)`（计数-only
  遍历）与 `moonrise$collectIndices(int paletteIdx, ShortList out)`（单 id 坐标延迟
  收集）；旧 `moonrise$countEntries` 原样保留（不再被 recalc 调用，作单测 oracle）。
- `SimpleBitStorage`：两个新方法与旧遍历**完全同形**（valuesPerLong/bits/mask/size
  局部化 + 同一 do-while 终止条件），无 per-id 列表。
- `ZeroBitStorage`：全零快路径（counts[0]+=size；id≠0 收空）。
- `LevelChunkSection.recalcBlockCounts`：paletteSize==1 的 FULL_LIST 分支原样保留；
  >1 分支改为**两遍法**——遍 1 flat `int[paletteSize]` 计数（唯一小分配，精确尺寸），
  按上升 id 序累计四计数器；仅当 totalTicking≠0 时遍 2 对 randomly-ticking id 逐个收
  集坐标，tickingBlocks 以精确 totalTicking 预容。

恒等性论证：四计数器为交换短加法求和（求和序无关，值恒等）；tick 列表**集恒等**
（插入序不同，但唯一消费方 ServerLevel 随机 tick 以 `nextInt() & 4095` 均匀索引——
顺序不可感知，且随机源为线程时序相关本就不可复现）；air 跳过/流体不双计/vanilla
计数 bug 注释语义全部原样保留。

## 验证

- **单测**（`PapoBlockCountRecalcIdentityTest`，入套件）：①SimpleBitStorage bits∈{4,5,6,8,13}
  稀疏数据——per-id 计数与坐标序列对旧 oracle 逐元素相等，缺席 id 收空；②ZeroBitStorage
  同构；③混合 section（stone/grass/water/lava/powder_snow 分区 2,100 实块）——重算后
  四计数器+tick 集与 setBlockState 增量簿记（独立引擎）完全一致（勘误：lava 的
  BlockState 本身 isRandomlyTicking——期望 300 而非 200，两侧引擎一致）；④零 ticking
  section 重算后 ShortList 惰性 map 保持未分配。全套 8,994 测试绿。
- **链门**：internal commit → rebuildPatches/applyPatches/test/compileJava 全 EXIT=0+
  BUILD SUCCESSFUL；createPapoJar 出 0.95.0。
- **配对浸泡腿**（DfuSoakCensus，同源世界 10,296 块降 3465，12 bot 专用池，JFR +25s/60s）：
  - A=0.94.0：LEGACY 面 **366 trips/815.8MB**；B=0.95.0：**0 trips/0MB**——面消灭。
    新面（recalc 帧）仅 6 trips/6.1MB（int[] 多低于 1ms 采样阈，多数未被采样）。
  - 驻留末直方图：B 多载 83% section（bot 覆盖差异），全部差分为内容缩放类且
    ShortList 与 LevelChunkSection 增量逐个相等（每 section 一字段）；无异常类聚集。
  - 启动常量第三腿确认：datafixers templates 22,617/34,273 A/B 逐位相同。
  - 窗内 GC：2 vs 2 次（暂停 24.3/29.6ms 亚噪声）；两腿门 logErrors=0 exited=true，
    降版覆盖 10,296/10,296 各自成立。

## 判例

1. **盘载窗是独立分配面**：全新生成浸泡对反序列化路径零覆盖，真实服务器的主区块
   获取路径需要专用腿（R28 的 DfuSoakCensus 现为可复用配对工具）。
2. **物化-需求差收割范式**：先问消费方需要什么（计数），再问供给方造了什么（全坐标
   列表）——差值即收割面；延迟收集到仅需要的分支。
3. 旧实现保留为测试 oracle 是零成本的最强恒等证据（同树双引擎对拍）。

内存账（盘载突发窗）：−815.8MB→6.1MB（面内 −99.3%，窗口占比 21.2%→0.2%）；
稳态驻留零变化（本面纯瞬态）。版本 0.95.0。
