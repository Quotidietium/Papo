# R43（批次 184）：保存 IO 缓冲池化重开评估（R36 判例应用）→正式判负关闭+内存战役收敛终局声明（版本保持 0.107.0）

日期：2026-10-12 ｜ 轮次：R43 ｜ 判定：**重开评估判负（正式关闭）**——审计轮，无代码变更

## 一、重开评估：定量与四支柱判负

R42 实体+保存窗普查见三面：`BufferedOutputStream.<init>`、`ByteArrayOutputStream.<init>
← RegionFile$ChunkBuffer.<init>`、`DeflaterOutputStream.<init>`。按 R36 判例（既优化的
代价面重开须先证净赢）评估。

**既有优化边界（源码核实）**：早期批次已完成该路径的实质收割——`RegionFileVersion`
写侧 `PapoDeflaterOutputStream`（**单槽 ThreadLocal 池化 native Deflater**，close 时
reset+还槽，JDK 字节码实证显式提供 Deflater 则 close 不 end）+读侧 `PapoInflaterInputStream`
（同型 Inflater 池）+缓冲按调用分块语义论证地调至 8192/32768。R42 普查三面是该优化的
**残余外壳代价**：每保存 3 个 stream 壳 + 32KB+8KB+8KB 三段缓冲。

**配对定量**（R42 配对腿既有 JFR，两二进制保存 IO 代码相同→配对即复现带）：
`BufferedOutputStream` **25/25 trips**（filt 25.6/24.6MB）、`ChunkBuffer` BAOS **19/19**
（18.8/17.8MB）、Deflater 外壳 in-stack **16/17**；合计 ~60 trips/保存密集 60s 窗，
filtered 权重 ~62MB=区间上界（实际 ≈48KB×保存数，每 5 分钟自动保存突发一次）。

**四支柱判负**：

1. **代价面定性**：native 重物（zlib 状态+Cleaner 注册）已被池化收割；残面=既有优化
   的固有外壳成本。R36 PacketEncoder byte[] 同型。
2. **量级**：~60 trips 低于流送窗收割门槛一个数量级；young 垃圾突发每 5 分钟一次、
   序列化后的压缩写出全在 IO 线程（`moonrise$startWrite` 栈），主线程 MSPT 无通道。
3. **恒等可证性失败**：唯一消除路径=可重定向的池化 stream 壳（借出时重绑底层
   ChunkBuffer）——须依赖 JDK `FilterOutputStream`/`DeflaterOutputStream` 未文档化内部
   状态的复用语义（closed 标志/缓冲残留/异常路径下借还），跨 JDK 更新脆弱=稳定性红线
   优先于收益；R24 立项三门槛的「恒等可证」腿不成立。
4. **子选项排除**：ChunkBuffer 初始 8096 的增长拷贝=内容缩放数据面（压缩载荷本身）；
   移除外层 BufferedOutputStream=逆转早期批次有意的 32KB 调用分块（吞吐回退风险）。

**结论：正式关闭。** 保存 IO 外壳族此后按维持代价面入档（与 PacketEncoder memo、
LongArrayTag bulk buf 同列）。

## 二、内存战役收敛终局声明（0.80.0 → 0.107.0）

### 轴 1：驻留轴（R5-R22，0.80.0→0.92.0）——已闭合

每区块 **189.5→101.4KB（−46.5%）**（同窗配对直对 −46.7%）。收割面：区块包缓存 TTL
驱逐（−9.0%）、Moonrise ShortList 惰性化（−9.7%）、NoiseChunk carvers 后释放（−6.9%）、
常量容器三层收割（ZeroBitStorage 共享/规范 Data/常量节规范化 −2.14%~-3.9%）、SWMR
uniform 紧凑表示（−1.96%）、shape 族规范共享两轮（−6.52%/−2.12%）、SWMR 冻结壳
（每区块 −80~82%）、结构模板驻留解耦（无玩家稳态 −28.4MB）、NoiseChunk 树停机释放、
blockEntities 懒分配。闭合证据：三轴收敛（时间/区块/玩家 R11-R12）+首尾同会话直对
（R14）+长时生长二次闭合（R22）+延迟守护（R15/R23 池化亚噪声）+位宽/状态级判负证据
（R13/R21）。变长 palette/光照两提案经用户裁定不做（R17，用户决策终局）。

