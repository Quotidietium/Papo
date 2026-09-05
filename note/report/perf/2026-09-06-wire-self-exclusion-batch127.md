# 批次 127（2026-09-06）：粉自身位排除——批 126 正确性代价的收回（0.75.0 → 0.76.0）

系列背景：批次 126 交付粉输入脏追踪生产跳过，其中"自标记"是为保新落位粉首评估而付的
正确性代价（自身 POWER 翻转后的自通知到达时 dirty，多付一次无变化全量评估）。本批
把该代价收回：**自身位排除出标记闭包 + 评估器 updateShape 旁路**。

## 一、交付（0263 单补丁 + 直提交跟踪类）

### 机制

- **自排除（PapoWireDirtyTracking.mark）**：粉自身 POWER 不是自身计算的输入
  （calculateTargetStrength 只读邻居），自身翻转扇出的自通知评估可证冗余——评估
  刚读完输入并设置了该 POWER，期间无输入变化则重算必无变化、无变化路径零可观测
  效应；期间若有输入变化，既有闭包钩子（LevelChunk 段提交/Chebyshev-2 直通/两
  二极管漏斗/WorldGenRegion）会重新标记，保守正确。
- **updateShape 旁路（DefaultRedstoneWireEvaluator）**：`updateShape==true` 恰为
  RedStoneWireBlock.onPlace 路径（`!oldState.is(state.getBlock())` 保证 pos 刚成为
  粉），新落位粉的存储 POWER 是放置默认值而非计算值，首评估无条件执行——跳过
  分支改为 `!updateShape && evalEntry(pos)`；该分支同时清除 pos 的过期脏位
  （synchronized 条纹锁对 WorldGenRegion 并发标记建立 happens-before：清位后
  的读可见标记前的写）。
- mark 扫描保持 27 次迭代形状（中心 continue），Chebyshev-2 直通闭包不变。

### 等价性论证要点（本批无新事件派发序变化）

批 126 四缺陷面的闭包不变；本批唯一语义增量是"自通知从 dirty 变 clean"，其安全性
由上述"输入自上次评估未变"给出。affectNeighborsAfterRemoval 路径（updateShape=false，
pos 已非粉）与 neighborChanged 路径语义均与 126 相同。

## 二、基线陷阱（本批最重要的操作判例）

首轮宏 A/B（本地 `build/libs/Papo-1.21.11-0.75.0.jar` vs 0.76.0）三腿计数器**完全
相同**暴露异常。字节码级核查（javap 解嵌套 jar `META-INF/versions/1.21.11/paper-1.21.11.jar`）：
本地 0.75.0 jar 的 mark 含自排除、评估器含 updateShape 门——**上会话在发布 0.75.0
之后开了批 127 WIP 并用同版本号重建覆盖了本地 jar**，首轮"基线腿"实为 127 代码。
`gh release download` 下载已发布资产核查：GitHub 上的 0.75.0 附件为干净 126 代码
（无门、无自排除），**无需修复**。处置：内仓库 `git checkout 8ec1f485 --` + 外仓库
`git show 170c96a050:` 临时回退 + 版本号临时回改，createPapoJar 重建真 126 基线
（mark 无自排除/评估器无门，字节码验证），立即另存后全部恢复。

**判例：本地 build/libs 版本 jar 不是 release 资产的权威副本；基线腿启动前必须
字节码级验证 jar 语义。**

## 三、验证矩阵

### 计数器判决（无噪声、确定性）

宏基准 N=441 环振荡稳态（240s 窗、400-tick 计数窗，尾窗值，双腿典型）：

| 计数器（avg/tick） | 126 基线 | 127 | Δ |
|---|---|---|---|
| 评估通知总数（runs+skipped） | 30870.0 | 30870.0 | **恒等**（通知流不变） |
| rs.wireEvalRuns（实评） | 7462.9 | 4630.5 | **−2832.4（−38.0%）** |
| rs.wireEvalNoChange | 3714.4 | 882.0 | −2832.4（自通知全数转跳过） |
| rs.wireEvalSkipped | 23407.1（75.8%） | 26239.5（**85.0%**） | +2832.4 |
| rs.blockTickRuns（活动门） | 441.0 精确 | 441.0 精确 | 逐位一致 |

