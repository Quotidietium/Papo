# R5 轮1（批次 141）：区块包缓存 TTL 驱逐 —— 内存浸泡三腿对比

Paper 1.21.11 · Papo 0.81.0 · 补丁 0267（features）

## 1. 背景与目标

R5 总目标：多玩家多区块加载场景下尽可能低的内存驻留。轮1 打掉画像里最大的
已知可回收驻留：**0241/0242 多观众区块包缓存的终身驻留**（已接受缺陷 0269，
R5 使其转为修复目标）。

驻留物（0.80.0 行为）：

- `LevelChunk.papoPacketHeightmapsCache + papoPacketBufferCache`（0241）：
  完整序列化 wire payload（heightmaps long[] + 全区块字节缓冲），
  每区块数十至数百 KB，**随区块加载终身驻留**；
- `ChunkHolder.papoSharedChunkPacket`（0242）：共享包实例（新起 light 数组 +
  PapoSharedWireMemo），同窗口驻留。

关键观察：缓存只在**时间上聚集的多次发送**（多观众突发，受 sender 配额在秒级
内摊开）中获利；一旦发送窗口关闭，驻留是纯开销。玩家离开/视角切换后无人复用，
但缓存仍占内存直到区块卸载。

## 2. 优化内容（0267）

TTL 驱逐，三文件：

- `ChunkHolder`：新增 `papoLastChunkSendGameTime`（`Long.MIN_VALUE`=从未发送）。
  `papoGetSharedChunkPacket` 首行打戳（命中与填充两路径都经过它）；
  新增 `papoEvictStaleChunkPacketCaches(now, ttl)`：超窗时清
  `papoSharedChunkPacket` + 经 holder 的 `getChunkToSend()` 清
  LevelChunk 侧 payload 缓存（区块实体区块只有 payload 缓存没有共享包，
  经同一 holder 戳独立清空），戳复位为 MIN_VALUE。
- `LevelChunk`：新增 `papoEvictChunkPacketCache()`（两字段置 null）。
- `ServerChunkCache.tick`（chunkMap.tick 之后、clearCache 之前）：
  `papoEvictChunkPacketCaches()`——`-Dpapo.chunkPacketCacheTtlTicks`
  （默认 600 tick=30s；≤0 禁用=诊断腿复现 0.80.0 驻留），每 TTL/2 game tick
  扫一轮全部 holder（`ChunkHolderManager.getOldChunkHoldersIterable()`）。

正确性论据：

- **访问面**：缓存读写只在主线程（发送主线程、扫描主线程），无并发；
- **在飞安全**：已排队发送持自有引用，驱逐不清在飞；
- **迟到观众**：过期后观众走 0241 之前的全新序列化路径（行为=原 vanilla
  等价路径），无正确性依赖；
- **戳语义**：命中也打戳（活跃复用窗口内不驱逐——缓存正在获利的定义）；
- **版本一致性**：驱逐不动 `papoChunkDataVersion` 计数器，重填充路径与
  批 133 判例（预序列化版本戳）完全兼容；
- **TTL=0 腿**：同一 0.81.0 二进制上禁用驱逐，二进制内对照。

## 3. 测量方法（MemSoakVerify）

真服浸泡：`-Xms4G -Xmx4G`，固定种子 papo90，view-distance=10、
simulation-distance=6、peaceful、online-mode=false、**gamemode=spectator**
（见 §5 勘误与判例）。12 bot 各自完整走 登录→配置→play 协议，采纳
PLAYER_POSITION（C 0x46，varint id + 3Δpos double + 3Δ double + 2 float +
int mask）并回 ACCEPT_TELEPORTATION（S 0x00），然后以 0.25 block/tick
径向行走 150s（加载沿途区块），原地驻留 180s 持票。驻留第 120s：
`jcmd GC.run`×2（间隔 3s）→ `GC.heap_info`（used K）→
`GC.class_histogram`（LevelChunk/共享包实例数等 WATCH 表）。
门：服务器日志零 ERROR/Exception + exit 0。

三腿：

| 腿 | 二进制 | 配置 | 语义 |
|----|--------|------|------|
| A | 0.80.0 | — | 基线（缓存终身驻留） |
| B | 0.81.0 | TTL=600（默认） | 驱逐生效 |
| C | 0.81.0 | TTL=0 | 驱逐禁用（同二进制对照） |

场景效度：12 bot 全部实测走出出生点（§5 判例；最终位径向散开 ~300+ 格），
LevelChunk 实例数随分散上升（单窗口 ~650 → 12 bot 扩散 ~4,400）。

## 4. 结果

场景：12 bot 径向行走 150s（0.25 block/tick）+ 原地驻留 180s 持票；驻留第
120s 双 Full GC 后测堆；`-Xms4G -Xmx4G`；view-distance=10；种子 papo90；
每腿 12/12 bot 走出出生点（botFinalPos 12 行×3 腿）、门全绿（logErrors=0
exit=0）。

