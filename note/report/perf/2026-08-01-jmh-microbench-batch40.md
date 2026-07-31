# 批次 40 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-034054.json`（0164/0165 + 0163 初版缺陷数据）、`benchmark/results/bench-20260801-034453.json`（0163 基准修正后复测）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**3 项正收益（1.61×/1.57×/54.6×，CI 均不重叠），1 项复刻内中性（经 `-prof gc` 证明 before 分配被 EA 标量替换），按 0140/0157/0159 先例保留并载明机制依据**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0163 潮涌核心 updateShape scratch pos | ConduitShapeScanBench | 762.268 | 815.532 | 持平（CI 重叠） | **EA 假象**：`-prof gc` 两路径 alloc.rate.norm 同为 2016.005 B/op——before 路径 loop1 的 27 次 BlockPos 分配在复刻浅栈中全被标量替换，两路径仅剩 42 个逃逸入表实例（完整框架场景两路径本就同数）。真实代码 isWaterAt/getBlockState 深调用栈内联预算有限，27 次分配真实发生；收益为机制性分配消除（0159 同款情形） |
| 0164a applyEffects 效果范围 AABB 3→1 | ConduitAabbBench applyEffects | 9.451 | 5.866 | **1.61×** | CI [8.86,10.04] vs [5.49,6.24] 不重叠。每 40 tick 每激活潮涌核心省 2 个中间 AABB；int 链逐位等价经矩阵自检 |
| 0164b getDestroyRangeAABB 2→1 | ConduitAabbBench destroyRange | 7.699 | 4.907 | **1.57×** | CI [7.22,8.18] vs [4.66,5.16] 不重叠。猎杀目标选择路径省 1 个中间 AABB |
| 0165 PreSpawnerSpawnEvent 零监听器快路 | SpawnerPreEventGateBench | 23.380 | 0.428 | **54.6×** | CI [21.95,24.81] vs [0.41,0.44] 不重叠。每次刷怪尝试每实体（spawnCount=4）省 2 个 Location + 事件对象 + 空派发；after 仅剩监听器数检查 |

## 复测裁决记录

- **ConduitShapeScanBench 初版基准缺陷（初版数据作废）**：Level 复刻的 `getBlockStateId` 用 ringIndex 全枚举应答（每次查询 O(125) 循环），扫描成本被查询成本淹没（初测 3676.0→3604.5 ns/op，分配差异不可见）。修正为 setup 预计算框架位置 HashSet 的 O(1) 查询（与真实 getBlockState 调色板索引同量级）后复测得 762.3→815.5（本表），分配差异转由 gc 剖析裁决。
- **ConduitShapeScanBench 复刻限制**：修正后 before/after 真实堆分配同为 2016.005 B/op（42 个逃逸入表 BlockPos × 24B + 42 次 HashSet<Long> contains 自动装箱 × 24B，两路径同构）——loop1 的 27 次 before 分配被 EA 完全抹除（alloc.norm 差 ≈ 0.001 B/op）。复刻无法测量该分配差；与 0159 updateBase 情形一致——可证人为假象的测量不构成真实回归，按延迟否决规则保留。

## 等价性支点（源码实证）

- **0163**：scratch.set(cx+i, cy+i1, cz+i2) 与 pos.offset(i,i1,i2) 坐标逐位一致；scratch 仅作 isWaterAt/getBlockState 坐标读取，不逃逸（0159/0160 同模式）；`new BlockPos(papoScratch)` 经 Vec3i 构造器坐标拷贝，仅命中框架方块时入表——原版同位置同为每位置新实例，且 BlockState.is(block) 至多命中 4 个 VALID_BLOCKS 之一（`getBlock() == block` 恒等语义），无双入表分歧；effectBlocks 表清空重建语义不变，无跨调用身份依赖（消费方 updateHunting/getRange 仅读 size）。
- **0164**：两处折叠项 x/y/z/i/h 全为 int（i = size/7*16 ≥ 0，h = level.getHeight() > 0）。原链每步为精确整数 double 运算（int 拓宽 → 加减 → 再拓宽，各中间值 ≪ 2^53），折叠式 int 求和后一次拓宽，doubleToRawLongBits 逐位相等；构造器 Math.min/max 归一化输入相同（min < max 恒成立，归一化为恒等）。
- **0165**：PreSpawnerSpawnEvent 无独立 getHandlerList，共享 PreCreatureSpawnEvent 静态 HANDLER_LIST（paper-api PreCreatureSpawnEvent.java:25,97-104），快路检查与事件派发同一张表；零监听器时 SimplePluginManager.callEvent 空派发（同步事件主线程调用无 async 检查副作用），cancelled/shouldAbortSpawn 恒为默认 false → callEvent() 恒 true → 原事件块整体为无副作用 no-op（两个 CraftLocation.toBukkit 与 minecraftToBukkit 均为纯分配/注册表查询），跳过等价；flag 仅在 !callEvent 分支置位，快路不触及。有监听器路径逐字节保留。

## 勘察说明

- **AttributeMap 评估结案（批次 39 留项）**：实例级已有 dirty/cachedValue 惰性缓存（AttributeInstance.java:26-27,138-145），getValue() 重算后 O(1)；AttributeMap.getValue 残余成本仅一次 Object2ObjectOpenHashMap 查找。再加一层 map 级值缓存仅省该查找（~10-20ns），而失效需覆盖 Paper 的 registerAttribute 直写（绕过 onDirty 回调，陈旧值风险）——价值不足且失效链不可闭合，**不做，结案**。
- 刷怪笼热路径（isNearPlayer + spawnDelay 递减）已无分配；事件段为 0165 所覆盖。
- 战利品表：getRandomItems 每掉落事件构造 LootContext 为语义必需负载，无可证等价快路，本批次无候选。
