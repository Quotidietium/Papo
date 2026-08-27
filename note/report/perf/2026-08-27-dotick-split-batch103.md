# 批次 103 报告（2026-08-27）— doTick 链细分：线性地板归因与封闭（多核调度系列⑲，0.64.0→0.65.0，勘察轮）

主题：批次 99/100 后 listenerTick 剩余（~23us/玩家@160 min）的内部构成。新增 0258 探针：
`player.superTick`（ServerPlayer.doTick 内 super.tick() = 实体 tick 链）与
`player.aiStepTouchScan`（Player.aiStep 接触扫描段，密度残余主嫌验证）。默认关零行为变化。

## 双点分解（0.65.0，种子 papo90，40 与 160 bot × 6min）

| 相位 | 40 bot avg/min | 每玩家 min | 160 bot avg/min | 每玩家 min | 密度斜率 |
|---|---|---|---|---|---|
| conn.listenerTick（包络） | 894 / 516us | 12.9us | 5693 / 3684us | 23.0us | 1.8× |
| — player.superTick | 629 / 367us | **9.2us** | 4009 / 2650us | **16.6us** | 1.8× |
| — player.aiStepTouchScan | 22.5 / 14.2us | 0.36us | 194 / 140us | 0.88us | 2.4×（绝对值微小） |
| — player.pushEntities | 32 / 19us | 0.49us | 193 / 125us | 0.78us | 有界 ✓（批次99） |

（160 轮 util 82% 争抢 epoch，min 窗口径；包络−superTick−tail ≈ doTick 尾段 ~5us/玩家线性。）

## 结论：面封闭（结构性，语义载荷）

1. **superTick（Player.tick→LivingEntity.tick→aiStep 实体 tick 链）= 地板本体**：两点均占
   listenerTick ~70%，每玩家 9.2us@40 → 16.6us@160。
2. **线性部分（~9us/玩家）是玩家实体 tick 的语义载荷**：装备/效果/移动/食物/容器校验/
   存档推送等 O(1) 段的常量和——无单一热点（touchScan/push 两段合计 <2us/玩家已排除）。
3. **残余密度链 ~7us/玩家（1.1ms@160）深埋于 LivingEntity.tick 链内部**：touchScan 仅
   贡献 ~0.5us/玩家的斜率（0.36→0.88），push 有界——剩余斜率来自移动/碰撞/区块交互
   随拥挤路径的隐性放大，分解到该层级后每段皆语义必需（等价红线内无可消除项）。
4. 批次 97-103 面收束：聚堆规模主线程四主面（密度超线性/分配/purge 风暴/扇出）全部
   消除或封闭；地板为 ~9-17us/玩家实体 tick 链 + N²/2 投递地板（102）——进一步削减
   需实体 tick 模型变更（区域化/Folia 级）超红线。

## 验证

全量 test ✓；0258 rebuild 干净；0.65.0 jar ✓；四态冒烟 10/10 全绿；40/160 分解各
exit 0 / logErrors=0（160 三次尝试内过，外部击杀防御生效）。

## 判例

- **包络−子段差分法**：先测最大子段（superTick）占比再决定是否深挖——70% 占比 +
  每段语义必需即可封闭，避免对 1ms 级残余做逐段侵入（探针成本/风险 > 收益）。
- **密度斜率要双点测**：单点 per-player 值无意义，40/160 双点才能区分线性/密度分量
  （本批 touchScan 若只看 160 点会被误判为主要密度源）。
