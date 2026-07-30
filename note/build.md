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

## 注意事项

- **游戏版本红线：始终保持 Minecraft 1.21.11**。同步上游/合并分支时，`gradle.properties`（mcVersion/apiVersion=1.21.11）、根 `build.gradle.kts`（paperweight **2.0.0-beta.19**、Java 工具链 **21**、snapshots 仓库）绝不能取上游 26.x 侧的值。2026-07-30 的事故：合并 ver/1.21.11 时以 26.x 为基底解决冲突，paperweight 变 beta.21 导致 `spigot {}` 等 DSL 无法解析、构建脚本编译失败（报错为 "Unresolved reference 'spigot'/'createMojmapPaperclipJar'"），mcVersion 变 26.2。修复方式：内容恢复提交（`git commit-tree <1.21.11树> -p HEAD` + reset），不改写历史。教训：**configuration cache 会掩盖构建脚本损坏**——compileJava 命中缓存成功，但 rebuildPatches/help 触发脚本重编译才暴露问题；合并后必须跑一次 rebuildPatches 或 help 验证。
- `paper-server/src/minecraft/` 在 `.gitignore` 中 —— 补丁生成的源码树**不进入版本库**，版本库只跟踪 `patches/` 目录。提交改动 = 提交补丁文件（`rebuildPatches` 的产物）。
- `paper-api/src/` 是直接提交的源码（无补丁机制），改 API 直接改源码并提交。
- 编译警告（`dep-ann`、`deprecation` 等）属上游正常现象，不影响构建。
- 首次构建会向 `~/.gradle/caches/paperweight` 写入数 GB 的 mache/反编译缓存，注意磁盘空间。
- 同步上游修复时：`git fetch upstream`，从 `upstream/main` cherry-pick 适用于 1.21.11 的提交；涉及补丁文件的冲突需人工解（补丁是文本 diff，冲突后通常要 `applyPatches` → 手工修复源码 → `rebuildPatches`）。
