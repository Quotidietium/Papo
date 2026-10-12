# R45（批次 186）：BE 密集内容轴普查 → 空容器 BE 驻留机械面收割（0.107.0 → 0.108.0）

日期：2026-10-12 ｜ 版本：0.107.0 → 0.108.0 ｜ 类型：新场景轴（R38 判例：从未普查过的场景即新证据等级）

## 1. 轮目标与场景选择

R24-R44 历轮驻留/分配普查的窗口全部是**自然地形**（bot 流送 + 稳态 tick），场景中容器类方块实体
（箱/桶/炉/漏斗）计数近乎为零——而真实服务器（生存/模组服）的驻留大头恰恰是**海量空容器 BE**。
本轮以 BE 密集内容轴补上这块从未成像的地图：

**工具**：`benchmark/src/papo/bot/BeDensifyCensus.java` + `benchmark/becensus-r45legs.sh`
（MemSoakVerify-fork：12 bot 撒布后，经控制台 stdin 按 bot 实际坐标分带填充
11 条带 × 150×3 格、y=200、类型 barrel/chest/furnace/hopper 按 s%4 轮转，
每 bot 4,950 BE、12 bot 合计 59,400 BE，`PAP0-FILL-DONE` 标记同步，之后 GC×2 + heap_info + class_histogram）。

## 2. 发现腿（同二进制 0.107.0 配对，自然 vs 密集）

| 面 | A 自然 | B 密集 | Δ |
|---|---|---|---|
| 堆 used | 628,269KB | 710,777KB | **+82,508KB** |
| NonNullList | 815 | 60,216 | +59,401 |
| Arrays$ArrayList | 1,296 | 60,697 | +59,401 |
| 普查 BE 计数（桶/箱/炉/漏斗） | ~500 | 59,900 | 59,400 只空 BE |

**逐面判定（收割/判负）**：

收割（机械面，空 BE 即付）：
1. **物品列表三件**：`NonNullList.withSize(n, EMPTY)` = NonNullList 壳 + Arrays$ArrayList + Object[n]
   （27 槽箱/桶 ≈248B、5 槽漏斗/3 槽炉 ≈56B——按 8B 引用口径；实际压缩指针更小）。
2. **PDC 三件中的两件**：Paper `BlockEntity.persistentDataContainer` 为 final 字段急初始化——
   容器实例本体（32B）保持（final 字段初始化=Paper API 契约），其 **HashMap（48B）** 与
   **AdapterContext（view 急初始化）** 可懒化；适用于全部 BE + 实体 + 物品 meta + 世界。
3. **炉 quickCheck**：ctor 急建的 `RecipeManager.createCheck` 闭包（~64B/炉），
   唯一读点在 serverTick 的有输入分支内。

判负留档：
- **lootableData**：Paper 用 null 表达"未开启补给跟踪"（CraftLootable 监听器判断），懒 null 会与语义碰撞；
- **ChestLidController**：tick 路径首 tick 即物化，懒化无收益；
- **dataAccess 匿名类**：与 menu 耦合（打开即建），BE 密集场景不开箱不触达；
- **BoundTickingBlockEntity/Rebindable 包装**：vanilla tick 架构面，改写=稳定性红线；
- POI/AABB/palette/SWMR/light/blockTicks 增量：内容面或既有优化（R33/R34）已覆盖。

## 3. 收割实现（四件）

### 3.1 PapoLazyItemsList（净新增，0296 补丁）
`net.minecraft.world.item.PapoLazyItemsList extends NonNullList<ItemStack>`：
- 空态 = `SHARED_EMPTY_VIEWS[n]`（`List.of nCopies(i, EMPTY)`，32 档全共享，零 per-BE 数组）；
- `get` 空态走共享视图、`set` 物化为 `Arrays.asList(filled Object[n])`（与 vanilla 同底结构）、
  `size` 返回 fixedSize、`clear` 置 null 回共享视图（vanilla clear=set 回 EMPTY，观察等价）；
