# Papo (Paper 1.21.11 fork) 构建环境笔记

> 记录日期：2026-07-27，基于分支 `ver/1.21.11`，在 Windows 11 上实测通过。

## 环境要求

| 项目 | 要求 | 本机实测 |
|---|---|---|
| JDK | 21（编译工具链固定为 21，见根 `build.gradle.kts`） | Oracle JDK 21.0.10（`C:\Program Files\Common Files\Oracle\Java\javapath`，PATH 中可用，`JAVA_HOME` 未设置也不影响） |
| Gradle | 由 wrapper 管理 | **本 fork 已改为 9.4.1**（见下文「网络问题」），上游原版为 9.2.0 |
| 网络 | 需访问 Mojang（piston-data）与 PaperMC（repo.papermc.io）仓库 | 均可正常直连 |

Gradle 会自动探测 PATH 中的 JDK 21 作为 toolchain，无需手动配置 `org.gradle.java.installations.paths`。

## 网络问题与解决（重要）

首次执行 `./gradlew` 时 wrapper 需要从 `services.gradle.org` 下载 Gradle 发行版，本机网络下：

- `services.gradle.org` 默认 10 秒 `networkTimeout` 直接超时；
- 其 307 重定向到 `github.com/gradle/gradle-distributions/releases`（实际资源在 `objects.githubusercontent.com`），本机同样无法连通；
- 本机 `~/.gradle/wrapper/dists/` 中已有完整缓存的 **Gradle 9.4.1**。

**解决方案**：将 [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) 的 `distributionUrl` 从 9.2.0 改为 9.4.1（已提交）。实测 paperweight `2.0.0-beta.19` 在 Gradle 9.4.1 下配置与构建均正常。

若以后需要在其他机器/环境构建：

1. 优先让 wrapper 正常下载（网络通畅时无需任何改动）；
2. 或手动把对应版本的 `gradle-<ver>-bin.zip` 放入 `~/.gradle/wrapper/dists/gradle-<ver>-bin/<hash>/` 并解压（wrapper 检测到解压目录与 `.ok` 标记后直接复用）；
3. 或直接使用本地已安装的 Gradle 二进制绕过 wrapper：
   `~/.gradle/wrapper/dists/gradle-9.4.1-bin/<hash>/gradle-9.4.1/bin/gradle <task>`

> 注意：构建过程中依赖下载走 `repo.maven.apache.org` 与 `repo.papermc.io`，Minecraft 服务端 jar / mappings 走 Mojang 官方 CDN，这些在本机网络均可直连。如果将来这些也受阻，需要另行配置镜像或代理。

## 构建步骤

```bash
# 1. 应用全部补丁（生成 paper-server/src/minecraft 等可编辑源码树）
./gradlew applyPatches

# 2. 编译并打包 Mojmap bundler jar
./gradlew createMojmapBundlerJar
```

产物位置（`paper-server/build/libs/`）：

- `paper-bundler-1.21.11-R0.1-SNAPSHOT-mojmap.jar`（约 93 MB，完整可运行 bundler）
- `paper-server-1.21.11-R0.1-SNAPSHOT.jar`（约 27 MB）

实测首次耗时（i9 级 CPU / SSD，仅供参考）：`applyPatches` 约 5 分 22 秒，`createMojmapBundlerJar` 约 2 分 17 秒。之后有 configuration cache 与增量编译，秒级到一分钟级。

## 日常开发常用任务

| 任务 | 作用 |
|---|---|
| `./gradlew applyPatches` | 应用 `patches/` 下全部补丁，生成可编辑源码 |
| `./gradlew rebuildPatches` | 修改源码后重新生成文件级补丁（**修改源码后必须执行**，否则补丁不同步） |
| `./gradlew createMojmapBundlerJar` | 构建 Mojmap 版 bundler jar（插件开发/分发用） |
| `./gradlew createReobfBundlerJar` | 构建 reobf（Spigot 映射）版 bundler jar |
| `./gradlew runServer` | 起 Mojmap 测试服（工作目录 `run/`） |
| `./gradlew runDevServer` | 不打 jar 直接起测试服，支持热重载 |
| `./gradlew runPaperclip` | 从 paperclip jar 起测试服 |
| `./gradlew test` | 运行 JUnit 测试套件 |
| `./gradlew printMinecraftVersion` / `printPaperVersion` | 打印版本号 |

> `runServer` 系列任务在 `paper-server/build.gradle.kts` 中指定了 **JetBrains 厂商的 JDK 21**（`JvmVendorSpec.JETBRAINS`），由 foojay-resolver 自动下载；若本机没有 JBR 且网络受限，这些 run 任务可能失败（不影响编译打包）。

## 修改 Minecraft 源码补丁的正确工作流（重要，踩过坑）

`paper-server/src/minecraft/` 下有一个 paperweight 维护的**内部 git 仓库**（`src/minecraft/java/.git`），外部 `patches/` 目录才是提交进版本库的"事实来源"。两者通过 gradle 任务同步：

1. **修改源码**：直接编辑 `src/minecraft/java/...` 下的文件。
2. **`./gradlew :paper-server:fixupSourcePatches`** —— 把未提交的源码修改以 `git commit --fixup` + `rebase --autosquash` 合并进内部仓库的 "paper File Patches" 提交。
3. **`./gradlew :paper-server:rebuildPatches`** —— 从内部仓库重新生成 `patches/` 下的补丁文件（会自动 `git add`）。

注意：

