# R3 审计轮（批次 120）——wire memo 族 0241-0248 事后对抗性审查 + 登录预取生命周期复核

> 日期：2026-09-01。分支 `perf/multicore-r3`，输入版本 0.71.2（补丁链至 0267），
> 产出 0.71.3（新增补丁 0268）。
> 本轮为常驻 goal 的持续审查迭代，与批次119（0260 边界/0264 预热/探针族/异步存档/
> 未信任输入/功能完整性）互补：**对批次 70-77 落盘的 wire memo 族做设计文档之外的
> 事后对抗审查**（以应用树实际代码为准，非等价性论证的复述），并复核登录预取管线
> 生命周期、brand payload 输入面与 join 静态包缓存的失效链。

## 一、审计范围与结论总表

| # | 审计面 | 方法 | 结论 |
|---|---|---|---|
| 1 | 0241/0242 区块包序列化缓存 + ChunkHolder 共享包 | 失效信号完备性逐路径推演、缓存载体可变性、跨线程可见性 | **闭合，无缺陷** |
| 2 | PapoSharedWireMemo 本体（压缩段+编码快照） | JMM 竞态、阈值/纪元撕裂读、ATTR 生命周期、无压缩器窗口 | **闭合，无缺陷** |
| 3 | 0243/0244 广播路径 memo（光照/section 更新） | 构造期快照确定性、per-viewer 差异排查 | **闭合，无缺陷** |
| 4 | 0245/0246/PapoJoinPacketCache join 静态包缓存 | recipes/tags/registry 失效链全路径核对（boot/reload/运行时 API） | **闭合，无缺陷** |
| 5 | 0247/0248/0206 实体同步跳过 + 配对包共享 | 逐分支与 vanilla 语义对照、sweep 内重入推演 | **闭合；0248 一项低风险披露** |
| 6 | memo 族逐连接编码差异面（locale/obfuscation） | ComponentSerialization 本地化路径逐行核对 + Carrier 全集扫描 | **发现 F1，已修复（0268）** |
| 7 | 0249-0251 登录预取生命周期 | 丢弃钩子覆盖、consume-once、双登录竞态、ServerPlayer 创建中断边缘 | **闭合，无缺陷** |
| 8 | 0205 brand payload 输入面 | 方向核对（服务端→客户端） | **闭合**（非输入面） |
| 9 | 功能完整性 | 配置键/系统属性消费者、0.71.2 release note 声明 vs 0267 实现 | **闭合** |

## 二、逐面记录

### 2.1 0241/0242 区块包缓存（闭合）

- **失效信号完备性**：bump 位点四处（setBlockState 变更分支/ setBlockEntity /
  removeBlockEntity 过度 bump / setBiome 覆写）覆盖全部序列化负载变更源。
  `setBlockState` 的两个早退（hasOnlyAir+isAir、同状态）均无变更发生，不 bump 正确。
  CraftBukkit 事件取消发生在 `Level.setBlock`，早于 chunk 层，不影响 bump 覆盖。
  `replaceBiomes`/`Heightmap.setRawData` 等 wholesale 路径仅在 chunk 装载（新实例，
  缓存空）时出现；CraftWorld biome 写走 `LevelChunk.setBiome` 覆写（有 bump）。
- **载体不可变性**：vanilla 构造器 `getRawData().clone()`（应用树 0133 微优化后保持），
  heightmap 数组为快照；buffer 为构造期填充、anti-Xray 关闭路径（shouldModify=false，
  缓存唯一启用域）无后续写。共享数组编码只读。
- **跨线程**：读写仅主线程；AtomicLong 覆盖 worldgen 线程经 ImposterProtoChunk 的
  写（仅存在于未满 chunk，此时无发送）。store 读版本在构造后（记录后写版本）在
  「worldgen 写满 chunk 且恰逢发送窗口」这一不存在路径下才有理论危害，维持防御性接受。
- **ChunkHolder 共享包**（0262 行区）：仅 BE-free chunk 发布共享实例；光照版本 bump
  funnel 为 `ServerChunkCache.onLightUpdate` 单点（mainThreadProcessor 队列），
  `sectionLightChanged` 头部 bump 覆盖未跟踪 chunk 早退。残余光照陈旧窗口由 vanilla
  增量光照包自愈（批次71 已论证，本轮核对 funnel 在位）。

