# R46（批次 187）：机械负载 tick 窗分配普查 → 漏斗空实体查询快路（0.108.0 → 0.109.0）

日期：2026-10-12 ｜ 版本：0.108.0 → 0.109.0 ｜ 类型：新场景轴（R38 判例：从未普查过的场景即新证据等级）

## 1. 轮目标与场景选择

R24-R45 历轮分配普查的窗口全部是**自然地形 + bot 流送**（生成/加载/驻留稳态/实体窗/保存窗），
从未在**机械负载**（漏斗物流 + 红石时钟）下成像——而真实服务器的常驻工作负载恰恰是农场的
漏斗环与观察者时钟。本轮以机械负载 tick 窗补上这块从未成像的地图：

**工具**：`benchmark/src/papo/bot/MechCensus.java` + `benchmark/mechcensus-r46legs.sh`
（12 bot 撒布 150s → +20s 机械填充相位：每 bot 6 个漏斗环（3×3 周界、
N/E/S/W 逆时针充装序、`replaceitem` 预置种子）+ 6 对观察者互视时钟（2gt 自启振荡、
无需支撑方块）→ 填充 +90s 打印 `MechCensus.loadWarmed` 锚点（两腿 JFR 窗共用）
→ +170s 测量（GC×2 + heap_info）→ 驻留末 bot 断开）。
机械规模实测每腿 576 漏斗 + 72 时钟（12 bot × 6/6），对照组自然腿 fill=0。

红石设计判例（build.md 详）：中继器/红石线时钟在 y=200 空中缺乏支撑方块即弹跳失效；
观察者对（observer[facing=east/west] 互视）= 经典 2gt 自启振荡，实心方块零支撑需求，
`/execute if block ... powered=true` 10×200ms 密采样可证活（2gt 占空比下单次 3s 采样必漏）。

## 2. 发现腿（同二进制 0.108.0 配对，自然 vs 机械，STAMP 20261012-153230）

| 口径 | A 自然 | B 机械 | Δ |
|---|---|---|---|
| 事件数（60s JFR 窗） | — | 1,777 | — |
| 堆 used | 643,124KB | 686,249KB | +43,125KB（含 576+55 漏斗机械本体） |
| 漏斗计数 | ~55 | 631 | +576 |

**逐面判定（≥20 trips 立信阈；B 差分 A）**：

立项收割（唯一机械面）：
- **漏斗空实体双查询**：`Level.getEntitiesOfClass <- HopperBlockEntity.getEntityContainer` 21 trips/24.0MB
  + `<- getItemsAtAndAbove` 12 trips/16.5MB（A 腿双面皆 0）。
  漏斗每 tick 两问"suck 范围内有无实体"与"上方容器实体查询"，绝大多数农场场景
  答案永远是"无"——vanilla 每问都走完整 `getEntitiesOfClass` 走架
  （±2 chunk 走架 + ArrayList 输出表 + 逐实体谓词），空答案也全价付费。
  60s 窗 ~72 漏斗动作/tick × 2 问 ≈ 172,800 次查询全空转。

判负留档（全部落 R41-R44 既定判例清单）：
- `ItemStack.copy` 分裂 + 观察者路径：<20 阈散布（数据面+结果对象，R41 判例）；
- 保存 IO 壳族（BufferedOutputStream 39 / Writer / RegionFile BAOS 12）：R43 判负关闭清单同型；
- NBT/DFU 族（CompoundTag/Object2ObjectOpenHashMap/DataResult）：R42 数据面；
- 网络面（DirectByteBuffer.slice 54 / PacketEncoder）：R41/R43/R44 判负维持；
- spark 采样器族 + JFR 自观测：R41 判例剥离；
- `Vec3.add`/`EntityDimensions.makeBoundingBox`：结果对象维持面，R41；
- MoonriseRegionFileIO 深拷贝：R30 留观察项定性收束（上游异步写架构）。

## 3. 收割实现（0297 补丁：hopper-empty-entity-query-fast-path）

### 3.1 保守空探针（四处）

**谓词本体** `ChunkEntitySlices.papoHasNoEntitiesInSections(AABB)`：
镜像真查询逐位读取的 section 区间（`EntityCollectionBySection.getEntities` 的
`clamp(floor(minY-2)>>4, minSection, maxSection) .. clamp(floor(maxY+2)>>4, ..)`），
逐 section 检查 `allEntities.entitiesBySection[section - minSection]` 是否全 null。
全 null ⟹ 真查询对该区间是 no-op ⟹ 可跳过。

