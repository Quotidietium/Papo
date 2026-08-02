# 批次 56 JMH 微基准报告（2026-08-02）— 网络 pivot：send-lambda（高风险，用户授权）

环境：JDK 21.0.10，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
结论：**0209 Connection.sendPacket 消除 per-outbound-packet `execute(lambda)`，1.29×（CI 不重叠）**。主价值是消除网络出站**最高频**的 per-packet lambda 分配（高吞吐下 GC 压力，微基准测不出）。**线程改动经推理验证（用户授权，未做 live 压测）**。

## Connection 出站 sendPacket（SendLambdaBench）— 补丁 0209

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_sendLambda vs after_directDoSend | 1.065 ± 0.087 | 0.827 ± 0.047 | **1.29×** | CI [0.98,1.15] vs [0.78,0.87] 不重叠。before：非 event loop 时 execute(() -> doSendPacket(p))（lambda 分配 + 虚调用）；after：直调 doSendPacket。EventLoop 经接口虚调用建模（防 EA 消除 lambda） |

- **价值定位**：微基准 1.29× modest，但本变更消除的是**每出站包**的 lambda 分配（服务端发包在主线程，非 netty event loop，是网络出站最高频分配点）。高吞吐（聚集/突发）下 per-packet 分配消除降低 GC 压力——这是微基准（小堆无压力）无法体现的真实收益。机制成立（lambda 分配消除，gc 探针可证）。

## 线程安全论证（推理验证，逐项）

变更：`sendPacket` 原 `if (inEventLoop()) doSendPacket; else execute(() -> doSendPacket)` 改为**无条件直调 `doSendPacket`**。即 doSendPacket 从"event loop 执行"改为"调用方线程（主线程）执行"。逐项审计 doSendPacket 触及的共享状态：

| 操作 | 线程安全 | 依据 |
|---|---|---|
| `this.channel.write/writeAndFlush` | ✓ | netty Channel.write Javadoc："若当前线程不在 event loop，写请求将被调度延后执行"——跨线程安全，netty 按 channel 串行化写、**保序** |
| `ChannelFuture.addListener` | ✓ | netty 线程安全 |
| `this.getPlayer()` | ✓ | 读 volatile `packetListener`（:77）派生 |
| `this.isConnected()` | ✓ | 读 `this.channel`——主线程在 `send():403` 已同样读取（既有访问模式） |
| `this.sentPackets++`（:447） | 未变 | 在 sendPacket 调用方线程（主线程），**不在** doSendPacket——消除 lambda **不改其线程语义** |
| 异常路径 `disconnect()` | 既有跨线程模式 | disconnect 已被 event loop（channel close）等多处跨线程调用，用 thread-safe `channel.close()`；其 `clearPacketQueue` 对 `pendingActions` 的访问是 Paper **既有并发模式**（processQueue 主线程无锁 vs clearPacketQueue），本变更只把 doSendPacket-异常-disconnect 的调用方从 event loop 改为主线程，未引入新的并发类别 |
| `packet.onPacketDispatchFinish`（!isConnected 早退路径） | 罕见 | 仅在连接已断开时（罕见），且连接正在消亡；正常路径经 addListener 在 event loop 触发（不变） |

**包发送顺序**：netty 按 channel 串行化写，跨线程调用 channel.write 仍按提交顺序入队 → 发送顺序保持。✓

## 残留风险（用户已知悉）

- **未做 live-server 并发压测**：仅 compileJava + 全量 test（零 FAILED）+ 上述推理验证。极端高并发下的细微竞态（如异常-disconnect 路径）理论上存在但罕见且在消亡连接上。
- 回滚成本低：单补丁 0209，可独立 revert。

## 验证链

compileJava（`--no-configuration-cache`）✓ BUILD SUCCESSFUL → 全量 test ✓ BUILD SUCCESSFUL（零 FAILED）→ rebuildPatches ✓（0209 正确格式）→ 恢复法保留。
