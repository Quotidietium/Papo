# 批次 116：菜单插件刷新链量化——监听器工作层典型模式排除（多核调度系列㉛续，bench 轮，无服务器代码变更，版本保持 0.71.0）

日期：2026-08-31　分支：`perf/multicore-r3`

## 目的

批次115 排除了命令派发层后，用户实例头号剩余假说是「插件监听器工作」。菜单插件的
标志性动作不是命令而是**刷新链**：取消点击 + 重建 54 格物品（name/lore/CMD 组件，
heavy 时+附魔）+ `updateInventory()`（= `sendAllDataToRemote()` 全量容器重同步，
含 90+ 槽位的 stack 拷贝与重编码）。本批以真实 Bukkit 插件在服内复现并量化。

## 方法

- **MenuRefreshPlugin**（benchmark/menuplugin/，真实插件 jar 装入 plugins/）：
  join 后自动开 54 格菜单；InventoryClickEvent 取消+刷新（典型 ChestCommands 式）；
  `/menurefresh <hz>` 调度按频率对每个打开菜单的玩家执行刷新链；`heavy 1` 切换
  三附魔 NBT 变体。带 viewers/exec 诊断（迭代中实证任务存活、viewTopIsMenu 绑定）。
- **MenuPluginBench**：四相位各 120s（0Hz 基线 → 10Hz → 30Hz → heavy 30Hz），
  bot 全程在线持菜单，tickProfile 逐窗。
- 迭代判例：Paper 插件 remapper 要求无 saveDefaultConfig 资源依赖；1.21 API 枚举
  名（LOOTING 非 LOOT_BONUS_MOBS）；**0259 fanout.sends 计数只覆盖 tracker 路径
  （容器包盲区）**——主线程 dur 是本实验的地面真相。

## 结果

**全相位（含 heavy 30Hz）稳态 dur p50 0.9-1.3ms / p99 ≤ 3.2ms / max ≤ 14ms /
over45=over50=0**；仅 boot 窗有已知首 join 形态。30Hz×1 viewer 的刷新链完全淹没
在噪声下限（单次 <~50µs）；线性外推 20 玩家同时 30Hz 刷新 ≈ 1.5ms/tick 上界——
任何合理规模的菜单插件刷新都不是停摆源。

## 结论

**菜单插件的全部典型成本面（命令派发 + 监听器刷新链）在服务端定量排除**。结合
此前各层（发送路径/容器事件/autosave/世界生成/实体/红石/join/首join），用户实例
「交互对齐的卡顿+闪回」的剩余可能收窄为：①插件的**非典型**工作（超大 NBT、每
点击数百次操作、反射/重序列化循环）②主机级（CPU 抢占/页文件/杀软）。两者均需
stall-report.txt 或插件清单才能继续。

## 复现

```bash
cd benchmark/menuplugin && F:/Java/21/bin/javac -encoding UTF-8 @cp.args -d build src/papo/menuplugin/MenuRefreshPlugin.java
cd build && F:/Java/21/bin/jar cf ../MenuRefresh.jar papo plugin.yml && cd ../..
java -cp build/classes papo.bot.MenuPluginBench ../paper-server/build/libs/Papo-1.21.11-0.71.0.jar menuplugin/MenuRefresh.jar
```

---

# 批次 117：PapoDiag 零配置停摆诊断插件——实例数据收集摩擦清零（多核调度系列㉝，工具轮，无服务器代码变更，版本保持 0.71.0）

用户「暂无法提供」实例数据的最大可能是操作摩擦（换启动参数+跑 PowerShell+找文件）。
PapoDiag 插件（benchmark/papodiag/PapoDiag.jar）把摩擦清零：**丢进 plugins/ 重启即
可**——主线程每 tick 心跳，看门狗守护线程 25ms 巡检，心跳年龄 ≥150ms 判定停摆
进行中，立即自动抓取主线程 25 帧栈（Thread.getAllStackTraces——批次114 定位首
join 冻结的同款证据形态）+ 在线玩家 + 已启用插件清单 + 滚动 20-tick 时长历史，
追加写入 `plugins/PapoDiag/stall-report.txt`（5s 去抖、追加式永不覆盖、诊断失败
静默绝不影响服务器）。

验证（DiagValidateBench）：强制停摆（forceload 未生成区域世界生成 + fill）→
**PASS**——自动捕获 2 条停摆（boot 期 MXBean 注册 152ms + forceload 生成尖峰
170ms），栈/插件清单齐全。判例：插件 dataFolder 需 mkdirs（FileWriter 静默失败
首版教训）。

**用户操作（终版，唯一一步）**：把 `benchmark/papodiag/PapoDiag.jar` 丢进你服务器
的 plugins/ 目录重启，正常游玩到症状出现，然后把 `plugins/PapoDiag/stall-report.txt`
发回——文件里已是完整定位证据（停摆时刻主线程正卡在哪+当时哪些插件在线）。