**走架同构** `EntityLookup.papoHasNoEntitiesIn(AABB)`：镜像
`getEntities(Class, Entity, AABB, List, Predicate)` 的 chunk 迭代骨架
（同 ±2 chunk 走边距、同 REGION_SHIFT/REGION_MASK 区块环形、同
`chunk == null || !status.isOrAfter(FULL)` 跳过），每 chunk 只调谓词不建输出表。

**透传** `Level.papoHasNoEntitiesIn(AABB)` → moonrise lookup（getEntitiesOfClass 同一入口）。

**双守卫**（HopperBlockEntity）：
- `suckInItems` else 分支（无容器可抽时）：`!flag && !level.papoHasNoEntitiesIn(...)` 才走
  原查询循环；
- `getEntityContainer`（私有）：查询前置 `if (level.papoHasNoEntitiesIn(aabb)) return null;`。

**共享 suck 查询盒** `papoSuckQueryAabb(hopper)`：getItemsAtAndAbove 原内联 AABB 计算
（含 per-hopper-kind 缓存：HopperBlockEntity 字段缓存 + MinecartHopper 每次重建）
提为共享静态——快路必须探测与真查询逐位相同的盒。

### 3.2 恒等可证性

- **保守单向**：谓词 true ⟹ 真查询必空（全 null section 列表 ⟹ allEntities 空区间 ⟹
  类/型集合亦空——initClass 从 allEntities 回填）；false = "可能有"，回退真查询。
  误报方向只付原价，无行为差。
- **线程封闭**：实体增删与查询全在主线程（Moonrise 架构），谓词读取与真查询读取
  之间无并发变更窗口，per-chunk 状态一致。
- **可见行为**：跳过的只是"构造空 ArrayList + 逐 chunk 空走架"；调用方对空结果的
  消费（返回空表/null）逐位一致。

### 3.3 粒度判例：section 粒度而非 chunk 计数

第一版谓词用 chunk 总数 `count == 0`，配对腿实测 `getEntityContainer 15→0` 但
`getItemsAtAndAbove 15→10`——**y=200 漏斗与 y≈75 观战 bot 同 chunk 列**（真实世界
同构：玩家站在农场上方），chunk 计数被无关高度实体顶住。升级为 section 粒度
（±2 section 区间内逐 section 判空）后双面全坍缩。判例：**空探针的粒度必须匹配
真查询的读取粒度**（查询按 section 区间读，探针就按 section 区间判），
否则共置负载（同列不同高）直接击穿快路。

## 4. 恒等单测（PapoHopperEmptyQueryFastPathTest，3 用例注册 PapoRegressionTestSuite）

1. `chunkPredicateMatchesQueryOracle`：空态双向（谓词 true=oracle 空）→ 注册实体翻谓词
   → oracle 找到 → 同 section 远实体（盒外）谓词保守 false 而 oracle 盒内排除 →
   移除恢复空态全路径；
2. `sameChunkDifferentSectionStaysSkippable`：同 chunk 列不同 section 保持可跳
   （y≈75 实体 vs y≈200 盒，x/z 同柱）→ 盒降到实体 section 翻谓词 + oracle 找到；
3. `lookupPredicateOnlySeesTouchedFullChunks`：±2 chunk 走边距外不可见 → 触达 chunk
   注册实体翻 → INACCESSIBLE（FULL 之下唯一枚举位）chunk 与真查询同过滤。

**测试工程判例两条**（本轮排障链，详见 build.md）：
- Mockito mock Entity 触发 `Entity.<clinit> → EntityDataSerializers → ParticleTypes →
  BuiltInRegistries`，未 bootstrap 即"Not bootstrapped"炸 mock——全套件运行时靠
  别的测试类先行 bootstrap 而通过 = **隐式执行顺序依赖**；单类过滤跑即暴露。
  修复：`@BeforeAll { tryDetectVersion(); bootStrap(); }`（全套件既定模式）。
- 查询盒 z 轴算术滑误（y 值误写入 z 槽）：盒 z=[74.9,75.6] 而实体 z=[8.0,8.5]，
  oracle 正确返回空而谓词（section 区间）正确翻 false——两断言"矛盾"实为测试自身
  坐标错。R33/R42 同族第三例：**非对称轴值的盒构造必须逐轴复核**（测试 1 因
  x=y=z 同值而免疫，恰使滑误只在测试 2 显形）。

## 5. 链门与出包