- **不要**只跑 `rebuildSourcePatches` 而不先 `fixupSourcePatches`：rebuild 会先 `git stash` 未提交的修改再重建，未 fixup 的修改会"丢失"（补丁重新生成后不含你的改动）。
- **不要**在同一次 gradlew 调用里串联 `fixupSourcePatches rebuildPatches`：实测会在内部 `git stash push` 时失败并**清空 patches 目录**（可用 `git restore --staged paper-server/patches && git restore paper-server/patches` 恢复，因为补丁已提交）。分开两次调用即可。
- 直接手工编辑 `patches/` 下的 `.patch` 文件也是合法的（它们是事实来源），但之后必须跑**完整的** `./gradlew :paper-server:applyPatches` 同步源码树。只跑 `applySourcePatches` 会把 feature 补丁（如对 log4j AsyncAppender 的修改）从源码树里抹掉，导致莫名编译错误。
- 内部仓库的 `file` 标签标记 "paper File Patches" 提交的位置，fixup/rebase 后若标签未跟随移动（任务中断时会发生），rebuild 会从旧提交重新生成补丁，表现为"修改神秘消失"。修复方法：`cd paper-server/src/minecraft/java && git tag -f file <新的paper File Patches提交哈希>`。

## 2026-07-28 补充：性能移植批次踩坑记录

### Gradle daemon 挂死（重要）

本机多次出现 Gradle daemon 挂死：daemon 进程活着但只做健康检查，客户端进程无输出死亡。规律是**前台 Bash 命令因超时被提升为后台任务时**必然触发。

**解决办法**：所有 gradlew 调用一律加 `--no-daemon`，并且从一开始就用 `run_in_background=true` 启动。清理残留 daemon 时按 PID 逐个 `Stop-Process`（不要用 blanket java 杀进程，会误杀）。

另：**不要在同一次调用里串联 `applyPatches` 与 `compileJava`/`test`**——Gradle 9 的隐式依赖校验会报 `applyResourcePatches`/`processResources` 冲突直接失败。applyPatches 永远单独一次调用，编译测试再另跑一次。

### 手工编辑 .patch 文件的三个硬性规则

codechicken diffpatch 引擎非常严格：

1. **空白上下文行必须是单个空格**。空上下文行在 diff 里是一个空格字符；某些编辑器/工具（包括 Write 工具）会剥掉行尾空格把它变成真正的空行，引擎直接拒绝（报 "Patch engine failure" / "Failed to apply N/M hunks"）。修复：用 python 把补丁里长度为 0 的行替换为单空格。
2. **hunk 头计数必须与行数一致**。`@@ -a,b +_,c @@` 中 b = (上下文行+删除行) 数，c = (上下文行+新增行) 数。把上下文行改成 -/+ 对**不改变**计数；只有纯增/删行才改变。校验脚本：`python note/check_patch_counts.py [files...]`。
3. **`+` 侧起始行号用 `_`**（paperweight 约定，表示由引擎推算），`-` 侧必须写真实行号但引擎实际不校验它，只校验 b/c 计数。

## 2026-07-31 补充：批次 29 踩坑记录

### javap 不在 javapath 中