- `add/remove` 不覆写 → 经 nCopies 视图抛 UOE，与 vanilla 定长表逐位一致；
- **11 个容器 BE、19 个构造点**替换：Barrel(27)/AbstractFurnace(3)/BrewingStand(3)/Campfire(4)/
  Chest(27)/ChiseledBookShelf(6)/Crafter(9)/Dispenser(9)/Hopper(5)/Shelf(19)/ShulkerBox(27)。

### 3.2 炉 quickCheck 懒化（0296 补丁）
字段改 `@Nullable papoQuickCheck` + 物化访问器；两个 `getRecipeFor` 读点均已被"有输入"标志守卫
（空炉=驻留常态永不物化）；`recipeType` 为 public final，物化时创建的正是 ctor 原来急建的那个闭包。

### 3.3 CraftPersistentDataContainer map 懒化（src/main 直跟踪）
`customDataTags` 改 `@Nullable`：读全走 `papoTagsView()`（空态=不可变 `emptyMap()`，
hashCode 0 == 空 HashMap、逐元 equals、空迭代、空 NBT 全等价）；写物化；
`remove`/空 `putAll` 不物化；`clear` 置 null。
- `equals`（CraftMetaItem.equals 无守卫热路径）走读视图比较，两侧空容器比较**零物化**；
- `getRaw()` 活 map 契约 → 物化；三个外部调用方（view copyTo / CraftMetaItem 有守卫序列化 /
  DeprecatedContainerTagType 包装）审计均为即用即弃、不跨 clear() 持有；
- 双参构造别名审计：vanilla 别名传入 map；唯一活别名消费方 `DeprecatedContainerTagType:43`
  别名的是 toPrimitive 内**瞬态转换产物**（返回后不可达，快照在 set() 时即取），
  拷贝与别名观察等价；`CraftMetaItem:2067` 传入即弃深拷贝；`DirtyCraftPersistentDataContainer(Map,·)` 树内零调用方。

### 3.4 PaperPersistentDataContainerView adapterContext 懒化（src/main 直跟踪）
急初始化字段改 `@Nullable` + protected 物化访问器，`get()`/`getAdapterContext()` 走访问器。

## 4. 恒等性

- 单测 11 用例入 `PapoRegressionTestSuite`：
  - `PapoLazyItemsListTest`（5）：空态 oracle 差分（尺寸/equals/hashCode/toArray/迭代 vs
    `NonNullList.withSize`）、写后读 oracle 差分（assertSame EMPTY、IOOBE 位次一致）、
    UOE 位次（两态）、clear 回空态、sort+迭代器语义；
  - `PapoPersistentDataContainerLazyTest`（6）：懒空 == 物化空（oracle=put+clear）、
    remove/空 putAll 保持懒态、写物化且往返、copyTo 语义、getRaw 活 map 契约、putAll(CompoundTag)。
- 套件实测 **9,082 / 0 失败**（9,071 + 11）。

## 5. 链门

内部 commit（lazy container block entity item lists and furnace quick check）→
rebuildPatches（**0296** 补丁，13 文件：1 新增 + 12 修改）→ applyPatches 全量校验 →
`:paper-server:test` BUILD SUCCESSFUL（9,082/0/0）→ `createPapoJar` 出
**Papo-1.21.11-0.108.0.jar**。全部 EXIT=0。

## 6. 配对验证腿（dense-0.107 vs dense-0.108，同场景同普查）

