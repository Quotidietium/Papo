# 批次 71 JMH 微基准报告（2026-08-22）— 共享区块包实例 + 压缩输出 memo（0242）

主题：**多玩家网络稳定**（区块发送突发的每观众主导成本消除，批次 70 的复合续作）。
批次 70 后，多观众发送同一 chunk 的剩余每观众成本排序：**DEFLATE 压缩（≈0.2-1.3ms/次，随包
尺寸与级别）≫ 包编码 codec 走查（~10-20µs）> 光照 memcpy（~4µs）> BE 列表构造**。N 个观众对
**逐字节相同**的包各自 DEFLATE——本批把"相同内容的重复压缩"整体消除。

机制（0242，复合优化）：
1. **共享 packet 实例**：BE-free 且（chunk 数据版本, 光照版本）未变时，`ChunkHolder` 向所有观众
   返回**同一个** `ClientboundLevelChunkWithLightPacket` 实例（FeatureHooks.sendChunkRefreshPackets
   的跨玩家共享先例延伸到主发送路径）。
2. **光照版本信号**：`ChunkHolder.sectionLightChanged` 递增计数——该信号由光照引擎
   `onLightUpdate` 驱动（vanilla LayerLightSectionStorage 与 moonrise StarLightEngine 的每个变更
   section 都经 `ServerChunkCache.onLightUpdate` 投递主线程），与 vanilla 自身增量光照广播同源同
   完备性。bump 置于"无观众早退"之前，计数不跳变。
3. **压缩 memo**（`PapoSharedWireMemo`）：共享实例携带单槽 memo；首个连接压缩后快照
   `[数据长 varint][压缩负载]` 段，后续连接 memcpy 直放。经 0217 的 channel attr 机制在
   PacketEncoder→CompressionEncoder 间传递（身份匹配才生效，prepender 兜底清理，无压缩
   handler 的连接不滞留）。

环境：JDK 21.0.10（Windows 11，JDK Deflater 回退后端），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性论证

- **字节恒等**：memo 段即同一 deflate 调用的原样快照；段自描述（内含未压缩长度 varint），命中
  与未命中路径线上字节逐字节相同（基准自检对 8 观众逐观众全等 + Deflater 同输入确定性验证）。
- **压缩级别不进协议**：任意 zlib 流可被解码端无关级别地解压；level 变更仍经
  `PapoSharedWireMemo.papoStampLevel` 纪元失效（setupCompression 创建新压缩器且级别不同才递增），
  threshold 戳不匹配同样不命中。竞态填充最坏交错出的段仍框定同一未压缩长度、解码同一负载。
- **新鲜性硬约束（与批次 70 同标准）**：
  - chunk 数据/BE 集合：`papoChunkDataVersion`（0241 四处 bump）——BE-free 在版本不变期间恒成立
    （BE 增删即 bump），共享实例无 BE 内容陈旧面。
  - 光照：`papoLightVersion`。**已知亚 tick 窗口**（诚实披露）：光照在调度线程写入、通知经
    `mainThreadProcessor.execute` 排队——共享包可能嵌入比"该观众构造时新鲜读取"稍旧的光照；
    排队中的通知必然 mark 本 holder 的广播过滤器并向**当时已在场的观众**（含刚收到共享包的新观众）
    发增量 `ClientboundLightUpdatePacket` 修正——同 tick 自愈，无持久 desync（vanilla 对已追踪玩家
    本就走"增量修正"路径）。
- **线程模型**：共享实例不可变（ready=true 置位后无人改写）；memo 三字段 volatile、段数组完全
  初始化后发布（JMM happens-before），多 event loop 并发编码安全；版本读写与共享条目读写均主线程。
- **0217 兼容**：memo 命中路径产出带 headroom 的输出 buffer 并发布身份，prepender 直通不变；
  引用计数所有权结构与既有 above-threshold 路径逐行同构；外来 buffer（插件注入）走原拷贝路径。

## 2. 基准（SharedChunkWireBench，JDK Deflater 真实压缩）

载荷模型 41,189B（8 非空 section 加权调色板 + 16 空 section + 12×2KB 光照型数据块），压缩比
**3.99×**（批次 63 实测真实区块包 level-6 ≈ 4.9×，模型贴近）。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 223,680.700 ± 4,933.449 | 每观众：序列化 + DEFLATE + 帧化 |
| afterFill | 227,313.946 ± 9,696.903 | 首观众：before + 段快照（CI 与 before 重叠，快照开销在噪声内） |
| afterHit | **3,509.720 ± 87.287** | 后续观众：序列化 + memo 段 memcpy + 帧化 |

- **命中路径 ≈ 63.7×/观众**（CI 完全分离）；每观众每 chunk 省 ~220µs 压缩 CPU。
- 场景外推（诚实边界）：20 观众 × 64 chunk 突发 tick ≈ 20×64×220µs ≈ **282ms CPU 消除**（分摊在
  各连接 event loop，此前为 login/跑图突发期的主要网络线程负载）。Linux 生产端 libdeflate 单次
  绝对耗时低约一个量级，但 N×→1× 的结构性节省与后端无关。
- 自检 main ALL OK：8 观众两路径 wire 字节逐观众逐字节全等 / Deflater 同输入同级别输出确定性 /
  threshold 戳不匹配不命中 / 载荷压缩比 sanity（2.5-8× 带内）。

## 3. 未做与留档

- **BE-containing chunk 的共享**：BE 内容（告示牌文字等）原位变化无 chunk 级信号，共享会把构造
  时快照发给后续观众（可观察回归）——不做；此类 chunk 走批次 70 的 per-观众路径（chunkData 缓存
  + 新鲜 BE/光照）。
- **编码（PacketEncoder）阶段 memo**：每观众 codec 走查 ~10-20µs，为次级成本；机制可复用本批
  attr 通道，留档后续。
- 无压缩连接（network-compression-threshold=-1）不享受 memo（无压缩阶段），prepender 清理 attr
  防滞留。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0242）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 71 记录）。
