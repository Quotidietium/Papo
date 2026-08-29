# 批次 112：红石轴勘察——环振荡器 harness + tickPending 子相位拆分（多核调度系列㉘）

日期：2026-08-29　分支：`perf/multicore-r3`　版本：0.68.0 → 0.69.0　性质：勘察/基建轮（0262 探针 + RedstoneScaleBench harness v7）

## 目的

批次 111 收口实体轴等价保持战役（105→111 七批，AI 计算仅 16% 无下放面）后，
记忆指定的下一前沿为**红石/流体轴**（R2/R3 记录的未勘察面）。本批交付勘察基建：
负载生成 harness + tickPending 段子相位归因仪器，量化红石负载的主线程成本结构
与容量墙，为后续优化批次的靶点选择提供证据。

## 交付一：0262 探针——tickPending 子相位拆分 + 活性计数器

`ServerLevel.tick()` 的 `tickPending` 超相位此前整段计入 `level.tickPending+misc`
（批次 90 基建），计划方块/流体 tick 与方块事件混不可分。0262 拆出三个子键：

- `level.blockTicks` / `level.fluidTicks` / `level.blockEvents`（nanos 相位）
- 计数器 `rs.blockTickRuns` / `rs.fluidTickRuns` / `rs.blockEventRuns`
  （挂点：`tickBlock`/`tickFluid`/`runBlockEvents` 应执行位，`papo.tickProfile` 门控，
  默认关——静态 ENABLED 分支，禁用态零成本）

计数器是本批的**在场度量创新**：红石无 say 语义（实体轴的 `execute as ... run say`
不可用），`rs.blockTickRuns` avg/tick 以 400-tick 窗均值度量振荡存活度，对环相位
不敏感，替代结构采样成为权威门。

## 交付二：RedstoneScaleBench v7——中继器环振荡器阵列 harness

负载模型：每 cell = 4×4 闭环（4 个 delay=1 中继器居边单向 + 8 红石粉含 4 转角）。
种子 = 末位置中继器以 `powered=true` 落位（输入侧无源 → 自身延迟后跳回断开，
形成宽度 2gt 的脉冲沿闭环永久循环）：环周期 8gt，每 cell 稳态恰 ~1 次计划方块
tick/tick + 粉强度重算级联。无火把无烧毁、纯中继器确定性稳态。N=441 实测计数器
**441.0/tick 与理论值分毫不差**。

v7 确定性设计（七版迭代的终态）：

- **天空盒**：StandB00 钉到绝对 y=200，悬空封闭盒（石地板 y-1 + 玻璃墙 y+0..+31 +
  barrier 顶 y+32）——无地形交互，物理上消灭水/沙/重力方块/清除边界全部向量；
  盒内无需任何清场 fill（基础设施仅 6 条）
- **分块节流写入**（writeChunked，50 行/40ms）：stdin 大块突发=命令行选择性丢失
  的触发条件（见判例 3）——节流后 N=441 首轮校验即 441/441 零修复
- **校验-修复循环**（≤3 轮）：RING_i/REP_i 探针找缺 → REP 缺=环静态、全 12 槽重发
  +重种子安全；仅 RING 缺=环在振荡、只补粉防双脉冲
- 环批单发：重发未上电中继器会重置振荡环相位制造额外瞬态脉冲
- 10 站立 bot 锚定+加载（`execute at StandB00`）；种子分 8 批 ~1.2gt 相位去同步
- 三重在场门：RING_i（粉转角，精确=N）+ REP_i（种子中继器，精确=N）+
  `rs.blockTickRuns` 尾 1/4 窗中位数 ≥ 0.6×N
- 用法：`java papo.bot.RedstoneScaleBench <jar> [cells=100] [windowMs=360000]
  [bots=10] [rsimpl=vanilla|ac|eigencraft]`

## 判例链（v1→v7，如实记录——三阶假说更替）

