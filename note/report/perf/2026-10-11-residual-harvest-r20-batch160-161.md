# R20（批次160/161）：R19 收敛判定的对抗复核闭合与残余收割

日期：2026-10-10 ~ 2026-10-11　轮次：R20　版本：0.91.0 → 0.92.0　补丁：0276 → 0278

## 0. 摘要

R19 对堆残余做了逐簇正面判负并宣告收敛。本轮以对抗立场复核该判定的两个弱点，
两者均被推翻为**可收割面**并完成收割：

| 面 | R19 判定 | R20 复核结论 | 收割 |
|---|---|---|---|
| [D 12.3MB 仅 1.5MB 实例可达 | "瞬态+弱归因维持" | 仪器盲区：10.58MB 是 **318 个 pre-carver 停机 ProtoChunk** 持有的 NoiseChunk 树（插值切片） | 批次160 sweep 释放 |
| O2O 45,899 张弱归因 | 内容/真实状态 | 13,810 张为 ChunkAccess.blockEntities 急切分配，**~8.9k 永空**（含 4,947 个 ImposterProtoChunk 死字段） | 批次161 懒分配 |

配对浸泡（12 bots / vd10 / 12 分钟驻留）与类级 census 验证如下（第 4 节）。

## 1. 对抗复核：[D 归因洞的逐层闭合（R19 弱点 b）

### 1.1 静态字段假设：证伪

新工具 `benchmark/src/papo/bot/PapoStaticRefCensus.java`（HPROF 行走骨架复制自
SWMRStateCensus/ShellRefAttr）解析全部 CLASS_DUMP 静态字段引用并与 [D/[B/[J 目标
数组交叉。B-091 收官转储（memsoak-1791617273408）结果：

```
static-attributed total: 102 arrays / 169,923 bytes
   65560 bytes      1 arrs  com.sun.org.apache.xerces.internal.util.XMLChar
   65560 bytes      1 arrs  io.netty.util.internal.StringUtil
```

全部类静态字段合计仅 0.17MB（两个 64KB 常量表）——**静态持有假设出局**。

### 1.2 根记录直指：出局

根记录（JNI global 420 / monitor 829 / sticky class 4,121 / thread 87）无一直指任何
[D/[B/[J；本格式（JDK21 GC.heap_dump）不发射 STACK_LOCAL 记录。转储口径核对：
`jcmd GC.heap_dump`（无 -all）= 活集转储，且直方图 [D 计数 37,294 与转储**逐个相等**
——浮动垃圾解释同时出局，缺口是活的。

### 1.3 仪器盲区：类型化引用数组的元素边

HeapRefAttr 的对象数组扫描只读 `[Ljava.lang.Object;` 与 `[Ljava.util.HashMap$Node;`
两类元素——**其他类型化引用数组（含 [[D 外层）的元素边被整体跳过**，其内层 [D 成为
"无主"。给 census 加全类型对象数组元素边（纯 id 交叉，不依赖类名匹配）：

```
objarray-elem total: ->D 25440/10583040B  ->B 556/501561B  ->J 94/464072B
    10583040 D(10583040B/25440) B(0B/0) J(0B/0)  outer=[[D
```

**10.58MB 全额落位**：25,440 个内层 double[] 全部是 [[D 外层的元素。对账：
10,583,040（[[D 元素）+ 169,923（静态）+ 1,333,677（实例字段，与 HeapRefAttr 的
~1.5MB 吻合）+ 0（根）≈ 12.09MB 全额闭合。

### 1.4 持有链：滑动窗口 + 大端修复

外层 [[D（5,088 个）的持有者归因两次踩坑后闭合：
- 对齐窗口扫描漏非对齐引用（hprof 实例 blob 按声明序序列化，1B 字段可使引用错位）
  → 改逐字节滑动；
- 8 字节 id 解码误写小端 → HPROF 大端，修复后对照组（NoiseInterpolator 探针）命中
  `Probe<-arr:[Ljava.lang.Object;  2544 refs` 验证扫描器正确。

最终持有链（每环分毫吻合）：

```
[[D outer arrays: 5088
   5088 refs  [[D]<-NoiseChunk$NoiseInterpolator     （每 interpolator 2 切片）
  Probe(NoiseChunk) instances: 318
    3180 refs  Probe<-NoiseChunk$FlatCache
    2544 refs  Probe<-NoiseChunk$NoiseInterpolator
     318 refs  Probe<-ProtoChunk                     ← 持有者是 ChunkAccess.noiseChunk
     318 refs  Probe<-Aquifer$NoiseBasedAquifer
```

2,544 interpolator × 2 切片 = 5,088 外层，25,440 内层 = 5,088 × 5。真身：**318 个
NoiseChunk 树**（插值切片 10.58MB + FlatCache/CacheOnce/Cache2D/BlendAlpha/... ≈
合计 ~11.4MB）。

### 1.5 判定：非瞬态，是停机泄漏面

批次143（R5）的释放钩子挂在 CARVERS 步骤出口（最后一个读点）。pre-carver 状态环
（等邻居依赖而停机、或取消后停机的 chunk）**永远到不了该钩子**。dwell 静止期 12 分钟
后仍有 318 个活着——驻留随 pre-carver 环周长（玩家边缘长度）扩展，不随加载面积。

## 2. 批次160：pre-carver 停机 NoiseChunk 的周期释放

### 2.1 恒等证明链（行为不变的论证）

释放后若 chunk 恢复生成，`getOrCreateNoiseChunk` 会用**当步闭包**重建。重建恒等性：

1. **跨步读面盘点**（getOrCreateNoiseChunk 读者全集 = NoiseBasedChunkGenerator 四处）：
   - `preliminarySurfaceLevel`（SurfaceRules.Context 唯一读点）——纯记忆化函数，
     `preliminarySurfaceLevel.compute(SinglePointContext)` 由噪声路由器决定，
     与 doFill 填充状态无关；
   - aquifer（buildSurface/applyCarvers 读）——确定性记忆化噪声查询；
   - 插值切片——**仅 doFill 消费**：要么已在原实例上跑完，要么在新实例上同样填充。
2. **闭包差异消除**：各步 `createNoiseChunk` 差异仅 Beardifier（结构种子确定；后步
   区域半径 ⊇ 前步，按 chunk 相交过滤结果不变）与 Blender——`Blender.of(region)` 的
   输出与区域半径无关（`isOldChunkAround` 走 chunkMap 的固定 ±7 环，BlendingData
   收集环固定），任意步闭包产出等价树。
3. **竞态安全**：普通引用写不会伤害在途步骤（步骤持局部引用）；未启动的步骤按需重建。
   与批次143 的"步骤严格串行"论证同构，扩展了上述读等价。

### 2.2 实现

- `ChunkAccess.papoReleaseParkedNoiseChunk()`：null 即 false，释放即 true（幂等汇报）。
- `ServerChunkCache.papoReleaseParkedNoiseChunks()`：每 `papo.noiseParkSweepTicks`
  （默认 2048，~102s；≤0 禁用为诊断腿，对齐批次141 惯例）主线程扫全部 holder，
  `getCurrentChunk() instanceof ProtoChunk && !(instanceof ImposterProtoChunk)` 即释放
  （ImposterProtoChunk 包装已完成 LevelChunk，自有继承字段从不写入）。
- 单测 `PapoNoiseChunkParkReleaseTest`（4 项，注册进 PapoRegressionTestSuite）：
  释放幂等/重建路由到当步闭包/停机缓存稳定/循环停放再释放/与批次143 钩子共用字段。

## 3. 批次161：blockEntities 懒分配（R19 弱点 a 收割）

R20 复核把 O2O 弱归因拆到持有者：CompoundTag 30,688（NBT 内容，真）、
MapEntrySet 17,809（内部视图）、NewChunkHolder 17,726 + ChunkEntitySlices 9,894
（R2O，Moonrise 真实状态）——以及 **LevelChunk 4,947 + ImposterProtoChunk 4,947 +
ProtoChunk 3,916**（O2O，1:1 于 chunk 家族）。字段定位：`ChunkAccess.java:79`
`public final Map<BlockPos, BlockEntity> blockEntities = new Object2ObjectOpenHashMap<>()`
——每个 ChunkAccess 构造即付 ~210B（表头 + Object[16] 键 + Object[16] 值），自然地形
绝大多数 chunk 终生无方块实体；ImposterProtoChunk 的继承字段更被证明从不触碰
（其全部 BE 访问器委托包装的 LevelChunk）。计 13,810 张活图中 ~8.9k 永空 ≈ 1.9MB。

### 3.1 触点全集（19 处，逐一改写）

- ChunkAccess：字段改 `@Nullable public Map<...> blockEntities`；新增
  `papoBlockEntitiesView()`（null → 不可变空视图）与 `papoBlockEntitiesWritable()`
  （首个变更器分配）；getBlockEntitiesPos/setBlockEntityNbt 两读点走视图。
- LevelChunk（8）：合并查重 keySet / getBlockEntity 读 / removed 标记清理 remove /
  addAndRegister put（唯一分配点）/ removeBlockEntity 条件 remove / getBlockEntities
  getter（调用者全集只读：ChunkHolder/ChunkMap/ServerLevel/EndDragonFight/
  TransportItemsBetweenContainers/LevelDebugSynchronizers/包数据预尺寸——均
  size/keySet/values/entrySet 迭代）/ clearAll / registerAll。
- ProtoChunk（4）：put / get / getter / remove。
- CraftChunk（2）+ CraftWorld（1）：直读字段改视图。
- 单测 `PapoLazyBlockEntitiesTest`（5 项）：null 即空语义/空视图写穿透即抛
  （响亮金丝雀，钉死只读契约）/分配一次并活视图/null 上 remove 无操作/移除后保留
  分配（与旧空图同形）。

（同轮观察入档：`pendingBlockEntities` 为 java.util.HashMap——表懒分配，空图仅 ~48B，
不值得动。）

## 4. 度量与验证

### 4.1 配对浸泡（同窗同制）

`memsoak-r20final.sh`（12 bots / 散开 150s / 驻留 180s / 测量 120s / B 腿转储）：

| 腿 | heapUsedKb | LevelChunk | logErrors | exit |
|---|---|---|---|---|
| A-080 | 1,159,239 | 6,085 | 0 | 0 |
| B-092 | 620,102 | 6,114 | 0 | 0 |

配对门 |ΔLevelChunk| = 0.48% < 1% ✓。**同窗双口径**：raw −46.5%；每区块
190.4 → 101.4 KB = **−46.7%**。注：R19 的 B-091 腿落在 4,574 区块的低载窗（固定堆
摊薄更少、每区块被抬高），其 −35.9% 为保守方向；本对两腿同在 ~6.1k 区块制式，
−46.7% 是 campaign 至 0.92.0 的干净收官数。

### 4.2 类级 census（机器无关主口径，B-091 → B-092）

| 指标 | B-091（0.91.0） | B-092（0.92.0） | 变化 |
|---|---|---|---|
| [D 直方图 | 37,294 / 11.79MB | 8,846 / 0.41MB | **−96.5% 字节** |
| [[D 外层数组（census） | 5,088 | **0** | 归零 |
| NoiseChunk 探针实例 | 318 | **0** | 归零 |
| 对象数组元素边 →[D | 25,440 / 10.58MB | **0** | 归零 |
| O2O 总数（直方图） | 45,899 | 32,583 | −13,316 |
| O2O ← LevelChunk | 4,947 | **462** | 仅真 BE 携带者（7.6%） |
| O2O ← ImposterProtoChunk | 4,947 | **0** | 死字段消灭 |
| O2O ← ProtoChunk | 3,916 | 23 | 仅转储瞬间在途 |
| O2O ← CompoundTag | 30,688 | 30,452 | 内容态不变 ✓ |

（[J 涨至 254,535/175.4MB 为区块数窗口 4,574→6,114 的加载制式差，非行为变化；
census 类级口径不受影响。转储 [D 8,844 vs 直方图 8,846 的 ±2 为取数时点微差。）

### 4.3 链验证

`rebuildPatches --rerun-tasks --no-configuration-cache` EXIT=0（0277/0278 生成）→
`applyPatches` 独立调用 EXIT=0（重放哈希 852c8d36/6dfa19f4）→ `compileJava
--rerun-tasks` BUILD SUCCESSFUL → PapoRegressionTestSuite 全绿（新增 9 项）→
`createPapoJar` 出罐 Papo-1.21.11-0.92.0.jar。

工程事故记录：本轮 applyPatches 两度失败于缓存层——(a) paperweight
`minecraft-sources/fastutil-8.5.18-sources.jar` 为一年前的**截断缓存**（zip END
header 丢失，`jar tf` 可过而 zipfs 不过——indexLibraryFiles 首次重执行才暴露），
且 gradle 构建缓存 FROM-CACHE 会还原损坏文件，需手动 curl 断点续传取整（22.6MB）
后 `--rerun-tasks` 绕过；(b) `downloadMappings` 网络截断（8.34/8.68MB），普通重跑
利用 UP-TO-DATE 通过。两故障同源于本机网络对大文件的单连接截断。

## 5. 判例

1. **归因闭合三口径**：静态/根直指/实例字段三者合计仍缺口时，先怀疑仪器再怀疑世界
   ——本轮缺口 97% 是对象数组元素边的系统性盲区（类型化引用数组不在扫描白名单）。
2. **HPROF 滑窗扫描两坑**：引用可因 1B 字段错位（须逐字节滑窗）；idAt 必须与 readId
   同端序（大端）。对照组探针（必然被持有的类）是扫描器的金标准。
3. **释放语义的恒等三件套**：读面盘点（哪些状态被跨步读）→ 闭包差异消除（重建输入
   是否步骤无关）→ 竞态无害化（在途持局部引用）。批次143"最后读者后释放"是特例，
   本轮"停机时释放+按需重建"是通式，前提是三件套闭合。
4. **继承死字段**：包装类（ImposterProtoChunk）继承的急切分配字段是双倍静默税——
   懒字段模式一次性消灭三代持有者（Level/Proto/Imposter）的空图成本。

## 6. 交付物清单

- 补丁 0277（批次160）/ 0278（批次161）；src/main CraftChunk/CraftWorld 直改；
  单测 2 个 9 项入 PapoRegressionTestSuite；
- 工具 `benchmark/src/papo/bot/PapoStaticRefCensus.java`（静态交叉+根直指+对象数组
  元素边+滑动窗口持有者归因+类探针，-Dprobe= 可换探针类）；
- `benchmark/memsoak-r20final.sh`；本报告；optimizations.md / release/0.92.0.md /
  perf README 索引；版本 0.92.0。
