# 批次 58 JMH 微基准报告（2026-08-20）— 网络带宽 + 出站编码延时

环境：JDK 21.0.10（Windows 11，**无 native libdeflate** → 压缩走 JavaVelocityCompressor/JDK zlib 回退；生产 Linux 为 libdeflate native，见逐项说明），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。netty 4.2.7.Final（与服务器同版本，benchmark/lib 本地 jar）。

批次构成：三路 survey（压缩管线 / 出站包量冗余 / flush 调度延时）交叉印证后落地 7 个补丁——记分板冗余广播门控 ×3（带宽）、出站编码链预分配 ×2（延时）、压缩输出上界分配 + setupCompression 正确性双修 ×2。

## 1. A1 — Varint21LengthFieldPrepender 精确预分配（EncodeAllocBench）

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_prependerDefault vs after_prependerExact | 1526.244 ± 1251.239 | 670.176 ± 4.904 | 均值 **2.28×** | before CI [275, 2777] 与 after [665, 675] 边缘重叠 |

- before：`ioBuffer()` 默认 256B + 首个 `ensureWritable(3+n)` 触发池化重分配（跳 2 的幂，超额分配）。
- after：`ioBuffer(3 + n)` 单次精确分配。
- **方差崩缩是本项主价值**：before 的 ±1251 是池化分配器 256→2^n 重分配的双峰（池命中快路 vs arena 慢路），after ±4.9——**每包成本从"双峰不确定"变为"确定性单次分配"**，对尾延迟（p99）的意义大于均值。
- 字节等价：帧内容（长度 varint + payload）逐字节一致（自检 main ALL OK）。
- 该 handler 每出站包必经（压缩与未压缩路径都是），是 tick 尾 writev 突发前的最后一跳。

## 2. A2 — PacketEncoder 按包类尺寸提示（EncodeAllocBench）

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_encoderGrow vs after_encoderHinted | 2329.463 ± 51.270 | 1591.822 ± 4.674 | **1.46×** | CI [2278, 2381] vs [1587, 1597] 不重叠 |

- before：每包从 256B 起步，codec 渐进写入（模型：32KB 按 1KB 分片）触发增长链 256→512→…→32768（8 次池化 reallocate，累计额外拷贝 ≈ 32KB）。区块包（压缩前 30-90KB）跑图/登录突发期每包如此。
- after：按包类缓存上次编码尺寸（≥8KB 才记录），首次分配到位。
- 机制：容量不上线、编码字节不变（自检 main ALL OK）；每连接每协议独立缓存（handler 单 event loop，无并发）。
- 复刻模型与真实差异：真实 codec 写入分片尺寸不一，增长次数 ±1-2；收益随包尺寸增大而放大（64KB 包链更长）。

## 3. D1 — CompressionEncoder 输出按 DEFLATE 实测上界分配（DeflateBoundBench）

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_sizePlusOne vs after_deflateBound | 1108435.700 ± 19844.298 | 1103931.222 ± 21944.241 | 1.004× | CI 重叠，持平（本机 JDK 回退路径，预期） |

**首版界公式被本基准自检证伪并据实修正（判例）**：

- 首版推导 n + n/4096 + 16（按"5B/65535B 存储块"理论最坏）——自检在 256KiB 随机数据上 **BOUND INSUFFICIENT**（实测膨胀 +86 > 界 +80）。
- **实测规律**（JDK zlib level 6，随机输入，64KiB/256KiB/1MiB/4MiB 四点）：膨胀 = **5B/16384B 窗口 + 6B**（zlib 头 2 + adler 4），即 `n + 5*ceil(n/16384) + 6`：
  - 64KiB → +26、256KiB → +86、1MiB → +326、4MiB → +1286，四点全吻合。
- 修正版界 `n + n/2048 + 32`（≥ 实测最坏 ~1.6× 裕量，同时覆盖 libdeflate 的更大块窗口）。
- 自检（修正后 ALL OK）：1KiB–1MiB 全尺寸 after 零扩容；before（n+1）**每个尺寸都触发扩容**（重试触发面实证）。

