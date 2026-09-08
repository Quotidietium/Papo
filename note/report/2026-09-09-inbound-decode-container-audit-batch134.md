# 入站帧化/读侧解码/容器交互家族对抗审计（批次 134，2026-09-09）

> 范围：批 132（R4 红石族）、批 133（R1 出站包管线/登录族）之后的第三面——
> **入站读侧与容器交互族**：0091-0103（批 27-29 NBT 读写/网络读侧）、
> 0066-0068/0075/0084/0085（批 15-17/21/25 NBT 与网络写侧快路径）、
> 0137-0140/0087/0139（批 34/45 交互路径）、0153-0155（批 37/38 物品解码/
> 点击快路/帧长内联）、0213-0225（批 58-62 帧化与带宽记账）、
> 0228/0229（批 65 容器/菜单零监听器快路）+ 直提交（netstat 命令）。
> 对齐流目标：修改版客户端不可信输入直击快路径、高频点击/拖拽/创造下的
> 数据操作完整性与交互流畅度、长期高负载下的解码/池化资源界。
> 方法：逐补丁对已应用源码树对抗读 + 对 vanilla（内部仓库 69b5ff19）逐路径
> 语义核对 + 结构调用图（构造点/消费点/释放点）完备性证明。
> 已接受缺陷（2026-09-03 用户决断）0267/0268/0269 只核对未复发，不重报。

## 结论表

| # | 面 | 结论 | 证据 |
|---|---|---|---|
| 1 | 容器点击快路（0154 handleContainerClick）副作用完备性 | **1 处真实缺陷（F1，本轮修复）**；QUICK_CRAFT 逐字复制/cancelled 单赋值/DENY-ALLOW 死臂闭合 | §1 |
| 2 | 创造包快路（0228 handleSetCreativeModeSlot） | **闭合**（copy≡asNMSCopy∘asBukkitCopy 含 EMPTY 单例；特征门/flag1/flag2 顺序保持） | §2 |
| 3 | 拖拽快路（0229 QUICK_CRAFT 两段 setCarried） | **闭合**（InventoryDragEvent 无 API 子类实证；零监听器下 DENY 臂死路、getCursor 恒构造值） | §3 |
| 4 | 入站帧提取（0222 retainedSlice/0155 内联 varint/0225 计数） | **闭合**（逐位累积等价 + 异常/reader-index 终态逐路径一致；CompressionDecoder 收编 slice 为 cumulation 后整帧耗尽释放） | §4 |
| 5 | 出站帧化（0213-0217 + 0216 复用/clamp） | **闭合**（headroom 身份匹配单 event-loop 单写遍历；回退面完备；0215 界数学核验；refcount 各路径恰一次） | §5 |
| 6 | 入站 NBT/物品解码（0096/0097/0098/0153/0067/0068） | **闭合**（ThreadLocal 适配器 save/restore 重入安全；ASCII 快路含 0x00 原始字节与 JDK 同判；数组负长度异常同型同点；mark/reset 为不可达差异） | §6 |
| 7 | NBT 写侧（0092/0095/0075/0084/0085/0091/0040） | **闭合**（单遍批量写/BE 视图/utf8Bytes 与 writeUtf8 同编码器；fastIterator entry 不逃逸） | §7 |
| 8 | 压缩池（0103 Inflater/0066 Deflater 单槽池） | **闭合**（借用-归还平衡；换级退役；同线程嵌套有界；double-close 仅理论场景不可达） | §8 |
| 9 | 交互路径（0137/0138/0140/0087/0139 + 2216/2227 门） | **闭合**（同 tick 同步无监听器集交错；null 栈仅在必早退路径；ItemCraftedEvent 零监听器跳过无消费方） | §9 |
| 10 | 内存界 + 已接受缺陷状态 + 功能完整性（netstat 接线） | 无新增无界结构；0267/0268/0269 决断维持未复发；/paper netstat 全链接（计数器穿线/每秒快照/命令注册） | §10 |
| 11 | 构建验证 | applyPatches 全量重放 EXIT=0 + compileJava --rerun-tasks BUILD SUCCESSFUL | §11 |

## §1 F1：0154 快路把 craft/smith 重同步判据读点挪到了 clicked() 之后（唯一代码缺陷，已修）

**机制**：原路径（有监听器分支）构造 `CraftItemEvent`/`SmithItemEvent` 时读
`craftingInv.getRecipe()` / `smithingInv.getResult()`——发生在 `callEvent` 与
`containerMenu.clicked()` **之前**（事件构造 :3399-3420 → 派发 :3437 →
clicked :3445 → `instanceof CraftItemEvent/SmithItemEvent → sendAllDataToRemote()`
:3448-3452）。0154 零监听器快路却先执行 `clicked()`（:3159）再读
`getRecipe()/getResult()`（:3162-3166）。

