# 批次 98 报告（2026-08-27）— tick 相位分解探针：批次97 超线性归因修正 + 聚堆密度面发现（多核调度系列⑭，勘察/基建轮）

主题：为归因批次 97 的"connection 相位 ≈N^1.6-2 超线性"新增五组诊断探针（0253 补丁 +
PapoTickProfile 扩展，默认关=零行为变化，0.59.0→**0.60.0**），40/120/160 三点分解实测。
**结论：批次97 的 connection 超线性读数是外部争抢伪影 + 真实聚堆密度面的混合**——
排队排水假设证伪，真超线性在 conn.listenerTick（玩家 doTick）的高密度段。

## 交付（探针，-Dpapo.tickProfile=1 时生效）

- `chunkMap.tracker.maintain` / `chunkMap.tracker.sendChanges`：追踪器 viewer 维护与广播分离；
- `conn.flushQueue` / `conn.listenerTick`：连接 tick 的队列排水与监听器 tick 分离
  （首版 listenerTick 误包进 PAPO_TRACK_PACKET_PROCESSING 死门分支，已修）；
- `server.taskDrain`：每 tick 任务排水（含入站包处理）桥接进窗口（recordEndOfTick 处
  读 taskExecutionTime 后复位前）；
- 窗口头 `gcMs=`：全 GC bean collectionTime 窗口增量；
- `PapoTickProfile.addNanos`（循环级累计一次上报）。

## 三点分解（0.60.0，种子 papo90，6 分钟窗口；共享机击杀带重试，160 一次过）

| 分解 avg/tick | 40 bot | 120 bot | 160 bot | 160/40 |
|---|---|---|---|---|
| connection 总 | 661us | 1874us | **8068us** | 12.2× |
| — conn.listenerTick | 638us | 1837us | **7998us**（min 6385） | **12.5×** |
| — conn.flushQueue | 3.4us | 4.8us | 8.4us | 2.5× |
| worlds 总 | 2477-3298us | 4439us | 6792us | ~2.1-2.7× |
| — chunkMap.tracker.maintain | 154us | 470us | 1032us | 6.7× |
| — chunkMap.tracker.sendChanges | 493us | 1200us（min 321） | 1125us（min 335） | 2.3× |
| — level.entities | 1238us | 1352us | 2447us | 2.0× |
| server.taskDrain | 169us | 180us | 260us | 1.5× |
| sendChunks | 91us | 73us | 93us | ≈1× |
| gcMs/窗口（稳态） | 0-15 | 0-19 | 4-24 | 微小 |

两版 0.60.0 的 40 点 worlds 相差 33%（2477/3298）——窗口级争抢方差；表中区间如实呈现。
160 点 join 窗口 wallMs=28478（+42%，160 并发登录风暴）；稳态全部 20000±2。

## 归因修正（相对批次 97）

1. **flushQueue 排水假设证伪**：全部三点 3-8us/tick——主线程 send 走内联快路
   （Connection.java:429 队列空判断），N² 广播对的成本在广播现场（sendChanges）而非
   connection 相位排水。
2. **批次97 的 connection 超线性 = 伪影 + 真面的混合**：跨轮 connection 剧烈漂移
   （630↔2058@40；3630↔2347@120）由共享机 CPU 争抢下 tickConnection 路径的调度/
   锁敏感造成；而 120→160 的 listenerTick 爆发（1837→7998，4.35×@1.33×bot）是真实面。
3. **listenerTick = 玩家 doTick 主体**（40→120 段 2.9×@3× 线性=每玩家常量成本 ~15us）；
   120→160 段每玩家成本 15→50us——**聚堆密度超线性**。主嫌疑：`LivingEntity.pushEntities()`
   （LivingEntity.java:3916）——每玩家 AABB 查询枚举全部重叠者（挤堆 160 bot 每人
   数十候选）+ 挤压伤害计数，O(局部密度) 每玩家。次嫌疑：移动碰撞展开查询
   （LivingEntity.java:4484）。
4. tracker.sendChanges 高方差（avg 1125/min 335@160）= send 快路/排队路径随 netty
   忙闲切换；maintain 温和超线性（6.7×@4×bot）。
5. taskDrain/GC/sendChunks 均非热点。

## 批次99 优化设计（依据本轮归因）

`pushEntities` 的扫描结果仅两个消费者：前 `maxEntityCollisions`（Paper 默认 8）个按序
推送 + "非乘骑数是否 > crammimg 阈−1"布尔（GameRules 默认 24）。**有界早停扫描**
（序保持，两个消费者的需求都精确满足即停）可把聚堆段的 O(密度) 每玩家扫描降为
O(min(密度, 界))，等价性可证（含乘骑者计数边界）；常规分散服务器零变化（早停条件
几乎不触发）。批次99 将实现 + JMH 等价自检 + 160 bot A/B（预期 listenerTick 显著回落
即为机制证实）。

## 验证

- 全量 test ✓（两轮：探针首版 + listenerTick 修正版）；0253 补丁 rebuild 干净
  （仅新增，无垃圾重命名）；0.60.0 jar 构建成功。
- 三点门：40/160 exit 0 零错误；120 复跑 logErrors=10 全为良性突断第四形态
  （正体中文 WSAECONNABORTED "中止了一个已建立"，过滤器已补），walk 完整数据有效。
- 共享机击杀全程伴随：40 点 4 中 1、120 点 4 中 1、160 点 1 中 1；构建链 3 次
  被杀后重试成功（compile/rebuild/jar 各带完成判定重试）。

## 判例

- **Paperweight 内部仓库的半成品 fixup 会卡死后续 fixup**（interactive rebase in
  progress 状态跨会话存留，fixupSourcePatches 快速失败）：恢复路径=确认 fixup 提交
  内容归属后 `git rebase --abort` + amend 成特性提交 + rebuildPatches；不要盲目重跑。
- **探针包错分支=零数据**：listenerTick 首版包进恒 false 的死门分支，输出直接缺相
  ——探针上线先核对样本数（n=连接数×tick 数）与在场性。
- **测量轮的"超线性"先过方差关**：批次97 的 connection 曲线被共享机争抢污染出
  N^1.6 假象；分解+复测+min 口径才把伪影（锁/调度敏感）与真面（密度超线性）分开。
- **Windows 管道 JVM 的 stdout 编码是 GBK**（stdout.encoding≠file.encoding）：harness
  落盘的中文是 GBK 字节（UTF-8 读=乱码）；服务器管道里的同一异常可能是正体中文。
  过滤规则按语义写（消息族），不按渲染形态。
