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

本机 gradle 缓存（wrapper dists、模块缓存、daemon）已整体迁移到 **`F:\TEMP\.gradle`**（环境变量未持久化，调用时显式 `export GRADLE_USER_HOME=F:/TEMP/.gradle`）。**2026-10-12 清理轮核实：旧的 `~/.gradle`（C 盘）已彻底不存在**——显式 export 是硬前提，漏掉时 wrapper 会尝试向 C 盘重新下载发行版，而 services.gradle.org 在本机不可达，直接卡死。PATH 中的 java 已是 **25.0.4**（javapath 转发器），但编译工具链仍钉 21：真实 JDK 21.0.10 在 `C:\Program Files\Java\latest\jdk-21`（`latest` junction 同时挂 jdk-21/jdk-25）。

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

## 2026-08-26 补充：批次 86 踩坑记录

### 本机 locale socket 错误文案乱码：sun.jnu.encoding 层，`-Dfile.encoding=UTF-8` 管不到

Windows 中文 locale 下 socket 层 IOException 文案（"远程主机强迫关闭..."）由 OS 按 GBK 产出、Java 经 native 层解码——给 JVM 加 `-Dfile.encoding=UTF-8` **不影响**该路径，UTF-8 读子进程输出仍得 5+ 连问号乱码。冒烟门的对策：`java.io.IOException:` + 连问号串按良性突断竞争过滤（BurstJoinVerify.isBenignCloseRace）。

### python -c 内联多行字符串拼接需显式括号（复发）

`var = ('a' + chr(10) +\n 'b')` 跨行拼接必须开括号；漏括号报 SyntaxError 指向行尾 `+`。连同 heredoc 吃反斜杠判例：**复杂补丁一律 Write 工具写 .py 文件执行**。

## 2026-08-27 补充：批次 87 踩坑记录

### whenComplete 可能在 future 赋值表达式内同步触发（重入双 finish 判例）

`this.chunkLoadFuture = trackLoadWithRadius(...)` 返回**已完成的 future** 时，紧随其后的 `whenComplete` 在赋值现场同步执行；若回调链推进状态机并 finishCurrentTask，而外层常规 tick() 尚在飞行中——内外双 finish → "Unexpected request for task finish" 断连（burst 才暴露，稳态不复现）。**规则：状态机完成转移必须单发（AtomicBoolean CAS 先到者胜），事件驱动与常规驱动共享同一 CAS。**

## 2026-08-27 补充：批次 90 踩坑记录

### Boolean.getBoolean 只认 "true" 不认 "1"（属性开关静默失效）

`-Dpapo.tickProfile=1` 下 `Boolean.getBoolean("papo.tickProfile")` 返回 **false**（内部是 `Boolean.valueOf(getProperty)`）——探针静默不启用，空载/带载双最小探针才定位。**规则：系统属性开关用 `getProperty` 后自判 `"true".equalsIgnoreCase(v) || "1".equals(v)`。**

### 多行单次 println 的续行被日志系统吞掉

服务器内 `System.out.println(多行字符串)` 经 log4j 包装后，只有首行落日志文件（续行丢失）。**规则：诊断输出逐行 println。** 另：子进程 stdout 管道在部分运行丢 post-boot 行（批次85 ◴ 同源）——**可靠解析一律读服务器自身 logs/latest.log**。

### paperclip/bundler jar 的类在嵌套 jar 里

`createPapoJar` 产物外层 listing 查不到服务器类——实际在 `META-INF/versions/<mc>/paper-<mc>.jar` 嵌套 jar。核对"改动是否进包"必须解嵌套 jar（本批曾因此误判探针未进包）。

### 运营类配置旋钮的文档去向（批次 92）

`disable-world-ticking-when-empty` 等运营 opt-in 配置的语义/建议不入 build.md（构建判例
专用），统一放 [note/ops-empty-world-ticking.md](ops-empty-world-ticking.md)——含源码级
"门内/门外"逐段核对（批次 90 的"22-23% 已有旋钮可消"口径在那里被修正为"旋钮只覆盖
entities/blockEntities 段"）。改 ServerLevel tick 结构时记得同步该文档的行号锚点。

## 2026-10-12 补充：深度清理轮——缓存盘点与清理判例（无代码变更）

**本机缓存位置权威地图**（盘点时点证据）：

- `F:\TEMP\.gradle` = 唯一 GRADLE_USER_HOME（C 盘 `~/.gradle` 已不存在，见上方批次 78 节修正）。内部归属：
  - **Papo 活跃**：`caches/9.4.1`（wrapper 钉的版本）、`caches/modules-2`（依赖缓存）、`caches/paperweight-userdev/v2/work`（881M——2026-10-03 全量重建波 + 10-05 applyDevBundlePatches 补丁波，无更早代际残留=整体现行键位；内含 vanilla server jar 下载副本，受"网络大文件单连接截断"判例保护）、`wrapper/dists/gradle-9.4.1-bin`（**命脉，网络不可达不可再取**）。
  - **非 Papo（sibling 项目所有，清理必须排除）**：`caches/fabric-loom` 1.3G（meteor-client 等 Fabric 项目，最后使用 2026-08-30，含 26.2 实验残量 155M）、`caches/9.4.0` + dist（Custom-Crops/PapersDelight）、`caches/9.8.0` + dist（无 wrapper 归属、10-07 仍被触碰=疑似系统/IDE 直调，默认保留）。
- `F:\TEMP\.cache/huggingface`（3.3G）= ML 项目模型缓存，与 Papo 无关，**清理 F:\TEMP 根时必须排除**。
- `F:\TEMP` 根 = 历次会话散落产物（构建日志 ap*/b*.log、jar 嵌套分析 jarcheck/pj/papo-jar-v、批 129 验证 b129v、census 提取物 r2x-*-events.txt、fastutil sources、PowerShell 探针脚本等）。
- 仓库内：`benchmark/results/`（.gitignore 内）= 每次浸泡/压测腿的完整服务器世界+转储+JFR，**每腿 memsoak ≈1G、无自动轮转**；`paper-server/build/libs` = 历版 release jar（各 97M）。

**本轮清理**（共释放 ≈23G，F 盘可用 62G→84G；git status 全程保持干净）：

1. `benchmark/results` 22G 全清（内存战役 R43 已终局声明、R44 已终验，数字以 note/report/perf/ 已提交报告为准，原始世界/转储不再复用）。
2. 旧版 release jar 删除（0.92.0/0.103.0-0.106.0 共 5×97M），保留当前 0.107.0；需要旧版时 `git checkout <该版提交> && createPapoJar` 可逐位重建。
3. 仓库 `.gradle/`（configuration cache）+ 根 `build/` 清除——本文件多处判例早已记录 config cache 掩盖编译/脚本损坏，清掉是纯收益。
4. F:\TEMP 根会话残留 ≈537M 清空（保留 `.gradle` 与 `.cache`）。
5. 三个 wrapper dist 目录（9.3.0/8.14.2/8.14.4）只剩 0 字节 `.zip.part`/`.zip.lck` 下载失败残骸，删除。

**清理判例**：

