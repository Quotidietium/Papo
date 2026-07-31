# 批次 42 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-044026.json`。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**3 项全部正收益（6.47×/8.78×/1.20×，CI 均不重叠）；0171 与 0167 同一表达式同一变换，共用其基准数据（1.25×）**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0169a 候选循环 pickRadius inflate(0) 跳过 | ProjectileInflateSkipBench pickRadius | 35.971 | 5.558 | **6.47×** | CI [34.77,37.18] vs [5.12,5.99] 不重叠。8 候选（常规生物半径全 0，主流情形）每弹射物每 tick 8 次 inflate 全免 |
| 0169b margin inflate(0) 跳过 | ProjectileInflateSkipBench margin | 28.905 | 3.291 | **8.78×** | CI [26.05,31.76] vs [3.13,3.45] 不重叠。margin=0 场景（年轻弹射物，computeMargin 钳制下界） |
| 0170 canHitEntity 谓词字段化 | CanHitPredicateCacheBench | 1.998 | 1.671 | **1.20×** | CI [1.92,2.08] vs [1.50,1.84] 不重叠。每弹射物每 tick 省 1 次捕获 lambda 分配（复刻浅栈中 before 分配仍真实发生——谓词经 Blackhole 逃逸） |
| 0171 AbstractArrow 扫描盒 2→1 | （共用 0167 ProjectileScanAabbBench） | 6.137 | 4.902 | **1.25×** | 同一表达式 `getBoundingBox().expandTowards(getDeltaMovement()).inflate(1.0)` 同一折叠变换，差异仅调用点（findHitEntity/findHitEntities）；等价论证与逐位自检随 0167 |

## 等价性支点（源码实证）

- **0169**：inflate(0) 对实体包围盒值等价——min-0.0==min：IEEE 减法 (-0.0)-(+0.0)=-0.0，任意 x-0.0=x 精确；max+0.0==max：makeBoundingBox maxes 为 pos + 非负半宽（w≥0 → w/2=+0.0 或正数），IEEE 加法 (-0.0)+(+0.0)=+0.0 使 max 不可能为 -0.0，其余 x+0.0=x 精确。半径非零路径逐字节保留；下游 clip/contains 纯读；跳过路径返回原 bb 对象（AABB 不可变）。Entity.getPickRadius 仅 Projectile 覆盖（1.0F/0.0F），margin 经 computeMargin 钳制 ∈[0,0.3] 且恒 +0.0 或正（自检覆盖 0/0.3/1.0 × 含 -0.0 min、零宽实体矩阵）。
- **0170**：`this::canHitEntity` 指向 protected 虚方法（Projectile.canHitEntity，AbstractArrow 等覆盖），方法引用调用时虚分派——共享单例与每次新建分派语义完全一致；字段初始化在构造期捕获 this，不快照任何实例字段（自检覆盖字段变更后两侧一致）；实体检索过滤链路不比较谓词身份。与 AttributeMap createInstance 缓存 lambda（Pufferfish 移植，既有 Papo 补丁）同模式。
- **0171**：变换与 0167 逐字相同（三元式保 NaN 分支语义、-0.0 边缘被 ±1.0 抹除、左结合 FP 序、构造器归一化输入相同），deltaMovement 同为 Entity.getDeltaMovement() 现场读取。

## 勘察说明

- GoalSelector.tick 已经 Paper 位集化（无流无分配）；Brain.tick/漏斗/信标/潮涌核心/刷怪笼见对应批次。
- Vault 服务端 tick：展示物品轮换为战利品表解析负载（语义必需），状态机转换稀有，无候选。
- Entity.blockPosition() 返回字段无分配，FallingBlockEntity 每 tick 查询免费。
