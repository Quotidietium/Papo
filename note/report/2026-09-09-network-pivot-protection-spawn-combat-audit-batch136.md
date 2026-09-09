# 网络 pivot/防护与刷怪/战斗管线族对抗审计（批次 136，2026-09-09）

> 范围：四家族（132 红石 / 133 出站登录 / 134 入站解码容器 / 135 早期分配）报告
> 显式范围清单核对后发现的**覆盖缺口**——0205-0212（批次 51-58：fingerprint
> 加固 brand、ServerEntity 配对延后、Connection 出站 pivot、per-chunk 物品上限、
> 记分板等值门×3）与 0230-0240（批次 66-69：寻路内联、Present 原始读、红石消费
> ×3、刷怪/despawn/merge、战斗事件门控×3）共 19 个补丁 + 4 处直接提交
> （GlobalConfiguration.FingerprintHardening / PaperServerListPingEventImpl /
> CraftPlayer.sendSupportedChannels / CraftDefaultPermissions + paper-api
> EntityDamageEvent Preconditions 循环化）。批 135 收官句"0001-0266 全覆盖"
> 表述不实（已在批 135 节与本报告头部勘误），本轮补审闭合。
> 对齐流目标：多用户高频 join/quit 下的出站并发正确性、长期运行内存界、
> 配置错误面可用性、事件语义等价（含零监听器门）、随机序列红线。
> 方法：逐补丁对已应用源码树对抗读 + 对 vanilla（内部仓库 69b5ff19）逐算子/
> 逐分支语义核对 + 参考实现（enderPearlChunkCount / CraftBukkit 既有门）同构性
> 证明 + 系列态行号机械重算与前向逐字节往返验证。
> 已接受缺陷（2026-09-03 用户决断）0267/0268/0269 决断维持，未复发未扩散。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | fingerprint 加固族（0205 + 四处直提交） | **1 真实缺陷（A1，本轮修复）**；其余闭合（REAL 默认逐字等价、resolve 纯函数、null 回退完备） | §1 |
| 2 | 0206 ServerEntity trackedDataValues 延后 | **闭合**（数据仅经 set() 变更→脏路径处理时刷新→配对时现算=末次刷新且只会更新鲜；forceStateResync 不依赖该字段；协议时点不变） | §2 |
| 3 | 0207/0208 出站 pivot（PacketSendAction/BundleUnpacker） | **1 真实缺陷（A2，本轮修复）**；0208 闭合（instanceof 路由与 unbundlePacket else 分支逐字一致） | §3 |
| 4 | 0209 per-chunk ItemEntity 上限 | **1 真实缺陷（A3，本轮修复）**；cap 检查位置/默认 -1/captureDrops 相对序与文档一致；三回调咽喉点覆盖全部进出路径 | §4 |
| 5 | 0210/0211/0212 记分板等值门 | **闭合**（同实例重设保广播→NMS 原位变异刷新保持；null 归一化在门内；NumberFormat record 等值可靠；0212 去重只影响 broadcastAll 纯发包、setDirty 在两处之外无条件保留；引用身份可观察性与幂等修复播丢失为已文档化权衡） | §5 |
| 6 | 0230 寻路 tick 内联 | **1 真实缺陷（A4，本轮修复）**；其余逐算子对账闭合（getEntityPosAtNode 公式含 int 强转位置、normalize 1.0E-5F 守卫、Mth.floor(int)≈int、getGroundY scratch 只读不逃逸） | §6 |
| 7 | 0231 Present 记忆原始读 | **闭合**（unregistered/absent/value-null 三态塌缩恰好等价；Present 为 record 不可子类；第三方 MemoryCondition 走原路径） | §7 |
| 8 | 0232 红石粉双站点门 + 桶序复刻 | **闭合**（handleRedstoneChange 复用已审 0125/0134 形态；Vec3i.hashCode=(y+z*31)*31+x、spread=h^h>>>16、7<12 不扩容、7<8 不树化、桶升序+插入序平局，从原理逐项对上） | §8 |
| 9 | 0233/0234 火把惰性化 / 比较器谓词缓存 | **闭合**（0233 事件字段与判读逐字一致、零监听器下与原空派发同归；0234 谓词无状态、AABB 仍按 pos 现算、static 初始化安全发布） | §9 |
| 10 | 0235/0236/0237 刷怪/despawn/merge | **闭合**（0235 事件块零随机消耗、门=跳过构造+空派发；0236 fill 变体即原返回列表同一路径、tryToMerge 仅触碰 this+当前元素→谓词时效性成立、scratch 不可重入（私有方法无 API 再入）；0237 全类别播种+@MergeMap 保键+@PostProcess 同点重建、ordinal 界内） | §10 |
| 11 | 0238/0239/0240 战斗事件门控 + API 直提交 | **闭合**（四组门逻辑逐字核对含 stabAttack 取反形；sweep DamageSource 共享副本只读（全 final 字段）；EntityDamageEvent Preconditions 异常类型/消息/求值序逐字等价） | §11 |
| 12 | 不可信输入面 + 内存界 + 已接受缺陷 + 构建 | 本族零新增客户端输入信任面（19 补丁无一处读客户端包；brand/status 为出站、计数器/记分板/事件门全部服务端权威输入）；A3 修复外无新增无界结构；0267/0268/0269 决断维持；applyPatches 全量重放 EXIT=0 + compileJava --rerun-tasks BUILD SUCCESSFUL | §12 |

