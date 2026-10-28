# R8（批次148）：SWMRNibbleArray 紧凑 uniform 表示——重开批144 判例（0.85.0 → 0.86.0）

- 日期：2026-10-10
- 版本：0.85.0 → 0.86.0（补丁 0271）
- 前置：批次147 后 0.85.0 树的配对浸泡 + 转储普查（r7 报告）；本腿 A-085 基线
  817.4MB（与 r7 会话 B-085 腿 831.6MB 同二进制不同会话，绝对值含 ±2% 环境漂移
  ——跨会话绝对值不可直接比，同会话配对差才是可信值，批146 判例维持）。

## 0. 摘要

重基线普查中光照 nibble 的均匀段（全 0 / 全 15 的 byte[2048]）10,737 个 = 22.0MB，
占堆 2.65%——批144 否决时的算术基础（15.8MB/1.8%）已翻倍。本轮对 441 行的
SWMRNibbleArray 全类审计后**重开该判例并落地**（0271）：存储字段全私有、所有
读写经类方法路由，写路径天然满足换新/拷贝纪律，表示层改造不触碰并发读协议。
一个 nibble 的整个 4096-nibble 载荷为 0 或 15（天空引擎在高度图以上产出的全亮
节、沉降为无光源的方块光节）时，**两个存储数组均保持 null**，值承载于跟随
SWMR 同款纪律的 uniform 字段（writer 私有 updatingUniform + volatile
visibleUniform；不变量：uniform ≠ 0 ⇒ storage == null ∧ state ∈ {INIT, HIDDEN}）。

**配对浸泡（12 bot、6,086 vs 6,145 区块、门全绿，配对差 +0.97% 在界内）：堆
817,354 → 801,373 KB = −15.98MB（−1.96%）**；B 腿多 59 区块（×86KB ≈ +5.1MB），
区块调整后净效应 ≈ **−21.1MB（−2.5%）**。转储普查归因精确闭合：
**SWMRNibbleArray byte[] 84.15MB/41,089 → 62.36MB/30,449（−21.79MB）**，均匀
数组 **10,836 → 4 个**（残 4 = 转储瞬间未发布/紧缩中的瞬态），非均匀载荷逐块
率不变（4.95/块），long[] 仅 +0.87MB（= 59 区块增量），String/容器实例持平
（内容面零触碰）。

## 1. 判例重开的两个前提

1. **算术基础变化**：批144 否决时均匀段 15.8MB/1.8%（836.6MB 堆）；r7 重基线后
   22.0MB/2.65%（831.6MB 堆）——绝对量 +39%，相对量 +47%，已越过"结构性改造
   不值得"的阈值。
2. **审计结论反转**：批144 判例的前提是"并发读协议改造"（共享 byte[] 须跨实例
   共享数组，读侧协议不安全）。本轮审计确认两个存储字段均为私有且**所有**读写
   都经类方法路由——表示可以是"无数组"而不必是"共享数组"，SWMR 写者/读者
   纪律原样保留，无需触碰读协议。

## 2. 设计（0271）

### 2.1 uniform 表示与 SWMR 纪律

| 载点 | 字段 | 纪律 |
|---|---|---|
| 写者侧 | `byte updatingUniform`（plain） | 仅写者线程触碰，与 storageUpdating 同款 |
| 读者侧 | `volatile byte visibleUniform` | 与 storageVisible 同款 volatile 发布 |

不变量：`uniform ≠ 0 ⇒ storage == null ∧ state ∈ {INIT, HIDDEN}`；存储物化
（首写/extrude 拷贝/保存转换）时 uniform 归零——null 存储的 UNINIT/NULL nibble
经同一表达式读出 0。载荷是打包格式（2 nibble/字节）：uniform u 物化为
`(byte)(u | u << 4)`（uniform 15 → 0xFF）。

### 2.2 六个值等价缝

1. **读者**：getUpdating/getVisible 在 null 存储时返回 uniform（不变量保证
   UNINIT/NULL 读 0）。
2. **翻转**：setFull/setZero 在干净 null 存储上直接翻 uniform 不分配（真值变化
   置 dirty——state 字段在翻转中不变，发布不能靠 state 差异触发，这是否决
   "只看 state"设计的点）；数组路径原样保留。
3. **首写物化**：swapUpdatingAndMarkDirty 的 null 分支按 uniform 打包填充——
   全亮节的传播边缘写一个 nibble 时其余 4095 个 15 全部保留。
4. **发布紧缩**：updateVisible 对数组载荷做 isAllZero/isAllFull 扫描（256 个
   long，比它可替代的 2048 字节拷贝便宜），沉降数组释放回池并发布 uniform；
   uniform INIT/HIDDEN 无数组发布。
5. **extrude**：null 存储源（UNINIT/NULL 或 uniform）解析为 setFull/setZero/
   setUninitialised——调用方先 setNonNull()，状态编排与数组路径相等。
6. **vanilla/保存恒等**：toVanillaNibble 与 getSaveState 对 uniform 物化回数组
   （uniform-0 映射既有"全零→UNINIT 保存规则"），**盘上格式与跨版本世界兼容
   字节恒等**；盘载双构造器（SaveUtil 形态 + fromVanilla 形态）把全 0xFF 载荷
   规范化回 uniform（全零载荷保留物化形——INIT-with-zero 与 UNINIT 保持引擎
   可区分）。

### 2.3 出航前差分对拍抓到的两个写者协议洞（已修）

