# 批次 118：容器线序对照——闪回线级等价的直接实证（多核调度系列㉜，bench 轮，无服务器代码变更，版本保持 0.71.0）

日期：2026-08-31　分支：`perf/multicore-r3`

## 目的

验证器对「箱子菜单闪回未被复现」的异议需要最后一层硬证据：在闪回的机制载体
（GUI 插件 close+reopen 容器序列）上，fork 与官方 Paper 的**线上行为是否逐位一致**。

## 方法

- **WireSequenceBench**：服务器装 MenuRefreshPlugin（新增 `reopen <periodTicks>`
  模式=每 N tick closeInventory+openInventory，GUI 插件闪回机制的标准载体）；
  bot 开帧日志记录容器相关 clientbound 包（CLOSE=0x11 / SET_CONTENT=0x12 /
  SET_SLOT=0x14 / OPEN_SCREEN=0x39——GameProtocols 注册序提取，+1 偏移经
  keepalive 0x2B / ping 0x3B 双实证校验）。两相位：基线 60s + reopen(20tick)
  120s。输出 reopen 序列签名与 OPEN_SCREEN→FullContent 到达间隔分位。
- 分别跑 Papo 0.71.0 与官方 Paper 1.21.11-132（fill API 下载，sha256 校验）。

## 结果

| 指标 | Papo 0.71.0 | Paper 1.21.11-132 |
|---|---|---|
| reopen 循环数 | 119 | 119 |
| 序列签名 | **OFSC ×118**（Open→FullContent→SetSlot→Close） | **OFSC ×118** |
| OPEN→FullContent p50/p95/max | 0/1/1 ms | 0/0/1 ms |

**逐位一致**：包型序列、次序、时延全部相同。fork 的容器路径（含 0260 发送批量化
生产配置下的实际线上行为）与上游等价——**任何在 fork 上出现的菜单闪回，在官方
Paper 上会以相同形式出现**（其源=停摆/插件/客户端，非 fork 差异）。批次113 的
模型级 FIFO 证明至此获得线级实证互证。

## 结论

「卡顿+闪回」的服务端排查至此全谱闭环：唯一可复现源已修（首 join，−46%），
全部典型成本面排除（命令/刷新链/autosave/世界生成/实体/红石/join），发送与容器
路径双重等价证明（模型级+线级）。剩余=用户实例的插件非典型工作或主机级——
PapoDiag（批次117，生产就绪）的一步数据收集是唯一解锁路径。

## 复现

```bash
cd benchmark && java -cp build/classes papo.bot.WireSequenceBench <jar> menuplugin/MenuRefresh.jar <label>
```