1. **共享 GRADLE_USER_HOME 里的东西不都是 Papo 的**——清理前必须归因：wrapper 版本反查 `/f/Github/repo/*` 的 gradle-wrapper.properties、目录内文件 mtime 波次；无法归因且近期被触碰的版本缓存默认保留。
2. **paperweight work 目录代际判定用"目录内最新文件 mtime"，不用目录自身 mtime**（同 hash 重跑只改写内部文件、不改目录 mtime）；盘点无更早波次=整体现行，不要凭"同任务多个 hash 目录"猜多代。
3. 0 字节 `.zip.part`/`.zip.lck` = 被墙网络下 wrapper 下载失败残骸，可径删。
4. benchmark/results 按腿累积无轮转——每轮大战役收官后应清一次（本轮 22G 即 R4/内存/分配源三役累积）。
5. **清理后验证门**：①内部仓库 `git status --porcelain` 必须为空（全量重编译可能触发 applyPatches 重建内部仓库，R20 判例）；②`gradlew --no-daemon help`（配置层从零重建）+ `:paper-server:compileJava --rerun-tasks --no-configuration-cache`（全量重编译）双 BUILD SUCCESSFUL；③删除只允许落在 .gitignore 覆盖路径内（git status 保持干净是硬门）。

## 2026-10-12 补充：批次 186 踩坑记录

### benchmark 共享 build/classes 被 PATH javac 异版污染 → 空服腿静默通过（MSPT 守护判废重跑判例）

- **事故链**：PATH `java/javac` = JDK 25（类文件版本 69），而腿脚本与 harness 均钉死 `/f/Java/21/bin/java`（只认 ≤65）。本轮普查工具编译走了 PATH `javac`，把 `OfflineJoinBot.class` 等重编为 v69 写进共享 `benchmark/build/classes`；随后 MSPT 腿的 J21 harness 在 `new OfflineJoinBot` 处全体 `UnsupportedClassVersionError`——bot 零在线，server 空转，但采样按时间表照常进行、腿 exit=0，**四条腿全部是空服噪声**。`grep -c "MlpB"`=0 是揭发证据（bot 从未 join）。
- **server 侧不受污染**：两个 harness 起 server 均走 `ProcessBuilder("F:/Java/21/bin/java.exe", …)` 常量——普查腿的 server 测量始终在 J21 上，harness JVM 版本只影响 bot 客户端进程内加载，普查配对腿有效性不受本次事故影响。
- **修复与门**：①bot 包用 `/f/Java/21/bin/javac` 全量重编（ShortListVerify 除外——需 Moonrise 类，非腿依赖面）；②两脚本编译/运行行全部钉死 J21 bin；③`msptload-r45legs.sh` 每腿加验收门：`TRACE[` 行数 ≥ bot 数（OfflineJoinBot 行走期打印，bot 在线正证据）且类版本/NoClassDef 错误 =0，否则判废。
- **判例**：①**腿有效性验收必须有 bot 在线正证据**——exit=0 + 采样齐全 ≠ 腿有效（空服腿与真腿在低负载下数值上可分辨性差，主序空服对比"3 优 3 平"毫无意义）；②**共享输出目录的编译器版本必须与运行 JVM 同钉**（多工具共用 build/classes 时，任一工具换 javac 即全局污染）；③杀后台任务后必须清进程树（`taskkill /T` 或核对端口）——被停 bash 的脚本子进程会继续推进腿序，孤儿 server 占端口令后续腿静默撞车。

## 2026-10-12 补充：批次 187 踩坑记录

### mock NMS 类的单测必须自带 @BeforeAll bootstrap（隐式套件顺序依赖根除判例）

- **事故链**：`PapoHopperEmptyQueryFastPathTest` 用 `Mockito.mock(Entity.class)`。Mockito
  inline mock 创建时触发 `Entity.<clinit>` → `EntityDataSerializers` → `ParticleTypes` →
  `BuiltInRegistries.<clinit>` → `Bootstrap.checkBootstrapCalled` 抛 "Not bootstrapped"。
  全套件运行时**碰巧**排在某个已 bootstrap 的测试类之后而全部通过；`--tests` 单类过滤
  跑立即 3/3 全炸（MockitoException: Cannot instrument class Entity）。该类此前从未在
  独立 JVM 里跑过——绿灯是执行顺序借来的。
- **修复**：`@BeforeAll { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }`
  （套件既有模式，tryDetectVersion 必须先行否则污染整个套件 JVM）。
- **判例**：**凡 mock/触碰 NMS 类静态的测试必须自带 bootstrap，不得依赖套件内其他类
  先行初始化**——顺序依赖绿灯是潜伏红灯；单类过滤跑一遍是暴露它的最廉价手段。

### 测试查询盒 z 轴算术滑误（R33/R42 同族第三例）与诊断方法论

- **事故链**：oracle 差分测试把 y 值误写进 z 槽（`new AABB(7.9, 74.9, 74.9, 8.6, 75.6, 75.6)`
  的 z 跨 [74.9,75.6] 而实体在 z=8.0..8.5）——oracle 正确返回空、谓词（只看 section
  区间）正确翻 false，两断言"矛盾"。逐字节核对源码无果后，一条**联合诊断断言**把
  `intersects=false` 与两个盒的 toString 倾倒进 failure message，一步定位：几何根本
  不相交，是测试自己写错了轴。
- **诊断方法论判例**：①Gradle 测试 stdout 默认不进控制台/报告——`System.out.println`
  诊断在 `--tests` 过滤跑里**不可见**，别用它；②把诊断数据拼进 assertion message
  （failure 里必然可见）是测试内实证隔离的最快路径；③`intersects` 是纯几何且 AABB
  不可变——当它对一个"明显相交"的盒对返回 false，先怀疑打印的盒不是比较的盒
  （轴写错），再怀疑玄学。
- **判例**：非对称轴值的盒构造必须逐轴复核（x/y/z 各自独立括值）；x=y=z 同值的
  测试用例对此类滑误免疫，恰使错误只在轴值分化的用例显形。

### applyPatches 后的出包新鲜度必须实证（Gradle up-to-date 跨任务图误判判例）

- **事故链**：16:15 出的 0.109.0 jar → 16:35 源修正（谓词 chunk→section 粒度）走
  amend+rebuildPatches+**applyPatches 全树重写**（mtime 16:35）→ 之后 `createMojmapBundlerJar`
  报 up-to-date **未重建**（16:15 jar 依然是旧谓词）。Gradle 的 packaging 任务对
  applyPatches 重写后的源-类一致性判断在跨任务图（test --rerun-tasks 重编译过 classes）
  场景下失真。
- **修复与门**：删产物 + `createMojmapBundlerJar --rerun-tasks` 强制重建 + `createPapoJar`
  改名；随后 `javap -c` 反汇编包内嵌套 jar 的 `ChunkEntitySlices.class` 验证 section
  粒度谓词字节码在场。
- **判例**：**applyPatches 之后出的包必须以源 mtime 对比或包内字节码抽查实证新鲜度**；
  up-to-date 绿灯不构成证据。createMojmapBundlerJar 的产物名是 paper-bundler-*，改名
  Papo-<ver>.jar 由 `createPapoJar` Copy 任务完成——直接跑 bundler 任务不出 Papo 名件。

### MSPT 同号槽终审：一次同序复跑复现不够，第五对破号定谳（相变槽漂移判例）

- **序列**：s4（首驻留槽）三序同号劣 +0.5/+1.4/+0.2 → 同序复跑 ② **+1.4 复现** →
  若止步于此按 R38 判例只能判"真差"；但改动全在漏斗路径而 MSPT 腿零漏斗
  （无机制通道），遂加跑第五对 → ③ **−0.1 破号**。
- **判例**：①**同序复跑复现 ≠ 定谳**——R42 的"复跑即破"是顺利情形；复现后仍有
  无通道/摆幅两道否决权，可追加独立对终审；②**相变槽特征**：合池只有两个相位
  过渡槽（末流送/首驻留）偏劣、稳态槽偏优的形态 = 过渡窗排空时序运气，不是代码
  通道——版本不可能选择性只拖慢过渡窗而加速稳态窗；③s4 版本内腿间摆幅（1.1ms）
  大于同号幅度带，序列本身在噪声带内。
