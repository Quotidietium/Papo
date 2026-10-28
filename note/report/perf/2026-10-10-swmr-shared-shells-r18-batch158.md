# R18（批次 158）：SWMRNibbleArray 冻结壳规范共享——0.90.0 → 0.91.0（0276）

**主题**：光照节（section nibble）外壳对象的结构重复收割。批 157 浸泡 B-090 的
top-25 直方图给出新角度：`SWMRNibbleArray` **539,188 实例 ×32B ≈ 17.3MB**，而
`byte[2048]`（真实光照载荷）census 总量仅 30,276——**≈94% 的壳是"生而冻结、从未
写入"的空壳**。本轮把这 ≈50 万冻结壳收敛为 **2 个不可变单例**，写路径经
copy-on-write 提升，任何漏网写点由身份 canary 立即炸响（批 149 纪律）。

## 1. 证据链（批 157 收官时的残余画像）

- B-090 top-25：SWMRNibbleArray 539,188 ×32B = 17.3MB；byte[2048] 全仓仅 30,276
  ——壳/载荷比 ≈ 18:1。
- 出生面枚举（全库 grep）：
  - `StarLightEngine.getFilledEmptyLight(int)`（`new SWMRNibbleArray(null, true)`
    = **NULL 态+空存储**）：每个 chunk 的天空+方块两张节表在出生时全部填充此壳
    ——539k 的压倒性来源（≈ chunk 数 × 节数 × 2）。调用方：SaveUtil 加载、
    light() 中心块、relightChunks。
  - `SaveUtil` 空数据段（`new SWMRNibbleArray(null, stateTag)`，stateTag∈{NULL,
    UNINIT}）：盘上空数据段必然是这两态（getSaveState 材质化 uniform、从不写
    null-data INIT/HIDDEN——批 148 不变式）。
  - `fromVanilla(null/empty)`：同一对出生态（生产代码零调用方，防御性同样收敛）。
  - 引擎惰性建点（BlockStarLightEngine:78 / SkyStarLightEngine:80）：cache-only、
    本会话内即弃，保持私有出生（不变更既有语义）。
- 冻结态空间：实测只有 **2 个** null-storage 出生态（NULL、UNINIT）。批 148 引入的
  INIT/HIDDEN uniform 态只能经变更产生——生而为私有，无需规范壳（比早先"6 规范
  壳"草案更简）。
- 写者面（变更方法全库 census）：8 个状态翻转方法 + 2 个 `.set` 重载，调用站点
  **11 处、仅 3 个文件**（StarLightEngine 2=两个 setLightLevel；Sky 6；Block 2）；
  `extrudeLower` 读参数侧不需处理。规模与批 149 六门同量级——"写者面宽"顾虑排除。

## 2. 设计：3 单例 + 源数组追踪 COW + 定居交换 + 身份 canary

### 2.1 SWMRNibbleArray（不可变单例 ×3）

- `PAPO_SHARED_NULL`（NULL 态，空存储，clean）、`PAPO_SHARED_UNINIT`（UNINIT 态，空
  存储，clean）、`PAPO_SHARED_INIT_FULL`（INIT 态，空存储，uniform 15，clean——出生
  即冻结的天空满亮节；经 (bytes,state) 构造器的 all-full 材质化出生，声明必须晚于
  `LONG_VIEW`：静态初始化按文本序，其出生路径读 VarHandle——本批判例）。
- 读面值恒等：`getUpdating/getVisible`（null 存储 → uniform；INIT_FULL 读 15）、
  `isDirty`=false、`updateVisible()`=false（不发布）、`getSaveState()`（NULL→null；
  UNINIT→(null,UNINIT)；INIT_FULL→材质化 all-0xFF+INIT）、`toVanillaNibble()`（NULL→
  null；UNINIT→空 DataLayer；INIT_FULL→满亮 DataLayer）——与私有壳逐值相等。
- **每个变更方法头部身份 canary**（extrudeLower/setFull/setZero/setNonNull/setNull/
  setUninitialised/setHidden/set(index,value)）：`papoIsShared()` 即抛
  IllegalStateException——漏网写点=立即崩溃。`updateVisible` 刻意**不设** canary：
  updateVisible 循环对 notifyUpdate 槽合法调用它，clean 单例返回 false 是正确行为。