## §1 A1：fingerprint brand custom-value 超长编码悬崖（已修）

**机制**：`BrandPayload.write` 经 `FriendlyByteBuf.writeUtf(brand)` 编码，无显式
上限的重载默认 `MAX_STRING_LENGTH = 32767`（short）；`Utf8String.write` 对
`string.length() > maxLength` 抛 `EncoderException`。`fingerprintHardening.
brandPayload.custom-value` 是操作员自由文本，粘贴错误产生 >32767 字符值时，
**每个加入者都在配置阶段出站编码处抛异常断连**——服务不可加入，且报错在
网络层、不指向配置项（可用性悬崖；低概率×全服不可用）。status.version-string
走 Component.literal 无此上限；plugin-channels 过滤的 channel 名来自
Messenger 注册表（服务端权威）不受影响。

**修复**：`BrandPayload.resolve` 对 CUSTOM 值截断到
`net.minecraft.network.FriendlyByteBuf.MAX_STRING_LENGTH`（REAL/VANILLA/空值
回退逐字不变）。截断产生的落单代理对由 `ByteBufUtil.writeUtf8` 按既有替换
语义处理，字节数仍在 `utf8MaxBytes` 界内。

**其余核对**：`papoVersionName` REAL 分支与上游逐字节一致（git b04edeeb0d diff
基线）；`papoOverrideCommandVisibility` 在 `registerCorePermissions` 两处调用点
（启动 enablePlugins(POSTWORLD) 与 `reloadPermissions()`——后者可有在线玩家，
setDefault+recalculatePermissibles 即重载语义本身，安全且是期望行为；批 52
"仅启动时应用"表述就此精确化为"启动 + /reload permissions 再应用"）；三权限
名经 CommandPermissions.registerPermissions(parent) 先行注册必然可查；config
未加载（get() null）三处全部回退现状。

## §3 A2：0207 AtomicBoolean 降级破坏 consume-once（已修，本轮最高严重度）

**原判断为何错**：0207 注释宣称"tryMarkConsumed/isConsumed 仅被 processQueue
调用，而 processQueue 每 Connection 单线程（play 阶段主线程、login/status 阶段
synchronized）"。前半句对，后半句**漏掉迁移窗口**：

1. 连接在 channel 初始化时进入 `ServerConnectionListener.pending`（isPending=true），
   此期间 netty 线程的 send→flushQueue 走 `synchronized(pendingActions)` 分支；
2. 主线程下一 tick 的 `ServerConnectionListener.tick()` 顶部 `addPending()` 把连接
   移入 `connections` 并写 `isPending=false`（普通字段写，无锁），**同一循环内**
   即对该连接 `connection.tick()`→`flushQueue()`→主线程分支 `processQueue()`
   **（无锁）**；
3. netty 线程只要在主线程写 isPending=false 之前通过了 538 行的 isPending 读，
   就仍会进入 synchronized 块——与主线程的无锁 processQueue **并发执行**。