**翻转场景**：客户端点击合成/锻造结果槽拿走最终成品。`clicked()` 消耗输入
格：`CraftingMenu.slotChangedCraftingGrid` 重算 → 恰好耗尽时
`TransientCraftingContainer.getCurrentRecipe()` 翻转为 null（SmithingMenu 输入
耗尽同理，`getResult()` 读实时槽 3）。原路径判据（前读）为真 →
`sendAllDataToRemote()` 执行；快路判据（后读）为假 → **跳过**。

**影响链**：`handleContainerClick` 尾部 `setRemoteSlotUnsafe(packet.changedSlots())`
把客户端自报的槽位哈希写入服务端 remote 追踪；自定义配方（插件注册、客户端
预测错位）下这是靠 `sendAllDataToRemote()` 全量治愈的（CraftBukkit 加此调用
的原始动机）。快路在"最终合成"恰好是错位最重的时刻丢掉治愈 → 客户端合成格
陈旧直至重开菜单。服务端状态正确、无复制面；与批 133 F1 同级（低概率 ×
客户端可见性、自定义配方零监听器服可复现）。**修法**：判据
`papoCraftResync` 在 `clicked()` 之前求值（与原路径事件构造点对齐），
原样保留其余结构——语义即与原路径逐分支等价。

**其余核对（全部闭合）**：QUICK_CRAFT case 与原 switch 逐字一致（含
quickcraftSlots.size()==1 递归改写 PICKUP + resetQuickCraft + 早退 return，
递归再入时监听器门重查）；`cancelled` 在 :3099 唯一赋值（isSpectator）；
零监听器下 `setCancelled(false) → setResult(ALLOW)`，`getResult() != DENY ⟺
!cancelled`（InventoryInteractEvent 语义）；SPIGOT-1224 分支（containerMenu
在事件期间被换）仅监听器可达；CartographyItemEvent 上游本就不在
sendAllDataToRemote 判据内，快路正确排除；早退 `slotNum < -1 && !=
SLOT_CLICKED_OUTSIDE` 未 resume 远端抑制为上游 CraftBukkit 原生行为
（恶意包可致当前菜单停止同步直到关闭，菜单对象级自愈、服务端权威校验
不受影响——记录不修，非 Papo 引入）。

## §2 0228 创造包快路

- `InventoryCreativeEvent` 无自有 getHandlerList（paper-api 全文核对），
  监听器注册进 InventoryClickEvent 唯一表——与 0154 同门。
- `packet.itemStack().copy()` ≡ `asNMSCopy(asBukkitCopy(x))`：非空栈两者皆
  新对象+组件拷贝+同 count；空栈 copy() 返 EMPTY 单例（:829 isEmpty 短路），
  asBukkitCopy→AIR→asNMSCopy→EMPTY，逐例恒等。
- DENY/ALLOW 臂零监听器死路（result 仅监听器可置）；`flag2`（count ≤ max）
  与 `isItemEnabled` 特征门均在快路之外前置；flag1 槽界 1..45 保持。
- 恶意面保持 vanilla 边界：超 max 栈被静默丢弃（两路径同）、超量丢弃节流器
  原样。

## §3 0229 拖拽快路

- `InventoryDragEvent` 自有 HandlerList 且 **API 无子类**（全库 grep 核对），
  门键完备。
- 零监听器下 result 恒 DEFAULT → `!= DENY` 恒真 → setItem 循环与末段
  setCarried(asNMSCopy(event.getCursor())) 与原路径逐句等价（getCursor 恒为
  构造传入的 newCarried 引用，setCursor 仅监听器可调）。
- 预写 setCarried（防插件关背包复制）与末写两段均保留，值恒等；
  eventMap/事件对象构造为纯分配，跳过不可观察。
- 状态机（quickcraftStatus 0/1/2、单槽退化 PICKUP 递归、resetQuickCraft
  时机）不在快路改写范围——doClick 主体未动，仅事件侧机械被门控。

## §4 入站帧提取（0222/0155/0225）