- `papoClonePrivate()`：冻结壳→全新私有出生（逐字段与出生点相等）。
- `papoFromSave(bytes, state)`：空数据+NULL/UNINIT→单例；载荷/畸形走原构造器（保留
  null-data INIT/HIDDEN 的既有 IllegalArgumentException 验证）。
- **`papoFrozenEquivalent()`（定居探针）**：发布后 clean 且一切可观测字段等于某单例
  （**状态字段精确相等**——isNull/isUninitialised/isInitialised 谓词逐位保持）→ 返回
  该单例。INIT-uniform-0 与 HIDDEN×{0,15} **刻意无单例**：前者对读者与 UNINIT 值同
  但 isInitialised*() 读 true、后者 vanilla 视图转 null——引擎逻辑恰在这些差异上
  分支。仅单写者上下文调用（光照线程的 updateVisible 尾声）。

### 2.2 StarLightEngine（写路径 COW + 源数组追踪 + 定居交换）

持有者拓扑（关键）：nibbleCache 引用可来自三种权威持有者——chunk 活节表
（`starlight$getSkyNibbles/getBlockNibbles`）、`light()` 的局部工作数组（结束时
`setNibbles` 整体落 chunk）、relight 的 `nibblesByChunk` 工作数组。原实现靠**别名
就地变更**贯通三者；COW 若只换 cache 槽，变更会在下次从 chunk 重灌缓存时丢失
（静默灯效损坏）。解法：

- `papoNibbleSourceCache[5×5]`：`setNibblesForChunkInCache` 记录喂给缓存的每 chunk
  数组（三类持有者在此统一）；`destroyCaches` 一并清空。
- `papoGetWritableNibble(cx, cy, cz)`：取出 → 非单例/null 直接透传；单例 →
  `papoClonePrivate()` + 写回 cache 槽 + **当且仅当源数组元素==该单例**（身份守卫，
  cache-only 惰性建点自然跳过）写回源数组元素。三类持有者一次覆盖。
- **定居交换（两处 updateVisible 出口）**：发布后调 `papoFrozenEquivalent()`，命中
  且非自身 → cache 槽+源数组（身份守卫）换为单例，私有实例转垃圾。
  - 出口一 `updateVisible(lightAccess)` 循环（增量操作尾声）：仅对 dirty/notify 槽
    到达——提升型壳（本操作内 setNonNull 后发布）在此坍缩；chunkY 越界守卫防
    nibbleCache 缓冲段。
  - 出口二 `relightChunks` 的逐节循环（索引化改写 nibblesByChunk 数组元素）——
    relight 工作数组落 chunk 前坍缩。
  - 每区块初始光照在其单次 light()/relight 调用内完成提升与发布，故定居交换在
    **同一操作尾声**即坍缩该块全部提升壳——不存在"旧私有壳滞留"窗口（clean 私有
    壳只可能是本操作刚发布的；更早操作的产物已在当时坍缩）。
- 7 个写点机械替换：两个 `setLightLevel`（world 坐标分解喂 wrapper）、Sky
  `setNibbleNull`/`initNibble(int)` 边界/`initNibble(SWMRNibbleArray)` 内的再取
  （防御性）、Block `setNibbleNull`/`initNibble`。
- 出生点替换：`getFilledEmptyLight(int)` 全填 `PAPO_SHARED_NULL`；SaveUtil 两条
  空数据段改 `papoFromSave`。引擎惰性建点（cache-only、本操作内即弃）保持私有出生。

正确性要点：`initNibble(SWMRNibbleArray)` 的 `currNibble` 参数只从 int 版边界进入
（全库唯一调用方），边界处已 COW；`extrudeLower` 的 other 侧为只读，单例作 other
合法且值恒等（uniform-15 源的底面外推=15，批 148 分支已处理）；所有 `nibble ==`
比较均为 null 检查，无身份语义依赖；交换后同操作内无代码再持旧私有引用
（updateVisible 是操作尾声，之后即 destroyCaches/落 chunk）。

## 3. 验证链