两个弱一致迭代器都可返回同一元素 X：双方各调 `iterator.remove()`（第二个为
无害 no-op），随后 `tryMarkConsumed()`。原 AtomicBoolean 的 CAS 保证恰一方赢→
accept() 恰好一次。降级为普通 boolean 后双方都读 false→双写 true→双双返回
true→**accept() 双执行**：PacketSendAction 双执行=同一出站包重复写上线
（登录/配置期重复包可致客户端状态机错乱断连）、WrappedConsumer 双执行=排队
动作（如插件 runOnceConnected 消费）重复运行；isConsumed 预检在无同步下也不
可靠。连接数越多主线程 tick 循环越长，窗口越宽——**多玩家高频 join 突发正中
靶心**。Paper 上游代码注释（"If we are on main, we are safe here..."）本身就是
对 login 期并发的承认；AtomicBoolean 是承载这一点的原语。

**修复**：恢复 `consumed` 为 AtomicBoolean（CAS/get 原样），保留 0207 的
delegate lambda 消除与 @Nullable 化（每排队包 3 对象→2 对象，收益主体保留）。
补丁留注释档说明该字段承载并发语义不可降级。系列态行号（-916,10 与原版
hunk1 完全一致）经"反向链 216/218/223/224/225/242 → 撤原 0207 → 打新 0207 →
前向重放全部后继"机械重算并逐字节往返验证。

**0208 核对**：`instanceof BundlePacket` 路由与 `unbundlePacket` 内部
`type()==bundleType` 精确判定的 else 分支（`consumer.accept(packet)`==
`list.add(packet)`）逐字一致；错误类型 bundle 落 else 仍等价；`isTerminal()`
处理位置未动。闭合。

## §4 A3：0209 移动路径计数零值残留（已修）

照抄参考实现 `enderPearlChunkCount` 时，`removeEntityCallback` 路径抄到了
remove-at-zero（`addTo` 返回**旧值**，旧值≤1→新值≤0→remove，含旧值 0 的
-1 自愈清理），`entitySectionChangeCallback` 路径只抄了裸递减——块内最后
一件物品**跨块移走**（而非被移除）后，该块表项以 value=0 残留；参考实现的
两条递减路径均在归零时 remove。长期运行服务器按"曾持有物品且最后一件是
移走"的不同区块数单调累积残留表项（每项 ~16-24B；大世界数月可达 10^5-10^6
量级）。修复=移动路径递减同构 remove-at-zero，后续 hunk +start 平移。

**其余核对**：cap 检查在 captureDrops 捕获块之后（合法方块破坏捕获件不受
影响）、doEntityAddEventCalling 之前；默认 -1 整块跳过；`moonrise$getEntityLookup()`
在 ServerLevel 恒为 ServerEntityLookup；三回调为 Moonrise 统一咽喉点（覆盖
discard×7/卸载/换维度），与参考实现同线程域。

## §6 A4：0230 距离门取反不保 NaN（已修）

原版 `!pos.closerThan(vec3, 2.0)` = `!(distanceToSqr < 4.0)`；0230 内联为
`distanceToSqr >= 4.0 → return false`。对非 NaN 两式互补，对 NaN 分道：
原版 `NaN < 4.0`=false→`!false`=true→**return false**；改写 `NaN >= 4.0`=false→
**不返回**，越入 `canMoveDirectly(NaN,...)`（原版根本不会调它）——若其返回
true 则结论与原版相反（返回 false 则殊途同归）。补丁注释明言"NaN inputs flow
through NaN comparisons unchanged"，与实现不符（批 132"注释反写"同型）。
NaN 位置仅来自病态实体位置（插件坏传送等），但本仓库纪律是严格等价。
修复=`!(x < 2.0 * 2.0)` 精确取反（NaN<c 为 false→!false→return false，与
原版逐点一致），行内注释档更新。

**其余对账**：`getEntityPosAtNode` 公式 `node.x + (int)(bbWidth+1.0F)*0.5`
（int 强转在乘 0.5 前）逐字；node.y 为 int → `Mth.floor(node.y)`==node.y
（Flying/Ground 两处 y 比较等价）；`Vec3.normalize` 为 double sqrt + 1.0E-5F
守卫（与 0230 内联同式）；`atBottomCenterOf`=(x+0.5, y+0.0, z+0.5)；
`!(d1<d) && !(d<0.5)` 与 `!flag && !flag1` 同构（NaN 同判）；
`getGroundY` scratch（set→move(DOWN)→读→move(UP)）只喂只读查询，
`WalkNodeEvaluator.getFloorLevel` 的 `pos.below()` 自建新对象不改 scratch、
不持有引用；单线程 tick（0190 先例）。`papoGetNode` 加法式访问器、Path final。

