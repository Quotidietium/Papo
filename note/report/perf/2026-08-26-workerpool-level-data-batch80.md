# 批次 80 报告（2026-08-26）— worker 池默认曲线提升 + level.dat 写下放（多核调度③）

主题：**多核调度**——两项：①chunk 系统 worker 池（并行区块生成/加载/光照/压缩/存档的唯一执行池）
的 auto 默认从 moonrise 的 cores/4 提升到 **cores/2 clamp [2,12]**（survey #4）：探索突发期
worker 池是吞吐瓶颈而核闲着，主线程等区块 future 直接造成 tick 卡顿；worker 线程 NORM 优先级
位于 NORM+2 主 tick 线程之下，扩容不偷 tick 时间（探针实证）。②level.dat 写下放
（survey #5，批次79 机制复用）：每世界每自动存档周期的 gzip+临时文件+原子替换在主线程，
现快照后入 IO 池 per-文件有序链。

## 1. 等价性 / 安全性

### worker 池默认曲线（直提交）
- 显式 `chunk-system.worker-threads`（>0）与 `-D<Brand>.WorkerThreadCount` 逐字优先（未触碰）；
  仅 auto 哨兵（-1）的默认值来源换成 `PapoParallelism.workerThreadCount()`。
- 曲线：8核 2→4、16核 4→8、24核+ → 12（cap）。cap 12 保证 32+ 核主机上
  workers(12)+netty(8-16)+IO(4)+main ≈ 不超订阅；≤12 核主机按 cores/2 温和放大。
- 池实现零改动（只换 `adjustThreadCount` 的数字来源）；AreaDependentQueue 区域串行化与
  优先级调度不变；线程优先级不变（NORM，主线程 NORM+2 上游既有）。
- **流畅度探针实证**：NORM+2 模拟 tick 线程在 8/12 worker 饱和期间 p50/p99/max 偏差全 0ms
  （32 核）——扩容不以 tick 流畅度换吞吐（goal 红线直接验证）。

### level.dat 写下放（源补丁 ×2 + 直提交工具类复用）
- `LevelStorageSource.saveDataTag`：`createTag` 快照在调用线程（语义=构造时读当前值，保持），
  CompoundTag 防御性深拷贝后入 `PapoOrderedFileWrites`（per-level.dat 路径链，串行+旧不盖新）；
  任务内原样 `createTempFile+writeCompressed+safeReplaceFile`+同 catch/日志。**boot 期的
  `modifyLevelDataWithoutDatafix` 路径保持同步 `saveLevelData` 不变**。
- **读后写可见性**：`makeWorldBackup` 拷贝前 `awaitPending(level.dat)`（链空零成本）——
  备份必见最新 level.dat，同步语义保持。level.dat 其余运行时读点不存在（仅 boot 读，
  boot 期无 pending 写）。
- **flush 语义**：`MinecraftServer.saveAllChunks(flush=true)`（/save-all、关服）尾部
  `awaitAll(60s)`；关服另有 haltExecutors 60s IO 池排水双保险。增量自动存档 fire-and-forget。

## 2. 基准（WorkerPoolScalingBench，真实 BalancedPrioritisedThreadPool）

96 个 CPU 型任务（512KiB×6 遍 FNV+旋转 ≈ 3.8ms/任务，section 解包量级代理）；本机 32 核：
旧默认 8 线程 vs 新默认 12 线程；tick 探针 = NORM+2 线程 5ms 工作迭代 × 120，池饱和期间采样。

| workers | best(ms) | mean(ms) | tick p50 | tick p99 | tick max |
|---|---|---|---|---|---|
| 8（旧 auto） | 46 | 47 | 0ms | 0ms | 0ms |
| 12（新 auto） | **31** | 32 | 0ms | 0ms | 0ms |

**吞吐 1.48×**（=12/8 线性，CPU headroom 内）；tick 偏差两配置持平全 0——流畅度无损。
自检 ALL OK（恰好一次完成、checksum 确定）。level.dat 下放无独立基准（与批次79 同机制，
写序列逐行同构，机制级对拍见批次79 报告）。

## 3. 同批否定结论（留档）

- **实体 unload 存档序列化下放（survey #3a）——红线否决**：`unloadStage2` 中
  `saveEntities` 在 `entityChunk.unload()` **之前**同步执行（NewChunkHolder.java:899→901），
  而 `unload()` 会对每个可保存实体触发 `PlatformHooks.unloadEntity`（Paper 侧卸载事件+
  `setRemoved(UNLOADED_TO_CHUNK)`）。下放后序列化将捕获 **post-event/post-removed 状态**
  ≠vanilla 的 pre-event 字节——插件在卸载事件中的状态变更是否入档将翻转。行为等价红线
  不可越，否决。autosave 路径（saveEntities(false)）同理有 ticking 中实体的快照一致性问题，
  一并否决。
- POI unload 序列化下放技术上可行（`moonrise$onUnload` 只动管理器映射不动 PoiChunk 数据）
  但收益小（POI 记录数少、脏 POI 卸载罕见），单独成批不成立，留档。

## 验证链

compileJava BUILD SUCCESSFUL → 基准吞吐 1.48× + tick 探针持平 + 自检 ALL OK →
fixupSourcePatches → rebuildPatches（LevelStorageSource/MinecraftServer 两源补丁）→
全量 test（见 optimizations.md 批次 80 记录）。
