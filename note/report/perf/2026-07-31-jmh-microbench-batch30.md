# 批次 30（0104-0105）优化前后 JMH 对比报告

- 日期：2026-07-31
- 环境：Oracle JDK 21.0.10，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，Windows 11
- 原始数据：[benchmark/results/bench-20260731-052624.txt](../../../benchmark/results/bench-20260731-052624.txt) / [.json](../../../benchmark/results/bench-20260731-052624.json)
- 基准源码：benchmark/src/papo/bench/（FluidPathBench / ComputeSpeedBench）
- 等价性自检：两个基准类 main 全部 **ALL OK**（0104：10 万随机 box 边界逐位比对 + 索引布局逐点/单 chunk 恒 0 实证；0105：静止/移动/往返/大坐标 4 组序列速度分量逐位比对 + hasMovedHorizontallyRecently 公式比对）
- 全量验证：rebuildPatches（幂等）+ 完整 applyPatches + compileJava + 完整 test 套件 **BUILD SUCCESSFUL**

## 结果汇总

| 补丁 | 基准 | 场景 | before | after | 倍率 |
|---|---|---|---|---|---|
| 0104 流体检测路径 | FluidPathBench | 序言（边界计算+scratch pos+sections 解析） | 15.106 ns | 4.443 ns | **3.40×** |
| 0105 computeSpeed | ComputeSpeedBench | 每 tick 速度计算 | 13.444 ns | 2.034 ns | **6.61×** |

## 说明

1. **基准复刻的是每调用/每 tick 的分配与算术序言**（不含世界访问）。before 路径每 op 分配 3 个对象（AABB + MutableBlockPos + Object[][] 包装数组）/ 1 个 Vec3，与真实服务器代码路径 1:1 对应。
2. **分配未被逃逸分析掩盖**：before 分配的对象经构造器字段存储并被 Blackhole 消费（包装数组为可变尺寸，EA 无法标量替换），13-15ns 的 before 成本即真实分配+写入成本；after 路径的 2-4ns 为纯算术。真实服务器中这些分配发生在每实体每 tick（流体 ×2、computeSpeed ×1），实体数规模下直接降低新生代分配率。
3. **等价性论证链**（详见 [optimizations.md](../../optimizations.md) 批次 30）：
   - 0104 边界内联：`AABB.deflate(v)` = `inflate(-v)` = `minX - -v / maxX + -v`，内联式逐运算相同（基准 main 逐位实证）；
   - 0104 MutableBlockPos 复用：Paper 运行时 `Fluid` 实现封闭于 5 个 vanilla 实例（注册表冻结 + `FluidState` final + 无扩展点），`FlowingFluid/EmptyFluid` 的 getHeight/getFlow 只读坐标不存引用，`lastLavaContact` 存 `immutable()` 新拷贝；
   - 0104 单 chunk 快速路径：单 chunk 时索引表达式恒 0（main 实证），多 chunk 走原公式；
   - 0105：`a - b` ≡ `a + (-b)`（IEEE 754 取负精确）与 `Vec3.subtract` 位级一致；Vec3 不可变故存坐标 ≡ 存引用；`position()` 无子类覆盖；首 tick 零速度与 `reapplyPosition` 失效语义逐分支一致。

## 结论

- 落地 2 个补丁（0104-0105），覆盖批次 29 survey 遗留的两个中风险候选，全部可证行为等价。
- 微基准收益：0104 **3.40×**、0105 **6.61×**；真实服务器收益主要为实体数 × tick 规模的新生代分配率下降。
