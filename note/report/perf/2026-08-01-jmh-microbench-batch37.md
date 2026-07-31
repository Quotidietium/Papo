# 批次 37 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-011814.json`。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**4 项测量全部正收益（1.25×–10.60×）**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0150 ValidateNearbyPoi 手写 OneShot + Brain 原生读 | ValidatePoiHandwrittenBench | 219.693 | 176.197 | **1.25×** | 16 次 tryStart 复刻（含记忆门三态与 erase 路径）；每次省 MemoryAccessor+Optional 分配与应用闭包层。CI [212.7,226.7] vs [146.8,205.6] 不重叠 |
| 0151/0152 Behavior/Sensor tickRate 缓存 | TickRateCacheBench | 101.240 | 15.831 | **6.40×** | 30 行为/tryStart 批量：Table.get 双哈希+拆箱 → volatile 纪元读+引用比较；未命中路径（首用/reload 后/换世界）与原版一致 |
| 0153 CountingOps RegistryOps 缓存 | CountingOpsCacheBench | 5.502 | 0.519 | **10.60×** | 每个非空入站物品栈解码省 RegistryOps+HolderLookupAdapter+CHM 三连分配（0133 同构机制） |
| 0154 InventoryClickEvent 零监听器快路 | InventoryClickGateBench | 2.300 | 0.598 | **3.85×** | 复刻仅含事件对象层成本；真实路径还含整个点击/动作映射 switch（约 200 行分支，QUICK_CRAFT 除外逐字保留）、Bukkit 包装与派发，实际收益更高 |

## 备注

- 0151/0152 的缓存键为（配置纪元, 世界配置引用）双键：纪元由 `/paper reload`（PaperConfigurations.reloadConfigs 开头，直接提交 0155）递增，引用比较覆盖跨维度传送（实体携 Brain 换世界）。NMS 内部直改配置表（无 Bukkit API 路径）在下次 reload 前不可观察——已在补丁注释载明。
- 0154 快路正确性三支点（全部源码实证）：① CraftItemEvent/SmithItemEvent/CartographyItemEvent 均未声明自己的 getHandlerList，四类共享 InventoryClickEvent 单一 HandlerList；② switch 全量副作用扫描：仅 QUICK_CRAFT case（resetQuickCraft/递归重派发/clicked）三处，已逐字复制进快路；③ `cancelled == isSpectator`（:3057 唯一定义），快路 `!cancelled → clicked` 与 `setCancelled→getResult()!=DENY` 语义等价。
- 0154 复刻未建模 QUICK_CRAFT 拖拽路径（快路与原版共享同一段逐字代码，无差异可测）。
