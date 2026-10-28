# R41（批次 182）：驻留稳态 tick 窗分配普查→InsideBlockEffectApplier 双机械面收割（→0.106.0）

日期：2026-10-12 ｜ 轮次：R41 ｜ 判定：**新窗口普查+收割**（0.105.0 → 0.106.0）

## 立项背景：空白窗口

R24-R40 十六轮分配源普查的 JFR 窗全部落在**行走/流送段**（世界生成支配的分配洪峰）。
驻留稳态 tick 窗——12 bot 在线持有区块票据、无流送、纯稳态 tick——的分配面从未被
普查过。R24 曾在流送窗见过 tick 路径巨权事件（tick:848 fastIterator 595.7MB×1）但因
计数口径不可归因留档。本轮以驻留窗为新优化点（/goal「每一轮从不同的优化点出发」）。

工具：`benchmark/alloccensus-r41dwell.sh`（发现腿）+ `alloccensus-r41dwelllegs.sh`
（配对腿）——MemSoakVerify 同场景，JFR 窗以 harness 日志 `walk phase started` 为行走
零点，+150s 行走结束 +25s 尾随流送排空后开 60s 采样（覆盖驻留 25-85s；MemSoak
histogram 于驻留+120s 采集不与窗重叠）。窗内无世界生成帧=相位干净。

## 发现腿画像（B-105 驻留窗，1,432 事件/60s）

驻留窗事件密度为流送窗的 23%（6,192→1,432），构成与流送窗完全不同：

| 族 | in-stack 事件 | 占比 | 判定 |
|---|---|---|---|
| spark 后台采样器内部 | 639 | 44.6% | 判负（上游） |
| JFR 自观测（networkStatFor 链） | 92 | 6.4% | 判负（观测伪影） |
| **flushStep 空 addAll（收割面）** | **34** | **2.4%** | **立项收割** |
| 实体移动/随机刻/网络/JDK 散布 | ~667 | ~46.6% | 逐面判定（见下） |

- **spark 族判负**：Paper 内置 spark 在启动时自动开始后台 profiler（本机 Windows 无
  async-profiler 支持→Java 引擎持续线程采样）。getThreadInfo 150+λ 85+
  StackTraceNode 73+Integer.valueOf 110+BigInteger.multiply 32（RollingAverage.
  percentile←CpuMonitor）+regex 41+……全在 me.lucko.spark 包内=上游诊断组件的默认
  观测行为，非游戏路径。
- **JFR 自观测判伪影（方法学注记）**：`JfrProfiler.networkStatFor ← onPacketReceived/
  onPacketSent` 每包做 InetSocketAddress.toString+StringBuilder+copyOfRange 链——这是
  我们启动 JFR 录制激活了服务器内建 JFR 网络统计，**观测者自付成本，非无录制基线的
  行为**。驻留窗普查解读时必须剥离此族，否则它以 ~100 trips 假面霸榜。
- **实体面散布全判负**：makeBoundingBox 27（←Entity.checkInsideBlocks←
  applyEffectsFromBlocks，与收割面同管线——AABB 是碰撞域 API 的值货币，final 字段无
  setter 不可 scratch 化，改签名=碰撞 API 分叉超机械面授权）；AABB.move 14/
  inflate 11/Vec3.add 21（移动物理结果对象+tick 红线路径）；randomTick offset 16
  （<阈）；NaturalSpawner.createState 12（<阈）；checkInsideBlocks λ 11（<阈）；
  setBoundingBox 8；ActivationRange 6。均低于流送校准的 ≥20 阈。
- **上游/JDK**：Netty flush0/CompressionDecoder 的 DirectByteBuffer.slice 52（包
  解压路径）；AQS ConditionNode、WEPoll processEvents 等 JDK 内部。

## 收割（补丁 0294）：flushStep 空 addAll 守卫 + applyAndClear 索引循环

`InsideBlockEffectApplier.StepBasedCollector` 是 per-Entity 字段复用的逐步效果收集器
（1.21.9+ 实体穿方块效果特性），**每实体每移动段**运行且几乎恒空收集。两处机械分配：

1. **flushStep 的 `finalEffects.addAll(list)`**：`ArrayList.addAll` 无条件先
   `source.toArray()`（→`Arrays.copyOf(elementData,0)`=新零长数组），14 型 × 2 表 =
   **每移动段 28 个空数组**。守卫 `!isEmpty()`——空集 addAll 无任何可观察效果（返回值
   被弃、目标列表零变更），`clear()` 保留无条件执行=modCount 语义与 vanilla 逐位一致。
