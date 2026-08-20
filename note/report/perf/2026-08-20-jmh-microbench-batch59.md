# 批次 59 JMH 微基准报告（2026-08-20）— 零拷贝出站帧化

环境：JDK 21.0.10（Windows 11，无 native libdeflate → 压缩走 JDK 回退；生产 Linux 为 libdeflate），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。netty 4.2.7（buffer/common/transport/codec-base，与服务器同版本）。

## 机制

出站三阶段（PacketEncoder → CompressionEncoder → prepender）原版每包全量拷贝：低于阈值（threshold=256，**绝大多数游戏包**：实体移动/音效/心跳/交互）双拷贝双分配；高于阈值（区块/大包）单拷贝单分配。0217 以 6 字节 headroom 前缀 + channel attr 身份标记直通消除之：

| 路径 | before | after |
|---|---|---|
| 低于阈值（100B 包） | encoder 分配 A → 压缩拷入 B（varint0+载荷）→ 帧拷入 C（帧长+载荷）＝ **2 拷贝 + 3 池化分配** | headroom 分配 A → 原地写 1 字节 varint0 + readerIndex 回拨 → 帧长回填 headroom ＝ **0 拷贝 + 1 池化分配** |
| 高于阈值（16KB 随机） | 压缩产出 B → 帧拷入 C ＝ 1 拷贝 + 1 分配 | 压缩产出带 headroom 的 B，直通 ＝ 0 拷贝（压缩自身的输出写是语义必需） |

## 结果（OutboundFrameBench，EmbeddedChannel 真实 netty write 管线）

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| belowThreshold（100B） | 469.691 ± 5.081 | 285.698 ± 10.349 | **1.64×** | CI [464.6, 474.8] vs [275.4, 296.0] 不重叠。每包省 2 次全量拷贝 + 2 次池化分配 |
| aboveThreshold（16KB 随机） | 124554.077 ± 22372.557 | 116408.761 ± 2437.130 | 1.07×（CI 重叠） | 压缩本身 ~116µs 主导且方差大；省下的 16KB 拷贝+分配（µs 级）在噪声内。均值正向，如实记录 |

- belowThreshold 是本补丁主战场：默认 threshold=256 下，实体移动包（~30-80B）、心跳、音效、交互等**每包每玩家**走此路径；聚集/高人数下每 tick 数千次。
- aboveThreshold 的均值收益 7% 未达统计分离；大包侧的结构性收益已由批次 58 的 0214（编码预分配）/0215（压缩上界）覆盖，本补丁在压缩路径消除的是最后的帧拷贝。
- 复刻保真度：真实 netty MessageToByteEncoder/write/引用计数语义（EmbeddedChannel）；压缩后端为 JavaVelocityCompressor.deflate 的 ByteBuffer 语义复刻（JDK Deflater 复用 + ensureWritable(8192) 续压 + readerIndex 推进 + reset），native libdeflate 的 memoryAddress 写出语义经 javap 实证兼容 headroom 偏移。

## 等价性验证（自检 main，ALL OK）

1. **字节级**：尺寸 {1, 100, 255, 256, 257, 1K, 4K, 16K, 100K} × {随机, 全零} 矩阵下 before/after 链出站字节逐字节一致（含阈值边界 255/256/257）；帧结构可解析（frameLen varint + dataLen varint 正确）。
2. **varint 反向写**：writeVarIntBackwards 与前向 VarInt.write 对 0..2^22 全档位 + 边界值逐字节一致，且不触碰后续载荷字节。
3. **引用计数**：万次写-读-释放循环 refCnt 全程正确，无泄漏。

## 等价性论证（源码级，逐项）

- 三阶段在同一 channel 单 event loop 的同一次 write 遍历内顺序执行（管线 list 序 [prepender, compress, encoder]，出站反序；Connection.configureSerialization:698-699 + setupCompression addAfter("prepender","compress") 实证）→ channel attr 发布/消费无并发。
- CipherEncoder（NativeVelocityCipher.process）以 `memoryAddress()+readerIndex()` 起算、只加密 readable 区（javap 实证）→ headroom 前缀 [0, readerIndex) 不参与加密，帧头明文/密文边界与原版一致。
- 引用计数：直通=所有权经 ctx.write 转移（netty invokeWrite0 对同步下游异常自行 release 传递中的 msg）；异常路径 finally 恰好一次 release——初版有"压缩抛异常双重释放"缺陷，引用计数审计后重写为所有权内聚结构（宁可泄漏不双重释放的守卫方向）。
- 回退面：身份不匹配（插件注入换 buffer / 协议切换瞬态 / 任何未预期形态）全量走原版拷贝路径；PacketEncoder 对 memory 连接（LocalFrameEncoder）不发布标记（防滞留引用）。

## 风险与残留

- 管线结构改动（write 覆写×2 + allocateBuffer 预推进），风险评级低-中：字节等价有真实 netty 管线自检实证、回退面完备、引用计数经万次压力。
- 未做 live 服务器压测（与既有基准纪律一致；本补丁无跨线程改动——与已回退的 0209 不同，三阶段全程单 event loop）。
- 单补丁可独立 revert（0217）。

## 基建修复（顺带，已记 build.md）

- run.sh 的 javac 补 `-proc:full`（JDK 21+ 注解处理显式化，否则 BenchmarkList 空、JMH 无匹配）。
- benchmark 依赖补 netty-transport + netty-codec-base（netty 4.2 的 MessageToByteEncoder 在 codec-base）。
- EmbeddedChannel 装配序与真实管线 list 序一致（反向则全直通、自检抓包失败）。
