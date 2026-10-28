# R11（批次151）：残余画像常驻性审计与系统性收敛论证（0.88.0 · 审计轮，无版本变更）

- 日期：2026-10-10
- 基线：0.88.0 树；B-088 驻留转储（memsoak-1791582031387，6,046 区块，757,348KB）
  与 **B-088 无玩家稳态转储**（memsoak-1791582795546，区块尽卸，243,125KB）同
  二进制差分——无玩家腿是干净的常驻性示差器（区块/玩家全灭后仍存活 = 永久集）。
- 新工具：`papo.bot.ShellRefAttr`（壳实例持有链归因器）——HeapRefAttr 只归因基
  本类型数组与 HashMap$Node，本轮需要"某类的 N 个存活实例由谁持有"；两遍流式
  扫描（HPROF 无拓扑序，收集与扫描必须分遍），与直方图实例数逐位对账验证。

## 0. 结论

**残余非内容簇全数判负：不存在下一个既非内容、又非注册表/引擎固定成本、且
可证明安全的角度。** 指令点名的三个簇持有链归因如下（无玩家腿，291,843/
281,720/29,813 实例全数存活）：

| 簇 | 持有者（ShellRefAttr 实测） | 判定 |
|---|---|---|
| DoubleArrayList 291,843（7.0MB 壳） | ArrayVoxelShape 424,515 引用（xs/ys/zs，~2.06 新建/形状）+ PerlinNoise 188 + NormalNoise 71 | **形状族载荷**：每注册方块状态碰撞/遮挡形状缓存（与 R10 已判负的 AABB 141k 族同宿主），注册表界恒定成本 |
| [J 281,720（15.9MB） | BitSet 词位 140,109 + CachedShapeData 140,060 + 光引擎缓冲（Sky/Block StarLightEngine 0.39MB）+ 传播器/队列机制（NoResizeFIFODeque/LevelMap/MpmcArrayQueue）+ fastutil 长键映射状态数组 | **形状族词位 + 引擎固定操作缓冲**（配置定容，非内容定容） |
| Object2ObjectOpenHashMap 29,813（1.9MB） | **net.minecraft.nbt.CompoundTag 29,525**（NBT 底衬映射） | **数据包/注册表 NBT 内容**：无玩家仍存活 = 注册表内容（配方/进度/战利品表/世界生成 JSON 的 NBT 形态）；dwell 增量 18,880 随区块生死 = 方块实体等区块内容 |
| （连带）BitSet 140,109 | BitSetDiscreteVoxelShape 140,060 + RegionBitmap 46 | 形状族 + 区域文件分配位图 |
| （连带）Object2ObjectArrayMap 37,741 | DFU/序列化 codec 树（MapCodec$2 7,884 + FieldDecoder/Encoder 各 4,056 + RecordCodecBuilder 族）+ AttributeInstance 原型 4,656 | DFU schema / 注册表编解码结构 |
| （连带）StringTag 63,844 | Object[]（CompoundTag 映射表元素） | 注册表 NBT 内容 |
| （连带）ArrayList 150,919 | **CachedToAABBs 141,156** + 组件/命令/codec 杂项 | 形状族载荷 + JVM/注册表 |
| （连带）HashMap$Node 133,570 | Node 表归属：HashMap 103,876/LinkedHashMap 25,227；键值对 Top 全为 **String→DFU Schema λ（25,464+9,093）、String→NamedType 9,670、ResourceKey/Identifier→Holder$Reference 9,448×2、NBTMapType 链 6,912** | DFU schema 树 + 注册表 Holder 映射 + JVM |

## 1. 永久集分解（243.1MB 无玩家稳态）

按上述归因，永久集 = ①形状族（AABB 22.0 + ArrayVoxelShape 14.7 + 双 7.8 +
CachedToAABBs 6.8 + VoxelShape[] 2.9 + BitSet 3.4 + DoubleArrayList 7.0 + [D
2.8 + ArrayList 大部 ≈ **81MB**，注册表界）②DFU/dataconverter schema 与 codec
树（Node 8.5 + Object2ObjectArrayMap 1.5 + Const 34,273 + NamedType 等 ≈
**15MB**）③注册表 NBT 内容（StringTag 3.8 + CompoundTag 映射 3.1 + Object[]
部分 + String 值 [B 12.4 ≈ **25MB**）④JVM/JFR/netty/jna/jopt/yaml/jar-zip
（Class 4.0 + zip 9.0 + MemberName 1.2 + … ≈ **20MB**）⑤引擎固定操作缓冲（光
引擎/传播器/队列/长键映射 ≈ **16MB**，配置定容）⑥杂项（joptsimple/反射/安
全框架等）。**全部由"JVM + vanilla 注册表内容 + 数据包 + 服务器配置"定容，
与区块数、玩家数、运行时长全部无关。**

## 2. 区块驱动集分解（驻留 757.3 − 无玩家 243.1 = 514.2MB，6,046 区块）

- **palette 位存储**（SimpleBitStorage [J ≈ 135MB）：方块/biome 调色板内容本
  体——逐格身份语义，等价性红线（改表示=改盘格式/网络格式，超出"结果一致"
  授权）。
- **光照 nibble**（SWMRNibbleArray [B 61.4MB，均匀表示已是批148 成果）：真实
  光照数据，内容。
- **节/容器/tick 壳层**：LevelChunkSection 318,574 + ShortList 318,574 +
  PalettedContainer 637,149 + Data 149,468 + LinearPalette 141,465——批
  142（tick 惰性）/147（单值 Data 共享）/148（uniform nibble）/149（常量节规
  范化）四轮已最小化；剩余为真实多值节结构。
- **heightmap**：本轮新证据——LevelChunk 晋升只保留 `FULL.heightmapsAfter()`
  = FINAL 四型（LevelChunk.java:295 逐条过滤），6,046×4×296B ≈ 7.2MB，
  **vanilla 本就修剪，无过度驻留**（否决了本轮审计前的 WG 型残留猜想）。
- **HashMap 机制**：dwell 113,399 − 无玩家 4,279 = +109,120（18.0/块：方块实
  体/刻/POI/heightmap 映射）≈ 25.7MB 含表与节点——内容邻近机制，已随宿主。
- **POI/方块实体/NBT/刻内容**（SavedTick 119,018、[S 65,159 tick 数组、
  Reference2IntOpenHashMap 39,808 调色板 id 映射等）：内容。
- **调度状态**：NewChunkHolder 10,358 / ChunkCompletion 86,176（2.1MB，
  8.3/holder）随 holder 生死，界=已加载集；moonrise 调度内部件动辄稳定性风
  险，2.1MB 收益不成比例，审慎判负。
- **模板 TTL 热窗** ≈ 10MB（批150，稳态全释放已在无玩家腿证实：模板簇零行）。
- **HashMap 节/表、字符串**：随区块的字符串已批146 判负（去重三准入设计全
  败）；机制部分随宿主。

## 3. 收敛论证

内存主线 0.80.0→0.88.0（十轮）= 1,154.3→757.3MB（−397.0MB/−34.4%），每区块
摊销 189.5→125.2KB/块。残余画像按三条增长轴封闭：

1. **时间轴（无界增长）**：候选已穷尽——模板缓存（批150 关闭，无玩家稳态全
   释放铁证）、区块包缓存（批141 TTL）、其余永久集跨 dwell/无玩家恒定
   243.1MB（同会话同二进制两腿实测）。无第三类无界簇：永久集每个簇的持有者
   均为注册表/DFU/JVM/固定缓冲（§0 表），无随时间累积的容器。
2. **区块轴**：每区块 125KB 中，palette 位存储与光照（≈32KB+10KB/块）为内容
   红线；结构壳层经批142-149 四轮压缩后剩余为多值节必要结构；heightmap/刻/
   POI/BE 为内容或 vanilla 已修剪。
3. **玩家轴**：12 bot 的贡献全在区块轴内（MemSoak.cls 各内容类随区块而非玩
   家缩放；无玩家腿与驻留腿的差即区块+玩家全体）。

因此后续内存收益只可能来自（a）改写内容表示（palette/光照编码——违反恒等语
义红线）或（b）vanilla 注册表结构重铸（DFU/形状缓存——注册表语义红线），均
在授权红线之外。**内存占用主线在"结果一致"约束下收敛。**

## 4. 验证纪律（审计轮适配）

- 工具正确性：ShellRefAttr 四目标实例数与 jcmd 直方图逐位一致（291,843/
  29,813/37,741/140,109）；HeapRefAttr 同转储解析 EXIT=0 交叉验证；工具开发
  期修复三处自身缺陷（CLASS_DUMP 实例字段误跳值字节 / readIdAt 8 字节端序反
  / LOAD_CLASS serial 按 id 误读），每处均有定位证据链。
- 服务端零变更：无补丁、无版本 bump（审计轮惯例同批132-140）；批150 全链门
  （counts ALL OK/compile/test 8,952/jar javap）在本会话内仍然成立且其后无
  代码改动。
- 配对浸泡（本会话，同二进制 B-088）：驻留腿 757,348KB（6,046 块，门绿）vs
  无玩家腿 243,125KB（门绿）；A-087 无玩家对照 271,526KB（模板簇差 −28.4MB
  已入批150 报告）。

## 5. 勘误（批次153，R13）：palette 簇判负理由修正 + 位宽实证

原文 §2 称 palette 位存储"改表示=改盘格式/网络格式，超出'结果一致'授权"——
**括号理由技术上不成立**：内存表示可独立于盘/网络格式改写（批148 SWMR
uniform 表示、批149 常量节正是先例，序列化经编解码器恒等）。判负结论维持，
但修正后的理由须是实证形式：

HeapRefAttr 对 B-088 转储的 SimpleBitStorage 逐数组尺寸直方图（177,575 个归
因数组全量）：

| 数组长(long 数) | 个数 | 语义 | 字节 |
|---|---|---|---|
| 1 | 58,382 | biome 容器（64 格×1 bit，2 值） | ~0.9KB/个合计 |
| 2 | 32,945 | biome 容器（64 格×2 bit，3-4 值） | 同上 |
| 37 | 28,141 | heightmap（256 格×9 bit；6,046×4 型=24,184+protos ✓） | 7.2MB |
| **256** | **49,990** | **方块 palette（4,096 格×4 bit，9-16 值）** | **≈103MB** |
| 342/410/456 | 8k | 非区块杂项 | 小 |

- **方块 palette 质量全部集中于 4-bit 档**（3-bit/5-bit 档几乎为零）：即这
  103MB 全部是 9-16 个不同方块态的真混合节（表面/结构/洞穴层）。
- 判负三支柱：① 该表示对 9-16 值定宽随机访问已贴近实用下界——进一步压缩
  需变长编码（算术/哈夫曼按格子分布熵 ~1.5-2.5 bit 可省约半），但
  getBlockState 是全服最热路径，变长解码的逐 tick 代价直接踩"交互体验一
  致"红线；② 优势值+例外补丁表示的盈亏算术为负——观测成分下例外数
  ~800+/节（2B/条 ≈1.6KB+）≈ 现行 2KB 定宽；③ 100% 单值档已由批147/149
  收割（19.2% 节），次档即为真混合。
- biome 容器（1-2 long）与 heightmap（37 long）在批148/149 与 vanilla 修剪
  后已是各自下界形态。

判例：**"改表示必改格式"是伪约束（内存表示层独立可改，148/149 先例）；
判负理由必须是正面的（熵下界 + 热路径 CPU 交换 + 盈亏算术），不能是负面的
（"不能改"）**。
