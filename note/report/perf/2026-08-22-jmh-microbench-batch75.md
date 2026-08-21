# 批次 75 JMH 微基准报告（2026-08-22）— join 玩家信息广播 memo（0246）

主题：**多玩家网络稳定（join 突发域收尾）**。每次 join，PlayerList 把**同一个**
`ClientboundPlayerInfoUpdatePacket` 实例（新玩家条目：UUID + profile + 皮肤 properties 签名
~0.5-1.5KB，超压缩阈值）发给每个在线玩家——每连接各自编码 + DEFLATE 相同字节；unlisted 变体
更在循环内对每个接收者**重复构造同参数包**（`createSinglePlayerInitializing(player, false)` 与
接收者无关）。本批（0246）：join 主包与 unlisted 变体 arm 双 memo（0242/0244 机制），unlisted
构造惰性提出循环外。

同批否定结论（留档勿重复勘察）：
- **入站解压域无优化点**：CompressionDecoder 两条路径均按段头声明的未压缩大小**精确分配**
  （Velocity `preferredBuffer(claimedUncompressedSize)` / JDK `directBuffer(size)`），无增长循环。
- **UPDATE_LATENCY 广播不可共享**：`PlayerList:740` 每 target 的包经 canSee 过滤构造——内容
  依赖 target 视角（vanish 插件下不同），共享需 n² 视角比较，收益不值。
- UPDATE_HAT/UPDATE_GAME_MODE broadcastAll 同实例小包（<threshold 不压缩，编码 µs 级）不扩展。

环境：JDK 21.0.10（Windows 11，JDK Deflater 回退），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性

- join 主包：vanilla 本就单实例循环发送（CraftBukkit 重写块）——memo 仅消除每连接的重复编码/
  压缩（0242/0244 机制：编码快照=首连接同 buffer 产出、压缩段=自描述 deflate 快照、threshold
  戳+level 纪元键控）。
- unlisted 提出：构造参数 (player, false) 与循环变量无关（逐行核验）——提出后各接收者收到内容
  逐字节相同的同一实例（与 listed 主包同构）；惰性构造保持"零 unlisted 观众零构造"的原语义。
- 包不可变、codec 确定且 locale 无关（UUID/字符串/property 写出）；memo 仅在两个 join 站点 arm，
  其余构造（latency/gamemode/hat）memo 为 null 零影响。

## 2. 基准（PlayerInfoBroadcastBench，模型=单玩家条目 737B）

载荷：UUID 16B + 名 + 700 词 base64 值域变长流（真实 textures 签名 ~700-1400 字符形态；首轮 96
词模型 126B 被自检带外证伪后修正），压缩比 1.20（高熵签名，难压缩）。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 12,206.315 ± 1,962.809 | 每观众：编码走查 + DEFLATE + 帧 |
| afterFill | 12,287.743 ± 2,168.143 | 首观众：before + 双快照（CI 重叠） |
| afterHit | **1,412.870 ± 99.941** | 后续观众：双 memcpy + 帧 |

- **命中路径 ≈ 8.6×/观众**（CI 分离）；30 在线观众 join 事件 ≈ 0.33ms CPU 消除 + unlisted 重复
  构造归一。**诚实定位**：收益为批次 74（7.2ms/join）的次级补充，价值在 join 域冗余的完整性
  收尾。
- 自检 main ALL OK：30 观众两路径 wire 字节逐观众全等 / 编码确定性 / 压缩比带内。

## 3. 留档

- join 域剩余（均 per-player 内容或红线外，无冗余可消除）：recipe book/advancement 初始包、
  `createPlayerInitializing(onlinePlayers)` 新玩家侧大包（单连接无跨连接冗余）、
  sendLevelInfo/initInventoryMenu 双发（批次 64 红线外维持）。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0246）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 75 记录）。