1. **同相位采样全有/全无**（v1）：种子同批落位 → 全环同相位，瞬时 `powered=true`
   采样要么全中（25% 概率落在 2gt ON 窗）要么全空——PULSE A=16/B=0 精确复现。
   结论：瞬时方块态采样对相位不鲁棒，弃用；`rs.blockTickRuns` 400-tick 窗均值
   （对相位与控制台命令聚批均不敏感）为权威度量。
2. **"物理损伤"三阶假说更替**（v2-v5，本批最长的一条弯路）：
   a. *沙坠假说*：缺失槽位沿环路径连续（2×3 足迹）→ 判为坠落沙块连毁；32 层
   清场+沉降清扫+barrier 封顶+玻璃挡墙逐级加码——损伤依旧。
   b. *水侵假说*：世界直查（WorldInspect 驱动 + forceload + playerdata Pos 提锚点）
   实证损伤槽位被 water 占据、对照 cell 完好——判为天然水体入侵；全高墙全封闭
   盒——损伤依旧（同水域不同 cell）。
   c. *真因*：14 条 fill 只执行 6 条且无任何失败日志 + `gamerule` 行被截断损坏
   （"Incorrect argument"）+ 修复循环对同 cell 三轮无效 → **stdin 大块突发写入的
   命令行选择性丢失**；叠加**锚点漂移**（出生点散布 ±10 格、playerdat Pos 逐 run
   变化：441d (0.5,71,413.5) → 441e (10.5,74,411.5)）使丢失的清场 fill/setblock
   表现为"特定 cell 损伤、A==B 稳定、连续槽位缺失"等物理损伤假象。
   根治=分块节流写入（丢行触发条件=突发）+ 天空钉定锚点（坐标系全程静止）。
   **方法论教训：harness 异常的归因必须先排除"命令未到达"再归因物理世界；
   say 回显只在进程 stdout 不入 latest.log，命令失败是 INFO 级——两类静默都要
   专门仪器（echoes.txt 落盘 + 结构探针）才能看见。**
3. **stdin 管道可靠性判例**（本批核心交付之一）：Paper 控制台对同一 write() 内
   突发到达的大量命令行存在选择性丢失（无错误回显）；50 行/40ms 节流后 N=441
   首轮即全绿。所有经 stdin 灌命令的 harness（EntityScaleBench 等）在命令量
   增大时都可能踩此坑——writeChunked 模式应作为后续 harness 的默认实践。
4. **进程孤儿与端口占用链**（运营判例）：harness 异常退出时 stop 可能失效 →
   服务器孤儿持有 25595 → 后续运行 boot 即死（表现为连续"boot 期死亡"假象）；
   被 TaskStop 的 bash 后代会继续执行脚本后续迭代（隐形阶梯与冒烟抢端口）。
   根治：finally 中 waitFor 超时后 destroyForcibly；杀后台任务须连带清进程树。
5. **无玩家 boot 的区块不保活**：WorldInspect 直查遗留世界时 `if block` 全静默
   失败（无持久区块）——forceload add 前置 + 25s 加载等待后才可探。

## 负载特征（机理备注）

- **脉冲追赶竞态**：`DiodeBlock.tick` 的 ON 分支在输入已回落（`!shouldTurnOn`）时
  追加一次计划 tick 用于关断（DiodeBlock.java:73）——受损/重置环的残躯会产生
  1.02-1.3× 计划 tick/环；健康稳态（结构门 PASS）实测精确 1.0×/环/tick
  （441.0/441）。报告口径以健康环为准。
- 单中继器跳变成本 = tickBlock 执行 + setBlock(POWERED) + 邻居更新扇出 + 8 粉
  强度重算级联——N=100 实测 ~72us/计划 tick（下节）。

## 阶梯矩阵结果

六点矩阵（10 bot、6min 窗、vd=6、尾 1/4 窗中位数口径；全部 presence/activity/
logErrors/exit 四门全绿，计数器与 N 分毫不差——v7 确定性成立）：

