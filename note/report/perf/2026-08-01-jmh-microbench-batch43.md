# 批次 43 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-051231.json`；分配率复核 `-prof gc`（`-wi 2 -i 3 -f 1`，输出留存于会话日志，结论见下）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**1 项正收益（0172 钓鱼开阔水域 3.72×，CI 不重叠）；0173/0174 共 4 项测量复刻内中性（EA 复刻伪影，`-prof gc` 证明两路径 gc.alloc.rate.norm 均 ≈10⁻⁵ B/op），按 0140/0157/0159/0163 先例以机制依据保留并载明**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0172 钓鱼开阔水域判定命令式重写 | FishingOpenWaterBench | 157.516 | 42.389 | **3.72×** | CI [135.29,179.74] vs [38.68,46.10] 不重叠。每判定消除流/Spliterator/方法引用 + 4 区域 × 125 不可变 BlockPos（约 500 分配/次）+ 外层 8 次 offset；INVALID 吸收早退 |
| 0173a 烟花助推分支 Vec3 内联 | FireworkVec3Bench boost | 2.886 | 2.849 | 1.01× 中性 | CI [2.79,2.98] vs [2.77,2.93] 重叠；`-prof gc`：两路径 alloc.norm 均 ≈10⁻⁵ B/op——浅复刻中 add 结果 Vec3 被标量替换，测量 op 等价（EA 伪影） |
| 0173b 烟花自由飞行分支 Vec3 内联 | FireworkVec3Bench freeFlight | 0.762 | 0.766 | 1.00× 中性 | CI [0.73,0.79] vs [0.73,0.80] 重叠；同上 EA 伪影（multiply+add 两中间量被标量替换） |
| 0174a 火球 applyInertia Vec3 内联 | FireballVec3Bench inertia | 1.540 | 1.570 | 0.98× 中性 | CI [1.46,1.62] vs [1.49,1.65] 重叠；同上 EA 伪影（normalize/scale/add/scale 四中间量被标量替换） |
| 0174b 火球 MISS 路径 setPos 逐分量 | FireballVec3Bench missSetPos | 0.927 | 0.939 | 0.99× 中性 | CI [0.90,0.95] vs [0.90,0.97] 重叠；同上 EA 伪影（position().add() 两中间量被标量替换） |

## 复测裁决记录

- 0173/0174 初测 4 项全部 CI 重叠中性。按既有裁决流程复跑 `-prof gc`：FireworkVec3Bench/FireballVec3Bench 全部 8 个测量路径（before/after × 4 方法）gc.alloc.rate.norm 均 ≈10⁻⁵ B/op——复刻浅栈内 Vec3 中间量**不逃逸**，EA 标量替换使 before/after 生成同构机器码，测量无法呈现分配消除收益。真实服务端 tick() 调用深、内联预算紧张，中间对象未必可标量替换；消除每 tick 对象图压力仍具机制价值。裁决：**机制保留**（0140 handleUseItemOn Vec3、0157 addDecoration、0159 信标 scratch、0163 潮涌核心 scratch 同先例），补丁注释与等价论证已随补丁文件留存。

## 等价性支点（源码实证）

- **0172**：①外层 8 次 `pos.offset(±2,i,±2)` 折入循环边界——BlockPos.offset 即 int 加法，`pos.getX()-2` 等逐位一致；②125 格访问顺序与 BlockPos.betweenClosedStream 行主序一致（x 外 / y 中 / z 内）；③折叠组合子 `acc==null→type；acc!=type→INVALID` 与流 reduce(身份 null、orElse INVALID) 语义一致；INVALID 吸收且序无关 ⇒ 遇异即早退保值（自检：首区域 INVALID 后区域有效仍 false）；④scratch MutableBlockPos 即设即读不逃逸（BlockCollisions.java:93 Cursor3D 传入 getCollisionShape 同先例）。六场景（开阔水域/全干/全浸/百合垫/流水/边界坐标 ±3×10⁷）自检 ALL OK。
- **0173**：`Entity.setDeltaMovement(Vec3)` 纯委托（读 .x/.y/.z 转调三参重载）；每分量 FP 链逐字相同。自由飞行分支 `multiply(d2,1.0,d2).add(0.0,0.04,0.0)` 内联保留 `+0.0` 项逐字——y 分量 `dm.y*1.0+0.04` 与 multiply→add 链逐位一致（x*1.0=x 精确保符号位；+0.0 加法除 -0.0+0.0=+0.0 外恒等，而原 add 路径同样执行该 +0.0）。矩阵自检（典型/零/-0.0/负值/极值 × look 三态，两分支共 30 组合）doubleToRawLongBits 逐位 ALL OK。
- **0174**：①normalize 阈值保留 `squareRoot < 1.0E-5F`（float 字面量，与 Vec3.normalize 逐字）；阈值路径下原链 `Vec3.ZERO.scale(power)` 每分量 = `0.0*power`，内联 `papoN=0.0 ⇒ papoN*power = 0.0*power` 逐位相同（power=0/0.05/0.1 覆盖）；非阈值路径 `(dm + (dm/|dm|)*power)*inertia` 与 normalize→scale→add→scale 链逐字；②MISS 路径：`ProjectileUtil.rotateTowardsMovement` 只改 yRot/xRot 不移动实体（源码实证），`getX/Y/Z` 与 `position()` 捕获值相等；`setPos(Vec3)` 与三参 `setPos` 同赋值。阈值边界矩阵（1e-6 / ~9.9e-6 / ~1.2e-5 / 1e-4 跨阈值两侧）+ -0.0 分量 + 极值 + MISS 路径坐标矩阵自检 ALL OK。

## 勘察说明

- ShulkerBullet 寻的 tick：目标检索为窄场景（仅潜影贝导弹且存在目标时），每 tick 收益覆盖面小，记录不实施。
- ServerLevel.tickChunk 主路径已经 Paper 优化（无流无热分配），无候选。