随机差分（200 轮 × 500 操作 vs 物化数组镜像模型）在出航前抓到：**uniform 翻转
留下 dirty=true 且 storage=null 的状态**，而 set() 只在 `!dirty` 时物化
（跳过物化写 null 数组 = NPE）、extrudeLower 的 dirty 复用分支同样取到 null。
修复：三处守卫统一为"存储缺失即物化"——set() 与 swapUpdatingAndMarkDirty 的
提前返回放宽为 `dirty && storage != null`（null+dirty 的翻转态物化出 uniform
打包填充）；extrudeLower 物化分支对翻转态保留其 INIT/HIDDEN state（仅干净 null
存储的 UNINIT/NULL 提升为 INIT，与原语义一致；extrude 的 16 次拷贝整覆数组故
无需填充）。另有 hiddenUniformRoundTrip 测试断言勘误：setHidden 只改写者侧，
发布前的 toVanillaNibble 反映上次发布态（UNINIT → 空 DataLayer）——与批148 前
行为逐位一致，测试改为如实断言。

## 3. 验证

- **单测**：PapoSWMRUniformNibbleTest **11 项**（PapoRegressionTestSuite 聚合，
  批145/147 三套件维持）：uniform-直至-首写 + 围绕 uniform 物化、翻转无 state
  变化仍发布、uniform-zero 保存语义、保存格式物化证明、盘载规范化 + 写穿透、
  uniform-full/UNINIT/uniform-zero 三态 extrude、**未发布翻转后 set/extrude
  物化**（本差分抓到的洞的确定性回归）、hidden uniform 往返（含发布前 stale
  visible 视）、vanilla 转换物化、沉降数组发布紧缩、200 轮 × 500 操作随机差分
  对拍。全绿（tests=11 failures=0 errors=0）。
- **全链**：rebuildPatches 0271 + applyPatches 重放 EXIT=0（补丁描述同步修正后
  二次重建重放）+ compileJava --rerun-tasks BUILD SUCCESSFUL + check_patch_counts
  ALL OK；0.86.0 jar 内嵌 server jar 经 javap 复核含 uniform 字段与 set() 守卫。
- **配对浸泡**（门 = 零错误 + exit 0 + 区块数 ±1%）：

| 腿 | 堆 (KB) | 区块 | 配对差 |
|---|---:|---:|---:|
| A-085 | 817,354 | 6,086 | — |
| B-086 | 801,373 | 6,145 | +0.97% ✓ |

**−15,981 KB（−1.96%）**；B 多 59 区块（×86KB ≈ +5.1MB），区块调整后净效应
≈ −21.1MB，与普查预估 22.0MB 同向闭合。

- **普查归因闭合**（B 腿 1,120MB 转储，对比 r7 B-085 腿，各按本腿区块数）：

| 指标 | r7 B-085 | r8 B-086 | Δ |
|---|---:|---:|---:|
| SWMRNibbleArray byte[] | 84,150,272 B / 41,089 | 62,359,552 B / 30,449 | **−21.79MB / −10,640** |
| 均匀 byte[2048] | 10,836（u0=3,104 uFF=7,732） | **4** | −10,832 |
| 非均匀 byte[2048]（每块率） | 30,477（4.95/块） | 30,657（4.95/块） | 持平（+59 块） |
| byte[] 总量 | 109,091,540 B | 87,263,684 B | −21.83MB ≈ 全部来自 SWMR |
| long[] | 173,933,128 B | 174,799,600 B | +0.87MB（= 59 块增量） |
| String 实例 | 388,609 | 388,350 | 持平 |

## 4. 账本与主线

- 本轮：**−15.98MB raw / −21.1MB 区块调整后（−1.96% / −2.5%）**，普查归因
  −21.79MB 精确闭合。
- 内存主线 0.80.0 → 0.86.0：1,154.3 → 801.4MB = **−352.9MB（−30.6%）**
  （各轮差为同会话配对值；跨会话绝对值含 ±2% 漂移，本会话 A-085 腿 817.4 vs
  r7 会话 B-085 腿 831.6 即其体现）。
- 残余画像：SimpleBitStorage long[] 内容 134.8MB 红线维持（比 r7 +0.87MB 为
  区块增量）；节/容器实例 ~40MB（data 字段可换性所需，容器四层状态已坍缩）；
  String 12.5MB（批146 判负关闭）；光照 nibble 非均匀载荷 62.4MB 为真实光数据。

## 5. 判例（入库）

1. **否决判例的复检以"否决前提是否还成立"为准**：批144 出局于"占比不足 +
   须改造并发读协议"；本轮占比翻倍且审计发现表示层改造（无数组）根本不经由
   当时恐惧的路径（共享数组）——两个前提双双失效即重开。判例不是永久地契。
2. **私有存储 + 全方法路由的类可做表示层替换**：当所有字段读写都被类方法
   收口，表示（数组 ↔ uniform 字段）是实现细节，等价性可在每个缝上局部证明
   （读者/翻转/物化/发布/转换/盘载六缝清单即审计模板）。
3. **紧凑表示的发布须显式 dirty**：翻转不改变 state 字段，"state 差异 ⇒ 发布"
   的推理链会静默丢发布——真值变化必须独立置 dirty（uniform 与数组两态皆然）。
4. **随机差分对拍须覆盖"中间态后继"**：本差分抓到的洞全部位于
   翻转态（dirty+null）这一**新引入的中间态**的后继操作上；对拍操作流必须
   能命中未发布中间态（本轮 set-after-flip 即最小复现）。确定性回归测试
   应与差分最小复现一一对应入库。
5. **跨会话绝对值漂移 ±2%**：同一二进制两次会话 831.6 vs 817.4MB——绝对水平
   只在同会话内可比；主线累计以"各轮配对差链"陈述并标注会话漂移。
