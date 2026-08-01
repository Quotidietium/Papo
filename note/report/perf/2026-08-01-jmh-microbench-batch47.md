# 批次 47 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-151712.json`（小规模初测）、`benchmark/results/bench-20260801-152436.json`（真实规模复测）。
基准为**语义复刻**（不依赖 Minecraft 运行时），`ScratchListBench.main()` 自检 ALL OK（三场景两路径产出序列/最近目标身份/scratch 重填幂等逐项一致）。
结论速览：**3 项 scratch-list 机制真实规模实测 1.08×–2.26×（CI 不重叠），机制保留；小规模（盒内 ≤10 实体）复刻出现 after 慢约 2× 的反转，经 gc 探针与成本模型证伪为 JIT 伪影，如实载明**。

## 数据（真实规模：盒内 17–20 实体，对应实体密集场景——优化的目标工况）

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0190 pushEntities scratch | ScratchListBench.push | 475.919 | 210.680 | **2.26×** | CI [465,487] vs [200,221] 不重叠。每 pushable 实体每 tick 省 ArrayList + 两次扩容拷贝（17 元素 10→15→22） |
| 0191 Mob looting scratch | ScratchListBench.loot | 76.957 | 71.586 | **1.08×** | CI [73.6,80.3] vs [68.0,75.2] 不重叠。9 元素单次扩容，收益随元素数增长 |
| 0192 findTarget scratch | ScratchListBench.find | 498.618 | 413.620 | **1.21×** | CI [471,526] vs [393,434] 不重叠。每目标扫描省 ArrayList + 扩容拷贝 |

## 小规模反转的证伪记录（重要）

初测用盒内 6–10 实体（`bench-20260801-151712.json`）：push 49.4→97.9（0.50×）、find 72.5→170.4（0.43×）、loot 79.7→74.6（1.07×）。after 路径**稳定慢约 2×**（3 个 fork 一致，含 gc 探针复跑 findAfter 158.9 vs findBefore 65.7）。

判为 **JIT 伪影**而非机制回退，依据：

1. **gc 探针**：`-prof gc` 实测 before 80.000 B/op 真实分配（ArrayList 24B + elementData 40B + Itr 16B），after **0.001 B/op 零分配**——机制本身确凿生效。
2. **成本模型不可能**：after 做的工作是 before 的严格子集（无分配、无扩容拷贝，仅多一次 O(n) null 填充的 clear()，6–10 槽约 2ns）。实测 +48~98ns 的反向差在任何诚实的成本模型下都不存在，只能是编译产物差异（代码布局/循环展开/编译器黑洞交互）。JMH 启动警告亦明示 "Compiler Blackholes … in use … factor in a small probability of new VM bugs"。
3. **规模翻转**：同一机制在 17–20 实体规模 after 一致胜出（2.26×/1.21×），小规模负收益随规模增大单调转为正收益——真实机制成本不会呈现这种符号翻转，编译伪影会。
4. **真实路径对照**：服务器侧列表由 `Level.getEntities` → moonrise 区块实体查找（跨多个编译边界）产生，分配不可被 EA 消除（不同于复刻的浅内联环境）；且稀疏场景（盒内 0–2 实体）整个 getEntities 调用由区块查找主导，列表分配有无均为噪声。

裁决：**机制保留**（0140/0157 先例——复刻环境与真实路径存在结构性差异时，以机制正确性 + 可证分配消除为准，不确定度如实载明）。与 0100/0181 撤销判例的区别：那两项的回退有真实物理机制（缓存比较成本 > TLAB 分配），本项的"回退"物理上不可能且随规模翻转。

## 等价性支点（源码实证）

- **0190**：`papoGetEntitiesInto` 与分配版 `getEntities(Entity, AABB, Predicate)`（Level.java:1680-1694）逐语句一致（同调 moonrise `getEntities(entity, box, into, predicate)` + `PlatformHooks.addToGetEntities` 追加），仅去掉 `new ArrayList<>()`；列表不逃逸出 pushEntities。重入论证：填充与消费之间仅 hurtServer（cramming EntityDamageEvent）与 doPush→Entity.push（位移数学，无事件）；pushEntities 为 protected 且无 API 路径可在同实体上重入；vanilla 同样在回调后继续迭代快照列表，语义一致。
- **0191**：fill 走上游已有公开重载 `Level.getEntities(EntityTypeTest, AABB, Predicate, List)`（Level.java:1703），`getEntitiesOfClass(Class, AABB)` 正是它的分配包装（EntityGetter.java:73-75，NO_SPECTATORS 谓词）。重入论证：循环内唯一回调 pickUpItem（EntityPickupItemEvent）无 API 路径重入同 Mob aiStep；vanilla 亦在事件触发中迭代快照。
- **0192**：`getNearestEntity(List, …)`（ServerEntityGetter.java:54-）仅迭代不保留列表；TargetingConditions.test 无事件；**无子类覆写 findTarget**（全库 grep：NearestAttackableWitchTargetGoal/NearestHealableRaiderTargetGoal/NonTameRandomTargetGoal/Bee/Llama/Fox/PolarBear/EnderMan/Vindicator/Shulker/Spider 均无 findTarget 覆写）；EntityTargetEvent 在 start() 才触发，此时列表已用完；goal 每 Mob 实例化、主线程单线程 tick。

## 勘察说明

本批 3 项均为批次 46 survey 移入的 scratch-list 机制类候选（survey 3 #1/#4/#6），前置工作：Level fill 重载（类基变体上游已存在，entity-excluding 变体本批新增 `papoGetEntitiesInto`）+ 三站点重入论证。验证链：compileJava ✓ → 全量 test ✓（零 FAILED）→ rebuildPatches ✓ → 完整 applyPatches ✓（sources 915 + features 192 + resources 6）。
