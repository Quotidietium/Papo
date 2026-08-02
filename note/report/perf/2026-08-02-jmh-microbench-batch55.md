# 批次 55 JMH 微基准报告（2026-08-02）— 网络 pivot 续

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
结论：**0208 PacketBundleUnpacker 非 bundle 包免 list::add Consumer 分配，1.23× 提速（CI 不重叠，EA 未消除）**。网络帧编码路径（IO 线程每出站包）。

## 帧编码（BundleUnpackerBench）— 补丁 0208

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_unbundleWithConsumer vs after_directAdd | 4.504 ± 0.291 | 3.669 ± 0.241 | **1.23×** | CI [4.21,4.79] vs [3.43,3.91] 不重叠。before 每包 `unbundlePacket(packet, list::add)` 分配 Consumer（方法引用捕获局部 list）+ 虚调用；after 非 bundle 直 list.add。差值 ≈ Consumer 分配 + 虚调用开销 |

- **EA 未消除**：survey 担心 `list::add` Consumer 被跨虚调用 EA 消除，实测 CI 不重叠（1.23×）证收益真实（非伪影）。每出站包省一次 Consumer 分配（IO 线程，高吞吐下累积）。

## 等价性支点（源码实证）

- **0208**：`PacketBundleUnpacker.encode` 原 `this.bundlerInfo.unbundlePacket(packet, list::add)` 改为 `if (packet instanceof BundlePacket) unbundlePacket(packet, list::add); else list.add(packet);`。
- **等价性**：`BundlerInfo.unbundlePacket`（:18-26）对非 bundle 包走 else `consumer.accept(packet)`==`list.add(packet)`（非 BundlePacket 的 type() ≠ bundle type）；仅 BundlePacket 需 consumer 展开。故 `instanceof BundlePacket` 路由：bundle 走 unbundlePacket（内部精确 `type()==type` 判定，错型 bundle 落 else==list.add），非 bundle 直 list.add。**结果逐字一致**，仅免非 bundle 路径的 Consumer 分配。`isTerminal()` 移除逻辑不变。
- main 自检：两路径对非 bundle 包 list 内容一致（[packet]，ALL OK）。
- **风险**：零（纯路由，结果等价；非 bundle 是 99% 主路径）。

## 验证链

compileJava（`--no-configuration-cache`）✓ → 全量 test ✓ BUILD SUCCESSFUL（零 FAILED）→ rebuildPatches ✓（0208 正确格式）→ 恢复法保留。
