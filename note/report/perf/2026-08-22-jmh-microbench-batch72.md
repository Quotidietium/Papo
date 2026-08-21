# 批次 72 JMH 微基准报告（2026-08-22）— 光照增量广播压缩 memo（0243）

主题：**多玩家网络稳定**——0242 的压缩 memo 机制扩展到**光照增量广播域**。
`ChunkHolder.broadcastChanges` 把**同一个** `ClientboundLightUpdatePacket` 实例发给每 chunk 的全部
追踪玩家（`broadcast(players, packet)` 同实例派发）：黄昏/黎明光照传播期每 tick 多个 chunk × N
玩家，每连接对相同的 10-40KB 各自 DEFLATE。本批仅让 `ClientboundLightUpdatePacket` 实现
`PapoSharedWireMemo.Carrier` 并在构造时挂 memo——PacketEncoder/CompressionEncoder 的 0242 机制
原样生效（memo 段 = 首连接压缩输出的自描述快照，后续连接 memcpy 直放）。

环境：JDK 21.0.10（Windows 11，JDK Deflater 回退），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性（0242 机制同源 + 本域特有论证）

- **同实例广播**：vanilla `ChunkHolder.broadcast` 本就把同一 packet 对象发给 N 玩家——0242 的
  "多连接重复压缩相同字节"问题在此天然存在，memo 消除的是纯冗余。
- **内容确定性**：包不可变（x/z/lightData 构造后只读），codec 只写整数/BitSet/byte 集合，
  locale 无关 ⇒ 各连接编码字节相同，memo 段（= 某连接自身 deflate 输出）逐字节可重放。
- **瞬态内存**：该包为每次 broadcast 调用新建（不驻留 holder），memo（段 ~16KB）随包 GC——
  无长期内存面（与 0241/0242 的 holder 驻留缓存不同）。
- threshold 戳 / level 纪元 / 竞态填充 / 0217 身份通道 / 外来 buffer 回退：全部沿用 0242 已论证
  机制，无新接触面。
- **单观众场景**（N=1 的 relight/单玩家光照包）：memo 填充多付一次段快照 memcpy（~3µs），
  相对其自身 deflate（~311µs）为 1% 噪声。

## 2. 基准（LightBroadcastBench，JDK Deflater 真实压缩）

载荷模型 32,770B（16×2048B 光照 section：过渡区随机 nibble 对 + 全亮/全暗大块），压缩比
**1.98×**——比区块包（4×）更难压缩 ⇒ 单次 deflate 更贵，memo 收益更大。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 311,336.888 ± 9,197.287 | 每观众：序列化 + DEFLATE + 帧 |
| afterFill | 314,831.804 ± 15,820.602 | 首观众：before + 快照（CI 与 before 重叠） |
| afterHit | **4,462.938 ± 256.111** | 后续观众：序列化 + memo 段 memcpy + 帧 |

- **命中路径 ≈ 69.8×/观众**（CI 完全分离）；每观众每次光照广播省 ~307µs 压缩 CPU。
- 场景外推：黄昏传播期每 tick 50 个 chunk 光照变化 × 20 追踪玩家 ≈ 50×19×307µs ≈ **292ms/s
  CPU 消除**（光照传播持续数十秒~数分钟，此前为各连接 event loop 的持续负载）；单连接独享
  chunk 的场景无收益也无损失（填充开销噪声级）。
- 自检 main ALL OK：20 观众两路径 wire 字节逐观众全等 / Deflater 确定性 / threshold 戳不匹配
  不命中 / 压缩比带内（1.5-4×）。

## 3. 未做与留档

- **编码（PacketEncoder）阶段 memo**（chunk 与 light 通用）：每观众 codec 走查 ~10-20µs 次级
  成本；chunk 共享实例驻留 holder，编码快照（~40KB/chunk）内存权衡未定，light 瞬态包无内存
  顾虑——留档后续（light 优先）。
- 其他大包广播点（`ClientboundLevelChunkWithLightPacket` 已由 0242 覆盖）：`broadcastAll` 的
  小包（< threshold 不压缩）收益趋零，不扩展。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0243）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 72 记录）。