- **会话恢复杀腿判例二度应用**（R44 判例）：pinned 对被会话暂停杀死于 B 腿中途
  （0x40010004 控制台中断）——A 腿虽完整完成仍整对重跑（配对腿须同会话时间邻接），
  不缝合不借用。

### 杂项工具纪律

- **Bash 会话恢复后 cwd 不保证**：恢复会话后 cwd 停在内部仓库
  `paper-server/src/minecraft/java`（上次 git 操作遗留），`./gradlew` 连续 127×4
  才发现；且 `cd X && cmd` 前缀在该会话里被剥不可靠。**稳态形态：`bash
  /f/Github/repo/Papo/gradlew -p /f/Github/repo/Papo …` 绝对路径 + `-p` 指项目根**，
  与 cwd 完全解耦。
- **空中红石时钟判例**：y=200 空中无支撑，中继器/红石线/火把时钟放置即弹跳失效、
  `/data get block` 对无 BE 方块返回空——**观察者对**（互视 facing）是唯一可靠的
  空中自启时钟（2gt 振荡、实心方块零支撑需求）；证活须 10×200ms 密采样
  （`/execute if block ... powered=true`），2gt 占空比下单次长采样必漏。

### 探针命令双斜杠事故：错误回显可同时满足 marker 门与在场门（批次188 判例）

- **事故链**：`probe()` 辅助给 cmd 前补 `/`，而调用点传入的串已带 `/` → 控制台收到
  `//data get ...` → `trimOptionalPrefix` 剥一层后 `/data` 作命令名 → "Unknown or
  incomplete command"。三重隐蔽：①同腿 `/fill`/`/setblock`/`summon`/`/say`（单斜杠）
  全部成功（farm 真建、ItemEntity 3,693 实测在场），唯探针全灭——命令族混杂掩盖根因；
  ②**错误回显行本身含 marker 子串**——`/say MARKER` 若失败其回显也能满足 marker 检测
  （`contains(MARKER)` 命中回显）；③`@e` 探针失败回显含 "CHURN-ITEMS-PRESENT" 字样，
  在场门 grep 同样被回显满足——本轮在场门=1 恰是回显贡献的假阳性。
- **修复与门**：①探针调用点去斜杠（辅助统一补前缀）；②harness 过滤器**排除
  `--[HERE` 错误回显**再打印（门只数 harness 打印行=正证据行）；③marker 检测加
  `!l.contains("--[HERE")`；④服务器自身 latest.log `grep -c "Unknown or incomplete"`
  是命令族失败总数的决定性证据（首轮=3 恰等于探针数→farm 命令族全绿）。
- **判例**：**console 探针门必须以"成功响应形态"为锚**（`data get <pos> <path>` 的成功
  响应是 `has the following block data: <值>`，**不含路径键名 "Items"**——按
  "Items.*count" 匹配必漏），且必须显式排除错误回显；错误回显与成功回显在子串门下
  不可分辨是探针门的默认脆弱态。

### 1.21.11 命令/红石实证三则（批次188 探针阶段，判负依据入档）

- **`/replaceitem` 不存在**（1.17 移除）；正确形式
  `/item replace block <pos> <slot> with <item> [count 1-99]`（ItemCommands.java 命令树：
  item→replace→block→pos→slot→with→item→count）。**追溯性勘误：R46 的 replaceitem
  播种自始未生效**（R46 探针回显 `[]` 即证据）——R46 场景实为空漏斗空查询+时钟，
  目标面=真实运行的空查询面，收割有效性不受影响；但 R46 文档中"物品环流+split
  转移"设计组件描述失实，以本条为准。
- **观察者仅向 FACING 侧面供能**（ObserverBlock L116 `POWERED && FACING == side ? 15
  : 0`）：互视对的两张脸互相消耗，**无法驱动邻接发射器**——发射器族 QC
  （DispenserBlock L125 `hasNeighborSignal(pos) || hasNeighborSignal(pos.above())`）
  理论可达但更新时序竞态使其不可靠。机械驱动设计三连败（命令语法×2+供能原理）后
  改 **summon 批式**（一次性命令、窗内零命令伪影、8 类轮转防合并、despawn 6000t>
  测量轴）。
- **无人服务器上 summon 的物品冻结不 tick**（ActivationRange 无玩家=不激活；Age 不增、
  OnGround=0b 原地悬停）但**漏斗吸取照常**（BE 侧无激活门）——探针验证机械可行性
  不需要 bot 在线；正式腿 bot 在场（MISC 列=水平 inflate×世界高的全高度柱，y=200
  物品在 bot 正上方即激活）后飞行/合并相才真实发生。

### 探针区块卸载静默假阴性（R48，与双斜杠回显假阳性互补）

- **事故链**：dig 探针首轮 bot 于 marker+60 断开（digMs 短时序）→ 区块票据
  掉落卸载 → marker+90 探针 `execute if block <pos> air run say ...` 在
  **未加载区块上静默翻 false，零错误回显**（既无 say 也无 "--[HERE"）——
  四条探针全静默，与"命令失败"形态完全相同，无法从回显区分。
- **判例**：①bot 驱动场景的探针窗必须保证 bot 在线（持有区块票据）；②探针
  **全静默 ≠ 命中失败**——先核区块加载状态（bot 断开时刻 vs 探针时刻）再
  怀疑协议本身；③与 R47 双斜杠事故构成互补对：一个错误回显**可满足门**
  （假阳性），一个无回显**可误判失败**（假阴性）——console 探针门必须既有
  成功响应锚又排除静默路径。
- 次轮 digMs=120s（bot 在线跨探针窗）即 AIR×3+STONE×2 全绿——协议本身
  无恙，纯探针设计缺陷。

### serverbound 包 ID 注册序提取：common 前缀包漏检=整体偏移（R48）

- **事故链**：从 GameProtocols.java 提取 serverbound 注册序的正则首版只匹配
  `GamePacketTypes\.`——但注册序列混有 `CommonPacketTypes.SERVERBOUND_KEEP_ALIVE`、
  `PingPacketTypes.SERVERBOUND_PING_REQUEST`、`CookiePacketTypes.*` 等 common/
  配置态前缀包，漏检 4 个 → PLAYER_ACTION 算出 0x19，而实证 MOVE_PLAYER_POS=
  0x1D 差 4。与批次141 clientbound 差一事故同族。
- **修复**：正则 `\w+PacketTypes\.`（任意前缀）+ **三锚点验证**（取三个
  已实证 ID——ACCEPT_TELEPORTATION=0x00/KEEP_ALIVE=0x1B/MOVE_PLAYER_POS=0x1D
  全中才可信推导）→ PLAYER_ACTION=0x28，探针实证挖掘生效。
- **判例**：注册序推导必须锚点实证收口；"包 ID 差一/差四"类偏移的第一嫌疑
  是序列提取漏检（前缀变体/条件注册），不是字段编码。

### `javac | head && bash` 管道掩盖编译失败跑旧类（R49）

- **事故链**：`javac … JoinQuitStormCensus.java 2>&1 | head -3 && bash 腿脚本 &`
  ——单文件编译缺 OfflineJoinBot 符号（-cp 未含 build/classes）报错，但管道
  出口取 head 的 0，`&&` 照常放行腿脚本 → **旧 .class 静默运行**（修复未编入，
  双计数 bug 残留于腿；仅显示计数翻倍，门语义仍立，数据有效）。
