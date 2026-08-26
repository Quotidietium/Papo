# 批次 89 报告（2026-08-27）— placeNewPlayer 内部构成分解 + quit 管线 survey（勘察轮，无代码变更）

主题：批次 88 后稳态 join 剩余 14ms 的最后两块——placeNewPlayer ~8ms 内部构成，与 join
管线之外的主线程串行面（quit 管线）survey。

## 1. placeNewPlayer 分解（bot 追踪前 13 个 play 包的到达形状）

稳态 join（0.57.0，相位基准中位）：

```
CONFIG:0x3 (finish-config)   t=7ms
PLAY:0x30 (login 首包)        t=14ms   gap=7ms   ← finish-config 处理 + placeNewPlayer
PLAY ×13（difficulty/abilities/插件channel/recipes/scoreboard/…）t=14ms 全部 gap=0ms
```

**join 突发 13 包同一毫秒内到达**——`suspendFlushing/resumeFlushing` 原子刷新 +
批次 74/75 的 join 包 memo 生效，发送侧零开销。7ms gap = placeNewPlayer 单次主线程
执行的构成（源码逐一核对）：协议切换（inbound/outbound 重绑）→ ~8 个初始包构造发送
（login/difficulty/abilities/held-slot/recipes-cached/权限/channels）→
sendInitialRecipeBook + updateEntireScoreboard → sendLevelInfo（border/time/spawn/天气）
→ `serverLevel.addNewPlayer`（实体入世界+追踪抑制）→ PlayerJoinEvent →
PlayerInfoUpdate 广播（memo 化）→ 追踪启动。

**结论：无残余量化、无可安全下放面**——全部为事件/世界状态耦合的真实工作（事件必须
主线程、实体入世界必须主线程）。join 管线优化到此为止（14ms = 登录 3-4 + 配置管道 3 +
placeNewPlayer 7，全为真实工作）。

## 2. quit 管线 survey（源码核对）

`PlayerList.remove`（channelInactive → 主线程处理，**事件驱动无 tick 量化**）：
PlayerQuitEvent（插件）→ bukkitPlayer.disconnect → `player.doTick()` → 碰撞规则团队
清理 → carried 物品掉落 → `save`（NBT 构建主线程耦合于活状态——读部位/背包/效果，
批次 79 已下放 gzip+写盘；stats/advancements 同）→ 载具卸载 → 实体移除/追踪清理。

**结论：quit 无量化面；写侧已被批次 79 覆盖；NBT 构建留在主线程是活状态一致性所系
（批次 84/85 的 datafix 放置教训同源）。无可安全增量。**

## 3. 本轮判例

- join/quit 管线的多核优化战役收束：读侧（82-84）、量化面（87-88）清零后，剩余延迟
  全部是语义必需的主线程工作——**进一步压缩需改事件/实体模型（Folia 级重写），超出
  等价性红线**。
- 原子 flush + 包 memo 使 join 突发的发送侧成本为零（13 包/0ms）——测量"包到达形状"
  是验证发送管线健康度的廉价手段（bot trace 已内置）。

## 4. 工程附带

OfflineJoinBot 追加前 12 个 play 包的 5ms 短读追踪（SO_TIMEOUT 形状采样），JoinPhaseBench
输出完整 join 突发形状——后续发送管线改动可用同一矩阵复验。
