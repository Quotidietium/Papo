# R5 轮4（批次144）：HPROF 持有链归因——HashMap$Node 簇与 byte[] 成分逐项确证，R5 内存主线正式收束

- 日期：2026-10-09
- 版本：0.83.0 保持（分析轮，无行为变更，无版本递进）
- 前置：批次141（TTL 驱逐）、142（ShortList 惰性）、143（NoiseChunk 释放）
- 指令：对 HashMap$Node 簇（~118/区块，持有者未归因）与 [B 成分做持有链归因，寻找
  下一个红线内可安全回收的区块缩放驻留；若逐项确证均为活数据或红线受限，则以判例
  归档正式收束 R5 内存主线。

## 0. 摘要

构建 HPROF 堆转储归因工具链（MemSoakVerify `-Dmemsoak.dump=true` + 新增
HeapRefAttr 三遍流式解析器），对 0.83.0 绿门浸泡（12 bot、径向 150s + 驻留 180s +
测量点 dwell+120s）在测量时刻抓取 982MB 全量堆转储并完成引用链归因：

- **byte[]（84.9MB，440,922 个，99.9% 归因成功）**：70.8% 为 Starlight
  `SWMRNibbleArray` 光照半字节表（29,322 × 2048B = 60.0MB，逐 section 活数据，
  光照正确性红线）；14.7% String 背景数组（活数据）；~12% jar/zip 读缓存
  （boot 常量，非区块缩放）。
- **HashMap$Node（363,420 个，表归属 0 未归因）**：区块缩放成分 =
  NoiseChunk 密度函数包装簇（~118.8k，31 日内已被批次143/0268 界定为 carvers 前
  活环）；其余为玩家缩放的进度数据（advancements ~83.4k，红线性命数据）、boot
  常量 DFU schema（~54k）、注册表 Holder 缓存（~18.9k）——全部活数据。
- **结论**：逐项确证无下一个红线内可安全回收的区块缩放驻留 → 依指令以判例归档，
  **R5 内存主线正式收束**。R5 四轮累计（0.80.0 → 0.83.0，同场景配对腿 6,081 vs
  6,112 区块，门全绿）：**1,154,349 → 873,548 KB = −280.8MB（−24.3%）**。

## 1. 工具链

### 1.1 采集：MemSoakVerify 转储选项

`measure()` 常规 GC.run ×2 → GC.heap_info → GC.class_histogram 之后，若
`-Dmemsoak.dump=true` 则追加 `GC.heap_dump <outDir>/heap.hprof`（实测 982MB、
~26s）。转储发生在测量协议的同一稳定点，与批次141-143 的头条数字可直接对照。

### 1.2 解析：HeapRefAttr（benchmark/src/papo/bot/HeapRefAttr.java）

三遍流式（不整堆载入，982MB / 10s / 零暂存大对象），自实现开放寻址 long 集合/
映射避免装箱：

- **P1 类元**：STRING/LOAD_CLASS（顶层记录）+ 段内 CLASS_DUMP——提取
  java.util.HashMap$Node 字段布局（hash/next/key/value，key/value 偏移）与
  数组类名表。
- **P2 数据**：byte[] 清单（id→len）；Node 实例 key/value 抽取（并行 long[]，
  363k 对）；`HashMap$Node[]` 表数组登记（id→非空元素数）；key/value 目标
  id→类解析（第二遍，因单遍内 target 集不完备）。
- **P3 引用者扫描**：逐实例字段 blob 按 idSize 步长扫 id——命中 byte[] 记
  bytesByReferrer；命中 Node[] 表记 tableOwner（**表归属而非 next 链**：沿 next
  链会把前驱节点误当持有者）。Node 数按表内元素数归入 owner，键值类对入
  nodeKeyValPairs。

**完整性保证**：段内子记录按各自定长结构推进并对账 `left`——未知子记录或越界
即刻报错（fail-loud），杜绝静默错位归因（见 §8 判例）。

HPROF 格式要点（实采 JDK21 jcmd 转储验证，多份规格文本与 HotSpot 实现不一致）：

1. 堆数据**不在顶层**：全部嵌在 HEAP_DUMP/HEAP_DUMP_SEGMENT（0x0C/0x1C）体内，
   子记录无 time/len 头，靠定长结构推进；
2. **根子记录编号取 HotSpot heapDumper.cpp 实写值**（较旧规格文本整体偏移）：
   JNI_GLOBAL=0x01（id+id）、JNI_LOCAL=0x02 / JAVA_FRAME=0x03 /
   THREAD_OBJECT=0x08（id+u4+u4）、NATIVE_STACK=0x04 / THREAD_BLOCK=0x06 /
   MONITOR_USED=0x07（id+u4）、STICKY_CLASS=0x05 / UNKNOWN=0xFF（仅 id）；
3. CLASS_DUMP 头后为 常量池（u2 计数 + {u2 index, u1 type, value}）→ 静态字段
   （u2 计数 + {id name, u1 type, value}）→ 实例字段（u2 计数 + {id name,
   u1 type}），三段的 value 字节数必须实际消费（计账不读流=错位根源之一）。

## 2. 采集与门

单腿浸泡（0.83.0 jar，Papo-1.21.11-0.83.0.jar）：12 spectator bot 径向 150s
（0.25 block/tick）+ 驻留 180s + 测量点 dwell+120s，服务端日志零 ERROR/Exception
（isBenignCloseRace 过滤后），退出码 0，转储 982MB。伴随日志
`benchmark/results/memsoak-dump-0.83.0.log`。

## 3. byte[] 归因（84,881,512 B / 440,922 个；attributed 84,155,051 B（99.1%））

| 引用者类 | bytes | 数组数 | 占比 | 判定 |
|---|---:|---:|---:|---|
| ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray | 60,051,456 | 29,322 | 70.8% | 逐 section 光照数据（sky/block light 半字节表 2048B/个），区块生命周期活数据；光照正确性红线，**不可回收** |
| java.lang.String | 12,435,288 | 385,950 | 14.7% | String 值本体（键名/路径/翻译/注册表名），活数据 |
| java.util.zip.ZipFile$Source + jdk.nio.zipfs.* | 10,248,236 | 18,733 | 12.1% | 服务端 jar/数据包 zip 读缓存，boot 常量，非区块缩放 |
| ImprovedNoise | 160,768 | 628 | 0.2% | 噪声置换表（构造期种子派生），boot 常量 |
| PapoSharedWireMemo | 156,639 | 44 | 0.2% | 网络包缓存共享备忘（0267 TTL 管辖内），已界定 |
| 其余（cert/reflect/DFU/RegionFile 等长尾） | ~1.8MB | ~7k | 2.0% | 活数据长尾 |
| **未归因**（静态字段/局部/仅数组持有） | 726,461 | 732 | 0.9% | — |

**结论**：byte[] 驻留的主体是光照数据本体（活）与 boot 常量；无可回收项。
（对照：批次141 前 byte[] 曾达 158MB+，其中区块包缓存 41MB 已被 TTL 驱逐回收，
本表为驱逐后残差画像。）

## 4. HashMap$Node 归因（363,420 个；表归属未归因=0）

byOwner 按表持有实例类分组是同义反复（表由 HashMap 自身 `table` 字段持有：
HashMap 251,198 / LinkedHashMap 41,636 / 长尾 ~11.7k），语义层由键值类对承载：

| 语义簇 | 键值类对（Top 成分） | 节点数 | 缩放轴 | 判定 |
|---|---|---:|---|---|
| NoiseChunk 密度函数包装 | HolderHolder→MulOrAdd 14,451、MulOrAdd→MulOrAdd 15,990、Ap2→Ap2 12,601、HolderHolder→Constant 12,547、HolderHolder→Noise 8,005、Marker→NoiseInterpolator/FlatCache 4,927 等 | **~118,755** | 区块（worldgen 活环） | **已被 0268 界定**：carvers 前活环（残留 ~372 NoiseChunk），carvers 后即释放；轮3 实证该簇 −65.5% |
| 玩家进度（advancements） | String→CriterionProgress 41,724、CriterionTrigger$Listener→Object 41,638 | ~83,362 | 玩家数 | 存档性命中数据，红线性（用户体验等同），**不可回收** |
| DFU schema | String→Schemaλ 34,557、String→NamedType 9,670、NBTMapType→NBTMapType 6,912、String→RecursivePoint 2,880 | ~54,019 | boot 常量 | 数据修复器类型表，启动期构建共享，非区块缩放 |
| 注册表 Holder | ResourceKey→Holder$Reference 9,448、Identifier→Holder$Reference 9,448 | ~18,896 | boot/内容 | 注册表绑定缓存，活数据 |
| 杂项 | String→String 4,821、V704$1 等长尾 | ~88k | — | 长尾散布，无单一可回收簇 |

**结论**：区块缩放成分（DF 包装簇）已被轮3 收走主体并界定残差边界；其余簇均
为玩家缩放/boot 常量活数据。**无下一个红线内可安全回收的区块缩放驻留。**

## 5. 判定汇总与 R5 收束

| 候选 | 量级 | 状态 |
|---|---|---|
| 区块包缓存（0267 前曾 2,108 包/41MB） | 区块 | **已回收**（轮1） |
| section 随机刻表（急切分配 ~83MB） | 区块 | **已回收**（轮2，96% 本就空表） |
| NoiseChunk DF 树 + 插值器（worldgen 残留） | 区块 | **已回收**（轮3，−65.5%/−61%） |
| SWMRNibbleArray 光照半字节表 60MB | 区块 | **活数据**（光照正确性红线） |
| 碰撞形状 initCache 66MB / PalettedContainer 单值 intern ~45MB / StructurePiece 块表 24MB | boot/保存路径 | **红线受限**（轮3 §6 判例维持） |
| advancements 进度 ~83k 节点 | 玩家 | **活数据** |
| DFU schema/zip 缓存/注册表 | boot | **常量** |

**R5 主线四轮累计**（0.80.0 → 0.83.0，同场景配对腿，6,081 vs 6,112 区块，
12 bot spectator，门全绿）：

| 轮 | 批次 | 手段 | 单轮堆降 |
|---|---|---|---:|
| 1 | 141 / 0267 | 区块包缓存 TTL 驱逐（120s） | −9.0%（4.4k 区块腿） |
| 2 | 142 / src-main | ShortList 随机刻表惰性化 | −9.7%（6k 配对腿） |
| 3 | 143 / 0268 | NoiseChunk carvers 后释放 | −6.9%（6k 配对腿） |
| 4 | 144 | 持有链归因 → 逐项确证活数据/红线受限 | 0（分析轮，收束） |
| **合计** | | | **−280.8MB（−24.3%）** |

区块内存面的可回收项至此逐簇闭合：内容本体（palette/光照/高度图）、worldgen
瞬态（已收）、缓存（已收/已界定）、惰性化机会（已收）。后续进一步压缩只能动
内容表示本身（如光照 nibble 均匀段压缩、palette 编码改革）——均属光照/区块
正确性红线区且收益不确定，不在本轮授权的风险预算内。**R5 收束。**

## 6. 判例（轮4 新增）

1. **HPROF 规格文本 ≠ HotSpot 实写**：解析以 heapDumper.cpp 实值为准（根子记录
   编号整体偏移一位、堆数据全部在 0x1C 段内）。以规格文本先验写解析器会在第一
   个根记录处静默错位。
2. **尺寸表缺项的病征是"远端伪合法记录"**：primLen 漏写 case 10（int，4B 按 1B
   跳）后，解析器在 int[] 数组处少跳 3×n 字节，但其后数 MB 的 INSTANCE 记录
   尺寸仍"看起来全对"（自描述长度记录流中错位可自愈出伪合法序列）——唯字节级
   对账（left 精确清零 + 未知子记录 fail-loud）能在错位点本地暴露。解析器必须
   计账与消费同源。
3. **计账不读流=慢性错位**：CLASS_DUMP 静态字段 value 计入 used 但未实际消费，
   accounting 与 stream position 分叉——所有"返回消费长度"的接口必须与读操作
   逐字节同源，或直接由底层计数器（CountIn）权威供给。
4. **HashMap$Node 归属走表持有者而非 next 链**：next 链把桶内前驱误当持有者；
   键值类对统计才是语义层，byOwner（HashMap.table 同义反复）只作交叉验证。
5. **归因轮的价值边界**：98.9% 归因成功 + 逐簇判定后，"下一个优化点"若不存在，
   应正式收束而非继续试探——收束本身是指令授权的合法产出（防无界漂移）。

## 7. 复现

```
# 采集（绿门浸泡 + 转储）
benchmark$ /f/Java/21/bin/java -cp "build/classes;lib/gson-2.11.0.jar;lib/netty-*.jar" \
    -Dmemsoak.dump=true papo.bot.MemSoakVerify ../paper-server/build/libs/Papo-1.21.11-0.83.0.jar \
    12 150000 180000 120000
# 归因
benchmark$ /f/Java/21/bin/java -Xmx6g -cp build/classes papo.bot.HeapRefAttr \
    results/memsoak-<ts>/heap.hprof
```

工件：HeapRefAttr.java（新）、MemSoakVerify.java（dump 选项）、
benchmark/results/memsoak-dump-0.83.0.log（浸泡+转储日志）、
results/memsoak-1791560643148/heap.hprof（982MB，不入库）。
