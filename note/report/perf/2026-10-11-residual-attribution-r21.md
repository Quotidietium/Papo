# R21 残余归因审计轮（批次162，版本保持 0.92.0）

**日期**：2026-10-10/11　**性质**：纯审计轮（零代码变更、零补丁、版本不 bump）
**基线转储**：`benchmark/results/memsoak-1791649392644/`（0.92.0 收官浸泡对 B 腿，620,102KB @ **6,114 区块**，R20 产出）
**对照片**：`memsoak-1791616915179/`（同会话浸泡对 A 腿，0.80.0 二进制，1,159,239KB @ **6,128 区块**——同窗制式，区块配对 0.23%）
**新工具**：`benchmark/src/papo/bot/PapoO2OStateCensus.java`（O2O 状态普查 + [J 构成归因 + 字段级采样三合一）

三候选面全部**判负**：每面都把既有判负理由从"相关性归因"升级为"状态级/公式级硬证据"。主线收敛结论（R11/R12/R13/R19 四轴）不变且更硬。

---

## §1 面 (a)：CompoundTag O2O 30,452 张——NBT 内容表示审计 → 判负（内容主导）

### 1.1 空率实测（本轮核心新证据）

`PapoO2OStateCensus` 解码每张 CompoundTag 内 `Object2ObjectOpenHashMap` 的 `size` 字段
（fastutil 声明序 blob 偏移，经控制转储实证，见 §4.1）：

| map size | 张数 |
|---|---|
| **0（空）** | **187（0.6%）** |
| 1 | 3,838 |
| 2-3 | 18,742 |
| 4-7 | 7,135 |
| 8-15 | 493 |
| 16-31 | 51 |
| 32-63 | 6 |
| 合计 | 30,452（=直方图 30,450 ±2） |

**中位数 2-3 条目 = 真实 NBT 内容**（典型最小复合：`id` + 若干字段）。空表壳全部成本
（187 × [16B CT 壳 + 64B O2O 壳 + 2×84B 16 槽背板] ≈ 0.04MB）——懒分配模式（批161 同型）
在此面收益为零。Paper 上游已用 `O2O(8, 0.8f)`（16 槽）压缩过底座。

### 1.2 持有者归因（ShellRefAttr + 空 CT 滑窗）

30,452 张 CompoundTag 的直接引用者（refsHit 34,803 > 实例数 = 复合计数正常）：

| 持有者 | 引用数 | 判定 |
|---|---|---|
| `[Ljava.lang.Object;` | 14,893 | 嵌套 NBT 树内容（ListTag elementData 等） |
| `com.mojang.serialization.Dynamic` | 7,182 | DFU 转码视图（在途，非驻留壳） |
| Moonrise `NBTMapType` | 6,956 | 数据修复管线视图（与 NewChunkHolder 4,312 批量 1:1，在途） |
| Moonrise `NewChunkHolder` | 4,312 | **全部指向 187 个共享空实例**（Moonrise 自带共享空 CompoundTag 表示，零收割空间） |
| 模板/SpawnData/TypedEntityData/玩家 | ~1,150 | R10/T10 已判域的真实内容 |

转码视图壳合计 ~0.4MB 且随管线在途生死——无驻留面。

### 1.3 表示改写的红线论证

对非空内容做规范化共享（intern/COW）需触碰**可变** NBT 热路径：R6 判例（String 规范化
三准入设计全判负 + 负结果回退）+ 跨持有者别名化 = 行为变更风险。**判负维持 R11 结论，
新增状态级证据（空率 0.6% 实测）。**

### 1.4 每区块稳定性

A-080：32,091/6,128 = 5.23/区块 → B-092：30,450/6,114 = 4.98/区块。无驻留增长。

---

## §2 面 (b)：[J 175.4MB @ 6,114 区块构成复核 → 判负（R13 边界 + 用户终局维持）

### 2.1 归因闭合（85.1% 直接 + 残余滑窗闭合）

| 持有者 | 字节 | 张数 |
|---|---|---|
| `SimpleBitStorage`（palette+heightmap+biome 全部容器载荷） | 139.5MB | 178,998 |
| `java.util.BitSet`（光/调度位图词） | 9.7MB | 29,544 |
| **直接归因小计** | **149.2MB（85.1%）** | |
| 残余 26.2MB 滑窗归因：`PapoPosTypeSet` 7.4MB（**批123/145 自有结构=计划刻真内容**，2 的幂长度指纹一致） | | |
| fastutil `Reference2ObjectLinkedOpenHashMap.link`（**本身就是 long[]**，索引编码双向链）5.9MB + `ReferenceLinkedOpenHashSet` 1.4MB = 每持有者调度状态（heightmap 注册表 map、Moonrise 状态 map；容量 N+1 槽长度指纹） | 7.3MB | |
| 光引擎固定缓冲（Sky/BlockStarLightEngine）+ 传播器 + netty 队列 + EndIsland 噪声缓存 | ~5MB | R11 已判域 |
| 长尾（每类 <1MB） | ~6.5MB | |

### 2.2 R13 三支柱边界重验——**布局公式恒等证明**

vanilla SBS 每 long 装 `valuesPerLong = 64/bits` 个值**不跨 long 边界**，故存储长 = `ceil(size / valuesPerLong)`。
0.92.0 全长直方图与公式逐档对账：

| 档位 | 公式 | 实测 | 字节 | 判定 |
|---|---|---|---|---|
| 方块 palette 4-bit | ceil(4096/16)=**256** | 50,951 张 | 105.6MB | R13 判负域（每区块 17.3KB，B-088 时 23.0KB——**下降**） |
| 方块 palette 5-bit | ceil(4096/12)=**342** | 7,464 张 | 20.7MB | **新成熟队列**：字段级解码证实 bits=5/mask=0x1F/size=4096/valuesPerLong=12/divideMul=0x15555555；17-32 态真混合段=世界内容成熟化（B-088 时 ≈0，同一世界反复游玩后跨 16 值界） |
| heightmap 9-bit | ceil(256/7)=**37** | 28,402 张 | 9.1MB | 与 R13 的 28,141 几乎逐位一致 |
| biome 1/2-bit | ceil(64/64)=**1** / ceil(64/32)=**2** | 95,777+33,608 张 | 4.4MB | R13 的 9.1 万量级 |
| 跨边界的 320（=ceil(4096×5/64)） | — | **恰好 0 张** | — | **公式恒等证明**：若 SBS 跨 long 边界打包，5-bit 应产生 320 档；其缺席证实不跨界布局 |

palette 内容变长编码 = **用户终局**（R17 呈报，2026-10-10 裁定不做）。5-bit 队列属同一内容族。
每区块 [J：A-080 31.4KB → B-092 28.4KB（同窗配对，下降）。

### 2.3 `link:2` 字段之谜的排障记录（判例载体，见 §4.2）

R2O-linked 残余归因初看反常（map 实例"直持"[J]）。字段级采样（`-DsampleCls`）命中全部落在
声明字段 `link:2`，查 fastutil 源 `protected transient long[] link;`——**字段本身就是 long[]**
（引用键 map 用索引编码链指针的内存优化）。2^k+1 长度（9/17/33/65/129）是其容量指纹。

---

## §3 面 (c)：A-080(6,128) vs B-092(6,114) 逐区块差分 → 判负（零新热点）

31 个达标类（|Δ|≥0.05KB/区块 且 B 侧 ≥40KB）**全部负向**，零正回归。前向收割弧线全部可见：

| 类 | Δ 每区块 | 来源批次 |
|---|---|---|
| `[B` | −22.9KB | R8/R18 SWMR 紧缩+壳共享、R5 包缓存驱逐 |
| `[S` | −11.0KB | R5 批142 ShortList 惰性化 |
| `[D` | −6.8KB | R20 批160 NoiseChunk 停机释放 |
| `[Object` | −5.8KB | R16/R17 shape 共享等 |
| Short2ShortOpenHashMap | −4.0KB | R5 批142 |
| AABB | −3.4KB | R16/R17 |
| PalettedContainer$Data | −3.4KB | R7 规范 Data 共享 |
| `[J` | −3.0KB | （内容制式，无代码变更） |
| HashMap$Node | −2.7KB | R20 批161 懒 map 等 |
| SWMRNibbleArray | −2.3KB | R8/R18 |
| shape 族（Array/Cached/BitSet/DoubleArrayList） | −2.3~−1.0KB | R16/R17 |
| BlockPos | −1.3KB | 模板解耦连带 |
| StructureBlockInfo | −1.3KB | R10 |
| SimpleBitStorage | −0.8KB | R7 零位共享 |
| ……其余 16 行同向 | | |

---

## §4 工程与判例

### 4.1 控制探针（金标准，批160 判例的再次应用）

`O2OControl`（F:\TEMP，不入库）：以 gradle 缓存 fastutil-8.5.18.jar 构造 size=0/3/38 三尺寸
（含扩容+2 删除），`HotSpotDiagnosticMXBean.dumpHeap` 自产控制转储；census 在其上复核
读出 `{0,3,38}` = CONTROL-OK 才允许跑真实转储。

### 4.2 判例入库（四条）

1. **hprof 实例 blob 序 = CLASS_DUMP 字段表序（声明序），非内存布局序**：字段解码偏移 =
   前序声明字段宽度之和（1B 字段使后续错位，批160 滑窗判例的精确化），且 **oop 宽 = idSize**
   （服务器转储 idSize=8 时 `data@0` 后 `bits` 在 @8 不在 @4）——本工具两度踩坑两度修正，
   控制探针全部当场抓住；另外同一 dump 中 CLASS_DUMP 可重复出现（SBS 出现 5 次），
   字段表须去重使用。
2. **字段级采样优于猜测**：`-DsampleCls`/`-DsampleLen`/`-DsbsLen` 把"归因到持有者类"细化到
   "归因到持有者字段"——`Reference2ObjectLinkedOpenHashMap.link` 是 long[] 这一事实 10 分钟排障
   vs 无限猜测；长度指纹（2 的幂 / 2^k+1 / ceil 公式档）是结构族的快速识别器。
3. **vanilla SBS 存储长 = ceil(size/(64/bits))，不跨 long 边界**：长度直方图因此是 palette 位宽的
   完备指纹（256/342/37/1-2 档），**320 档缺席 = 布局公式恒等证明**——R13 的尺寸分类法获得
   封闭式解释，本轮 5-bit 新队列（342×7,464）据此一步定性为内容成熟化而非表示异常。
4. **审计轮的价值形态**：三面全部判负时，交付物 = 把判负理由升级为硬证据（空率实测 0.6%、
   字段级持有者、公式恒等）；"无收割面"结论的证据等级应高于"有收割面"立项（前者终结方向，
   后者只是开始）。

### 4.3 工程记录

- Git Bash 路径转录：gradle modules-2 组目录是 `it.unimi.dsi`（带点），转成 `it/unimi/dsi` 会
  静默失败；`find` 输出直接复制最稳。
- `python3` 不可用（exit 49），用 `python`（既有判例维持）。
- O2O 8.5.18 运行时字段表（CLASS_DUMP 实测）：`key, value, mask, containsNullKey, n, maxFill,
  minN, size, f, entries, keys, values`——与 sources jar 提取有出入（minN/f），**CLASS_DUMP 是
  唯一权威**。

## §5 结论

0.92.0 基线上残余内存 = **内容主导 + 注册表/调度常量 + 用户终局域**，三面证据链闭合：

- (a) O2O/CompoundTag：空率 0.6%，中位 2-3 条真实内容，Moonrise 已自带共享空表示；
- (b) [J：palette/heightmap/biome 载荷 139.5MB 处于 R13 判负域 + 用户终局，BitSet/光引擎/传播器
  为固定基础设施，残余 26.2MB 持有者级闭合（自有 PapoPosTypeSet 是计划刻真内容）；
- (c) 逐区块差分零正回归，0.80→0.92 收割弧线类粒度单调。

**内存主线状态：0.80.0→0.92.0 = 每区块 189.5→101.4KB（−46.5%），总堆 −46.5%（配对同窗）。
"结果一致"约束下的结构收割面已穷尽（R11 三轴收敛 + R19 直对 + R20 盲区修复 + R21 状态级复核）。**

交付物：本报告、`PapoO2OStateCensus.java`（工具入库）、optimizations.md 批次162、README 索引、
记忆更新。版本保持 0.92.0，无补丁/罐变更。
