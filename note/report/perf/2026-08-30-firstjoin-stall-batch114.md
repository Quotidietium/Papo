# 批次 114：首 join 停摆修复——冷类初始化预热（多核调度系列㉚）

日期：2026-08-30　分支：`perf/multicore-r3`　版本：0.70.0 → 0.71.0　性质：修复轮（0264 补丁 + jstack 归因 + A/B 三代验证）

## 背景

批次 113 七场景矩阵判定 fork 运行时稳态干净，但留下两个未除根的异常：boot/首 join
窗的 0.6-0.9s 主线程冻结（churn 两次复现）。验证器正确指出"修复"未闭环——本批
以 3/3 可复现的 FirstJoinStallBench + jstack 采样抓现场，除根并 A/B 验证。

## 复现（FirstJoinStallBench，3/3）

boot（tickProfile=1）→ Done+2s 写 gamerule → +4s 首 bot 连接（churn 同构时序）；
jstack 每 ~250ms 采样 Server thread 栈（60 帧覆盖 +2.5s..+6.1s）。

BEFORE（0.70.0）每 run 稳定命中：

```
stall tick=113-118 gap=475-827ms dur=2-6ms   ← 大间隔（主线程消失）
stall tick=115-119 gap=396-487ms dur=180-204ms ← 重 tick
```

首 join 扰动总量 ~1.2s——**每个重启后的第一个进服玩家必吃**，且与用户报告的
"TPS 突变+交互闪回"形态直接对应（冻结期所有交互停摆）。

## 根因（jstack 现场实证）

**源一**：首实体 NBT 加载（join 加载玩家周围区块的实体）→ `Entity.getBukkitEntity()`
→ `CraftEntityTypes.<clinit>`——CraftBukkit 实体注册表静态初始化定义数百个
adapter 类，首次触发它的线程=主线程（tick 间 pollTask 的区块系统回调内）：
`ClassLoader.defineClass1 ← CraftEntityTypes.<clinit> ← Entity.load ←
EntityType.loadEntitiesRecursive ← ChunkTaskScheduler.executeMainThreadTask ←
pollTask ← recordTaskExecutionTimeWhileWaiting`。

**源二**：首玩家构造 → `ServerPlayer.<init>` → `HashOps.<clinit>` → guava
`Hashing$Crc32CSupplier.<clinit>`（CRC32C intrinsic 的 Class.forName 探测）及其
后续冷类——在 `runAllTasksAtTickStart`（tick 头任务排水）内的
`PrepareSpawnTask.spawn` 触发。

gap 计时构成闭环：tick 间 park（正常）+ tick 头 spawn 冷类加载（~200ms）+
重 tick dur——全部落入 END-to-END gap。

## 修复（0264）

`DedicatedServer.initServer` 尾部（"Done" 前）异步预热：

```java
CompletableFuture.runAsync(() -> {
    Class.forName("org.bukkit.craftbukkit.entity.CraftEntityTypes");
    Class.forName("net.minecraft.util.HashOps");
});
```

**等价性论证**：两个 `<clinit>` 只构建静态查找映射/探测 intrinsic（无事件、无
世界状态、无网络包）；JVM 初始化锁保证单次执行；提前到 boot 期（无 tick 运行）
完成——可观测行为零变化，首 join 只是不再在主线程付类加载成本。红线 ✓。

## A/B 三代

| 版本 | 首 join 大 gap（3 run 最差） | 重 tick dur | 扰动总量 |
|---|---|---|---|
| 0.70.0（修复前） | 827ms | 180-204ms | ~1.2s |
| 0.71.0 v1（+CraftEntityTypes） | 522ms | 142-230ms | ~0.75s |
| 0.71.0 v2（+HashOps） | 490ms | 120-183ms | ~0.65s |

**冷启动浪费削减 ~46%**。v2 的增量在噪声内（490 vs 522）——**剩余 ~0.5s 的构成
已从"纯浪费"转为"首 join 真实工作"**（ServerPlayer 构造/全量 join 包广播/tracker
注册/区块发送启动，vanilla 同付）+ 少量不可经济穷举的残余冷类。继续压缩将触碰
语义红线，收束。

## 判例

1. **首 join 冷类风暴是 boot 后首位玩家的系统性冻结源**——0263 stall 墙钟行 +
   jstack 采样的组合是定位该类问题的标准仪器（本批全程只用这两个工具闭环）。
