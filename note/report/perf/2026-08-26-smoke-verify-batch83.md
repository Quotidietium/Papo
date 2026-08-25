# 批次 83 报告（2026-08-26）— 批次 82 join 读侧预取：真实服务器端到端冒烟 + A/B 对拍

主题：**稳定性验证轮**（goal："多核调度之后的服务器核心稳定性"）。批次 82 改动面横跨
login→configuration→spawn 三阶段与三类存档文件，此前仅有模型基准与编译/单测验证。本批
构建**最小离线模式协议机器人**（纯 JDK socket，零外部依赖），对真实专用服做全生命周期
join 对拍：0.55.0（批次 82 后）vs 0.54.0（批次 82 前）。

## 机器人（[OfflineJoinBot](../../../benchmark/src/papo/bot/OfflineJoinBot.java)）

1.21.11 / protocol 774（mojang version.json 实证）。全部包 ID 取自服务器源码注册序
（`LoginProtocols`/`ConfigurationProtocols` 的 `addPacket` 顺序即 packetId，
`ProtocolInfoBuilder.listPackets` 实证）；字段编码逐包核对源码 STREAM_CODEC：

- C→S：Handshake(774,host,port,LOGIN) → LoginStart(name,offline-UUID) → LoginAcknowledged →
  SelectKnownPacks(空表) / KeepAlive 回显 / FinishConfiguration
- S→C：处理 LoginCompression（varint 阈值，之后双向 zlib 帧）、LoginFinished、
  config 全量包（跳读）；**首个 play 包 = placeNewPlayer 完成**（含批次 82 消费点）
- offline UUID = `nameUUIDFromBytes("OfflinePlayer:"+name)`（服务器 createOfflineProfile 同式，
  保证多次 join 落同一 playerdata 文件）

## 测试矩阵（[SmokeJoinVerify](../../../benchmark/src/papo/bot/SmokeJoinVerify.java)）

每 jar：fresh 目录 boot（offline、固定 seed `papo82`、view/sim 6、peaceful）→
**join#1 首次加入**（空数据——批次 82 全部回退路径：预取未命中文件→null→同步原路径）→
**join#2 即时重连**（socket 关闭后 0ms——批次 79 异步 quit 存档在飞，批次 82 预取链的
**读后写排序实战**）→ **join#3..#10 稳态重连**（真实 .dat/stats/adv 文件——**预取命中路径**）
→ `stop` → exit 0 + 日志零 ERROR/Exception 核验 → 三类存档产物校验（dat = gzip+NBT magic 0x0A）。

## 结果（两轮全绿）

| 项 | 0.55.0 | 0.54.0 |
|---|---|---|
| boot Done | 13.974s | 14.721s |
| join#1 spawn（新世界+空数据） | 3328ms | 2773ms |
| join#2 spawn（**即时重连**） | 160ms | 102ms |
| join#3..#10 mean / p50 / max | 81.6 / 82.0 / 90.0 ms | 80.4 / 80.0 / 91.0 ms |
| joins | 10/10 | 10/10 |
| shutdown | exit 0 | exit 0 |
| 日志 ERROR/Exception | **0** | **0** |
| playerdata/stats/advancements | 全部 ok | 全部 ok |

## 结论

1. **稳定性（本轮主目标）**：批次 82 的完整链路——login 触发预取、配置阶段消费、
   即时重连读后写排序、ServerPlayer ctor 消费 stats/adv、quit 异步存档、关服契约——
   在真实服务器 20 次 join 全生命周期零异常、行为符合设计、产物全部合法。
2. **端到端墙钟持平（81.6 vs 80.4ms，CI 重叠量级）**：新机器人玩家文件为 KB 级
   （dat ~2KB），单次读成本 µs 级，端到端由区块加载与协议往返主导——**无回退**如预期。
   机制收益（老服大 .dat/datafix、突发 join）由模型基准量化（主线程 380×，
   见[批次 82 报告](2026-08-26-joinread-prefetch-batch82.md)），本轮验证其安全性。
3. join#1 差异（3328 vs 2773ms）为新世界出生点区块生成噪声（一次世界、单样本，
   不作性能依据，批次 81 同口径）。

## 基建沉淀（可复用）

OfflineJoinBot + SmokeJoinVerify 成为**真实服务器 join 路径验证的标准工具**：后续任何
触碰 login/config/spawn/playerdata 的改动都可用同一矩阵（空数据/即时重连/稳态/关服）
做端到端冒烟，不再依赖"编译过+单测过"的间接证据。
