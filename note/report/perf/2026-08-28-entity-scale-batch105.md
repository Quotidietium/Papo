# 批次 105 报告（2026-08-28）— 实体规模轴勘察：负载生成 harness 四版迭代与仪器教训（多核调度系列㉑，勘察/基建轮，无服务器代码变更，版本保持 0.65.0）

主题：R2 最后一轮（用户决断本轮后终止循环）。目标为非玩家实体规模轴（实体 tick/追踪
在数量放大下的画像）新建负载生成 harness [EntityScaleBench](../../../benchmark/src/papo/bot/EntityScaleBench.java)。
**结论：轴的量化未达成——实体持续在场无法在本会话环境内确立**（下述判例链）；
harness 与四条仪器教训归档，轴留作开放前沿。

## Harness 设计与四版迭代

- **v1（spreadplayers）**：批量 summon 于出生点上空 + `spreadplayers` 散布 →
  **500 实体时 spreadplayers 主线程阻塞 ~5 分钟**（tick 停摆，仅 2 个 400-tick 窗口，
  行为门仍绿）——命令在主线程执行的教科书案例，运营判例：大 N 时禁用。
- **v2（±46 网格 + 行走 bot）**：数据平坦 → 归因行走 bot 6 分钟北移 90 格离开牛群，
  超 EAR 激活半径 32 后实体不 tick（仪器无效负载）。
- **v3（NoAI + ±20 网格 + 站立 bot）**：仍平坦 → 控制台反馈计数暴露真相。
- **v4（裸召唤零 NBT + y=104）**：summon 反馈 ok=500/bad=0 但在场探针 false——
  实体在场问题未解，确认跑遭环境击杀后按用户停止指令收口。

## 关键诊断链（stdin/console 工具 stdintest3/4 归档）

1. **控制台 stdin 通道正常**：`say` → `[Not Secure] [Server] HELLO_CONSOLE` 实证；
2. **成功消息格式陷阱**：1.21.11 为 "Summoned **new** Cow"（无 "a"）——首版反馈
   grep 假阴性（ok=0 实为 500 成功）；
3. **实体 NBT 键名易变性**：`ActiveEffects`（及 NoAI 疑似）在 1.21.x 被静默丢弃
   （summon 成功+效果无效）→ 80 格坠落全灭（在场探针 false 与之自洽）；v3/v4 无/简
   NBT 仍全灭（y=110 疑似山体内窒息），未及进一步定位；
4. **选择器体积陷阱**：`x,y,dx` 缺 dy/dz 时体积退化为一维线——在场探针需省 y 或
   给全三维。

## 数据披露

0/500/1000/2000 四点"曲线"全部平坦（worlds/entities/tracker 与 0 点基线在噪声内）——
**仅在"实体实际不在场"的前提下成立**（在场探针 false），故作为基线+冒烟数据归档，
不作为实体规模结论。真正的实体规模画像需先解决在场问题（候选：`/place` 模板、
刷怪笼 NBT、或插件式实体注入），留作开放前沿。

## 交付清单

- EntityScaleBench v4（harness，含在场探针与反馈计数）；
- stdintest3/4（stdin/console 诊断工具）；
- 四条仪器判例（上）+ spreadplayers 运营判例；
- 本报告 + optimizations.md 条目 + R2 终止记录。

## 验证

全程行为门绿（各轮 exit 0 / logErrors=0 / 无端口孤儿）；无服务器代码变更，
0.65.0 保持；全量 test/冒烟沿用批次 103/104 的绿态（本轮未触碰服务器源码）。