### 2.2 PapoSharedWireMemo 本体（闭合）

- **撕裂读竞态**：`papoSegmentFor` 三段 volatile 读与并发 `papoStore` 交错时可能取到
  「T1 阈值检查 + T2 段」组合——但任何已存段都解码为同一未压缩负载（段自携带
  uncompressed-length；阈值仅决定压缩与否，非协议不变量），客户端按段内 varint 自
  解析，无协议违规。维持良性判定。
- **ATTR 生命周期**：CompressionEncoder 两分支（headroom 身份命中 `getAndSet(null)` /
  非命中 `set(null)`）均清除；段只可能由 CompressionEncoder 自身写入——「无压缩器
  窗口期 set-ATTR 无段」不构成污染源。threshold=-1 期无 CompressionEncoder 读方。
- **编码快照武装面**：`papoCreateEncodeArmed` 仅 6 类（RegistryData/SectionBlocks/
  Recipes/PlayerInfoUpdate/LightUpdate/UpdateTags）——除 PlayerInfoUpdate 外均无
  Component 字段（recipes 同步数据=propertySet+stonecutter slot displays，无组件；
  registry 走 NbtOps 数据包 codec 非本地化网络 codec）。

### 2.3 0243/0244 广播 memo（闭合）

光照增量包与 section 更新包均为构造期快照 + 同实例广播本就是 vanilla 行为
（`ChunkHolder.broadcast` 语义未改，仅 forEach→索引循环）。

### 2.4 join 静态包缓存失效链（闭合）

- recipes：`propertySets/stonecutterRecipes` 全树唯一赋值点 =
  `RecipeManager.finalizeRecipeLoading`；调用者仅 boot（MinecraftServer:528，先于任何
  join）与 reload（:2332 → 主线程 `PlayerList.reloadResources` → `reloadRecipes` 丢缓存
  重建广播）。无运行时增量 API 触达同步数据（Bukkit addRecipe 不改派生集，vanilla 同
  样不反映）。`ClientboundUpdateRecipesPacket` 发送者全树仅 PlayerList（缓存感知）。
- tags/registry：`reloadTagData` 单点换缓存并顺手丢 registry 两变体；worldgen 层
  registry 运行时不可 reload。matched/mismatched 双变体键 = KnownPack 相等性（客户端
  可控但仅作相等比较，无信任面）。

### 2.5 0247/0248/0206 实体同步（闭合 + 一项披露）

- 0247：无观众跳过仅去构造；ServerEntity 内部状态（lastSent*/teleportDelay/脏清理/
  hurtMarked/attributesToSync.clear）全保留；AndSelf 站点以 `papoHasRecipients`
  （观众非空 ∥ ServerPlayer 自身）保住自发自收。
- 0206+0247 组合：`packDirty()` 清脏位与配对时全新快照互补，无丢失面。
- 0248（披露，不修）：配对包 sweep 内共享依赖「sweep 间无实体状态变更」。但
  `addPairing` 链上的 PlayerTrackEntityEvent（有监听器时）与 `startSeenByPlayer` 可
  重入插件代码——若插件在 sweep 中途改实体（传送/装备/乘客），后续观众拿到一 tick
  陈旧配对快照。自愈上界：位置/数据脏信号下一 tick 修正；最坏绝对位置同步
  （tickCount%60）≤3s。触发需「tracking 事件监听插件 + sweep 中途变更」叠合， vanilla
  对照为逐观众新鲜构造。维持接受（收益-风险比明确，记录在案供复评）。

### 2.6 逐连接编码差异面（F1，已修复 0268）

**缺陷机制**：`ComponentSerialization.TRUSTED_STREAM_CODEC`（Paper 增补）在 encode 时走
`localizedCodec(registryFriendlyByteBuf.adventure$locale)`：
- `AdventureComponent`（插件经 adventure API 设置的组件）**无条件**经
  `PaperAdventure.localizedCodec(locale)`；
- vanilla `TranslatableContents` 在 `hasAnyTranslations()` 时同样本地化。