- **0155**：对 vanilla（内部仓库 69b5ff19）逐路径比对——前缀不全 →
  resetReaderIndex+return；3 字节全续位 → 消耗前缀后抛
  CorruptedFrameException("length wider than 21-bit") 不 reset；零长 → 消耗
  后抛 "Frame length cannot be zero"；载荷不足 → reset。累积式
  `i |= (b & 127) << n*7` 与 copyVarint+VarInt.read（helper 复读）逐位一致
  （1 字节 b≥0 ⟺ 无续位 ⟺ b&127==b）。monitor.onReceive 逐字保留。
- **0222**：retainedSlice+skipBytes 与 LTFBD.extractFrame 同型。关键结构核
  对：CompressionDecoder 是 ByteToMessageDecoder，上游 slice 消息被**收编为
  其 cumulation**（首消息零拷贝采纳），每帧一次 decode 内整帧耗尽（JDK 路
  径 nioBuffer 视图只读 + skipBytes；Velocity 路径 inflate 后 `in.clear()`
  仅动 slice 包装索引），finally 整帧释放——与 readBytes 拷贝时代完全同构，
  唯一差异是背衬内存共享（引用计数持有）。帧区间 [readerIndex,
  readerIndex+i) 互不重叠、每区仅切一次，假设"下游只读"即使被打破也只影响
  该帧自身字节。协议切换 FlowControlHandler 钉住至多一个读批次（LTFBD
  同语义）。
- **0225**：入站计数在帧完整判定后累加 `i + getByteSize(i)`，AtomicLong 每
  连接（event loop 内无竞争），构造点唯一（ServerConnectionListener →
  configureSerialization），memory 通道传 null 豁免。纯观测无行为耦合。

## §5 出站帧化（0213-0217 + 0216）

- **装配序实证**（应用树 Connection.java:766-771）：addLast 序
  [splitter, FlowControl, decoder, prepender, encoder]，出站遍历
  encoder→prepender；setupCompression addAfter("prepender","compress") →
  encoder→compress→prepender；cipher addBefore("prepender") →
  encoder→compress→prepender→cipher→socket。headroom attr 在同一写遍历内
  set/consume，身份匹配否则全量回退拷贝路径（嵌套 write 场景验证：内层
  覆盖 attr、外层身份失配 → 回退，字节流不受影响）。
- **0217 refcount 逐路径**：prepender 快路 too-large 分支显式 release 后抛
  同消息 EncoderException（MessageToByteEncoder 语义）；CompressionEncoder
  papoEncodeHeadroom 以 papoConsumed 标记所有权（too-big → finally 释放；
  below-threshold → ctx.write 转移；memo 命中 → in.release 后转移 out；
  全压缩 → 异常时 out 未发布先释放、in 由 finally 释放；ctx.write 同步失败
  由 netty invokeWrite0 释放）——每路径恰一次。attr 清洁：prepender 两分支、
  CompressionEncoder 两分支全部先清 headroom/memo 标记，陈旧标记最迟被下
  一次 write 的失配分支清除（仅钉 ByteBuf 包装对象，不钉池化内存）。
- **0215 界数学**：`n + n/2048 + 32 ≥ n + 5·⌈n/16384⌉ + 6` 全 n≥0 成立
  （n<16384 段由 +32 覆盖；n≥32768 段 8n/16384 ≥ 5⌈·⌉+5 恒成立），实测
  界（批 58 判例）两后端覆盖，resize-retry 退化为兜底。
- **0216**：重跑复用活 compressor（原实现双拒收 → 原生上下文孤儿泄漏已
  修）；非法级别 clamp 后重试仅改崩溃路径。级别重用时 memo epoch 不变
  （级别确实未变，复用分支语义一致）。
- **null-compressor 构造器为死代码**：唯一构造点（Connection:890）恒传
  工厂产物（Natives.compress 回退平台为 Java 工厂），papoEncodeHeadroom 的
  NPE 面不可达；即使可达亦被 catch(Exception) 收口、refcount 平衡、退化为
  断连。
- **0213/0214**：allocateBuffer 仅容量策略（帧字节不变）；per-connection
  尺寸提示表受包类数约束（几百），无淘汰为设计声明（结构性大包类才入表）。

## §6 入站 NBT/物品解码（0096/0097/0098/0153/0067/0068）

- **0096**（PapoByteBufDataInput）：bind 快照 endIndex（= Netty 构造器语
  义）；save/restore 双字段使 Tag.read 内重入 readNbt 正确；逐方法与 Netty
  4.2.7 ByteBufInputStream 比对（EOFException 消息逐字符、read()/read(b,o,l)
  -1 语义、skipBytes 静默截断、readLine 本地计数从不抛 EOF——批 29 javap
  实证）。**记录一处不可达差异**：mark/reset 直通 buffer 标记 vs Netty 自
  管 int mark——无 mark 时 reset 异常类型不同（IOE vs IOOBE）且共享 buffer
  级标记；NBT 读路径（NbtIo/TagType readers/DataInputStream.readUTF）零
  mark/reset 调用（全树核对），不可达。
