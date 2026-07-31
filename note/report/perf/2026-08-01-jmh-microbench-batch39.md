# 批次 39 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-030755.json`（0159/0160/0161）、`benchmark/results/bench-20260801-031303.json`（0162，基准缺陷修复后复测）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**2 项正收益（1.57×/1.42×，CI 不重叠），2 项复刻内 EA 假象（经 `-prof gc` 证明 before 分配被标量替换），按 0140/0157 先例保留并载明机制依据**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0159 updateBase scratch pos | BeaconPosScratchBench baseScan | 17.550 | 17.235 | 持平（CI 重叠） | **EA 假象**：`-prof gc` before 路径 alloc.rate.norm ≈ 10⁻⁴ B/op——复刻浅调用栈中 164 个 BlockPos 全被标量替换。真实代码 getBlockState 深调用栈内联预算有限，分配真实发生；收益为机制性分配消除 |
| 0160 tick 扫描 pos 字段化 | BeaconPosScratchBench tickScan | 1.749 | 2.802 | 0.62×（假象） | **EA 假象（非语义回归）**：before 每调用 new MutableBlockPos 被 EA 化为寄存器（alloc.norm ≈ 10⁻⁵ B/op），after 走真实堆字段读写——复刻反转了真实条件。与 0135 updateFluidOnEyes 字段 scratch 同模式（该补丁忠实复刻实测 1.29× 正收益）；无语义差异，保留 |
| 0161 信标范围 AABB 3→1 | AabbCollapseBench | 8.792 | 5.616 | **1.57×** | CI [8.58,9.01] vs [5.43,5.80] 不重叠。每 80 tick 每信标省 2 个中间 AABB；逐位等价经矩阵自检（含负坐标/d=0/巨大 d） |
| 0162 BeaconEffectEvent 零监听器快路 | BeaconEffectGateBench | 74.794 | 52.838 | **1.42×** | CI [71.2,78.4] vs [48.5,57.2] 不重叠。每 80 tick 每激活信标（8 玩家）省共享 toBukkit+CraftBlock 与每玩家事件分配+fromBukkit 往返 |

## 复测裁决记录

- **BeaconEffectGateBench 初版基准缺陷**：`Player.received` ArrayList 跨基准调用无界增长（批次36 BroadcastLoopBench 同款缺陷），分配/GC 噪声淹没信号（初测 272.7±145.8 vs 246.1±112.0，误差棒 >40%）。改为覆盖式有界存储（last/count）后复测得 74.8→52.8（本表）。初版数据作废。
- **BeaconPosScratchBench 复刻限制**：两对方法的 before 分配在复刻浅栈中均被 EA 抹除（gc 剖析实证），复刻无法测量分配差；0159/0162 类深栈场景与 0140/0157 先例一致——可证人为假象的测量不构成真实回归，按延迟否决规则保留。

## 等价性支点（源码实证）

- **0159**：scratch.set(i3,i2,i4) 仅作 getBlockState 坐标读取，无逃逸（0135/0142 同模式）；循环边界/早退/计数逐字保留。
- **0160**：scratch 用途限 getBlockState 坐标读取与 getY/setY/set；静态 tick 主线程逐实体调用，无重入；字段为每方块实体实例。
- **0161**：`new AABB(pos).inflate(d).expandTowards(0,h,0)` 链展开为 min=(x-d,y-d,z-d)、max=(x+1+d,(y+1+d)+h,z+1+d)，浮点结合序逐位一致（int+1 先算再加宽、左结合加法链），构造器 Math.min/max 归一化输入相同；d 来源（getEffectRange/computeBeaconRange）非负有限。
- **0162**：三支点——① BeaconEffectEvent 独立 HandlerList（paper-api :85 自有 getHandlerList），零监听器时 callEvent 恒 true、getEffect 恒为构造值；② fromBukkit(toBukkit(mei)) 与 copy 构造字段相等：holder 经注册表双向映射恒等，hiddenEffect 信标实例为 null（fromBukkit 本就丢弃），amplifier 0/1 过 clamp 恒等；③ MobEffectInstance.duration tick 时就地可变（:118/139/143），共享实例不得外发——快路每玩家 copy 构造与原版每玩家 fromBukkit 新实例一一对应，addEffect 下游（EntityPotionEffectEvent）两路径一致。

## 勘察说明

- 批次 39 为信标专题（survey 旧候选清单已薅完，余者均有暂缓记录）。酿造台勘察结论：getPotionBits/lastPotionCount 已有 Papo scratch，isBrewable 为 map 查找无分配，余者为状态转换稀有路径，无候选。AttributeMap 缓存失效链复杂，未深入。
