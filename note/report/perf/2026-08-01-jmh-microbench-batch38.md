# 批次 38 JMH 微基准报告（2026-08-01）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260801-021402.json`（0156/0158）、`benchmark/results/bench-20260801-022026.json`（0155/0157，复刻修正后复测）。
基准均为**语义复刻**（不依赖 Minecraft 运行时），每类 `main()` 自检等价性（全部 ALL OK）。
结论速览：**2 项正收益（1.15×/1.69×，CI 不重叠），2 项复刻内中性保留（0155 小幅正向、0157 分配消除被 EA 抹除，均载明机制依据）**。

| 补丁 | 基准方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|---|
| 0155 Varint21FrameDecoder 内联解析 | VarintFrameBench 1 字节前缀 | 5.346 | 5.075 | 1.05×（CI 重叠） | 复刻用堆数组模拟 helper，**未计入**真实路径直接缓冲区（direct ByteBuf）逐字节带界检查写/读与 clear() 的开销；批次 29 survey 定级"低价值"与实测一致。96B/300B 负载的 arraycopy 主导两侧耗时 |
| 0155 同上 | VarintFrameBench 3 字节前缀 | 14.364 | 13.877 | 1.04×（CI 重叠） | 同上；前缀处理占比随负载增大进一步摊薄 |
| 0156 EntitySelector.getPredicate allOf 缓存 | SelectorPredicateCacheBench | 16.434 | 14.309 | **1.15×** | CI [15.85,17.02] vs [13.38,15.24] 不重叠。3 谓词场景每次命令执行省 1 个捕获 lambda；循环命令方块高频路径 |
| 0157 addDecoration 比较优先 | MapDecorationBench steadyTick | 3.130 | 3.101 | 持平（CI 重叠） | **EA 抹除警示**：`-prof gc` 两侧 gc.alloc.rate.norm ≈ 10⁻⁵ B/op——复刻中 before 的 Pair/位置记录/MapDecoration 全被标量替换（put 在深内联后被证明可消除），故复刻无法测量分配差。真实代码调用栈深、内联预算有限，稳态每携带者每 tick 省 3 处分配（Pair+Location+Decoration）为机制性收益；时间持平非回归，按 0140 先例保留 |
| 0158 tickCarriedBy 谓词内联 | MapMatcherBench | 54.113 | 32.062 | **1.69×** | CI [52.36,55.86] vs [31.45,32.68] 不重叠。每次 tickCarriedBy 省捕获 lambda + Predicate 间接分派（37 槽扫描场景） |

## 复测裁决记录

- **VarintFrameBench 初版复刻失真**：初版 before 路径每次解码 `Arrays.copyOf` 新建 helper 副本（实测 15.67/22.41 ns），但真实 helperBuf 是每解码器持久的 direct 缓冲（clear 仅复位索引）。修正为持久数组复刻后复测得 5.35/14.36 ns（本表）。初版数据（021402 中 VarintFrame 部分）作废，以 022026 为准。
- **MapDecorationBench 初版自检失败**：复刻两 MapData 实例各自持有 `new Object()` 充当 MapDecorationTypes.PLAYER，而真实代码为注册表全局 Holder 单例——跨实例 record equals 恒 false。改为共享静态单例后自检 ALL OK（此修正同时提升复刻忠实度：真实 Holder 即全局单例）。
- 两轮 MapDecoration 测量（021402: 3.201/3.249；022026: 3.130/3.101）均 CI 重叠持平，结论稳定。

## 等价性支点（源码实证）

- **0155**：内联累积 `i |= (b & 127) << n*7` 与 VarInt.read 对 1-3 字节序列逐位一致（0097 剥离分支：`first >= 0` ⇔ 无延续位，返回值 `first == first & 127`）；读者索引结局逐路径相同（前缀不全/负载不足 → reset；超宽/零长 → 消耗前缀后抛异常不 reset）；`monitor.onReceive(i + VarInt.getByteSize(i))` 逐字保留；移除的 `handlerRemoved0` 覆写仅释放 helperBuf（Netty final handlerRemoved 自释 cumulation，super 无需调用）。
- **0156**：`contextFreePredicates` 由 EntitySelectorParser.getSelector 以 `List.copyOf` 构造（不可变快照，构造后无写入路径），`Util.allOf` 为其纯函数；缓存仅影响 i==0 分支（无 features/box/range）；size 0/1 原本即免分配，size≥2 谓词无状态可重入。
- **0157**：目标字段计算与 calculateDecorationLocationAndType/playerDecorationTypeAndRotation 逐公式一致（clampMapCoordinate/isInsideMap/calculateRotation/decorationTypeForPlayerOutsideMap 原样复用）；逐字段比较镜像 MapDecoration record equals（新值为接收者，Holder/Optional equals 语义相同——Holder.Direct/Reference 均未覆写 Object.equals）；相等时跳过 put：LinkedHashMap 已存在键 re-put 不改变迭代序，全部消费方（CraftMapRenderer/PaperMapDecorations/ClientboundMapItemDataPacket）按值读取，实例身份无 API 可观察路径；trackedDecorationCount 增减与标脏条件逐分支对齐（含 Paper "仅真实移除才标脏"语义，基准复刻同）。
- **0158**：逐项测试为 mapMatcher 函数体逐字内联（引用相等短路 → `is(item)` → MAP_ID equals 的顺序与短路语义一致），迭代为同一 Inventory 迭代器同序扫描；MAP_ID 可为 null（Objects.equals 语义不变）。

## 暂缓（批次 38 重勘察结论）

- **ServerEntity 增量合并（net#2）**：重勘察完成。sendChanges 主路径已经 Paper 多轮优化（Vec3 展开、passengers 持久 ImmutableList、hurtMarked 门控 0100）；sendPairingData 的 ArrayList/Pair/copy 均为协议负载必需分配；`sendDirtyEntityData` 内 `getNonDefaultValues()` 全量重扫改惰性（配对时重算）会使配对包内容在脏窗口内不同于原版快照时点——数据包内容属协议可观察行为，不满足可证等价；SynchedEntityData 本身已数组化+惰性。无可证等价候选，正式结案不再重勘。
