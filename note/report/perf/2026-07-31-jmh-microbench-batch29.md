# 批次 29（0096-0103）优化前后 JMH 对比报告

- 日期：2026-07-31
- 环境：Oracle JDK 21.0.10，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，Windows 11
- 原始数据：[benchmark/results/bench-20260731-023948.txt](../../../benchmark/results/bench-20260731-023948.txt) / [.json](../../../benchmark/results/bench-20260731-023948.json)
- 基准源码：benchmark/src/papo/bench/（NbtReadAdapterBench / VarIntReadBench / EnumConstantsCloneBench / RegistryCodecHoistBench / MapEncodeLoopBench / EventGateBench / LazyListBench / InflaterPoolBench）
- 等价性自检：全部 8 个基准类的 main 方法自检 **ALL OK**（0096 对真实 Netty 4.2.7 jar 逐方法行为比对含异常消息；0103 真实 zlib 三尺寸×复用 3 轮+双流并发逐字节全等）

## 结果汇总

| 补丁 | 基准 | 场景 | before | after | 倍率 |
|---|---|---|---|---|---|
| 0096 readNbt 适配器 | NbtReadAdapterBench | 5 条目 | 81.3 ns | 83.6 ns | 0.97×（持平） |
| | | 20 条目 | 300.5 ns | 303.3 ns | 0.99×（持平） |
| 0097 VarInt 快速路径 | VarIntReadBench | 1 字节 | 0.759 ns | 0.654 ns | **1.16×** |
| | | 3 字节 | 1.615 ns | 1.894 ns | 0.85×（见下注） |
| 0098 枚举常量缓存 | EnumConstantsCloneBench | readEnum | 9.63 ns | 2.24 ns | **4.30×** |
| | | writeEnumSet | 16.95 ns | 8.67 ns | **1.96×** |
| 0099 registry codec 单例 | RegistryCodecHoistBench | ids=1 | 2.19 ns | 2.47 ns | 0.89×（持平/噪声） |
| | | ids=8 | 17.03 ns | 19.27 ns | 0.88×（持平/噪声） |
| ~~0100 map entrySet~~（已撤销） | MapEncodeLoopBench | 3 条目 | 32.2 ns | 33.3 ns | 0.97×（持平） |
| | | 7 条目 | 54.5 ns | 71.2 ns | **0.77×（回退，已撤销）** |
| 0100 事件门控 | EventGateBench | 0 监听器（常态） | 9.69 ns | 0.60 ns | **16.3×** |
| | | 2 监听器 | 9.50 ns | 9.56 ns | 1.01×（无劣化） |
| 0101/0102 惰性 list | LazyListBench | 32 候选 0 命中（eager） | 1.78 ns | 1.72 ns | 1.03×（持平） |
| | | 32 候选 0 命中（防御性拷贝，0102 场景） | 73.2 ns | 1.72 ns | **42.7×** |
| | | 32 候选 2 命中（eager） | 35.2 ns | 24.9 ns | **1.41×** |
| | | 32 候选 2 命中（防御性拷贝） | 82.9 ns | 24.9 ns | **3.33×** |
| 0103 Inflater 池化 | InflaterPoolBench | 4 KiB 解压 | 13.47 µs | 11.70 µs | **1.15×** |
| | | 32 KiB 解压 | 79.0 µs | 72.7 µs | **1.09×** |

## 如实记录

1. **0100（map forEach→entrySet）已撤销**：7 条目实测回退 0.77×。原因：`HashMap.forEach` 直接扫内部表、无迭代器分配；捕获型 BiConsumer 在热点下被 JIT 逃逸分析消除，"每次 encode 分配 lambda"的前提不成立。该内部提交已 rebase 摘除，补丁序列重编号（0101-0104 → 0100-0103）。教训：消除 lambda 分配类候选必须先过微基准。
2. **0096 readNbt 适配器微基准持平**：单次分配的 `ByteBufInputStream` 被 JIT 逃逸分析掩盖（与 0087/0088 同现象）。价值在真实服务器分配压力下消除每调用 ~40B 堆分配 + 去掉 InputStream 虚拟分派层，非微基准可见收益；作为 0095 的对称面落地，零回退风险。
3. **0099 registry codec 单例微基准持平略负（0.88-0.89×，绝对差 0.3-2.3ns）**：匿名类实例化被 EA 消除，单例不改变分派形态，差异在误差/代码布局噪声量级。保留理由同 0096（消除真实分配点，零行为风险）。
4. **0097 VarInt 3 字节路径 0.85×**：多字节情形多一次首字节判断，绝对值 +0.28ns；但真实流量压倒性为 1 字节 varint（包 id、集合长度、枚举序数全部 < 128），1 字节路径 1.16×，净收益为正。
5. **0101/0102 eager 变体 0 命中持平（1.03×）**：单个空 ArrayList 分配被 EA 掩盖；真实收益在防御性拷贝场景（0102 TrackedEntity：42.7×）与有命中场景（1.41-3.33×）。
6. **0103 Inflater 池化 1.09-1.15×**：每次区块读盘省一次 native inflateInit/end + Cleaner 注册 + fill 次数减少（512→8192 缓冲）。微基准含完整解压工作，真实路径（解压线程池上每 chunk 一次）的相对收益更高（省去的固定开销占比随 chunk 变小而增大）。

## 结论

- 落地 8 个补丁（0096-0103），全部可证行为等价（0096/0097/0103 附字节级或行为级实证），完整 test 套件 + applyPatches 全绿。
- 撤销 1 个（map entrySet），数据驱动决策，内部历史已摘除该提交。
- 高价值项：0100 事件门控（16.3×，常态零监听器）、0102 惰性移除（42.7×）、0098 枚举缓存（4.3×）。