| 面 | A=0.107 密集 | B=0.108 密集 | Δ |
|---|---|---|---|
| 堆 used | 711,482KB | 701,488KB | **−9,994KB（−1.4%）** |
| NonNullList | 60,220 | **183** | −60,037 ≈ 全数归零（自然带量级） |
| Arrays$ArrayList | 60,701 | **669** | −60,032 ≈ 全数归零 |
| java.util.HashMap | 174,600 | **94,038** | −80,562（≥59,400=普查 BE PDC map；余为聚合类跨窗带） |
| [Ljava.lang.Object; | 619,040 | 552,129 | −66,911 实例 / −6.0MB |
| 普查 BE（桶/箱/炉/漏斗） | 59,905 | 59,904 | 组成恒等（±1） |
| fills/marker/gate | 132✓/达✓/净✓ | 132✓/达✓/净✓ | 双腿全绿 |

口径说明：B 腿载入 6,037 节 vs A 6,156（−1.9%，探索/生成跨腿带），BE 计数与普查组成恒等，
堆差以场景恒等为口径；−9,994KB / 59,400 BE ≈ **−172B/空 BE**（物品三件 + PDC map/adapter + 炉闭包，
与压缩指针下逐件合计的量级带一致）。

## 7. MSPT 三序守护（A=0.107 vs B=0.108，主/倒/钉死；win5s 均值逐槽配对）

**有效性门（本轮新增）**：每腿 `TRACE[MlpB` 行数 84-90（≥12，bot 在线正证据）、
零类版本错误（见 §8 判例 5 的首跑判废事故）。

| 槽（相位） | 主序 d | 倒序 d | 钉死 d |
|---|---|---|---|
| s01 stream | +0.5 | −0.1 | −0.4 |
| s02 stream | −0.3 | +0.1 | −1.8 |
| s03 stream | −1.0 | +0.3 | −0.6 |
| s04 dwell | −0.1 | −0.8 | +0.3 |
| s05 dwell | −0.6 | +0.4 | +0.5 |
| s06 dwell | −0.2 | +0.3 | +0.0 |
| B 优计数 | 5/6 | 2/6 | 3/6 |

- **无任何槽三序同号劣化**——每处 B 劣化出现均被他序反号否决（R38 判例：真系统差同序必复现），
  最差单槽 +0.5ms << 腿内摆幅（stream 相位 A 腿 s01→s03 跨 3.6→8.5ms）；
- 六槽×三序池中位：A 3.6/5.4/7.8/5.5/4.6/4.7 vs B 3.6/4.8/6.8/5.7/4.6/4.7——
  B 五槽平/优、一槽 +0.2；
- **结论：零回归**。对照：判废的首跑空服腿 stream 仅 0.2-0.3ms，真腿 3.4-8.5ms——
  bot 在线门的分辨力自证。

## 8. 判例

1. **BE 密集内容轴是独立成像域**：自然地形普查对容器面零覆盖，地图补全即立项证据（R38 新证据等级）；
2. **急初始化 final 字段的懒化边界**：字段本身是 API 契约（`persistentDataContainer` final）时，
   收其**字段内部**的急初始化（map/adapter）而非字段本身；
3. **别名构造的观察等价判法**：别名产物生命周期若封闭在工厂方法内且快照即取，拷贝==别名；
4. **共享只读视图替代 per-instance 空表**（R5-轮2 ShortList、R20 O2O map 同族）在
   `NonNullList.withSize(n, EMPTY)` 上的第三度应用，且 add/remove 位次经由共享视图的 UOE 保持；
5. **腿有效性验收必须含 bot 在线正证据**：MSPT 首跑四腿在共享 build/classes 被 PATH javac
   （JDK 25，类版本 69）污染后由 J21 harness 加载即全 bot `UnsupportedClassVersionError`——
   server 空转但采样按时间表照常进行、腿 exit=0，**空服腿静默通过**（`grep MlpB`=0 揭发）；
   server 侧两 harness 均 ProcessBuilder 常量 J21 起服，普查腿测量不受污染。修复=bot 包
   J21 重编+两脚本工具链钉死+TRACE 行数/类版本错误双验收门（已入 build.md 判例）。

## 9. 结论

空容器 BE 每 only ~172B 机械驻留归零（四件套），59,400 BE 普查规模实测 −9,994KB；
恒等性由 11 用例 oracle 差分钉死，链门/配对腿/MSPT 守护全绿。BE 密集轴从"从未成像"到"收割闭合"。
