# 批次 78 报告（2026-08-26）— 核心感知线程池自动 sizing（Netty event loops + region IO 池）

主题：**多核调度**——多核调度系列第一轮。历史遗留的三个固定线程数让大核主机吃不满：
①spigot.yml `settings.netty-threads` 默认平 **4**（64 核主机也只有 4 个事件循环，多连接高带宽
时编码/压缩排队）；②paper-global `chunk-system.io-threads` 的 auto 哨兵（-1）落到
`Math.max(1, -1)` = 平 **1**（全部世界全部 region file 的读写串行过单线程——一个冷读的磁盘
延迟卡住其他一切 region IO）；③无统一预算（各池各自为政）。本批新增
`io.papermc.paper.util.PapoParallelism` 集中 sizing 决策：netty = cores/4 clamp [4,16]、
regionIo = cores/8 clamp [1,4]，显式配置永远优先（兼容红线）。

## 1. 等价性 / 安全性（逐项）

- **region IO 并行安全性（核心论断，源码+javap 实证）**：moonrise 的 region IO 经
  `MoonriseRegionFileIO.RegionDataController` 构造 `AreaDependentQueue(ioExecutor, shift 5)`
  （`MoonriseRegionFileIO.java:1440/1446`），每 chunk 任务以**点区域** `(chunkX>>5, chunkZ>>5,
  range 0)` 入队。AreaDependentQueue（concurrentutil 0.0.8，javap 验证）内部以
  `ReentrantAreaLock` + 位置表实现**区域重叠串行化**：同一 region file 的任务永不并发，只有
  不相交区域（不同 region file）才会派发到不同池线程并发执行。⇒ 增加 IO 线程**只会**并行化
  不同 region file，永不引入同文件并发写。
- **兼容性**：显式 `settings.netty-threads` / `chunk-system.io-threads`（>0）逐字保留优先；
  spigot.yml 已物化 `4` 的存量安装保持 4（INFO 提示可删 key 启用 auto——`count==4 && def>4`
  才触发，新装/已删 key 不受扰）；`io.netty.eventLoopThreads` property 机制不变，主网络路径
  与 ManagementServer（`new NioEventLoopGroup(0)` 走 Netty 同源默认值）一致受益。
- **核心数语义**：`OSNuma.getNativeInstance().getTotalCores()`——NUMA 可用时=物理总核
  （与 moonrise 自身 worker 池 sizing 同源），NoOp 回退=`Runtime.availableProcessors()`
  （javap 实证构造），`Math.max(1, …)` 防御退化值。cgroup 限核场景天然被尊重。
- **不降低流畅度（goal 红线）**：netty 下限 4=旧值（≤16 核主机行为不变），事件循环空闲即
  park 于 selector（零 CPU 成本），上限 16 防止巨核主机过量线程；regionIo 下限 1=旧 auto 值
  （≤8 核行为不变），上限 4（region IO 本质磁盘带宽受限，更多线程无益）；worker 池 sizing
  逻辑**未触碰**（moonrise 既有 cores/2→/2 曲线保持）。
- **池实现零改动**：`BalancedPrioritisedThreadPool.adjustThreadCount` 是 moonrise 既有 API
  （初始化与 `/paper chunkstats` 运行时调整共用），本批只改喂给它的数字来源。

## 2. sizing 曲线（本机 32 核 → netty 8 / regionIo 4）

| cores | 1-8 | 16 | 32 | 64+ |
|---|---|---|---|---|
| netty loops | 4（=旧值） | 4 | 8 | 16 |
| regionIo threads | 1（=旧值） | 2 | 4 | 4 |

## 3. 基准（IoPoolScalingBench，真实 concurrentutil 0.0.8 池+队列，8 region file × 16 任务）

模型忠实复刻 `MoonriseRegionFileIO` 派发结构（同构点区域+同 shift），任务体=4KiB 真实文件读
（独立扇区）+ 模拟设备延迟（parkNanos 500µs；Windows 定时器粒度实际放大到 ~15.6ms——两配置
每任务付出相同延迟，**相对加速比是指标，绝对墙钟失真**）。

**安全性自检（threads=1 与 4 均跑）ALL OK**：
- ① 每 region file 内并发度恒 ≤1（多线程下同文件绝不并发——优化安全性的直接证明）；
- ② 每 region file 任务完成顺序==提交顺序（FIFO 保持）；
- ③ 全部任务恰好完成一次；
- ④ threads=4 时实测 maxConcurrentTasks=4（并行度真实兑现，不同 region file 重叠执行）。

| threads | best(ms) | mean(ms) |
|---|---|---|
| 1（旧 auto） | 1992 | 2001 |
| 4（32 核 auto） | **450** | 499 |

**speedup 4.43×**（128 任务；1 线程=128×串行延迟，4 线程=每线程 ~2 个 region 流×16 任务串行，
结构性差异即"阻塞 IO 不再互相卡死"）。页缓存热读变体（无延迟）实测 1 线程反而更快
（3ms vs 6ms——任务 µs 级时池唤醒开销占主导），留档：**本优化的收益面是冷读/阻塞 IO 场景**
（玩家分散跑图、多世界加载、备份后首访），热页缓存下多线程无害（无损失，自检同绿）。

## 4. 同批构建修复（独立提交）

`PaperConfigurations.defaultFieldProcessors()` 返回类型放宽为与
`InnerClassFieldDiscoverer.globalConfig/worldConfig` 参数一致的宽类型——上游遗留的
`? extends Annotation` 窄类型使 javac 在**被调方经 class 文件签名解析**（增量编译）时拒绝
本应合法的通配包含转换（源码同批编译则接受；二分实验定位：同调用方源码，Mini 替身源码编译
通过、二进制解析失败）。私有静态方法签名，无 API/行为变化。`--rerun-tasks` 全量编译不受
影响（BUILD SUCCESSFUL 实证），修复只解除增量编译陷阱。详见 build.md 新节。

## 留档

- worker 池（chunk 生成/加载 worker）auto 曲线未动：moonrise 既有 cores 曲线已合理，且 worker
  数量影响生成吞吐的调参面太大，本批只做"预算集中化"的基础设施。
- Netty 事件循环规模本身无微基准（循环空闲 park，收益面是连接数×带宽超 4 循环吞吐的高压
  场景，属于容量上限提升而非热路径加速；公式曲线+空闲零成本论证留档）。
- 基准新依赖：concurrentutil-0.0.8 + slf4j-api-2.0.1 入 benchmark/lib（run.sh 自动下载）。

## 验证链

compileJava（增量+全量 --rerun-tasks）BUILD SUCCESSFUL → 基准自检 ALL OK → scaling 4.43× →
全量 test（见 optimizations.md 批次 78 记录）。源码改动全部在 `src/main/java` 直提交面
（0155 先例），无补丁编号。
