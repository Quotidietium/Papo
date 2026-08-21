# 批次 74 JMH 微基准报告（2026-08-22）— join 静态大包构造缓存 + 双 memo（0245）

主题：**多玩家网络稳定（join 突发域）**。0226 缓存了 tags/registry 包的**构造**（跨 join 共享
实例），但其**编码 + DEFLATE** 仍每 join 连接各做一次；`ClientboundUpdateRecipesPacket` 更是每
join **重新构造**（PlayerList:199，`new` per join）——~100-300KB 的配方同步数据，0129 量级下
level-6 压缩毫秒级/join。重启后/广告后大量玩家涌入的 join 突发是多玩家网络稳定的关键窗口。

本批（0245）：
1. **recipes 包 join 静态构造缓存**（0226 模式）：`PlayerList.papoRecipesPacket()` 惰性单实例，
   join 路径与 `reloadRecipes()` 广播共用；失效信号 = `RecipeManager.finalizeRecipeLoading`
   （同步数据 propertySets/stonecutterRecipes 的**唯一赋值点**，实证 RecipeManager.java:137-139）
   → 无参重载调 `PlayerList.reloadResources()` → `reloadRecipes()` 清缓存——数据变化的下游必经
   链完备（addRecipe/removeRecipe/clearRecipes/datapack reload 全经 finalizeRecipeLoading）。
2. **三族 join 静态包 arm 双 memo（编码+压缩）**：tags（SynchronizeRegistriesTask 缓存构建处 +
   PlayerList.reloadTagData 处）、registry 24 包（SynchronizeRegistriesTask 缓存构建处，mismatched
   即 Via 全量 NBT 分支收益最大）、recipes（PlayerList 缓存构建处）。静态缓存实例长期驻留，
   快照一次性常驻（合计 ~200-500KB，远小于 0241 的 chunk 缓存面）；reload 换新实例，旧 memo
   随旧实例 GC。
3. `ClientboundRegistryDataPacket` / `ClientboundUpdateRecipesPacket` 为 record——**不能声明实例
   字段**，改为同构造形态的 final class（组件访问器同名保留，codec 的构造器/方法引用不变；
   grep 实证全树无 equals/hashCode 消费者）。

环境：JDK 21.0.10（Windows 11，JDK Deflater 回退），JMH 1.37，`-wi 3 -i 5 -f 2 -r 1s -w 1s`，avgt。

## 1. 等价性

- **构造缓存（recipes）**：包内容两次 finalizeRecipeLoading 间逐字节恒定（唯一赋值点实证；
  reload 以整体替换字段而非原地修改——stream collect 产生新 map/list）；包对象跨玩家共享为
  vanilla 既有行为（reloadRecipes 本就单实例广播全体在线玩家，join 路径改走缓存后与之同构）；
  getSynchronizedItemProperties 返回内部 map 引用——外部 NMS 原地篡改不在兼容红线内（与 0226
  同等论证）。
- **memo（0242/0244 机制沿用）**：tags/registry/recipes 三类 codec 均为确定性、locale 无关的
  纯数据写出（registryAccess 服务端全连接同源）；编码快照=首连接同 buffer 产出，压缩段=自描述
  deflate 快照——命中/未命中线上字节逐字节相同；threshold 戳 + level 纪元键控；配置阶段连接
  压缩已启用（login 中段 setup）✓。
- **record→class**：同构造器形状 + 同访问器名；仅失去 record 自动 equals/hashCode/toString——
  全树 grep 实证无消费者。

## 2. 基准（JoinPacketMemoBench，模型=1200 配方×3 物品栈×8 组件词）

载荷 91,731B、压缩比 2.02（贴近真实 recipes 包 ~100-300KB / 可压缩集合流形态；首轮纯 varint
模型 8.9KB 被自检带外证伪后按 ItemStack 组件形态修正）。

| 方法 | ns/op | 说明 |
|---|---|---|
| before | 7,229,623.601 ± 767,635.110 | 每 join：编码走查 + DEFLATE + 帧（**~7.2ms**） |
| afterFill | 6,923,850.780 ± 388,605.392 | 首 join：before + 双快照（CI 重叠） |
| afterHit | **167,837.986 ± 9,503.961** | 后续 join：编码 memcpy + 压缩段 memcpy + 帧 |

- **命中路径 ≈ 43×/join**（CI 分离）；**每 join 省 ~7.1ms**（仅 recipes 一项；tags/registry 同
  机制另计——0226 实测 tags 构造 0.21ms + 编码压缩毫秒级）。
- 场景外推：重启/活动后 50 玩家 join 突发 ≈ **355ms CPU 消除**（此前集中爆发在 join 窗口）。
- 自检 main ALL OK：10 join 两路径 wire 字节逐 join 全等 / 编码确定性 / 压缩比带内。

## 3. 未做与留档

- `sendLevelInfo` 双发 / `initInventoryMenu` 双发（批次 64 红线外留档项维持——时点与插件可见性）。
- recipe book 初始包（per-player 状态，不可共享）。
- advancement 初始包（per-player 进度）。

## 验证链

compileJava BUILD SUCCESSFUL → 基准自检 ALL OK → JMH → rebuildPatches（0245）→ 完整 applyPatches
→ 全量 test（见 optimizations.md 批次 74 记录）。
