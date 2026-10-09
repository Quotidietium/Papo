# R7 轮1（批次147）：单值 PalettedContainer 规范 Data 共享——常量容器三层状态坍缩（0.84.0 → 0.85.0）

- 日期：2026-10-10
- 版本：0.84.0 → 0.85.0（补丁 0270）
- 前置：批次146 负结果收束后，0.84.0 树重跑浸泡 + 1,205MB 堆转储全量普查重新基线化
  （旧画像来自 0.83.0 时代转储，且该腿为 ~4.4k 区块的慢机插曲——按区块数归一后两代
  画像逐项一致，无代码性漂移）。

## 0. 摘要

重基线普查（6,059 区块 / 861.6MB）显示最大非内容驻留簇为**常量区块节（section）的
容器三层状态**：394,848 个 LevelChunkSection 中 87%（343,650）持有单值方块 palette，
另有 232,765 个单值生物群系容器——合计 576k 个"平凡"容器，各付 Data(32B) +
SingleValuePalette(24B) + moonrise$palette/rawPalette Object[1](24B) ≈ 80B，仅取决于
(strategy, value)。批次145 已共享 ZeroBitStorage 载荷（bits=0 存储），本轮把其余三层
全部坍缩为**每 (strategy, registry id) 一个规范 Data**（0270）：共享值以注册表 id 为键，
规范实例即注册表驻留实例；发布后的共享 Data 永不结构突变——增长走属主容器的 data
整体换新（onResize），唯一就地写是引用幂等的 idFor(同值) 赋值，就地重写 palette 的
FriendlyByteBuf read() 路径（服务端不走，区块经 NBT codec 装载）以守卫强制私有化。

**配对浸泡（12 bot、6,072 vs 6,114 区块、门全绿）：堆 865,311 → 831,599 KB =
−33.7MB（−3.90%）**，且 B 腿多 42 个区块（区块调整后净效应 ≈ −37MB）。转储普查
归因闭合：**单值 moonrise$palette Object[1] 569,100 → 11 个**（规范共享集本身），
多值缓存逐档不变（elems=2：128,582 保留），Object[] 总数 1,386,853 → 820,906
（−565,947），PalettedContainer/LevelChunkSection 实例数不变（语义面零变化）。

## 1. 重基线：0.84.0 活集画像（6,059 区块，861.6MB，1,205MB 转储）

| 簇 | 规模 | 定性 |
|---|---|---|
| SWMRNibbleArray byte[]（光照 nibble） | 83.4MB / 40,730 个，均匀 10,737（22.0MB） | 批144 判例维持出局（并发读协议改造；占比 2.55% 不足以推翻） |
| SimpleBitStorage long[] | 134.8MB / 247,557（n=256 方块 palette 50,185 + n=37 高度图 28,181 + 生物群系 161k） | 区块内容本体，红线 |
| **单值容器三层状态（本轮）** | **576k 容器 × ~80B ≈ 46MB** | Data+palette+Object[1] 仅依赖 (strategy, value)——可共享 |
| LevelChunkSection 实例 | 15.8MB（394,848 × 40B） | 节对象本身可变（计数/回调），不可共享 |
| String | 12.5MB / 387,940（47.7% 冗余） | 批146 三设计判负关闭 |
| HashMap$Node 族 | 账面闭合（批144） | 活 |

每节常量开销结构（单值方块节）：LevelChunkSection 40B + PalettedContainer 24B +
Data 32B + SingleValuePalette 24B + Object[1] 24B + 生物群系侧同构 ≈ 280B；
三层（Data/palette/数组）共 80B 为本轮目标，容器实例（24B）与节（40B）因 data 字段
必须可换而保留私有。

**普查勘误（判例）**：0.83.0 时代普查腿实际运行 ~4.4k 区块（当时未记录区块数），
本代 6,059 区块逐项 +35~40% 完全由区块数解释——两代每区块比率逐项一致（palette
8.3/节、高度图 4.65/块、nibble 6.76/块、生物群系 26.6/块）。**跨代普查对比必须先按
区块数归一**，否则会把场景差当代码差。

## 2. 设计（0270）

### 2.1 共享单元与键

共享单元 = `PalettedContainer.Data`（含其 SingleValuePalette 与预构建的
moonrise$palette 缓存数组；storage 已是批145 的按尺寸共享 ZeroBitStorage）。键 =
(strategy 实例, `strategy.globalMap().getId(value)`)：

- **注册表 id 即身份**：共享值必然是注册表驻留实例（getId<0 直通不共享），因此
  `SingleValuePalette.idFor` 的引用比较（`this.value != state`）在共享 palette 上的
  行为与私有 palette 逐位一致——等值异引用的歧义在键选择处就被消灭；
- **缓存挂 Strategy 实例字段**（`Strategy.papoSharedSingleValues`）：随 level 卸载
  整体回收（无全局强引用泄漏面），容量上界 = 该 strategy 的注册表大小（无用户数据
  驱动的增长）。

