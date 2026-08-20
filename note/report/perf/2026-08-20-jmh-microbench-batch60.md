# 批次 60 JMH 微基准报告（2026-08-20）— 批量分派否决 + send 快路径重排 + pairing 预尺寸

环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。netty 4.2.7（真实 NioEventLoopGroup 用于分派基准）。

## 1. 否决评估：主线程每包 eventLoop().execute 批量化（B1，未落地）

**候选**：`Connection.sendPacket` 非 event-loop 分支原每包 `execute(lambda)`（每包 1 捕获 lambda + 1 任务入队）。改为 MPSC 队列 + 每突发一次排水任务（CAS 边界防丢唤醒；event-loop 发送者保持 vanilla inline；每条目独立异常隔离）。

**两轮实测**（BatchedDispatchBench，真实 NioEventLoopGroup(1)，32 包突发模型）：

| 队列实现 | before（逐包 execute） | after（批量排水） | 结论 |
|---|---|---|---|
| ConcurrentLinkedQueue（首版） | 462.136 ± 275.076 | 2209.040 ± 2088.849 | **回退 4.8×**（跨线程 CAS + 每元素 Node 分配） |
| netty shaded MpscChunkedArrayQueue（修正版） | 534.188 ± 421.329 | 793.425 ± 157.866 | 仍劣化 1.49×（CI 部分重叠） |

**机制复查（关键：候选的理论前提不成立）**：原假设"每包 execute = 每包一次 selector 唤醒 syscall"。复查 netty 现代实现：`NioEventLoop.wakeup` 带 **CAS 守卫**（每 park 窗口至多一次唤醒，突发内后续 execute 只做 MPSC offer）；且 event loop 自身以 64 任务/批处理任务队列。即 vanilla 的逐包任务机制早已被 netty 内部摊销，可省的只剩每包一个 lambda 分配（TLAB 便宜，且被 0217 之后更强的出站路径优化覆盖）。批量排水反而多付：排水任务本身 + CAS 边界 + 双重队列入队（MPSC 排水队列 + event loop 任务队列）。

**否决**（实测无收益即撤，0100/0181 判例；且改动属 0209 类跨线程复杂度）。内部提交已摘除，基准类与两轮结果留档作复评依据。判例沉淀：**"消除每任务开销"类候选必须先核实运行时（这里是 netty event loop）是否已内建摊销**——纸面上的逐包成本可能早就不存在。

## 2. 0218 — Connection.send 立即发送判定析取重排

**热点**：`send()` 的立即发送判定 `canSendImmediate(conn, packet) || (isMainThread && isReady && queueEmpty && noExtra)`。play 阶段主线程发送（压倒性常态）时，**非白名单包**（实体移动/实体数据/方块/区块更新——绝大多数流量）要先走完 `canSendImmediate` 的 ~20 个 instanceof（KeepAlive/Chat×3/Title×5/Sound×3/Particles/PlayerInfo×2/BossBar/Pong，InnerUtil.java:916-935）全部 miss，才落到恒真的主线程臂。

**改法**：两臂均为无副作用纯谓词（isMainThread/isReady/isEmpty/getter 与 isPending/protocol/instanceof）且都导向同一 `sendPacket` 调用，求值顺序不可观察——把廉价的主线程臂（线程检查 + 3 字段读）提前短路。异步线程发送路径（主线程臂假）仍走 instanceof 链，行为逐字不变。

| 方法 | before (ns/op) | after (ns/op) | 倍率 |
|---|---|---|---|
| before_chainFirst vs after_cheapArmFirst | 0.923 ± 0.006 | 0.534 ± 0.004 | **1.73×**（CI 不重叠） |

- 诚实定位：绝对节省 ~0.4ns/包（JIT 后 20 个 instanceof 本身已被优化为亚 ns 级类比较）——纯重排零风险白捡，与 0217/0214 同一每包热链。
- **基准判例**：首版载荷用 static final 常量，被 JIT 整链常量折叠（两版同测 0.48ns 伪平）；输入改经 @State 非终态字段（JMH 保证不 DCE state 字段读）后真实差异显现。谓词类基准的输入必须经 state 字段。
- 自检 main：布尔等价矩阵（白名单命中/全 miss × 主线程臂真/假 × 协议 × isPending）ALL OK。

## 3. 0219 — ServerEntity.addPairing 配对包列表预尺寸（批次 50 遗留项）

`new ArrayList<>()`（容量 10）→ `new ArrayList<>(4)`：sendPairingData 常态产出 2-4 个包（spawn/typed data/passengers/equipment），默认容量超额分配于每次实体配对（区块加载突发期高频）。容量不经 List API 可观察，零风险（批次 50 survey 候选 5 原案）。

## 验证链

compileJava（--no-daemon）BUILD SUCCESSFUL（含 B1 摘除后复验）→ 自检 ALL OK → JMH 本报告 → rebuildPatches → applyPatches → 全量 test（见 optimizations.md 批次 60 记录）。