## §5 0210-0212 记分板等值门（闭合，权衡已文档化）

- 0210：九 setter 门=布尔/枚举 `==`、Component 实例守卫+内容相等、
  prefix/suffix 的 null→EMPTY 归一化在等值判定内完成；**同实例重设保持广播**
  →NMS MutableComponent 原位变异后靠重设刷新的奇偶性保持；
  `Scoreboard.onTeamChanged` 基类空实现、ServerScoreboard 侧 broadcastAll+
  updateTeamWaypoints+setDirty 对等值输入均为幂等无观察差异。
- 0211：setDisplayName 等值跳过 formattedDisplayName 重算（createFormattedDisplayName
  纯函数，等值输入产等值输出）；numberFormatOverride 采用同文件 display() 的
  CraftBukkit 既有门形态（mutableBoolean.isTrue() 强制位=新建 score 仍必发）；
  NumberFormat 为 record，Objects.equals 值可靠，同实例重设不可携带新信息
  （不可变）。
- 0212：`papoBroadcastDisplay` 只跳过第二个逐字节相同的
  ClientboundSetDisplayObjectivePacket；`setDisplayObjective` 尾部 setDirty 在
  广播之外无条件执行，存档脏标记不受影响；broadcastAll 为纯发包。
- 已文档化权衡（不判缺陷）：等值新实例被跳过后失去"同值重播修复客户端失步"
  的机会（vanilla 也不承诺 set 即发包）；getter 返回旧等值实例（引用身份）而非
  新传入实例（Bukkit API 无此承诺）。

## §8 0232 红石粉双站点门 + 桶序（闭合）

两站点（DefaultRedstoneWireEvaluator.updatePowerStrength +
RedStoneWireBlock.calculateCurrentChanges）收编进已审 handleRedstoneChange
（0125/0134 同型，批 135 门键权威性定性覆盖 BlockRedstoneEvent 自有表）。
桶序复刻逐项对上：`Vec3i.hashCode = (y + z*31)*31 + x`（应用树 :50 原文）；
HashMap `spread(h)=h^h>>>16`、`bucket=spread&15`（table 16）；`Sets.newHashSet()`
→默认容量 16、7 项 < 12 阈值不扩容、≤7/桶 < 8 不树化；插入序 pos+DOWN/UP/
NORTH/SOUTH/WEST/EAST 与 Direction.values() 一致；Papo 侧
`(y+offY+(z+offZ)*31)*31+x+offX` 与 `Vec3i.hashCode(relative)` 同算同值。
迭代序=桶升序+同桶插入序=O(16×7) 双循环复刻。1M 随机位置对拍（WireSetOrderBench）
在案。

## §10 0235-0237（闭合）

- 0235：零监听器跳过的是事件构造+空派发（shouldAbortSpawn 无人可置→原路径
  必然落入 final boolean 表达式）；事件构造参数（CraftLocation/CraftEntityType
  映射）零随机消耗，自然刷怪随机序列红线不动。
- 0236：`getEntities(typeTest, box, pred, output)` 即 `getEntitiesOfClass` 的
  同一实现路径（返回列表版本就是 new ArrayList+调 fill 版本，Level.java:1719-1727），
  序与内容等价；循环体删 isMergable 复检的时效性成立——tryToMerge→merge 仅
  赋值 destination/origin 两实体的栈（后继元素状态在 fill→loop 间不可变），
  且 tryToMerge 内 areMergable 对 this 的当前栈重验（装满后自然停止合并）；
  scratch 不可重入（mergeWithNeighbours/scanForMerges 私有、无 API 再入路径；
  ItemMergeEvent 监听器无法回调进这两个私有方法）；ExperienceOrb.merge 同型。
- 0237：map 由 `Arrays.stream(MobCategory.values())` 全类别播种、@MergeMap
  合并保键→每 ordinal 必有项；数组与 preComputed 在同一 @PostProcess 重建
  （config load/reload 同钩子）；初始空数组仅存在于 tick 开始前（config 加载
  先于世界 tick）。运行时反射改 map 不属支持流（与 vanilla map 查找同样越约）。

## §11 0238-0240（闭合）

- 0238：零监听器→callEvent() 恒 true（不可取消事件无人可置）→reset 分支与
  原版一致；getAttackStrengthScale(0F) 纯计算，跳过求值无观察面。
