# R48 方块交互轴（挖掘风暴窗）分配普查——判负闭合（批次 189，2026-10-12）

**版本保持 0.110.0（无代码变更；轴判负闭合轮，R43 保存 IO 关闭同型）**

## 1. 背景与选轴

R38 判例（新证据等级=从未成像的场景/机制轴）第 N 次应用：R24-R47 分配普查
六域（流送/盘载/驻留/实体+保存/机械负载/物品旋涡）中，**没有任何窗含方块
交互**——方块状态变更链（`handleBlockBreakAction → destroyAndAck → setBlock
→ 光照增量 → 邻居通知 → BLOCK_UPDATE 广播 → ACK`）从未成像，而挖掘是真实
玩家头号负载（挖掘/建筑服常态）。与 R46 机械负载（漏斗/时钟）、R47 物品旋涡
（实体生命周期）正交。

creative 即时挖掘三重适配性：零工具依赖（instabuild 直通 `destroyAndAck`，
ServerPlayerGameMode L224 实证）、零掉落物实体（R47 已覆盖域，轴不重叠）、
确定性高（无挖掘进度相）。

## 2. 协议前置推导（源码实证）

- **serverbound PLAYER_ACTION = 0x28**：GameProtocols 注册序提取。正则必须
  `\w+PacketTypes\.` 而非仅 `GamePacketTypes\.`——common 前缀包
  （CommonPacketTypes.SERVERBOUND_KEEP_ALIVE、PingPacketTypes.PING_REQUEST、
  CookiePacketTypes.\*）同在注册序列内，漏检即整体偏移（首版算出 0x19 差 4）。
  三锚点验证全中：ACCEPT_TELEPORTATION=0x00、KEEP_ALIVE=0x1B、
  MOVE_PLAYER_POS=0x1D（与批次141 bot 实证一致）→ PLAYER_ACTION=0x28 可信。
- **payload 格式**（ServerboundPlayerActionPacket 源码）：
  `varint action ordinal + long BlockPos.asLong()（大端）+ byte direction
  （from3DDataValue）+ varint sequence`；Action 枚举 START_DESTROY_BLOCK=0。
  asLong = `((x & 0x3FFFFFF) << 38) | (y & 0xFFF) | ((z & 0x3FFFFFF) << 12)`。
- **距离门**（Player.isWithinBlockInteractionRange）：`eye 到方块 AABB 的点距
  < (blockInteractionRange + buffer)²`，buffer=1.0、creative range=4.5 →
  阈 5.5。候选筛用同式保守半径 5.0 → 每个包必然过门（零无效包）。
- **creative 反作弊面**：mayfly=true 豁免 flying 踢出；但无 spectator noPhysics
  豁免 → 不能穿山（批次141 判例 creative 等价形态：**以不遇山的高度飞**——
  y=200 空中带纯空气零纠偏，R46 观察者同款）。

## 3. 探针验证（协议风险前置，两轮）

第一轮（1 bot 短时序 digMs=40s）：join/爬升（0x46 纠偏采纳收敛，join 窗口
一次 "moved too quickly 19.0"=客户端加载前移动被忽略后的首包差，无踢出）、
fill 落地、320 挖发送全绿——**但探针②全静默**。根因：bot 于 marker+60 断开
→ 区块卸载 → marker+90 探针 `execute if block` 在未加载区块上**静默翻 false
（无错误回显）**。判例见 §7。

第二轮（digMs=120s，bot 在线跨探针窗）：**DIG-DUG-AIR×3 + DIG-CTRL-STONE×2 +
全球 793/793 耗尽 + gate 零错误**——挖掘真实破坏方块实证，协议风险全部解除。

## 4. 普查设计与发现腿

**DigStormCensus**（y=200 空中带）：bot 爬升+径向撒布 150s 定点 → harness
/fill 石立方 11×15×11（覆盖保守可达球 r=5.0 全候选格，**两腿同发=fill 面在
delta 中相消**）+ reach 外 control 石格 → /say 标记 → latch 放行 → B 腿按
（距离,y,x,z）确定序 100ms/挖全球 ~793 格/bot；A 腿同节拍驻留零挖掘包。
腿脚本 `alloccensus-r48diglegs.sh`（同二进制 0.110.0 配对，JFR @1ms 60s 窗，
marker+17..77 覆盖挖掘主体 ~60/99s）。

门（双腿全绿）：A 腿 traces=96·digsTotal=0·ctrl=2·unknown=0；B 腿
traces=24·digdone=12·**digsTotal=9,472**（793×12 全球耗尽）·air 探针×3·
ctrl=2·零类版本错误·exit 0·服务器日志零 "Unknown or incomplete"。

## 5. 发现与面判定（B−A 全事件 in-stack 差分）

