# 批次 90 报告（2026-08-27）— 稳态 tick 主线程串行面系统性 survey（多核调度⑪）

主题：join/quit 之外的一般性主线程面勘察。新增**属性门控的 tick 相位剖析器**
（[PapoTickProfile](../../../paper-server/src/main/java/io/papermc/paper/util/PapoTickProfile.java)，
`-Dpapo.tickProfile=true|1`，默认关=每相位一次静态布尔检查）+ **行走 bot**
（OfflineJoinBot.joinWalkAndDisconnect：每 50ms 发 MOVE_PLAYER_POS、响应
KEEP_ALIVE/PING，5 blocks/s）+ [TickSurveyBench](../../../benchmark/src/papo/bot/TickSurveyBench.java)
（10 bot × 45s 稳态行走负载；日志解析走 logs/latest.log——管道捕获丢 post-boot 行判例）。

## 稳态相位画像（10 行走 bot，两窗口，全门绿）

| 相位 | 窗口1（行走初期，区块加载中） | 窗口2（稳态行走） |
|---|---|---|
| **worlds 总计** | **39.0%（4.32ms/tick）** | **37.1%（1.32ms/tick）** |
| ├ level.tickPending+misc（×3 世界） | 23.4%（2.60ms/t） | 22.1%（0.79ms/t） |
| ├ level.chunkSource（×3 世界） | 16.7%（1.85ms/t） | 20.7%（0.74ms/t） |
| └ level.entities（×3 世界） | 14.9%（1.65ms/t） | 14.3%（0.51ms/t） |
| connection（网络 tick+包处理） | 5.5%（0.61ms/t） | 4.4%（0.16ms/t） |
| sendChunks | 0.4% | 1.3% |
| functions / players | ≈0 | ≈0 |
| **主线程总利用率** | **4.43ms/50ms = 8.9%** | **1.42ms/50ms = 2.8%** |

## 结论

1. **稳态主线程串行面利用率仅 3-9%**——tick 循环在此负载规模远未饱和，与批次 86
   宏观校准互证（主线程非瓶颈；多核效率的大规模问题是 world/entity tick 的区域化，
   即 Folia 级架构，超出等价性红线）。
2. **最大单项是"空维度固定成本"**（tickPending+misc ×3 世界 ≈ 22%）：下界/末地无
   玩家仍支付每 tick 固定成本——**上游已有配置旋钮**
   （`paper-world.yml unsupported-settings.disable-world-ticking-when-empty`，
   ServerLevel.tick 源码实证），无需新代码；记录为运营建议。
3. **connection 4-6% / sendChunks ≈1%**：网络与区块发送管线的主线程足迹极小
   （批次 58-77 的网络清理 + moonrise 下放在真实负载下的验证）。
4. **无新的可安全消除量化/阻塞面**：所有相位都是连续工作，无 tick 边界等待残留
   （批次 87/88 已清零 join 管线的量化面；稳态 tick 各相位本就在 tick 内联执行）。

## 工程沉淀

- PapoTickProfile 剖析器（默认零开销）保留为诊断基建，后续任何 tick 路径改动可用
  `-Dpapo.tickProfile=true` 复测量化回归。
- 行走 bot + TickSurveyBench 为稳态负载标准工具（与四态冒烟/突发压测并列）。

## 踩坑判例（入 build.md）

- **`Boolean.getBoolean` 只认 "true" 不认 "1"**：`-Dpapo.tickProfile=1` 静默失效
  （空载/带载双探针定位）；属性开关统一 `getProperty` 后 `equals("true")||equals("1")`。
- **多行单次 println 的续行被日志系统吞掉**（log4j 包装的 System.out）——逐行 println。
- paperclip/bundler jar 的类在嵌套 `META-INF/versions/<mc>/paper-<mc>.jar`——核对类
  是否进包须查嵌套 jar，外层 listing 查不到。