locale 来自每连接的 `LOCALE_ATTRIBUTE`（PacketEncoder 编码前置入 ThreadLocal →
`RegistryFriendlyByteBuf.adventure$locale`）。**vanilla/Paper 语义 = 共享实例逐连接重
编码、locale 各自正确**。

`ClientboundPlayerInfoUpdatePacket`（tab 显示名 = Component 字段）在 join 广播两处
（PlayerList:263/:280）武装了 encode+压缩双 memo——回放把首观众 locale 冻结进字节，
其余观众收到错误语言渲染且持续到显示名下次变更。触发条件：多语言服务器
（注册翻译）+ 显示名含可翻译节点 + 观众 locale 互异。非默认配置不触发（默认
`hasAnyTranslations()=false` 时编码 locale 无关，memo 行为与 0.71.2 字节一致）。

**修复（0268，3 文件 +34/−10）**：
- `PapoSharedWireMemo.Carrier` 新增 `papoLocaleDependent()`（缺省 false，契约注释）；
- `ClientboundPlayerInfoUpdatePacket` 覆写返回 true；
- `PacketEncoder` 统一计算 `papoMemoUsable = memo != null && (!localeDependent ||
  !hasAnyTranslations())`，门控编码快照回放/存储**与**压缩 memo ATTR 发布
  （段内嵌同样 locale 冻结字节，须同门）。翻译注册翻转的两种时序均验证无陈旧泄漏
  （快照只在可用窗口内写入/消费）。

### 2.7 登录预取生命周期（闭合）

- 丢弃钩子：login 期（authenticatedProfile 非空门）+ config 期 onDisconnect 双覆盖；
  play 期无需（stats/adv 在 ServerPlayer 创建期 consume-once）。ServerPlayer 创建后
  placeNewPlayer 中断的极端边缘最多遗留一条瞬态 map 条目，同玩家下次登录覆写自愈。
- 双连接同 UUID：consume-once + 同步回退，数据正确性保持。
- 0267 两消费点（stats/adv 60s 有界 + raiseToBlocking + 回退同步路径含 awaitPending）
  落盘形态与声明一致。
- 0251：主线程队列跃迁带同守卫（disconnecting/cookies/VERIFYING），状态机单次执行
  由主线程串行化保证；executor 拒绝尾部由常规 tick 路径兜底。

### 2.8/2.9 输入面与功能完整性（闭合）

0205 为服务端→客户端方向（自有 brand 串解析自 GlobalConfiguration），无客户端输入；
批次119 §2.6 结论维持。配置键消费点抽查（`papo.tickProfile` 解析、PapoParallelism
三公式 clamp、fingerprintHardening 配置组、0209 itemEntityLimitPerChunk 缺省 -1）全部
在位。

## 三、产物

- 补丁 0268（内部提交 c65288bd → applyPatches 重建后 ea23de47，3 文件）：
  F1 locale 门控。
- 版本 0.71.2→0.71.3；compileJava 通过；applyPatches 全链 BUILD SUCCESSFUL。
- 默认配置（无服务端翻译注册）下行为与 0.71.2 字节级一致：门控的
  `hasAnyTranslations()` 为 false → memoUsable 恒真 → 走原路径。

## 四、判例

- **「确定性」声明必须对逐连接维度证伪**：批次 70-77 的等价性论证以「同实例同字节」
  为前提，但字节的逐连接差异源（locale 本地化、未来的 obfuscation session 类面）不在
  「包内容不变」的失效信号模型内。新增长寿命/共享编码缓存时，checklist 应加入
  「编码是否 per-connection 差异」（locale/属性/会话三类已知源）。
- **补丁链手工维护期禁止全量 rebuildPatches**：批次 118/119 以手工补丁目录为事实源
  （链终止 0264+0267），内部仓库尚存已回退的死提交——本轮误跑全量 rebuild 复活了
  0265-0268 四个死补丁并重编号；正确动作=保住再生产物中目标补丁→整体
  `git restore` 补丁目录→目标补丁改号入列→applyPatches 重建校验。build.md 已有
  「先 rebuild 看编号再写文档」判例，本例为其逆命题（存量手工链 + 增量补丁时禁全量）。
