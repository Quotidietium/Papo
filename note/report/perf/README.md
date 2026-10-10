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

## 终局状态

- **内存轴**：0.80.0→0.90.0 ≈ 1,154.3→~670MB（链式 ≈−42%；每区块 189.5→110.3KB −41.8%）；
  同会话首尾直对 −284.7MB（−31.2% 保守界）/每区块 213.2→143.6KB（−32.7%）/
  边际 −43.3%。证据三层：逐轮配对差链 + 三轴收敛（区块/注册表 R11、时间 R12、
  位宽 R13）+ 首尾直对 R14。
- **延迟轴**：MsptLoadProbe 双序配对，槽位差 ≤0.2ms、池化 +1.0%（0.06ms）亚噪声、
  boot 带内、绝对 5-8ms@12 玩家流送距 50ms 预算一个量级（R15）。
- **残余判负**（红线内无下一角度）：方块 palette 全质量 4-bit 地板（103MB，
  变长编码踩 getBlockState 热路径）、非均匀光照 62MB（真实内容）、shape 族静态
  表残余 ~16-20MB（R16 后注册表固定量，Shapes 工厂拦截留档）、NBT/DFU（注册表
  内容）、无玩家底座 243MB（JVM/注册表固定）。
- **判例总集**：各批条目内联于 [optimizations.md](../../optimizations.md)
  （内存主线判例集中于批 141-155 条目）。

工具与数据：bot 浸泡套件（`benchmark/src/papo/bot/`：MemSoakVerify/OfflineJoinBot/
HeapRefAttr/ShellRefAttr/LongRunGrowth/MsptLoadProbe 等）+ 腿脚本
（`benchmark/memsoak-*.sh`、`msptload-r15legs.sh`）；原始数据不入库，留
`benchmark/results/`（含 B-088 转储 memsoak-1791582031387 / 1791591974980）。
