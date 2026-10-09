# R5 轮2（批次 142）：每 section 随机刻列表惰性化 —— 空表驻留归零

Paper 1.21.11 · Papo 0.82.0 · src/main 直提交（Moonrise ShortList，无补丁号）

## 1. 背景与目标

轮1 后的新画像（0.81.0，12 bot / 4.4k 区块 / 856MB）里，每
LevelChunkSection 一个的 Moonrise `ShortList`（随机刻方块位置表，
`tickingBlocks`）表现异常：**Short2ShortOpenHashMap 实例数 299,568 ==
LevelChunkSection 实例数**（逐个对应），其 fastutil short[16] 键/值数组
构成 [S] 行 64.1MB 的绝大部分——合计 **~83MB（堆的 ~9.7%）**。

根因：`ShortList` 构造器急切 `new Short2ShortOpenHashMap()`（容量 16 +
默认装载率 → 键值两个 short[16]），而**自然地形绝大多数 section 的随机
刻方块数为零**（石头/深板岩/空气/水不随机刻；只有草/作物/树叶/火等）。
空表付全价。

## 2. 优化内容

`ShortList.map` 改为**惰性**：null 直到首次 `add()` 才分配。全部可观测
面逐方法等价：

- `size()`/`getRaw()` 读 count/byIndex 字段（本来就与 map 无关）；
- `remove()`：null == 空 map == "不存在" → false（与空 map 的
  defaultReturnValue 路径逐位同构）；
- `clear()`：count=0 + map 存在才 clear；
- `setMinCapacity()`：byIndex 预容量行为不变；map 的 ensureCapacity 仅在
  已分配时执行——其唯一调用点（LevelChunkSection 重算路径）后面紧跟
  add 序列，首次 add 无论如何都会分配，无行为差。

消费面盘点（全树）：填充=LevelChunkSection 重算（仅随机刻 palette 项触发）
+ setBlockState 增删翻转；读取=ServerLevel 随机刻循环（size/getRaw，且外层
已有 isRandomlyTickingBlocks 门）。无其他持有者（ChunkAccess 的 ShortList
是 fastutil 接口，另一物）。

## 3. 验证

- **语义差分**：`benchmark/src/papo/bot/ShortListVerify`——插入序 +
  末位交换删除的 ArrayList 参照模型，确定性边界序列（空态 remove/clear/
  setMinCapacity、首 add、remove 到空再复活）+ 20 万条随机指令差分，
  全量快照逐位断言：**ALL OK**。
- 构建验证：compileJava BUILD SUCCESSFUL + :paper-server:test BUILD
  SUCCESSFUL EXIT=0（src/main 直提交不经 applyPatches，compile+test 覆盖）。
- **浸泡两腿**（0.81.0 vs 0.82.0，同 MemSoakVerify 场景，绿门）：结果 §4。

## 4. 结果（绿门复跑，场景高度配对：6,088 vs 6,055 区块，−0.5%）

| 指标 | A：0.81.0 | B：0.82.0 | Δ |
|------|-----------|-----------|---|
| 堆 used | 1,034,658 KB | **933,862 KB** | **−100,796 KB（−9.7%）** |
| LevelChunk | 6,088 | 6,055 | −0.5%（配对） |
| LevelChunkSection | 395,712 | 393,600 | −0.5%（配对） |
| Short2ShortOpenHashMap | 395,712（=全部 section） | **15,163（3.85% 的 section）** | −96.2% |
| [S short[] | 85.0 MB（826,402 个） | **17.8 MB（65,020 个）** | −67.2 MB（−92.1%） |
| ShortList 包装对象 | 395,712 / 9.5 MB | 393,600 / 9.4 MB | 保持 1:1（预期，~24B/个） |
| long[] | 187.7 MB | 186.3 MB | 配对（无关类不动） |
| byte[] | 117.6 MB | 117.0 MB | 配对 |
| Object[] | 105.0 MB | 104.5 MB | 配对 |

两腿 12/12 bot 全部径向走出（终位 250-900 格），门全绿（logErrors=0
exit=0）。归因：Short2Short map −24.4MB + [S −67.2MB ≈ **−91.5MB**，与堆
降额 −100.8MB 同量级（余量为包装/邻接对象抖动）；全部无关类 ±1% 内——
改动完全隔离。

方法论注记：首次两腿（21:05）测量段有效但门被 bot 关断竞争噪声击中
（WSAECONNABORTED 文案变体，isBenignCloseRace 已补认）；且当时宿主有
并发构建造成 fork 压力。复跑（22:08）在静默宿主上取得绿门与更配对的场景
（两腿都走到 ~6k 区块）。首次运行的配对比对（4,404 vs 4,375 区块跨运行）
给出 −8.8%，与复跑 −9.7% 一致。

## 5. 结论

轮2 达成：空 section 的随机刻列表零驻留——0.81.0 上 96% 的 Short2Short
map 是纯空表开销（393,600 section 中仅 15,163 个非空），惰性化后该簇
−91.5MB（配对场景堆 **−9.7%**）。语义零差（20 万指令差分 + 全消费面盘点）；
性能面：空表路径少一次间接（null 早退），首 add 代价一次分配（section
加载时一次性）。**0.81.0 → 0.82.0 发布。**