- **判例**：①编译→运行链必须 `set -o pipefail` 或先独立验类文件 mtime/
  javac 输出为空再启动；②`cmd1 | cmd2 && cmd3` 的 cmd3 看到的是 cmd2 的
  出口码——凡"编译成功才跑"的语义都不得用管道头。
- **同轮姊妹判例**：服务器 stdout 捕获流与 logs/latest.log 是同批行镜像，
  合并计数=2×（探针 joins=6 for 3 join 实证）——计数门只取单源；PlayerDataStorage
  成功存档零日志（仅失败 warn）——存档正证据锚 dat 文件计数/mtime。

### 不可并行两个 harness（同端口 25599 互踩，R50）

- **事故链**：OldObjectSample 探针服务器未退出即启动正式腿 → 两 server 抢
  25599：腿 bots 连到探针服务器；探针 bots SocketException（连接被中止）→
  探针 main 异常退出 → finally `stop` 杀掉探针服务器 → 腿 bots 全部断连
  （腿注定失败，且症状出现在"别人"的日志里）。
- **判例**：①基准腿永远串行——启动前 jcmd 扫残留 harness/服务器（端口
  独占门），存在即中止（alloccensus-r50old.sh 已内置）；②"你的主机中的软件
  中止了一个已建立的连接"出现在 harness A 而 B 刚启动时，第一嫌疑是端口
  共享互踩，不是协议。

## 2026-10-13 补充：批次 193 踩坑记录

- **多域叠加 harness 空间隔离设计**：复合普查（R52）把两个已验证域负载源（R48
  挖掘立方 + R47 漏斗农场）同窗叠加时，**负载源几何必须置于对方作用域之外**
  （农场 z+8 vs 挖掘候选 |dz|≤5）——否则 bot 会挖掉自己的漏斗（creative
  instabreak 对任何可达格生效），差分把"农场被破坏"误读为域面。域间零物理
  干扰是复合差分可归因的前提；配合 bot 零改动复用（joinClimbSpreadDig
  AndDisconnect），复合轮的协议风险只剩时间线共存（探针轮一次覆盖）。
- **万级命令 burst 时长仍须实测锚定**：14,461 命令（fill+summon）服务器侧
  处理实测 2.5s（R47 判例"不可假设"的又一数据点，量级远小于 bot 侧 120s
  latch 预算）；marker 锚（/say 回显）+ loadWarmed 锚（marker+12s 起 JFR）
  双锚制式在复合时间线同样成立。
- **自观测族随复合包数可加和放大**：JFR networkStatFor 每包 toString 族在
  复合窗 Δ+812 trips 假面霸榜第一（R48 挖掘窗 +56、R47 旋涡窗独立存在）
  ——各域包数叠加时自观测权重=可加和；R41 剥离判例在复合窗必要且直接
  适用（不剥离则差分第一面归因错误）。

## 2026-10-13 补充：批次 194 踩坑记录

- **keepalive 连环踢出=服务器侧停摆签名**：多个 bot 在数秒内相继 keepalive
  timeout（本轮 4 bot/8s）=所有在途 keepalive 同时到期=服务器 ~30s 停摆
  （本机环境瞬态：OS 调度/磁盘/AV），不是单 bot 协议失败（后者不会同时命中
  多个不相干连接）。诊断入口=临时目录 server latest.log（harness stdout 不含
  踢出行；"主机软件中止连接"出现在 bot 侧写入栈=连接已被服务器关闭的后效）。
  gate 破坏 → R44 判例整腿重跑不缝合；重跑通过=R48/R51 同序复跑判例瞬态定谳。
- **残留 harness 进程清理**：harness 主线程异常路径绕过 botPool.shutdown()，
  非守护池线程使 JVM 不退出（进程挂着但 server 已停）——taskkill 后必须
  jcmd 复核零 server.jar + 零 java 再重跑（端口独占门只扫 server.jar，挂着的
  harness 本身无端口但持有结果文件句柄）。
- **版本配对的收割批切分读法**：相邻版本配对差分=版本窗内变更净效应——选
  版本窗使其只含一个收割批（本轮 0.107→0.110 唯一变更=0298），差分面的
  塌缩/出现线即精确落在该批边界（StepBasedCollector 两腿同在证 R41 在窗前、
  betweenCorners 簇全塌证 0298 在窗内），无需逐面溯源。

## 2026-10-13 补充：批次 195 踩坑记录

- **stream 槽=MSPT 配对有效性锚**：判读任何相位槽前先验版本中性相（本轮
  stream 槽，farm/dig 未启动）的 |B−A|——超带（~1.5ms）=整对机器状态漂移
  （本轮裁决①的 A 腿 9.7ms vs 历史带 14.6-15.8=异常快机器窗口），整对判
  污染作废不用于判读（R44 整腿重跑判例的配对级推广）。
- **同号槽三步终审**（R48/R51 判例升级）：三序同号→同序复跑①复现仍不是
  终点（连续对的同号可能是漂移相关簇——机器状态跨腿相关，非独立证据）→
  须第二轮干净复跑对+每工作单元归一（本轮每物品 µs）：破灭+归一反优=瞬态
  定谳；持续同号+归一仍劣=真差开红线条调查。
- **jcmd 物品普查的位置纪律**：GC.run×2+class_histogram 的 safepoint 停摆
  （秒级）必须放在全部 mspt 采样窗之后、只影响后续状态探针（状态查询不受
  GC 影响）——普查值是"活数"（双 GC 后），与直方图 measure 同口径。

## 2026-10-13 补充：批次 196 踩坑记录

- **扩密度参数的几何覆盖冲突**：站立格从 10×10 扩 20×20 时原 z 起点（bz+6）
  会与漏斗排（bz+8）/双箱（bz+8/9）重叠且石台平台 /fill 会覆写漏斗——扩格
  须整体移到漏斗排之后（z+10 起）并全格平台；改参先做重叠审计（x/z 与漏斗
  排、箱、挖掘立方三查）。
- **密度轴与形态轴耦合**：扩站立密度同时消除了前形的带外落体相（全格平台
  =无落体）→窗内走查率降 6×——扩参腿与原形腿的收割面量级不可直接比，同形
  配对才能解读（本轮 0298 簇 175 vs R53 的 1,056 即此效应，非收割回归）。
- **Edit 多点修改的签名/调用点配对**：一次改 doFills 形参须同步改调用点与
  签名两处——两处 Edit 部分失败时编译错误被后台任务吞到输出文件尾（stdout
  文件 tail 才见），编译验证永远独立前台跑一次再启动腿脚本。

## 2026-10-13 补充：批次 197 踩坑记录

- **shell 布尔变量与退出码语义分离**：门变量 `ok=1` 表 pass，但函数结尾 `return $ok`
  把 1 当作失败退出码——腿门全绿却报 LEG FAILED。pass/fail 布尔必须显式转换为
  `return 0/1`；同理 `[ cond ] || ok=0` 风格的累加门结尾要单独裁决。
- **JFR 事件集可组合**：`jcmd JFR.start settings=profile duration=Xs
  jdk.ObjectAllocationSample#threshold=1ms jdk.OldObjectSample#enabled=true`——
  分配普查（1ms 阈）与存活归因（OldObjectSample）一次录制双聚合；长窗时自观测族
  绝对量随窗长线性累积（22min ~2,900 trips），归因前必须剥离。
- **扩窗即改相位构成**：把 60s 窗扩到 22min 后 ItemEntity 走完 5min despawn（vanilla
  语义）——实体相只占窗前段，实体面绝对量与短窗不可直接比（残余 109 vs 短窗 ~3,600
  不是回归）；扩时间参数前先想窗内对象的生命周期边界。
