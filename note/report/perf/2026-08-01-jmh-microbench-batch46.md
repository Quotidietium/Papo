# 批次 46 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-141758.json`。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**8 项正收益（1.17×–18.13×，CI 均不重叠）；2 项复刻内中性按先例机制保留；1 项（记分板 Optional）复核为零收益纯搅动，已从补丁撤除**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0181 GameEventDispatcher BlockPos+debug 门控 | GameEventPostBench | 8.716 | 3.839 | **2.27×** | CI [8.10,9.33] vs [3.66,4.02] 不重叠。每次游戏事件派发省 1 BlockPos + 无调试订阅者时省 BlockPos+DebugGameEventInfo |
| 0182 FurnaceBurnEvent 门控 | FurnaceEventGateBench.burnEvent | 7.200 | 1.206 | **5.97×** | CI 不重叠。每次燃料消耗省镜像+CraftBlock+事件+空派发 |
| 0182 FurnaceStartSmeltEvent 门控 | FurnaceEventGateBench.startSmelt | 10.445 | 0.576 | **18.13×** | CI 不重叠。每烧炼周期省镜像+toBukkitRecipe+CraftBlock+事件 |
| 0182 FurnaceSmeltEvent 门控 | FurnaceEventGateBench.smelt | 18.887 | 5.817 | **3.25×** | CI 不重叠。每烧出物品省 asBukkitCopy/asNMSCopy 往返+事件链 |
| 0183 营火 BlockCookEvent 门控 | CampfireCookGateBench | 42.579 | 24.648 | **1.73×** | CI [39.99,45.17] vs [23.16,26.13] 不重叠。含 split 掉落循环；门控单次 copy vs 往返两次 copy |
| 0184 VaultDisplayItemEvent 门控 | MiscEventGateBench.vault | 8.729 | 0.575 | **15.18×** | CI 不重叠。事件路径完全消除 |
| 0185 PlayerArmSwingEvent 门控 | MiscEventGateBench.swing | 4.326 | 0.684 | **6.32×** | CI 不重叠。挥臂包每包省事件构造+空派发 |
| 0186 withItemStack 直路 | ObfuscationSessionBench.withItemStack | 5.514 | 5.639 | 0.98× 中性 | CI [5.13,5.90] vs [5.37,5.91] 重叠。复刻热循环内捕获 lambda 被 EA 消除（同 0100 判例）；真实编码深栈及**默认非混淆配置**下 before 仍在调用点分配 lambda（invokedynamic 捕获在早退前发生），机制保留（0140 先例），不确定度如实载明 |
| 直提 start 上下文缓存 | ObfuscationSessionBench.start | 4.872 | 3.770 | **1.29×** | CI [4.61,5.14] vs [3.57,3.97] 不重叠。每实体数据/装备包每连接省 1 上下文分配 |
| 0187 属性快照预尺寸 | PacketConstructionBench.attributes | 85.889 | 35.461 | **2.42×** | CI [78.6,93.1] vs [26.5,44.4] 不重叠（after CI 偏宽但与 before 不重叠）。14 属性同步免三次扩容拷贝 |
| ~~0187 记分板 Optional.empty~~ **已撤除** | PacketConstructionBench.scoreboard | 1.791 | 1.728 | 1.04× 中性 | CI 重叠。复核发现 `Optional.ofNullable(null)` 本就返回 empty() 单例、**两写法均零分配**——非 EA 伪影，是真零收益纯代码搅动，按"实测无收益即撤"精神从补丁撤除（补丁手工编辑 + 完整 applyPatches 同步，报告留档） |
| 0188 气泡柱 scratch pos | BubbleColumnPosBench | 2.990 | 2.566 | **1.17×** | CI [2.80,3.18] vs [2.43,2.70] 不重叠。水下实体每 tick 省 1 BlockPos |
| 0189 EntityEffectTickEvent 门控 | MiscEventGateBench.effectTick | 0.537 | 0.532 | 1.01× 中性 | CI 重叠。复刻浅栈内事件对象被 EA 标量替换；真实路径 `callEvent()` 发布语义 + `CraftPotionEffectType.minecraftHolderToBukkit` 注册表转换成本未被复刻覆盖，机制保留（0140/0157 先例），不确定度如实载明 |

## 裁决记录

- **0187 记分板 Optional 撤除**：survey 称"每分数变更 2 个 Optional 分配"，复核 `Optional.ofNullable` 实现（null 返回 empty() 单例）证伪——null 分支两写法均不分配，非 null 分支两写法均分配一次。改动为纯代码搅动，无 EA 伪影辩护空间。处理：手工编辑 0187 补丁移除 ServerScoreboard hunk（build.md 合法流程）→ 完整 applyPatches 同步源码树 → 校验全绿。教训已记入 build.md：survey 候选的"分配"断言需对照 JDK 实现复核，不能凭方法名望文生义。
- **0186/0189 机制保留**：复刻内中性均为 EA 伪影判例（0140/0157/0173b/0174/0175/0176 同一论证链）：0186 的 lambda 分配在真实默认配置（非混淆）调用点必然发生；0189 的事件发布语义与注册表转换超出复刻覆盖。不确定度如实载明。

## 等价性支点（源码实证）

- **0181**：`BlockPos.containing(pos).getX()` 定义为 `Mth.floor(pos.x)`（BlockPos.java:97-99）；`broadcastEventToTracking` 首行即 `hasAnySubscriberFor`（LevelDebugSynchronizers.java:205-209，public）。
- **0182**：FurnaceBurnEvent 自有表无子类、构造原样存 burnTime（钳制仅在 setBurnTime）、burning/consumeFuel 默认 true；FurnaceStartSmeltEvent 无自有表（仅 import），监听器入 `InventoryBlockStartEvent` 表（与 BrewingStartEvent/CampfireStartEvent 共享）；FurnaceSmeltEvent 无自有表、为 BlockCookEvent 唯一子类（paper-api 全库 grep）。round-trip 引理：`asBukkitCopy(x)=asCraftMirror(x.copy())`（CraftItemStack.java:133-138）、`asNMSCopy(craft)=craft.handle.copy()`（:106-113）、`isSimilar==ItemStack.isSameItemSameComponents`（:439-454，双 handle 非空）。
- **0183**：门控单次 copy 保证 split 循环不就地改槽（无配方时 itemStack1 别名槽位栈）；`EMPTY.copy()==EMPTY`。
- **0184**：自有表无子类、Cancellable 默认 false；roll 产出栈独占。
- **0185**：PlayerArmSwingEvent 无自有表、为 PlayerAnimationEvent 唯一子类（paper-api grep），`isCancelled` 默认 false。
- **0186/直提**：`context.itemStack(x)` 恒新实例（checkState 不变量逐字一致）；start 缓存上下文字段逐项一致、身份无可观察点。
- **0188**：containing=3×Mth.floor；getBlockState 只读坐标。
- **0189**：自有表无子类、Cancellable 默认 false。

## 勘察说明

三路 survey（方块实体与世界 tick / 网络编码与同步 / 实体 tick 与 AI）共产出 19 候选：实现 9 补丁+1 直提；scratch-list 机制类 3 项（pushEntities/Mob looting/NearestAttackableTargetGoal，需 Level fill 重载 + 重入论证）移批次 47；否决 7 项（travel(Vec3) 签名红线、goal 降频改语义、currentTimeMillis 合并改反作弊时点、SynchedEntityData/DataValue/Connection 限流等噪声级）。
