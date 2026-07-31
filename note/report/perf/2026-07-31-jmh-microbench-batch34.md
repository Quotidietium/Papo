# 批次 34 优化前后 JMH 对比报告（0114–0124 + 直接提交）

- 日期：2026-07-31
- 运行器：`benchmark/run.sh`（JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op）
- 原始数据：`benchmark/results/bench-20260731-202100.{txt,json}`（0114/0115/0116）、`benchmark/results/bench-20260731-205418.{txt,json}`（门控模型/0120/0123）
- 等价性自检：各基准类 `main` 全部 `ALL OK`（逐位/逐分量/分支级比对，见类注释）
- 基准类：`UnloadedChunkCheckBench`（0114）、`PathTypePosBench`（0115）、`HopperAabbCacheBench`（0116）、`EventGateBench`（0117-0119/0121/0122/0124 统一门控模型，0100 既有类）、`PathNodeCoordBench`（0120）、`PushVectorBench`（0123）

## 结果总览

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 加速比 |
|---|---|---|---|---|
| 0114 touchingUnloadedChunk 内联 | inflateAlloc → inlineBounds | 4.464 ± 0.226 | 2.072 ± 0.136 | **2.15×** |
| 0115 WalkNodeEvaluator scratch pos | newMutablePos → scratchPos | 2.045 ± 0.121 | 1.231 ± 0.102 | **1.66×** |
| 0115 FlyNodeEvaluator 坐标比较 | blockPosEquals → coordCompare | 2.208 ± 0.077 | 0.789 ± 0.056 | **2.80×** |
| 0116 漏斗 suck AABB 缓存 | suckAlloc → suckCached | 3.606 ± 0.162 | 0.560 ± 0.043 | **6.44×** |
| 0116 漏斗 eject AABB 键控缓存 | ejectAlloc → ejectCached | 3.602 ± 0.212 | 0.729 ± 0.042 | **4.94×** |
| 门控模型（0 监听器，常态） | alwaysConstruct → gated | 8.625 ± 0.565 | 0.507 ± 0.008 | **17.0×** |
| 门控模型（2 监听器） | alwaysConstruct → gated | 8.788 ± 0.483 | 8.729 ± 0.836 | 1.01×（无劣化） |
| 0120 PathNavigation Node 直读 | blockPosEquals → coordCompare | 3.514 ± 0.283 | 1.957 ± 0.079 | **1.80×** |
| 0123 push Vector 消除 | vectorAlloc → scalarAdd | 3.168 ± 0.186 | 2.793 ± 0.137 | **1.13×** |

## 说明

- **门控模型（EventGateBench，0100 既有类）**：模拟"无条件构造事件 + 空列表派发" vs "一次烘焙数组长度读取"。零监听器（绝大多数无对应插件的服务器之常态）17.0×；2 监听器时 8.788 vs 8.729 统计持平——门控在有插件时无劣化。该模型统一适用于 0117（BlockFadeEvent×16）、0118（Sprint/Sneak/ItemHeld/Pose×4）、0119（LeavesDecay/BlockIgnite）、0121（EntityPathfindEvent）、0122（VehicleUpdate/Move）、0124（EntityTarget×3 + EndermanAttack）及直接提交的 CraftEventFactory 门控（BlockSpread/Moisture/EntityChangeBlock/BlockFade 快路）。
- **0123 PushVector 1.13×**：单次调用的 Vector 分配部分被 JIT 逃逸分析掩盖（0100 撤销教训同款现象），但实体-实体挤压路径每 tick 高频调用下仍稳定为正，且零风险（构造仅存字段、读回分量位级等价）。
- 0114/0115/0116/0120 为确定性分配消除，加速比即分配+构造开销的真实削减。

## 结论

全部 11 个补丁 + 1 个直接提交基准为正、无一回退；有监听器场景无劣化。等价性论证见 [optimizations.md](../../optimizations.md) 批次 34。
