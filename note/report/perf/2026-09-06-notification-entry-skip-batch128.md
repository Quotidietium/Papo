# 批次 128（2026-09-06）：clean 跳过前移至通知入口（0.76.0 → 0.77.0）

## 一、画像驱动选型

0.76.0 单腿 JFR 诊断（N=441×240s，settings=profile，19289 执行样本，门全 PASS）：

| 热点（jfr view hot-methods） | 占比 |
|---|---|
| BlockStateBase.handleNeighborChanged（内联归并桶：跳过通知的 canSurvive+帧+入口簿记） | **15.65%** |
| PapoWireDirtyTracking.markIfWire（标记扫描自身帧） | 9.33% |
| ZeroCollidingReferenceStateTable.get + SimpleBitStorage.get + ChainedHashTable.getNode（方块态读取链） | 12.39% |
| RedStoneWireBlock.canSurviveOn/canSurvive | 3.72% |
| papoCalculateTargetStrength（真实评估本体） | 2.43% |

结论：跳过机制本身成功后（85.0% 通知跳过），**被跳过通知仍支付 canSurvive 的支撑块
读取与两层调用帧**（handleNeighborChanged 桶的主要构成）。批次 128 把跳过点上移。

## 二、交付（0264 单补丁，无 src/main 改动）

`RedStoneWireBlock.neighborChanged` vanilla 分支入口（canSurvive 之前）：

```java
if (impl == VANILLA && !useExperimentalEvaluator(level) && level.papoWireDirty.evalEntry(pos)) {
    addCount("rs.wireEvalSkipped", 1); return;
}
```

- `DefaultRedstoneWireEvaluator` 的跳过分支移除（入口已消费位，评估器无条件运行）；
  `updateShape==true` 清过期位保留（onPlace 首评估保证不变）。
- **canSurvive 跳过的等价性**：支撑块位于 pos 下方 Chebyshev-1 闭包内——"悬空且
  clean" 不可达（支撑消失必伴随下方 transition ⇒ dirty ⇒ 走原路径重查生存并弹出）；
  通知来源均可追溯到闭包内 transition（0262 审计面：段提交钩子/直通闭包/两二极管
  漏斗/WorldGenRegion）。
- **门控**：VANILLA 实现 + 非实验评估器——AC/EIGENCRAFT/experimental 三腿与上游
  逐字节一致（EIGENCRAFT 共享 neighborChanged→updateSurroundingRedstone 路径，故
  门必须在跳过点而非评估器内）。
- **移除路径行为恢复**：affectNeighborsAfterRemoval（updateShape=false，pos 已为
  空气）的评估自 126 起被跳过，现恢复运行——稳态无操作（stored==target 且
  getBlockState(pos)!=state 挡住 setBlock/扇出）；非稳态时恢复 vanilla/CraftBukkit
  的过期电量移除事件（**向 vanilla 收敛的行为变化**，非偏离）。

## 三、验证矩阵

### 产物与拓扑

- 0.77.0 嵌套 jar 字节码验证：neighborChanged 内 evalEntry（bci 79）→ skipped 计数
  → canSurvive（bci 96），顺序正确；评估器 0 处 wireEvalSkipped。
- TopologyVerify 10/10 PASS（T3b 新鲜落位 14 = updateShape 旁路；T4a 拆除回落 0 /
  T4b 原位重放置 15 / T4c 传播恢复 14 = 移除路径新行为直击）。

### 宏 A/B（ABAB 四腿交错 03:29–04:01，N=441×240s，10 bot，vanilla）

| 腿 | 代码 | blockTicks 中位（21 振荡窗） | 均值 |
|---|---|---|---|
| A1 | 0.76.0 | 22990.9 | 25882.4 |
| B1 | 0.77.0 | 21720.6 | 24388.9 |
| A2 | 0.76.0 | 19772.4 | 19952.9 |
| B2 | 0.77.0 | 18909.2 | 18935.8 |
| **合并** | **0.76.0 / 0.77.0** | **21197.9 / 19701.9（42 窗）** | **−7.1%（1.076×）** |

- 相邻配对差：−5.5% / −4.4%（两对一致为负；环境小时内整体改善 14%，交错设计消解）。
- 四腿 presence/activity/logErrors/exit 全 PASS，活动门 441.0/tick 逐位一致。
- JFR 预估（canSurvive 3.72% + 帧开销 ≈ blockTicks 的 8~12%）与实测 −7.1% 相符。

### 计数器等价

实测与 127 **精确恒等**：skipped 26239.5 / runs 4630.5 / noChange 882.0 / 通知 30870
恒等 / 活动门 441.0 精确——跳过种群与闭包完全不变，本批为纯跳过路径成本削减
（canSurvive 支撑块读取 + 两层调用帧 × 26239 跳过/tick）。JMH 模型不适用
（无算法/数据结构变化，宏基准直接裁决）。

## 四、版本

0.76.0 → 0.77.0（gradle.properties papoVersion）。

## 五、原始数据

`benchmark/results/b128-macro-A1..B2.log`（ABAB 四腿）、`benchmark/results/b128-jfr-leg.log`
（JFR 诊断腿，录制 F:/TEMP/b128-jfr.jfr 会话内留存）。