- **腿脚本入库腿选择参数**（`bash script.sh B` 只跑指定腿）——部分腿因脚本 bug 误报
  而数据完整时，修复后单跑剩余腿，不整脚本重放已绿腿。

## 2026-10-13 补充：批次 198 踩坑记录

- **Paper fill 对未加载位置直接拒绝**：`execute in <dim> run fill ...` 在目标区块
  未加载时回显 "That position is not loaded"（不报错、不排队、静默跳过）——异步
  区块体系没有主线程同步加载路径。跨维度（或任意远端）负载触发的通式=两波制：
  波① forceload add 区块区间（异步生成）→等生成完成→波② fill（已加载，生效）。
- **门变量必须在回显发生处带读窗**：fill 回显发生在命令发出后下一刻，若 harness
  的 probe 读窗（1.5s）不在该时点，门变量恒 0——腿被误判失败。数据有效性复核入口
  =临时目录 server latest.log 的命令回显计数（"Successfully filled"×N）。
- **JFR 样本量是负载形态的第一验收指标**：v1 腿 JFR 里维度生成帧为零（NoiseChunk
  样本全在命令发出前=overworld 残余）——负载没发生，先修形态再谈判读；磁盘 region
  增量（字节数/region 数）是生成量的旁证但来源要归因（forceload 邻域≠fill 触发）。

## 2026-10-13 补充：批次 199 踩坑记录

- **方块状态谓词探针的属性值按 vanilla 语义逐格推导**：红石 wire#k（距源第 k
  格）power=15−(k−1)——紧邻=15、第 7 格=9（不是 8：把"距源块"当"距首 wire"
  就是差一）。任何 `[property=N]` 探针先写一格的期望值再写整列。
- **probe 命中集扩展与新增 say 标签同步**：新轴探针 tag（如 RS-EDGE-*）忘了
  加进 probe() 的打印过滤器=harness 输出恒 0、门假失败（R57 加了 DIM-*、本批
  忘了 RS-*=同型失误两连）。通式：新增探针 tag 的 Edit 顺序=先改命中集再写
  调用点，漏一处 grep 一下再跑。
- **单态探针失败先看复合证据再重跑**：另一态正证据（日志双中）+JFR 里该负载
  的特征帧在场（in-stack 数百）=负载真实发生，探针差一只是测量瑕疵——复合门
  定谳免整腿重跑（~11min/腿）。
- **papo* 前缀帧=自有优化代价面**：分配普查栈帧出现 papo 前缀方法时，反查
  patches/features 补丁名即可定位是哪一批的历史优化（本例 papoCalculateTarget
  Strength=批次125 wire 目标强度合并），按代价面判例处理不误判为新面。

## 2026-10-13 补充：批次 200 踩坑记录

- **bot 新包 ID 必须从注册表源码提取，锚点外推禁用**："+10 偏移"（两个已验证
  锚点恰好同位推出的线性规律）在注册表其他位置失效——common/ping/cookie 包在
  特定位置穿插移位（0x35=SET_COMMAND_BLOCK 而非 CREATIVE_SLOT、0x3d=
  TELEPORT_TO_ENTITY 而非 USE_ITEM_ON）。唯一权威路径=
  GameProtocols.java SERVERBOUND_TEMPLATE 的 addPacket 定义序（正则
  `(\w+PacketTypes)\.(\w+)` 逐条，五锚点 0x00/0x1b/0x1d/0x28/0x2c 校准）。
  服务器日志踢因（解码出的包名+extra 字节数）是协议错位的实锚；与 R48
  "extraction regex 漏 common 前缀移位 4" 同源陷阱两连踩——通式：新包 ID
  提取+**单 bot 隔离验证器**（PlaceVerify 三探针：Inventory 回显/目标格/
  支撑格）实证后才进 harness。
- **1.21.11 BlockHitResult 尾双布尔**：writeBlockHitResult=BlockPos long+
  direction+cursor float×3+isInside+**isWorldBorderHit**（旧版单
  insideBorder）——载荷少一个字节即解码踢。与 1.21.2+ move 尾单字节打包
  同族：线格式逐版本演化，逐字段对照源码不算想当然。
- **BlockPlaceContext.replaceClicked 语义**：构造器
  `replaceClicked=getBlockState(点击格).canBeReplaced()`——点击格=空气时
  getClickedPos() 返回点击格自身（非 relative(face)）。放置风暴的支撑格被
  前序挖掘清空时石头落在支撑格；放置探针必须查支撑格，查外贴格恒零命中。
- **世界直存=最强放置证据**（WorldProbe 工具）：保存世界目录重开+forceload
  加载目标区块（R57 两波制+R48 未加载静默假规避）+`execute if block` 探针
  ——R58 复合门判例扩展：**探针坐标语义错误≠负载未发生**，bot 侧计数+
  JFR 特征帧+世界直存三源复合定谳免重跑。
- **会话恢复杀编排 shell 的数据判读**（R44 判例扩展）：shell 死而 java 孤儿
  活=JFR 永不启动（JFR.start 是 shell 侧 loadWarmed 锚后 jcmd，非 harness
  自为）——数据必然不完整，杀孤儿整腿重跑。腿前自查清单加一项：**门变量与
  harness 探针条件对照**（本轮 air≥3 无条件门 vs 探针② place==0 条件=B 腿
  必假失败，腿前拦下省一轮 10min）。
- **门阈值按候选池实测校准**：places≥1800 预估无据（d2≤25 表面格筛后真实池
  ~95/bot×12=1,140）——预估阈值先跑一腿实测池再定门（或首腿后校准并记录
  判例），阈值池外=门永假。
- **1ms 采样地板下的链级成像**：小风暴（10³ 次级动作）单链在聚合 top-40 里
  不可见，但全栈 in-stack 符号扫描（jfr print --events+grep 链符号）可见
  （77 事件）——链级显著性判定用符号扫描，聚合 top-N 只用于大面。

## 2026-10-13 补充：批次 201 踩坑记录

- **TNT summon 服务器回显格式**="Summoned new Primed TNT"（1.21.x 新格式，
  非旧版 "Object summoned"）——门用双模式匹配，harness 保留样本行打印
  （boomEchoSample）供格式演化时校准。
- **服务器日志行统计必须在 harness 内做**：CompositeCensus 的 logLines 只在
  harness 内存（log-tail 线程收集），不会进 leg.log——shell 门要消费服务器
  日志计数（summon 回显/joined/left 等）须 harness 内统计后以结构化行打印
  （`CompositeCensus.boomSummonEcho=N`）。R49 的 joins/lefts 计数同法=通式。
- **探针腿先行**：新负载形态先单跑"重"腿（风暴腿）验证探针命中/回显格式/
  量级三件——全绿后该腿数据直接留用为普查腿，再补对照腿；比先跑对照腿再
  发现形态缺陷省一轮 ~9min。
- **`execute unless entity` 反向探针**：负向断言（实体应不存在）用 unless
  变体可 say 化=门可 grep——正向探针族的补集形态（TNT 尽清探针）。
- **爆炸域是批次 73 memo 的实证点**：PapoSharedWireMemo 当年以"大红石/TNT
  批量更新域"立项但从未在爆炸负载下成像过——本轴 in-stack 在场（4→5）=
  memo 对爆炸域广播覆盖的首次直接实证（回流检查的新语义：不仅查无回归，
  还可证历史设计对新城段兑现）。

## 2026-10-13 补充：批次 202 踩坑记录