### 2.2 出生点双钩

1. **生成路径公共构造器** `PalettedContainer(defaultValue, strategy, presetValues)`：
   presetValues==null 且值在注册表内 → `this.data = 规范 Data`。覆盖
   PalettedContainerFactory.createForBlockStates/createForBiomes（每节构造）与
   recreate()（生物群系重填）。
2. **磁盘解码私有构造器**（codec unpack 尾部）：presetValues==null && palette
   单值 && storage 为 ZeroBitStorage → 规范化（valueFor(0) 为 null 的损坏输入保持
   私有，与 vanilla 容忍度一致）。

### 2.3 不变量与守卫

- 发布后的共享 Data 永不结构突变：`onResize` 构造新 Data 并仅替换**属主容器**的
  `this.data`（volatile，字段重读语义既有）；`Data.copy()`/`palette.copy()`
  （SingleValuePalette 本就返回 this——vanilla 自身视其为可共享）只读或同样走容器级
  换新；`updateData` 只在私有 Data 上被调用（共享实例在构造时预构建缓存数组）。
- 就地 palette 重写的唯一路径 `read(FriendlyByteBuf)`（服务端区块装载走 NBT codec，
  不经此路径）加守卫：data.papoShared 或 data.palette 为共享 SingleValuePalette
  （经 Data.copy() 传递的形态）→ 强制 `createOrReuseData(null, _byte)` 私有化。
- Anti-Xray presetValues 非空的容器构造后立即扩容（ctor 内 idFor presets），共享
  钩以其为前置条件自然跳过。

## 3. 验证

- **单测**：PapoPalettedSharedDataTest 8 项（合成 IdMap strategy，无需 vanilla
  bootstrap）：共享+跨 strategy 隔离、同值写不脱离不突变、增长脱离且共享者不受扰、
  codec 解码规范化 + pack 字节恒等、copy/recreate 独立性、未注册值永不共享、
  buffer-read 双形态守卫、8 线程构造收敛。全绿（PapoRegressionTestSuite 聚合，
  批145 两套件维持 8/8）。
- **全链**：rebuildPatches 0270 + applyPatches 重放 EXIT=0 +
  compileJava --rerun-tasks BUILD SUCCESSFUL + check_patch_counts 全仓 918 ALL OK；
  0.85.0 jar 内嵌 server jar 含批次147 字节码（javap 复核）。
- **配对浸泡**（门=零错误+exit 0+区块数 ±1%）：

| 腿 | 堆 (KB) | 区块 | PalettedContainer |
|---|---:|---:|---:|
| A-084 | 865,311 | 6,072 | 790,945 |
| B-085 | 831,599 | 6,114 | 794,977 |

**−33,712 KB（−3.90%）**；B 多 42 区块（×86KB ≈ +3.6MB），区块调整后净效应
≈ −37MB，与普查预估 46MB 同向（差额 = 本腿区块构成 + 规范实例保留 + 生成中瞬态）。

- **普查归因闭合**（B 腿 1,139MB 转储）：Data Object[1]（单值缓存）569,100 → **11**；
  elems=2/4/16/41 档逐档不变（多值缓存内容保留）；Object[] 总数 −565,947；
  SWMRNibbleArray 41,089 / long[] 173.9MB 不变（内容面零触碰）。

## 4. 账面与主线

- 本轮：**−33.7MB（−3.90%）**，R6 两轮累计 0.83.0 → 0.85.0：876.3 → 831.6MB。
- 内存主线 0.80.0 → 0.85.0：1,154.3 → 831.6MB = **−322.7MB（−28.0%）**。
- 残余画像：nibble 均匀段 22.0MB（判例维持出局）、SimpleBitStorage 内容 134.8MB
  （红线）、节/容器实例 ~40MB（data 字段可换性所需）、String 12.5MB（批146 关闭）。

## 5. 判例（入库）

1. **共享对象的可变性审计以"发布后是否结构突变"为判据**：增长走属主字段换新 +
   就地写仅引用幂等 + 唯一就地重写路径加守卫，则共享在并发语义内（moonrise 惰性
   缓存预构建于发布前，消除发布后惰性写）。
2. **共享键选注册表 id 而非对象身份/equals**：注册表 id ↔ 驻留实例一一对应，从键
   层消灭"等值异引用"进入共享集的可能，下游所有引用比较（idFor 的 `!=`）行为
   逐位不变。
3. **缓存挂属主对象字段而非全局映射**：生命周期随属主（level 卸载即回收），泄漏面
   与容量上界（注册表大小）同时收口——比弱键全局映射简单且无锁竞争。
4. **跨代普查对比必须按区块数归一**（§1 勘误）：场景差（腿的区块数）会伪装成代码差。
5. **配对差分的区块调整**：B 腿 +42 区块 × ~86KB/块 ≈ +3.6MB 应从总差中扣除后再
   归因（本轮 −33.7MB 总差 → ≈−37MB 净效应）。
