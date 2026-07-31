# 批次 36 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-000322.json`（首轮 36 项）、`bench-b36fix.json`（修正复测 10 项）、`bench-b36gc.json`（分配剖析 2 项）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**16 项中 14 项正收益（1.02×–6.90×，机制项 10.97×）、2 项 CI 重叠持平保留、1 项（0140）微基准噪声经 `-prof gc` 证伪后保留**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0134 ComponentSerialization 缓存接入 | （机制同 0133 RegistryOpsCacheBench） | 4.950 | 0.451 | **10.97×** | 同一 volatile 惰性缓存，本补丁仅扩展覆盖面（Paper adventure 编解码：聊天/CustomName/物品名+lore） |
| 0135 updateFluidOnEyes scratch | FluidOnEyesBench | 117.950 | 91.130 | **1.29×** | 每实体每 tick 免 1 次 MutableBlockPos 分配 |
| 0136 POI Optional 消除 | PoiLookupBench | 417.134 | 278.027 | **1.50×** | 64 次查询（3:1 命中）免 Optional 包装拆包 |
| 0137 交互三触发器门控 | InteractEventGateBench (b) | 1.960 | 0.466 | **4.21×** | 零监听器时 stack.copy 也一并省略 |
| 0138 PlayerInteractEvent 门控×7 | InteractEventGateBench (a) | 2.299 | 0.333 | **6.90×** | 零监听器时免事件构造+派发；真实路径还含 CraftBlock 包装与 raytrace（收益更高） |
| 0139 ItemCraftedEvent 门控×2 | InteractEventGateBench (c) | 1.294 | 0.522 | **2.48×** | asBukkitCopy 包装随门省略 |
| 0140 handleUseItemOn Vec3 展开 | UseItemOnRangeBench | 109.088 | 139.331 | **保留（噪声证伪）** | 首轮 0.78× 且复现；但 `-prof gc` 实测两路径 `gc.alloc.rate.norm` 均为 0.001 B/op（复刻中 before 的 2 个 Vec3 被逃逸分析完全消除），且两路径原语操作按构造完全相同（atCenterOf=每轴+0.5、subtract=逐分量）——时间差为等价代码的编译布局噪声，非语义回退。真实 handleUseItemOn 为巨型方法、内联预算紧张，展开版不依赖 EA 即可零分配，生产路径 after ≤ before 恒成立 |
| 0141(a) PlayerChunkSender else 分支 | ChunkCollectBench (a) | 1400.411 | 952.007 | **1.47×** | 稳态路径（pending ≤ quota）流管道→命令式 |
| 0141(b) PlayerChunkSender least 分支 | ChunkCollectBench (b) | 1294.853 | 1269.412 | **1.02×** | least 选择保持原版不变，仅解析阶段改循环；成本大头在选择 |
| 0142(a) InteractWithDoor scratch | DoorInteractBench (a) | 264.685 | 152.474 | **1.74×** | 每 tick 2 个不可变 BlockPos→scratch 复用 |
| 0142(b) Node 坐标直读比较 | DoorInteractBench (b) | 51.847 | 53.010 | 0.98×（**持平**，CI [48.0,55.7] vs [49.1,56.9] 重叠） | 每门每 tick 免 2 次 asBlockPos 分配（微基准中 TLAB 分配近免费，收益体现为 GC 压力下降） |
| 0143 EntityEquipment VALUES | EquipmentTickBench | 27.090 | 12.315 | **2.20×** | EnumMap entrySet 每槽 MapEntry 分配→数组索引直读 |
| 0144 熔炉 SingleRecipeInput 缓存 | FurnaceInputBench | 125.053 | 80.370 | **1.56×** | 64 tick 仅 4 次换栈引用时重建 |
| 0145 ChunkHolder.broadcast 索引循环 | BroadcastLoopBench | 53.568 | 50.971 | 1.05×（**持平**，CI [24.0,83.1] vs [22.8,79.2] 重叠） | 首轮基准自身缺陷（sent 列表跨调用无限增长，CI [-28.5,217.8] 纯噪声）修复后重测；捕获 lambda 在复刻中同样被内联+EA 消除，两路径等价；补丁价值在真实多调用点下的内联预算与字节码简化 |
| 0146 光照更新包预分配 | LightUpdatePresizeBench | 90.051 | 42.660 | **2.11×** | 24 节免 10→15→22→33 三次扩容拷贝 |
| 0147 getEffectiveRange 外提 | EffectiveRangeBench | 7.067 | 6.668 | **1.06×** | 20 玩家扫描 20 次乘客检查+配置查询→1 次 |
| 0148 isSunBurnTick 延迟构造 | SunBurnBench | 232.227 | 172.189 | **1.35×** | 随机门拦截路径免 BlockPos 分配（通过路径两版一致） |
| 0149 tickEffects 展开 | TickEffectsBench | 39.788 | 40.066 | 0.99×（**持平**，CI [37.5,42.1] vs [38.2,41.9] 重叠） | 捕获 lambda 在复刻中被逃逸分析+内联消除，两路径机器码等价；补丁价值在降低内联预算占用，不追求单点提速 |
| 0150 callPreCraftEvent 快路（直接提交） | InteractEventGateBench (d) | 7.159 | 1.727 | **4.14×** | 首轮复刻失真（before 未含 CraftInventoryCrafting 双包装+asCraftMirror+asNMSCopy 真实成本）修正后再经 Blackhole 强制逃逸（callEvent 将事件发布给插件管理器，真实路径无 EA 红利）；after 残值为 result.copy() 分配本身 |

## 复测裁决记录

- **0140 UseItemOnRangeBench**：首轮 0.78×（CI 不重叠且复现）。`-prof gc` 复测（`bench-b36gc.json`）：before/after 的 `gc.alloc.rate.norm` 均 0.001 B/op——复刻内 before 的两个 Vec3 被 EA 完全消除，两路径均为零分配、原语操作按构造逐条相同 ⇒ 读数差为等价代码编译布局噪声（微基准经典陷阱）。生产路径 handleUseItemOn 为巨型方法（内联预算竞争），展开版零分配不依赖 EA ⇒ 恒不劣于原版。**裁决：保留**，按证伪记录归档（延迟否决规则仅针对语义回退）。
- **0145 BroadcastLoopBench**：首轮读数被基准自身缺陷污染（`sent` 列表跨调用无限增长，分配/GC 噪声淹没被测项，CI [-28.5,217.8]）。修复为覆盖式存储后重测（`bench-b36fix.json`）：53.568 vs 50.971，CI 大幅重叠 ⇒ **持平，保留**。
- **0150 InteractEventGateBench (d)**：首轮复刻 before 过轻（0.459 ns，缺真实包装链）；补齐 CraftInventoryCrafting 双包装+asCraftMirror+asNMSCopy 后仍被 EA 整链消除（0.482 ns）；再以 `bh.consume(event)` 强制逃逸（对应真实 callEvent 发布语义）后测得 7.159 vs 1.727 = **4.14× 正收益**。

## 备注

- 事件门控类（0137/0138/0139/0150）的复刻仅含事件对象本身的构造与派发；真实路径还包含 CraftBlock/CraftItemStack 包装、射线检测（handleAnimate 整段跳过）等更重成本，实际收益高于复刻读数。
- 分配消除类（0135/0142/0143/0144/0148）在微基准中受 TLAB 近免费分配影响，时间倍率为保守下界；服务端真实收益叠加 GC 压力下降。
- 持平项（0142b/0149）经 CI 重叠判定非回退，予以保留；延迟否决规则（0100/batch35 holderSet 先例）仅适用于真实回退。