1. **雷击火统计性落点——固定点探针必 miss**：雷击点火是 10% 概率事件且落点在 hit 位 ±3 范围随机偏移，576 雷×10% 期望 ~57 火但分布在 12 bot×每雷 ~343 格候选域里；固定坐标 `execute if block` 探针即使扫 12 邻域点也接近必然零命中。判例：**统计性落点事件的探针必须多点邻域扫描（位×偏移组合展开）或改用 say-tag 计数器**；本腿 fire 探针零命中不构成负载未发生证据，由 summonEcho+JFR 特征帧复合定谳。
2. **控制台回显本地化陷阱**：`weather query`/部分 vanilla 命令的回显是 zh_cn translatable 组件（如"现在的天气是…"），**在服务器日志/管道里 grep 英文短语（"clear skies"）零命中≠命令未执行**。判例：协议回显探针一律用**恒英文**的通道——summon 回显（"Summoned new Lightning Bolt"）、say tag、execute if block 计数；本地化文本不可作门。
3. **探针腿先行+复合门链定谳（R58→R59→R60→R61 判例链成熟）**：负载发生性证据=回显计数（summonEcho 576/576）+JFR 特征帧（Lightning/FireBlock/Thunder/Weather 四型在场）+可命中的正探针，三源合取即定谳，单源测量瑕疵（落点统计性 miss、本地化 miss）不再触发整腿重跑。判例：**先跑风暴腿验证形态，数据留用，再补对照腿**——避免重跑 40 分钟风暴。
4. **天气轴差分的相位画像**：`weather thunder` 会同时改变 randomTick 模式（雪蔓延 SpreadingSnowyDirt 消长）与 spawn 模式（雷暴期 NaturalSpawner 活跃度提升=+123/+121 陪跑主体）；天气轴差分判读前必须先剥离 spawn/randomTick 陪跑再谈天气链本体（本腿链本体仅 23 帧=历史最低量级）。与 R57 维度轴（ambient spawn 混杂）、R60 自缩放负载（多干 40% 生成活）同族：**环境命令的差分=全机器相位切换，不止目标链变化**。

## 2026-10-13 补充：批次 203 踩坑记录

1. **villager summon 回显=display name 非实体类型名**："Summoned new **Librarian**"（职业名做实体显示名）——echo 计数匹配词绑定实体类型名（"Villager"）必零命中。判例：回显计数用类型无关词（"Summoned new"/"Object summoned"，R60 TNT 先例的通式化），样本行打印兼作措辞校准。
2. **门与 harness 输出约定必须腿前对齐（R59 门设计一致性判例新形态）**：对照腿（vill=1）无 summon 分支=harness 不打印 echo 行，shell 门把"无输出（none）"当失败——门变量在对照腿的"无输出口径"必须显式定义（`${vecho:-0}=0` 同过）。
3. **运行期结构突变容忍是集合快照遍历的立项前提**：Brain 行为集"构造后冻结"假设被 Villager.refreshBrain（职业转换运行期 addActivity）证伪——脏标记 lazy 重建是唯一正确形态（突变置脏，下 tick 重建=vanilla 突变后立即生效同构）；冻结假设必须 grep 运行期调用点证伪而非想当然。
4. **paperclip 嵌套 jar 的 javap 验法（R46 新鲜度判例补全）**：Papo-*.jar 是 paperclip 结构（server jar 嵌套于 META-INF/versions/<mc>/paper-<mc>.jar），外层 `javap -cp` 找不到 net.minecraft 类零输出——须解包内嵌 jar 再验（unzip META-INF/versions/... 后 javap）。
5. **对象复用≠内部数组复用（fastutil grow-ladder）**：ThreadLocal 对象壳复用后，fastutil 集合内部数组的扩容爬升（倍增 copyOf）仍逐查询发生——IO 线程池轮转使每线程槽反复爬升（JFR 时间分布全窗均匀=爬升特征签名）；预容量到工作集上界是彻底解。**配对腿是浮出残余同族面的唯一仪器**（R39"同族完成判例不留半脸"的测量面：单腿差分看不到，版本配对才浮出）。
6. **MSPT"后跑腿劣势伪影"二度实证（R37 判例）**：主序（A→B）B 劣 4/6+倒序（B→A）B 优 5/6+钉死 B 优 5/6——两序都是后跑腿系统性吃亏（时间趋势），与代码通道无关；同号槽判读必须先排除该形态（换序破号+钉死反优）再谈回归。

## 2026-10-13 补充：批次 204 踩坑记录

1. **fill 命令在 server 控制台零回显**（R61 wq 判例的新形态）：`/fill` 经 stdin 发给 server 后**根本不打印任何回显行**（非"本地化措辞 grep 不到"——是命令反馈通道不发控制台；576 命令 0 行实证）。判例：**以 fill 为负载驱动的门不可用 fill 回显计数**——正证据改由 `execute if block` say 探针（命中格在场）+JFR 特征帧（FlowingFluid/LiquidBlock）复合承担，harness 保留双词计数+样本行仅作观测。
2. **模板复制后的参数位错两犯**（vill=args[11]/fluid=args[12]）：从 R62 脚本复制 java 调用行少写一个 `0`，`fluid=2` 落在 vill 槽=腿跑了村民风暴。两次（发现腿+配对腿）都被探针门零命中兜底拦下（FLUID-WATER=0 → gateOk=0），VOID 目录留档不删。判例：**跨批次复制 harness 调用行后必须逐位重数参数序**；腿门的价值在腿前自查之后的最后一道兜底。
3. **预容量字节账本否决（trips 门≠字节门）**：fastutil 容器预容量按 R62 判例立项（trips 坍缩 44→0），但配对腿字节账本显示前置 256 槽数组本体 `<init>` +94 trips/+98.7MB **大于** ladder 拷贝节约 44.7MB——per-call 临时容器（每 spread() 一次即死）与 R62 ThreadLocal 持久槽（每查询反复爬升无界）是相反 regime，预容量判例不可外推。判例：**JFR trips 是可信度口径、字节是内存轴本位口径，两门分开判读**；配对中间版二进制是判负证据的生产器（负结果也要实测不要推测）。
4. **归因帧移动的配对判读**：de-lambda 后 miss 路径的 `pos.below()` 分配从 `lambda$isHole$0` 帧移到 `isHole` 直帧——配对差分中 A 侧该帧 0 trips 会伪装成"新增面"。判例：面比较按**分配语义**对齐（同一 below() 调用），不是按帧名。
5. **rebuildPatches 的前置**：feature 补丁从内仓（`paper-server/src/minecraft/java/.git`）**已提交**状态生成——工作树未提交的改动产出 "Saved modified patches (0/300)"。顺序恒为：改源→内仓 commit→rebuildPatches→test/compile 链门。

## 2026-10-13 补充：批次 205 踩坑记录

1. **flat item id 的源码声明序≠运行时注册序**：从注册表源码读的序号直接进 SET_CREATIVE_MODE_SLOT 是错的——dye 块在注册表中程序化注册使后续 id 集体漂移 +16：源码序 1066=white_dye，BONE_MEAL 真身=**1082**。定位法=四槽候选（1081/1082/1083/1084=black_dye/bone_meal/bone/sugar）写入后 `data get entity Inventory` 回显比对。判例：**协议风暴的 item id 必须运行时回显定位，源码序只能做候选外推**。
2. **sendPacket/sendRaw 无锁=跨线程并发写帧损坏**：风暴驱动线程的 USE 包与 bot 自身 walk 循环每 50ms 的 MOVE_PLAYER_POS 并发写同一 socket，帧交错后服务器静默丢弃——症状是"uses 全发但 ageadv=0"的假阴性（两次 VOID 腿的真因）。修=帧级互斥锁（write+flush 原子段）。判例：**R59 单线程发送从未踩中≠协议安全；多线程驱动的协议腿必须帧级互斥**，这类失败不抛异常只丢效果，最易误判为协议编码错误。
3. **farmland 干涸回退弹麦掉种链**：干 farmland（moisture<7）被 randomTick 打回 dirt→小麦弹落掉种→bot 走位拾取 wheat_seeds 占 slot 0→后续 give 落他槽→手持=种子在"种田"不在"催熟"。修=建田用 farmland[moisture=7]（保水+防回退）+手持武装坚持 SET_CREATIVE_MODE_SLOT 槽位直写（确定性覆盖，不用 give）。判例：**长窗协议风暴腿的目标方块态必须对抗 randomTick 漂移**（干涸/生长/蔓延三类自漂移都要锚定或排除）。
4. **探针确定性化（R60 统计性落点判例的确定性化）**：催熟一次推进 2-5 龄是随机分布——单龄单格探针有 miss 概率，必须多龄（age=2..5）×多位（cell 扫位）使"命中≥1"成为确定事件而非统计事件。另：bot 脚下中心格 wheat 不驻留（实体占位），探针位集必须显式避开该格（风暴驱动位与探针位同式计算即天然一致）。

