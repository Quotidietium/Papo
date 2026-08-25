# 批次 79 报告（2026-08-26）— 玩家存档文件管线下放（多核调度②）

主题：**多核调度——主线程阻塞 IO 清算**。survey 排名第一的旗舰候选：每次玩家保存
（增量自动存档 / 退服 / `/save-all` / 关服 / 紧急保存）都在主线程完成
`player.saveWithoutId` NBT 构建 + **GZIP** + createTempFile + 原子替换 + **stats JSON 写** +
**advancements JSON 写**——单玩家 0.5-3ms，`playerAutoSave.maxPerTick`（默认 10）突发即
多 ms tick 尖峰，随玩家数线性放大，而 IO 池线程闲着。本批：主线程只做快照
（NBT 树防御性深拷贝 / stats、advancements 的 detached Json 树），gzip+文件写全部下放
到 moonrise IO 池，**per-目标文件 CompletableFuture 有序链**保证旧快照永不覆盖新快照。

## 1. 等价性 / 安全性（逐项）

- **快照分离**：主线程构建载荷（vanilla 语义=构造时读取当前值，保持不变）；NBT 树
  `buildResult().copy()` 防御性深拷贝（树本应每保存新建，深拷贝使"任何子系统未来共享
  子 tag"的假设也安全，成本 ~µs 级）；stats `toJson()` 与 advancements `encodeStart` 产出的
  JsonElement 为新建 detached 树（对拍：任务内用同一 `GSON.toJson(element, newJsonWriter)`，
  文件字节与同步路径逐字节相同）。
- **per-UUID 有序（数据不回滚红线）**：同一目标文件的写任务经 CompletableFuture 链严格串行
  （前一失败不阻断后续——`handle` 吞异常，任务内部保留 vanilla try/catch+日志语义）；
  不同玩家并行。无序并发的"旧快照后落盘覆盖新快照"数据回滚风险被链排除。
- **读后写可见性（快速重连竞态）**：三个加载点（`PlayerDataStorage.load`、
  `ServerStatsCounter` 构造器、`PlayerAdvancements.load`）读前 `awaitPending`（链空时零成本
  快路径；有界 60s）——同步代码"读到的一定是最新保存"的保证原样保持。
- **全量保存语义**：`PlayerList.saveAll()`（interval==-1：`/save-all`、关服、紧急保存、
  `saveAllChunks` full 路径）排空后 `awaitAll`（60s 有界）——vanilla "保存返回即完成"契约保持；
  增量自动存档 fire-and-forget（收益主体）。关服另有 `MoonriseCommon.haltExecutors` 60s
  IO 池排水双保险（dedicated）。
- **池拒绝兜底**：`enqueue` 检查队列 `isActive()`，池已停时同步执行（持久性不静默降级）。
- **覆盖面**：`PlayerDataStorage.save` 唯一调用方 `PlayerList.save`；stats save 唯一调用方同；
  advancements 另一调用方（MinecraftServer reload 路径 :2319）同样受益且无回读。
  载荷构建线程=调用线程（join/reload/quit/autosave 全部主线程），无新并发面。

## 2. 基准（PlayerSaveOffloadBench，50 玩家 × 4 save/tick × 12 tick 突发）

载荷 96KiB 结构化可压缩 byte[]（gzip 后 898B——**比真实玩家 NBT（gzip 5-15×）更易压缩，
即本基准的 gzip 成本偏小、测得的主线程削减是保守下界**）。before=主线程 gzip+临时文件+
原子替换（vanilla 序列复刻）；after=主线程快照深拷贝+入队（复刻 PapoOrderedFileWrites 的
per-Path 链 + pending 计数 + awaitAll），池=4 线程。

**自检 ALL OK**：① 同目标文件写任务并发度恒 ≤1；② 最终文件字节==最后快照（gzip
round-trip 对拍，30 连续写后校验）；③ awaitPending 新鲜度（入队后立刻等待，必读最新）；
④ awaitAll 后全部完成且池收束。

| 模式 | 主线程 ms/轮（48 存档） | 总墙钟 ms |
|---|---|---|
| sync（before） | 89 | 87 |
| async（after） | **2** | 56 |

**主线程削减 44.5×**（单次保存 1.85ms → 0.03ms），总墙钟还因 4 路并行降低（87→56ms）。
外推：200 玩家 maxPerTick=10 突发 ≈ 每 tick 18ms 主线程 → 0.3ms；`/save-all` 200 人 ≈ 370ms
主线程阻塞消除（awaitAll 期间仍在 IO 池并行排水）。

## 3. 留档

- level.dat 写（survey #5，每世界每自动存档周期一次，~0.2-1ms）留待后续批次（涉及
  ServerLevel/LevelStorageSource 启动期路径分支，收益小面大）。
- 逐包 eventLoop execute 批量化（survey #2）与批次 60 已实测否决的 MPSC 排水同域，不动。
- 实体/POI 卸载序列化下放（survey #3a）为下一批主候选。
- 基准 payload 熵偏低（gzip 109× vs 真实 5-15×）已披露；真实玩家存档 gzip 更贵，
  实际削减只会更大。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → 主线程削减 44.5× → fixupSourcePatches →
rebuildPatches（4 个源补丁更新，feature 补丁仅 index 哈希位移）→ 全量 test（见
optimizations.md 批次 79 记录）。