2. **预热类初始化是零等价代价的修复**——JVM 初始化锁语义使时机迁移不可观测。
   新的冷路径热点（未来插件生态/join 路径扩展）可按同模式追加清单。
3. **gap 的计时学**：END-to-END gap 含 tick 间 park + tick 头任务排水
   （runAllTasksAtTickStart）+ dur——归因时必须拆层，jstack 的 parked 样本
   不等于"无辜"（等待正常，真正吃时间的是相邻 RUNNABLE 样本）。

## 回归门（0.71.0，全绿）

- JoinPhaseBench：稳态重连 spawn 中位 **20ms**（0.70.0 为 16/26ms——同级波动，
  无回归）；20-bot burst lastSpawn 658ms（历史 568-634ms 同级）。
- ChurnStabilityBench（10 槽×30s，120 次 join/quit）：gate PASS、logErrors=0、
  正常停机；首 join 扰动同向改善（0.70.0: tick 75/76 gap 599.6+388.9ms ≈1.16s →
  0.71.0: tick 68-70 合计 ≈0.85s，共享机噪声内）。

## 复现

```bash
cd benchmark
java -cp build/classes papo.bot.FirstJoinStallBench ../paper-server/build/libs/Papo-1.21.11-0.71.0.jar 3
# BEFORE 对照：0.70.0 jar 同命令
```

## 补充：残留 ~0.5s 摊销空间评估（终版，v2 修复 180 帧 jstack 全量分类）

对修复后运行的 3×60 帧样本按主线程栈顶分类，停摆窗内构成实证：

1. **LambdaForm/CallSite 惰性链接**（`InvokerBytecodeGenerator`/`InnerClassLambdaMetafactory`
   字节码生成，如 `LandRandomPos` 内联 lambda 首链、`ServerboundPlayerInputPacket` 类
   加载及其 jar Inflater 读取）——JVM 级冷执行，`Class.forName` 预热**覆盖不到**
   （LambdaForm 只在真正执行时编译），安全预热不可行（后台执行这些链路需真实
   世界/寻路上下文）。
2. **区块 postProcessGeneration**（`NewChunkHolder.handleFullStatusChange →
   postProcessGeneration → updateFromNeighbourShapes`）——首 join 扩展票务半径使
   边缘区块 LOADED→TICKING 升级的形状后处理，**真实语义工作**（vanilla 同付）。
3. 零散包/实体类冷加载——穷举不经济（上百类，抽样命中率为递减分布）。

**结论：0264（冷类预热）已达该修复模式的等价安全边界**；继续压缩需改变 join
语义（构造/后处理时机），触红线。残留 ~0.5s 中约半数为 JVM 冷执行、半数为
首 join 固有工作。

## 补充：插件命令派发型（用户实例四型假说之三）的非源论证

菜单类插件每交互 dispatchCommand 的服务端固定开销 = `ServerCommandEvent`
分配 + 零监听 HandlerList 遍历（纳秒级，0125/0134 同形态）——**非停摆源**；
stdin 群发实验的 0.3-0.9s 停摆来自命令语义本体（fill/summon 的世界修改），
属插件侧责任，服务端不可等价优化。四型假说本环境验证状态全齐：贴墙型=rs441
数据；周期型=ent2000 9.7min autosave 无尖峰；突发群型=stdin 群发实证+本节
事件包装排除；主机型=共享机 boot 离群观察。

## 用户实例源确认：硬外部依赖（数据收集指引）

用户实例上的持续性波动源确认需要其服务器数据（本环境无法替代）：

1. 以 `java -Dpapo.tickProfile=1 -jar Papo-1.21.11-0.71.0.jar` 启动；
2. 出现症状时段后查 `logs/latest.log` 中 `PapoTickProfile.stall tick=… gapMs=…
   durMs=… at=HH:mm:ss.mmm` 行；
3. 与同文件日志行对齐：等周期≈autosave/备份任务；与交互时刻对齐≈插件命令
   派发（检查菜单插件点击处理）；dur 低 gap 大且无日志对应≈主机级（CPU steal/
   页文件/杀软）；
4. 将含 stall 行与对齐日志的时段片段反馈，可据此实施针对性修复。
