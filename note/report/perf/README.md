# 性能报告索引（note/report/perf/）

本目录是 Papo 全部性能量化报告。三大优化主题的逐批全日志（含等价性论证与判例）
在 [optimizations.md](../../optimizations.md)；此处按时代索引，重点是内存主线（2026-10）。

## 时代索引

| 时代 | 日期 | 批次 | 主题 | 报告 |
|---|---|---|---|---|
| R1 多核调度 | 2026-08-26~27 | 78-96 | 池 sizing/存档 IO 下放/事件驱动 join | `2026-08-26-*`/`2026-08-27-*` |
| R4 红石 VANILLA | 2026-09-05~07 | 123-131 | 红石评估级联 | `2026-09-0*` |
| JMH 微基准 | 2026-07-30~08-22 | 23-77 | 分配/编解码/事件门控微基准 | `*-jmh-microbench-*` |
| **内存主线 R5-R15** | 2026-10-09~10 | 141-155 | 多玩家多区块加载下最低内存 | 见下表 |

## 内存主线（R5-R15，0.80.0 → 0.88.0）

场景：12 bot 行走扩散+驻留浸泡（spectator、view-distance 10、seed papo90），GC×2 后
jcmd heap_info 活集；配对纪律=同会话相邻二进制、LevelChunk ±1% 门、零错误+exit 0。

