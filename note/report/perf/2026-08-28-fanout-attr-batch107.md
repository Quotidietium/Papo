# 批次 107 报告（2026-08-28）— 扇出包型子归因：0259 探针（多核调度系列㉙，0.65.0→0.66.0，勘察轮）

主题：分解批次 106 的第一大牛驱动面（tracker 扇出 19us/牛@500 vs 批次 102 ~90ns/send
的 20× 缺口）。交付 0259 探针（`fanout.sends` 观察者计数 + 8 类包型计数，默认关，
papo.tickProfile 门控）与 PapoTickProfile.count 计数节（src/main 直提交侧）。

## 归因结果（N=2000 聚堆、10 站立 bot、稳态尾窗 win#19/#20）

| 指标 | 值 |
|---|---|
| fanout.sends | 14,475-15,070 /tick |
| chunkMap.tracker.fanout | 13,057-15,976 us/tick |
| **每 send 主线程成本** | **902-1,060 ns** |
| velocity（SetEntityMotion） | 780-825 /tick（41%） |
| movePos | 569-604 /tick（30%） |
| headRot | 294-309 /tick（15%） |
| movePosRot | 190-200 /tick（10%） |
| posSync/moveRot/other/实体数据 | <7 /tick 合计 |

机理闭环：

1. **velocity 主导非异常**——`EntityType.trackDeltas()` 对牛为 true（排除表仅 PLAYER/
   LLAMA_SPIT/WITHER/BAT/ITEM_FRAME 等），走路牛 deltaMovement 因重力每 tick 振荡，
   `d > 1e-7` 恒真 → 每牛每 tick 一条速度包广播（vanilla 语义，不可削减）。
2. **~1 包/牛/tick × 10 观察者 ≈ 15k sends**，与 fanout.sends 自洽。
3. **每 send ~1us 是移交成本**（ServerCommonPacketListenerImpl.send 包装链 + Connection.send
   即时路径 + `eventLoop().execute` lambda 分配/MPSC 入队），flush 已被
   suspendFlushing/resumeFlushing 按 tick 合并（Paper 既有设计）——**余下的每 send
   固定成本是扇出面的本体**。

## 优化设计（批次 108 交付，红线内）

per-connection tick 批量排水：主线程 send 在即时路径改为 append 到每连接批次列表，
suspendFlushing/resumeFlushing 双端 + 256 阈值中途排水（一次 eventLoop 任务写整批，
flush 仍由既有 tick 末 flushChannel）。等价性：每连接 FIFO 序不变、同 tick 交付、
终端包/捆绑包/非主线程路径原样；预期每 send ~1us → ~50-100ns（扇出面 ~10×，
N=2000 主线程省 ~12-14ms/tick）。

## 披露

- N=500 计数点两次尝试均被共享机清扫杀死（21:45/22:00，一次死在探针前、一次死在
  召唤-沉降期；形态=日志瞬时截断无 crash report）；N=2000 归因窗（22 窗含稳态）完整，
  0.65.0 批次 106 已有 N=500 的 ns 侧数据——500 计数点为锦上添花不作门。
- test + createPapoJar（Papo-1.21.11-0.66.0.jar，97.4MB）BUILD SUCCESSFUL。

## 原始数据

benchmark/results/batch107-attr-N2000.log（含 .count 节 22 窗）；
batch107-attr-N500.log（被杀 run 的 boot/沉降窗）。
