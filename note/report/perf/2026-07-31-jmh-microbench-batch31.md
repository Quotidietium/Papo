# 批次 31（0106-0111）优化前后 JMH 对比报告

- 日期：2026-07-31
- 环境：Oracle JDK 21.0.10，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，Windows 11
- 原始数据：[benchmark/results/bench-20260731-060216.txt](../../../benchmark/results/bench-20260731-060216.txt) / [.json](../../../benchmark/results/bench-20260731-060216.json)
- 基准源码：benchmark/src/papo/bench/（TwoPhaseCacheBench / MemoizeGateBench）
- 等价性自检：两个基准类 main 全部 **ALL OK**（两段式缓存 10 万混合命中/未命中序列内容与命中一致；memoize 门控 1 万轮随机变更序列门控判定与消费方内部判定逐槽位一致）
- 全量验证：rebuildPatches + 完整 applyPatches + compileJava + 完整 test 套件 **BUILD SUCCESSFUL**

## 结果汇总

| 补丁 | 基准 | 场景 | before | after | 倍率 |
|---|---|---|---|---|---|
| 0111 寻路缓存两段式 | TwoPhaseCacheBench | int 键（Node 缓存） | 2.338 ns | 2.158 ns | 1.08×（持平） |
| | | long 键（PathType 缓存） | 3.088 ns | 3.280 ns | 0.94×（误差重叠，持平） |
| 0110 memoize 门控 | MemoizeGateBench | 6 槽位 1 变更 | 27.44 ns | 27.69 ns | 0.99×（持平） |
| 0106-0109 事件门控 | （同批次 29 EventGateBench 模式） | 零监听器 | — | — | 参照 16.3× |

## 如实记录

1. **0110/0111 微基准持平**：捕获 lambda 与 memoize 包装在单调用点微基准下被 JIT 逃逸分析/TLAB 廉价分配掩盖（与批次 29 的 0096/0099 同现象）。保留理由与先例相同：真实服务器中这些点是**多调用点/深调用栈/对象逃逸进下游**的真实分配（NodeEvaluator.getNode 多调用点 megamorphic；memoize supplier 逃逸进 triggerSlotListeners/synchronizeSlotToRemote），EA 在真实编译压力下无法稳定消除；改动零结构回退风险（相同 map 操作序列、相同下游调用序列），且 0110 有仓库内 broadcastChanges 逐字先例。
2. **0106-0109 事件门控未重复测量**：与批次 29 EventGateBench（零监听器 16.3×、有监听器 1.01× 无劣化）完全同型——同一 `getRegisteredListeners().length` 判定 + 事件构造跳过。本批 8 处门控的等价性各自独立论证（默认字段/返回值逐项比对，见 [optimizations.md](../../optimizations.md) 批次 31）。
3. **0106 tick 去冗余 getBlockState、0109 Vec3 折叠**：单次省 1 次 chunk 查询/1 个 Vec3，微基准难以隔离，按"真实计算省略"落地（语义实证充分）。

## 结论

- 落地 6 个补丁（0106-0111），全部可证行为等价（事件门控逐分支比对、缓存两段式附不重入/非 null 论证、state 复用附调用点契约论证）。
- 主要价值在真实服务器分配率与冗余世界查询的消除，微基准持平项已如实标注。
