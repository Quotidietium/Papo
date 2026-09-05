# 批次 126（2026-09-05）：粉输入脏追踪生产跳过——三补丁与一次等价性危机（0.74.0 → 0.75.0）

系列背景：批次 125 勘察确立脏追踪上限（87.9% 粉评估无变化）。本批交付生产跳过机制：
粉评估入口检查"输入闭包内自上次评估以来无变化"则整段跳过（无变化路径零可观测效应，
构造性等价）。**但本批真正的产出是等价性危机的完整排查链**——初版机制杀死全部环振荡器，
trace 双腿对拍定位根因后重写标记时序与闭包，终版宏 A/B **blockTicks 中位 −26.4%（1.359×，
分布完全分离）**且振荡速率逐位一致。

## 一、交付（0260 勘察 + 0261 生产 + 0262 闭包修复）

### 0260 — 空间脏追踪勘察探针（诊断面保留，tickProfile 门控）

Chebyshev-1 标记 + clean-at-entry 可跳/dirty 计数（rs.wireEvalSpatialSkippable /
rs.wireEvalDirty）。实测环振荡 85.0% 评估到达时 clean。**计数器语义盲区留档**：勘察只
分别计数 clean 与 no-change，从未交叉表——"clean ⊆ no-change"未被数据证明（见三）。

### 0261 — 生产跳过（初版，存在缺陷，被 0262 修复）

PapoWireDirtyTracking（8 条带 striped LongOpenHashSet + CAP 2^18 泄流阀）：transition
标记 Chebyshev-1 粉集（含自身位——新落位粉存储 power 是放置默认值，首评估必须执行）；
评估入口 clean 则跳过。

### 0262 — 等价性闭包修复（本批核心，四项独立缺陷全部修复）

| # | 缺陷 | 机理 | 修复 |
|---|---|---|---|
| 1 | **标记晚于扇出**（杀死全部环振荡的根因） | `LevelChunk.setBlockState` 在段提交后就内联派发 `onPlace`/`affectNeighborsAfterRemoval`，其邻接更新级联同步执行；初版 mark 在 `Level.notifyAndUpdatePhysics`——**整个 onPlace 级联之后**。种子中继器落位→邻粉评估到达时标记未打→clean→跳过→脉冲边沿死亡（fix 7b91081 的自标记修正治标不治本：mark 本身就来晚了） | mark 移至 LevelChunk 段提交点、任何派发之前；tick 线程（或客户端）执行，世界生成线程跳过（ServerLevel 读取会强制加载区块） |
| 2 | **闭包缺 Chebyshev-2 直通位** | 粉的输入含"导体后方源"：粉 W 隔导体 C 读源 S（W 距 S Chebyshev-2）——中继器/比较器强充能方块、粉在方块旁的最常见拓扑。S 翻转不标记 W → 跳过 → 停在过期值 | mark 闭包扩展：6 轴各查 `pos±d` 是否导体，是则标记 `pos±2d` 粉位 |
| 3 | **无 transition 的信号强度变化类** | 比较器 BE 输出刷新（POWERED 恒 true、模拟值 1→15）经 `updateNeighborsInFront` 通知前方粉；容器/命令方块等经 `updateNeighbourForOutputSignal`——均无 setBlock。审计确认这两个漏斗覆盖全部 wire 相关非 transition 源（阳光传感器 POWER 是状态属性 ✓；压力板/按钮/中继器/火把均为状态驱动 ✓；trapdoor/sculk 模拟值仅比较器可见 ✓） | 两漏斗处挂 mark（调用频率低，成本可忽略） |
| 4 | **全局集跨层位互扰** | 位只按 packed pos 键控：不同维度/客户端-服务端同 packed 位粉互相清除对方位 → 无据跳过 | 追踪器改为 per-Level 实例（`Level.papoWireDirty`），WorldGenRegion 钩子以区域为读取器标记进底层 ServerLevel 的追踪器 |

## 二、诊断方法论（可复用资产）

1. **验证器**（-Dpapo.wireSkipVerify）：跳过时仍计算目标强度并对比存储值——逐例证伪/证实。
   初版结论"0 错误跳过"却环死——**逐例正确 ≠ 流等价**（跳过改变了后续评估流，验证器
   无法捕捉"从未到达的评估"）。