账目闭合：通知总数恒等、runs/noChange/skipped 三者差额一致（2832.4 = 每翻转一次的
自通知首达评估）。机制预测：−2832.4 评估 × ~850ns（JFR 口径）≈ −2400us/tick ≈ −10%。

### 宏 A/B（ABABAB 六腿交错，02:18–03:07 同时窗，N=441×240s，10 bot，vanilla）

| 腿 | 代码 | blockTicks 中位（21 振荡窗） | 均值 |
|---|---|---|---|
| A1 | 126 | 22793.4 | 22261.9 |
| B2 | 127 | 21540.3 | 23225.1 |
| A3 | 126 | 23062.4 | 23237.7 |
| B4 | 127 | 23253.4 | 27123.9（污染窗，max 40321） |
| A5 | 126 | 23131.4 | 23104.6 |
| B6 | 127 | 20756.4 | 20645.8 |
| **合并** | **126 / 127** | **23029.1 / 21606.2（63 窗）** | **−6.2%（1.066×）** |

- 相邻配对差：−5.5% / +0.8% / −10.3%（中者落在 B4 污染窗：均值-中位差 17%）。
- 六腿 presence/activity/logErrors/exit 全 PASS，活动门 441.0/tick 逐位一致。
- 环境注：A 腿自身中位极稳（±0.7%）；同 126 代码较批 126 报告窗（20192）漂移
  +14%——共享机慢窗再现（批 125 判例 +18% 同量级），故以交错配对+合并中位裁决，
  −6.2% 为保守值（机制预测 −10%）。

### 拓扑验收（TopologyVerify，10 探针）

真 126 / 0.76.0 两 jar 均 10/10 PASS（logErrors=0）。本批新增 5 探针：重新上电 15 /
**新鲜落位 14**（updateShape 旁路正向门——旁路失效则新粉停留放置默认 0）/ 拆除邻粉
回落 0 / **原位重放置 15**（onPlace 清过期位路径）/ 传播恢复 14。

### JMH 模型（WireDirtySkipBench 三腿同 JVM；epoch 槽位 0=自身位通知）

| 腿 | us/op（10 迭代） |
|---|---|
| beforeEvaluateAll（无追踪） | 214.0 ± 65.9 |
| after126SelfIncluded（3/16 dirty） | 123.9 ± 37.6 |
| **after127SelfExcluded（2/16 dirty）** | **106.2 ± 12.9（对 126 腿 1.17×）** |
| skipPathOnly（clean 簿记底价） | 27.8 ± 2.6 |

自检 ALL OK：10 万随机通知暴力对照（**自身 transition 后自身位评估必须 clean 可跳过**
——判例自 126 反转，1802 例全过；邻域含对角必 dirty）+ 4 线程并发 mark 条带锁压力。

### 构建

rebuildPatches 导出 0263（0260-0262 链不动）+ createPapoJar BUILD SUCCESSFUL；
0.76.0 产物嵌套 jar 字节码验证（门与自排除均在位）。

## 四、判例

1. **本地版本 jar 非权威**：发布后继续开发会静默覆盖 build/libs 同名产物；基线腿
   前必须 javap 级验证（见二）。
2. **计数器是比时序更强的判别器**：确定性负载下 runs/skipped 的精确差额（2832.4）
   直接证明机制生效与生效量，环境噪声只能掩盖时序不能掩盖账目。
3. 自排除的等价性重心从"闭包覆盖"移到"评估与自通知之间的输入不变性"——单线程
   派发序内 trivially 成立，跨线程面由条纹锁 happens-before 覆盖。

## 五、版本

0.75.0 → 0.76.0（gradle.properties papoVersion）。

原始数据：`benchmark/results/b127x-A1..B6.log`（交错六腿）、`benchmark/results/b127-macro-A/B/A2.log`
（首轮作废腿=127 对 127，留作漂移对照）、`benchmark/results/bench-20260906-030859.txt`（JMH）。
