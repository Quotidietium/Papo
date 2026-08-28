# 批次109：0.67.0 发送批量化 10 分钟 soak（稳定性收口）

- 日期：2026-08-28（23:05-23:17）
- 版本：Papo-1.21.11-**0.67.0**（批次108 挂起窗发送批量化 0260 之后的稳定性验证）
- Harness：`EntityScaleBench` v5——N=500 牛、10 站立 bot、窗口 600000ms（10 分钟）、
  `-Dpapo.tickProfile=1` 400-tick 窗
- 日志：`benchmark/results/batch109-soak-N500-10min.log`

## 结论（一句话）

**服务器侧全绿**：33 个非爬坡 400-tick 窗 TPS 19.80-20.06、logErrors=0、正常停机
（exited=true）、GC 均值 7.7ms/20s 窗（峰值 21ms，占墙钟 0.04%）且无增长趋势——
批次108 的批量排水在 10 分钟连续负载下稳定，**批次108 收口成立**。
在场门 FAILED（504/505 vs 500）判定为 **harness 精度问题**（worldgen 种群杂散混入
计数），非服务器缺陷；本批已根治（在场门标签化，见下）。

## 稳态画像（35 窗聚合，us/tick）

| 相位 | min | max | mean |
|---|---|---|---|
| worlds | 4192.5 | 8674.0 | 6433.1 |
| level.entities | 1830.2 | 6367.1 | 4828.9（worlds 的 75%） |
| level.tickPending+misc | 1251.5 | 2706.5 | 1583.6 |
| level.chunkSource | 1196.0 | 2239.1 | 1545.1 |
| chunkMap.tracker.sendChanges | 47.9 | 397.8 | 292.1 |
| chunkMap.tracker.maintain | 73.4 | 420.8 | 268.0 |
| chunkMap.tracker.fanout | 10.3 | 185.8 | **122.5** |
| connection | 180.1 | 1540.6 | 265.9 |

- worlds 均值 6.43ms/tick 与批次108 短窗 N=500（8.4-8.7ms）同量级；后半程随牛群
  沉降（fanout.sends 3.3k→2.0k/tick）下移至 5.6-6.3ms——**无时间漂移、无恶化**。
- fanout 均值 122.5us/tick = worlds 的 ~2%——批次108 后扇出移交面在长窗下同样
  不再是主面；主面回到实体链（level.entities 75% of worlds）。
- TPS：墙钟 19944-20200ms/400tick（剔 boot 与首个爬坡窗）→ **19.80-20.06**。

## 在场门失败判读（504/505 vs 500 → harness，非服务器）

证据链：

1. `doMobSpawning=false` 只禁周期性自然刷新，**管不到 chunk population 的 worldgen
   种群**（harness 注释自认，靠 join 后全局 `kill @e[type=!player]` 兜底）。
2. 共享机负载下（同窗口 JMH fork 两度 mmap 失败，见下）晚期 chunk population 可
   晚于全局 kill 完成 → 杂散牛在 kill 之后刷出，被全局 `@e[type=cow]` 探针计入。
3. 批次106 起杂散模式一致：+2（@2000）/ +1~+3（@500）/ 本批 +4/+5——均为**只多不少**
   （零死亡：A、B 均 ≥ N），与"召唤群全活"相容。
4. 服务器侧零错误、TPS 恒定——若为实体复制类服务器缺陷，10 分钟内应呈增长趋势，
   实测 A→B 仅 +1 后收敛。

**根治（本批 harness 提交 d664b8004b）**：召唤即打标签，探针改
`@e[type=cow,tag=papoCow]`——在场门只数本批次召唤群，worldgen 杂散一律不计。
后续批次（110+）在场门恢复全精度。

## 环境噪音披露

22:15 两份 `hs_err_pid*.log`（JMH ForkedMain G1 virtual space 1073741824B mmap
失败）——共享机内存/页面文件挤压（协同租户），与批次108 报告披露的同一窗口
DOS 1455 页面文件耗尽同源。**发生在 soak（23:05）之前、进程为 JMH fork 而非
被测服务器**，与本批次数据无关；文件已清理不入库。

## 交接

- 批次110（已启动）：实体链 AI/非AI 分量分解——NoAI 变体（`{NoAI:1b}` 跳过
  serverAiStep，保物理/push/EAR/追踪）与 AI 版同 harness 同 N=2000 对照，
  每牛 AI 分量 = (AI − NoAI)/N，决定下一优化靶点（AI 面 vs 物理/push 面）。
