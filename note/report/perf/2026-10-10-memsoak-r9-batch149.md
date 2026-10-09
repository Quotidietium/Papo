# R9（批次149）：常量节规范化共享——节/容器实例层坍缩（0.86.0 → 0.87.0）

- 日期：2026-10-10
- 版本：0.86.0 → 0.87.0（补丁 0272）
- 前置：r8 后 0.86.0 树最新普查（1,120MB 转储，6,145 区块）；本会话 A-086 基线
  797.1MB（r8 会话同二进制 801.4–817.4MB——会话间绝对漂移 ±2% 判例维持，跨会话
  只比配对差与每区块率）。

## 0. 摘要

节/容器实例层是残余画像中最大的非内容簇：398,976 个 LevelChunkSection 壳
（40B）+ 797,953 个 PalettedContainer 壳（24B×2/节）+ 398,976 个 ShortList 壳
（24B，批142 已惰性化但壳仍每节一个）+ 每 uniform 容器 Data（32B）与单条目
palette（24B）≈ **224B/节**。本轮把"整个内容 = 单一方块状态 + uniform 注册
biome + 计数可解析（无随机刻/流体刻/特殊碰撞）"的常量节坍缩为**每
(方块全局 id, biome 注册表 id) 每注册表一个规范实例**（0272）——被坍缩节的全部
六件套（节壳/双容器/双 Data+palette/tick 壳）归零，容器经批147 钩子与全服其他
常量容器共享 Data。

**配对浸泡（12 bot、6,080 vs 6,098 区块、门全绿，配对差 +0.30%）：堆
797,078 → 780,005 KB = −16.7MB（−2.14%）**；B 腿多 18 区块（≈+1.5MB），区块
调整后净 ≈ −18.2MB。归因闭合：**LevelChunkSection −76,126（−19.2%）、
PalettedContainer −152,252、ShortList −76,126、LinearPalette −~70k、Data
222k→150k；76,126 × 224B = 17.0MB ✓**；内容面零触碰（SWMRNibbleArray byte[]
每区块 4.939 vs 4.946 持平；long[] −1.6MB = 被释放的 1~2 位微型均匀存储，非
内容）。

## 1. 角度与算术修正

- 立项算术（87% 单值节 × 112B ≈ 38MB）源自 r7 普查的"**方块侧**单值 palette
  87%"；实际可坍缩要求**双均匀**（states 与 biomes 同时均匀）——biome 随深度
  三维变化使地下均匀石节多为 biome 多值。实测坍缩率 **19.2%**，收益 −16.7MB
  而非 38MB。判例：复合谓词的可行率必须按**所有分量的交集**预估（单分量统计
  高估）。
- 每 uniform 节实际成本 224B（立项时只数了 112B 四件套，漏了 Data×2 与
  palette×2——批145/147 已共享"载荷"，但私有容器各自的 Data 壳与单条目
  palette 仍在）：两处误差大部分互消，最终每节实测 ≈ 220B ✓。

## 2. 设计（0272）

### 2.1 均匀性以"格子"判，不以"palette 型"判

首配对浸泡（判负腿）暴露的关键缺陷：谓词初版只认 `SingleValuePalette`，仅坍
缩 1.4%——**世界生成经由写路径构造均匀节，palette 是长出来的**（biome 容器
首 quart 写入非默认 biome 即扩容为单条目 LinearPalette；整节写入石头同理），
dump 直方图证实全服 SingleValuePalette 仅 11 个（批147 共享集本身）。
`PalettedContainer.papoUniformValue()` 改为按格子判定：零位存储快路径（每格皆
id 0）→ raw long 全等快速否决 → 全等后逐格核对（等词是必要不充分，位跨需逐
格裁决）；值 = `palette.valueFor(格值)`，与 palette 型无关。

### 2.2 规范实例与缓存

- 缓存挂**每注册表 biome Strategy 实例**（批147 判例③）：随 level 卸载整体回
  收；键 = 方块全局 id << 32 | biome 注册表 id——方块 id 全局（BLOCK_STATE_
  REGISTRY）、biome id 注册表域内，复合键跨世界不碰撞。
- 规范节容器经批147 单值 Data 共享钩构造（与全服常量容器同 Data）；计数解析
  式（nonEmpty = isAir?0:4096，tick/特殊计数为零），谓词防御性核验节自身计数
  与解析值一致后才共享。
- 未注册 biome（direct holder）/混合节/随机刻/流体刻/特殊碰撞均匀节/客户端节
  保持私有；Anti-Xray preset 容器（多条目 palette）由格子判定自然覆盖（均匀
  preset 节同样可坍缩——packet 内容逐格相等，obfuscation 从控制器侧取 preset
  与节 palette 无关）。

### 2.3 写者审计与门

全树写者面枚举后收口为**六门**：LevelChunk.setBlockState 与
ProtoChunk.setBlockState（均置于既有 air-into-air no-op 快路径之后——最常见
写"放空气"永不物化）、ChunkAccess.setBiome、ChunkAccess.fillBiomesFromNoise、
NoiseBasedChunkGenerator doFill 双获取点、OreFeature（经新增
BulkSectionAccess.getWritableSection——读路径 getSection 原样，写变体替换缓存
中的 canonical 项并转移 acquire/release）。客户端包路径
（replaceWithPacketData/replaceBiomes）改索引循环门控。**每个节变更器
（setBlockState/setBiome/fillBiomesFromNoise/read/readBiomes/recalcBlockCounts）
内置响亮金丝雀**——漏门写者立即崩溃而非静默腐蚀所有共享该节的 chunk。旧
Bukkit 生成器路径（CustomChunkGenerator）以整槽置换注入私有 scratch 节，无需
门（核安全）。