- 单测 `PapoSWMRSharedNibbleTest`（11 项，注册进 PapoRegressionTestSuite）：单例读面
  出生恒等（含 save/vanilla 转换）、9 个变更器全部 loud canary 且失败后单例完好、
  clone 私有化后全变更编排可用而单例冻结、papoFromSave 映射+畸形验证保留、
  fromVanilla 冻结路径共享、getFilledEmptyLight 出生即共享（LevelHeightAccessor.create
  假世界，26 节全 assertSame）、定居交换契约 4 项（提升壳→UNINIT 单例、满亮发布→
  INIT_FULL 单例、谓词独占态（INIT-uniform-0/载荷）留私有、INIT_FULL 完整契约+
  clone 首写围绕 uniform 材质化）。注意 `SharedConstants.tryDetectVersion()` 必须
  先于 `Bootstrap.bootStrap()`（漏它会把 BuiltInRegistries 留在 init-ERROR 态并级联
  毒化整个套件 JVM——本批判例）。
- 全链：内 commit（5 文件）→ rebuildPatches **--rerun-tasks**（0276）→
  applyPatches 重放（内部仓库 282 提交，HEAD=Papo: batch 158）→
  check_patch_counts ALL OK → compileJava --rerun-tasks → test 六套件全绿 →
  jar 0.91.0。
- 构建系统判例：**rebuildPatches 与 applyPatches 不得链式单调用**——两任务间存在
  未声明的输出依赖，Gradle 以 UP-TO-DATE 跳过重生成时，applyPatches 会把内部仓库
  重置到上游并中断重放（编译期爆发全库"找不到符号"）。恢复=单独 applyPatches 完整
  重放；修复必须 `--rerun-tasks` 强制重生成补丁。
- 中间迭代证据（定居交换的必要性实证）：仅出生点收敛+写路径 COW 的第一版 0.91.0
  浸泡 B 腿 census：SWMRNibbleArray 539,604→**308,078（−43%）**——出生即冻结壳已
  塌缩，但 `setNonNull`/`setFull` 提升后定居在冻结等值态的私有壳（UNINIT 空、
  INIT 满亮）仍占 308k×32B≈9.9MB；定居交换正是收割这部分，最终数字见 §4/§5。

## 4. 测量（同会话配对浸泡 A-090 vs B-091，v1→v4 四次迭代）

**迭代证据链**（每轮配对浸泡即回归门，canary 在 v3 抓到 4 个漏网写点后 v4 全绿）：

| 版本 | 机制 | B 腿 SWMR 实例 | 门 |
|---|---|---|---|
| A-090 基线 | 无（0.90.0） | 539,604 / 542,048 / 414,908（三腿，随机窗） | 绿×3 |
| v1+v2 | 出生点收敛+写路径 COW+定居交换 | 308,078 | 绿（−43%） |
| v3 | +population sweep+载入规范化 | **崩溃**：canary 抓到 performLightIncrease/Decrease 4 个直接 `.set` 站点（grep 判例） | 红 |
| v4 | +4 传播写点惰性 COW | **77,004** | 绿×2（−81%） |

**v4 终版三对配对（同会话，双门全绿，机器负载窗 ±2.5%~13% 漂移践踏 ±1% 配对门）**：

| 对 | A 堆@区块 | B 堆@区块 | B/A 每区块堆 | B/A 每区块 SWMR |
|---|---|---|---|---|
| 4 | 538,831KB@4,327 | 526,399KB@4,218（−2.52%） | +0.2%（窗口噪声吞没） | **−81%**（95.9→18.3） |
| 5 | 557,491KB@4,448 | 529,743KB@4,331（−2.63%） | **−3.0%**（125.34→122.32KB） | **−80%**（95.2→18.6） |
| 6 | 547,175KB@4,361 | 580,420KB@4,947（**+13.4%**，保守向） | **−6.5%**（125.47→117.30KB） | **−82%**（95.8→17.7） |

区块 ±1% 配对门在六对中零通过——本会话后段机器负载窗剧烈不稳（同一二进制区块数
4.2k~6.1k 摆动）。按 R8 批次 148 先例（"−21.1MB（区块调整）"）双口径收口：

**census 主口径（类直方图，机器无关，三对独立复验）**：SWMRNibbleArray 每区块
**95.x→18.x 实例（−80~−82%）**，类字节 **−10.7~−11.0MB**（13.3MB→2.5MB）；A 侧
415k~423k、B 侧 77k~88k，跨对恒稳。byte[2048] 载荷侧三对不变（真实光照数据零影响）。