2. **trace 双腿对拍**（-Dpapo.wireTrace=skip0/skip1，同 jar 单属性切换）：评估/变更/标记
   全事件流失序号日志（4 cells × 60s ≈ 176 万行/腿），diff 首分歧。铁证：种子评估
   `EVAL ... curP=0 computed=15 dirty=false`——评估先于标记到达。**同 jar 双腿消除构建
   差异，是等价性排查的最短路径。**
3. **活动门盲区修复**（批次 126 附带交付）：PapoTickProfile 计数器零活动窗口不发行，
   消费方只解析在场行 → 死环沿用最后一个非零值 PASS。修复：ever-seen key 注册表每窗口
   全量打印（含零）。同时修复 harness log-tail 无锁追加的 CME（RedstoneScaleBench）。
4. **拓扑验收 harness**（TopologyVerify）：直通强充能 + 比较器模拟刷新两类拓扑的
   setblock/data-merge 序列 + power 探针，同 jar skip 开/关双腿对照，5 探针全等 PASS。
   （初版探针期望值错误两轮——27 格箱子按平均填充度算、中继器换 unpowered 会被输入侧
   重点亮、FACING 指向输入侧——harness 语义错误由双腿同错暴露，**双腿全等才是验收标准**。）

## 三、验证矩阵

- **宏 A/B**（RedstoneScaleBench N=441 × 240s，两腿背靠背同时窗，10 bot，vanilla，探针同开）：

| 指标 | before（0.74.0） | after（0.75.0） | Δ |
|---|---|---|---|
| level.blockTicks 中位（21 振荡窗） | 27442 us/tick | 20192 us/tick | **−26.4%（1.359×）** |
| 均值 / 分布 | 27542（23078–29907） | 20123（18156–21227） | **分布完全分离** |
| 活动门 rs.blockTickRuns | 441.0/tick PASS | 441.0/tick PASS | 振荡速率逐位一致 |
| 在场门 / logErrors / exit | 441/441 / 0 / 0 | 441/441 / 0 / 0 | — |

  after 腿计数器（尾中位）：评估通知 30870/tick（与 0259 勘察完全吻合）= 跳过 23407
  （**75.8%**）+ 实评 7463（其中 no-change 3714——保守标记的自标记/重标记正确性代价）。

- **trace 复验**（4 cells，终版机制）：腿 B（skip 开）活动门 4.0/tick 精确、验证器全 run
  0 错误跳过、健康腿流中无 clean-载荷性评估。
- **拓扑验收**：直通强充能（中继器 on/off）+ 比较器模拟（箱子 1→27 满格→清空）5 探针，
  skip 开/关双腿逐行全等 PASS。
- **微基准**（WireDirtySkipBench，epoch 负载模型：mark:eval=1:16 对齐实测 1750:30870，
  每 transition 16 次扇出评估中 2.4 次 dirty）：before 108.7±5.1 → after 74.1±3.0 us/op
  （**1.47×**，CI 分离；含 27+6 直通闭包扫描成本）；clean 入口簿记底价 6.6ns/entry。
  自检：10 万随机通知暴力对照（含自身/邻域/远距 transition 判例）+ 4 线程并发 mark
  压力 ALL OK。
- **构建**：全量 applyPatches（0253-0262 链）+ createPapoJar BUILD SUCCESSFUL（补丁往返
  完整性验证）。

## 四、判例

1. **"不变式成立"必须证明标记时序**：0260 勘察的 clean/no-change 两计数器方向一致但
   从未交叉表；即便逐例验证全对，标记晚于扇出意味着"到达的评估"集合本身已被改写。
   **顺序性等价论证必须覆盖事件派发序，不只谓词真值。**
2. **内部仓库 fixup 配错目标**：fixupSourcePatches 的 autosquash 把 5 文件修改配到早期
   "paper File Patches" 提交（与 0253-0261 同文件区冲突）。处置：中止 rebase，软重置
   后以正式提交信息重建 → rebuildPatches 生成 0262。**fixup 流适合小改，结构性多文件
   修改直接顶层提交更稳。**
3. **skip0/skip1 同 jar 单属性切换**是行为对拍的标准腿型（消除构建差异）；PAPO_JVM_EXTRA
   只传单 argv，多 -D 需合并为单属性解析。
4. 环振荡器 load 的 30870 评估通知全量保留、跳过 75.8% 后下游通知流不变——**跳过不改
   变可观测行为的最直接证据是活动门逐位一致（441.0/tick 精确）**。

## 五、版本

0.74.0 → 0.75.0（gradle.properties papoVersion）。