### 轴 2：分配源轴（R24-R40，0.92.0→0.105.0）——已闭合

流送窗事件 **9,180→6,192（−32.6%）/按区块 −51.7%**，权重按区块 **−63.2%**，同窗区块
吞吐 **+39.7%**（分配收割最终度量），G1New 7→4，驻留按区块再 −10.3%。十六轮收割：
SurfaceRules memo、Node/BinaryHeap 清引用、盘载三面（countEntries/papoReadUtf/
PackedData 别名）、reencode 两阶段、Climate/Beardifier scratch、CubicSpline、
PlacementModifier 流契约接口级、谓词偏移 scratch、水体/气候/特征五面、Ap2 每深度槽栈、
矿石/模板机械三面。闭合证据：R40 收官直对（两系复现带互不重叠）+top-40 逐面审计
（全判例维持）。

### 轴 3：新窗口轮（R41-R42，0.105.0→0.107.0）——已闭合

- 驻留稳态 tick 窗（R41）：`StepBasedCollector` 空 addAll+迭代器（每移动段 29 件→0）；
  判例：JFR 自观测伪影剥离/空容器机械面通式/外离腿识别。
- 实体密集+保存窗（R42）：packed long[] 编码 NBT 快路（流面 35→0）；判例：同腿成像/
  单例 ops 恒等快路/同序复跑终审。
- 保存 IO 缓冲池化重开：本轮正式判负关闭（上文）。

**分配普查域终态：流送（R24-R40）/盘载（R28-R32）/驻留（R41）/实体+保存（R42）四域
全覆盖**，每域逐面判定入档，无空白域。

### 判例索引（核心可复用）

立项与判负：分配收割立项三门槛（R24）/判负面重开判据（R38）/代价面重开须先证净赢
（R36，本轮应用）/审计轮判负证据等级高于立项（R21）/轴闭合（R40）。
等价性：释放恒等三件套（R20）/单槽借还池通式+清引用字节码实证（R39）/每深度槽栈通式
（R38）/空容器机械面通式（R41）/单例 ops 恒等快路（R42）/所有权即线程 confinement
（R37）/结果对象 vs 查询载体判别式（R37）/流形态=隐藏拷贝（R31）。
测量与守护：三序守护+同序复跑终审（R23/R38）/第二腿劣势伪影（R37）/外离腿识别
（R41）/弧形延迟直对自缩放混杂（R40）/工作量缩放与机械面分开记账（R39）/计数与权重
双估计器（R24）/驻留窗观测者伪影剥离（R41）/同会话直对（R14）/census 机器无关口径
（R18）。
测试：套件外新测试=未执行（R35）/Proxy 单方法路由 fake（R36，R42 复用）/oracle 输入
先破平局（R33）/公式逐调用同形（R33）。

### 残余与余量（终局）

红线内无已识别未收割机械面。剩余收益仅可能来自：**wrap/mapAll 架构改写**（per-chunk
密度树重物化，超等价红线授权范围）、**RNG 族**（种子派生绝对红线）、**数据内容面**
（palette/光照/NBT 内容本身，R13/R17 用户裁定终局）、**上游族**（Moonrise/DFU/Netty/
spark）、**维持代价面**（PacketEncoder memo、LongArrayTag bulk buf、保存 IO 外壳——
本轮正式关闭）。红线两轴证据：内存轴四域闭合，延迟轴 R15-R42 逐批相邻守护全零回归。

版本保持 0.107.0。