**区块调整辅助口径**：每区块全堆 −3.0%（中位；三对 +0.2%/−3.0%/−6.5%，后两对
方向一致且第六对为保守向——B 多载 13.4% 区块仍每区块更轻）。

## 5. census 归因闭合（SWMRStateCensus 状态普查）

新工具 `benchmark/src/papo/bot/SWMRStateCensus.java`（HPROF 行走复制 ShellRefAttr
骨架 + CLASS_DUMP 实例字段布局解析 + 逐实例状态组合计数）：

**v2 中间版 B-091（308,078 私有壳）的状态分布**——定居交换必要性的直接实证：

| 数量 | 状态 | 判定 |
|---|---|---|
| 175,499 | NULL+空存储+clean | 值恒等 PAPO_SHARED_NULL，可坍缩 |
| 90,632 | UNINIT+空存储+clean | 值恒等 PAPO_SHARED_UNINIT，可坍缩 |
| 30,421 | INIT+真实载荷 | 不可约（与 byte[2048] census 30,276 吻合） |
| 3,084 | INIT+uniform-0 | 谓词独占（isInitialised* 读 true），刻意私有 |
| 7,617 | INIT+uniform-15 | 值恒等 PAPO_SHARED_INIT_FULL，可坍缩 |

泄漏机制三条：epilogue 定居只访 dirty 槽而 `extrudeLower→setUninitialised` 直出
clean UNINIT；`rewriteNibbleCacheForSkylight` 逐节发布在定居点之外；存档往返把
INIT_FULL 材质化再载回私有——分别由 population sweep（灌入点全扫）与载入路径
all-full 规范化收口。

**v4 终版 B-091（77,005）残差**：21,093 载荷 + 2,047 INIT-0 = **23,140 判不可约
地板**；其余 53,865 为清洁私有壳的 **sweep 滞后**（population sweep 仅在 chunk
参与光照操作时触发，静默 chunk 的壳值恒等但未坍缩；持续游戏即收敛至地板）。
save 侧清扫已评估并放弃：与光照线程对 updating 字段存在竞态，收益 ~1.6MB 不值
红线风险。

## 6. 判例

- **"壳/载荷比"是新的一类收割信号**：载荷已收敛（批 148 uniform）后，实例直方图
  的壳数量本身成为下一个质量；类直方图 × census 交叉定位"生而冻结"出生点。
- **状态普查先于机制猜测**：v2 无效（两版 B 计数相同）后没有继续推测，而是写工具
  直测 308k 的 (state, storage, uniform, dirty) 分布——三条泄漏机制一次全部显形，
  v3 的三个修复全部有 census 行级依据。
- **canary 是共享设计的验证器**：v3 把共享壳首次放进传播路径，canary 立即抓到 4 个
  漏网 `.set` 站点——站点普查 grep 的变量名大小写陷阱（`nibble\.set(` 漏
  `currentNibble.set(`）被运行时验证器兜住。**写面枚举必须 grep `\.set(` 全形态再按
  接收者类型过滤**。
- **三类持有者统一于"喂给缓存的数组"**：COW 写回不需要枚举持有者种类，只需要
  在缓存灌入点记录来源——比按持有者类型分叉的写回简单且更不易漏。
- **updateVisible 与变更器的 canary 区别**：合法调用只读方法于共享实例是共享设计
  的常态，canary 只放在真正写 this 的方法上；否则正确路径会自炸。
- **单写者探针不得漂移线程**：population sweep 只在光照线程的灌入点跑；save/main
  线程清扫 updating 字段=竞态，宁可留 sweep 滞后（值恒等零功能差）。
- rebuildPatches/applyPatches 链式调用禁令 + `--rerun-tasks` 强制重生成（§3）。
- 测量口径：区块 ±1% 门被负载窗践踏时，R8 先例（区块调整）+ 类级 census 主口径
  双呈现；census 是机器无关的硬归因。

## 7. 残余与方向

- SWMRNibbleArray 剩余实例=真实载荷节 + 变更私有壳（预期 ~30-60k，见 §5），
  由光照真实多样性构成，无结构重复可收。
- 用户裁定终局（2026-10-10）维持：palette 变长编码、光照 nibble 变长表示
  （载荷侧）均不做。
- [D 13.5MB / [I 侧：世界生成瞬态（NoiseChunk$FlatCache 等）+弱归因，非稳态主角，
  不入收割面。
