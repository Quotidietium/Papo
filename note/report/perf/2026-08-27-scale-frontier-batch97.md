# 批次 97 报告（2026-08-27）— R2 轮开篇：真并发规模前沿探测（40/80/120/160 bot 阶梯，多核调度系列⑬）

主题：R1（批次 78-96）的"40 bot 上限"实为 harness 缺陷上限，主线程饱和拐点从未被逼近。
本批修复 harness 真并发后完成 40/80/120/160 四点阶梯（每点 6 分钟稳态窗口，种子 papo90，
0.59.0 jar，全部点位 exit 0 + 稳态零 tick 滞后 + 零门错误）。**无服务器代码变更，版本保持 0.59.0。**

## Harness 真并发修复（本批 benchmark 侧变更）

- **commonPool 欠启动**：原 `CompletableFuture.runAsync`（并行度 31）在 bot>31 时超额 bot
  等前序任务结束才启动——批次 96 的"40 bot"实际峰值并发 ≈31-32。改为每 bot 专用线程
  （纯阻塞 IO 无共享锁），N bot = N 真并发；bot≥80 服务器堆 3G→4G。
- **良性突断过滤器第三形态**：160 bot 退出风暴出现 U+FFFD 替换符乱码形态
  （`Files.readAllLines` UTF-8 解码 GBK 字节的产物；此前只有 5 连问号/中文两形态），
  补 `indexOf('\ufffd')` 规则；真实异常消息不含 U+FFFD，规则安全。

对照：真 40 并发 worlds=2457us（vs 批次 96"40bot" 1734us 的 ~31 并发口径），修复即数据修正。

## 最终阶梯（各点取污染后复测的干净轮：40-r3 / 80-r2 / 120-r2 / 160-r3）

| 相位 avg/tick | 40 bot | 80 bot | 120 bot | 160 bot | 160/40 |
|---|---|---|---|---|---|
| worlds | 2457us | 3634us | 5799us | 6511us | 2.65×（亚线性） |
| — level.chunkSource（含实体追踪器） | 1469us | 2343us | 4166us | 4506us | 3.07×（≈线性） |
| — level.entities | 889us | 1186us | 1514us | 1873us | 2.11×（亚线性） |
| **connection** | **630us** | **1487us** | **3630us** | **5858us** | **9.30×（幂≈1.6-2.0）** |
| sendChunks | 58us | 79us | 93us | 93us | 1.60× |
| 顶层相位合计 | 3149us | 5204us | 9526us | 12467us | 3.96× |
| 主线程利用率（@50ms） | 6.3% | 10.4% | 19.1% | 24.9% | |

（level.* 为 worlds 内分解，chunkSource ⊂ tickPending+misc 有重叠，故顶层合计只计
worlds+connection+sendChunks+players+functions。）

join 窗口 tick 超限（各点首个 400-tick 窗口 wallMs / 目标 20000）：21290@40（+6.5%）/
21578@80（+7.9%）/ 22339@120（+11.7%）/ 23939@160（+19.7%）——并发登录风暴的 tick 代价
随规模单调增长，160 bot 约 4 秒 tick 超限；批次 87-88 join 管线在 4-8×设计规模外仍收敛。

## 发现一：connection 相位是唯一强超线性面（新优化面）

630→1487→3630→5858us：段间幂 2.36×@2×、2.44×@1.5×、1.61×@1.33×——整体 ≈N^1.6，
中段 ≈N²。归因假设（代码级证据链，批次 98 探针证实）：

1. **聚堆玩家实体追踪广播 N² 对**：bot 全部互见（追踪范围内），玩家 updateInterval=2
   （EntityType.java:222），每 tick ≈N²/2 对 send。链：ServerEntity.sendChanges →
   Synchronizer.sendToTrackingPlayers（ChunkMap.java:1343，逐 viewer `connection.send(packet)`）。
2. **主线程 send 两条成本路径**（Connection.java:429）：pendingActions 空 → 内联
   `eventLoop().execute`（成本落在广播现场=chunkSource 相位）；非空 → PacketSendAction
   入队、`Connection.tick()→flushQueue()`（Connection.java:587）排水——**排水在 connection
   相位**。N 越大队列越常非空，逐对成本越向 connection 相位转移（与 chunkSource ≈线性、
   connection ≈二次的观测一致：广播循环本体近似线性分摊，排队-排水段的每包成本在高队列
   压力下上升）。
3. GC 压力（PacketSendAction/lambda 分配随 N²）为候选加速项——批次 98 的 gcMs 窗口计数分辨。

**业务语义**：该面只在大量玩家互相可见（活动服/小游戏服/出生点聚集）时触发；分散玩家
seenBy 小，此项塌缩。120-160 聚堆 bot = 大型事件服的真实画像。

## 发现二：饱和拐点在 160 之外

- 160 bot 稳态利用率 24.9%（仅剖析相位），稳态窗口全部 20000±2ms 无滞后。
- 外推：connection 维持 ~N²（0.23us·N²）→ 单项 50ms 需 N≈460；合计线性趋势 → 50% 利用率
  ≈ 300-400 聚堆 bot。**R1"主线程在真实负载规模非瓶颈"的结论延伸到 160 并发仍成立**，
  但超线性面已实测显现——R1（≤40 真并发）看不到的前沿。
- 多核利用：追踪广播逐对入队/排水全部串行主线程——**结构性可并行面**（per-connection
  排水互相独立），为批次 99+ 候选。

## 运行环境事故与对策（本机为共享机）

- **协同租户**：外部会话/脚本周期性启动 java 工作负载（spigot-api-1.20.1 基准对、
  另一 paper.jar）并多次全机清扫 java.exe——160 首跑（02:22-02:27）与 40 复测（03:04）
  均被击杀（exit 127/1，无 hs_err，全机 java 同时消失）；80/120 首跑疑似被其 CPU 争抢
  污染（120 首跑 connection 5283 vs 干净复测 3630，+45%）。
- **对策**（已进流程）：击杀后带 walkDone 判定重试；污染点全规模复测；解析器输出逐窗口
  min（最少争抢窗口≈干净口径）；跑前快照 java 进程清单核对重叠窗口。
- 本报告表格只采用干净轮数据；污染轮原始日志保留于 results/ 供审计。

## 验证

- 四点干净轮全部 exit 0 / logErrors=0 / 稳态无滞后；40 首跑（2408/537）与复测
  （2457/630）一致性确认 ±5% 内。
- harness 修复对 ≤31 bot 行为零变化；U+FFFD 过滤规则经 40-r3/120-r2 实跑零误报零漏报。
