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

## 终局状态

- **内存轴**：0.80.0→0.90.0 ≈ 1,154.3→~670MB（链式 ≈−42%；每区块 189.5→110.3KB −41.8%）；
  同会话首尾直对 −284.7MB（−31.2% 保守界）/每区块 213.2→143.6KB（−32.7%）/
  边际 −43.3%。证据三层：逐轮配对差链 + 三轴收敛（区块/注册表 R11、时间 R12、
  位宽 R13）+ 首尾直对 R14。
- **延迟轴**：MsptLoadProbe 双序配对，槽位差 ≤0.2ms、池化 +1.0%（0.06ms）亚噪声、
  boot 带内、绝对 5-8ms@12 玩家流送距 50ms 预算一个量级（R15）。
- **残余判负**（红线内无下一角度）：方块 palette 全质量 4-bit 地板（103MB，
  变长编码踩 getBlockState 热路径）、非均匀光照 62MB（真实内容）、shape 族静态
  表残余 ~16-20MB（R16 后注册表固定量，Shapes 工厂拦截留档）、NBT/DFU（启动 schema
  常量+注册表内容，R28 盘载旧数据腿位相同直方图实测零增量驻留）、无玩家底座 243MB
  （JVM/注册表固定）。
- **判例总集**：各批条目内联于 [optimizations.md](../../optimizations.md)
  （内存主线判例集中于批 141-155 条目）。

工具与数据：bot 浸泡套件（`benchmark/src/papo/bot/`：MemSoakVerify/OfflineJoinBot/
HeapRefAttr/ShellRefAttr/LongRunGrowth/MsptLoadProbe 等）+ 腿脚本
（`benchmark/memsoak-*.sh`、`msptload-r15legs.sh`）；原始数据不入库，留
`benchmark/results/`（含 B-088 转储 memsoak-1791582031387 / 1791591974980）。
