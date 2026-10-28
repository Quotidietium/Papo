# R16（批次 156）：shape 族规范共享——"注册表界恒定成本"判据证伪与收割（0.88.0 → 0.89.0）

日期：2026-10-10　版本：0.89.0　补丁：0274　工具：`benchmark/src/papo/bot/ShapeDupCensus.java`（解析核心逐字取自 ShellRefAttr）

## 1. 选点：旧判据的缝隙

R10/R11 将 shape 族（AABB 345,829 + ArrayVoxelShape 141,519 + BitSetDiscreteVoxelShape 140,060 + DoubleArrayList 291,870 + 列表/AABB 载荷 ≈51MB）判为"每 BlockStateBase$Cache ~4.8 形状的**注册表界恒定成本**"出局——但那是**有界**判据，不是**不可约**判据：族内结构性重复率从未量化。新工具 ShapeDupCensus 解析 B-088 驻留转储全部 AABB 实例的六个内联 double：

- **345,808 实例 / 仅 3,256 个不同几何 = 99.1% 结构重复**；Top 元组全是方块几何（栅栏柱 (0,0,.3125)-(1,1,.6875) ×74,022、墙柱 ×65,832、全立方 ×7,643……）
- 持有链（ShellRefAttr 双层）：209,718 AABB 挂 Object[]（toAabbs 列表背板）+ 147,326 直接挂 ArrayVoxelShape 字段——**全部质量随 141,519 个 ArrayVoxelShape 生死**；形状挂点 = VoxelShape[]（43.4 万引用=面向形状惰性数组）+ 每状态 Cache/occlusionShape + 块类静态表（Guava ImmutableMap ~1.1 万）

形状是不可变值对象，等几何异实例正是批 147/149 规范共享模式（已验证非红线）的适用面。

## 2. 实现（0274）

- **`PapoShapeCache`**（net.minecraft.world.phys.shapes，新类）：`ConcurrentMap<ShapeKey,VoxelShape>` + `papoCanonical(VoxelShape)`。键=结构相等（三轴点表 double[] + 体素格尺寸/界 + BitSet 词）；仅 `ArrayVoxelShape` 且 `BitSetDiscreteVoxelShape` 背书的形状参与（CubeVoxelShape/其他表示原样透传——**Shapes.empty()/block() 单例永不被替换**，`==` 单例比较语义不变）。**只在长寿命缓存点调用，绝不在构造点**——瞬态形状（dynamic-shape 块/偏移查询）不入表，表大小被状态注册表不同几何数严格上界。
- **BlockBehaviour 两挂点**：`Cache` 构造器 `collisionShape = papoCanonical(block.getCollisionShape(...))`；`initCache()` `occlusionShape = papoCanonical(...)`。两根规范后，全部惰性子结构（faces[6]、toAabbs/CachedToAABBs、Moonrise cachedShapeData/mergedORCache[16]）随规范根自动每几何一份——这正是 census 中 43.4 万 VoxelShape[] 引用与 20.9 万列表 AABB 的坍缩机制。
- 红线论证：几何逐位不变（键即几何）；共享仅改实例同一性；规范表增长界=注册表不同几何数（≤状态数，无运行时增长源）；惰性字段并发面=vanilla 每状态单例本就跨线程共享的既有模式，无新风险类。

## 3. 验证链

