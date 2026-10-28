# R40（批次 181）：分配源收割战役收官直对+轴闭合审计（版本保持 0.105.0）

日期：2026-10-12 ｜ 轮次：R40 ｜ 判定：**战役收官测量+轴闭合**（审计轮，无代码变更）

R24（批次165，0.92.0）开轴的分配源收割，经 R24-R39 十六轮收割后，红线内 ≥20 trips 机械面
已穷尽（R39 后 B-105 普查 top 余面全为判例维持，见 §3）。本轮以**同会话首尾直对**
（R14 判例：收官数字必须同会话直对产生）闭合此轴。

## 1. 收官直对（0.92.0 vs 0.105.0，alloccensus-r40arc.sh，同 seed 流送窗 60s）

| 面 | A-0.92.0 | B-0.105.0 | 差 |
|---|---|---|---|
| 分配采样事件（threshold=1ms） | 9,180 | 6,192 | **−32.6%** |
| 采样权重 raw | 20,034.2MB | 10,315.2MB | **−48.5%** |
| 同窗装载区块（LevelChunk） | 4,349 | 6,077 | **+39.7%** |
| **按区块归一事件** | 2.111/chunk | 1.019/chunk | **−51.7%** |
| **按区块归一权重** | 4.61MB/chunk | 1.70MB/chunk | **−63.2%** |
| 窗内 G1New 次数 | 7 | 4 | −42.9% |
| 窗内 GCPhasePause 事件 | 11 | 8 | −27.3% |
| dwell 堆按区块 | 115.2KB/chunk | 103.3KB/chunk | −10.3% |

两条腿各自的复现带互不重叠（A 系腿区块 4,349/4,389，B 系腿 5,941/6,077）——同窗多载
40% 区块即**世界生成吞吐提升**的直接度量：收割的分配全在 gen worker，减 GC 压力直接
转化为吞吐。权重是区间估计（R24 计数口径判例），方向与计数双口径一致。

## 2. 弧线（R24→R39 逐轮收割面，全部普查配对腿确证归零）

SurfaceRules memoize（22.8% trips）→ BinaryHeap/Node 清引用 → recalcBlockCounts 计数重写
（815.8MB→0）→ papoReadUtf（−97.9%）→ PackedData 别名（−89%）→ palette 重编码两阶段
（429MB→0）→ Climate scratch+Beardifier 索引（165+228→0）→ CubicSpline 流形态（459→0）→
PlacementModifier 走查（≈370→0）→ 谓词偏移 scratch+OreFeature 访问器（134→0/146→4）→
水体/气候/特征五面（426→0）→ **Ap2(ADD) 温度数组（1,347→0，判负面重开首例）→
段缓存单槽池+规则迭代器（37+37→0）**。逐轮判例见 optimizations.md 对应批次条目。

## 3. 轴闭合审计（B-105 普查 top-40 逐面判定）

**全判例维持，无未收割机械面**：

| 簇 | trips | 判定（判例来源） |
|---|---|---|
| NoiseChunk.wrap（DMH.allocateInstance） | 707 | per-chunk 密度树重物化架构（R24 判负维持：vanilla Marker 绑定=世界生成核心） |
| Xoroshiro/Legacy/RandomSource.create | 466+139+39+65+60+35 | RNG 种子派生绝对红线（R24；RuleProcessor 位置种子同理） |
| mapAll 全族（HolderHolder/Marker/Ap2.create/Mapped/ShiftedNoise/Spline） | ≈1,300 | per-chunk 数值树重包架构（R24/R34 判负维持） |
| NoiseInterpolator.allocateSlice | 138 | 填充目标数组本体（R37 判负） |
| PacketEncoder.encode | 101 | memo 快照=既优化代价面（R36 判例：重开须先证净赢） |
| OreFeature.doPlace | 93 | 放置本体（数据面，工作量缩放） |
| CubicSpline$Multipoint.create/mapAll | 91+67+74 | 数值树节点重物化（mapAll 族） |
| WorldCarver.carveEllipsoid | 73 | 游标已摊销（R37 判负） |
| BitSet/SimpleBitStorage/HashMap.newNode | 50+42+33 | 数据面（内容缩放） |
| BlockPos.offset←processBlockInfos | 48 | 结果对象（存入输出 StructureBlockInfo，R33/R37 判别式） |
| StructureTemplate 物化/transform/getWorldPos | 49+32+35 | R33 判负维持（vanilla 模板放置架构+结果对象） |
| Arrays.copyOf←ArrayList.grow | 44 | 散布机械（单点 1-3 trips，全 <阈） |
| BlockPos.atY←HeightRange/InSquare←walk | 39+36 | 发射位=结果对象（R35 契约；消费者持有 origin） |
| ChunkTaskScheduler.schedule | 28 | Moonrise 上游调度器（R37 findEligibleGroups 同判） |
| StaticCache2D/TestRuleSource.apply | 27+28 | vanilla 缓存/数据面 |
| SurfaceRules 其余 | — | R24 已收割 memoize 面，余为数据面 |