| 轮 | 批 | 版本 | 角度 | 头号数字 | 报告 |
|---|---|---|---|---|---|
| R5-1 | 141 | →0.81.0 | 区块包缓存 TTL 驱逐 | −9.0% | [memsoak-r5-batch141](2026-10-09-memsoak-r5-batch141.md) |
| R5-2 | 142 | →0.82.0 | Moonrise ShortList 惰性化 | −9.7% | [memsoak-r5-batch142](2026-10-09-memsoak-r5-batch142.md) |
| R5-3 | 143 | →0.83.0 | NoiseChunk carvers 后释放 | −6.9% | [memsoak-r5-batch143](2026-10-09-memsoak-r5-batch143.md) |
| R5-4 | 144 | 0.83.0 | HPROF 持有链归因（工具轮） | 归因基础设施 | [memsoak-r5-batch144](2026-10-09-memsoak-r5-batch144.md) |
| R6-1 | 145 | →0.84.0 | 常量容器载荷消除 | −0.81% | [memsoak-r6-batch145](2026-10-10-memsoak-r6-batch145.md) |
| R6-2 | 146 | 0.84.0 | String 反序列化规范化（负结果回退） | 角度关闭 | [memsoak-r6-batch146](2026-10-10-memsoak-r6-batch146.md) |
| R7 | 147 | →0.85.0 | 单值 PalettedContainer 规范 Data 共享 | −3.90% | [memsoak-r7-batch147](2026-10-10-memsoak-r7-batch147.md) |
| R8 | 148 | →0.86.0 | SWMRNibbleArray 紧凑 uniform 表示 | −21.1MB（区块调整） | [memsoak-r8-batch148](2026-10-10-memsoak-r8-batch148.md) |
| R9 | 149 | →0.87.0 | 常量节规范化共享（六件套归零） | −16.7MB | [memsoak-r9-batch149](2026-10-10-memsoak-r9-batch149.md) |
| R10 | 150 | →0.88.0 | 结构模板驻留解耦（TTL+惰性取） | −18.1MB 转储对 / −28.4MB 稳态 | [memsoak-r10-batch150](2026-10-10-memsoak-r10-batch150.md) |
| R11 | 151 | 0.88.0 | 残余画像常驻性归因+收敛论证（区块/注册表轴） | 全簇判负+归因 | [memsoak-r11-…](2026-10-10-memsoak-r11-batch151-residual-convergence.md) |
| R12 | 152 | 0.88.0 | 长时生长审计（时间轴闭合） | ±1.25%/±1.9% 持平 | [longrun-growth-r12-…](2026-10-10-longrun-growth-r12-batch152.md) |
| R13 | 153 | 0.88.0 | palette 判负勘误+位宽实证（R11 §5） | 4-bit 地板实证 | 同 R11 报告 |
| R14 | 154 | 0.88.0 | 首尾同会话直对（收官测量） | −31.2% 保守 / 每区块 −32.7% | [campaign-final-paired](2026-10-10-memsoak-campaign-final-paired.md) |
| R15 | 155 | 0.88.0 | tick 性能与交互延迟守护（CPU 面终验） | 槽位配对≈零差 | [mspt-load-guard-r15-…](2026-10-10-mspt-load-guard-r15-batch155.md) |
| R16 | 156 | →0.89.0 | shape 族规范共享（"有界≠不可约"证伪） | −40.1MB（−6.52% 保守） | [shape-intern-r16-…](2026-10-10-shape-intern-r16-batch156.md) |
| R17 | 157 | →0.90.0 | 静态形状表收割（getShapeForEachState 咽喉点） | −14.2MB（−2.12%，族累计 −94%） | [shape-static-tables-r17-…](2026-10-10-shape-static-tables-r17-batch157.md) |
| R18 | 158 | →0.91.0 | SWMR 冻结壳规范共享（3 单例+COW+定居交换+sweep） | SWMR 类 −10.8MB（−81%，三对 census 复验）；每区块全堆 −3.0%（中位） | [swmr-shared-shells-r18-…](2026-10-10-swmr-shared-shells-r18-batch158.md) |
| R19 | 159 | 0.91.0 | 残余收敛审计+主线收官直对（0.80 vs 0.91） | 正面判负（内容主导）；每区块 189.3→121.3KB=**−35.9%**（同会话直对） | [residual-audit-r19-…](2026-10-10-residual-audit-r19-batch159.md) |
| R20 | 160/161 | →0.92.0 | R19 判定对抗复核+两残余面收割（NoiseChunk 停机释放+blockEntities 懒分配） | 同窗每区块 190.4→101.4KB=**−46.7%**；census：[[D/NoiseChunk 归零、[D −96.5%、O2O 空图 −13,316（Imposter→0） | [residual-harvest-r20-…](2026-10-11-residual-harvest-r20-batch160-161.md) |
| R21 | 162 | 0.92.0 | 0.92.0 基线残余归因审计（三面全判负，状态级/公式级证据升级） | O2O 空率 0.6% 实测；[J 构成 85.1%+残余闭合，SBS ceil 公式恒等（320 档缺席）；逐区块差分 31 类全负向 | [residual-attribution-r21](2026-10-11-residual-attribution-r21.md) |
| R22 | 163 | 0.92.0 | 0.92.0 长时生长复验（R12 方法论）+转码视图对抗复核 | 活集同相位 ±0.4-0.7%（优于 R12）；批158-161 机制周期负载零回归；Dynamic/NBTMapType=静态扁平化表（双重定位+十采样逐位恒定） | [longrun-r22](2026-10-12-longrun-r22-batch163.md) |
| R23 | 164 | 0.92.0 | 0.92.0 延迟守护复验（批158-161 机制 MSPT 面终验，红线两轴双闭合） | 六腿三序（含同序第三对）：池化 +0.8% 亚噪声、run2/run3 B≤A、boot 同带 | [mspt-guard-r23](2026-10-12-mspt-guard-r23-batch164.md) |
| R24 | 165 | 0.93.0 | 堆外面归因（判负：类/JIT/线程/Netty 无可收敛驻留）+分配源归因（JFR 计数口径）与收割 | SurfaceRules updateY memoize+SequenceRule 迭代器（22.8% trips）→0：总 trips −12.2%、估重净化 −35%、密度树驻留逐位恒等、旧代 −0.13%；巨权伪影判例（计数为准） | [allocsource-r24](2026-10-12-allocsource-r24-batch165.md) |
| R25 | 166 | 0.94.0 | 实体密集面画像（唯一未演习轴；spectator 触发刷怪实证）+寻路滞留收割 | BinaryHeap.clear 不清引用：Node −38%（26,683→16,476，残差=Path 自有）；实体图 0.75% 堆全表语义判负 | [entityface-r25](2026-10-12-entityface-r25-batch166.md) |
| R26 | 167 | 0.94.0 | "clear 只置零"反例族全仓清扫（R25 判例落地） | 判负：7 位点 6 处已正确清引用，唯一纯置零=原生 long[] 无害；族穷尽（三例已收 160/165/166） | [clearfamily-sweep-r26](2026-10-12-clearfamily-sweep-r26-batch167.md) |
| R27 | 168 | 0.94.0 | 高并发玩家面画像（60 bot；harness commonPool 裂批缺陷修复） | 判负：状态本体 45KB/玩家 vs 边际 6.9MB=99% 内容；[LServerPlayer; 归因 NearbyPlayers 内容缩放；在线数验收判例 | [playerface-r27](2026-10-12-playerface-r27-batch168.md) |
| R28 | 169 | 0.94.0 | DFU 盘载旧数据转码面（RegionDowngrade 10,296 块降 3465 + DfuSoakCensus 12 bot + JFR） | 判负：templates ≈151k 簇与未执行基线位相同=启动常量，零增量驻留；DFU 归因分配 66MB/1.7% 全瞬时；重写触旧世界兼容红线；NBT 宽度表勘误判例 | [dfuface-r28](2026-10-12-dfuface-r28-batch169.md) |
| R29 | 170 | →0.95.0 | 盘载反序列化分配面收割（R28 JFR 归因→物化-需求差） | recalcBlockCounts 计数重写：countEntries 坐标列表物化 366 trips/815.8MB（窗 21.2%）→**0**（新面 6.1MB）；两遍 int[] 计数+仅 ticking 态延迟收集；恒等单测对拍旧 oracle；驻留零变化 | [blockcount-r29](2026-10-12-blockcount-r29-batch170.md) |
| R30 | 171 | →0.96.0 | NBT 读取通路（残余簇归因：copy=Moonrise 防御拷贝判负/O2O=流式判负/unpack<阈判负） | papoReadUtf 零分配重写：TL scratch+≤48B 有界去重（同一不可变 String），面 94 trips/83.5MB→**2 trips/2.0MB（−97.9%）**；驻留 String −5.4k/[B −8.1MB；判例：读侧物化三重奏/不可变值共享零语义面/有界缓存三性质 | [nbtread-r30](2026-10-12-nbtread-r30-batch171.md) |
| R31 | 172 | →0.97.0 | 盘载窗第三面+红线轴闭环（批170/171 MSPT 守护双序 B≤A：主序 −18%/倒序 −7%） | PackedData storage long[] 化+NBT 别名直取（unpack toArray 重复物化）：unpack 框内 51/109.7MB→**14/11.8MB（−89%）**；assertSame 别名铁证单测；LongArrayTag 零滞留；判例：流形态=隐藏拷贝/别名三问/守护腿串行纪律 | [packeddata-r31](2026-10-12-packeddata-r31-batch172.md) |
| R32 | 173 | →0.98.0 | 批172 MSPT 守护（三对符号乱翻=噪声地板，机械不在 tick 路径零回归）+稳态普查复验→新晋面收割 | palette 重编码 int[4096] 消面：两阶段保序（升序填充 id 分配=线字节恒等+序无关打包），面 316 trips/429.1MB→**0/0**；两次自查纠偏（空 palette 崩/降序访格 id 重分配）；判例：线格式是 id 分配序的函数/急切求值隐藏依赖/符号翻转判别式 | [reencode-r32](2026-10-12-reencode-r32-batch173.md) |
| R33 | 174 | →0.99.0 | 0.98.0 普查残余帧级归因（立项 2 判负 4）→双小面收割 | Climate search TL scratch（只读输入无重入）+ Beardifier 索引循环：**165→0 + 228→0 双面消灭**；判负维持：StructureTemplate 族/OreFeature/散布 BlockPos/NoiseChunk wrap-mapAll；判例：assertSame 级 oracle 输入须破平局/公式复刻逐调用同形（pow(E,·)≠exp 位级）/双 switch 臂错位逐臂断言 | [climatescratch-beardifier-r33](2026-10-12-climatescratch-beardifier-r33-batch174.md) |
| R34 | 175 | →0.100.0 | 批174 三序守护（主 3/6·倒 4/6·钉 5/6 含 dwell 全优，槽3 同号无通道=噪声）+残余归因→流形态消面 | CubicSpline.Multipoint 簇 ≈459 trips→**0**（mapAll 流机械+create 迭代器；替代仅 47 ArrayList）；判例：同号但幅度<腿内摆幅且无机械通道=仍噪声/代表栈外推=截断陷阱（in-stack 是下界）/PlacementModifier 流契约族新判负 | [splinemapall-r34](2026-10-12-splinemapall-r34-batch175.md) |
| R35 | 176 | →0.101.0 | 批175 三序守护（无三序同号劣化槽，R34 槽3 跨轮反号=噪声佐证）+流契约族接口级收割 | PlacementModifier.forEachPosition 双路（默认桥接插件兼容+11 override 收 3 组）+DFS 走查驱动：placement 流机械 ≈370 trips→**0**（Head 235→0/StreamBuilder 74→0），替代仅 15 walk；判例：流契约零分配三件套/多重发射随机时序不变量（全采样先于首发射）/套件外新测试=未执行 | [placementwalk-r35](2026-10-12-placementwalk-r35-batch176.md) |
| R36 | 177 | →0.102.0 | 批176 三序守护（主 3/6·倒 6/6 全优·钉 4/6，无三序同号劣化；A-100r 会话中断按 gate 行核对补跑）+谓词/矿物双面收割 | blockpredicates 偏移族 134→**0**（PapoPredicateScratch ThreadLocal+查询坐标只读恒等）+OreFeature λ 146→**4**（访问器提升出循环巢）；immutable 总量 162→14，无 ≥20 新面；判例：既优化的代价面判负（memo 快照 179）/Paper 接口 fake 用 Proxy 单方法路由/守护腿 gate 行核对补跑 | [predicateoffset-r36](2026-10-12-predicateoffset-r36-batch177.md) |
| R37 | 178 | →0.103.0 | 批177 三序守护（主 2/6·倒 5/6 零劣化·钉 0/6=第二腿劣势伪影，换序即反转；无三序同号劣化槽）+水体/气候/特征五面收割 | Aquifer MutableDouble 121→**0**（所有权即线程 confinement：实例字段化）/SinglePointContext 77+33→**0**（PapoContextScratch 线程局替身）/Geode ListItr 110→**0**（ArrayList+索引循环）/MonsterRoom 58→**0**+PapoWire 27→**0**（自有代价面 ThreadLocal 化）；判例：所有权即线程 confinement/结果对象 vs 查询载体判别式/第二腿劣势伪影/Paper MutableBlockPos 防御性覆写 | [aquifercontext-r37](2026-10-12-aquifercontext-r37-batch178.md) |
| R38 | 179 | →0.104.0 | 批178 三序守护+钉死同序复跑（主 **6/6**·倒 5/6·钉1 0/6 同序复跑不重现=整机漂移瞬态·钉2 **6/6**；无两对以上同号劣化槽→零回归且方向性改善）+Ap2(ADD) fillArray 组合温度数组收割（判负面重开判例） | `Ap2.fillArray←selectCellYZ` **1,347→0**（filtered 1,405.9MB/60s=榜首面大于其余机械面之和）；替代面 PapoAddTempStack **0**；事件 −12.4%/raw 权重 −14.6%；驻留按区块 +0.4% 噪声内；判例：判负面重开判据（新机制通道+恒等升级+面占比）/每深度槽栈=嵌套自递归复用通式/同序复跑不重现=瞬态/allocsum.py 聚合器入库 | [ap2tempstack-r38](2026-10-12-ap2tempstack-r38-batch179.md) |
| R39 | 180 | →0.105.0 | 批179 三序守护（倒序对=杀任务污染窗 B 1/6 降权·干净主序 3/6（槽3=A 腿 72ms 尖峰）·钉死 **5/6**；无两对以上同号劣化槽、无 tick 通道→零回归；B 同窗多载 35% 区块=吞吐提升佐证）+矿石/模板放置机械面三面收割 | `Long2ObjectOpenHashMap←BulkSectionAccess` **37→0**（单槽借还池：唯一使用者+try-with-resources=活深度恒 1；fastutil clear 清引用字节码实证；双 close 守卫）+`forArrayWithPosition←RuleProcessor` **37→0**（索引循环；RNG 构造不动）+processBlockInfos 迭代器（阈下同族机械完成）；替代面 0；驻留按区块 114.7→104.2KB（−9%）；判例：单槽借还池通式/清引用先实证字节码/污染窗判读/工作量缩放面与机械面分开记账 | [sectionpool-r39](2026-10-12-sectionpool-r39-batch180.md) |
| R40 | 181 | 0.105.0 | 分配源战役收官直对+轴闭合审计（R24 开轴 16 轮收割后红线内机械面穷尽） | 同会话首尾直对（0.92→0.105）：事件 −32.6%/**按区块 −51.7%**、权重 −48.5%/**按区块 −63.2%**、同窗区块 **+39.7%（吞吐）**、G1New 7→4、驻留按区块 −10.3%；top-40 逐面全判例维持；弧形 MSPT 6/6 劣=自缩放负载混杂判例（延迟轴有效仪器=相邻守护）；判例：两系复现带互证/吞吐是分配收割最终度量 | [allocarc-final-r40](2026-10-12-allocarc-final-r40-batch181.md) |
| R41 | 182 | →0.106.0 | **驻留稳态 tick 窗**分配普查（空白窗口）→收集器双机械面收割 | 发现腿 1,432 事件：spark 后台采样器 44.6% 上游判负+**JFR 自观测伪影 6.4% 判例（networkStatFor 每包 toString 链须剥离）**+实体面全 <阈；收割 `StepBasedCollector.flushStep` 空 addAll（addAll 无条件 toArray=**每移动段 28 空数组**，34 trips）+applyAndClear 索引循环（同族残余 8 trips 同轮完成）；配对腿 **30→0·8→0** 替代面 0；MSPT 三序零回归（唯一同号槽=外离腿判例：单腿整腿偏移驱动，合池中位差 0.05ms）；判例：空容器机械面通式/驻留窗观测者伪影剥离/同族不留半脸 | [dwellwindow-r41](2026-10-12-dwellwindow-r41-batch182.md) |
| R42 | 183 | →0.107.0 | **实体密集+保存窗**分配普查（同腿成像：驻留 150-210s 窗天然含自动保存）→packed long[] 编码快路 | 发现腿 1,416 事件：NBT 内容/DFU/Moonrise 写完成深拷贝（R30 留观察项收束）/节快照/RegionFile IO/代价面全判负；收割 `PAP0_LONG_ARRAY.encode` 流往返（createLongList=stream.toArray 纯机械，R31 同族）→`ops==NbtOps.INSTANCE`（私有构造严格单例）直连 `new LongArrayTag(input.clone())`；配对腿 **35→0**；MSPT 唯一三序同号槽 s4 同序复跑 0.0 不复现=瞬态（R38 判例三度应用）；判例：保存窗实体窗同腿成像/单例 ops 恒等快路（clone 证明强于所有权转移）/同序复跑是同号槽终审 | [entitywindow-r42](2026-10-12-entitywindow-r42-batch183.md) |
| R43 | 184 | 0.107.0 | 保存 IO 缓冲池化**重开评估判负关闭**（R36 判例应用）+**内存战役收敛终局声明** | 源码核实既有边界（Deflater/Inflater 已单槽池化+缓冲分块已论证）；配对定量 25/25·19/19·16/17≈60 trips<门槛一个数量级+IO 线程无 MSPT 通道；**恒等可证性失败**（可重定向池化壳依赖 JDK 未文档化内部状态=稳定性红线）；**四支柱正式关闭**；终局声明：驻留轴 −46.5%/区块+分配源轴 −51.7% 按区块+四域普查全覆盖+判例索引四组+终局余量（wrap-mapAll 超授权/RNG 绝对红线/数据面用户裁定/上游/维持代价面） | [saveio-closure-r43](2026-10-12-saveio-closure-r43-batch184.md) |
| R44 | 185 | 0.107.0 | **终局二进制驻留轴四重闭合复验**+网络 λ 面判负维持 | RegistryFriendlyByteBuf 逐包包装（mapStream 适配器，两窗 ≥20）判负：源 buf 每包不同不可缓存+~80B/包 trivial+final 委托链手术=红线；LongRunGrowth 12×40min 十样本门绿：同相位无趋势+boot 常量表与 R22 逐位相同=无泄漏；同会话配对 0.105vs0.107：原始 +4.8% 全为工作量缩放（B 多载 6.2% 节）、**按节归一 −1.3%**、容器结构恒等→R41/R42 零驻留回归；**终局二进制驻留轴闭合成立**（R43 终局声明最后支柱）；判例：会话恢复杀腿无 gate 行=整腿重跑不缝合/终产物复验是闭合必要支柱 | [finalverify-r44](2026-10-12-finalverify-r44-batch185.md) |
| R45 | 186 | →0.108.0 | **BE 密集内容轴**普查（自然地形普查对此面零覆盖）→空容器 BE 驻留四件套收割 | PapoLazyItemsList（11 容器 BE 19 构造点：空态 32 档共享只读视图零 per-BE 分配、物化与 vanilla 同底、UOE 位次经共享视图保持）+PDC map/view 懒化+炉 quickCheck 懒化；配对腿（59,400 BE 组成恒等）NonNullList 60,220→183·HashMap −80,562·堆 −9,994KB；MSPT 三序零回归；判例：急初始化 final 字段懒化边界=收内部急件/别名构造观察等价/腿验收必须含 bot 在线正证据 | [becontent-r45](2026-10-12-becontent-r45-batch186.md) |
| R46 | 187 | →0.109.0 | **机械负载 tick 窗**分配普查（漏斗环+观察者时钟从未成像）→漏斗空实体查询快路 | 保守空探针四处：section 粒度谓词（chunk 计数版被同列共置击穿后升级）+EntityLookup 走架同构+Level 透传+漏斗双守卫+共享 suck 查询盒；发现面 21+12 trips（~172,800 空查询/60s 窗）；配对腿（组成恒等 576 漏斗）**双面全 0 零替代面**；MSPT 三序+第五对终审零回归（s4 三序同号+1.4 复现后 −0.1 破号=相变槽漂移，无机制通道）；判例：空探针粒度=真查询读取粒度/mock NMS 单测自带 bootstrap/applyPatches 后出包新鲜度实证/同序复跑复现≠定谳 | [mechload-r46](2026-10-12-mechload-r46-batch187.md) |

## 终局状态

- **内存战役收敛终局（R43 声明，0.80.0→0.107.0；终局后延伸轮 R45-R46 至 0.109.0）**：驻留轴
  （R5-R22）每区块 189.5→101.4KB（**−46.5%**，三轴收敛+同会话直对+长时生长+延迟守护四重闭合）；
  分配源轴（R24-R40）流送窗事件 **−32.6%/按区块 −51.7%**、权重按区块 **−63.2%**、同窗吞吐
  **+39.7%**、G1New 7→4、驻留按区块再 −10.3%；新窗口轮（R41-R42）驻留稳态 tick 窗与
  实体+保存窗收割；终局后延伸轮：R45 BE 密集内容轴（空容器 BE 驻留四件套）、
  R46 机械负载 tick 窗（漏斗空实体查询快路）。**分配普查五域全覆盖**
  （流送/盘载/驻留/实体+保存/机械负载）。
- **延迟轴**：R15-R46 逐批相邻版本三序守护**全零回归**（判读判例集：第二腿伪影/外离腿/
  同序复跑终审/自缩放混杂/相变槽漂移第五对终审）。
- **残余判负**（红线内无已识别未收割机械面，R43 终局余量声明）：wrap/mapAll 架构改写
  （per-chunk 密度树重物化，超等价红线授权）；RNG 族（种子派生绝对红线）；数据内容面
  （palette 4-bit 地板 103MB 变长踩 getBlockState 热路径、非均匀光照 62MB 真实内容、
  变长两提案用户裁定不做 R17）；上游族（Moonrise/DFU/Netty/spark）；维持代价面
  （PacketEncoder memo、LongArrayTag bulk buf、保存 IO 外壳 R43 正式关闭）；shape 静态
  表残余 ~16-20MB（注册表固定量）；无玩家底座 243MB（JVM/注册表固定）。
- **判例总集**：各批条目内联于 [optimizations.md](../../optimizations.md)；分组索引见
  [R43 终局声明](2026-10-12-saveio-closure-r43-batch184.md)。

工具与数据：bot 浸泡套件（`benchmark/src/papo/bot/`：MemSoakVerify/OfflineJoinBot/
HeapRefAttr/ShellRefAttr/LongRunGrowth/MsptLoadProbe 等）+ 腿脚本
（`benchmark/memsoak-*.sh`、`msptload-r15legs.sh`）；原始数据不入库，留
`benchmark/results/`（含 B-088 转储 memsoak-1791582031387 / 1791591974980）。
