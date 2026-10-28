# R31（批次 172）：盘载窗第三面+红线轴闭环——PackedData 数组别名收割与 MSPT 守护（→0.97.0）

日期：2026-10-12 ｜ 轮次：R31 ｜ 判定：**收割**（0.96.0 → 0.97.0）+ 守护闭环

## 红线轴闭环：批 170/171 MSPT 守护（R23 方法论）

MsptLoadProbe 配对腿 0.94.0（批 170 前）vs 0.96.0（批 171 后），主序 A→B + 倒序 B→A
四腿全绿（gate logErrors=0 exited=true）：**双序全槽 B≤A**——主序三槽 21.7→17.8
（−18.0%）、倒序 17.1→15.9（−7.0%）。批 170/171 改动全在盘载 IO 线程（不在 tick
路径），改善来自加载期分配减半后 GC 争用外溢减少。**交互体验轴零回归（方向偏优）**。

## 第三面归因（C 腿残余：LongArrayTag$1.readAccounted 133-164MB）

`readAccounted` 本体是**内在载荷物化**（区块 packed 数据本身）；但追链发现**重复物化**：
`NbtOps.getLongStream` 返回 `Arrays.stream(tagArray)`，而 `unpack()` 的
`optional.get().toArray()` **必然再分配同内容数组**——每个 section 载荷被物化两次
（#1 tag 载荷→垃圾；#2 toArray→SimpleBitStorage 真正滞留者，构造器别名 #2）。
SimpleBitStorage(bits,size,long[]) 构造器本就别名传入数组——浪费纯粹在流形态。

## 收割（补丁 0283）

- `PackedData.storage`：`Optional<LongStream>` → **`Optional<long[]>`**（波及面=两文件，
  PackedData 仅 PalettedContainer/RO 使用）。
- 字段 codec `PAP0_LONG_ARRAY`：`NbtOps + LongArrayTag` → **别名直取** `getAsLongArray()`
  （单次解码契约：parse 路径每 section 字段恰解码一次，tag 树随后整体弃置）；
  其余 ops 走 `getLongStream + toArray` 与 `Codec.LONG_STREAM` 完全同形（JsonOps 回退
  不变）。
- `unpack()`：`toArray()` → 直用数组；`pack()`：交 `getRaw()` 给同形 encode（写侧
  createLongList→toArray 拷贝，线上字节恒等）。

恒等性：NBT 路径滞留数组=tag 原数组（同一数据、同一内容，省一次拷贝）；写路径仍拷贝
（活数组不被序列化缓冲别名）；非 NBT ops 行为逐字节同旧。DFU 8 勘误：`createLongList`
返回裸值非 DataResult（与旧版 mergeInto 语义不同）。

## 验证

- **恒等单测 4 件**（`PapoPackedDataAliasTest`）：①NbtOps 全内容往返逐格相等 +
  **assertSame(tag 数组, 滞留 storage.getRaw())**（别名铁证）；②二次编码线形逐字节
  相同；③均匀容器解码落共享 ZeroBitStorage；④JsonOps 非 NBT 回退解码正确。
  全绿；全套件 EXIT=0；rebuild/apply/compile 全 EXIT=0；0.97.0 出包。
- **同世界配对腿**（新源世界 10,355/10,355 块降 3465 双腿，门双绿）：
  - unpack 框内样本 C 51 trips/109.7MB → D **14/11.8MB（−89%）**；流 toArray 框内
    76/147.1MB → 42/45.8MB（−69%）。
  - 驻留：D 覆盖 +7% section 下全内容行同步缩放（[J +3.6%/SimpleBitStorage +5%），
    **LongArrayTag 零滞留**（别名不泄 tag 树）；每 section 3.26→3.10KB。
  - D 的 `Unsafe.allocateUninitializedArray` trips 全为字符串拼接/DFU toString 面，
    非流复制。
- 工具注：旧源世界被 Windows Temp 清理——新源世界由 MemSoakVerify 再生
  （papo-memsoak-9466973299594566798），R30 结论不受影响（其配对当时同世界成立）。

## 判例

1. **流形态=隐藏拷贝**：任何"集合→Stream→toArray"管线都保证一次额外物化；载荷
   直取（数组别名）是唯一消法，且要求单次消费契约可证。
2. **别名三问**（收割前必答）：谁还持有该数组？（tag 弃置后无人）谁会改它？
   （滞留者本就是可变存储）失败路径？（长度校验同旧，InitializationException 同旧）。
3. 守护腿串行纪律：MSPT 测量期间不得并发重负载腿（CPU 争用污染窗口）——本轮一次
   违例被自查捕获并重跑。

内存账（盘载窗）：unpack 复制面 −89%（109.7→11.8MB）；滞留无变化（纯瞬态面）；
MSPT 双序 B≤A。版本 0.97.0。