- 单测 `PapoShapeInternTest` 7 项 + 套件注册：等几何 join 坍缩同实例/异几何各异/幂等/单例透传/**楼梯跨类型共享**（StairBlock 每实例自建形状表=改动前异实例、几何恒等——强判别器）/楼梯朝向各异/共享后 bounds+toAabbs 恒等。首跑 2 败=测试输入退化（全立方 OR 被 join 快速路径短路成 block() 单例，预断言 notSame 即挂）——换非退化输入后全绿。`:paper-server:test` **8,960 通过**（含 149/150/147/145/148 五套件维持）。
- rebuildPatches → **0274**；applyPatches 全量重放 EXIT=0；check_patch_counts **ALL OK**；compileJava --rerun-tasks --no-configuration-cache BUILD SUCCESSFUL；createPapoJar 0.89.0。

## 4. 浸泡（同会话配对，12 bot/150s 扩散/180s 驻留/120s 测点）

机器负载晨窗剧烈漂移（同二进制 B-088 跨时点 4,481→5,539 区块 ±11%），三次尝试：

| 对 | A-088 | B-089 | 配对 | 结论 |
|---|---|---|---|---|
| rep1 | 725,268KB @5,539 | 560,665KB @4,388 | −20.8%（B 少载） | 机器窗差吞噬，弃 |
| rep2（B 带转储） | 666,220KB @4,712 | 576,841KB @4,524 | −3.99% | B 腿门败=**转储冻结伪影**（见下），测量与转储有效 |
| **rep3（双门绿）** | **630,304KB @4,384** | **589,259KB @4,522** | **+3.15%（B 多载，保守向）** | **原始 −41,045KB = −40.1MB（−6.52%）**；每区块 143.8→130.3KB（−9.4%） |

rep3 为头号数字：B 多载 3.15% 区块仍 −6.52% 原始（保守界，R14 判例双口径）；boot 17.9 vs 17.5s 无回退。

**rep2 转储腿门败归因（判例级）**：logErrors=422 实为 3 个 `Could not save data to *.dat`（栈帧行逐行计数放大）；时间线=44.2s GC.heap_dump STW 冻结 → stop → 终存首试 NoSuchFileException（10:43:56）→ **10:49-10:50 全部落盘成功 + "All dimensions are saved" + exit 0**（临时目录现场取证：chunks.dat/scoreboard.dat/stopwatches.dat 齐全）。=转储冻结与终存竞争的瞬态、自愈、数据完整，非服务端缺陷；非转储腿零出现。

## 5. 归因闭合（转储 census，逐位确定性、机器无关）

| 类 | 0.88.0（6,046 块） | 0.89.0（4,524 块） | 实例差 | 每区块 |
|---|---|---|---|---|
| AABB | 345,808 | 71,467 | **−79.3%** | 57.2 → 15.8 |
| ArrayVoxelShape | 141,519 | 38,785 | −72.6% | 23.4 → 8.6 |
| BitSetDiscreteVoxelShape | 140,060 | 37,334 | −73.3% | — |
| DoubleArrayList | 291,870 | 86,392 | −70.4% | — |

（族为注册表主导，绝对实例数不随机器态漂移，跨会话转储可比。）残余 71,467 AABB 仍 96.4% 重复=**块类静态形状表**（楼梯/多面体等 ImmutableMap 静态，~1.1 万形状及其惰性派生，注册表固定量）——后续角度（Shapes 工厂拦截需瞬态排除，判例风险已记）留档未做。

族质量 ~51MB → 残余 ~16-20MB，归因约 −31~35MB，与配对原始 −40.1MB（含列表/背板/缓存壳等 census 类未单列部分）一致。

## 6. 判例

1. **"有界"≠"不可约"**：族级判负必须测结构重复率——AABB 99.1% 重复藏在"注册表界恒定成本"标签后面三轮未被发现。
2. **解析器复制不重写**：两遍手写 HPROF 走查皆错位，纯复制已验证工具的核心一次通过（"转录即缺陷源"）。
3. **转储腿门败先取证后定罪**：盘上文件齐全 + "All dimensions are saved" + exit 0 三证据齐=冻结竞争瞬态自愈，非服务端缺陷；不入良性过滤器（过滤器看不到自愈，泛化危险）。
4. **机器负载晨窗配对法**：同二进制区块装载 ±11% 时窗漂移下，复跑到同向+保守向+双口径与逐位 census 互证即收，不追 ±1% 硬门（会陷入无限复跑）。

## 7. 主线更新

**0.80.0 → 0.89.0**：0.88.0 会话基线 725,268/630,304KB（两窗），本批同窗 −6.52%（保守界）。主线累计（跨会话链）：1,154.3 → ~720MB ≈ **−37.6%+**（批 154 直对口径 + 本批 −6.5% 叠加）。残余判负清单更新：shape 族从"注册表界红线"移出，残余为静态表固定量（有界非增长）；palette/光照/NBT/底座判负维持。
