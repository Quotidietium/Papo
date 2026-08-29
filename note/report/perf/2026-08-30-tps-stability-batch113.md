# 批次 113：TPS 稳定性轴——逐 tick 直方图、七场景矩阵与闪回归因（多核调度系列㉙）

日期：2026-08-30　分支：`perf/multicore-r3`　版本：0.69.0 → 0.70.0　性质：诊断/验证轮（0263 探针 + 4 个 harness + 线上序形式化证明）

## 目的（用户报告）

"多线程情况下 TPS 突变严重、波动极大；箱子菜单等交互出现闪回"。修复前先取证：
量化波动形态（逐 tick，而非 400-tick 均值——均值窗把 500ms 停摆稀释成 1.25% 偏差
而瞬时 TPS 已崩到个位数）、在 7 个场景家族中定位方差源、对多线程改动逐项排除。

## 交付一：0263 探针——逐 tick 时长/间隔直方图 + 停摆事件墙钟

`PapoTickProfile.tickdist`（400-tick 窗，`-Dpapo.tickProfile=1` 门控，默认关）：

- `durMs[p50/p95/p99/max]`：tick 时长分布（此前只有窗均值）
- `over45/over50`：超预算 tick 计数
- `gapMs[p95/p99/max]`：tick 间隔分布（含睡眠；>名义 50ms 即停摆）
- `stalls100ms`：间隔 ≥100ms 的 tick 数（多 tick 停摆/单 tick 大停摆）
- `tps`：窗内真实 TPS
- `PapoTickProfile.stall tick=N gapMs=… durMs=… at=HH:mm:ss.mmm`：**每个停摆
  事件的精确墙钟**（与服务器日志行对齐——join/autosave/插件指令/世界生成的直接
  判别器；本批即用它把唯一异常定位于首 join 窗）

这是**自服诊断的完备工具**：TPS 波动的溯源不再依赖 1s 级均值（spark/tps 命令的
粒度会掩盖一切 <1s 的突变形态）。

## 交付二：七场景稳定性矩阵（0.70.0，全部四门全绿）

| 场景 | 稳态 dur p50/p95/p99 | 停摆（stalls） | 判定 |
|---|---|---|---|
| 实体 N=500 ×10bot | 6.4/7.4/8.0ms | 0 | 干净 |
| 实体 N=2000（容量墙） | ~27/36/—ms | 稳态 0 | 干净（贴墙负载比例化） |
| 红石 441 环（容量墙） | 47/64/67ms | 负载本体 | 慢性贴墙（by design，批112 选点） |
| churn 10槽×30s（120 次 join/quit） | 2.5-3.5ms | 0（boot 窗后） | 干净 |
| 行走探索世界生成（部分，地形踢线） | ~2.5ms | 0 | 干净 |
| forceload 扫掠世界生成（72 步） | 3.2-6.5ms | 0（boot 窗后） | 干净 |
| JoinPhaseBench 稳态重连 ×6 | spawn 中位 26ms | 0 | 干净（批次88 基线 14-16ms 同级） |

**结论：本 fork 运行时稳态在七场景家族下无可复现的病理性波动。** 全部观测到的
巨停摆（0.3-0.9s）只出现在：①harness stdin 群发操作期（kill/summon/探针风暴=
群发命令的主线程本体成本）②boot/首 join 窗的一次性 600-818ms 离群（churn0/churn2
两次出现、JoinPhaseBench2/flgen 不复现——疑共享机冷启动/杀软扫描新临时目录的
OS 级干扰；后续 120 次 join 全干净证明非 join 路径缺陷）③GC 全程 0.2% 无关联。

## 交付三：闪回假说的形式化闭合——0260 线上序 FIFO 证明

`SuspendBatchOrderBench`（benchmark/src/papo/bench/，自检 main）：忠实复刻
Connection.send 三路径（批量 append / 直发+守卫排水 / 入队+守卫排水）+ flushChannel
排水，在 FIFO 执行器模型（netty NioEventLoop.execute 的上游保证）上 **10,000 随机
交错 × 四形态（纯批量/批量+直发/批量+入队/全混合含 flush）全部 wire order ==
issue order**。披露例外（vanilla 同构）：异步白名单发送（pong 类）可越序。

判例：**netty 4.2 EmbeddedChannel 存在非 FIFO 交错 quirk**（最小探针实证：
单任务内连续 write 后管线序已含后续任务的写）——不能作为事件循环模型，
bench 改用手写 FIFO 队列（批次113 判例归档）。

配合结构论证：挂起窗内 flush=false 写的**线上时刻**批量化前后不变（都等 tick 末
flushChannel 的唯一 flush；0260 只摊销了 eventLoop 任务移交），故容器/交互包的
到达时序无任何变化。**闪回在本 fork 侧无乱序/延迟机制可解释**——真实成因是
停摆（任意来源）下权威包迟到的客户端预测回退，属停摆的下游症状。

## 用户症状的判读与自服诊断路径

本 fork 运行时未复现 → 用户实例上的波动最可能来自（按 0263 stall 行可直接判别）：

1. **慢性贴墙**（dur p50 接近 50ms，如 rs441 形态）：负载本体接近预算，任何扰动
   都外显为 TPS 突变。判据：tickdist over45/50 常驻、gap 正常。处置：降载或
   ALTERNATE_CURRENT（红石）/实体密度治理。
2. **周期性停摆**（stall 行等间隔出现）：autosave/备份任务/定时插件任务。判据：
   stall at= 时间戳等周期（5min=bukkit autosave）。处置：错峰/异步化插件任务。
3. **突发停摆群**（stall 行聚簇）：插件同步 IO 或**命令派发风暴**（菜单类插件每
   次点击 dispatchCommand=主线程命令解析+执行，等价本矩阵的 stdin 群发形态——
   箱子菜单闪回与此完全同构）。判据：stall 与交互时刻对齐。处置：插件侧异步化/
   缓存化。
4. **主机级**（dur 低但 gap 大）：CPU steal/页文件压力/杀软。判据：tickdist gap
   大而 dur 小且无日志对应。

诊断命令：`java -Dpapo.tickProfile=1 -jar Papo.jar`，观察 `PapoTickProfile.stall`
行的墙钟与服务器日志对齐即可锁定。

## 复现

```bash
cd benchmark
# 稳定性矩阵
bash b113-stability.sh                        # 红石441 + 实体500/2000
java -cp build/classes papo.bot.ChurnStabilityBench ../paper-server/build/libs/Papo-1.21.11-0.70.0.jar 10 30000 360000 0
java -cp build/classes papo.bot.ForceloadGenBench ../paper-server/build/libs/Papo-1.21.11-0.70.0.jar 360000 5000
java -cp build/classes papo.bot.JoinPhaseBench ../paper-server/build/libs/Papo-1.21.11-0.70.0.jar 6
# 线上序 FIFO 证明
java -cp build/classes papo.bench.SuspendBatchOrderBench   # ALL OK: 10000 interleaves
# 解析
python b113-parse.py F:/TEMP/papo-b113-*.log
```