- 0239：attack/stabAttack 两站点布尔化（papoAttackAllowed/papoAttackDenied）
  与原 callEvent 组合逻辑逐字等价（含 stab 的提前返回取反形）；PlayerVelocityEvent
  第二站点零监听器下 cancelled 恒 false、velocity.equals(event.getVelocity())
  恒 true（构造即 clone）→整块 no-op，跳过安全（0100 首站点同型已审）；
  sweep DamageSource 提出——knownCause 产新实例、DamageSource 全 final 字段
  无 setter、hurtServer 只读，常量实参共享一份值恒等副本安全。
- 0240：PlayerItemDamageEvent/EntityDamageItemEvent 双门各自 HandlerList；
  零监听器下 isCancelled 恒 false、getDamage()==构造 i→两块均 no-op。
- paper-api EntityDamageEvent 直提交：循环替代 stream allMatch——失败条件、
  异常类型（IllegalArgumentException）、消息串、求值顺序（values→keySet.equals→
  functions）与上游逐字一致（git e7d7a0f99e 基线 diff 核对）；stream allMatch
  短路=for 循环首 null 即抛，行为同点。

## §12 不可信输入面 / 内存界 / 已接受缺陷 / 构建

- **不可信输入**：本族 19 补丁零新增客户端输入信任面——brand/status 为出站；
  0209 计数器输入为实体进出回调（服务端权威）；0210-0212 记分板输入为插件 API
  调用（可信面）；0230-0240 全部内部游戏逻辑。修改版客户端无直击路径。
- **内存界**：A3 修复外无新增无界结构（0207 恢复的 AtomicBoolean 每排队动作
  一次、生命周期=队列项；0206 删字段纯减；0231 无状态；0232 常量表）。
- **已接受缺陷**：0267（无界 join 等待）/0268（locale 冻结）/0269（缓存常驻）
  决断维持，本轮触碰面与其无交集，未复发未扩散。
- **构建验证**：`python note/check_patch_counts.py` ALL OK（全 918 文件）；
  `./gradlew :paper-server:applyPatches` 全量重放 EXIT=0（源码树同步，四处修复
  逐一在树核对）；`./gradlew :paper-server:compileJava --rerun-tasks`
  BUILD SUCCESSFUL。

## 判例入库（四条）

1. **线程封闭论证必须覆盖状态迁移窗口**（A2）："'X 只有主线程跑'与'Y 只有
   synchronized 跑'不等于'X 与 Y 不并发'——必须核对两分支间的迁移点（本例
   addPending 同 tick 内 isPending 翻转+入列+tick）是否可能与旧分支的滞留执行
   交叠；迁移点无锁即破封闭。原子原语（CAS）这类'看似多余的同步'在被删除前
   必须证明其保护窗口确实不可达。"
2. **取反改写不保 NaN**（A4）："`!f(x)` 改写为独立谓词时，含 `<` 比较的 f 的
   精确取反是 `!(x < c)`，不是补关系 `x >= c`——NaN 对两者不同判。'逐字照抄'
   的注释宣称本身是可审计对象（批 132 注释反写判例的数学版）。"
3. **照抄参考实现要每条路径同构**（A3）："参考实现的清理语义（归零删项）存在
   于它的每条递减路径上；照抄者新增的每条路径都要单独核对是否需要同样的清理，
   漏一条就是与参考实现渐行渐远的慢速泄漏。fastutil addTo 返回旧值——语义
   逐库核对，不凭直觉。"
4. **配置直通协议编码器的自由文本必须过编码器上限**（A1）："resolve() 返回的
   字符串若直接进入 writeUtf 等带硬上限的编码器，超限即出站 EncoderException
   ——对'每个连接都会编码一次'的包（brand）等于全服不可用。配置自由文本在
   resolve 层按编码器常量截断。"

## 覆盖勘误（表述与事实不符，本流水目标命中项）

批 135 文档与报告的收官句"至此 0001-0266 全部 Papo 自有补丁四家族均获对抗
审计覆盖"与四家族报告的显式范围清单不符：0205-0212、0230-0240 共 19 个补丁
（含 4 处直接提交）不在任何一族范围内。两处已就地勘误并指向本报告。至此
0040-0266 的覆盖声明以各报告显式清单为准、真实闭合。