**收益定位（本机测不到的部分，如实注明）**：JDK 回退路径扩容是 `ensureWritable(8192)` 续压（不重压）→ 本机近中性。**native libdeflate 路径（生产 Linux）的收益是消除"容量翻倍 + 整个输入重压缩一遍"的尖峰**（LibdeflateVelocityCompressor.deflate 字节码实证 resize-retry 循环）——插件发送二进制/已压缩 payload（不可压）时每包 2× 压缩 CPU 的尾延迟尖峰，改后不可达（重试保留为兜底）。线上字节与帧格式完全不变。

## 4. C1-C3 — 记分板冗余广播门控（ScoreboardGateSelfCheck，行为自检非 JMH）

非性能基准——行为等价性自检（先例：FingerprintHardeningSelfCheck）。全场景 ALL OK：

- **C1 PlayerTeam**（9 个 setter）：等值新实例 → papo 0 广播 / vanilla 1（TAB 类插件周期重设 prefix/suffix 的常态）；真变更 → 两模型一致；**同实例重设 → 仍广播**（NMS MutableComponent 原位变异后靠重设刷新的手段保持 vanilla 奇偶性）；`unpackOptions` 默认 flags → 0 广播（加载期本就无接收者）。
- **C2 Objective**（4 setter + Scoreboard.numberFormatOverride）：等值 → 0 广播且 formattedDisplayName 不重算；真变更一致。
- **C3 ServerScoreboard.setDisplayObjective**：双发路径（旧 objective 仍显示于其他槽 且 新 objective 已追踪）恰好少一个**逐字节相同**的包；其余路径（未追踪 objective / 清槽 stopTracking / 同值重设刷新）包数与 vanilla 完全一致；末状态全场景一致。
- **带宽量级估算**：team 参数包（METHOD_CHANGE，displayName/flags/color/prefix/suffix/visibility/collision，NBT 编码约 50-150B + 帧头）× scoreboard 全体玩家；TAB 插件每 tick/每秒周期重设场景下，N 玩家服每轮省 N × 包大小 的出站流量。

## 5. D2/D3 — setupCompression 正确性双修（正确性，无 JMH）

- **D2 泄漏**：重复 `setupCompression` 时新建的 VelocityCompressor 被两个 setThreshold 双双拒收（decoder 只在自身 compressor 为 null 时收新，encoder 从不收新）→ 原生 deflate/inflate 上下文泄漏。修复：复用 decoder 现存实例（同包 package-private 访问器），不再创建孤儿。默认路径（每连接一次）行为不变。
- **D3 level clamp**：`misc.compression-level` 配 10-12 在 JDK 回退平台（无 native，如 Windows）原版 `new Deflater(10)` 抛 IAE → **登录即断连**。修复：create 抛 IAE 时以 clamp 到 [1,9] 的级别重试。合法配置（native 1-12 / 回退 1-9）行为逐字不变，只有原本崩溃的路径改变。

## 6. 等价性与红线

- 线上字节：A1/A2/D1 全部"仅容量/分配策略"，帧与包内容逐字节一致（各自检 main 实证）；C1-C3 消除的是**等值幂等重放包**，客户端终态一致（自检模型对比 vanilla/papo 全场景）。
- 红线保持：API 零变更；游戏版本 1.21.11 不变；默认行为兼容（D3 仅改崩溃路径）。
- 判例沉淀：**理论上界推导必须过实测校验**（D1 首版公式被 256KiB 实测点证伪）；**JMH 方差本身是信息**（A1 的 ±1251 双峰 → after 方差崩缩是尾延迟价值）。

## 验证链

compileJava（`--no-daemon`，三次含修正复验）BUILD SUCCESSFUL → 自检 main ×3 ALL OK → JMH 本报告 → rebuildPatches → applyPatches → 全量 test（见 optimizations.md 批次 58 记录）。