| 点 | blockTicks us/tick | 计数器/tick | us/计划tick | worlds 相位占比 |
|---|---|---|---|---|
| N=0 vanilla | 6.2（地板） | 0.0 | — | — |
| N=100 vanilla | 5,343.8 | 100.0 | 53.4 | — |
| N=256 vanilla | 12,823.3 | 256.0 | 50.1 | — |
| N=441 vanilla | 23,713.0 | 441.0 | 53.8 | tickPending+misc 24.5ms |
| N=256 ALTERNATE_CURRENT | 2,549.7 | 256.0 | **10.0** | 3.4ms |
| N=256 EIGENCRAFT | 5,562.2 | 256.0 | 21.7 | 6.3ms |

要点：

1. **VANILLA 完美线性**：us/计划tick 恒定 50-54（无密度超线性抬头——与实体轴
   U 型曲线不同，环振荡器互不连接无跨 cell 交互），外推容量墙 ≈ 890 环
   （~10,700 红石器件）贴满 50ms 预算减固定成本。N=441（5,292 器件）达预算
   47% 仍保 TPS 20（wallUtil 100%）。
2. **Paper 既有旋钮的量化（运营推荐，非 Papo 改默认）**：同负载同在场
   ALTERNATE_CURRENT **5.03×**（10.0 vs 50.1 us/计划tick）、EIGENCRAFT 2.30×。
   AC 换来容量墙外推 ≈ 4,470 环。语义差异（更新序/中间态可见性）由运营侧
   自担——Papo 默认保持 VANILLA（等价红线）。
3. **红石负载的相位画像**：worlds 增量几乎全部落在 `level.blockTicks` 子相位
   （blockEvents <1us/tick——中继器无方块事件广播负担；fluidTicks 地板 ~3us）；
   chunkSource/entities 与批次 106-111 实体轴画像一致（~0.8/0.35ms）不受红石
   负载影响。tickPending+misc 与 blockTicks 差值 ~800us 为该超相位的 raid/
   脚手架固定成本，与负载无关。

## 运营量化：redstone-implementation 旋钮

`config/paper-world-defaults.yml` → `misc.redstone-implementation`（上游既有，
默认 VANILLA）。红石重度服（大型农场/机器/时钟阵列）建议切 ALTERNATE_CURRENT：
本 harness 实测 5.03× 计划 tick 成本削减，且零 Papo 侧代码介入。Papo 不改默认。
运营验证可用本 harness：`... 256 360000 10 ac` 对比 `vanilla` 的
`level.blockTicks` 读数。

## 结论与下一批次方向

- 红石轴**勘察闭环**：负载模型（环振荡器）+ 子相位仪器（0262）+ 六点矩阵
  （含运营旋钮量化）交付。VANILLA 每 1 计划 tick ~53us 的成本结构确立，
  主面在 blockTicks 段（tickBlock 执行 + setBlock + 邻居更新扇出 + 粉强度
  重算级联）。
- **下一批次候选**（按证据排序）：① blockTicks 段内部归因（tickBlock 执行 vs
  setBlock/邻居更新 vs 粉重算——需 0263 子子相位探针分解，评估 vanilla 路径
  等价优化空间）；② 流体轴负载生成（水渠/滴管网 harness——fluidTicks 地板
  3us 量级，待负载下画像）；③ 红石轴多核化论证（blockTicks 队列的 tick 优先级
  语义=串行等价红线，预计否决面——论证价值在于正式留档）。
- harness 判例资产（stdin 节流写入/天空盒/进程孤儿链）对后续所有批次复用。

## 复现

```bash
# 阶梯（6 点全绿）
cd benchmark && bash b112-ladder.sh
# 单点
F:/Java/21/bin/java -cp build/classes papo.bot.RedstoneScaleBench \
  ../paper-server/build/libs/Papo-1.21.11-0.69.0.jar 256 360000 10 ac
# 解析
python b112-parse.py F:/TEMP/papo-b112-N*.log
```

