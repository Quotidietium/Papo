# Papo 性能基准测试

对 Papo 已交付优化的**优化前/优化后**实现做 JMH 微基准对比。
基准代码忠实复刻补丁中的真实实现（同一算法、同一数据结构、同一热路径形态），
在独立 JVM 中运行，无需启动 Minecraft 服务器。

## 运行

```bash
cd benchmark
./run.sh                # 全部基准
./run.sh 'NbtArray.*'   # 按 JMH 正则过滤
```

- 参数：`-wi 3 -i 5 -f 2 -r 1s -w 1s`（3 轮预热 + 5 轮测量 × 2 fork）
- 原始结果输出到 `results/`（txt + json）
- 汇总报告在 `../note/report/perf/`

## 覆盖的优化

| 基准类 | 对应补丁 | 对比内容 |
|---|---|---|
| `NbtStringReadBench` | 0067 | `DataInputStream.readUTF` vs ASCII 快速路径（含非 ASCII 回退对照） |
| `NbtArrayReadBench` | 0068 | 逐元素 readInt/readLong vs readFully + ByteBuffer 大端批量解码 |
| `CompoundTagIterBench` | 0040/0047 | keySet()+get() vs fastutil fastIterator |
| `OptionalProtocolBench` | 0048 | `Optional<Float>` 协议 vs boolean+字段协议 |
| `StreamArgminBench` | 0069 | stream min() vs 手动 argmin 循环 |
| `DistSqrAllocBench` | 0045/0058 | new Vec3 + distanceToSqr vs 纯标量 distToCenterSqr |
| `EarlyExitBench` | 0070 | 构建完整列表判空 vs 命中即早退 |
| `Utf8StringWriteBench` | 0084 | 临时 ByteBuf + 拷贝 vs utf8Bytes 精确长度 + 直写目标（含字节级等价自检 main） |
| `LongArrayWriteBench` | 0085 | 逐元素 writeLong vs internalNioBuffer + LongBuffer 批量（含字节级等价自检 main） |
| `SlotTakeOptionalBench` | 0087 | Optional.ofNullable + ifPresent vs @Nullable 内部路径 |
| `IngredientOptionalBench` | 0088 | Optional map/orElseGet vs 三目 |
| `SleepStatusBench` | 0089 | 双 stream 遍历 vs 单遍循环 |
| `SavedTickFilterBench` | 0090 | stream filter/toList vs isEmpty 早退 + 预尺寸循环 |
| `NbtStringWriteBench` | 0092 | `DataOutput.writeUTF` vs ASCII 快速路径（含字节级等价自检 main：空/短/长分块/utf8 回退/NUL 回退/65535 边界/超长异常） |
| `NbtWriteAdapterBench` | 0095 | 每次分配 `ByteBufOutputStream` vs ThreadLocal 轻量 DataOutput 适配器（含字节级等价自检 main：对真实 Netty 4.2.7 逐方法比对 + 树形写出） |
| `InventoryTriggerScanBench` | 0093 | 背包全槽位扫描（组件查找模型化）vs hasListeners 早退 |
| `ChunkSelectBench` | 0203 | `Comparators.least` 装箱 Long 流选块 vs 原语 k 近邻（含等价性自检 main：多组随机输入下选出的 floor 个最近"距离多重集"一致） |
| `TrackCanSeeBench` | 0204 | 已追踪对每对重算 canSee（HashMap 查找）vs seenBy.contains 短路跳过（含等价性自检 main：稳态计数 + 未追踪-被hide 路径一致） |
| `FingerprintHardeningSelfCheck` | 0205/批次51 | 非性能——指纹泄露加固的行为自检（复刻 brand/status resolve + plugin-channels shouldBroadcast 全分支，18 项断言） |
| `EntityDataPairingBench` | 0206 | trackedDataValues 每 dirty 刷新（getNonDefaultValues 全扫）vs 延后到 pairing（仅 packDirty）（含自检 main：packDirty/getNonDefaultValues 输出一致） |
| `PacketSendActionBench` | 0207 | PacketSendAction 3 对象（action+delegate lambda+AtomicBoolean）vs 1 对象（直调 accept+boolean）（含自检 main：accept 触发 sendPacket + tryMarkConsumed 语义一致；用 Object sink 不依赖 Blackhole） |
| `BundleUnpackerBench` | 0208 | 帧编码每包 unbundlePacket+list::add Consumer vs 非 bundle 直 list.add（含自检 main：非 bundle list 内容一致） |
| `SendLambdaBench` | 0209 | Connection.sendPacket 非 event loop 时 execute(lambda) vs 直调 doSendPacket（含自检 main：writes/sentPackets 一致） |
| `EncodeAllocBench` | 0213/0214 | 帧缓冲默认 256B+重分配 vs ioBuffer(3+n) 精确分配；编码 256B 增长链 vs 按类尺寸提示（真实 netty PooledByteBufAllocator；含自检 main：帧/编码字节逐字节一致） |
| `DeflateBoundBench` | 0215 | 压缩输出 n+1 起步扩容续压 vs DEFLATE 实测上界一次到位（JDK 回退路径建模；自检 main：上界充分性 + before 重试触发面 + 两路径压缩流逐字节一致——首版公式被此自检证伪后据实测修正） |
| `ScoreboardGateSelfCheck` | 0210-0212 | 非性能——记分板冗余广播门控行为自检（等值跳过/真变更广播/同实例 vanilla 奇偶性/双发去重恰少一个逐字节相同的包，全场景对比 vanilla/papo 模型） |
| `OutboundFrameBench` | 0217 | 出站三阶段拷贝链 vs headroom 直通（EmbeddedChannel 真实 netty 管线；含自检 main：9 尺寸×2 可压性字节级全等 + 阈值边界 + varint 反向写全档位 + 万次引用计数） |
| `BatchedDispatchBench` | 批次60否决 | 逐包 execute(lambda) vs MPSC 批量排水（真实 NioEventLoopGroup；两轮实测否决证据：CLQ 4.8× 回退、MPSC 1.49× 劣化；含并发顺序/排水边界竞态自检） |
| `SendFastPathBench` | 0218 | 立即发送判定 instanceof 链先 vs 主线程廉价臂先（@State 字段输入防常量折叠；含布尔等价矩阵自检） |
| `InboundFrameBench` | 0222 | 入站帧提取 readBytes 拷贝 vs retainedSlice（EmbeddedChannel 真实 BTMD/cumulation 语义；含帧内容/半帧累积/万帧引用计数自检；gc 探针 9.6× 分配消除） |
| `DeadInstrumentationBench` | 0221/0224 | 每包 watchdog 簿记（CLD push/pop + AtomicLong）vs 门控移除 |
| `NetstatCounterSelfCheck` | 0225 | 非性能——/paper netstat 字节计数正确性自检（出站/入站计数==wire 字节、半帧不计数、每秒窗口清零守恒） |

## 依赖

`run.sh` 会自动下载缺失的 jar 到 `lib/`（不入库）：JMH 1.37
（jmh-core / jmh-generator-annprocess / jopt-simple / commons-math3）、
fastutil 8.5.18 与 netty-buffer/netty-common 4.2.7.Final（均与服务器运行时同版本）。

## 方法学说明

- 微基准衡量的是**单点代码路径**的吞吐/时延差异，用于验证优化方向正确、量级可信；
  服务器端到端 TPS 收益取决于该路径在真实负载中的占比（如 NBT 读取在区块加载期密集）。
- before/after 输入数据完全一致；分配敏感的用例（Optional、Vec3）差距包含 GC 压力，
  在真实服务器高分配场景下收益通常比微基准更大。
