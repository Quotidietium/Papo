# 批次 35 JMH 微基准对比报告（0125–0133 + 直接提交 0134）

- 日期：2026-07-31
- 运行器：`benchmark/run.sh "(ChunkSectionsOptional|DecorationsStream|DeflaterPool|MoveEventLocation|RegistryOpsCache|SensorTweaks)"`
- 原始数据：[benchmark/results/bench-20260731-215545.txt](../../../../benchmark/results/bench-20260731-215545.txt) / `.json`
- JMH 参数：`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt
- 全部基准类 `main()` 等价性自检：**ALL OK**（逐位/逐引用/逐分支比对）

## 测量结果

| 补丁 | 基准 | before | after | 倍率 | 结论 |
|---|---|---|---|---|---|
| 0127 sections Optional 消除 | ChunkSectionsOptionalBench（24 section 整区块读取） | 420.373 ± 17.150 ns/op | 359.483 ± 15.925 ns/op | **1.17×** | 应用 |
| 0128 装饰 rangeClosed 双循环 | DecorationsStreamBench（3×3 邻域收集） | 114.558 ± 2.575 ns/op | 74.290 ± 1.829 ns/op | **1.54×** | 应用 |
| — 装饰 holderSet for-each | DecorationsStreamBench（32 特性索引收集） | 249.738 ± 5.224 ns/op | 304.919 ± 12.793 ns/op | **0.82×** | **否决（未应用）** |
| 0129 DEFLATE Deflater 池化 | DeflaterPoolBench（64KB 单次压缩+close） | 1334.948 ± 56.177 µs/op | 1324.696 ± 32.079 µs/op | ≈1.01×（持平） | 应用（收益在分配消除，见下） |
| 0132 PlayerMoveEvent Location 延迟 | MoveEventLocationBench（被阈值过滤的移动包） | 10.933 ± 0.131 ns/op | 1.984 ± 0.025 ns/op | **5.51×** | 应用 |
| 0133 RegistryOps 缓存 | RegistryOpsCacheBench（每次编解码取 ops） | 4.950 ± 0.098 ns/op | 0.451 ± 0.013 ns/op | **10.97×** | 应用 |
| 0130 TemptingSensor 条件复用 | SensorTweaksBench（copy+range / tick） | 9.490 ± 0.575 ns/op | 8.144 ± 0.263 ns/op | **1.17×** | 应用 |
| 0131 PlayerSensor 属性外提 | SensorTweaksBench（20 玩家逐次取值） | 29.954 ± 0.862 ns/op | 8.833 ± 0.221 ns/op | **3.39×** | 应用 |

## 延迟否决记录（同 0100 先例）

- **ChunkGenerator :408 `holderSet.stream().map(Holder::value).forEach` → for-each**：实测 0.82× 回退
  （ArrayList spliterator 的索引循环 + 管道整体内联优于 Iterator 对象分配 + 逐元素虚调用）。
  遵循延迟否决规则未予应用，仅保留 (a) rangeClosed 双循环（1.54×）。测量代码存档于
  [DecorationsStreamBench.java](../../../../benchmark/src/papo/bench/DecorationsStreamBench.java)。

## 说明

- **0129 DeflaterPoolBench 数字接近持平是预期**：单次 64KB 压缩本身约 1.3 ms，新建 Deflater 的
  本地 zlib 状态分配 + Cleaner 注册 + `end()` 仅 ~10 µs 量级。本项收益是**每次区块保存消除一次
  本地内存分配/Cleaner 注册**（区域保存高频、多线程压缩执行器上按线程复用），外加内部缓冲
  512→8192 与外层 BufferedOutputStream 8192→32768 减少 native 调用次数；非单点吞吐提升。
  等价性已由自检覆盖：池化 `reset()` 复用产出与全新 Deflater **逐字节一致**。
- **事件门控类（0125 红石快路 / 0126 刷怪复用 / 直接提交 0134 BlockGrowEvent 门控）**沿用批次 29
  [EventGateBench](../../../../benchmark/src/papo/bench/EventGateBench.java) 模型（批次 34 实测：
  零监听器 17.0×、2 监听器 1.01× 无劣化），本批未重复测量。
- **0132** 的 5.51× 针对"被 1/256 阈值过滤的移动包"（占移动包绝大多数——该阈值正是为此而设）；
  阈值通过的包两路径成本相同（同样构造 2 个 Location 并派发事件）。
- **0133** 的 10.97× 为每次 `fromCodecWithRegistries` 编解码（聊天组件、实体元数据 Component、
  HoverEvent、成书页面等一切 Component 包）省去的 `RegistryOps + HolderLookupAdapter + CHM` 分配。

## 安全/等价论证索引（详见各补丁提交信息）

- 0127：`List.get(i)` 界内等价私有 `getNullable`；4 处 instanceof 三元逐引用自检
- 0128：3×3 闭区域坐标集合一致；`set` 内容序无关、下游 `toIntArray+sort` 输出确定
- 0129：显式 Deflater 构造 close 不 end（JDK usesDefaultDeflater=false）；`reset()` 字节级等价；
  池按压缩级别键控（配置重载级别变更时 end 旧实例，行为同前）
- 0130：`range()` 仅改 range 字段返回 this；每 tick 重设当前属性值 ⇒ 属性运行时变更即时生效
- 0131：单线程单次 doTick 内属性值恒定；无子类覆写（全库 grep 实证）
- 0132：非包值分量逐位复刻 `absSnapTo` 存储语义（x/z clamp ±3e7、yaw %360、pitch clamp±90 后 %360，
  归一化边界矩阵自检 ALL OK）；from/to 仅在阈值通过分支内使用
- 0133：`HolderLookupAdapter.lookups` 为 ConcurrentHashMap（源码实证）、NbtOps 无状态；
  非 ImmutableRegistryAccess 走原路径；服务端 `registryAccess()` 确为 ImmutableRegistryAccess 子类
