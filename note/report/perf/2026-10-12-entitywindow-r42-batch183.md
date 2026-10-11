# R42（批次 183）：实体密集+保存窗分配普查→packed long[] 编码 NBT 快路（→0.107.0）

日期：2026-10-12 ｜ 轮次：R42 ｜ 判定：**新窗口普查+收割**（0.106.0 → 0.107.0）

## 立项背景：最后两块未普查的分配域

R41 关闭驻留窗（peaceful）后，剩余未普查的分配域是**实体密集稳态窗**（R25 在
EntitySoakCensus 场景只普查过驻留、从未普查分配；真实服务器稳态 GC 压力由 tick 路径
支配）。工具 `benchmark/alloccensus-r42entity.sh`：EntitySoakCensus（difficulty=normal+
spawn-monsters）12 bot × 行走 150s + 驻留 300s，JFR 以 harness `boot:` 行为零点 +310s
开 60s 窗（覆盖驻留 150-210s 怪群成熟段）。

**意外收获**：窗内 uptime 325-385s 跨越 5 分钟**自动保存**——保存窗（R32 之后从未被
专门普查的域）与实体窗同腿成像。

## 发现腿画像（B-106，1,416 事件/60s）

top 面分层判定：

| 面 | trips | 判定 |
|---|---|---|
| O2O map ← CompoundTag. | 85+27+24 | NBT 保存内容构造（数据面） |
| DataResult.success/ap2（DFU encode 机械） | 45+12 | 上游 DFU codec 架构（R28/R11 维持） |
| makeBoundingBox / Vec3.add | 28/28 | AABB 值货币+结果对象（R41 判定维持，随实体数缩放） |
| LongArrayTag.write | 27 | **既优化的代价面**（R32 bulk encode 临时 buf，R36 判例维持） |
| CompoundTag.copy ← MoonriseRegionIO 写完成 | 23+22+10 | Moonrise 异步写防御深拷贝=上游架构（R30 留观察项定性收束） |
| SimpleBitStorage.copy ← Data.copy ← copyOf | 20 | 保存节快照（Paper/Moonrise 异步保存线程安全架构） |
| findEligibleGroups 等 | 21+11 | Moonrise 调度器上游维持 |
| RegionFile ChunkBuffer/Deflater/Buffered | 14+10+15 | 保存 IO 基建（Mojang RegionFile 架构） |
| spark/JfrProfiler | 151/60 in-stack | 上游诊断+观测伪影（R41 判例剥离） |
| **Nodes$LongArrayNode ← createLongList ← PalettedContainer$1.encode** | **18** | **立项收割（R31 同族流形态面）** |

## 收割（补丁 0295）：PAP0_LONG_ARRAY.encode 的 NBT 快路

`pack()`（R32 两阶段重编码）返回的 `long[]` 经 `PAP0_LONG_ARRAY.encode` 落盘：
`ops.createLongList(Arrays.stream(input))` → NbtOps 实现是
`new LongArrayTag(stream.toArray())` —— **long[] → LongStream → spliterator/LongFixedNodeBuilder/
LongArrayNode → 新数组**，纯机械往返（R31「流形态=隐藏拷贝」判例同族）。R31 当年正是
依赖这下游 toArray 兜底恒等（pack 返回新数组的注释自证）。

收割：`ops == NbtOps.INSTANCE` 时直接 `new LongArrayTag(input.clone())`。
恒等论证：①NbtOps 私有构造严格单例，恒等比较精确（无子类面）；②`Arrays.stream(x).toArray()`
内容恒等于 `x.clone()`（同为全新数组同内容——LongArrayTag 拥有独立后备数组的 vanilla
保证逐位保持，对第三方手工构造 PackedData 的边角场景也恒等）；③包装 ops
（RegistryFileOps 等）走原通用路径=行为逐字节 vanilla。触发面：每个非单值 block-states/
biomes 节的保存（4-bit 档 256 longs 主力）——每次自动保存数百节。

判负维持：LongArrayTag.write 临时 buf（代价面重开须先证净赢）；Moonrise 写完成深拷贝
（R30 留观察项本轮定性判负=上游异步写架构）；节快照拷贝（上游线程安全架构）；DFU
DataResult 族（上游）；NBT 内容构造（数据面）。

## 验证

- **恒等单测** `PapoLongArrayEncodeFastPathTest`（4 用例，注册套件实测 4/0/0）：
  **Proxy 全委托 oracle 差分**（DynamicOps Proxy ≠INSTANCE 但逐方法路由 INSTANCE→
  encode 走通用 createLongList 流路径=精确 vanilla 路径；R36 Proxy 判例复用）断言快路
  Tag 与 oracle Tag 内容相等；编解码往返 64 格逐位；编码快照免疫容器后续变异
  （clone 证明）；单值容器仍省略 data 字段。自查纠偏一次：y=1 单层 4×4=16 格
  预填断言写成 32（测试输入算术滑误，R33 同类判例）。
- **链门**：rebuildPatches（0295）/applyPatches/`:paper-server:test --rerun-tasks`
  （XML 合计 **9,071/0/0** 恰=9,067+4）/compileJava（--rerun-tasks）全 EXIT=0+
  BUILD SUCCESSFUL；0.107.0 出包。
- **MSPT 三序+同序复跑**（0.106.0 vs 0.107.0，八腿）：主 2/6（≤+0.3）·倒 2/6
  （+0.6..+1.1）·钉 3/6（s4 +0.7 为唯一三序同号槽）→ **钉死对同序复跑 s4 = 0.0 不
  复现**（R38 判例：真系统差同序必复现，瞬态机器漂移）；s6 B 四对全优、s3 两序反向、
  全部幅度 <0.7 < 腿内摆幅（~1.0）、无机制通道（改动只在保存编码路径且只减工作量）→
  **零回归成立**。
- **实体+保存窗配对腿**（A-106en 2,061 事件 vs B-107en 2,204 事件；实体 62,444 vs
  62,873=+0.7% 工作量缩放如实入档）：`Nodes$LongArrayNode ← LongFixedNodeBuilder`
  **35→0**（发现腿 18、配对 A 腿 35→B 腿 0）；替代面零新增；两腿
  EntitySoak.gate logErrors=0。

## 判例

1. **保存窗与实体窗同腿成像**：驻留 150-210s 窗天然含 5 分钟自动保存——一次普查
   覆盖两域；保存窗此后不再空白。
2. **R30 留观察项定性收束**：MoonriseRegionFileIO 写完成深拷贝的调用链正面显形
   （Completable 完成回调内 syncAccept 持久化前防御拷贝）=上游异步写架构，判负。
3. **单例 ops 恒等快路**：私有构造的严格单例（NbtOps.INSTANCE）允许 `==` 身份守卫
   的直连快路；clone 保住"tag 拥有全新数组"的 vanilla 可观察保证，比所有权转移
   （免 clone）的证明更强、代价仅一次 System.arraycopy。
4. **同序复跑是同号槽的终审**（R38 判例第三次应用）：三序同号槽以同序复跑定生死，
   不复现即瞬态。

内存账：保存窗流机械面 18-35 trips→0；每 hash-palette 节保存少 4 件流机械对象+1 次
冗余拷贝；驻留无变化（纯瞬态）。版本 0.107.0。
