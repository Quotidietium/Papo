# Papo 插件指纹泄露加固报告（批次 51）

> 报告日期：2026-08-02
> 触发：用户报告"作弊客户端能读取服务端数据，从而推测出服务器装了哪些插件"。
> 性质：安全加固（config-gated 可控开关），非性能优化——故无 JMH 前后对比，改用行为自检验证。
> 关联 survey：批次 50 第三路"插件指纹泄露向量"已完整测绘全部向量与文件:行号。

## 0. 结论速览

新增 `paper-global.yml` 的 `fingerprint-hardening` 区段，**默认全部保持现状（兼容性红线）**，服主可 opt-in 隐藏三类会泄露服务端软件 / 插件身份的客户端可见信息：

| 开关 | 默认 | 隐藏什么 | 对应泄露向量 |
|---|---|---|---|
| `brand-payload.mode` | `REAL` | `minecraft:brand` payload 里的服务端品牌名（"Papo"），客户端 F3 可见 | V2 |
| `status.version-string` | `REAL` | 服务器列表 ping 的 `ServerStatus.Version.name`（"Papo 1.21.11"），未登录即可读 | V3a |
| `plugin-channels.broadcast-mode` | `ALL` | `minecraft:register` 广播给客户端的 incoming plugin channel 名（常可识别插件） | V1 |

## 1. 已加固的向量

### V2 + V3a — 品牌名 / ping 版本串（隐藏 fork 身份 "Papo"）
- **实证**：`minecraft:brand` payload（配置阶段，[ServerConfigurationPacketListenerImpl.java:104](../../paper-server/src/minecraft/java/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java#L104)）发 `server.getServerModName()` = "Papo"；ping 版本串（[PaperServerListPingEventImpl.java:17](../../paper-server/src/main/java/com/destroystokyo/paper/network/PaperServerListPingEventImpl.java#L17)）= `getServerModName() + ' ' + getServerVersion()` = "Papo 1.21.11"。两者客户端**被动接收、无需任何权限**（ping 甚至未登录可达）。
- **加固**：
  - `brand-payload.mode`：`REAL`（现状）/ `VANILLA`（发 "vanilla"）/ `CUSTOM`（发自定义串）。
  - `status.version-string`：`REAL`（现状）/ `VANILLA`（仅 MC 版本，如 "1.21.11"）/ `CUSTOM`。
  - 实现：配置阶段 `BrandPayload` 内容与 ping 版本串各经一处 `GlobalConfiguration.get().fingerprintHardening.*.resolve(...)` 解析（[ServerConfigurationPacketListenerImpl.java](../../paper-server/src/minecraft/java/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java) `papoBrand` + [PaperServerListPingEventImpl.java](../../paper-server/src/main/java/com/destroystokyo/paper/network/PaperServerListPingEventImpl.java) `papoVersionName`）。`GlobalConfiguration.get()` 是静态字段读取（无异常），config 未加载时回退 REAL。
- **影响**：仅客户端显示文字 / 统计；`Brand-Id` 仍为 `papermc:paper`（`isBrandCompatible` 不变），**不影响协议兼容与插件**。

### V1 — plugin messaging channel `minecraft:register` 广播（隐藏插件身份，主针对用户痛点）
- **实证**：玩家登录后 [CraftPlayer.sendSupportedChannels()](../../paper-server/src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java#L2367) 把 `server.getMessenger().getIncomingChannels()`（所有插件 `registerIncomingPluginChannel` 注册的 incoming channel 名）以 `\0` 分隔写入 `minecraft:register` payload 发给客户端。channel 名常为 `myplugin:main` 等**可识别串**——客户端被动接收即可枚举推断插件。这正是用户"推测装了哪些插件"痛点的最直接被动向量。
- **加固**：`plugin-channels.broadcast-mode`：`ALL`（现状，全发）/ `WHITELIST`（仅 `allowed-channels`）/ `NONE`（全不发）。逐 channel 经 `shouldBroadcast` 过滤；过滤后无任何 channel 通过则不发 REGISTER。
- **影响**：`ALL`（默认）行为不变。`WHITELIST`/`NONE` 下，依赖"客户端先得知服务端在监听某 channel"的协议类插件（如老式 BungeeCord 联动）可能受影响——但 `register` 仅是"声明"，多数插件仍能收到客户端主动发的消息，故影响面小。建议 opt-in 前在测试服验证。

## 2. 暂未加固（survey 已测绘，留后续轮次）

- **V5（P0，最严重）`/plugins` `/version` `/help` 默认权限 TRUE**：[CommandPermissions.java:17-20](../../paper-api/src/main/java/org/bukkit/util/permissions/CommandPermissions.java#L17)（paper-api）将三者设为人人可用，是唯一直接吐**完整明文插件清单**的向量。加固需在 [CraftDefaultPermissions.java:13](../../paper-server/src/main/java/org/bukkit/craftbukkit/util/permissions/CraftDefaultPermissions.java#L13)（paper-server，`registerCorePermissions` 后 `pluginManager` 已就绪）按 config 用 `Permission.setDefault(OP)` 覆写三者 + recalculate。跨 paper-api↔paper-server，留**批次 52** 专门处理（本轮聚焦纯 paper-server 的三个被动向量）。
- **V4 Brigadier 命令树**：复用既有 `spigot.yml` `commands.send-namespaced: false` + `PlayerCommandSendEvent`；无需新开关。
- **V3b MOTD 占位符 / V7 datapack 命名空间 / V8 manifest 版本串 / V6 未知 channel 时序**：均为插件配置依赖或泄露面小，文档化即可，Papo 层不做统一开关。

## 3. 行为自检（非 JMH）

本轮是安全加固（无前后性能差异——config 解析是单次 / 稀有的静态字段读 + 字符串比较，亚微秒级）。验证改用行为自检 [FingerprintHardeningSelfCheck.java](../../benchmark/src/papo/bench/FingerprintHardeningSelfCheck.java)：忠实复刻三个开关的 `resolve` / `shouldBroadcast` 逻辑，**18 项断言全通过**——覆盖 REAL/VANILLA/CUSTOM(+空 customValue 回退)/非法 mode 回退、ALL/WHITELIST/NONE、以及"默认值与现状逐字一致"的兼容性红线。`ALL OK (18 checks)`。

## 4. 配置示例（paper-global.yml）

```yaml
fingerprint-hardening:
  brand-payload:
    mode: REAL          # REAL | VANILLA | CUSTOM
    custom-value: ""
  status:
    version-string: REAL # REAL | VANILLA | CUSTOM
    custom-value: ""
  plugin-channels:
    broadcast-mode: ALL # ALL | WHITELIST | NONE
    allowed-channels: []
```

## 5. 兼容性与验证

- **兼容性红线**：三个开关默认 REAL/REAL/ALL，与 0.26.0 行为逐字一致；config 未加载时（极早 ping/join）也回退 REAL/ALL。API 零变更。
- **验证**：`compileJava --no-configuration-cache` BUILD SUCCESSFUL（含 fingerprint-hardening 四处改动）；全量 `test` BUILD SUCCESSFUL（零 FAILED）；行为自检 18 项 ALL OK。
- **性能影响**：可忽略——brand/status 各一次 / 玩家（配置阶段 / ping），plugin-channels 一次 / 玩家 join；均为一次静态字段读 + 字符串比较，亚微秒级，无分配。
