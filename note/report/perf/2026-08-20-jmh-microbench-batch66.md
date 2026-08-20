# 批次 66 JMH 微基准报告（2026-08-20）— 寻路 tick 内联 + Present 记忆 raw 读（0230/0231）

批次 49 AI 域两项 + 批次 50 聚集域一项的实证落地。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 1 -r 1s -w 1s -prof gc`（裁决探针），avgt。

## 1. 0230 — PathNavigation tick 纯分量内联（+ FlyingPathNavigation + Path 访问器）

**热点**（每移动中地面 mob 每 tick）：`shouldTargetNextNodeInDirection` 昂贵分支（节点末段）物化 atBottomCenterOf×2（Node→BlockPos→Vec3）、closerThan、subtract×2、normalize×2、dot——至多 4 Vec3 + 2 BlockPos；基类/飞行 tick 的 `getNextEntityPos` 调用点各 1-2 Vec3；`getGroundY` 体内 containing+below 2 BlockPos。

**改法**：
- Node 字段直读 + 逐分量公式（FP 序/`+(-x)` 形态/normalize 的 `1.0E-5F` 守卫与 ZERO 分支/NaN 流穿逐字照抄，0173-0180 模式）；`Path.papoGetNode(int)` 加法式访问器（Path final）。
- 红线保持：`getTempMobPos`（abstract，逃逸进 lastStuckCheckPos 字段）、`canMoveDirectly(Vec3,Vec3)`/`getGroundY(Vec3)`（protected 虚方法实参物化，批次 31 判定维持）不动。
- `getGroundY` 体内 MutableBlockPos 复用 + `move(DOWN)/move(UP)`（连 below() 分配也省；体内优化不触碰虚分发）。

**基准**（PathNavBench，模型）：

| 方法 | ns/op | gc.alloc.rate.norm |
|---|---|---|
| before_vec3Chain | 5.098 ± 0.007 | ≈10⁻⁴ B/op |
| after_componentInline | 5.116 ± 0.130 | ≈10⁻⁴ B/op |

- **如实记录**：模型内两路径均被 EA 消除（中性）——按 0175/0176/0180 先例**机制保留**：严格少工作（省 2-6 次物化 + 7 个虚调用点）、逐位等价有矩阵自检（近阈值/零向量/同点/远点/NaN 全等 ALL OK）、收益在 C1/冷路径/内联预算受限深栈真实存在。
- 每移动中 mob 每 tick 稳态净省：廉价 tick 1 Vec3 + 2 BlockPos；昂贵 tick（节点末段）4 Vec3 + 4 BlockPos；飞行 mob 另 2 Vec3。残余：`WalkNodeEvaluator.getFloorLevel` 静态内部 `below()` 1 BlockPos（需新原语重载，留二期）。

## 2. 0231 — PureMemory Present 记忆 raw 读（gc 探针一票裁决通过）

**热点**：声明式行为链（OneShot 每 tick 全量评估门链）的 **Present 条件 present 读**每次分配 1 个结果 Optional——村民交易所场景 JOB_SITE/visible-entities 常 present，≈5-10 present 读/tick/村民（500 村民 ≈ 每秒数千 Optional）。

**裁决探针**（MemoryOptionalProbe，复刻 map 查找 → Optional 包装 → 3 实现轮换的条件虚分发 → 逃逸 Accessor）：

| 方法 | ns/op | gc.alloc.rate.norm |
|---|---|---|
| before_optionalChain | 6.713 ± 2.136 | **28.000 B/op** |
| after_rawPresent | 3.769 ± 0.113 | **20.000 B/op** |

**8 B/op 真分配差**（Optional 在多态分发下未被 EA 消除）——通过批次 65 判例的硬门，落地。

**改法**（BehaviorBuilder.PureMemory.tryTrigger 一处）：Present 分支特判走 0150 的 `papoGetMemoryInternalRaw`——三态塌缩恰好等价（Present.createAccessor 对 unregistered 与 absent 均返回 null；MemoryCondition 三实现均为 final record，无第三方条件）。Absent/Registered 保持原路（Absent present 读本零分配；Registered 的 Optional 是 accessor 值本体）。

**明确不做**：MemoryAccessor/IdF 系统性消除（63 个 BehaviorBuilder.create 声明点改造，越红线）。

## 3. 维持不做：getEffectiveRange ridden 缓存（批次 50 暂缓 → 正式否决）

实证升级：失效钩子可闭合（addPassenger/removePassenger 仅有的两个运行期写点 + vehicle 链向上失效 + sendChanges equals-diff 兜底），但 0147 之后**非 ridden 实体（>99% 存量）已是 O(1) 快路**，缓存零增益；真正受益的 ridden 实体全服通常个位数到几十。换来的是 mount/dismount 热路径祖先链遍历 + public 字段越合同直写残余 + 1 tick 陈旧回退风险。**中风险换边际收益，不做**（失效链测绘留档，若未来载具农场场景可启用该设计）。

## 验证链

compileJava（--no-daemon）BUILD SUCCESSFUL → 自检 ALL OK（PathNav 布尔矩阵；MemoryOptionalProbe 裁决）→ JMH + gc 探针 → rebuildPatches（0230/0231）→ applyPatches → 全量 test（见 optimizations.md 批次 66 记录）。
