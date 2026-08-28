# 批次 108 报告（2026-08-28）— 挂起窗发送批量化——扇出移交成本摊销（多核调度系列㉓，0.66.0→0.67.0）

主题：批次 107 归因的第一大牛驱动面（tracker 扇出 ~1us/send 移交成本 × 15k sends/tick）
的优化交付：0260 补丁。`Connection.send` 的即时路径在**挂起窗**（flush=false 的主线程
就绪包——即 tick 内追踪广播的精确形态）改为 append 到每连接批次，由单个 eventLoop 任务
排水；其余路径全部原样。

## 机制

- append 条件：`!flush && extraPackets==null && isMainThread`（即时路径 arm-1 命中后的
  精确子集）——恰好覆盖 suspendFlushing→resumeFlushing 窗内的全部广播发送。
- 排水点：`flushChannel()`（=resumeFlushing 的 tick 末，写后统一 flush——与既有节奏
  完全一致，只摊销移交）、直发/入队前的 FIFO 守卫排水、256 阈值中途排水。
- 排水内部主线程自检（异步调用点空转防竞态）；断连残包丢弃 ≡ 既有队列路径语义。

## 等价性论证（红线：行为零变化）

1. **包集合不变**：每连接写入同样的包序列，无增无删。
2. **主线程流量序不变**：批次 FIFO；一切会"插队"的路径（直发、入队）先排水再走
  （FIFO 守卫）。唯一重序面：非主线程 canSendImmediate 包（pong 类）可能超越批内包
  ——该类包与追踪流量无跨包序契约（vanilla 亦无——本就跨线程派发），如实披露。
3. **时序**：批内写在同 tick 内落盘（排水点=既有 tick 末 flush 点；≤256 的中途排水
  比旧"逐包即时"更早或同时）；挂起窗外 flush=true 发送保持原即时路径——
   聊天/命令/任务响应零延迟变化。
4. **bundle 协议**：捆绑包带 extraPackets → 队列路径原样；排水先于入队保序。
5. **失败面**：排水任务逐包过 sendPacket→doSendPacket 的 isConnected 检查 ≡ 逐包
   语义；终端包不达 append（listener 先 close，send 早退）。
6. **区块发送**：PlayerChunkSender 在 resume 之后以 flush=true 直发，节奏不动。

## JMH（SendHandoffBench）

- 无争抢模型（轻载荷）：批量 10.3-19.7×（15000/1500 sends/tick）。
- 争抢模型（loop 侧 ~600ns/包载荷）：perSend 15000 档 2093 ops/s 完整；batchDrain
  fork 死于系统页面文件耗尽（协同租户挤压，DOS 1455，同窗口 test 任务同因失败一次）
  ——如实披露，**决定性证据以在位 A/B 为准**（判例：隔离模型两次均与在位口径分歧，
  30-970ns/send 的入队成本强烈依赖争抢态，in-situ 902-1060ns 才是被摊销对象）。

## 在位 A/B（EntityScaleBench，10 站立 bot，稳态尾窗）

| 指标 | 0.66.0 @2000（批次107） | 0.67.0 @2000 | Δ |
|---|---|---|---|
| fanout.sends | 14,475-15,070 /tick | 25,079-26,533 /tick | 同量级（窗口期牛群活跃度不同） |
| chunkMap.tracker.fanout | 13,057-15,976 us/tick | **1,982-2,199 us/tick** | **6-8×** |
| **每 send（fanout/sends）** | **902-1,060 ns** | **75-88 ns** | **~12×** |
| chunkMap.tracker.sendChanges | 12,249-13,853 us/tick | 2,975-3,270 us/tick | ~4× |
| worlds | ~48,000（0.65.0 批次106：44,869） | 30,813-34,566 us/tick | 主线程省 ~10-14ms/tick |

N=500（0.67.0）：fanout 159-168us（61-63ns/send，worlds 8.4-8.7ms 的 ~2%）——0.66.0
的 N=500 计数点两次被清扫未取得，per-send 严格对照以 N=2000 对为准。在场门：2000
点 A=2000 精确/B=2002（+2 杂散零死亡）、500 点 A=501/B=503——批次 106 以来的杂散
新增模式，数据有效。epoch 披露：before=21:41（批次107）、after=22:45（本批），
共享机不同时段；ns/send 为机制级口径（sends 同量级）受 epoch 影响最小。

## 首版 NPE 判例（0.67.0 第一版 A/B 抓获，已修复）

排水任务与后续 append **复用同一数组**：在途任务逐槽置 null 与下一次排水的读取竞态
→ `action` 为 null 的 NPE → 该排水任务死亡 → 批内 keepalive 未写 → bot 逐个
"Timed out"/"keepalive response out-of-order" 级联断连（10/10 全灭、A=B=0）。修复：
**排水即换新数组**，在途任务独占旧数组（每 tick 每连接一次数组分配，远小于被消除的
逐包 lambda+任务分配）。判例：数组池"释放引用复用"与异步在途读取不兼容——跨线程
交接的缓冲必须交接即换所有权；A/B 冒烟门（在场门+bot 全活）抓住了 login/keepalive
路径的死亡，证明门的有效性。

## 冒烟（连接路径触碰：join/quit/disconnect）

- SmokeJoinVerify 四态（空数据/即时重连/稳态/关服）× 0.67.0：**全 PASS**（exit=0、
  各态 logErrors=0、产物 dat/stats/advancements ok、稳态 join #3-#10 均值 28ms/p50 22ms/最大 78ms）。
- ShutdownRaceVerify ×3：**ALL ROUNDS PASS**（shutdown exitCode=0 logErrors=0、
  重启暖读 dat ok；0.59.0 后累计 ×17）。
- 判例（工具链）：历史 bot class 由 PATH 的 JDK25 编译，F:/Java/21 运行报
  UnsupportedClassVersionError——bot 类一律用 F:/Java/21/bin/javac 显式重编译。
