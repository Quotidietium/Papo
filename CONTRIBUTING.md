# Papo 开发指南

本仓库是私人性能 fork，不走上游 PaperMC 的投稿流程（PR 指南、审核看板等不适用）。
本文只描述本 fork 的实际开发方式；构建与踩坑判例的完整版在 [note/build.md](note/build.md)。

## 红线（先读）

1. **游戏版本钉死 Minecraft 1.21.11**：`gradle.properties`（mcVersion/apiVersion）与
   paperweight 2.0.0-beta.19 绝不能取上游 26.x 侧的值。Java 工具链自 2026-10-27（批次 209，
   用户决断）为 **25**——这是本 fork 的既定形态而非上游漂移。
2. **默认行为与 vanilla/Paper 等价**：只做可证等价的优化；涉及行为面（时序、事件、随机）
   的候选默认判负，除非用户明确授权。
3. RNG 族（种子派生）、插件可见 API、存档格式不做变更。

## 补丁工作流

改动分两类，走不同通道：

- **`paper-server/src/minecraft/java/`**（Minecraft 源码）：内部 git 仓库管理。
  改源码 → 内部仓库按优化粒度 `git commit`（subject 用英文 kebab，它决定补丁文件名）
  → `./gradlew :paper-server:rebuildPatches`（**单独一次调用**）→ 完整 `applyPatches` 验证
  → 外层仓库提交 `paper-server/patches/` 下的补丁文件。
- **`paper-server/src/main/java/`**（CraftBukkit 侧）与 **`paper-api/src/`**：直接改源码
  直接提交，不占 feature 补丁编号。

注意：`rebuildPatches` 会自动 `git add` 全部产物，提交前先 `git reset` 再只 stage 本次
目标的文件。手工编辑 `.patch` 的三条硬规则（空白上下文行、hunk 计数、`+_` 行号）与
校验脚本见 build.md。

## 验证门（缺一不可）

1. `compileJava --rerun-tasks --no-configuration-cache` 显式 BUILD SUCCESSFUL（工具链 25）
   （configuration cache 与 up-to-date 绿灯不构成证据）。
2. 全量 `test` 套件绿；触及 NMS 静态的 mock 测试必须自带 `@BeforeAll` bootstrap。
3. 行为等价改动附恒等单测（oracle 对拍），机械改动附 JMH 微基准
   （`benchmark/`，含自检 main）或真实服务器配对腿。
4. 出包后以 `javap` 抽查嵌套 jar 字节码实证改动进包（paperclip 结构外层查不到类）。

## 文档纪律

每批工作的记录四件，全部在 `note/`：

- [note/optimizations.md](note/optimizations.md)：逐批次日志（改动、等价性、风险、补丁号）。
- [note/release/`<版本号>`.md](note/release/)：每个 papoVersion 的变更记录。
- `note/report/perf/`：性能量化报告（索引见其 README.md）。
- [note/build.md](note/build.md)：新踩的坑写成判例追加进去。

## 提交规范

- 细粒度提交：一个优化/一批文档一个 commit，出问题可精确回滚。
- 不写协作者与 Co-author 信息。
- 版本号变更（`papoVersion`）与对应 release note 同批提交。
