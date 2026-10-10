# R26（批次 167）："clear 只置零"反例族全仓清扫——判负（版本保持 0.94.0）

日期：2026-10-12　版本状态：0.94.0（审计轮，无代码变更）　方法：静态体级扫描（clear/reset 体含计数器置零且无 null/clear()/Arrays.fill 者）+ 运行时直方图交叉。

## 动机

R25 判例：BinaryHeap.clear() 只置 size 不清引用钉住 26,683 寻路节点——"循环复用结构的引用卫生"反例族在 vanilla 代码里**系统性存在**（NoiseChunk 停车/批160、SurfaceRules 分配/批165、BinaryHeap/批166 三例同族）。本轮对全仓做该模式的系统性清扫，判定族内是否还有可收位点。

## 扫描与结果

体级扫描（方法名 clear/reset + 体含 `size = 0` 类计数器置零）：

- **7 处 size-置零 clear/reset**：6 处体含 `Arrays.fill(..., null)`/`clear()`（vanilla/Paper 已正确处理——SortedArraySet 等即范例），**唯一纯置零位点 = `LocalSampleLogger.reset()`**（net/minecraft/util/debugchart/LocalSampleLogger.java:63，`this.start=0; this.size=0`）。
- LocalSampleLogger 的后备数组是 **`long[]`（原生槽位）**——置零不清无任何对象引用滞留语义，数组容量本身即 scratch（与 R25 的 [LNode; 容量壳同性质、且更小）→ **无收割面，判负**。
- 扩大名变体（invalidate/rewind/drain/truncate + count/index/head/tail 置零）扫描：无体级纯置零 + 引用数组字段的新位点。

## 运行时交叉

R24 JFR 分配普查 + R25 实体直方图 + R21 [J/[B/[Object 全归因（区块面）+ R25 实体面全表——四面运行时证据中再无未归因的容量/滞留壳。静态族清扫与运行时画像互证闭合。

## 结论与判例

**族判定：该反例族在本代码库已穷尽**——三例已收（160/165/166），一例原生无害（LocalSampleLogger），其余 6 位点 vanilla/Paper 本就正确。判例：**"clear 只置零"怀疑清单的快速分诊序**=①后备数组是原生类型→立即判负；②引用数组+读面以计数为界→恒等三件套（R25）后收割；③无后备数组→无面。零代码变更零补丁零版本 bump；0.94.0 保持。
