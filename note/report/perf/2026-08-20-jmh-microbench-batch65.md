# 批次 65 JMH 微基准报告（2026-08-20）— 容器/菜单域暂缓清单回头攻克（0228/0229 + 直提交 + 两项否决）

批次 49 暂缓清单五项经专项 survey 逐句实证后：**三落地两否决**。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。

## 1. 直提交 — callPrepareResultEvent 零监听器快路（PrepareResult 全族）

- **门控键**：`PrepareInventoryResultEvent.getHandlerList()`——**全族唯一表**（PrepareAnvil/Grindstone/Smithing 与 Paper 的 destroystokyo 变体均不声明自己的表，paper-api 逐文件实证；PrepareItemCraftEvent 独立另一张表且已有门控）。一张表覆盖 7 个调用点（anvil/grindstone/smithing/loom/stonecutter/cartography/combiner）。
- **快路**：零监听器 → `container.broadcastChanges(); return;`。跳过 setItem 回写的三点论证：零监听器时 result 为构造原值（值恒等回写）；`ResultContainer.setChanged` 是空方法；vanilla 本无此写（Paper 事件 shim 的一部分）。broadcastChanges（客户端同步）无条件保留。
- **频率**：各结果菜单每次输入变化 + **铁砧改名每击键**（AnvilMenu.setItemName → createResult → 本方法）。
- **风险**：低。

## 2. 0228 — InventoryCreativeEvent 零监听器快路

- **门控键**：`InventoryClickEvent.getHandlerList()`（子类无自有表 → 注册进父表，与 0154 同键同表）。
- **快路**：`itemStack = packet.itemStack().copy();`——`asNMSCopy(asBukkitCopy(x)) ≡ x.copy()`（CraftItemStack 拷贝链逐例恒等，empty → EMPTY 单例）；零监听器时 ALLOW/DENY 死路、getCursor() 恒为构造值。
- **频率**：创造模式每次背包点击/放置/丢弃。
- **风险**：低。

## 3. 0229 — InventoryDragEvent 零监听器快路（doClick QUICK_CRAFT）

- **门控键**：`InventoryDragEvent.getHandlerList()`（自有表，无子类）。
- **快路**：逐句保留 `itemStack` 快照/`count` 计算/`newCarried` 镜像+setAmount/**两段 setCarried**（预写是防插件关背包复制的机制、末写是原路径行为，两者值恒等）/view.setItem 循环；仅省 eventMap 构建+事件对象+callEvent+结果判定（零监听器恒非 DENY）。
- **频率**：每次多槽拖拽手势完成（用户交互级）。

## 结果（ContainerGateBench，模型）

| 方法 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| before_dragEvent vs after_dragFastPath（9 槽） | 193.622 ± 0.514 | 103.143 ± 0.184 | **1.88×**（CI 极窄不重叠；快路保留了必需赋值，差值即事件侧机制成本） |
| before_creativeEvent vs after_creativeFastPath | 4.168 ± 0.035 | 1.728 ± 0.009 | **2.41×**（模型仅含 view+bukkit 拷贝+事件；真实路径还含派发+switch，实际更优） |

- 行为自检 ALL OK：drag 快/慢路终状态逐字段一致（carried 值/槽位/setCarried 次数）；creative/recipe 等价断言。

## 4. 否决一：RecipeManager nullable 内部核（实测中性即撤，判例）

- 候选：hint 命中路径（熔炉/营火每 tick）`Optional.of` 双包 → nullable 核单包。
- **gc 探针实证**：`-prof gc` 下 before/after 同为 **16.000 B/op**——中间 Optional 跨方法但被内联，EA 直接消除；手工拆包零收益。
- **判例**：跨方法但被内联的 Optional 包装链不值得手工拆（0187"实测无收益即撤"同判）。已实现后撤回（内部 rebase 摘除），基准留档。

## 5. 否决二：TransientCraftingContainer → CraftingInput 缓存（失效信号不可闭合）

- 三个实证漏洞：①vanilla `ServerPlaceRecipe.java:184` 的 `item1.grow(count1)` **原位变异网格活栈**（不经 setItem，mutation counter 不可见）；②插件经 `CraftInventory.getItem` 镜像 setAmount/setType 原位改栈 + `getContents()` 活 list 暴露；③缓存键与容器别名同一批栈 → 原位变异后 `equals` 校验恒真（比较栈对自身）——值校验也救不了。
- 对照：熔炉侧 `SingleRecipeInput` 引用键缓存（0144 先例）可行恰因其纯包裹无预计算状态；CraftingInput 预计算 ingredientCount/StackedItemContents ⇒ 同法不可移植。收益上限仅每 craft 10 次构造中的 1 次。**不做**。

## 验证链

compileJava（含否决摘除后复验）BUILD SUCCESSFUL → 自检 ALL OK → JMH + gc 探针 → rebuildPatches → applyPatches → 全量 test（见 optimizations.md 批次 65 记录）。