| 面 | B | A | Δ | 判定 |
|---|---|---|---|---|
| LightEngine（光照传播） | 15 | 1 | **+14** | 数据语义面：挖掘穿孔的光照再传播=用户可观察内容变更（R17 非均匀光照同族），过阈亦不立项；且 <20 阈 |
| destroyBlock 事件机器（CraftBlock.at/BlockBreakEvent/CraftBlockState/captureDrops） | 4 | 0 | +4 | **阈下 5×**：R36 判例（既优化代价面 179 trips 判负）强两档；creative+零监听下整套事件机器无观察效果，但质量太小 |
| handleBlockBreakAction / playerWillDestroy | 3 / 3 | 0 / 0 | +3 / +3 | 阈下（动作入口+石方块钩子） |
| setBlockState / SectionBlocksUpdate 广播 | 1 / 2 | 0 / 0 | +1 / +2 | 阈下（setBlock 主链大部分零分配：容器侧 R5-R22 已优化） |
| ClientboundBlockUpdatePacket / BlockChangedAck | 0 / 0 | 0 / 0 | 0 | 未达采样（64B 小对象；ack 每 tick 合并一枚） |
| RegistryFriendlyByteBuf | 32 | 18 | +14 | R44 判负维持（逐包包装 ~80B/trivial） |
| JFR 自观测族（InetSocketAddress.toString 等） | ~+56 | 基线 | +56 | **挖掘活跃指纹**（R41 剥离判例：networkStatFor 每包 toString，随包数缩放）——证明 5,700+ 挖掘包在窗内流动 |
| NaturalSpawner/spark 族 | ~460/250 | ~455/250 | ~0 | 基线两腿同载（peaceful 自然刷新+上游采样器） |

**单挖分配质量核算**：creative 每挖 ~4-6 件小对象（CraftBlock 32B +
BlockBreakEvent 48B + CraftBlockState ~120B + captureDrops ArrayList 40B +
BlockUpdatePacket ~64B+编码 buf）≈ **300B/挖**；窗内 ~5,760 挖（8 挖/s/bot ×
12 bot × 60s）≈ **1.7MB/60s**——比窗内基线 raw 权重（14.1GB）低四个数量级，
young 代直接收割，MSPT 零通道。

**窗口覆盖敏感性**：窗覆盖 60/99s 挖掘相；全窗折算 LightEngine ≈23（勉强
过阈，但性质=数据语义面不立项）、destroyBlock 机器 ≈7（仍 3× 阈下）——
判负结论对窗口覆盖不敏感。

## 6. 判负闭合论证（四支柱，R43 同型）

1. **全谱低于立信阈**：最大可归因机械面 4 trips（destroyBlock 机器），最大
   可归因面 14 trips（LightEngine，数据语义面）——均 <20；对照 R46（172,800
   事件→21 trips 立项），本轴 5,760 事件→最大 14 trips，面密度天然低一个
   数量级（每事件分配质量小 100×）。
2. **质量量级**：1.7MB/60s 占窗内分配质量的 0.01%——R43 判例"trips 低于门槛
   一个数量级=关闭"更强适用（低两个数量级）。
3. **语义面不可消除**：光照传播/邻居通知/方块广播都是方块状态变更的用户可
   观察语义本体，恒等可证性不成立（消除=改变游戏行为=红线）。
4. **事件机器消除路径存在但收益不成立**：零监听快路（L201-216 判例模式）
   可静态复制 BlockBreakEvent 结果（零监听 ⟹ cancelled=canAttackBlock 预设、
   dropItems=true、exp=creative 0），creative ⟹ captureDrops 机器恒空——但
   该面 4 trips/2.8MB 整腿，收割收益 < 变更风险一个数量级（R36 判例：代价
   面判负不看可消除性看质量）。

**结论：方块交互轴分配面判负闭合**，与保存 IO（R43）、网络 λ 面（R44）同列
维持代价面清单；分配普查第七域（方块交互）成像→判负留档。版本保持 0.110.0。

## 7. 判例（build.md 同步）

1. **探针区块卸载静默假阴性**：`execute if block` 在未加载区块上**静默翻
   false，无任何错误回显**（区别于双斜杠事故的错误回显可辨形态）——bot 驱动
   场景的探针窗必须保证 bot 在线（区块票据持有）；探针全静默 ≠ 命中失败，
   先查区块加载状态再怀疑协议。本判例与 R47 双斜杠事故族互补：一个是**回显
   假阳性**，一个是**无回显假阴性**。
2. **serverbound 包 ID 正则必须含 common 前缀**：GameProtocols 注册序提取的
   正则若只匹配 `GamePacketTypes\.` 会漏 CommonPacketTypes/PingPacketTypes/
   CookiePacketTypes 前缀的 common 包导致整体偏移（首版 0x19 vs 实证 0x1D
   差 4）；正确正则 `\w+PacketTypes\.`+三锚点验证（已知实证 ID 全中才可信）。
   与批次141 clientbound 差一事故同族：注册序推导必须锚点实证。

## 8. 工件

- harness：`benchmark/src/papo/bot/DigStormCensus.java`（+OfflineJoinBot 挖掘
  扩展：digBlock/爬升/可达球挖掘/闩等待）
- 腿脚本：`benchmark/alloccensus-r48diglegs.sh`（门含挖掘正证据）
- 数据：`benchmark/results/allocdig-*-A-110nat/`、`allocdig-*-B-110dig/`
  （JFR+allocsum 聚合，原始不入库留 results/）
- 探针日志：`results/r48-probe.log`（假阴性首轮）、`r48-probe2.log`（全绿）