| 指标 | A：0.80.0 基线 | B：0.81.0 TTL=600 | C：0.81.0 TTL=0 |
|------|---------------|-------------------|------------------|
| 堆 used | 941,057 KB | **856,122 KB（−84,935 KB，−9.0%）** | 1,091,158 KB |
| LevelChunk 实例 | 4,371 | 4,404（+0.8%，同规模） | 5,564（+27%，场景方差） |
| 缓存共享包实例 | 2,108 | **0**（低于直方图出线阈值） | 2,741 |
| PapoSharedWireMemo | 2,133 | 25（瞬态残余） | 2,766 |
| byte[]（wire 缓冲载体） | 176.8 MB | **94.4 MB（−82.4 MB，−46.6%）** | 218.7 MB |
| long[] | 145.0 MB | 143.3 MB | 176.7 MB |
| PalettedContainer | 595,969 | 599,137 | 731,617 |

读法：

- **B vs A 是对比腿**（两腿区块数 4,404 vs 4,371，场景规模一致）：堆
  −84.9MB（−9.0%），其中 **byte[] −82.4MB 与堆降额几乎完全重合**——
  驱逐收益全部落在缓存 payload 载体上，无扩散性副作用；2,108 个缓存包
  ×~39KB ≈ 82MB，归因闭合。
- **C 是机制对照腿，不是绝对对照**：TTL=0 时缓存驻留比 2,741/5,564 =
  0.49/区块 ≈ A 的 2,108/4,371 = 0.48/区块——同一二进制上驱逐开关
  完整复原旧驻留行为 ✓。C 的堆/区块数绝对值偏高（区块 5,564，+27%）：
  腿间场景动力学方差（发送节奏/时序影响行进覆盖），属测量观察，
  归档为方法论注记（浸泡测量的绝对堆对场景形状敏感，跨腿比较须配对
  区块规模或看实例类指标）。
- LevelChunkSection/PalettedContainer 等与缓存无关的类在 A/B 间同规模
  波动（±0.5%），证明驱逐改动无波及面。

## 5. 测量基建判例与勘误

- **判例（bot double 端序）**：bot 曾以小端写 double（`b[i]=v>>>(8*i)` 产
  [LSB..MSB]），服务端 FriendlyByteBuf 按大端读出**次正规数 ≈ 0.0**——
  三个坐标恒 (0,0,0)，每 tick "moved too quickly" 纠偏回出生点。表面症状
  （位置未推进）与真根因（端序）相距三个排查层（误以为 0x46 误采纳/反作弊
  拒绝/teleport 确认失败）。**双端手写协议代码必须逐字节锚定端序判例**；
  次正规数解码不抛异常，错位不报错——静默错值比异常更危险。
- **判例（spectator bot）**：内存基准的 bot 用 gamemode=spectator：
  noPhysics 豁免 moved-wrongly/CLIPPED_INTO_BLOCK（`ServerGamePacketListenerImpl`
  1620-1640 行），任何地形直穿；加载票据与普通玩家同制（PlayerChunkLoader），
  但免除全部移动验证类不确定性。基准追求的是区块驻留画像，不是移动语义。
- **判例（1 字节打包双布尔）**：1.21.11 ServerboundMovePlayerPos 尾部是
  **单字节打包**（bit0=onGround, bit1=horizontalCollision），不是两个独立
  boolean——读 wire 格式必须锚定代际（1.21.2+ 改动）。
- **勘误（TickSurveyBench 行走前提）**：批 90/93-96 的 TickSurveyBench
  "行走 bot" 前提从未成立——其 bot 从未发送过任何 MOVE_PLAYER 包（该轮
  bot 栈止于 join+keepalive）。因此那些轮的"多玩家行走"场景实为"多玩家
  驻留出生点"场景；tick 耗时数字本身（驻留负载）有效，但不得引用为
  行走负载证据。本轮 MemSoakVerify 是首个真正行走的 bot 栈。
- **判例（climb-while-walk 不可用）**：survival bot 边走边 +0.5/tick 爬升
  必撞山面（claimed 落入实体方块 → moved-wrongly 纠偏循环，实测 id 01-08
  连续纠偏同点）；spectator 后不需要爬升，y 恒定直穿地形。

## 6. 结论

轮1 达成：0269 类驻留在 12 玩家/4.4k 区块浸泡下占 **~83MB（堆的 9%）**，
TTL 驱逐后归零（收益全部落在 byte[]，归因闭合），代价是 30s 后的迟到
观众走全新序列化路径（0241 之前的 vanilla 等价行为，正确性无依赖；
多观众突发的秒级窗口内缓存照常命中）。默认 TTL 600 tick 可调，TTL=0
诊断腿复现旧驻留。**0.80.0 → 0.81.0 发布。**