`C:\Program Files\Common Files\Oracle\Java\javapath` 只有 java/javac/javaw/jshell 四个转发器，**没有 javap**。真实 JDK 在 `C:\Program Files\Java\latest`（junction）→ `F:\Java\21\`，反编译用 `/f/Java/21/bin/javap.exe`（实证 Netty/JDK 字节码语义时必需，如 0096 的 ByteBufInputStream.readLine 循环内不抛 EOF 就是 javap 核对才发现的）。

### 消除 lambda 分配类候选必须先过微基准（0100 回退案例）

`map.forEach(capturing-lambda)` → `entrySet` 循环这类"消除 lambda 分配"改动，在 JMH 下实测可能**回退**（HashMap.forEach 直接扫内部表无迭代器分配；单次调用的捕获 lambda 基本被 EA 消除；entrySet 反而引入 iterator）。批次 29 的 0100 因此从内部历史 rebase 摘除（`git rebase --onto <提交>^ <提交>`，补丁序列自动重编号）。规则：涉及"改遍历方式"的候选，先写微基准验证再落地；"纯减少分配次数不改遍历结构"的候选（ThreadLocal 适配器、静态单例、门控）不受此限。

### 内部仓库 rebase 摘除提交是安全的（未推送前提下）

批次 29 用 `git rebase --onto 608af24^ 608af24` 摘除中间一个 feature 提交后，rebuildPatches 正常重生成补丁并自动重编号。前提：内部仓库历史从未推送（它本来就在 .gitignore 里）。注意摘除后所有引用旧补丁号的文档（optimizations.md、基准类注释、报告、release note）要同步重编号。

### 直接提交的源码改动不占补丁编号（批次 32 踩坑）

`paper-server/src/main/java`（CraftBukkit 侧，如 CraftEventFactory）是**直接提交进外层仓库的源码**，不走补丁机制——只有 `src/minecraft/java`（内部仓库）的改动才会被 rebuildPatches 编号。批次 32 先在文档里按"0112=BlockForm 门控（直接源码）、0113=双事件门控（补丁）"写号，结果补丁文件只生成了 0112（双事件门控）。**规则：先跑 rebuildPatches 看实际编号，再写文档编号；或直接源码改动的编号排在补丁之后。**

## 2026-07-30 补充：批次 23-27 踩坑记录

### rebuildPatches 会自动暂存全部生成补丁

`rebuildPatches` 对所有生成的补丁文件执行 `git add`。**第一次 `git commit` 会把已暂存的全部补丁一次吞掉**。要按组/按补丁细粒度提交，先 `git reset` 取消暂存，再逐组 `git add` 提交（optimizations.md 工作流要点中的"只 stage 本次目标的文件"同理）。

### jspecify @Nullable 不能标注嵌套类型的作用域结构（Java 21 编译错误）

`@Nullable private StreamTagVisitor.ValueResult acceptEntry(...)` 报 "无法使用 type-use 批注 @org.jspecify.annotations.Nullable 来批注确定作用域结构"。正确写法是移到嵌套类型内侧：`private StreamTagVisitor.@Nullable ValueResult acceptEntry(...)`。**注意 configuration cache 会掩盖此类编译错误**（命中缓存时跳过脚本重编译/增量编译，换任务才暴露），合并上游或改构建脚本后必须跑 `help` 或 `rebuildPatches` 做一次全量验证。

### 网络/IO 类优化的基准实践（字节级自检）

对"产出线上字节"的优化（如 0084 Utf8String、0085 long 数组批量写），基准类除 JMH 前后对比外，附带一个 `main` 方法对两种实现做**字节级输出比对**（多组输入：ascii/utf8/非 2 幂长度），`run.sh` 编译后即可直接 `java` 运行自检。netty-buffer/netty-common（4.2.7.Final，与服务器运行时同版本）已由 run.sh 自动下载。0095 进一步用 javap 反编译 netty jar 实证第三方类内部实现（`ByteBufOutputStream.writeUTF` 实为惰性 `utf8out` 字段 + `DataOutputStream` 包装），自检 main 对真实 jar 逐方法比对。

### gradlew 管道会掩盖构建失败（重要）

`./gradlew <task> 2>&1 | tail -N` 的**管道退出码是 tail 的**，gradlew 失败时整体仍返回 0——批次 28 曾因此误判 compileJava "通过"，实际 BUILD FAILED（`DataOutputStream.writeUTF(String,DataOutput)` 包私有，0095 初版直接调用编译错误），到 test 任务才暴露。后台任务的"exit code 0"同样来自管道末命令。**规则：gradlew 输出重定向到文件再 tail（`./gradlew <task> > /tmp/x.log 2>&1; echo "exit=$?"; tail /tmp/x.log`），或 `set -o pipefail`，并必须肉眼确认 "BUILD SUCCESSFUL" 字样；"exit=0" 与 "BUILD SUCCESSFUL" 缺一都不算通过。**

另：JDK 的 `DataOutputStream.writeUTF(String, DataOutput)` 静态方法是**包私有**——要复用 JDK 的 modified-UTF-8 编码器只能经 `DataOutputStream` 实例方法（可包装任意 OutputStream，FilterOutputStream.write(byte[],int,int) 在 JDK 9+ 直接委托底层流，无逐字节循环）。

### rebuildPatches 部分任务失败会静默跳过 feature 补丁再生（批次 28 踩坑）

一次 `rebuildPatches` 中 `rebuildResourcePatches` 因 `git add -A` exit 128（Windows 文件锁竞争，重跑即过）失败，Gradle 并行调度下独立的 `rebuildSourcePatches` 照常跑完（"Rebuilt 915 patches"），但 **feature 补丁（patches/features/）未重新生成**。随后 applyPatches 用旧补丁重建内部仓库，把只存在于内部提交里的新改动**彻底顶掉**（孤儿提交）。教训：

1. rebuildPatches 必须看到 **BUILD SUCCESSFUL** 才算数；部分任务成功不代表 feature 补丁已更新。
2. 改源码后的顺序必须是 rebuildPatches（成功）→ 才能 applyPatches；applyPatches 会以磁盘补丁为准重建内部仓库，未进补丁的内部提交会丢失。
3. 改动是否进了补丁，直接 `grep` 补丁文件里的特征字符串验证，不要只看任务输出。
4. 内部仓库 amend 前确认 HEAD 是哪个提交——applyPatches 重建仓库后 HEAD 顺序会变，误 amend 会把改动折进别的补丁（本批次因此 reset 重排了一次历史）。

### main 分支污染事故（26.x 合并）与修复

2026-07-30 合并 ver/1.21.11 到 main 时以 26.x 上游为基底解决冲突，导致 mcVersion/apiVersion 变 26.2、paperweight beta.21、Java 25 工具链及 patches/paper-api/paper-server 大量文件偏离 1.21.11（详见上方「注意事项」版本红线）。修复：内容恢复提交 `e7d7a0f99`（`git commit-tree <9e1ddc416^{tree}> -p HEAD` + `git reset --hard`），不改写已推送历史，可随时 revert。**教训：合并上游/分支后，先对照 1.21.11 红线检查 gradle.properties 与根 build.gradle.kts，再跑 rebuildPatches 验证。**

### 版本不匹配的补丁如何移植（26.x → 1.21.11）

上游 main 已到 26.x，很多补丁直接拿过来 hunk 对不上（变量改名、行数不同）。可靠流程：

1. 从内部仓库提取 1.21.11 vanilla 源码：`cd paper-server/src/minecraft/java && git show base:<path> > /tmp/a.java`；
2. 复制一份为 /tmp/b.java，手工把上游改动**按 1.21.11 的变量名/结构**应用进去；
3. `git diff --no-index --src-prefix= --dst-prefix= /tmp/a.java /tmp/b.java` 生成 diff，套进补丁文件（hunk 头按上面规则改）；
4. 完整 `applyPatches` 验证。

已知 26.x vs 1.21.11 差异点（移植时逐个核对）：`WaypointTransmitter` 的 broadcastRange 三行 vs 单行 `double min`；`Main.java` 的 `options` vs `optionSet`、`pidFilePath` vs `path`；`Blocks.java` 的 `state` vs `blockState`；`BarrelBlockEntity` 构造器签名不同；`ItemStackTemplate`/`ItemInstance`、`net.minecraft.util.filefix.FileFixerUpper` 在 1.21.11 **不存在**（对应上游提交直接跳过）。

### feature 补丁移植工作流（保留原作者）

从 Pufferfish 等外部仓库移植补丁时：

1. 在**已 applyPatches 的源码树**上直接改代码（变量名适配 1.21.11），编译通过；
2. 内部仓库提交，用 `git -c user.name=X -c user.email=Y commit --author="X <Y>"` 保留原作者，消息里注明 "Papo: ported from Pufferfish (GPL-3.0)" 及本地适配差异；
3. `./gradlew :paper-server:rebuildPatches`（单独一次调用）自动生成 `patches/features/00NN-*.patch`；
4. 完整 `applyPatches` + `compileJava` + `test` 验证后，外层仓库提交补丁文件。

### 移植评估结论备忘（Pufferfish ver/1.21, MC 1.21.10）

已移植：0003 窒息优化（去配置门控）、0008 闪电倒计时、0010+0013 区块查找/加载、0014 的 AttributeMap lambda 缓存。

跳过及原因：
- 0005 ShapelessRecipes 贪心匹配：成分有重叠的配方（如 `[planks标签, 橡木]`）会假阴性，数据包可构造，游戏性回归风险；
- 0007 万圣节检查：1.21.11 无此热点（`Bat.isHalloween` 已移除，`SpecialDates` 只在骷髅/僵尸 finalizeSpawn 调用）；
- 0011 攀爬缓存：上游 EAR 2.0 已有等价物（`getLastClimbablePos`）；
- 0015 goal selector 节流：EAR 2.0 已节流 1/3，叠加 1/20 会让远处生物几乎不动；
- 0006 弹射物限载：会破坏珍珠炮等依赖弹射物加载区块的装置，无配置门控不能开；
- 0004 异步刷怪：自述可能产生不一致，需移植 AsyncExecutor 等工具类，风险大于收益；
- 0009 DAB：侵入性强，EAR 2.0 已覆盖大部分收益，暂缓。

## 2026-08-01 补充：批次 47 踩坑记录

### scratch-list 微基准的小规模 JIT 伪影与 gc 探针证伪法（0190-0192 保留判例）

ScratchListBench 小规模（盒内 6-10 实体）复测稳定出现 after（scratch 复用）比 before（每次 new ArrayList）慢约 2× 的反转（3 fork 一致），但：gc 探针实测 before 80 B/op 真实分配、after 0.001 B/op 零分配；成本模型上 after 工作量是 before 的严格子集（仅多 O(n) clear），反向差物理不可能；同一机制在 17-20 实体规模翻转为 1.08×-2.26× 正收益。三证合一判为 JIT 伪影（JMH 1.37 + JDK 21.0.10 的 compiler blackhole 实验特性警告亦提示 VM bug 可能），机制保留。**教训：微基准出现"工作量严格子集却更慢"的反直觉结果时，先跑 `-prof gc` 看 alloc.rate.norm 确认机制是否生效，再换规模复测看符号是否翻转，最后再谈撤销；"实测回退即撤销"只适用于物理上可解释的回退（0100/0181 缓存比较成本 > TLAB 分配）。**

### EntityTypeTest 的包是 net.minecraft.world.level.entity（不是 world.entity）

`EntityTypeTest` 在 `net.minecraft.world.level.entity` 包。Mob/LivingEntity 等在 `net.minecraft.world.entity` 包，同包直觉会写错 import，编译报"找不到符号"。类基 fill 重载签名：`Level.getEntities(EntityTypeTest<Entity,T>, AABB, Predicate<? super T>, List<? super T>)`（Level.java:1703 公开），`getEntitiesOfClass(Class, AABB)` 就是它的分配包装（EntityGetter.java:73-75，NO_SPECTATORS 谓词）——scratch-list 化类基查询无需新增 Level API。

## 2026-08-01 补充：批次 46 踩坑记录

### survey 候选的"分配"断言必须对照 JDK 实现复核（0187 记分板撤除案例）

survey 子代理报告称 `Optional.ofNullable(score.display())` "每分数变更 2 次 Optional 分配"，据此实现并进了补丁。复核 JDK 源码证伪：`Optional.ofNullable(null)` 直接返回 `Optional.empty()` **单例**，null 分支（生产常态）两写法均零分配；非 null 分支两写法均分配一次。JMH 实测 1.791 vs 1.728（CI 重叠）证实中性——这不是 EA 伪影，是真零收益纯代码搅动，按"实测无收益即撤"精神从补丁撤除。**教训：survey 的分配断言只能按方法名望文生义，实现前必须打开 JDK/库源码确认真实分配路径（工厂方法常有单例/缓存快路）；"零监听器门控"类断言同理，需 grep 事件类确认自有 HandlerList 及子类层级。**

### python 编辑 .patch 后缺尾部换行 → corrupt patch（可预防）

用 python `split('\n')/'\n'.join(...)` 手工删 hunk 时，若被删段落延伸到文件末尾，会顺带丢掉最后一个空元素，产物文件**没有结尾换行**。`git apply` 报 "corrupt patch at line N"（N = hunk 最后一行），`git am` 报 "corrupt patch at .git/rebase-apply/patch"。教训：手工编辑补丁后统一 `rstrip` + 追加单个 `\n`，并立即 `git apply --check` 验证，再跑完整 applyPatches。

### rebuildPatches 按内部提交 subject 再生命名（可预防，复发）

内部仓库提交 subject 用中文（如"0168-xxxx"）时，rebuildPatches 会以 subject 重新生成补丁文件名，产生垃圾名（"0168-0168.patch"）。批次 39 起就踩过，本批又复发一次。教训：内部提交 subject 保持英文 kebab 命名（它会直接成为补丁文件名）；若已污染，脚本批量 `git mv -f` 恢复原名后再提交。

### Blackhole 不能在 main() 中实例化（基准类自检结构）

JMH 的 `Blackhole` 构造会检查 JMH 运行时上下文，在普通 `main()` 自检里 `new Blackhole(...)` 直接抛 IllegalStateException。基准类的等价性自检要把逻辑拆成**普通 body 方法**（用 `Object sink` 字段做逃逸汇对齐 `bh.consume` 语义），`@Benchmark` 方法只做薄包装，main() 调 body 方法自检。

## 2026-08-01 补充：稳定性审计踩坑记录

### Paperweight 编译期访问宽化：package-private 源码可跨包调用（非 bug）

稳定性审计时子代理报告 0134 的 `ByteBufCodecs.papoNbtSerializationContext(RegistryAccess)` 声明为 `static`（package-private，包 `net.minecraft.network.codec`）却被包 `net.minecraft.network.chat` 的 `ComponentSerialization` 跨包调用，判定为"javac 必报编译错误（HIGH）"。**复核证伪**：实跑 `./gradlew :paper-server:compileJava --rerun-tasks --no-configuration-cache`（强制重编译、绕开 configuration cache）**BUILD SUCCESSFUL**，`javap` 编译产物确认该方法字节码为 `public static`——尽管源码是 `static` 且 `paper.at` 无对应宽化条目（`grep network/codec build-data/paper.at` 为 0）。结论：**Paperweight 在编译期对 dev 源码做了访问宽化（package-private → public），故跨包调用 package-private 方法在本项目可编译且产出 public 字节码，非 bug。** 教训：审计"访问修饰符"类发现时，不能只读源码望文生义判定编译失败，必须用 `--rerun-tasks --no-configuration-cache` 实跑编译 + `javap` 核对字节码访问标志定论（configuration cache 会掩盖真实编译结果）。

### 验证编译必须绕开 configuration cache（复发）

与上方"configuration cache 会掩盖构建脚本损坏"同源：`touch` 源文件只改 mtime，Gradle 9 用内容哈希判定仍 UP-TO-DATE 跳过。要强制重编译验证，必须 `--rerun-tasks --no-configuration-cache`（见上条实证）。`compileJava` 增量缓存命中显示 "UP-TO-DATE / BUILD SUCCESSFUL" **不代表**当前源码真编译过。

## 2026-08-02 补充：批次 50 踩坑记录

### rebuildPatches 垃圾重命名复发（0163–0180 中文 Subject 头）+ 确定性恢复法

本批新增 0203/0204 时跑 `:paper-server:rebuildPatches`，BUILD SUCCESSFUL 且新补丁正确生成，但**同时把 0163–0192 一批已有补丁按内部仓库的中文 subject 重新命名成垃圾名**（如 `0168-Papo-piston-scan-aabb.patch` → `0168-0168.patch`、`0163-...→0163-scratch-pos-0163.patch`）。这是批次 39 起就踩过、build.md 已记录的坑的**又一次复发**。

**根因（实证）**：0163–0180 的补丁**文件名**虽被当年手工 `git mv` 成干净英文，但其 patch 文件的 **`Subject:` 头仍是中文**（RFC 2047 编码 `=?UTF-8?q?...?=`，如"潮涌核心框架"）。`rebuildPatches` 从内部仓库提交 subject 重新生成文件名，而内部 subject 经 applyPatches 取自这些中文 Subject 头 → slug 退化成垃圾。0181+ 的 Subject 头是干净英文 `Papo: ...` 故不受影响。

**确定性恢复法（已验证）**：发现垃圾重命名后，**核平 patches/features 再从 HEAD 恢复，最后只补回本次新补丁**：
```bash
cp paper-server/patches/features/0203-*.patch /tmp/papo_0203.patch   # 先备份新补丁
cp paper-server/patches/features/0204-*.patch /tmp/papo_0204.patch
git rm -r --cached --quiet paper-server/patches/features/
rm -rf paper-server/patches/features
git checkout HEAD -- paper-server/patches/features/                  # 恢复干净命名的存量补丁
cp /tmp/papo_0203.patch paper-server/patches/features/0203-...patch  # 补回新补丁
cp /tmp/papo_0204.patch paper-server/patches/features/0204-...patch
git add paper-server/patches/features/0203-*.patch paper-server/patches/features/0204-*.patch
```
恢复后 `git status` 仅显示本次新增补丁、0163–0192 全为干净命名。0107 等"被 rebuildPatches 用更多上下文行重新生成"的修改也一并回退到 HEAD（语义等价，HEAD 是已发布验证态）。

**多轮持续优化的对策（避免每轮都做恢复舞）**：
1. **根除**：一次性把 0163–0180 的中文 Subject 头改成与文件名一致的干净英文（`Subject: [PATCH] Papo: <desc>`），applyPatches 后内部 subject 即干净，往后全量 rebuildPatches 不再复发。本批暂未做，留作独立清理项。
2. **规避**：未来新增补丁**不跑全量 rebuildPatches**，改用定向导出——内部仓库提交后 `git -C paper-server/src/minecraft/java format-patch -1 HEAD --stdout > paper-server/patches/features/02NN-<slug>.patch`（只产出新补丁，不重生成存量文件名）。注意 format-patch 的 From 行用真实 commit hash，paperweight 用 `0000…0000`，两者 `git am` 均可应用，但若要完全一致可手工归一化 From 行。
3. **增量编译无需 applyPatches**：改 `src/minecraft/java` 源码 + 内部仓库提交后，`compileJava`/`test` 直接在源码树上跑即可（源码树即编译输入）；applyPatches 仅在源码树被重置（如被某任务清空）时才需重跑以重建。

### rebuildPatches 过程中源码树瞬时回退 vanilla（非损坏）

本批 rebuildPatches 运行期间，系统曾报告 `PlayerChunkSender.java` 显示为 vanilla（无任何 Papo 改动，连 0053/0141 都没有）。**这是 rebuildPatches 内部 applySourcePatches 的瞬态**：任务完成后内部仓库工作树 == HEAD（含全部补丁+本次新提交），源码恢复正常（grep `papoSelKey`/`papoAlreadyTracked` 命中、`git diff HEAD` 无差异、compileJava SUCCESSFUL）。教训：rebuildPatches 期间看到的源码状态不可信，以**任务完成后的内部仓库 HEAD + compileJava 结果**为准。

## 2026-08-20 补充：批次 58 踩坑记录

### 0163-0180 垃圾重命名第 4 次复发 → 已根除（重要）

批次 58 的 rebuildPatches 再次触发中文 Subject 头垃圾重命名（本次波及 0055-0180 共 127 个）。按恢复法恢复后，**本轮执行了留置两批的根除项**：

1. [note/fix_patch_subjects.py](fix_patch_subjects.py)：把 127 个补丁的 RFC2047 编码 Subject 头（含多行续行）改写为"文件名体（'-'→空格）"——slug 往返闭合（subject "deflate 6" ↔ 0066-deflate-6.patch）。字节级只动头部 Subject 区，正文/行尾不动。
2. 完整 `applyPatches`：内部仓库重建，干净 subject 流入内部提交（`git log --format=%s | grep -cP 中文` = 0）。
3. `rebuildPatches` 验证：**216 个补丁零垃圾名**；14 个历史尾杠文件名（旧 subject 截断产物）一次性归一为终态（R066-R100 相似度重命名，语义等价）。

**此后全量 rebuildPatches 不再需要恢复法。** 新增补丁的内部提交 subject 保持英文（照旧），其补丁文件 Subject 头自动干净。

### 恢复法补充：Windows 文件锁连 rm -rf 都会挂

本次恢复法执行时 `rm -rf paper-server/patches/features` 报 "Device or resource busy"（目录句柄被残留进程占用，重试 3 次 + sleep 20s 无效）。**变通**：rm 已把目录内容删空、仅目录本身删不掉——`git checkout HEAD -- paper-server/patches/features/` 可直接向占用目录写入恢复内容，跳过删目录步骤。

### DEFLATE 上界判例：理论推导必须过实测校验

压缩输出缓冲上界首版按"5B/65535B 存储块"理论最坏推导 `n + n/4096 + 16`，被基准自检在 256KiB 随机数据上证伪（实测膨胀 +86 > 界 +80）。实测规律：**JDK zlib（level 6，随机输入）膨胀 = 5B/16384B 窗口 + 6B**（lit_bufsize=16384 决定块节奏；64KiB/256KiB/1MiB/4MiB 四点分别 +26/+86/+326/+1286 全吻合），修正为 `n + n/2048 + 32`（~1.6× 裕量）。教训：**上界类公式推导完成后，必须构造"最坏输入"实测打满再落地**——纸面最坏（65535 存储块）与引擎实际行为（16384 窗口）可以差 4 倍。

### Paper 的 misc.compression-level 在 JDK 回退平台是枚配置炸弹

`misc.compression-level` 配 10-12 在无 native 平台（Windows）`new Deflater(10)` 抛 IAE → **该连接登录即断连**（libdeflate 接受 1-12，JDK 只接受 -1..9，同一配置两平台行为分裂）。0216 已修（IAE 回退 clamp [1,9]）。排障时注意：症状是玩家连不上（配置阶段断连），日志有 IllegalArgumentException 栈。

### benchmark javac 注解处理需显式 -proc:full（JDK 21+）

批次 59 踩坑：`benchmark/run.sh` 的 javac 在 JDK 21+ 下**不运行 classpath 上的 JMH 注解处理器**（JDK-8306819：需显式 `-proc:full`/`-processor`），症状是 BenchmarkList 0 字节、无 `*_jmhTest` 桩类、JMH 报 "No matching benchmarks"。已给 run.sh 的 javac 加 `-proc:full`。另：netty 4.2 的 `MessageToByteEncoder` 在 **netty-codec-base**（非 netty-codec）；EmbeddedChannel 在 netty-transport——run.sh 依赖清单已补两 jar。**EmbeddedChannel 构造参数序 = head→tail，出站遍历 tail→head，装配序须与真实管线 list 序一致**（[prepender, compress, encoder]），装反时 compress/prepender 对非 ByteBuf 消息直通、出站字节=裸载荷，自检立刻抓包失败。

## 2026-08-20 补充：批次 60 判例

- **谓词类 JMH 基准的输入必须经 @State 非终态字段**：SendFastPathBench 首版载荷/旗标用 static final 常量，被 JIT 整链常量折叠（两版同测 0.48ns 伪平）；改经 @State 字段后真实差异 1.73× 显现。
- **跨线程队列选型必须实测**：同一批量排水逻辑，ConcurrentLinkedQueue 版实测回退 4.8×（跨线程 CAS+每元素 Node 分配），netty shaded MpscChunkedArrayQueue 版 1.49× 劣化——MPSC 也救不回理论收益不存在的候选。
- **"消除每任务开销"类候选先核实运行时内建摊销**：netty NioEventLoop.wakeup 带 CAS 守卫（每 park 窗口至多一次唤醒）+ 64 任务/批处理——"每包 execute = 每包一次唤醒 syscall" 的直觉在现代 netty 上不成立。

## 2026-08-02 补充：批次 51 踩坑记录

### Paper 配置 @Comment 是单 String，不是 String[]

`org.spongepowered.configurate.objectmapping.meta.Comment` 在本仓库是**单 String**（`@Comment("…" + "…")` 拼接多行），**不是 `String[]`**。用数组语法 `@Comment({"a","b"})` 编译报"批注值不是允许的类型"。现有代码（如 GlobalConfiguration 的 chunkLoading* 段）一律用 `+` 拼接单 String。规则：`@Comment` 多行写 `"line1 " + "line2"`，**不要** `@Comment({...})`。

### final 变量在 try + catch 都赋值 → 定值分析错误

`final T x; try { x = …; } catch { x = null; }` 报"可能已分配变量"——Java 对 final 变量的定值分析保守地认为 try 块赋值后异常仍可能进入 catch 二次赋值。本批 `GlobalConfiguration.get()` 被误包进 try/catch 防御，触发此错。**规则**：若赋值表达式本身不会抛（如 `GlobalConfiguration.get()` 是静态字段读取），**直接赋值 + null 检查**，不要 try/catch；确需捕获异常时用非 final 变量或两段式（先在 try 内取值到局部，再在 try 外赋给 final）。

### Windows 下 `python3` 是 Store 占位符，用 `python`

`/c/Users/.../WindowsApps/python3` 是 Microsoft Store 占位（不实际运行 Python，静默无输出）。真实 Python 在 `/f/Python-Launcher/python`（3.12）。脚本一律调 `python`，不要 `python3`。另：Git Bash 的 `/tmp/...` 与 Windows Python 的 `/tmp/...`（当前盘根）**不是同一目录**——跨 bash/python 传文件用 repo 相对路径或 `F:/...` 绝对 Windows 路径（正斜杠 Windows Python 也认）。

### rebuildResourcePatches 仍间歇 git add -A exit 128（重跑即过）

批次 28 已记的 Windows 文件锁竞争在本批复发一次（rebuildPatches 整体 FAILED，但 feature 补丁可能已由 rebuildSourcePatches 生成）。重跑 rebuildPatches 即过。注意区分：`rebuildResourcePatches` 失败 ≠ feature 补丁未生成——要 `ls patches/features` 确认 02NN 是否在场、是否为 rebuildPatches 正确格式（全 hash index 行、无 `--` 签名尾）。

### `git format-patch` 定向导出 ≠ paperweight 补丁约定（未采用）

本批尝试用 `git format-patch -1 HEAD` 定向导出新补丁以避开全量 rebuildPatches 的垃圾重命名复发。`git apply --check` 通过，但与 rebuildPatches 产物有格式差异：index 行用缩写 hash（vs 全 40 字符）、尾部带 `-- \n2.55.0` 签名、**hunk 头 `+` 侧用真实行号（vs paperweight 的 `+_` 约定）**。paperweight 的 applier（codechicken diffpatch，对 hunk 头/计数严格）是否接受未验证，故本批仍用 rebuildPatches + 恢复法（保证正确格式）。format-patch 工作流待后续用 applyPatches 实证后再启用。结论：**目前新增补丁仍走 rebuildPatches + 恢复法**（见批次 50 踩坑），format-patch 留作待验证优化。

## 注意事项

- **游戏版本红线：始终保持 Minecraft 1.21.11**。同步上游/合并分支时，`gradle.properties`（mcVersion/apiVersion=1.21.11）、根 `build.gradle.kts`（paperweight **2.0.0-beta.19**、Java 工具链 **21**、snapshots 仓库）绝不能取上游 26.x 侧的值。2026-07-30 的事故：合并 ver/1.21.11 时以 26.x 为基底解决冲突，paperweight 变 beta.21 导致 `spigot {}` 等 DSL 无法解析、构建脚本编译失败（报错为 "Unresolved reference 'spigot'/'createMojmapPaperclipJar'"），mcVersion 变 26.2。修复方式：内容恢复提交（`git commit-tree <1.21.11树> -p HEAD` + reset），不改写历史。教训：**configuration cache 会掩盖构建脚本损坏**——compileJava 命中缓存成功，但 rebuildPatches/help 触发脚本重编译才暴露问题；合并后必须跑一次 rebuildPatches 或 help 验证。
- `paper-server/src/minecraft/` 在 `.gitignore` 中 —— 补丁生成的源码树**不进入版本库**，版本库只跟踪 `patches/` 目录。提交改动 = 提交补丁文件（`rebuildPatches` 的产物）。
- `paper-api/src/` 是直接提交的源码（无补丁机制），改 API 直接改源码并提交。
- 编译警告（`dep-ann`、`deprecation` 等）属上游正常现象，不影响构建。
- 首次构建会向 `~/.gradle/caches/paperweight` 写入数 GB 的 mache/反编译缓存，注意磁盘空间。
- 同步上游修复时：`git fetch upstream`，从 `upstream/main` cherry-pick 适用于 1.21.11 的提交；涉及补丁文件的冲突需人工解（补丁是文本 diff，冲突后通常要 `applyPatches` → 手工修复源码 → `rebuildPatches`）。

## 2026-08-22 补充：发布产物完整构建验证（批次 70-76 后补）

0.44.0-0.50.0 各 release note 声称的产物此前未逐版实构建（产物名由 `:paper-server:createPapoJar`
从 createMojmapBundlerJar 产物复制重命名为 `Papo-<mcVersion>-<papoVersion>.jar`）。已补跑完整
打包：`Papo-1.21.11-0.50.0.jar`（97.4MB）BUILD SUCCESSFUL，含批次 70-76 全部 7 个补丁
（0241-0247，PapoSharedWireMemo 等新类入包）。后续每个 release 提交前应跑一次 createPapoJar
验证产物可构建。

## 2026-08-26 补充：批次 78 踩坑记录

### GRADLE_USER_HOME 已迁移

本机 gradle 缓存（wrapper dists、模块缓存、daemon）已整体迁移到 **`F:\TEMP\.gradle`**（环境变量未持久化，调用时显式 `export GRADLE_USER_HOME=F:/TEMP/.gradle`）。旧的 `~/.gradle` 只剩 wrapper dists（gradle-9.4.1-bin），依赖缓存以 F: 为准。PATH 中的 java 已是 **25.0.4**（javapath 转发器），但编译工具链仍钉 21：真实 JDK 21.0.10 在 `C:\Program Files\Java\latest\jdk-21`（`latest` junction 同时挂 jdk-21/jdk-25）。

### javac 增量编译的 class 文件签名解析陷阱（PaperConfigurations 泛型）

**症状**：改 `SpigotConfig.java`（或任何触发 `PaperConfigurations.java` 单独重编译的改动）后 `:paper-server:compileJava` 报 2 个泛型错误——`List<Definition<? extends Annotation,?,? extends Factory<?,?>>>` 无法转换为 `List<Definition<?,?,? extends Factory<?,?>>>`（PaperConfigurations.java:204/240）。全量编译（`--rerun-tasks`）**同一份源码 BUILD SUCCESSFUL**。

**根因**（二分实验逐变量定位）：上游遗留的 `defaultFieldProcessors()` 返回类型首参带 `? extends Annotation` 窄边界，与被调方 `InnerClassFieldDiscoverer.globalConfig/worldConfig` 的宽 `?` 参数构成仅靠通配包含（JLS 4.5.1 `?` contains `? extends X`）才成立的赋值。**javac 对"从源码解析的签名"接受该转换、对"从 class 文件 Signature 属性解析的签名"拒绝**（同调用方源码、同被调方源码文本，Mini 替身源码编译通过 / 二进制解析失败，反复对拍实证）。增量编译恰好把被调方留在 classpath 二进制里 → 陷阱只在"调用方重编译而被调方没有"时触发。8 月批次都能过纯属全量/同批编译运气。

**修复**（批次 78 同批直提交）：`defaultFieldProcessors()` 返回类型放宽为与参数一致的 `List<Definition<?, ?, ? extends FieldProcessor.Factory<?, ?>>>`（私有静态方法，无 API/行为变化），两类型完全相同后陷阱永久解除。

**通用教训**：遇到"增量编译报泛型错误但 `--rerun-tasks` 全量通过"，先怀疑这个源码/二进制签名解析不对称，而不是怀疑自己的改动；修复方向是让调用方声明类型与被调方参数类型**逐字相同**。

## 2026-08-26 补充：批次 82 踩坑记录

### Git Bash 下长输出经 `| head -N` 管道会假挂（Windows 管道语义）

后台跑基准 `java ... | head -40`：head 读满退出后，java 进程继续写管道在 Windows 下**阻塞不退出**（Linux 是 SIGPIPE 即死），表现为进程"挂死"无输出。**规则：长输出一律重定向文件（`> /tmp/x.log 2>&1`），事后 cat/tail，不接 head 管道。**

### 池任务里共享 ByteBuffer 的并发 rewind/read 会 BufferOverflowException

多个池任务共享一个 `ByteBuffer` 做 `ch.read(buf.rewind(), pos)`：并发 rewind/read 竞态触发 `java.nio.BufferOverflowException`（RuntimeException），任务在计数递减前死亡 → 排水循环永卡。IoPoolScalingBench 原版就是每任务 `ByteBuffer.allocateDirect`——照抄时别"优化"成共享。规则：**NIO buffer 一任务一份；任务内清理逻辑放 finally**。

### concurrentutil 判例：OrderedStreamGroup 内 BLOCKING 优先级可插队 + 异常不毒化流

最小复现实证（OrderGroupRepro）：同一 OrderedStreamGroup 内，BLOCKING 优先级任务可越过已排队的 NORMAL 任务先执行（这正是批次 82 读预取的流畅度保证）；单任务抛异常后后续任务照常执行（流不毒化）。

## 2026-08-26 补充：批次 83 基建（真实服务器 join 冒烟工具）

[benchmark/src/papo/bot/OfflineJoinBot.java](../benchmark/src/papo/bot/OfflineJoinBot.java)：最小离线模式协议机器人（1.21.11 / protocol 774，纯 JDK socket）。**协议包 ID 全部从服务器源码注册序提取**（`LoginProtocols`/`ConfigurationProtocols` 的 `addPacket` 顺序 = packetId，`ProtocolInfoBuilder.listPackets` 实证 ID=列表下标）；不要凭记忆写包 ID——升级 MC 版本后须重新提取（含 version.json 的 protocol_version）。SmokeJoinVerify 跑四态矩阵（空数据/即时重连/稳态/关服）+ 产物校验 + 日志零异常门。服务器子进程日志必须重定向文件（Windows 管道 head 假挂判例）。

## 2026-08-26 补充：批次 84 踩坑记录

### 服务器子进程日志编码（中文 Windows）

JDK18+ 默认 charset=**UTF-8**（JEP 400），但中文 Windows 上 JVM 内某些 native IOException 消息仍按 GBK 产出——Java 端按 UTF-8 读子进程输出得到乱码 `??????`，无法匹配"远程主机强迫关闭"等良性过滤。**规则：起服务器子进程加 `-Dfile.encoding=UTF-8`，读取端按 UTF-8**（SmokeJoinVerify/BurstJoinVerify 已内置）。

### 后台任务链路里 grep "BUILD" 对 FAILED 也返回真

`./gradlew X > log; grep "BUILD" log && next`——grep 匹配到 "BUILD FAILED" 同样成功并继续执行后续步骤（曾把失败产物 amend 进内部提交）。**规则：判定必须 `grep -c "BUILD SUCCESSFUL"` 或存 `$?` 判退出码。**

### bot 突断噪声三形态（真实服冒烟门）

bot 直接 close socket 与服务端出站写入竞争，日志出现三种同源良性 ERROR：`StacklessClosedChannelException` / `IOException: Connection reset by peer` / 中文 reset 消息。两版本同现，冒烟门应过滤并单独计数披露，而非放宽到"允许 ERROR"。

### 端口孤儿：失败路径必须收走服务器进程

runner 异常抛出时若不 stop/destroy 服务器子进程，端口被占导致下一轮 boot 直接失败（"no Done"）。BurstJoinVerify 已用 try/catch destroyForcibly 兜底。

## 2026-08-26 补充：批次 85 踩坑记录

### 本会话 bash heredoc 持续吃反斜杠（复发×3）

`python - <<'EOF'` 内的 `\n`/`\u25f4` 转义到 python 源码时被降级（`\n` 变真换行、`\u` 变实际字符），导致 Java 源出现跨行字符串字面量等损坏。**规则：含反斜杠转义的补丁一律用 Write 工具写 .py 临时文件再执行，不用 heredoc 内联。**

### 管道控制台不输出 ◴ 数字行（/mspt 捕获限制）

zh-CN Windows + 管道 stdout 下，`/mspt` 的表头行可见、⛁ 数字行缺失（MsptProbe 92 行日志实证）。jline 非 tty 编码/写入路径限制。后续需 MSPT 数据时改用 Linux/tty 或服务器侧改造。
