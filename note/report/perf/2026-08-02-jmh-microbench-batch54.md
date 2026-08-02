# 批次 54 JMH 微基准报告（2026-08-02）— 网络 pivot

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-<latest>.json`（PacketSendActionBench）。
基准为**语义复刻**，`main()` 自检 ALL OK（不依赖 Blackhole 实例化——按 build.md 用 Object sink）。
结论：**0207 PacketSendAction 消除 delegate lambda + AtomicBoolean→boolean，per-queued-packet 2.03× 提速（CI 不重叠）**。网络出站队列路径（突发负载下 canSendImmediate 不命中、queue 非空时）的真实分配源。

## Connection 出站队列（PacketSendActionBench）— 补丁 0207

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_createAndAccept vs after_createAndAccept | 5.379 ± 0.314 | 2.651 ± 0.245 | **2.03×** | CI [5.07,5.69] vs [2.41,2.89] 不重叠。每 op = 新建一个 PacketSendAction + accept（drain 时 delegate/direct→sendPacket）。before 3 对象（action + delegate lambda + AtomicBoolean），after 1 对象（action 内联 listener/flush 字段 + boolean consumed） |

- **分配**：before 每 PacketSendAction 分配 3 对象（PacketSendAction + 捕获三元组的 delegate lambda + AtomicBoolean）；after 仅 1 对象（PacketSendAction，listener/flush 内联为字段，consumed 为 boolean 字段）。突发负载下出站队列路径（send 的非 canSendImmediate 分支）每排队包省 2 对象。

## 等价性支点（源码实证）

- **0207**：`Connection.WrappedConsumer` 的 `consumed` 由 `AtomicBoolean` 降为 `boolean`（tryMarkConsumed 改 `if(consumed) return false; consumed=true; return true;`，语义=首次 true/再 false，与 CAS 一致）。`PacketSendAction` 加 listener/flush 字段 + override `accept(connection)` 直调 `connection.sendPacket(packet, listener, flush)`，删除 delegate lambda（`super(null)`，delegate 对本子类未用）。
- **线程安全**：`tryMarkConsumed`/`isConsumed` 仅在 `processQueue`（:534/:546）调用；`processQueue` 经 `flushQueue`（:507 主线程 play 阶段 / :511 synchronized login 阶段）单线程访问，同一 Connection 不并发 → CAS 非必要，boolean 安全。
- **accept 语义**：原 delegate `connection -> connection.sendPacket(packet, listener, flush)` 经 `WrappedConsumer.accept`→`delegate.accept`；新 `PacketSendAction.accept` 直调同方法同参 → 调用序列逐字一致。`WrappedConsumer(action)`（runOnceConnected:442）/`WrappedConsumer(Connection::flush)`（flushChannel:491）仍用非空 delegate（accept 未 override），不受影响。
- main 自检：before/after 的 accept 均触发 sendPacket（conn.sends 递增一致）；tryMarkConsumed 首次 true/再 false、isConsumed true，两路径一致。ALL OK。

## 验证链

compileJava（`--no-configuration-cache`）✓ BUILD SUCCESSFUL → 全量 test ✓ BUILD SUCCESSFUL（零 FAILED）→ rebuildPatches ✓（0207 正确格式）→ 恢复法保留。

## 网络 pivot 后续

- **高风险高价值（需授权 + live 验证）**：`Connection.sendPacket` 行451 `execute(() -> doSendPacket(...))`——主线程发包（非 netty event loop）每包分配 lambda。消除会让 `sentPackets++`（普通 int，:82）从 event-loop 移到主线程，涉跨线程语义；需 live-server 并发压测验证。这是网络出站最高频分配点。
- 待评估：PacketBundleUnpacker 每包 `list::add` Consumer（零风险低价值，EA 可能消除）、VecDeltaCodec base 缓存（零风险低价值）。
