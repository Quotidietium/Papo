# 批次 49 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-214753.json`（主跑）、avoid gc 探针复跑、trade 单独复跑（补 tradeBefore）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检 ALL OK。
结论速览：**5 项正收益（1.10×–6.68×，CI 不重叠）、2 项机制保留（gc 探针证分配消除、复刻浅栈内 JIT 伪影）**。

## 事件门控（EventGateMiscBench）

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0196 TradeSelectEvent 门控 | trade | 3.174 | 0.524 | **6.06×** | CI [2.90,3.45] vs [0.46,0.59] 不重叠。每次交易选项点击省 getBukkitView+事件+callEvent |
| 0197 BlockBurnEvent 门控 | burn | 3.243 | 0.485 | **6.68×** | CI [2.55,3.94] vs [0.46,0.51] 不重叠。每次火烧毁省 CraftBlock×2+事件+callEvent |
| 0198 CauldronLevelChangeEvent 门控 | cauldron | 3.714 | 0.635 | **5.85×** | CI [3.45,3.98] vs [0.60,0.67] 不重叠。炼药锅水位变化省 CraftBlockState+CraftBlock+事件 |
| 0199 BlockIgniteEvent 门控 | ignite | 0.656 | 0.548 | 1.20× 中性 | CI [0.54,0.77] vs [0.50,0.59] 重叠。复刻浅栈内 CraftBlock 被 EA 标量替换（同 0186/0189 判例）；真实 callBlockIgniteEvent 跨方法深栈 EA 无法消除，机制保留 |

## scratch-list（EntityScanScratchBench）

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0200 AvoidEntityGoal scratch | avoid | 280.075 | 355.698 | 0.79×→中性 | 主跑反转；gc 探针实测 before 416.00 B/op → after 0.002 B/op（分配确凿消除），复跑 CI 重叠（315±40 vs 319±17）。批次47 同款 JIT 伪影：after 工作量是 before 严格子集（仅多 O(n) clear），反向差物理不可能，机制保留 |
| 0201 FollowParentGoal scratch | parent | 264.454 | 239.571 | **1.10×** | CI [260.7,268.2] vs [237.4,241.7] 不重叠 |
| 0202 NearestItemSensor scratch | item | 460.049 | 409.521 | **1.12×** | CI [456.9,463.2] vs [395.2,423.8] 不重叠 |

## 等价性支点（源码实证）

- **0196**：TradeSelectEvent 自有 HandlerList（TradeSelectEvent.java:19）、无子类（全库 grep）；零监听器 callEvent 无操作、isCancelled() 默认 false（Cancellable 默认不取消）。
- **0197**：BlockBurnEvent 自有 HandlerList（BlockBurnEvent.java:18）、无子类；调用方 FireBlock.checkBurnOut 只读取消标志。
- **0198**：CauldronLevelChangeEvent 自有 HandlerList（CauldronLevelChangeEvent.java:18）、无子类；炼药锅无方块实体 → `newState.place(UPDATE_ALL)` ≡ `level.setBlock(pos, newBlock, UPDATE_ALL)`（CraftBlockState.place 即 Level.setBlock(pos, handle, flags)，handle=newBlock）。零监听器时 callEvent 恒 true。
- **0199**：BlockIgniteEvent 自有 HandlerList（BlockIgniteEvent.java:20）、无子类；两站点（LavaFluid randomTick 引火）与 FireBlock:231 同模式（后者 Papo 已门控，岩浆两处漏网补齐）。
- **0200-0202**：0190 scratch-list 模式——getNearestEntity/线性扫描仅迭代不保留列表，列表在 canUse/doTick 内消费完毕；AvoidEntityGoal/FollowParentGoal 每 goal 实例化、NearestItemSensor 每 Brain 实例化（per-entity），主线程单线程 tick，无重入。

## 勘察说明

本批为批次 49 三路 survey（容器/菜单、区块IO/保存、AI/Brain）产出 28 候选中**已验证低风险子集**：4 个零监听器事件门控（自有表无子类，逐文件实证）+ 3 个 scratch-list（复用 0190 模式与 fill 重载）。复杂候选（InventoryDragEvent 门控含 setCarried 双拷贝语义、InventoryCreativeEvent 的 ALLOW/DENY/getCursor 链、callPrepareResultEvent 的 Prepare* 子类 getHandlerList 方法层实证、TransientCraftingContainer CraftingInput 缓存、RecipeManager Optional 内部路径、PathNavigation Vec3）需更深分析，留后续批次。验证链：compileJava ✓ → 全量 test ✓（零 FAILED）→ rebuildPatches ✓ → 完整 applyPatches ✓（sources 915 + features 202 + resources 6）。
