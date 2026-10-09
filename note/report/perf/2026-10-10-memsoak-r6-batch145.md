# R6 轮1（批次145）：常量容器载荷消除——ZeroBitStorage 共享 + PapoPosTypeSet 惰性（0.83.0 → 0.84.0）

- 日期：2026-10-10
- 版本：0.83.0 → 0.84.0（补丁 0269）
- 前置：批次144 收束 R5 后，其归因工具链继续扩展（[J/[Object 三类归因 + String 重复值
  普查 + 均匀 nibble 普查），在"已收束"画像上发现两个安全可回收项与一个下轮角度
  ——内存主线以 R6 名义延续（R5 的判例全部维持，无推翻）。

## 0. 摘要

轮4 工具扩展后对 0.83.0 转储（982MB）完成 [J / [Ljava.lang.Object; / String / 均匀
nibble 四项普查，全部残余驻留逐簇定性。落地两个安全回收项（0269）：

1. **ZeroBitStorage 按尺寸共享**：431k（本腿 570k）实例 → 每 entryCount 一个共享
   实例。对象构造后不可变（final size、无任何方法写字段、copy() 本就返回 this），
   全库无 BitStorage 身份比较，共享在 vanilla 语义内。
2. **PapoPosTypeSet 惰性分配**（批次123 结构改造）：自然地形多数区块永不调度刻，
   急切 long[16]+Object[16]（192B/容器）改为首次 add 才分配；remove/contains 空
   态短路，clear 空态 no-op。

浸泡两腿（12 bot、径向 150s + 驻留 180s、测量点 dwell+120s、门全绿、6,064 vs
6,089 区块配对）：**堆 876,325 → 869,221 KB = −6.94MB（−0.81%）**；ZeroBitStorage
从直方图 top-20（570,518 实例 / 9.1MB）**跌出 top-25**——降额与该簇精确归因
（PapoPosTypeSet 惰性收益在本规模被区块数噪声掩盖，方向与 [J −4k 数组一致）。

## 1. 普查数据（轮4 工具扩展，同一 982MB 转储）

### 1.1 [J 活跃成分（129.8MB，509,423 个，归因 99.95%）

| 引用者 | ×8 字节 | 数组数 | 定性 |
|---|---:|---:|---|
| SimpleBitStorage（方块 palette n≥256：36,619×256L=75.2MB 主项；高度图 n=37：20,554 个；生物群系 n≤2） | 98.3MB | 180,403 | 区块内容本体，红线 |
| java.util.BitSet（碰撞簇） | 8.5MB | 156,636 | boot 常量（批143 判例维持） |
| PapoPosTypeSet | 6.0MB | 8,674 | 5,851/8,674 处于最小容量（有内容或删后不缩）；**本轮惰性化** |
| fastutil Linked map link 数组 | 4.2MB | 7,986 | =ChunkHolder 数，随内容伸缩，活 |
| Sky/BlockStarLightEngine 队列 | 3.1MB | 48 | 引擎工作态，活 |
| Aquifer/Long2Int（=308 残留 NoiseChunk） | 1.5MB | 628 | 0268 已界定 |
| 长尾（缓存/队列/证书等） | ~8MB | ~300k | 活 |

### 1.2 [Ljava.lang.Object;（85.5MB，1,118,459 个，按引用类高度碎片化）

最大单持有者 = PalettedContainer$Data 的 moonrise$palette T[] 缓存（576,392 个，
尺寸直方：1 元素×420,382 / 2×91,964 / 4×22,796 / 16×35,633 / 41×5,064…），其余为
ArrayList/fastutil 映射/palette 值表长尾——无单一可回收簇。

### 1.3 String 重复值普查（轮6 立项依据）

386,053 实例 / 202,349 个不同值 / **183,704 个冗余副本（47.6%）≈ −7~9MB**。重复
Top 自曝语义：GameProfile NBT 键（Name 6,962 / Properties 6,660）、布尔字面量
（true/false 8,979）、方块状态属性值（north/west/south/east 12,556、up 2,500、
facing 2,499）、进度判据名（has_the_recipe 2,916，玩家缩放）、拼图结构 NBT 字段
（pool/joint/final_state/minecraft:jigsaw/target 等 ~8.5k）、命名空间
（minecraft 7,597）——全部为反序列化反复重建的常量串。规范化（按值驻留）属独立
完整角度，留作 R6 轮2：需 NBT 读取管线选择点 + String 身份语义审计
（equals 处处值语义，`==` 面需 grep 实证）。

### 1.4 均匀光照 nibble 普查（判例归档）

byte[2048] 共 29,564 个，**均匀 7,733 个（26.2%：全 15 天光 5,596 + 全 0
2,137）= 15.8MB**。均匀段表示（uniform 状态 + 单字节值）需改造 SWMRNibbleArray
状态机与全部并发读路径（VarHandle 可见性协议、storageVisible 消费者 ~30 处）——
光照正确性红线区，15.8MB（~1.8%）不值该风险，**判例：均匀 nibble 压缩出局，
量化数据入库供未来光照引擎重写时参考**。

## 2. 0269 实现

### 2.1 ZeroBitStorage 共享

```java
// ZeroBitStorage.java
private static final ConcurrentHashMap<Integer, ZeroBitStorage> PAP0_SHARED = ...;
public static ZeroBitStorage papoShared(int size) {
    return PAP0_SHARED.computeIfAbsent(size, ZeroBitStorage::new);
}
```

两个构造点（createOrReuseData 与 codec unpack）改走 papoShared。安全性论证：
构造后不可变（final int size；get/set/getAndSet/unpack/getAll 全为 size 派生或
no-op；getRaw() 返回静态共享空数组）；`copy()` 返回 this——vanilla 自身已按可共
享对待；全库 grep 无 BitStorage 身份比较；entryCount 实际取值仅 4096（方块
section）/64（生物群系 section）两档，缓存容量天然有界。

### 2.2 PapoPosTypeSet 惰性

`keys/types` 初始 null，首次 add 分配；contains/remove 空态返回 false；clear 空态
no-op；分配后路径逐字节未动（probing/resize/backward-shift 删除原样）。调用面
（LevelChunkTicks）无数组逃逸，透明。

## 3. 验证链

- 差分测试：PapoPosTypeSetLazyTest（空态语义、首 add 分配与复用、引用相等类型
  语义、20 万随机指令 HashSet 模型对拍、超容量 resize 交织删除）5/5 +
  PapoZeroBitStorageSharedTest（按尺寸同实例、访问器语义、copy 恒等）3/3——经
  新增 PapoRegressionTestSuite 聚合（paper-server 测试任务只收 \*TestSuite，bukkit
  suite 的 SelectPackages 不含 net.minecraft 树）。
- 途中发现并修正：对拍首版断言未对齐两侧返回值约定（HashSet.add=新插入 true vs
  PapoPosTypeSet.add=已存在 true）——批次142 判例重演，op=0 即假分歧；独立对拍器
  证实原版与惰性版同种子 200k 指令逐位一致后修正断言。
- rebuildPatches 0269 生成 + applyPatches 全量重放 EXIT=0 + compileJava
  --rerun-tasks + :paper-server:test 全量 BUILD SUCCESSFUL + check_patch_counts
  ALL OK。
- 浸泡两腿（§0）门全绿，机制指标（ZeroBitStorage 跌出 top-25）与堆降额归因闭合。

## 4. R6 账本

| 轮 | 批次 | 手段 | 单轮堆降 |
|---|---|---|---:|
| 1 | 145 / 0269 | 常量容器载荷消除（ZeroBitStorage 共享 + PosTypeSet 惰性） | −6.94MB（−0.81%） |
| 2（计划） | | String 反序列化规范化（47.6% 重复） | 预期 −7~9MB |

R5 判例全部维持（均匀 nibble、rawPalette 一致性机制、SingleValuePalette 突变、
palette 位宽预算外——本轮普查以数据加固了前三者的量化边界）。

## 5. 判例（轮1 新增）

1. **对拍两侧返回值约定必须显式对齐**（142 判例重演）：HashSet.add 与
   PapoPosTypeSet.add 的 true 含义相反，未对齐时 op=0 即假分歧——任何差分对拍先
   写一行"两侧约定对照"再写断言。
2. **paper-server 测试任务只收 \*TestSuite 类**：net.minecraft 树（Papo 补丁类的
   天然居所）不在 bukkit/paper suite 的 SelectPackages 内——Papo 回归测试须自带
   聚合 suite。
3. **"不可变 + copy() 返回 this"即可共享**：以对象自身契约（无突变面）而非调用
   面审计判定共享安全，ZeroBitStorage 类零调用点改动完成 570k→2 实例。
4. **收束后工具增量可再开新线**：R5 的收束判例不被推翻，但轮4 工具的后续扩展
   （三类归因/字符串普查/均匀普查）在已收束画像上发现新安全项——收束的是"当时的
   画像"，不是"优化的可能性"；新数据 = 新轮次的正当性。

## 6. 复现

```
benchmark$ bash memsoak-r5legs.sh          # 0.83.0 vs 0.84.0 配对两腿
benchmark$ /f/Java/21/bin/java -Xmx6g -cp build/classes papo.bot.HeapRefAttr \
    results/memsoak-<ts>/heap.hprof        # 含 [J/[Object 归因 + String/nibble 普查
```

工件：0269 补丁、PapoPosTypeSetLazyTest / PapoZeroBitStorageSharedTest /
PapoRegressionTestSuite、HeapRefAttr（三类归因 + 双普查）、memsoak-r5legs.sh、
Papo-1.21.11-0.84.0.jar。