2. **applyAndClear 的增强 for**：空列表也要分配一个 ArrayList$Itr（EA 只能消热路径，
   解释/C1 窗仍实配——配对腿在面 1 修复后浮出残余 8 trips）。索引循环同序同元素
   （消费方为 vanilla 内方块效果应用器，循环中无结构性修改路径）。

恒等论证：两处均为空容器机械面——分配只发生在"什么都不做"的路径上；非空路径行为
逐位不变。发现腿 34 trips ≥20、单文件封闭、证明平凡，立项三门槛全过。

## 验证

- **恒等单测** `PapoStepCollectorEmptyAddAllTest`（4 用例，注册套件实测执行 4/0/0）：
  空循环无副作用且收集器保持可用（守卫的回归面）；同步型内 before→recorded(FREEZE
  applier Mockito verify)→after 次序；步界 flush 按到达序而非声明序（CLEAR_FREEZE
  先于 FREEZE 加入、断言输出序反转）；单步内跨型按 APPLY_ORDER 合并（空型穿插行使
  守卫路径）。自查纠偏一次：`verifyNoMoreInteractions` 与 applyAndClear 循环守卫的
  isAlive() 桩调用冲突（纯 harness 交互）→删该行，红灯暴露的是测试非代码。
- **链门**：rebuildPatches（0294）/applyPatches/`:paper-server:test --rerun-tasks`
  （XML 合计 **9,067 tests / failures=0 / errors=0**，恰为 9,063+4 新增）/compileJava
  （--rerun-tasks）全 EXIT=0+BUILD SUCCESSFUL；0.106.0 出包（初版+同族完成后 amend
  内部提交 8e541be4 重建，最终门全绿）。
- **MSPT 三序守护**（0.105.0 vs 0.106.0 最终二进制，六腿）：主序 B≤A 2/6（劣化
  +0.5..+1.7）·倒序 5/6（最劣 +0.2）·钉死 4/6（最劣 +0.3）。唯一跨序同号槽 s2
  （+0.7/+0.2/+0.3）逐腿复核=**主序 A 腿整腿异常快的外离**（该腿六槽 4.2/5.0/7.8
  全低于其余 A 腿 4.5-6.3/9.2 带宽），两轮三序十二腿合池 A 中位 6.25 vs B 6.30
  （差 0.05ms=噪声）；流送槽由世界生成支配、改动无生成通道（两守卫+一索引循环在
  实体路径，纳秒级）——R34 判例（同号但幅度<腿内摆幅且无机械通道=噪声）应用。
  改动自身路径的驻留槽（4-6）B 持平略优。**零回归成立**。
- **驻留窗配对腿**（A-105dw 721 事件 vs B-106dw 1,304 事件，事件总量差为工作量缩放
  面如实入档）：`copyOf ← ArrayList.toArray` **30→0**（三腿复现带 26/30/34→0）；
  `ArrayList.iterator ← applyAndClear` **8→0**；替代面无（守卫与索引循环零分配）；
  残余 in-stack 'InsideBlockEffectApplier' 130/87 全为判定面（makeBoundingBox 结果
  对象族）。两腿 MemSoak.gate logErrors=0。

## 判例

1. **驻留窗普查的观测者伪影剥离**：JFR 录制激活内建 JfrProfiler.networkStatFor 每包
   toString 链——驻留窗（分配率低、单事件权重大）中此类自观测族以 ~7% 假面霸榜，
   归因前必须先按 in-stack 剥离，否则误立项。
2. **空容器机械面通式**：`addAll(空)` 无条件 `toArray()` 新零长数组、空列表增强 for
   仍配迭代器——"什么都不做的路径也要分配"族在低速率窗口（驻留）权重放大显现；
   守卫/索引循环是零语义面的标准收割形态。
3. **同族完成判例（R39 沿用）**：修复主面后配对腿浮出同类阈下残余（迭代器 8 trips）
   ——同一收集器同一管线的机械残余应同轮完成，不留半脸。
4. **外离腿识别**：跨序同号槽先逐腿核对是否单腿整腿偏移（该腿全部槽同向偏离带宽）
   ——外离腿驱动的同号模式合池即消失，非两序同号信号。

内存账：驻留窗最大真实游戏面 30-34 trips→0；每实体移动段空情形分配 29 件→0（28 空
数组+1 迭代器）；驻留无变化（纯瞬态）。版本 0.106.0。
