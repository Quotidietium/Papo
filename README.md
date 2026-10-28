# Papo

基于 [Paper](https://github.com/PaperMC/Paper)（Minecraft 1.21.11）的高性能服务端 fork，
专注于**可证行为等价**的底层性能优化：默认行为与 vanilla/Paper 语义逐位一致，
每一处优化都附带等价性论证、恒等单测与真实服务器实测验证。

- 游戏版本：Minecraft **1.21.11**（protocol 774）
- 运行时：Java 25（0.117.0 起编译目标）；生产服建议加 `-XX:+UseCompactObjectHeaders`（实测活集 −2~4%）
- 当前版本：**0.117.0**（`gradle.properties` 的 `papoVersion`，独立于 Paper API 版本演进）
- 许可：GPL-3.0（随上游 Paper，见 [LICENSE.md](LICENSE.md)）

## 核心成果

| 战役 | 结果 |
|---|---|
| 内存驻留（R5-R22，0.80.0→0.92.0） | 每区块驻留 189.5→101.4KB（**−46.5%**），四重闭合复验 |
| 分配源（R24-R40，0.92.0→0.105.0） | 流送窗事件 **−32.6%**/按区块 **−51.7%**，同窗吞吐 **+39.7%** |
| 红石轴（R4，0.59.0→0.80.0） | N=441 环振荡器计划 tick 相位中位 **−19.8%**，振荡速率逐位不变 |
| 分配普查 | 二十个负载域全覆盖（流送/盘载/实体/红石/爆炸/雷电/村民/流体等），收割面六维终验零回流 |
| 延迟守护 | 相邻版本三序 MSPT 配对守护全零回归 |

红线：稳定性、安全性、API/行为兼容；优先可证行为等价的改动，判负候选实测留档后回退。

## 构建

需要 JDK 25 与网络（Mojang / PaperMC 仓库）；自 0.117.0 起服务器与 API 以 Java 25 编译（类文件 v69），插件仍按 Paper 1.21.11 目标编译（v65 类在 25 上正常加载）。Windows 本机构建判例（Gradle daemon、
网络、补丁工作流踩坑）见 [note/build.md](note/build.md)。

```bash
./gradlew applyPatches            # 应用补丁，生成可编辑源码树
./gradlew createMojmapBundlerJar  # 编译打包（paper-server/build/libs/）
./gradlew :paper-server:createPapoJar  # 复制重命名为 Papo-1.21.11-<papoVersion>.jar
./gradlew test                    # JUnit 测试套件（含 37 个 Papo 恒等/回归测试类）
```

## 文档（note/）

| 文档 | 内容 |
|---|---|
| [note/build.md](note/build.md) | 构建环境、补丁工作流、全部踩坑判例 |
| [note/optimizations.md](note/optimizations.md) | 优化总日志（批次 1-205，每条含等价性论证） |
| [note/report/perf/README.md](note/report/perf/README.md) | 性能报告时代索引与终局状态（先读这份） |
| [note/release/](note/release/) | 逐版本变更记录 |
| [note/ops-empty-world-ticking.md](note/ops-empty-world-ticking.md) | 运营配置旋钮的源码级语义 |
| [benchmark/](benchmark/) | JMH 微基准 + 离线协议 bot + 真实服务器测量 harness |

## 上游

- [Paper](https://github.com/PaperMC/Paper)（PaperMC）——本 fork 的上游与本体的绝大部分工程量来源
- 上游漏洞报告与支持请走 [PaperMC 官方渠道](https://github.com/PaperMC/Paper/issues)
