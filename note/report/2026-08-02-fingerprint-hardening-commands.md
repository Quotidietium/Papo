# Papo 指纹泄露加固补遗：V5 命令权限（批次 52）

> 报告日期：2026-08-02
> 关联：[批次 51 fingerprint-hardening 报告](2026-08-02-fingerprint-hardening.md)（brand/status/plugin-channels 三个被动向量）。本批补齐第 4 个开关，闭合插件泄露主题。

## V5 — `/plugins` `/version` `/help` 默认权限（唯一直接吐明文插件清单的向量）

- **实证**：[CommandPermissions.java:17-20](../../paper-api/src/main/java/org/bukkit/util/permissions/CommandPermissions.java#L17)（paper-api）将 `bukkit.command.help` / `bukkit.command.plugins` / `bukkit.command.version` 三者 `PermissionDefault` 设为 **TRUE（人人可用）**。`PluginsCommand` 输出完整插件清单 `(N): PluginA, PluginB...`，`VersionCommand` 输出每插件版本并支持 `/ver <substring>` 模糊匹配。这是所有向量里唯一**直接吐完整明文插件清单**的——虽是主动向量（客户端需发命令），但默认权限 TRUE 使任意玩家（含改装客户端）可触发。
- **加固**：`paper-global.yml` 的 `fingerprint-hardening.commands.player-visible-defaults`：`true`（现状）/ `op`（仅 OP）/ `false`（默认无人）。
- **实现（直接提交，无补丁）**：
  - [GlobalConfiguration.FingerprintHardening.Commands](../../paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java)：新增 section（`playerVisibleDefaults` String + `papoResolveDefault()` → PermissionDefault）。
  - [CraftDefaultPermissions.registerCorePermissions()](../../paper-server/src/main/java/org/bukkit/craftbukkit/util/permissions/CraftDefaultPermissions.java#L11)：`CommandPermissions.registerPermissions(parent)` 之后调 `papoOverrideCommandVisibility()`——按 config 用 `Permission.setDefault(def)` 覆写三者 + `recalculatePermissibles()`。三者经 `Bukkit.getPluginManager().getPermission(name)` 取得（DefaultPermissions 经 `addPermission` 注册进 PluginManager，[DefaultPermissions.java:26](../../paper-api/src/main/java/org/bukkit/util/permissions/DefaultPermissions.java#L26)）。
  - **时机**：`registerCorePermissions` 在 `CraftServer.enablePlugins`（:605）调用，此时 `pluginManager` 已就绪（:416 构造）、**无在线玩家**，故 `setDefault`+`recalculate` 安全（不影响已在线玩家）。
  - **注意**：仅启动时应用一次；config 改动需**重启生效**（与其余三个 live 读取的开关不同——文档已注明）。
- **等价性/兼容性**：`true`（默认）行为与 0.27.0 逐字一致；config 未加载时（极早）回退 TRUE。`op`/`false` 仅改变这三条命令的**默认**权限——OP 仍可经权限附加/管理员覆盖使用，插件显式赋权不受影响。
- **风险**：低。切到 `op` 后普通玩家失去 `/plugins` `/ver` `/help`（少数老服依赖需自查）。

## 验证

- compileJava（`--no-configuration-cache`）BUILD SUCCESSFUL；全量 test BUILD SUCCESSFUL（零 FAILED）。
- 行为自检 [FingerprintHardeningSelfCheck](../../benchmark/src/papo/bench/FingerprintHardeningSelfCheck.java) 扩到 **25 项 ALL OK**（新增 commands 的 true/op/false/大小写/空/非法回退 7 项）。
- 性能影响：可忽略——启动时一次性 `setDefault`+`recalculate`（无在线玩家），运行时零开销（权限按 default 求值，无额外查表）。

## 配置示例（paper-global.yml，完整四开关）

```yaml
fingerprint-hardening:
  brand-payload:
    mode: REAL          # REAL | VANILLA | CUSTOM   （V2，批次51）
    custom-value: ""
  status:
    version-string: REAL # REAL | VANILLA | CUSTOM   （V3a，批次51）
    custom-value: ""
  plugin-channels:
    broadcast-mode: ALL # ALL | WHITELIST | NONE      （V1，批次51）
    allowed-channels: []
  commands:
    player-visible-defaults: true  # true | op | false  （V5，批次52；改后需重启）
```

至此，fingerprint-hardening 覆盖 survey 测绘的全部主要向量（V1/V2/V3a 被动 + V5 主动），插件泄露主题闭合。V4（Brigadier 命令树）复用既有 `spigot.yml` `commands.send-namespaced`，无需新开关。
