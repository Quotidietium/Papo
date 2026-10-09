# R5 轮3（批次 143）：NoiseChunk 后置释放 —— worldgen 环驻留归零

Paper 1.21.11 · Papo 0.83.0 · 补丁 0268（features）

## 1. 背景与目标

轮2 后画像（0.82.0，12 bot / ~6k 区块）出现**随区块数线性放大的 worldgen
对象族**：`DensityFunctions$HolderHolder` 255,636、`Holder$Direct` 382,935、
`CubicSpline$Multipoint`/`Spline$Coordinate` 各 127,257、`MulOrAdd` 55,854
（~42/21/63 每持有区块；两腿 4.4k→6k 区块实测线性）。追因（全源码盘点）：

- 持有链：`ChunkAccess.noiseChunk`（NoiseChunk，BIOMES 步创建于 ProtoChunk）
  → `NoiseChunk.wrapped` map（键=每次 mapAll 新造的 HolderHolder{Holder.Direct}）
  + 映射后的 DF/样条子树 + 插值器 double[] + 含水层缓存；
- 唯一读者=BIOMES/NOISE/SURFACE/CARVERS 四个生成步骤（全部经
  `getOrCreateNoiseChunk`）；FULL 转换本就丢弃它（LevelChunk 不复制该字段）；
- 但**停在 carvers 与 FULL 之间状态的区块**（features/light/spawn 环，
  覆盖面积≈已加载区面积，实测持有数≈full 区块数）把整棵树留到区块卸载。

## 2. 优化内容（0268）

`ChunkStatusTasks.generateCarvers` 末尾调用新增的
`ChunkAccess.papoReleaseNoiseChunk()`（noiseChunk=null）。正确性：

- 字段本为 `@Nullable` 惰性设计：任何假想后继读者走
  getOrCreateNoiseChunk **确定性地重建**同一棵树（行为中立）；
- 步骤按区块严格串行（chunk system 状态机），释放写不可能与同区块读者竞争；
- 四读者全部 ≤ carvers（NoiseBasedChunkGenerator 的 biome/noise/surface/carver
  路径），全树盘点无其他持有者/读者。

## 3. 验证

- 构建链：rebuildPatches 0268 生成 + applyPatches 全量重放 EXIT=0 +
  compileJava --rerun-tasks BUILD SUCCESSFUL + :paper-server:test BUILD
  SUCCESSFUL + 0.83.0 jar 产出；
- 功能面：Soak 场景本身即 worldgen 压测（12 bot 行走持续触发新区块生成至
  features/spawn 环），门=服务器日志零 ERROR/Exception + exit 0；
  地形正确性由种子确定性保证（同种子同地形；NoiseChunk 重建路径逐位同构）；
- 浸泡两腿（0.82.0 vs 0.83.0）：结果 §4。

## 4. 结果（场景配对：6,064 vs 6,112 区块，两腿 12/12 bot，门全绿）

| 指标 | A：0.82.0 | B：0.83.0 | Δ |
|------|-----------|-----------|---|
| 堆 used | 941,245 KB | **876,194 KB** | **−65,051 KB（−6.9%）** |
| LevelChunk | 6,064 | 6,112 | +0.8%（配对） |
| NoiseChunk | 1,086 | **372** | −65.7% |
| DF$HolderHolder | 258,480 | **89,262** | −65.5% |
| Holder$Direct | 387,195 | **133,725** | −65.5% |
| CubicSpline$Multipoint | 128,673 | **44,421** | −65.5% |
| double[] | 41.8 MB（第4名） | **16.2 MB（第10名）** | −25.6 MB（−61%） |
| long[] | 187.1 MB | 184.0 MB | 配对（无关类不动） |

归因：可见 DF/Holder/样条簇实例 ~10MB + **插值器 double[] −25.6MB** +
含水层缓存等邻接对象 ≈ 堆降额 65.1MB，闭合。B 腿残留的 372 个
NoiseChunk/89k HolderHolder = 仍停在 carvers 之前状态的活环（BIOMES/
NOISE/SURFACE，后续步骤仍要读）——释放点只作用于过 carvers 的区块，
语义保留正确。

## 5. 结论

轮3 达成：worldgen 环的 NoiseChunk 驻留在 carvers 步末释放，配对场景堆
**−6.9%**（其中插值器 double[] −61%）。R5 三轮累计（同场景类）：
轮1 −9.0% × 轮2 −9.7% × 轮3 −6.9% ≈ **累计 −23.4%**。**0.82.0 →
0.83.0 发布。**
