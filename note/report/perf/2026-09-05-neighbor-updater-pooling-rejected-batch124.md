# 批次 124（2026-09-05）：邻接更新对象池化——**否决轮**（0258 宏劣化 −20~26%，回退，版本保持 0.73.0）

## 动机

批次 123 后画像的次族：CollectingNeighborUpdater 每次排队分配 record
（Shape/Simple/Full/Multi + Full/Shape/Multi 的 pos.immutable() 防御拷贝），
N=441 环振荡负载 ~29k 对象/tick；JFR 叶帧 runUpdates 2.31% + ArrayList.clear 1.07%
+ 分配/GC 摊销。

## 实现（0258，已回退）

四种更新形态改为可变类 + 每型自由表循环：acquire() 全字段初始化、
papoRecycle() 在唯一 pop 点（或异常路径 drain）归还、位置快照进对象内
MutableBlockPos（替代 immutable() 拷贝；vanilla SimpleNeighborUpdate 原本直存
调用方引用，快照严格更安全）。执行序/层叠机械/上限/debug 发射逐行保留。

## 证据链（否决）

1. **JMH**（NeighborUpdaterPoolBench，忠实复刻层叠机械+嵌套 fan-out）：
   分配 44029 → **0.37 B/op**（≈归零，44KB/根扇出消除）；时间 52.1±21.6 vs
   54.1±39.5 us——模型内**时间中性**（CI 大幅重叠，共享机噪声）。
   自检：100 试验 × 1000 根执行序逐条全等 + 上限路径一致 ALL OK。
2. **宏基准**（RedstoneScaleBench N=441×240s，振荡窗分布，b123 内容为对照）：
   - b123（0253-0257）：中位 23043（n=20，22549–27200）
   - b124 leg1（+0258）：**中位 28993**（n=21，22964–34218）
   - b124 leg2（+0258 复跑鉴别）：**中位 28091**（n=20，22390–33354）
   两腿复现一致 → **真实劣化 ≈ −20~26%**，非共租噪声（JFR 亦无病理帧：池化类
   未上榜、getDirectSignal 反而跌出前五——归因不是池类本身的热点而是间接性
   成本：可变类+自由表破坏 JIT 对 record final 字段的标量替换/内联友好性）。
3. **判例**：与 R2 批次60（逐包 execute 批量化 MPSC 1.49× 劣化）同型——
   分配消除的机械成本超过分配本身；TLAB bump 分配 + record 标量替换在真实
   JIT 环境下近乎免费，池化的自由表间接性不是。

## 处置

- 补丁 0258 从链上删除（applyPatches 重建 0253-0257 树，compileJava 绿）。
- NeighborUpdaterPoolBench 保留入库（机制模型+执行序等价自检可复用于未来
  重试；JMH/宏原始数据入 results/）。
- 版本保持 0.73.0（无交付面变化）。

## 教训（判例入库）

**"分配归零"不是充分目标**：JMH 模型的时间中性 + 分配归零组合仍可能被真实
工作负载否决——record→可变池化类的重构会系统性破坏 JIT 优化面（final 字段、
标量替换、单态内联），该成本不出现在等价复刻模型中（模型方法体太小）。
此类重构必须过宏基准裁决才能入库。