- **0097**：剥离分支 `first ≥ 0 ⟺ 无续位`，值=first=first&127 与单次循环
  迭代一致；后续 do-while 移位链/`i1 > 5`（第 6 字节）抛点逐字节一致；
  EOF 由 readByte 同点抛出。
- **0098**：ClassValue 缓存 getEnumConstants 克隆（内容 JVM 生命周期不变，
  三调用点只索引/迭代不逃逸；非枚举类 null 透传 NPE 同点）。
  readEnum 的 readVarInt 越界索引 AIOOBE → DecoderException 与 vanilla 同。
- **0153**：ImmutableRegistryAccess volatile 单检缓存（benign race，两建皆
  有效）；CountingOps.INSTANCE 无状态（深度计数经每操作新 builder，批 37
  源码实证）；非 Immutable 走原路径。解码后 encodeStart 全量校验保留——
  恶意组件深度仍在校验面内。
- **0067 读侧**：ASCII 扫描对 byte<0（≥0x80）回退 JDK 解码器（含畸形序列
  UTFDataFormatException 保持）；**0x00 原始字节不回退**——JDK readUTF 对
  0x00-0x7F 单字节 char 直通（含 NUL），ISO_8859_1 快路同判（char 0），
  逐位等价。EOF 语义（readFully）与 vanilla 部分消费一致。
- **0068 读侧**：IntArrayTag Spigot 单侧 cap（`< 1<<24` 放行负数 →
  NegativeArraySizeException）为**上游既有**（vanilla 同点同型）；
  `_int<<2` 在 cap 下不溢出；readFully EOF 与逐 readInt EOF 同型。
  LongArrayTag 无 cap（vanilla 亦无），≤1<<20 走 bulk（buf ≤8MB）、更大
  逐元素流式（无 2x 峰值内存）；负长度 NegativeArraySizeException 同
  vanilla `new long[i]`。BE 视图解码 = readInt/readLong 序列逐位一致。

## §7 NBT 写侧（0092/0095/0075/0084/0085/0091/0040）

- **0092 papoWriteUtf**：单遍扫描 c==0||c>0x7f → writeUTF 回退（NUL 双字节
  0xC0 0x80 必须回退，已保持）；length>65535 委托 writeUTF 抛同一
  UTFDataFormatException；≤8190 单次批量写（前缀+内容一体）、更长
  writeShort+8190 分块——字节序与 writeUTF 逐字节一致（批 28 基准字节级
  自检全等）。ThreadLocal 8KB scratch 无重入面（write 不回调 NBT）。
- **0095**（PapoByteBufDataOutput）：buffer save/restore 防重入；writeUTF
  经惰性 DataOutputStream 包装（与 Netty ByteBufOutputStream.utf8out 同构，
  批 28 javap 实证）；逐方法字节级自检全等（批 28）。
- **0075**：IntArray/LongArray 写侧 BE 视图 put = writeInt/writeLong 序列；
  超界回退逐元素（IntArray >MAX_VALUE/4 防移位溢出、LongArray >1<<20 对称
  读侧）。
- **0084**：`ByteBufUtil.utf8Bytes` 与 `writeUtf8` 同编码器（孤代理 '?'
  同替换），字节计数恒等；异常序（长度→字节数）与终态（抛出时主 buffer
  零写入）一致；净效果省临时 ByteBuf 分配+拷贝。
- **0085**：单组件 BE buffer 直写 internalNioBuffer + writerIndex 前移，
  否则回退逐元素；ensureWritable 前置（容量不变式保持）。
- **0091/0040**：fastIterator entry 不逃逸循环体（key/value 即取即用）；
  迭代序 = 同背衬数组序；visitor 变异 map 时的 CME 与 vanilla entrySet 迭
  代器同型。非 fastutil map（CompoundTag(Map) 构造）instanceof 分支保
  持原路径。

## §8 压缩池（0103/0066）

- 借用（弹出槽位或新建）→ 归还（reset + 放回，顶替者交 GC/Cleaner）严格
  线程内平衡；每线程单槽，同线程并发双流时第二流新建（有界：每线程 ≤
  并发流数）。
- 显式 provided Deflater/Inflater 构造器置 usesDefault*=false（JDK 字节码
  实证，批 15/29）：close 不 end 原生上下文，reset() 恢复构造态（level/
  strategy 持久、mid-stream abort 后 reset 亦安全）。