## 2026-10-26 补充：批次 206（插件兼容修复轮）判例

### Windows 用户 SID 变更 → 全部 git 仓 dubious ownership（重建类任务 128）

**症状**：`rebuildPatches` 在 `rebuildResourcePatches` 稳定失败 `git stash push` exit 128（两次
重跑不过，非文锁间歇判例——判例"重跑即过"只适用于单次）。手跑 stash 暴露真因：`detected
dubious ownership`，仓库属主 SID `S-1-5-21-1626371262-…` ≠ 当前用户 SID
`S-1-5-21-3725959389-…`（Windows 用户配置文件重建导致本机 SID 变更；外层仓因当前会话
一直可写而隐性同病）。**修复**：`git config --global --add safe.directory` 登记外仓 +
两个内部仓（`src/minecraft/java` 与 `src/minecraft/resources`）。**判例**：①重建类任务
128 连续两次即应手跑内部 git 命令看真实报错，不要按文锁判例无限重跑；②safe.directory
只认 global/system 配置（repo 级自证无效）；③诊断入口=`rebuildResourcePatches` 的
`stash push`（rebuildSourcePatches 同病但可能先报别处）。

### 共享包/memo 与插件改包的兼容门（本轮 0302 的设计判例）

1. **递出即污染**：跨连接共享的包实例/缓存数组，只要曾递给可改它的对象（协议层插件管线、
   Bukkit API 持引用者），就必须假设被改。兼容门必须装在**递出点**且 fail-closed
   （未知 handler 一律判非纯——未来上游新增 handler 只损失优化不损失正确性）。
2. **纯度按精确类名**：子类化原生 handler=外来（类名不同即非纯）；匿名 handler 按声明类
   前缀（`Connection$N`）匹配。检测位选在武装/递出判定处（管线遍历 ~12 项，非每包）。
3. **内容校验回放**：identity 键控的压缩 memo 兼容"对象可能被改"的世界的办法=回放前对
   当前连接字节做 FNV-1a 校验（O(n) 扫描 << O(deflate)，哈希须在 deflate 前算——deflate
   可能移动包装缓冲的 readerIndex）。
4. **locale 敏感包永不 memo**：组件序列化按连接 locale 分派（FriendlyByteBuf adventure
   locale 属性），"包 codec 确定性"论证必须查到 codec 末梢的每个组件字段。
5. **NMS 公共字段即 API**：@Nullable 化（0278）、删字段（0273）、final 剥离都是二进制/
   行为破坏；懒化收益 ~210B/实例不够支付。fastutil 容量也保持 vanilla——容量改变迭代序。

## 2026-10-27 补充：批次 207（第二轮）环境判例

### F:/Java/21 与 C 盘 latest/jdk-21 双双消失——harness 起服 JVM 跟随自身 java.home

`C:\Program Files\Java\latest` 只剩 jdk-25，`F:/Java/21` 目录不存在（本会话 Gradle 工具链在
`F:/TEMP/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2` 自动供给 21.0.12.1）。SmokeJoinVerify
硬编码 `F:/Java/21/bin/java` 的起服常量随之失效。**修复**：起服 JVM 改为"harness 自身
`java.home`（harness 必须以 J21 启动）优先，旧路径回退"——钉死版本语义不变，绝对路径不再
随机器布局漂移腐烂。**判例**：工具里的 JDK 绝对路径常量必须可从运行时推导（java.home），
否则换机/重装即静默失效。

### 上游网络条件测试的翻转暴露过期测试数据（PlayerProfileTest）

`@EnableIfMojangServerAvailable`（1 秒连接超时探测 sessionserver）：昨日超时→两项签名测试
**跳过**（套件假绿）；今日连通→测试真跑→红（"Invalid test property signature, has the public
key changed?"）。校验路径=本地固定公钥(.der)+上游硬编码签名，Mojang 现实轮换签名密钥致上游
测试数据过期——与 Papo 改动零交集（git 溯源测试文件纯上游）。**判例**：网络条件门控制的
测试，绿灯可能只是"没跑"；跳过计数漂移（7↔8）是翻转的可见信号；上游数据过期类失败按
环境留档不改测试fixture。

### 自有并发测试的 ArrayList 丢失写（concurrentCreationConverges 8→7）

批次 147 测试用裸 ArrayList 收 8 线程产物——并发 add 丢失写（size 7/8 偶发）。改每线程槽位
数组。**判例**：并发测试的收集器必须是并发结构或按线程分槽；"期望 N 实得 N-1"的偶发失败
先查测试自己的竞态再怀疑产品。

## 2026-10-27 补充：批次 209——Java 25 全面迁移（用户决断，→0.117.0）

**变更**：根 `build.gradle.kts` 工具链与 `options.release` 21→25；`paper-server` run 任务去掉
JETBRAINS vendor 钉（JBR 25 本机无保证）并移除 JBR 专属 DCEVM 旗标；测试依赖 mockito
5.14.1→5.20.0（旧 ByteBuddy 不识 v69 类文件——297 测试 142 挂的唯一根因）。

**版本红线修订**："Java 21 工具链"自本批起不再是红线（这是 2026-10-27 用户明确决断的迁移，
非上游漂移）；mcVersion/apiVersion=1.21.11 与 paperweight 2.0.0-beta.19 红线**不变**。

**兼容性口径**：插件不受影响——Papo 不向插件开发者发布 api 工件，插件按上游 Paper 1.21.11
（repo.papermc.io，v65）编译，v65 类在 Java 25 运行时正常加载（真机冒烟 10/10 join 实证）。
已知残留风险：自带旧版 ASM 且会解析**服务器类**字节码的插件（罕见形态）可能不识 v69。

**性能注记**：Java 25 运行时收益入口——`-XX:+UseCompactObjectHeaders`（JEP 519，对本内存
优化型服务端是大头）、分代 ZGC、JIT 改进。运维建议以 COH 开旗做一轮配对浸泡对比。

**判例**：①工具链迁移的真实坑在测试栈（ByteBuddy/ASM 类文件版本）不在编译——javac 25
对反编译源码零告警错误一次过；②harness 起服路径含空格（Program Files）且无 `.exe` 后缀时
ProcessBuilder CreateProcess error=2——用 `File(java.home, "bin/java.exe")` 绝对路径；
③Gradle daemon 全会话本就跑在 PATH 的 Oracle JDK 25 上（工具链自动供给 21 编译），迁移=
改 toolchain 声明一行 + 测试栈升级。

## 2026-10-27 补充：批次 209 附属——Java 25 稳定特性实测（COH/AOT）

