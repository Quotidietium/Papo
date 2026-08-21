# 批次 73 JMH 微基准报告（2026-08-22）— 编码阶段 memo + SectionBlocksUpdate 广播共享（0244）

主题：**多玩家网络稳定**——多观众出站冗余的全面清算（0242/0243 之后，把每观众出站成本打到
memcpy 地板）。两项改动：
1. **编码阶段 memo**：`PapoSharedWireMemo` 增加编码字节槽（首连接 `PacketEncoder` codec 走查的
   原样快照，后续连接 `writeBytes` 直放）；仅对**瞬态广播包** arm（light/section-update，随包
   GC），chunk 共享实例（驻留 ChunkHolder）不 arm——~40KB 编码快照会钉住整个 chunk 生命周期。
2. **ClientboundSectionBlocksUpdatePacket 接入 Carrier**（压缩 + 编码双 memo）：
   `ChunkHolder.broadcastChanges` 把同一实例发给每 chunk 全部追踪玩家（大红石装置/TNT 大炮每 tick
   每 section 数十~数百方块变更批成一包）；此前每观众各自 varlong 走查编码 + DEFLATE。

否决项：`ClientboundExplodePacket` 共享——构造参数含 `getHitPlayers().get(serverPlayer)` 的
**per-player 击退向量**（ServerLevel:1991-1992 逐玩家构造），不可提出循环共享，且爆炸为低频事件。

环境：JDK 21.0.10（Windows 11，JDK Deflater 回退），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性

- **编码 memo**：快照取自首次编码**同一 buffer**（HEADROOM 起）`getBytes`，命中路径 `writeBytes`
  到同一 writerIndex 起点——输出逐字节相同；finally 的 PacketTooLarge 检查读 readableBytes()，
  两路径同值；size hint/JFR/log/ProtocolSwapHandler 均不受影响。arm 门槛=codec 完全确定且
  locale 无关（light：整数/BitSet/byte 集合；section-update：varlong 定数走查——逐项核验）。
- **线程安全**：papoEncoded volatile、数组完全初始化后单写发布（JMM）；包不可变 ⇒ 竞态双填内容
  相同（benign）。
- **单观众损失**：memo 填充=一次编码快照 memcpy（~1-3µs），相对其压缩 ~30µs 噪声级。
- 压缩段 memo 沿用 0242 全部论证（threshold 戳/level 纪元/自描述段/外来 buffer 回退）。

## 2. 基准（SectionBroadcastBench，模型=480 方块变更）

载荷模型：480 变更（14 项小调色板 + 聚簇位置——真实红石/爆炸批量更新形态，首轮全随机模型
ratio=0.99 被自检证伪后修正），编码 1,924B、压缩 1,220B（ratio 1.58）。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 34,364.342 ± 1,093.890 | 每观众：varlong 走查 + DEFLATE + 帧 |
| afterFill | 34,904.733 ± 1,554.822 | 首观众：before + 双快照（CI 与 before 重叠） |
| afterHit | **4,530.736 ± 801.534** | 后续观众：编码 memcpy + 压缩段 memcpy + 帧 |

- **命中路径 ≈ 7.6×/观众**（CI 分离：[3,092, 5,969] vs [32,159, 36,569]）；每观众每次批量广播
  省 ~30µs（编码走查 + 小包 DEFLATE 各半）。
- 光照包（批次 72 挂压缩 memo）本批补 arm 编码 memo：~16KB codec 走查（BitSet/集合写出）→
  memcpy，机制同源（本报告论证覆盖，无单独基准——与 section 域同构）。
- 自检 main ALL OK：20 观众两路径 wire 字节逐观众全等 / varlong 编码确定性 / 编码快照=新鲜编码 /
  压缩比带内。

## 3. 未做与留档

- chunk 共享实例的编码 memo：内存权衡（+40KB/chunk 驻留）未做——chunkData buffer 复用已让编码
  剩余成本为 heightmap/BE/光照段写出（~10-15µs/观众），ROI 低于内存代价；如需启用可对
  `papoEnableWireMemo` 补 arm 调用。
- 其他 broadcast 同实例大包扫描：`broadcastAll` 小包为主（<threshold 不压缩，编码 µs 级）；
  explode 因 per-player 击退向量否决——多观众出站冗余面至此**封闭**。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0244）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 73 记录）。
