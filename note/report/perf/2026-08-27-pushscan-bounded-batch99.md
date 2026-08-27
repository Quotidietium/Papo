# 批次 99 报告（2026-08-27）— 有界 push 扫描：聚堆密度超线性面消除（多核调度系列⑮，0.60.0→0.61.0）

主题：批次 98 归因的 conn.listenerTick 高密度超线性（638→1837→7998us@40/120/160）主嫌疑
`LivingEntity.pushEntities()` 的 O(局部密度) 全量扫描——每玩家 AABB 查询枚举**全部**重叠者，
而消费者只用得到 (a) 按 fill 序前 `maxEntityCollisions`（默认 8）个 + (b) 两个布尔
（`size > cramming-1`、`非乘骑数 > cramming-1`，默认阈 24）。本批交付**双目标有界早停扫描**
（0254 补丁），聚堆段 O(密度)→O(界)。

## 实现（三层）

- `ChunkEntitySlices.EntityCollectionBySection.papoGetEntitiesBounded`：镜像 `getEntitiesLimited`
  的 section 扫描，加非乘骑计数与联合停止条件（`size ≥ listTarget && np ≥ npTarget`）；
- `EntityLookup.papoGetEntitiesBounded`：镜像 `getEntities` 的 region/chunk 循环，跨 chunk
  传递 npRunning 并在每 chunk 后查停止；
- `Level.papoGetEntitiesBounded`：透传 + PlatformHooks 追加（与 papoGetEntitiesInto 同构）；
- `LivingEntity.pushEntities`：`listTarget=max(MEC, cramming)`、`npTarget=cramming>0?cramming:0`；
  附 `player.pushEntities` 探针（默认关）。

## 等价性论证 + 自检

- **push 循环**：消费者只触前 MEC 个（fill 序前缀）；早停时 size ≥ listTarget ≥ MEC，前缀
  覆盖；自然耗尽时列表相同。
- **cramming 布尔**：早停仅在 np ≥ i 时发生 ⇒ 全量 np ≥ 前缀 np ≥ i > i-1，两版同真；
  若全量 np ≤ i-1 则早停不可达（np 计数单调），自然耗尽 ⇒ 相同。`size > i-1` 同理
  （listTarget ≥ i）。随机门 `nextInt(4)` 的消耗奇偶由 size 布尔决定，两版一致。
- **PlatformHooks 追加**在停止后无条件追加（与无界路径同序），上述论证对追加集合同样成立。
- **PushScanBench 自检**：随机配置（MEC∈{0,8,1..100}, cramming∈{0,24,1..30}, 乘骑率
  0-60%, 1-6 sections, 0-39 密度）× **20,000 组对拍全 PASS**（push 序列逐位、cramming
  布尔、随机消耗奇偶）。首版自检曾报"分歧"，定位为基准自身共用 Random 流的连续抽取
  伪影（两次 consume 各取下一个 nextInt 值必然不同），改同种子独立流后闭合——真实
  服务器 nextInt 取自实体自身序列，对拍的是行为对相同抽取的响应。

## 性能

- **JMH（160 密度模型，8×20 候选全 pushable 最坏形）**：before 590.1ns → after 115.5ns
  （**5.1×**）。
- **真实服务器 160 bot A/B**（同种子同 harness，-Dpapo.tickProfile=1）：

| 相位 avg/tick | 0.60.0（批次98） | 0.61.0（本批） | Δ |
|---|---|---|---|
| conn.listenerTick | 7998us（min 6385） | 5461us（min 4460） | **avg −32% / min −30%** |
| connection 总 | 8068us（min 6430） | 5535us（min 4527） | −31% |
| player.pushEntities（新增探针） | —（扫描摊在 listenerTick 内） | **135us**（min 111） | 全 160 人合计 |
| worlds | 6792us | 6768us | 持平（push 在 connection 相位） |
| 顶层合计 | 36292us | 32583us | −10%（含争抢方差） |

- 归因闭环：listenerTick 降幅（−2537us）≈ 被消除的扫描成本；剩余 135us 为有界后 push
  全成本（0.85us/人），机制证实批次 98 的密度超线性主因判定。
- 两轮分处不同争抢时段（凌晨/傍晚），min 窗口径交叉验证方向一致。

## 验证

- 全量 test ✓；0254 补丁 rebuild 干净（仅新增）；0.61.0 jar 构建成功；
- 四态冒烟 10/10 joins、exit 0、dat/stats/advancements 全 ok（push 为游戏可见行为，门必过）；
- 160 bot A/B：exit 0、logErrors=0、稳态窗口 20000±2 无滞后；
- 等价性 20,000 随机配置 PASS + 机制论证（见上）。
- 共享机环境：验证链 3 次 attempt 内通过（冒烟 1 过、160 A/B 3 过）。

## 判例

- **消费者分析先于扫描优化**：无界填充的"优化"常驻直觉是缓存/并行，但对只读前 K 个 +
  布尔的消费者，早停是零成本代数等价——先列消费者的全部可观测输出再动扫描。
- **对拍基准的随机流必须对齐**：共享 Random 连续抽取会把"同一决策的不同抽签"伪装成
  "行为分歧"；正确形态是两股同种子独立流（对齐输入，比对输出）。
- **早停等价性的充分性证明模式**：证明"早停可达 ⇒ 全量版本对应布尔必真"（单向蕴含）
  + "布尔假 ⇒ 早停不可达"（逆否），两侧闭合后无需全量对拍即得精确等价。