- 换级退役（pooled.level != level → end()）处理配置 reload。
- **记录一处理论场景（不可达）**：同一包装流 double-close 且期间另一流借
  走同 Inflater 时，第二次 finally 会 reset 借出中的实例。全部调用点为
  try-with-resources 单次 close（MC/Paper 代码无 double-close 路径）。
- 缓冲加大（512→8192/32768）只改调用分块：DEFLATE 输出为输入流+flush 点
  的纯函数，批 15/29 字节级自检（三尺寸×池化三轮+同线程双流并发）全等。

## §9 交互路径（0137/0138/0140/0087/0139 + handleUseItemOn 门）

- 0137：`itemStack = hasListeners ? copy : null` 的 null 仅在两 trigger 均
  必早退的路径可达（监听器集变更与同 tick 同步块无交错——advancement 重载
  在主线程）；ANY_BLOCK_USE 门 `consumesAction() && hasListeners` 短路省
  copy；三 trigger 的 LootParams 构建为纯读。
- 0087：tryRemoveInternal 与公共 Optional 包装分支逐行同构（null ≡ empty），
  两个调用点（safeTake/doClick）null 检查保持。
- 0139：ItemCraftedEvent 零监听器门——callEvent 对零监听器新事件返回 true
  且无人消费返回值，跳过构造+派发不可观察。
- 2216/2227（RIGHT_CLICK_AIR/BLOCK 的 PlayerInteractEvent 门）：零监听器下
  useItemInHand 恒 DEFAULT（cancelledBlock 仅置 useInteractedBlock），
  `cancelled = false` 与原判定恒等；事件不可变更 → :2248 重读 itemInHand
  无害。
- 0138/0140 为 Location/Vec3 分配消除（批 34 逐点等价分析入档），本轮对
  交互面重读未发现副作用序变化。

## §10 内存界 / 已接受缺陷状态 / 功能完整性

- 本族新增常驻结构全部有界：PacketEncoder 尺寸提示表（每连接、包类数上
  界）、headroom attr（每通道单 buffer、每 write 清）、ThreadLocal
  scratch/适配器（每线程固定小对象）、枚举 ClassValue（每类一次）、
  Inflater/Deflater 池（每线程单槽）、每连接 AtomicLong×2（随连接回收）、
  0220 k-近邻 scratch（每 player 实例字段，grow-on-demand 有上限 64）。
- 0267（join 预取无界等待）/0268（0245/0246 locale 冻结）/0269（0241/0242
  缓存常驻）：批 133 核对后至本轮零提交触及相关文件（git log 核对），
  决断维持，未复发未扩散。
- 功能完整性：/paper netstat 全链核对——计数器经 configureSerialization
  穿线（服务端接入两方向、memory 通道豁免 null）、tickSecond 每秒
  getAndSet 快照与累计、NetstatCommand 注册于 PaperCommand（权限门
  bukkit.command.paper.netstat）；出站计压缩后加密前=精确 wire 字节，
  入站对称。0222/0225 计数点与帧交付同步（帧完整才计）。

## §11 构建验证

applyPatches 全量重放（补丁 0154 手编后）EXIT=0，源码树与补丁逐字节同步；
compileJava --rerun-tasks BUILD SUCCESSFUL。补丁 hunk 计数经
note/check_patch_counts.py ALL OK。

## 判例（入库）

1. **快路复刻"可观察结果"时，判据的求值时点本身是可观察语义的一部分**——
   原路径在副作用（clicked()）前的读取（事件构造用的 recipe/result）被挪
   到副作用之后，即使"读什么"逐字相同也会在读取值翻转的场景分叉。复刻
   清单必须带时点（本判例为批 133"stamp 读取点必须先于数据生产起点"的
   对偶：**消费侧判据的读取点必须对齐原路径的判定点**）。
2. **ByteToMessageDecoder 下游收编语义**：上游帧 slice 被首个下游
   ByteToMessageDecoder 直接采纳为 cumulation（首消息零拷贝），其
   finally 释放承担 refcount——retainedSlice 优化在 splitter→decompress
   链上的安全性依赖此结构而非"下游只读"假设。
3. **单槽池的 double-close 窗口**：close 内归还池的 finally 使"close 幂等
   性"从 JDK 语义（二次 close no-op）变为"二次 close 归还两次"——当借
   出 interleaving 存在时跨流污染；调用点全 try-with-resources 时不可达，
   但该模式复用前应核对全部 close 调用点。
