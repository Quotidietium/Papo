# R32（批次 173）：批 172 MSPT 守护+稳态普查复验→palette 重编码数组消面收割（→0.98.0）

日期：2026-10-12 ｜ 轮次：R32 ｜ 判定：**收割**（0.97.0 → 0.98.0）+ 守护闭环

## 红线轴：批 172（PackedData 别名）MSPT 守护

0.96.0 vs 0.97.0 主序+倒序+钉年轻代（-XX:NewSize=MaxNewSize=1G）三对：地板指标
（win5s=min）九槽符号乱翻（主序 B≤A 2/3、倒序 B 更优 2/3、钉死 1/3），**方向不复现
=当日噪声地板主导**（单腿槽间摆幅 7→15ms 远超腿间差）。机械论证：批 172 改动全在
codec 解码（IO 线程），不在 tick 路径；R31 已证 0.94→0.96 双序 B≤A。**零回归结论
成立**（无可复现回退、无机械通道）。判例强化：符号翻转跨序=噪声特征（R24 判例 #5
的"+0.34ms 两序复现"才是真回归特征，二者必须区分）。

## 稳态普查复验（0.97.0，R24 方法论：同 seed 流送窗 JFR 60s 计数口径）

6,347 样本。维持判负：Ap2.fillArray 805 trips（R24 §8 数值管线——噪声采样核心本体）、
NoiseChunk.wrap/mapAll 族（per-chunk Marker 绑定架构）、XoroshiroRandomSource（R24
判例 #4 种子派生绝对红线）、Climate/Placement/Ore 散布小面。**新晋面：
`PalettedContainer.reencodeContents` 316 trips/429.1MB（窗 5.2%）**——帧级归因：
**316/316 全部经 `pack()`**（区块保存序列化，CompoundTag.store ← 自动保存/卸载），
即 palette 重编码的 `int[entryCount]`（区块 section 16KB/次）中间物化，唯一消费
者是打包循环。

## 收割（补丁 0284）

- `SimpleBitStorage(bits, size, IntUnaryOperator)`：函数式打包构造器，循环与 int[]
  构造器**逐行同形**（仅每格 id 来源不同）。
- `papoPopulateAscending`：阶段 1 升序 RLE memo 走查，发出与旧 reencodeContents
  循环**逐次相同**的 idFor 调用序列——这固定 palette 的 id 分配（升序首现序），
  即线字节恒等的关键。
- `papoReencodedStorage`：阶段 2 memo 闭包直接打包（此时 idFor 全命中、序无关）。
- 旧数组管线留树为包私有测试 oracle。

**两次自查纠偏（本轮最有价值的部分）**：①首版把 pack() 的急切 reencodeContents 删
掉后 configurationForPaletteSize 在**空 palette**上计算→bits=0→无 data 字段→解析
崩（PackedDataAliasTest 红灯捕获）；②打包循环按 long 内**降序**读格——朴素函数式
重打包会改变首现序→**palette id 分配变→线字节变**（保存文件与 vanilla 分叉=红线）。
两阶段设计同时解决两者。勘误：memo 计数断言的期望须按**新存储**的 valuesPerLong
模拟访问序（由被打包 ctor 的循环决定）。

## 验证

- **恒等单测 3 件**（`PapoReencodeIdentityTest`）：①bits∈{4,5,8}×{5,6,9} 随机
  run-交织存储——函数路径 longs 与数组 oracle 逐位相等 + memo 触发数=打包访问序
  精确期望；②ZeroBitStorage 源（单 run）；③**pack() 线字节 oracle**——同容器上
  新 pack() 的 storage/paletteEntries 与旧数组管线完全一致（id 分配序铁证）。
  全绿；全套 EXIT=0；rebuild/apply/compile 全 EXIT=0；0.98.0 出包。
- **普查配对腿**（同 seed 流送窗）：A=0.97.0 旧面 **316 trips/429.1MB → B=0.98.0
  0 trips/0MB（面消灭）**；替代路径（papoReencodedStorage/papoPopulateAscending）
  自身零采样分配。门双绿（logErrors=0）。

## 判例

1. **线格式是 id 分配序的函数**：任何"边映射边注册"的重编码，其消费序即注册序即
   线字节——消中间数组必须两阶段（先按旧序填充、再序无关打包）。
2. **急切求值的隐藏依赖**：删除"只为结果数组"的调用时，先查它的副作用（palette
   填充）是否被下游读取——本例被 configurationForPaletteSize 读取。
3. 符号翻转跨序=噪声，两序同号=信号（R24 #5 的判别式补全）。

内存账（同 seed 流送窗）：reencodeContents 面 429.1MB→0；驻留无变化（纯瞬态）。
版本 0.98.0。