### COH（JEP 519，正式特性）——运维采纳

- **兼容门**：全量测试套件于 `-XX:+UseCompactObjectHeaders` 下全绿（含 mockito 内联代理
  与字节码操作——对象头布局变化零行为回归）。
- **配对浸泡**（MemSoakVerify，0.117.0 同 jar 旗标 A/B，12bot/150s 走/180s 驻留，同会话相邻）：
  对1 A=632,460 / B=608,815（−3.74%）；对2 A=620,162 / B=606,794（−2.15%）——两对同号，
  B 侧稳定（608.8/606.8），保守口径 **−2.2%~−3.7% 活集**。
- **采纳**：`runServer` 系任务默认加 COH；运维建议所有生产服加 `-XX:+UseCompactObjectHeaders`。

### AOT 缓存（JEP 483，正式特性）——四坑判例 + 实测

坑链（每坑实测揭发）：①`AOTMode=create` 必须先 `record` 产出 `-XX:AOTConfiguration`；
②**paperclip 形态自解包重执行——类路径逐次漂移，与 AOT 类路径稳定性根本冲突**，须用
mojmap bundler jar 固定路径；③bundler 把 libraries 解包进 CWD——record/create/use 必须
同一工作目录；④**AOT 配置文件与缓存都在 JVM 优雅退出时落盘**——taskkill /F 训练轮=零产物
（且半途 conf "not valid ... generated by the current JVM"）。

实测（bundler jar 固定路径+同工作目录+优雅退出）：cache 162MB；启动 Done 8.75-8.78s（基线
×3）→ **5.61-6.03s（AOT×3）≈ −33%**。运维口径：仅启动收益，长时服价值有限，重启频繁的
开发/滚动场景可选；不建议默认。

### MemSoakVerify 路径修复

起服/jcmd 路径改跟随 `java.home`（原 `F:/Java/21` 硬编码已死——与 SmokeJoinVerify 同判例，
批次 207 判例第二度应用）。

## 2026-10-27 补充：批次 210——工具链全面升级到最新（2026-10-27 查证）

### 升级矩阵（依据源均为当日权威源）

| 组件 | 旧 → 新 | 依据 | 验证 |
|---|---|---|---|
| Gradle wrapper | 9.4.1 → 9.8.2 | services.gradle.org/versions/current（9.8.2 构建 2026-10-19） | 双模块全量 build 绿，paperweight beta.19 兼容 |
| mockito（双模块+agent） | 5.20.0 → 5.24.0 | Maven Central release | 双模块全测试绿 |
| junit-jupiter | 5.12.2 → 5.14.4 | Maven Central 5 线 latest（6.1.3 被阻，见判例一） | 148+测试类绿 |
| junit-platform suite/launcher | 1.12.2/无版本 → 1.14.4 显式钉 | 与 jupiter 5.14.4 对齐 | 绿 |
| junit-pioneer | 2.2.0 → 2.3.0 | 2 线 latest（3.0 仅 RC1） | @CartesianTest 套件绿 |
| hamcrest | 2.2 → 3.0 | Maven Central release | 绿 |
| commons-lang3（api test） | 3.17.0 → 3.21.0 | release | 绿 |
| asm-tree（双模块） | 9.9.1 → 9.11.1 | release | 绿 |
| classgraph | 4.8.179 → 4.8.199 | 4.8 线 latest（6.0.0 被阻，见判例二） | 绿 |
| JDK | 25.0.5.0.2（不变） | Adoptium API：most_recent_lts=25 | 已是最新 LTS 线 |
| JMH | 1.37（不变） | Maven Central release=1.37 | 已是最新 |
| foojay-resolver | 1.0.0（不变） | plugin portal latest=1.0.0 | 已是最新 |
| paperweight | 2.0.0-beta.19（**不动**） | 红线：fork 基线（最新 beta.24 有意不取） | — |
| API 运行时依赖（joml/brigadier/jspecify 等） | 不动 | 属上游 API 兼容面，非工具链 | — |
| benchmark 镜像库（netty/gson/fastutil） | 不动 | 镜像服务端运行时版本，独立升级=失真 | — |

### 判例一：JUnit 6 跨版被"稳定线纪律"阻断

JUnit 全局 latest=6.1.3，但 `@CartesianTest` 依赖的 junit-pioneer 其 JUnit 6 适配线 3.0.0 仅 RC1
（非正式版）；稳定线 2.3.0 只配 JUnit 5。为 RC 重写测试基建违背本仓判例（不上非正式版），
故 JUnit 取 5 线 latest 5.14.4 并全文对齐 platform 1.14.4；pioneer 3.0 GA 后可重评估。

### 判例二：classgraph 6.0.0 删除 4.x 扫描 API——4.8 线收尾

6.0.0 移除了 `whitelistPackages`/`getSubclasses`/`getClassesImplementing`，被 5 个上游测试文件
使用（EntitySetItemSlotSilentOverride/ShearableDrops/Shearable/BlockPlayerDestroyOverride/
MetaHandledTags）。为测试扫描库重写上游测试=零收益补丁漂移 → 回退 4.8 线 latest 4.8.199
（179→199 仍取满 4.8 线补丁）。

### 副证：纯工具链升级不改发布产物

9.8.2 全量构建后 0.117.0 jar 任务 up-to-date（字节未变）——test 栈与 wrapper 变更不进入
发布产物；无需版本号变更。README 版本行陈旧（0.113.0）顺带修正为 0.117.0。

## 2026-10-27 续：批次 210 第二轮——两项"阻断"解除，JUnit 6.1.3 + classgraph 6.0.0 落地

### 判例一修订：JUnit 6 解封（pioneer 全仓仅 1 个消费者）

pioneer 的 @CartesianTest 全仓只被 AdventureCodecsTest 一个文件（5 方法）使用，且
`@MethodParameterSource` 是本地薄封装（MethodParameterSource/Provider 两个 util 文件仅服务它）。
解法：重写为原生 `@ParameterizedTest` + 笛卡尔 `@MethodSource` 参数工厂（styleOpsArgs/
componentOpsArgs，@TestComponents 九源合并为 testComponents() 流），删 pioneer 依赖与两个
util 文件。JUnit 升 **6.1.3**（jupiter/suite-engine/launcher 三件统一 6.1.3，双模块）。
稳定线纪律不变：没有采用 RC（pioneer 3.0.0-RC1），而是消除了对它的需求。

### 判例二修订：classgraph 6.0.0 落地（4 线已 EOL，升 6 是 JDK25 项目的受支持路径）

官方版本矩阵：4=JDK8-16（**EOL**）、5=17-24、6=25+。端口映射：`whitelistPackages`→
`acceptPackages`；`getSubclasses`→`getAllSubclasses`；`getClassesImplementing`→
`getAllClassesImplementing`；`loadClass(es)` 删除→`Class.forName(name, false, 测试类加载器)`
（Gradle 单类路径等价）；`MethodParameterInfo[]`→`List`（`getLast()`）。

**新坑判例：classgraph 6 默认什么都不扫**——必须显式 `.enableClasspath()`，否则任何
acceptPackages 扫描静默返回 0（编译不报错、测试表现为"found 0 sub types"或参数源为空）。
官方发行说明明示此 opt-in 变更；独立探针实证：不加=0 类，加后 127/19/768 类全找回。
另注意 6.0 拆为多工件（classgraph-classpath/-vfs/-base 传递依赖）——手工类路径探针需
凑齐四 jar，Gradle 自动解析无感。

### 验证与产物

双模块 build 全绿（9097 测试，原 5 个失败翻绿）；jar 仍 up-to-date（字节未变，无版本号变更）。
