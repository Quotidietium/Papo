# 批次 125（2026-09-05）：粉目标强度单遍合并扫描 + 评估冗余勘察（0.73.0 → 0.74.0）

## 交付

### 0258 — DefaultRedstoneWireEvaluator.calculateTargetStrength 单遍合并扫描

原两遍：getBlockSignal（0255 特化拉取：六向信号 + 导体 getDirectSignalTo 扇出，
shouldSignal=false 期粉零派化）→ 若 <15 再 getIncomingWireSignal（水平同层粉 +
导体规则上下变体粉，max-1）。合并为单遍六向扫描：

- 同一方向的状态只读一次（水平邻原两遍各读一次）；
- isRedstoneConductor（含碰撞形状查询）水平向原两遍各算一次，现复用；
- **wireMax 只喂 vanilla incoming 认的位置**（水平同层 + 变体位；UP/DOWN 竖列
  粉不计入——原 incoming 不读竖列，这是合并最容易写错的一个坑，定向用例覆盖）；
- 导体内层扇出只喂信号侧（其粉读在 vanilla 被 flag 归零，不得抬 wireMax）；
- 方向序（DOWN,UP,N,S,W,E）与 15 早退结构逐行复刻（15 命中即返，跳过后续
  incoming 读——与原两遍短路一致）。

**等价性**：WireTargetMergeBench **100 万随机邻域对拍 before==after 全等** +
定向用例（竖列粉不计 / 水平粉 7→6 / 15 早退）；构造性事实：每评估省 ~5 次位置
读 + 4 次 isRedstoneConductor 重复计算。

**JMH**：模型 before 32569±4482 vs after 33046±6906 ns/512 世界——统计不可区分
（共享机噪声 ±15-21%；且模型世界为直读数组，不体现真实 getBlockState 的区块
节段成本，模型的成本分辨率不足以分辨 ~10% 的读取削减）。**机制保留**
（0230 先例：等价已证+机制 sound+无观察劣化；宏基准为成本仲裁但当前环境
不可分辨——见下）。

### 0259 — 粉评估冗余勘察探针（rs.wireEvalRuns / rs.wireEvalNoChange）

**勘察结论（宏腿实测，尾 1/4 窗中位）：wireEvalRuns 30870/tick，
wireEvalNoChange 27122/tick —— 87.9% 的粉评估结果无变化**（纯计算、零副作用——
不变路径不 setBlock 不发事件不扇出）。这为"输入脏追踪"轮确立了上限：理论上
可消除 ~88% 的评估计算（环振荡负载形态）。后续轮次设计：邻域版本追踪（粒度
候选：区块节段版本），跳过的必须仅限"输入未变"的评估（不变评估无任何可观测
效应，构造性等价）。

### 放弃路线留档（survey 产物）

**粉 POWER-only setBlock 形状扇出跳过（UPDATE_KNOWN_SHAPE 旗标技巧）**——
否决：CoralBlock.updateShape 在无水时**消耗世界随机流**（`60+random.nextInt(40)`
的珊瑚死亡延迟）且 schedule 侧效应与 neighborState 无关地发生在每次形状更新上；
无条件跳过会移位共享随机序列（可观测分歧）。107 个 updateShape 覆写的
neighborState 使用全量排查完成（bed OCCUPIED / chest TYPE / door HALF 均有
类型守卫，粉状态走 false 分支；唯 CoralBlock 随机流构成严格性孔洞）。判例：
**"纯函数性"论证必须覆盖随机流消耗与 schedule 侧效应，107 覆写面逐一排查是
必要成本**。

## 宏基准（含环境漂移判例）

b125 腿中位 28250（n=20）vs **同时窗 b123 对照腿 27195**（n=20）——名义 +3.9%
落在噪声带（b125 max 45972 单窗离群）。**判例（本批最重要产出）**：机器在
~01:15 进入持续慢窗（共租负载），b123 同一二进制原窗 23043 → 现窗 27195
（+18%）；批次 124 的"−20~26% 劣化"据此修正为环境漂移主导（其报告已加修正头）。
**规则：宏 A/B 判定必须附带同时窗对照腿；跨时段双腿对比作废。**

批次 123 的 −19.8% 方向安全：两腿均在原窗内（若环境变慢只会低估收益）。

在场/活动/错误门：双腿全 PASS（441.0/tick 精确，0 错误，exit 0）。

## 验证矩阵

- compileJava 全绿；全量 applyPatches（0253-0259 链）BUILD SUCCESSFUL；
- WireTargetMergeBench 自检 1M 随机对拍 ALL OK；JMH 原始数据入库；
- 宏双腿（b125 + b123 对照）门全 PASS。
