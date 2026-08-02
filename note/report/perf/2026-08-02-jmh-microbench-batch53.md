# 批次 53 JMH 微基准报告（2026-08-02）

环境：JDK 21.0.10 (HotSpot 64-Bit)，JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt ns/op。
原始数据：`benchmark/results/bench-20260802-182942.json`。
基准为**语义复刻**（不依赖 Minecraft 运行时），`main()` 自检 ALL OK。
结论：**0206 trackedDataValues 缓存刷新延后到 pairing，per-dirty-tick 成本 2.16× 提速（CI 不重叠）**。属网络发送管线（实体数据包 ClientboundSetEntityDataPacket 的服务端准备）CPU 优化。

## 实体数据同步（EntityDataPairingBench）— 补丁 0206

| 方法 | before (ns/op) | after (ns/op) | 倍率 | 备注 |
|---|---|---|---|---|
| before_sendDirtyEntityData vs after_sendDirtyEntityData | 62.524 ± 8.693 | 28.915 ± 1.798 | **2.16×** | CI [53.8,71.2] vs [27.1,30.7] 不重叠。复刻典型实体 30 数据项（10 非默认、3 dirty/tick）。before 每 dirty-tick 做 packDirty(3) + getNonDefaultValues 全扫(30) 刷新；after 仅 packDirty(3)，刷新延后到 pairing |

- **分配**：before 每 dirty-tick 还分配 getNonDefaultValues 的 List（全量快照）；after 仅在 pairing（新观众，稀少）分配。稳态聚集（战斗/药水）下 dirty 频繁、新观众稀少，分配与 CPU 双省。

## 等价性支点（源码实证）

- **0206**：`ServerEntity.trackedDataValues` 原在构造器初始化（:87）、`sendDirtyEntityData` 每次 dirty 刷新（:399）、`sendPairingData` 读（:330）。改为：删除字段/构造初始化/dirty 刷新，`sendPairingData` 即时 `entity.getEntityData().getNonDefaultValues()` 计算。
- **协议时点完全不变**：dirty DELTA 包仍由 `sendDirtyEntityData` 经 `packDirty()` 产生并发送（行不变）；full 快照仍由 `sendPairingData` 在新观众加入时发送——只是改为即时计算而非读字段。**与原"ServerEntity 增量合并结案"是不同优化**（后者针对 getNonDefaultValues 内部惰性化改协议时点；本批只移缓存写入点，不动 SynchedEntityData 内部，不改任何包时点）。
- **等价性**：`getNonDefaultValues` 读当前值（`isSetToDefault` 比较 initialValue 与当前 value），实体数据仅经 `SynchedEntityData.set()` 变更（标 dirty）；值在两次 dirty 间稳定。故 pairing 时即时计算 = 原 last-refresh 字段值（刷新随 dirty 保持了字段为当前）。null（全默认→不发数据包）语义保留。
- main 自检：packDirty 输出（3 dirty）一致；getNonDefaultValues 输出（10 非默认）= 非默认集合；多轮 dirty 后 pairing 仍正确（dirty 由 packDirty 消费，不影响 getNonDefaultValues）。ALL OK。

## 验证链

compileJava（`--no-configuration-cache`）✓ BUILD SUCCESSFUL → 全量 test ✓ BUILD SUCCESSFUL（零 FAILED）→ rebuildPatches ✓（0206 正确格式）→ 恢复法保留（避开 0163–0192 垃圾重命名复发）。
