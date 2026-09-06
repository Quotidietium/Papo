# 批次 130（2026-09-07）：fan-out 读路径 chunk 解析提升（0.78.0 → 0.79.0）

## 一、画像驱动选型

0.78.0 单腿 JFR 诊断（N=441×240s，settings=profile，13967 执行样本，门全 PASS，
`benchmark/results/b130-jfr-leg.log`，录制 F:/TEMP/b130-jfr.jfr）：

| 热点 | 占比 |
|---|---|
| BlockStateBase.handleNeighborChanged（派发桶，含内联读链） | **15.20%** |
| ZeroCollidingReferenceStateTable.get（属性读底座） | 7.12% |
| PapoWireDirtyTracking.markIfWire（129 后从 9.33% 降） | 5.99% |
| SimpleBitStorage.get | 3.19% |
| CollectingNeighborUpdater.addAndRun | 3.12% |
| BlockStateBase.isRedstoneConductor | 3.06% |
| ChunkHolder.blockChanged（ShortOpenHashSet.add 链） | 2.46% |

栈聚合判决：**MultiNeighborUpdate.runNext 路径 19.5% 样本，其中仅 4.1% 到达粉
通知（RedStoneWireBlock.neighborChanged），~15% 是无观察者目标的派发机械**
（每目标 1 次 BlockPos 分配 + 1 次 getBlockState 区块哈希查找 + 物理事件门 +
无操作虚派发）；26239.5 跳过 + 4630.5 执行 + ~126K 无操作派发/tick。

**方案筛除**（论证入档）：
- section 级"无观察者块"位图跳过派发——密集红石区无效（fan-out 目标所在
  section 必含红石组件=观察者，位恒置位），且位维护引入 setBlock 热路径成本
  与陈旧位正确性风险；
- 通知去重（42 派发中 17 重复）——改变 BlockPhysicsEvent 计数与派发序，
  违反事件流恒等红线（125 批 HashSet 逐位复刻序判例）；
- 池化/分配消除——124 批否决判例（JIT 标量替换破坏）。

**入选**：把每读一次的区块哈希查找折叠为每闭包/每扇出一次的 chunk 解析——
读语义逐位不变，纯查找次数削减（124 判例的安全对偶面）。

## 二、交付

**F1（直提交，`src/main/java`）**：`PapoWireDirtyTracking.mark` 列固定 chunk
解析。18 扫描位 + 6 轴向导体探测 + ≤6 直通目标全部位于 pos 的 x±2/z±2 内
→ 至多 2×2 chunk 槽位；每槽经 Level.getBlockState 内部同一调用
（getChunk(cx,cz,FULL,true)，fullChunks 快查+强加载回退语义保留）解析一次，
每读直走 chunk.getBlockState——**每 mark 区块查找 24-30 次 → ≤4 次**。
路由：Level 读者走 markLevel（captureTreeGeneration 与 ChunkPos.isValid 前置
检查回退 markGeneric）；WorldGenRegion 保持原逐读路径。VOID_AIR/AIR 等价性：
两者皆非粉、非导体，标记行为零差。

**F2（补丁 0265）**：`CollectingNeighborUpdater.MultiNeighborUpdate` 同 chunk
读快路径。六向目标至多跨一个 chunk 边/轴；runNext 首次惰性解析源 chunk
（requireChunk=false 纯 fullChunks 查找、零副作用、ChunkAccess 接口类型——
currently-loading 回退可交出非 FULL chunk，虚 getBlockState 与 vanilla 同形），
同 chunk 目标直读。门：chunk 坐标匹配 + !isOutsideBuildHeight(目标)
（VOID_AIR 对 BlockPhysicsEvent 监听器可见性保持）+ !captureTreeGeneration；
任一 exotics 回退逐字节 vanilla 读。缓存有效性：runUpdates 突发内无 chunk
卸载/替换点（同线程调用栈内），moonrise chunk 状态晋升为同实例原位晋升。

## 三、验证矩阵

### 产物与拓扑

- 0.79.0 嵌套 jar 字节码验证：PapoWireDirtyTracking 含 markLevel/papoChunk/
  markGeneric；MultiNeighborUpdate 含 papoChunk/X/Z 字段。