- 全套件 `:paper-server:test --rerun-tasks`：**9,085/0/0**（9,082 + 3 新）EXIT=0；
- `compileJava` + `createMojmapBundlerJar --rerun-tasks` + `createPapoJar` EXIT=0，
  0.109.0 出包；**旧包陷阱**：16:15 出的 0.109.0 jar 早于 16:35 applyPatches 源重写
  （chunk 粒度版），Gradle up-to-date 误判未重建——删除产物 + `--rerun-tasks` 强制
  重建后 `javap` 验包内字节码确为 section 粒度谓词（判例：**applyPatches 后必须
  以源 mtime 或字节码验证出包新鲜度，Gradle up-to-date 在跨任务图下不可信**）。

## 6. 配对验证腿（STAMP 20261012-170645，双机械腿 fill=1，唯一变量=jar）

| 口径 | V108（0.108.0） | V109（0.109.0） |
|---|---|---|
| gate | exit=0 / logErrors=0 | exit=0 / logErrors=0 |
| bot 在线正证据 | TRACE 84 ≥ 12、零类版本错误 | TRACE 84 ≥ 12、零类版本错误 |
| 机械组成 | fills 504 / hoppers 576 / clocks 72 | 同左（逐位一致） |
| 事件数（60s JFR） | 1,805 | 1,812 |
| `getEntityContainer` 面 | **19 trips / 24.1MB** | **0** |
| `getItemsAtAndAbove` 面 | （本腿低于 top-40；发现腿 12 trips/16.5MB） | **0** |
| 替代面（papo 谓词/共享盒） | — | 0（谓词零分配） |
| 堆 used | 668,057KB | 671,573KB（+0.5%，负载方差带内） |

**双面全坍缩 + 零替代面 + 组成恒等** → 收割成立（R41 判例：面坍缩是门，堆差不是）。

## 7. MSPT 三序守护（0.108 vs 0.109，bot 在线正证据门，十腿全绿）

三序 + 同序复跑（win5s 中位，B−A 每槽）：

| 槽 | 主序① | 倒序 | 钉死 | 主序②(复跑) | 主序③(终审) |
|---|---|---|---|---|---|
| s1 流送 | **−0.8** | +0.2 | +0.4 | **−0.4** | **−0.6** |
| s2 流送 | **−0.7** | +0.5 | +0.3 | −0.1 | +0.4 |
| s3 流送 | **−0.3** | +1.2 | +1.7 | +0.2 | **−2.3** |
| s4 驻留 | +0.5 | +1.4 | +0.2 | +1.4 | **−0.1** |
| s5 驻留 | **−0.3** | +0.8 | **−0.2** | 0.0 | +0.3 |
| s6 驻留 | **−0.2** | +1.3 | +0.8 | +0.3 | **−0.7** |

- 腿 gate 全绿：TRACE 84-96/腿（≥12）、零类版本错误、exit=0（十腿）。
- **唯一三序同号槽 s4（+0.5/+1.4/+0.2）** → R38 判例同序复跑终审：主序②复现 +1.4，
  主序③**破号 −0.1**（B 微优）——同号序列在独立同序重复下断裂。
- **无机制通道**：MSPT 腿为自然地形（MsptLoadProbe，零漏斗），0297 全部改动都在
  漏斗路径上（谓词/守卫/共享盒），本场景零执行——JFR 配对腿亦零 papo 帧佐证。
- **相变槽特征**：合池中位只有两个相变槽（s3 末流送/s4 首驻留）B 偏劣，稳态槽
  （s1/s5）B 偏优——版本不可能选择性只拖慢相位过渡窗而加速稳态窗，这是过渡窗
  排空时序运气特征（s4 版本内腿间摆幅 4.5..5.6=1.1ms > 同号幅度带 0.2..1.4）。
- 倒序双腿整体上漂（B 6.8/A 5.4 均高于主序绝对位）= 会话漂移带，如实入档。

**结论：零回归**（s4 同号序列 = 相变窗漂移，五对终审破号 + 无通道 + 幅度<摆幅三重否决）。

## 8. 判例与结论

判例四条：
1. **机械负载 tick 窗是独立成像域**：自然地形普查对漏斗/红石面零覆盖，农场景观
   必须专门成像才能看见空查询全价付费；
2. **空探针粒度必须匹配真查询读取粒度**（section vs chunk，共置负载击穿案例）；
3. **mock NMS 类的单测必须自带 bootstrap**（隐式依赖套件执行顺序 = 顺序一变即红）；
4. **applyPatches 后的出包新鲜度必须实证**（源 mtime / 字节码验证；Gradle
   up-to-date 跨任务图误判案例）。

每漏斗动作省：2 次完整实体查询走架（±2 chunk 迭代 + 输出 ArrayList + 逐实体谓词）
→ 1 次零分配谓词走架。农场场景（漏斗密集、实体稀疏）每 tick 直接受益；
分配源轴第五域（机械负载）从未成像 → 收割闭合。版本 0.109.0。
