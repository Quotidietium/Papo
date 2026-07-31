# 批次 44 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-054717.json`；分配率复核 `-prof gc`（`-wi 2 -i 3 -f 1`，留存于会话日志）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**1 项正收益（0177 格挡视角 1.09×，CI 不重叠）；0175/0176 复刻内中性，`-prof gc` 显示两路径分配量逐字节相同（中间量被 EA 标量替换），按先例机制保留并如实载明不确定度**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0175 getInputVector 内联 | InputVectorInlineBench | 17.894 | 18.121 | 0.99× 中性 | CI [17.02,18.77] vs [17.21,19.03] 重叠；`-prof gc`：两路径 alloc.norm **均 40.000 B/op**——恰为结果 Vec3 一个（d=1.0 输入走 scale 分支，中间量被 EA 抹除），before/after 分配逐字节相同 |
| 0176 knockback 内联 | KnockbackVec3Bench | 6.484 | 6.508 | 1.00× 中性 | CI [6.25,6.71] vs [6.25,6.77] 重叠；`-prof gc`：两路径 alloc.norm **均 104.000 B/op**——恰为 finalVelocity(40)+diff(40)+Vec3[2] 数组(24)，三中间量被 EA 抹除 |
| 0177 格挡视角向量内联×2 | BlockViewAngleBench | 12.516 | 11.464 | **1.09×** | CI [12.23,12.81] vs [11.16,11.76] **不重叠**。每次格挡消除 position/subtract/水平化/normalize 四中间 Vec3（复刻中 before 链更深，C2 未能全部标量替换，差异得以呈现） |

## 复测裁决记录

- 0175/0176 初测 CI 重叠中性；复跑 `-prof gc`：before/after alloc.norm 逐字节相同（40B/104B，仅剩逃逸至 Blackhole 的结果对象），复刻内 C2 已将 before 中间量标量替换，测量 op 等价。
- 如实载明：真实服务端 C2 对热路径大概率同样抹除这些非逃逸中间量，故 0175/0176 在 C2 热路径上的实测收益预期同样趋零；保留依据为——①语义逐位等价、零行为风险（自检矩阵覆盖阈值/-0.0/Inf/次正规）；②降低 C1/冷路径与观测 JVM（attach profiler 等 EA 受限场景）的分配压力；③与 0140/0157/0159/0163/0173/0174 先例一致。**不确定度已载明，不夸大收益。**
- 0177 复刻内差异得以呈现（before 四对象链超出 C2 标量替换能力），1.09× 为正收益下界；真实服务端每次格挡（持盾受击）省 4 分配。

## 等价性支点（源码实证）

- **0175**：`lengthSqr()` 与 `normalize()` 求和表达式逐字相同（Vec3.java:83/184）⇒ `Math.sqrt(d)` 与 normalize 内部 squareRoot 逐位一致；`d > 1.0 ⇒ len > 1.0 > 1.0E-5F`，normalize 阈值分支不可达；`(x/len)*scaler` 与 normalize().scale() 链逐字（左结合）；末行 `x*cos - z*sin` 等逐字保留；`d < 1.0E-7 → Vec3.ZERO` 分支原样。自检：d<1e-7/贴阈值/d=1.0 恰好/d>1/-0.0/巨值/d 溢出 Inf/次正规 × scaler×facing 共 220 组合逐位 ALL OK。
- **0176**：while 循环（RNG 序列不动）保证 `!(x²+z² < 1.0E-5F)` ⇒ `len ≥ √(1e-5) ≈ 3.16e-3 > 1.0E-5F`，normalize 阈值分支不可达；normalize 求和 `x²+0.0*0.0+z² == x²+z²`（平方非负，+0.0 恒等）；`vec3.y` 从不被读（finalVelocity.y 用 Math.min/deltaMovement.y，Paper 事件 diff 用 finalVelocity），仅内联 x/z 两分量。自检：贴阈值/单轴/-0.0/双 1e308 溢出 Inf/整数勾股 × strength×onGround 对 finalVelocity+diff 全分量逐位 ALL OK。
- **0177**：①求和恒等同上；②normalize 阈值分支**保留**（同位置/微距格挡可达）；③被丢弃的 y 项 `0.0*view.y = ±0.0`：加 ±0.0 对任意非零和恒等，且 `t1+(-0.0)==t1` 逐位恒成立；唯一分歧情形为 `t1=-0.0 ∧ t3=-0.0 ∧ t2=+0.0`（和由 +0.0 变 -0.0），被 `Math.acos` 抹除（acos(±0.0)=π/2 逐位相同）⇒ 可观测值逐位一致。自检：同位置/贴阈值两侧/-0.0 坐标/巨值 Inf/构造全零点积 × view 五朝向共 200 组合 acos 输出逐位 ALL OK。
- **0178**（注释勘正，零行为变更）：批次 43 注释误述 `setDeltaMovement(Vec3)` 为"纯委托"——实为 isFinite 守卫赋值（含 Paper posLock 看门狗同步块），三参重载经 `new Vec3` 流入同一 Vec3 重载；等价性不受影响（相同分量经相同守卫路径），已就正注释并修正中间量计数表述。

## 勘误（批次 43 记录修正）

- **0173a（烟花助推分支）分配计数**：before `setDeltaMovement(dm.add(...))` 分配 1 个 Vec3（add 结果即被赋值对象），after 三参重载分配 1 个——**净消除 0 次分配**，该分支实为装饰性简化（字节码略简），批次 43 报告"机制保留"论证对 0173a 不成立（0173b 2→1、0174a 4→1、0174b 2→0 仍然成立）。0173a 语义逐位等价、无回退必要，如实更正计数。
- **"纯委托"表述**：批次 43 报告/发布说明中"setDeltaMovement(Vec3) 纯委托"系误述（实际方向：三参 → new Vec3 → Vec3 重载做 isFinite 守卫赋值）；等价论证不依赖该误述（两侧均经同一守卫），已由 0178 就正源码注释。

## 勘察说明

- LivingEntity.aiStep:3717 `new Vec3(xxa,yya,zza)`：传入可覆写 `travel(Vec3)`/`travelRidden(Player,Vec3)`，改签名即破 API，红线否决。
- Sensing.tick 已极简（双 IntSet 清理）；振动系统（Sculk）为窄场景；Explosion 无热分配候选（已查 new Vec3/BlockPos/inflate/getEntities 模式）。
- ItemEntity 合并扫描半径早退批次 42 已否决（改变行为）；ExperienceOrb 已带既有优化注释。