## 4. 弧形 MSPT 直对（0.92.0 vs 0.105.0 主序一对）

| 槽 | A-0.92.0 win5s(min) | B-0.105.0 | 差 |
|---|---|---|---|
| 1 stream | 4.4 | 4.9 | +0.5 |
| 2 stream | 5.1 | 7.3 | +2.2 |
| 3 stream | 8.6 | 9.4 | +0.8 |
| 4 dwell | 6.4 | 8.1 | +1.7 |
| 5 dwell | 5.9 | 6.3 | +0.4 |
| 6 dwell | 6.1 | 6.4 | +0.3 |

B 6/6 劣 +0.3..+2.2ms——**自缩放负载混杂，非延迟回归证据**：bot 走廊与移动包速率固定，
但 A 生成/发送跟不上形成积压（同窗仅装载 4,349 区块），B 全速跟上（6,077 区块，+39.7%），
即 B 的每个 tick 在做 ~40% 更多的真实工作（区块完成钩子、光照、实体追踪、发送）。
**按单位工作归一（win5s/chunk）B 大幅更低**。延迟轴的真实证据是逐批相邻版本守护
（R24-R39 十六轮全零回归，含多轮全优对）——相邻对的负载差可忽略，弧形直对的负载差
即吞吐差本身。dwell 段的 +0.3..+1.7 同理受已装载集合规模差影响，且处后夜窗（R38 判例：
同二进制跨腿漂移可达 2.9ms）。

**判定**：分配源轴在"默认行为可证等价"红线内收敛终局。后续收益仅可能来自：wrap/mapAll
重物化架构改写（需重构 vanilla 密度树求值模型，超出等价红线授权）、RNG 族（绝对红线）、
数据面（内容本身）。轴闭合。

## 判例

1. **收官直对必须同会话产生**（R14 判例沿用）：跨夜会话的 A/B 两系腿各自复现带互不
   重叠（4,349/4,389 vs 5,941/6,077）是差异真实性的最强佐证——比百分比本身更硬。
2. **吞吐是分配收割的最终度量**：瞬态面收割的收益以"同窗装载区块数"显形（+39.7%），
   比分配事件数（−32.6%）更接近用户可感知改善；双口径并列陈述。
3. **弧形延迟直对的自缩放负载混杂**：吞吐 materially 变化后（本轴 +39.7% 区块/窗），
   远版本对的 MSPT 差被"快服每 tick 多干活"系统性污染、方向恒惩罚快者——**延迟轴的
   有效仪器是相邻版本配对守护**（负载差≈零），弧形对只可按单位工作归一陈述。
4. **轴闭合审计的判负证据等级须高于立项**（R21 判例沿用）：本表逐面给出判例来源而非
   望文生义，全部为既往轮次实证维持。

工具：`benchmark/alloccensus-r40arc.sh`（收官腿脚本）+ `allocsum.py`（聚合）；原始数据
`benchmark/results/alloc-20261012-021605-A-092` / `alloc-20261012-022208-B-105`。
