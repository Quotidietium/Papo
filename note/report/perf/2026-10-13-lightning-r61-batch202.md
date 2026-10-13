# R61（批次202）：天气/雷电轴（thunder 天气+LightningBolt 风暴）→判负闭合（第十八域）

日期：2026-10-13 · 版本 0.110.0（保持，无代码变更）· 提交 98c89a0e0 后

## 0. 选轴事实核查（R38 判例）

验证器候选四核查：①骑乘/载具轴——optimizations 0122 矿车事件门控已收割=
R2/R3 已收割面的 census 回流终验型非新格；②投射物轴——批次 41-44"弹射物
专题"（0167/0169-0171/0172-0174/0175-0177）已深度收割弹射物 tick 链分配=
同型判出；③聊天/命令高频轴——0133 RegistryOps 缓存已收割 Component 编解码
主面+Brigadier 解析缓存已判否（键不可证等价）判出；④**天气/雷电轴——
全史零命中**（grep Lightning/Thunder 于 optimizations/全部 perf 报告=0）
=从未被任何时代的任何口径碰过→**第十八域真新格**。雷电链复合：LightningBolt
实体 spawn+tick、点火尝试（BlockIgnite=0119 门控收割面的**真实触发者**首次
入场）、雷击火 setBlock 邻域更新（R58 族）、音效/实体 AddEntity 广播
（批73 memo 检查点第二域）、weather thunder 雷暴 tick 相
（ServerLevel.tickWeather 自然雷击循环）。

## 1. harness 与腿

**boom=3 雷电形态**（复用 boom 骨架，bot 零改动 y=200 驻留）：boomSetup
石板 24×24×3 y[148,150]/bot 同款；风暴=marker 前 `weather thunder 600`+
24 波×每 bot 2 雷（互质步长扫描位 y=151 石板面）=**576 雷/腿**；对照腿
boom=1（石板+默认 clear 零雷击）。

**探针腿先行（R60 判例）**：B 形态腿先行验证——summonEcho=**576/576**
（回显 "Summoned new Lightning Bolt"）+**JFR Lightning 15/FireBlock 5/Thunder 1/
Weather 1 帧在场**=雷电链真实执行铁证；fire 探针三点零命中=测量瑕疵
（**雷击火=10% 点火概率×hit 位 ±3 随机偏移的统计性落点**，固定点必 miss）
+weather query 回显零命中=**本地化陷阱**（zh_cn 控制台 translatable 组件，
英文 grep 不可用）——按 R58/R59 复合门判例定谳**免重跑**（探针坐标语义
错误≠负载未发生），harness 探针修正为两雷击位 3×3 邻域 12 点+weather
探针退役（判例留档）。A 对照腿随后补跑，门绿（SLAB-INTACT+零 summon）。

**腿与门**（alloccensus-r61storm.sh，JFR 300s 双事件集）：

| 腿 | boom | summonEcho | 探针 | events | raw | heap | OldSample | 门 |
|---|---|---|---|---|---|---|---|---|
| A-110clear | 1 | —（零） | SLAB-INTACT ✓ | 11,957 | 31,691MB | 627,077KB | 32 | 绿 |
| B-110thunder | 3 | 576/576 | 复合定谳（JFR 帧+echo） | 14,723 | 35,641MB | 705,815KB | 8 | 复合绿 |

traces 12/12、logErrors=0、exit 0×2。B 事件 +23.1%/raw +12.5%。heap B
+12.6%——注意：B 腿 bots 在线期间雷暴自然 spawn 更活跃（NaturalSpawner
族 B 独占增量 +123/+121 相=雷暴期 spawn tick 陪跑）+雷击火区块脏=数据面。

## 2. 判定：四支柱判负闭合（第十八域）

