# 批次 115：菜单命令派发链成本量化——头号假说的服务端排除（多核调度系列㉛，bench 轮，无服务器代码变更，版本保持 0.71.0）

日期：2026-08-30/31　分支：`perf/multicore-r3`　性质：量化验证轮

## 目的

批次114 结构化问询锁定头号假说（波动与交互对齐 + 箱子菜单/GUI 插件 → 点击处理
器 dispatchCommand 链），用户暂无法提供实例数据。本批以 CommandCostBench 把该
假说的服务端层做直接量化：若单条命令 ≥1ms，多玩家点击叠加即用户症状量级。

## 方法

boot（tickProfile=1）→ 1 bot 全程在线（@p 有效）→ 5 类代表性菜单命令
（give / give+NBT 组件 / playsound / effect / title）× 3 档批量（10/50/200 条，
writeChunked 分块注入）→ tickdist 逐窗观察 dur 尖峰。

## 结果

**全部批量窗 max ≤ 42ms、p99 ≤ 7.4ms、over45=over50=0**——200 条同型命令的
突发对主线程无可测影响（摊销 <0.2ms/条，含 NBT 组件的 give 亦然；控制台命令
队列在 tick 间自然摊开 + 单条成本低）。唯一的 max=196ms 在 boot 首 窗（首 join
已知形态，批次114 已修 −46%）。

## 结论

**菜单插件的命令派发层作为服务端停摆源被直接测量排除**。至此用户实例症状的
全部服务端层均已定量排除：发送路径（乱序/延迟形式化证明）→ 容器事件路径
（零监听门控复核，有监听=原版路径）→ 命令派发层（本批实测）→ autosave/
世界生成/实体/红石/join（八场景家族）。剩余可能只能在插件自身监听器工作
（InventoryClickEvent 内的背包重建/重开包风暴/NBT 拷贝风暴）或用户环境
（主机级）——均需 stall-report.txt 或插件清单才能继续定位。

## 复现

```bash
cd benchmark && java -cp build/classes papo.bot.CommandCostBench ../paper-server/build/libs/Papo-1.21.11-0.71.0.jar
```
