# 批次 62 报告（2026-08-20）— /paper netstat 出站/入站带宽监控（纯观测）

批次 61 survey 预定的独立交付。非性能优化——为带宽主题提供**每玩家/全服真实线上字节数据**（后续带宽优化的度量基础），默认零输出零成本（每帧一次无竞争 AtomicLong add）。

## 机制

- **计数点**（帧编码/解码器，看见的就是线上字节）：
  - 出站 `Varint21LengthFieldPrepender`：两条路径（0217 headroom 直通 + 回退拷贝）均计 `帧载荷 + 帧长 varint 字节数`——位于压缩之后、保长加密之前，即客户端收到的精确 wire 字节。
  - 入站 `Varint21FrameDecoder`：对称计数（0155 内联解析处，与 BandwidthDebugMonitor.onReceive 同点同式）。
- **接线**：`Connection` 每连接两个 `AtomicLong`（eventLoop 无竞争 add），经 `configureSerialization` 新参传入帧编解码工厂；服务端 `ServerConnectionListener` 把连接创建上移以传入计数器（构造顺序改动 inert——构造器不触碰 channel 状态）；客户端路径对称接入；内存连接不计。
- **快照**：`Connection.tickSecond()`（既有 20-tick 即 1 秒门，与 averageSentPackets 同相位）`getAndSet(0)` → lastSec + 累计总量。
- **暴露**：`/paper netstat [topN|all]`（默认 top 10，按出站 B/s 降序）：全服行（out/in B/s + totals）+ 每玩家行（out/in B/s、pkt/s、累计出站）。权限走 Paper 既有 `bukkit.command.paper.netstat` 自动注册（OP 默认）。

## 正确性自检（NetstatCounterSelfCheck，ALL OK）

1. **出站**：7 尺寸（1B-30KB）× 帧化链（EmbeddedChannel 真实管线），计数器累计 == 出站 buffer 真实字节数总和。
2. **入站**：多帧连发 + 半帧（不完整帧不计数），计数 == 全部完整帧 wire 字节。
3. **窗口语义**：两次 getAndSet 快照清零、累计守恒、无残留。

## 开销评估（无 JMH——每帧一次无竞争 add 的量级已知）

计数点在 eventLoop 单线程上下文，`AtomicLong.addAndGet` 无竞争（每连接独立 counter）≈ 数 ns/帧，与 0215/0217 的帧处理成本（百 ns-µs 级）相比可忽略；不执行命令时零成本。诚实说明：未单独 JMH（先例：fingerprint-hardening 行为自检替代）。

## 风险

- 纯观测：计数器只写不读行为（命令执行时读快照），无 promise/buffer/flush 耦合。
- `configureSerialization` 签名扩展（NMS 内部静态方法，3 个调用点全更新）。
- prepender 新增实例字段（每连接实例化的 @Sharable，共享仅会导致计数合流不会错计）。
- ServerConnectionListener 连接创建上移 4 行（构造 inert）。

## 验证链

compileJava（--no-daemon）BUILD SUCCESSFUL → NetstatCounterSelfCheck ALL OK → rebuildPatches（0225）→ applyPatches → 全量 test（见 optimizations.md 批次 62 记录）。
