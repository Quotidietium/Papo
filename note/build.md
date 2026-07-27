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

## 注意事项

- `paper-server/src/minecraft/` 在 `.gitignore` 中 —— 补丁生成的源码树**不进入版本库**，版本库只跟踪 `patches/` 目录。提交改动 = 提交补丁文件（`rebuildPatches` 的产物）。
- `paper-api/src/` 是直接提交的源码（无补丁机制），改 API 直接改源码并提交。
- 编译警告（`dep-ann`、`deprecation` 等）属上游正常现象，不影响构建。
- 首次构建会向 `~/.gradle/caches/paperweight` 写入数 GB 的 mache/反编译缓存，注意磁盘空间。
- 同步上游修复时：`git fetch upstream`，从 `upstream/main` cherry-pick 适用于 1.21.11 的提交；涉及补丁文件的冲突需人工解（补丁是文本 diff，冲突后通常要 `applyPatches` → 手工修复源码 → `rebuildPatches`）。