### 2.4 规范化时机与保存格式

单一规范化点 = LevelChunk 主构造器扫描（世界生成晋升与磁盘装载两路皆汇于此，
空白槽替补节亦然），使用 level 长寿命 factory（缓存命中按世界）。规范节以单值
palette 落盘——vanilla 自身对从未扩容节的编码，全版本可解码且更小（内容逐格
恒等）。

## 3. 验证

- **单测**：PapoSharedConstantSectionTest **11 项**（Bootstrap 真实方块状态 +
  合成 biome IdMap/Holder，无需数据包；PapoRegressionTestSuite 聚合，批145/
  147/148 三套件维持）：坍缩同一性 + 每注册表缓存、解析计数 + 分 biome 键、
  strategy 实例隔离、**uniform-写路径节坍缩**（首 soak 抓到的缺陷的确定性回
  归：整节写石头 + 全 quart 写非默认 biome）、随机刻/混合/未注册拒绝、拷贝值
  精确 + 写隔离、六变更器金丝雀、写门槽位置换 + 私有直通、300 轮 × 120 写随
  机差分对拍（vs 全私有镜像节）。全绿（tests=11 failures=0）。
- **全链**：rebuildPatches 0272 + applyPatches 重放 EXIT=0（谓词修正后二次重
  放）+ compileJava --rerun-tasks + check_patch_counts ALL OK；0.87.0 jar 内嵌
  字节码 javap 复核（papoUniformValue/papoTryCanonicalise/字段在嵌套 jar 内）。
- **配对浸泡**（门 = 零错误 + exit 0 + 区块数 ±1%）：

| 腿 | 堆 (KB) | 区块 | 节 | 容器 |
|---|---:|---:|---:|---:|
| A-086 | 797,078 | 6,080 | 396,384 | 792,769 |
| B-087 | 780,005 | 6,098 (+0.30% ✓) | 320,258 | 640,517 |

**−17,073 KB（−2.14%）**；区块调整后 ≈ −18.2MB，普查账目 76,126 × 224B =
17.0MB 同向闭合。

- **测量过程判例应用**：第一跑（谓词修正前）B 腿坍缩仅 1.4% → dump 直方图定
  位 palette 型缺陷；第二跑 B 腿区块 −29.6% **配对门破** → 按批146 判例复跑
  （服务端健康：boot 15.9 vs 16.6s、零错误）→ 第三跑门绿。每区块率在两跑间
  一致（节/区块 −14.3% vs −19.2%，B 内二跑成分差），证实第二跑为环境插曲。

- **普查归因闭合**（B 腿第三跑转储）：节 −76,126 / 容器 −152,252 / ShortList
  −76,126 / LinearPalette −~70k / Data 222,336→150,229；SingleValuePalette
  11→28（批147 集 + 17 个规范节键）；内容面：SWMR byte[] 每区块 4.939 vs
  4.946（持平）、long[] −1.6MB = 释放的 1~2 位微型均匀存储（非内容）、byte[]
  总量持平。

## 4. 账本与主线

- 本轮：**−16.7MB（−2.14%）**，归因精确闭合。
- 内存主线 0.80.0 → 0.87.0：同会话链 1,154.3 × (1−28.0%) × (1−1.96%) ×
  (1−2.14%) ≈ 797.5MB 预期 vs 本会话实测 780.0MB（会话整体偏瘦 ~2%，±2% 漂移
  判例内）= **−374.3MB（−32.4%）**。
- 残余画像：SimpleBitStorage 内容 ~135MB（红线）；节/容器层剩 320k 节（多
  为 biome 多值或方块混合的真实数据节）；nibble 非均匀载荷 ~62MB（真实光数
  据）；String 12.5MB（批146 关闭）。

## 5. 判例（入库）

1. **复合谓词的可行率按分量交集预估**：87% 是"方块侧单值"，双均匀要求 biomes
   同时均匀（三维 biome 随深度变化砍掉地下石节）——实测 19.2%。单分量统计
   外推动辐可达 4 倍。
2. **均匀性以格子判，不以容器表示型判**：世界生成的均匀内容经由写路径构造，
   palette 是长出来的（单条目 Linear/HashMap 而非 SingleValuePalette）——
   dump 直方图（全服 SingleValuePalette 仅 11 个）是谓词假设的直接证伪面。
3. **响亮金丝雀优于静默防御**：共享不可变对象的每个变更器入口抛异常，漏审计
   写者立即崩溃而非跨 chunk 静默腐蚀——浸泡 12 bot + 全世界生成即全写路径
   回归测试。
4. **配对门破先复跑再归因（批146 判例第三次应用）**：第二跑 B 区块 −29.6%，
   boot 时间一致 + 零错误排除服务端病因，复跑门绿证实环境插曲；每区块率在
   判负腿与干净腿间可比（成分差除外）。
5. **实例层收益按"全部随实例死掉的壳"计**：节坍缩带走的不是 112B 四件套而
   是 224B（含双 Data 壳与双 palette 壳）——立项算术须枚举随宿主对象生死的
   全部成员。
