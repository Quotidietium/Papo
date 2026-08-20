# 批次 61 JMH 微基准报告（2026-08-20）— 入站零拷贝帧提取 + 死仪表门控 + tick 尾微项

环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。netty 4.2.7。批次 61 由两路新 survey（入站路径 / tick 尾 flush 窗口+带宽监控）驱动。

## 1. 0222 — Varint21FrameDecoder 帧提取 readBytes → retainedSlice（入站零拷贝）

**热点**：原版 `out.add(in.readBytes(i))` 对**每一入站帧**分配新 buffer 并 memcpy 全部载荷——全部入站流量在 splitter 处被完整拷贝一次（移动包、交互包等高频小帧每帧一次分配+拷贝）。

**改法**：`in.retainedSlice(readerIndex, i) + skipBytes(i)`——netty `LengthFieldBasedFrameDecoder.extractFrame` 同型先例。下游共享同一池化内存（引用计数持有）。**下游只读已核**：CompressionDecoder 仅索引/释放（`in.clear()` 只动 slice 自身索引、`ensureCompatible` 对 direct slice retain 直传）、PacketDecoder codec 只读。协议切换窗口 FlowControlHandler 排队 slice 至多钉住一个读批次（LTFBD 同语义）。

### 结果（InboundFrameBench，EmbeddedChannel 真实 BTMD/cumulation 语义，16 帧 × 300B）

| 指标 | before | after | 倍率 |
|---|---|---|---|
| avgt ns/op | 402878.572 ± 33751.289 | 4327.796 ± 17.095 | 93×（紧密循环） |
| **gc.alloc.rate.norm（1 fork -prof gc）** | **6774.784 B/op** | **704.030 B/op** | **9.6× 分配消除** |

- **诚实定位**：93× 的时间差被紧密分配循环的 GC 摊销放大（探针复现：before 440-535µs、after 36µs，校验和一致）；机制性收益是**每帧省 1 次 buffer 分配 + 1 次全载荷 memcpy**（6070 B/op ÷ 16 帧 ≈ 每帧 380B 分配：300B 数组 + Unpooled 包装 + BTMD 机器）。真实服务器收益与入站流量成正比的 GC 压力削减。
- 自检 main ALL OK：①两链帧数与内容校验和一致；②半帧累积（cumulation 留存）+ 补齐路径一致；③万帧引用计数压力无泄漏。

## 2. 0221/0224 — PacketProcessor/Connection.tick 死仪表门控

**热点**：Paper 的 "detailed watchdog information" 簿记——每入站包在**主线程**付出 `ConcurrentLinkedDeque.push`（1 Node 分配 + 2 CAS）+ `AtomicLong.getAndIncrement`（1 volatile CAS）+ `deque.pop`（1 CAS）。**全仓库零读取方**（survey 全树 grep 实证：`packetProcessing`/`totalMainThreadPacketsProcessed`/两个 getter 仅出现在定义与写入点，watchdog 线程不读包状态）。

**改法**：`static final boolean PAPO_TRACK_PACKET_PROCESSING = false` 包住三处写入（PacketProcessor.handlePacket 两处 + Connection.tick 一处），JIT 整段移除；字段与 getter 保留形状（返回空/0——与原效果一致，deque 本就在同一调用内 push 后 pop）。

### 结果（DeadInstrumentationBench）

| 方法 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| before_bookkeeping vs after_gated | 15.701 ± 0.982 | 0.258 ± 0.008 | **~15ns/包**（CI 不重叠） |

- 主线程每入站包 -2 分配 -3 CAS；高入站负载（移动/交互包风暴）下主线程直接减负。
- 自检 ALL OK（簿记语义 push/pop 配对验证）。

## 3. 0220 — PlayerChunkSender k-近邻 scratch 数组字段化（批次 58 survey A-1 遗留）

`collectChunksToSend` 突发分支每次调用 `new long[floor]`/`new int[floor]`（floor≤64，跑图/登录突发期每 player 每 tick）→ 提升为实例字段 + grow-on-demand（每 player 单实例、单一主线程调用点、无重入；仅 [0, sel) 被读且每次全量重写）。分析性收益：突发期每 player-tick 省 2 个小数组分配（JMH 不适用：分配消除机制，先例 0219/0217 注释论证）。

## 4. 0223 — Connection flush 任务缓存

`flush()` 非 eventLoop 分支每 tick 每 player 捕获一个 `() -> this.channel.flush()` lambda → 缓存为每连接一个 `Runnable` 字段（执行时读 channel，与原 lambda 语义逐字一致；eventLoop FIFO 保序）。分析性收益：每 player-tick 省 1 分配（~百人服数百 obj/tick 年轻代垃圾）。survey 确认正常路径无 WrappedConsumer 分配（垂死连接才走）。

## 5. 否决/不做（survey 结论，证据留 optimizations.md）

- **主线程入站投递批量化**：不存在批次 60 型机会——Paper 的 `scheduleIfPossible` 直接传 packet+listener（无逐包 Runnable），且 `pollTask` 集成 + unpark 投机唤醒使排水连续化（MinecraftServer.java:1177/1180/1225/1235）。
- **RunningOnDifferentThreadException 成本**：单例 + 空 fillInStackTrace（构造时 setStackTrace(空)），零成本，无优化面。
- **RegistryFriendlyByteBuf 复用 / ListenerAndPacket 池化**：低价值高风险（~130 个 codec 的逃逸审计 / jctools 依赖），不做。
- **出站带宽监控**（/paper netstat + prepender 计数 + tickSecond 每秒窗）：survey 给出完整最小设计（~150-180 行，纯观测零风险），**留批次 62 作为独立交付**。
- 微项（SGPLI level() 解引用、limiter 快照化）：趋零或不满足 reload 语义，不做。

## 验证链

compileJava（--no-daemon）BUILD SUCCESSFUL → 自检 ×2 ALL OK → JMH + gc 探针 → rebuildPatches → applyPatches → 全量 test（见 optimizations.md 批次 61 记录）。