**雷电链符号级归因**（R59 判例全栈扫描；A 腿对照 Lightning/Thunder/Fire
全零=差分隔离纯净）：**链本体 23 帧**（Lightning 15+FireBlock 5+Thunder 1+
Weather 1）=**历史十八域最低量级**（R58 红石 ~550 trips/R59 放置 77/
R60 爆炸 ~1,050/R61 雷电 23，两数量级低于红石）。叶子分布：Collecting
NeighborUpdater.shapeUpdate ×2（雷击火 setBlock 邻域更新=R58 维持族）+
LightningBolt.tick+事件机器基建（PriorityQueue/IdentityHashMap）+散布
单点——**零单体机械面**。

**B−A 全站差分 38 面（|Δ|≥8）全归因**：
- B 独有面：**RegistryFriendlyByteBuf decorator +94（0→94）=R44 维持同脸
  第四见**（雷击音效/AddEntity 包广播流量）；DataResult$Success.setLifecycle
  +76（DFU 族=数据面）；CompoundTag.write fastIterator +85（保存序列化挤窗
  面）；InetAddress.toString +76（网络统计自观测）；
- 共享面增量：NaturalSpawner 族 +123/+121（**雷暴期 spawn tick 陪跑**=B
  事件 +23% 的主因之一，R57/R60 同款 vanilla 语义面）、自观测族
  （ThreadImpl +230/spark 族 +444 计）按事件率放大、保存族带内、AQS/
  线程池 +41/+46（IO 活动回声）；
- A 独有面（挤窗）：**SpreadingSnowyDirtBlock.randomTick −64**（晴天随机
  tick 面——雷暴天气改变 randomTick 模式的相位画像）、WeightedList.contains
  iterator −66、AABB.inflate −57。

**回流检查（双收割面+一门控）**：R47 travel-dedupe 带内（papoVisitCorners
50→95=雷暴实体活动量联动）；**批73 PapoSharedWireMemo B=9（A=0）=雷击火
setBlock 广播走 memo 快路=第三域覆盖实证**（红石→TNT→雷击火三域兑现）；
**0119 BlockIgnite 门控零帧回流**（雷击火链上 handleIgnite 无分配残留=
零监听快路对真实雷击触发者的正确表现——0119 收割面对第十八域触发源无
代价回流）。存活侧 32/8 全 spark 自观测族零泄漏。

**四支柱**：①量级——23 帧/300s 窗两数量级低于 R58 立项门槛；②恒等可证
——零单体面（23 帧散布全已知族）；③上游/语义——LightningBolt.tick=vanilla
语义本体、点火随机性=游戏语义、天气循环=tickWeather vanilla；④相位——
雷暴期 spawn/randomTick 模式迁移带内判读（R56 判例）。**判负闭合，第十八
域（天气/雷电轴）终态**。分配普查十八域全覆盖终态维持。

## 3. 判例（build.md 同步）

1. **雷击火统计性落点**：LightningBolt 点火=10% 概率×hit 位 ±3 随机
   偏移——固定点 fire 探针必 miss；统计性产物探针须多点邻域扫描
   （两雷击位 3×3 邻域 12 点）或复合门定谳。
2. **控制台回显本地化陷阱**：`/weather query` 等 translatable 组件回显按
   控制台 locale 渲染（zh_cn=中文）——英文 grep 不可用；探针正证据优先
   用 say 自定 tag（恒英文）或 JFR 帧，系统命令回显不可作门依赖。
3. **探针腿先行+复合门免重跑**（R60/R58 判例链）：summonEcho 计数+JFR
   特征帧+回显样本三源已证负载真实时，探针差一不触发重跑——探针是测量
   不是负载本身。
4. **天气轴的相位画像**：thunder 切换改变 spawn 活跃度（+23% 事件主体）
   与 randomTick 模式（SpreadingSnowyDirt 位移）——天气轴差分判读必须
   先剥离 spawn 陪跑面再谈雷电链本体。

## 4. 版本与工件

版本保持 0.110.0（无服务器代码变更）。工件：
- `benchmark/alloccensus-r61storm.sh`（腿脚本）
- `benchmark/src/papo/bot/CompositeCensus.java`（boom=3 雷电形态+探针修正）
- `benchmark/results/storm-20261013-081353-A-110clear/`、
  `benchmark/results/storm-20261013-080321-B-110thunder/`（双腿 JFR+日志）