- TopologyVerify 10/10 PASS（T1-T4 全过）。
- WireDirtySkipBench 自检 ALL OK（10 万暴力对照 + 4 线程条带锁压力；闭包形状
  未变，模型无需同步）。
- compileJava 绿；补丁 0265 单文件 49 插入。

### 计数器预期形态

纯路径成本削减（查找次数削减），标记/评估/跳过种群零变化——四腿计数器应与
127/128/129 逐位恒等（441.0 / 4630.5 / 882.0 / 26239.5）。

### 宏 A/B 第一套（ABAB 四腿×240s，A=0.78.0 B=0.79.0）

腿中位 A1 15113 / B1 15466 / A2 20070 / B2 28765——**环境强漂移污染**（B2 前
60% 落入共租热窗 26-37ms 后恢复 17.9-18.7；B1 冷窗 13.1-14.8 低于 A1 全域
14.4+），中位口径失效（合并 +7.5% vs 地板 p25 −2.0% 自相矛盾）。判例：与
124 批修正判例同型的共租漂移，改用细交错复测。

### 宏 A/B 第二套（ABABABAB 八腿×120s 细交错，判定套件）

| 配对 | A 腿中位 | B 腿中位 | A 最小窗 | B 最小窗 | 地板差 |
|---|---|---|---|---|---|
| 1 | 18426.6 | 18181.6 | 16651.3 | 16372.4 | −1.7% |
| 2 | 20666.2 | 27508.4* | 19269.6 | 17031.9 | −11.6% |
| 3 | 21760.2 | 18271.1 | 18742.3 | 16999.9 | −9.3% |
| 4 | 27909.3 | 17705.6 | 18949.5 | 15324.6 | −19.2% |

- *B2 腿中位被腿中程热窗污染（min 17.0 已回到全域）——2A 腿另遇 12 条
  **teardown 套接字拆除 IOException**（Windows 关闭竞态，非服务器行为；门全
  PASS/窗口序列完整平滑，数据保留并披露）。
- **合并中位 20959.9 → 18301.2（−12.7%，60+60 窗）**；**四配对最小窗（环境无关
  地板）全部分离为 B < A（−1.7%/−11.6%/−9.3%/−19.2%）**；合并最小窗
  16651.3 → 15324.6（−8.0%）。
- 四腿×2 套 presence/activity/logErrors 门全 PASS（2A 的 IOException 披露
  如上），活动门 441.0/tick 精确。
- 机制算术交叉验证：F2 省约 121K 查找/tick × ~12ns ≈ 1.45ms（blockTicks 的
  −8%）+ F1 ≈ −2~3% → 预测 −10~11%，与实测地板 −8% / 中位 −12.7% 相符。

### 计数器等价

两套 12 腿全部与 127/128/129 **逐位恒等**：blockTickRuns 441.0 /
wireEvalRuns 4630.5 / wireEvalNoChange 882.0 / wireEvalSkipped 26239.5——
查找次数削减零行为变化（纯路径成本削减）。

### 0.79.0 JFR 机制归因（对照 0.78.0 同夜基线）

红石家族内份额：runNext 路径 **55.4% → 50.2%**（−9.4% 相对，F2 直接目标
达成）；handleNeighborChanged 自身帧 15.20% → 2.41% + runNext 自身帧升至
8.54% = 快路径分支改变内联归属（工作总量由宏基准裁决，非病理）。mark 路径
家族份额持平（27.8→27.7%）——F1 的读削减被归因洗牌掩盖（markIfWire 自身
份额 17.0→15.8% 家族内），其净效应由宏合计口径承载。

## 四、版本

0.78.0 → 0.79.0（gradle.properties papoVersion）。

## 五、原始数据

`benchmark/results/b130-macro-A1..B2.log`（第一套 240s，漂移污染披露）、
`benchmark/results/b130-macro8-1A..4B.log`（判定套件八腿）、
`b130-jfr-leg.log`（0.78.0 基线 JFR）、`b130-jfr-newleg.log`（0.79.0 JFR）
（JFR 录制 F:/TEMP/b130-jfr.jfr、b130-jfr-new.jfr 会话内留存）。
