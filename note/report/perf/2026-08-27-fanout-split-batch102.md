# 批次 102 报告（2026-08-27）— sendChanges 面 build-vs-fanout 分解：结构封闭（多核调度系列⑱，0.63.0→0.64.0，勘察轮）

主题：批次 98/101 遗留的 `chunkMap.tracker.sendChanges` 高方差面（160bot min 1.0-1.5ms /
avg 2.8-4.7ms）内部分解。新增 0257 探针：`TrackedEntity.sendToTrackingPlayers`（逐 viewer
扇出环）主线程累计器，`tracker.fanout` 每追踪 tick 冲洗；**build = sendChanges − fanout**。
默认关零行为变化。

## 分解实测（160 bot × 6min，0.64.0，util 97.6% 争抢 epoch）

| 分解 | avg | min |
|---|---|---|
| chunkMap.tracker.sendChanges（总） | 2093us | 1232us |
| — chunkMap.tracker.fanout（逐 viewer send 环） | **2065us** | **1133us** |
| —（差分）包构建+状态机 | ~29us（1.4%） | ~99us（8%） |

（min 窗口径下 build 占比 8% 为上界——争抢 epoch 的 min 窗本身仍含放大；avg 口径 1.4%。）

## 结论：面封闭（结构性，无红线内优化空间）

1. **扇出是面本体（>92-99%）**：聚堆 160 bot 下每 tick ≈N²/2 对移动包投递（玩家
   updateInterval=2），12.7k send/tick × ~90ns = 1.1ms，与 fanout min 1133us 定量吻合。
2. **每 send 成本已紧**：~90ns 覆盖 Connection.send 主线程快路径（isMainThread/isReady/
   队列空判定 + eventLoop().execute 任务发布）——Paper 已优化路径（批次 60 判例：netty
   每批 64 任务+wakeup CAS 守卫，批量化无收益）。
3. **对数 N²/2 是追踪语义本体**：每个已配对 pair 必须收到每次移动更新——削减投递=
   客户端可见更新缺失=行为变更，超精确性红线（与批次 101 的 purge 不同：purge 是
   冗余重复计算，投递是语义载荷）。
4. **高方差归因外部**：min 1.2ms vs avg 2.1-4.7ms 的漂移与争抢 epoch 的 util 同步
   （netty eventLoop 队列/CAS 在 CPU 争抢下放大），非服务端浪费。
5. 包构建（差分 ~30-100us）无优化价值（<8%）。

## 验证

全量 test ✓；0257 rebuild 干净；0.64.0 jar ✓；四态冒烟 10/10 全绿；160 分解 exit 0 /
logErrors=0 / 走满。探针默认关（-Dpapo.tickProfile=1 诊断专用）。

## 系列前沿状态（批次 97-102 后）

聚堆规模下的主线程面全部完成勘察与处置：listenerTick 密度超线性（99 消除）、aiStep
分配（100 消除）、maintain purge 风暴（101 消除）、sendChanges 扇出（102 封闭=语义
本体）。剩余为线性常量成本（~34us/玩家 tick 链 + N²/2 投递地板），进一步削减需
投递模型变更（客户端插值容忍/区域化，Folia 级）超红线。160 聚堆 bot 主线程稳态
（净 epoch）≈25-30% 利用率无饱和。
