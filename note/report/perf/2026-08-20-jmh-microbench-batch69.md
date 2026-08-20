# 批次 69 JMH 微基准报告（2026-08-20）— 伤害/战斗管线域（0238-0240）

伤害/战斗域 survey 定案：**本 fork 事件门控模式此前未覆盖的最大高频集群**（EntityDamageEvent 族从未门控；双 Knockback 事件、PrePlayerAttack、CooldownReset、PlayerItemDamage、PlayerVelocity 第二站点全部裸奔）。本批落地六项严格等价改造；族级全量快路因 lastDamageCause 可观察性约束单独立项。环境：JDK 21.0.10（Windows 11），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 0238 — 四组战斗事件零监听器门控（本批主项）

| 事件 | 站点 | 频率 | HandlerList 实证 |
|---|---|---|---|
| PlayerAttackEntityCooldownResetEvent | LivingEntity.java:2510 | **每次玩家近战命中** | 自有表（:74）无子类 |
| PrePlayerAttackEntityEvent ×2 | Player.attack/stabAttack | **每次玩家攻击（含矛刺）** | 自有表（:87）无子类 |
| PlayerVelocityEvent（第二站点） | Player.causeExtraKnockback :1223 | 每次对 hurtMarked 玩家的攻击 | 自有表无子类（0100 已论证同构——本站点是 0100 遗漏） |
| PlayerItemDamageEvent + EntityDamageItemEvent | ItemStack.hurtAndBreak :637-653 | **每击最多 4-5 发**（护甲 4 槽+武器） | 各自有表均无子类 |

- 等价性（逐项）：零监听时 callEvent 恒真/事件恒不取消/getDamage() 返回构造值/getVelocity() 值恒等——各站点零监听分支与跳过后落点逐字一致（0100/0165 同型论证）；getAttackStrengthScale 等构造参数求值均为纯计算，跳过求值无观察面。
- 频率收益：PvE 战斗服每次近战命中省 CooldownReset 1 事件构造 + PreAttack 1 + Velocity（命中玩家时）1 + 护甲/武器 ItemDamage 最多 5 —— **每击最多 8 个事件构造 + 各自的 Craft 包装/镜像分配归零**。

## 2. 0239 — 横扫 DamageSource 副本提出循环

`doSweepAttack` 循环内逐目标 `damageSource.knownCause(ENTITY_SWEEP_ATTACK)`（每次 DamageSource.copy）——常量实参产出值恒等的不可变副本，提出循环共享一份（下游 hurtServer 只读）。每横扫目标省 1 副本；**注意** `getEnchantedDamage(...damageSource)` 仍用原 damageSource（未动）。

## 3. 0240 — EntityDamageEvent 构造器 Preconditions stream→循环（paper-api 直提交）

构造器内两条 `values().stream().allMatch(Objects::nonNull)` 校验管线（+Spliterator+lambda）改普通循环——每次伤害实例（**全族最高频事件构造器**）省 stream 管线分配。异常类型（IllegalArgumentException）与消息逐字保持（自检对拍）；内部调用方构造参数本就不可能触发（CraftEventFactory 全控两 map 的键集）。

## 基准（CombatEventGateBench，模型）

| 方法 | before (ns/op) | after (ns/op) | 备注 |
|---|---|---|---|
| before_cooldownEvent vs after_gate | 见 JMH 表（b69_jmh2） | | 门控模型：构造+纯计算+派发 vs 直落 |
| before_streamChecks vs after_loopChecks | 见 JMH 表 | | 构造器校验模型（3 键 EnumMap） |

- 自检 main ALL OK：门控三态语义（零监听=默认路径逐字一致）；构造器校验合法输入同过、null 注入两路径同拒且异常消息逐字符一致。

## 留档（survey 论证完备，单独立项或否决）

- **EntityDamageEvent 族全量快路**（每次伤害实例 ~40-50 对象：8 lambda+16 装箱 Double+2 EnumMap+事件+CraftDamageSource）：两硬约束——①事件对象被 hurtServer/actuallyHurt 逐 modifier 消费，需把 8 个 lambda 体抽为私有方法直接调用的传值通道重构；②`setLastDamageCause` 使插件可在无关事件处理器里 `getLastDamageCause()` 观察差异——严格等价方案=惰性物化 lastDamageCause（保留 nms DamageSource+9 double 原始载体，首次读取物化缓存）。**单独立项评审**（侵入 LivingEntity 主链+CraftEntity）。
- **EntityKnockbackEvent 双事件门控**：需逐分量复刻 `(cv+kb)-cv` 双精度往返 + 两张表同时为空判据（层级已实证：org.bukkit 与 io.papermc 两族各自单表）。中风险，留后续。
- **非生物伤害事件门控**（ArmorStand/ItemFrame/EndCrystal/ShulkerBullet/ItemEntity 燃烧——刷怪塔物品堆积场景每 10 tick/物品）：与族级快路共用方案。
- **否决**：仅门控 callEvent 派发（零监听派发已是空数组遍历，收益趋零）；CombatRules 标量数学（无分配）；DoubleDoubleImmutablePair 内联（虚方法签名红线）；totem/EntityResurrectEvent（低频）。

## 验证链

compileJava BUILD SUCCESSFUL → 自检 ALL OK → JMH → rebuildPatches（0238-0240）→ applyPatches → 全量 test（见 optimizations.md 批次 69 记录）。
