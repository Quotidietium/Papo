# 批次 48 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-203137.json`。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**3 项全部正收益（1.46×–8.58×，CI 均不重叠）**。批次 46 暂缓清单至此全部清算完毕。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0193 SWAP_ITEM_WITH_OFFHAND 门控 | SwapInteractGateBench.swap | 10.892 | 1.353 | **8.05×** | CI [10.79,11.00] vs [1.342,1.364] 不重叠。每次 F 键交换省 2×asCraftMirror + 2×clone + 事件 + callEvent |
| 0194 handleInteract 双事件门控 | SwapInteractGateBench.interact | 4.952 | 0.577 | **8.58×** | CI [4.89,5.01] vs [0.571,0.583] 不重叠。每次实体交互省事件构造 + getBukkitEntity + callEvent + resendData 判定链（精准交互另有 CraftVector，未计入） |
| 0195 MoveToBlockGoal above 缓存 | MoveToTargetCacheBench.tick | 2.185 | 1.496 | **1.46×** | CI [2.155,2.215] vs [1.474,1.518] 不重叠。每活跃 goal 每 tick 省 1 BlockPos |

## 等价性支点（源码实证）

- **0193**：PlayerSwapHandItemsEvent 自有 HandlerList（PlayerSwapHandItemsEvent.java:17）、无子类（全库 grep）；`CraftItemStack.equals`（CraftItemStack.java:76-83）：clone vs mirror 时 null==null 首查命中（EMPTY 场景 asCraftMirror 给 null handle，:140-142）、否则 `ItemStack.matches` 内容匹配——恒 true ⟹ 零监听器时两 setItemInHand 必走默认交换分支。门控路径与原默认分支逐句一致（先 setOff(原主)、后 setMain(原副)）。
- **0194**：两事件各自独立 HandlerList（PlayerInteractEntityEvent.java:16 / PlayerInteractAtEntityEvent.java:23，各持私有静态表）⟹ 按调用点分别门控精确。门控路径（event==null）跳过的事件块在零监听器时全为死代码：callEvent 无操作、isCancelled false、两次 getItemInHand 之间无任何调用 ⟹ resendData 恒 false；共享尾部（entityInteraction.run + CriteriaTriggers + swing）不变。
- **0195**：identity 缓存失效信号完备性——blockPos 只被重赋值（findNearestBlock 赋新建 MutableBlockPos 后立即 return、Paper stop() 赋 ZERO 单例），从不原地 mutate；8 直接子类 + RemoveBlockGoal 子树全库审计：无 `this.blockPos =` 直写、无 findNearestBlock 覆写（RemoveBlockGoal 仅调用）、无 cast-mutate；StriderGoToLavaGoal 覆写 getMoveToTarget 不受影响。身份相同 ⟹ 坐标相同 ⟹ above 结果相同（含 ZERO 单例跨 goal 复用：above(ZERO) 值确定，缓存不错误）。

## 勘察说明

本批清算批次 46 暂缓清单全部三项：0195 经全量子类审计解除"原地 mutate 面"疑虑；0194 采用 null-event 重结构（performInteraction 接收 `@Nullable PlayerInteractEntityEvent`），避免了暂缓时担心的"改 CraftBukkit 匿名 Handler 结构"；0193 直接成立。验证链：compileJava ✓ → 全量 test ✓（零 FAILED）→ rebuildPatches ✓ → 完整 applyPatches ✓（sources 915 + features 195 + resources 6）。
