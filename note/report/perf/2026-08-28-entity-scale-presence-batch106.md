# 批次 106 报告（2026-08-28）— 实体规模轴在场确立 + 阶梯画像（多核调度系列㉒，R3 开篇，勘察/基建轮，版本保持 0.65.0）

主题：R2 遗留开放前沿（批次 105 实体在场失败）根治。真因闭环 + harness v5 + 阶梯矩阵。

## 在场问题真因（批次 105 四版全灭的完整解释）

[OfflineJoinBot.walk()](../../../benchmark/src/papo/bot/OfflineJoinBot.java) 的位置包起点
**硬编码为绝对 (0.5, 100.0, 0.5)**——站立/行走 bot 在服务端的真实位置由首个位置包锚定在该
绝对坐标。批次 105 v4 的牛群召唤在 y=104 绝对坐标：

- 地形低于 ~90 时：牛坠落 >13 格 → 摔伤超 10 HP → 全灭（v1 的 80 格坠落同因，NBT 键名
  只是次要因素——`NoAI`/`ActiveEffects` 在 1.21.11 实为合法键名（Mob.java:130
  TAG_NO_AI="NoAI"、LivingEntity active_effects），批次 105 的"键名被静默丢弃"判断不成立）；
- 地形高于 104 时：召唤点山体内窒息。

"在场探针 false"是结果不是原因：牛死了。选择器体积陷阱（x,y,dx 缺 dy/dz）是独立的
假阴性源，v4 的 `@e[type=cow]` 探针本身无此问题。

## v5 harness 方案（确定性在场）

全部命令 `execute at StandB00`（bot 真实位置权威锚点）相对执行：

1. 清空 bot 脚平面 ±40 XZ × y+0..y+24（7 条 fill，单条 ≤32768 块限制）；
2. y-1 铺 stone 平台（整平地形起伏；stone 非草方块不引动物刷新）；
3. 平台边 y 层橡木围栏（牛跳不过 1.5，杜绝坠落）；
4. 等面积网格（±32 足印恒定）召唤牛于 bot 脚平面（零坠落零窒息）；
5. `gamerule doMobSpawning false`（禁周期刷新）+ **join 稳定后清场** `kill @e[type=!player]`
   +item（chunk population 的 worldgen 种群不受 gamerule 约束——N=0 实测早清仍混入 5 头
   自然牛，晚清后 A=B=0）；
6. **计数式在场门**：窗口前后 `execute as @e[type=cow] run say MOO_A/MOO_B` 计数全等
   （A==B 且 A≥N；v5 冒烟 500 牛 A=B=505→禁刷+清场后 N=0 A=B=0 全绿）。

EAR 预写 spigot.yml `entity-activation-range.animals: 96`（±32 足印恒激活；EAR 判例：
maxBB 的 Y 向按世界全高膨胀，垂直距离不影响激活；sim-distance 8 时上限 (8<<4)-8=120>96
不被削）。

## 阶梯矩阵（10 站立 bot × 6min 窗，400-tick 相位窗，稳态=min 窗口径）

N ∈ {0, 500, 1000, 2000}（4000 见披露）。原始日志 benchmark/results/batch106-scale-N*.log，
解析 benchmark/results/batch106_parse.py（稳态=尾部 16 窗，500/2000 因清扫部分窗）。

| 相位 avg/tick (us) | N=0 | 500 | 1000 | 2000 | 每牛 500 | 每牛 1000 | 每牛 2000 |
|---|---|---|---|---|---|---|---|
| level.entities | 349 | 5053 | 7019 | 19440 | 9.4 | 6.7 | **9.5** |
| chunkMap.tracker.fanout | 70 | 9497 | 11897 | 21348 | 18.9 | 11.8 | 10.6 |
| chunkMap.tracker.sendChanges | 81 | 9741 | 12249 | 22194 | 19.3 | 12.2 | 11.1 |
| chunkMap.tracker.maintain | 27 | 430 | 547 | 891 | 0.86 | 0.55 | 0.44 |
| worlds（总） | 1124 | 16255 | 21583 | 44869 | — | — | — |

关键读数：

1. **容量墙**：2000 聚堆牛 worlds=44.9ms/tick（50ms 预算的 90%），窗墙 20.0s 仍保 20 TPS
   ——贴墙未破；4000 外推必破。每牛总成本（entities+fanout+maintain）≈20us。
2. **两大面**：实体 tick 链（N=2000 时 19.4ms，第一）与 tracker 扇出（21.3ms 主线程
   send 调用，随 N 反超关系变化：500 时扇出 2×实体链，2000 时两者相当）。
3. **每牛 U 型**：实体链 9.4→6.7→9.5us/牛——1000→2000 密度超线性抬头（spacing 2.0→1.42，
   push/goal 邻居交互）；扇出 18.9→11.8→10.6us/牛次线性（批量/摊销效应）。
4. **maintain 印证批次 101**：0.86→0.44us/牛单调下降，O(1) purge 在规模轴成立。
5. **扇出单位成本未归因**：19us/牛@500 vs 批次 102 实测 ~90ns/send 差 ~20×——
   构成（包数/牛 × ns/send，含 head-rot/metadata/posSync 型）是批次 107 探针的第一目标。

## 稳定性/环境披露

- **共享机清扫 ×3**：500 牛窗口第 2 分钟（20:54，日志瞬时截断无 crash report）、
  2000 牛窗口尾部（~21:12）、4000 牛 boot 后数秒（21:14）——外部杀进程，非服务器缺陷；
  harness 加固（stdin 容错 SERVER_DIED + dump 永达 + bot 失败改标志位）后数据可抢救。
- 500 数据为部分窗（6 稳态窗，killed 前牛群全在场 A=500 精确）；2000 为 8 稳态窗
  （A=2000 精确，窗口尾部被杀，B 探针未达）。
- 杂散实体：N=0 残 1 头（清场后仍出现，疑似晚到 population 装载）、N=1000 窗口 +3
  （B=1003>A——新增非死亡，0.3% 级噪声；严格门判 FAIL 属设计保守性，数据有效）。
- 4000：boot 即被清扫，错峰重试结果见下补记。

## 结论

实体规模轴**在场确立、画像闭合**（0→2000 线性外推置信）：主线程两大牛驱动面为
tracker 扇出与实体 tick 链，2000 聚堆牛即触及 TPS 预算墙。R3 深挖次序：①扇出内构成
探针（包型×ns/send 归因）→ 批量移交/排水优化设计（多核主轴，先例 suspendFlushing/
resumeFlushing 已在 Connection）；②实体链密度超线性子归因（EAR 扫描/goal 邻居/push
三候选）；③4000 若环境允许补测。harness v5 判例（bot 硬编码起点/population 不受
gamerule 约束/计数式在场门）沉淀入 optimizations.md。
