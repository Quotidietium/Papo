# 批次 45 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-061636.json`；0181 修正后复测为同参数单类复跑（留存于会话日志）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**0179 正收益 1.61×（CI 不重叠）；0180 复刻内中性（CI 重叠）机制保留；0181 实测回退（0.46×，修正后 0.91×）已撤销**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0179 块内检测 AABB 折叠 | InsideBlocksAabbBench | 5.925 | 3.673 | **1.61×** | CI [5.68,6.17] vs [3.53,3.81] 不重叠。每实体每 tick 省 1 个 AABB（makeBoundingBox+deflate 两构造 → 单构造；复刻中 before 双对象链未被完全标量替换，差异真实呈现） |
| 0180 位移差 Vec3 内联 | MovementDeltaInlineBench | 2.651 | 2.786 | 0.95× 中性 | CI [2.53,2.78] vs [2.66,2.92] 重叠。subtract 结果不逃逸，复刻浅栈内被标量替换，before/after op 等价（EA 伪影同批次43/44 判例） |
| ~~0181 回退 Movement 缓存~~ **已撤销** | FallbackMovementCacheBench | 3.808 | 8.255 | **0.46× 回退** | CI 不重叠。6×Double.compare 的 NaN 规范化分支代价超过 2 次 TLAB 分配；修正为两段式精确比较（`==`+零号位校验，命中⟺逐位相同）后复测 3.699→4.044 仍 **0.91×** 回退——按批次29先例（实测回退即撤销）回退 |

## 复测与裁决记录

- **0181 撤销**：初测 0.46×（CI 不重叠）——缓存命中路径（null 检查 + 6 次 Double.compare）比两次 TLAB 分配还贵。一轮修正迭代（两段式比较，命中条件可证等价于逐位相同）复测仍 0.91×。裁决：思路在复刻与本机均无法跑赢分配路径，且为 Entity 核心类永久增加 7 字段 + 辅助方法的复杂度，真实收益（静止实体 ~2ns/tick）不成比例——**撤销**（内部提交回退、外仓补丁移除、验证链全绿）。基准类保留于 benchmark/src 作为撤销证据。
- **0180 机制保留**：复刻内中性；真实服务端 checkInsideBlocks 调用深（forEachBlockIntersectedBetween 内联预算紧张），中间对象未必可标量替换。与 0140/0157/0163/0173b/0174/0175/0176 判例一致，不确定度如实载明。

## 等价性支点（源码实证）

- **0179**：makeBoundingBox 为 `new AABB(x-f, y, z-f, x+f, y+h, z+f)`（EntityDimensions.java:19-23）；deflate(v)==inflate(-v) 即 mins `-(-v)` / maxes `+(-v)`（AABB.java:178-190,275-281）；`a-(-b)` 与 `a+b` 逐位一致（取反为符号位精确翻转）。折叠后六分量各自保持原左结合 FP 链（`(x-f)-(-1e-5)`、`(x+f)+(-1e-5)` 等），AABB 构造器 min/max 归一化接收相同六输入（含极小宽度 min>max 交换边缘：矩阵覆盖 1e-5 宽实体）。25 组合逐位 ALL OK。
- **0180**：subtract 逐分量、`lengthSqr` 为 `x*x+y*y+z*z` 左结合、`get(axis)` 为分量选择——三处读取逐字内联；switch 表达式覆盖 Axis 三常量。24 组合（含 NaN/±0.0/巨值 delta）对 lengthSqr 值与轴分量逐位 ALL OK。
- **0181（已撤销）**：两段式比较 `k==v && (k!=0.0 || rawBits相等)`——非零值 `==` 即逐位相同（无两个不同非零位型 `==` 相等）；零值走位校验保 -0.0/+0.0 区分；NaN 不命中仅重算。等价性本身成立，撤销原因为性能非正确性。

## 勘察说明

- Entity.applyEffectsFromBlocks 主路径：AtomicInteger 可变持有者不可免（BlockGetter.forEachBlockIntersectedBetween 静态接口仅回传 boolean，复制遍历循环得不偿失）；`collidedWithShapeMovingFrom` 的 shape.move/toAabbs 仅非空气块触发（稀有）。
- getOnPos/getBlockPosBelowThatAffectsMyMovement：公开 API 返回 BlockPos（调用方可能持有），缓存 scratch 不可证安全，否决。
- 流体推动路径已于 0104/0135 优化（papoFluidMutablePos 复用）。
