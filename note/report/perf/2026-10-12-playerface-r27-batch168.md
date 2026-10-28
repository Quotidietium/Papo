# R27（批次 168）：高并发玩家面画像——判负（版本保持 0.94.0）+ 测量工具并发缺陷修复

日期：2026-10-12　版本状态：0.94.0（审计轮，服务器零代码变更）　工具：`MemSoakVerify`（bots=60 修复后）

## 1. 缺口与本轮目的

历轮全部浸泡 ≤12 bot——每玩家状态图（连接/监听器/进度/配方书/背包/区块跟踪/waypoint）从未在规模下画像。60 bot 定向腿 + 实例数=60 的类直读每玩家足迹。

## 2. 测量工具缺陷（发现与修复）

**首跑暴露并发裂批**：60 bot 下直方图所有每玩家类=31——服务端日志证明裂成 **31@05:31:55 + 29@05:38:25 两批**（第二批在首批全部断开后才启动）。根因：`CompletableFuture.runAsync` 走 commonPool（并行度≈核数−1≈31），bot 任务全程阻塞且无 ManagedBlocker → 超出并行度的任务排队到有空位。**修复**：专用定长线程池（`Executors.newFixedThreadPool(bots)`），bots 数即并发在线数。**历史有效性核验**：历轮 bots≤12 ≤31 → 全并发，全部历史数据不受影响。判例：bot 池并发上限必须显式；验收=测量点 joined−left=在线数=bots。

## 3. 普查数据（0.94.0，60/60 在线，门绿）

heapUsed 1,013,123KB @ 驻留中段；区块内容主导（LevelChunkSection 295,392 ≈ 5,681 区块、PalettedContainer 590,785）。**每玩家图（实例=60 直读）**：

| 类 | 每玩家字节 | 判定 |
|---|---|---|
| ServerPlayer | 1,248 | 语义 |
| ServerGamePacketListenerImpl(+λ) | 328+16 | 语义 |
| Connection | 176 | 语义（netty 堆侧小、direct 面 R24 已判） |
| PlayerChunkLoaderData(+3 内部) | 136+48 | 语义（Moonrise 加载器） |
| ServerPlayerGameMode / PlayerAdvancements / PlayerChunkSender | 72/64/48 | 语义 |
| Inventory / RecipeBookSettings / ServerRecipeBook / PaperPlayerGameConnection | 40/32/32/24 | 壳级小（<50B，懒化无收益） |
| WaypointTransmitter$EntityChunkConnection | 52 个/玩家×32B | 语义（1.21 waypoint 追踪） |
| [LServerPlayer;（33,748 数组/3.19MB） | ~29KB | **归因 NearbyPlayers.TrackedChunk**：每跟踪区块×6 视图类型的 ReferenceList 类型化后备数组——Moonrise 核心邻近查改结构，随玩家邻近线性缩放（31→60 人：数组 1.36×/字节 1.78×）=内容 |
| LongOpenHashSet 3,864 | sentChunks 等跟踪集 | 语义 |

**每玩家边际拆解**：(1,013,123−812,537)KB/(60−31)=6.9MB/玩家，其中走廊新区块（60 条径向不重叠走廊）≈99%；**每玩家状态本体 ≈45KB（0.65%）**。壳候选全量 <50B/玩家（Inventory/RecipeBook 族）——远低于簇级份额立项门槛（R24 三门槛判例）。

## 4. 判定

**判负**：每玩家状态面全语义且壳级候选微量；规模缩放主导者是世界内容（区块）与 Moonrise 邻近结构（重写=架构红线）；waypoint 连接/配方书/进度均为真实状态。高并发面在内存主线下无未收割结构面。

## 5. 判例

1. **bot 池并发上限必须显式**：commonPool 阻塞任务超并行度即裂批，且门照常全绿（bot 全部最终完成）——**在线数验收（joined−left=bots@测量点）是规模腿的必要门**，仅 gate 绿不足。
2. **每玩家边际必须拆状态/内容**：6.9MB/玩家的原始边际若不拆分会误立项；状态本体 45KB 的拆解才是判定依据。
3. **类型化数组行是结构指纹**：[LServerPlayer; 33,748 行直接指向 NearbyPlayers 的 ReferenceList 后备——类型化数组行→持有结构→语义判定的三步链可复用。

## 6. 结论

高并发玩家面闭合：工具并发缺陷修复（历史数据核验不受影响）+ 60/60 census 定量（状态本体 45KB/玩家 vs 内容边际 6.9MB）+ 全表语义判负。**零服务器代码变更零补丁零版本 bump，0.94.0 保持。**
