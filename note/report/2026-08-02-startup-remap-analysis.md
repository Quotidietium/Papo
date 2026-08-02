# Papo 启动耗时分析报告：Remapping 篇

> 报告日期：2026-08-02
> 分析对象：Papo 0.25.1（Mojmap bundler jar：`Papo-1.21.11-0.25.1.jar`）
> 证据来源：(a) 仓库内测试服日志 [run/logs/latest.log](../../run/logs/latest.log)（0.1.0 首次新世界启动）；(b) 源码机制实证（行号见文中链接）。**未访问用户实际生产服务器日志**——文末附自查步骤，请对照确认。

---

## 0. 结论速览（TL;DR）

1. **运行时 remapping = `[ReobfServer]`**，是为「传统 Spigot 插件兼容」把 mojang 命名的服务端**重新映射成 Spigot 命名**，作为插件 classpath 的 server jar。在仓库日志里实测 **5371ms**（占首次启动 17.967s 的约 30%）。

2. **它不是每次启动都跑**。ReobfServer 只在缓存文件 `plugins/.paper-remapped/remap-classpath/<mappingsHash>.jar` **缺失时**才执行；该缓存命中后会**完全跳过**（秒级）。`mappingsHash` 基于映射文件内容，**不含 git hash / 构建时间**，版本不变就一直稳定。

3. **用户「每次启动都慢」的最可能根因**：部署 / 重装 / 备份恢复流程**清掉了（或没保留）`plugins/.paper-remapped/` 这个隐藏缓存目录**，导致每次首启都重新 reobf 一次。这是头号嫌疑。

4. **三条优化路径**（按推荐度）：
   - **A. 保留缓存目录**（零风险，推荐）——让 reobf 只在首次/升级时跑一次。
   - **B. 改用 reobf bundler jar**（`createReobfBundlerJar`）——从机制上**彻底跳过**运行时 ReobfServer；副作用是失去 mojmap 反射转译层（仅影响极少数特殊反射插件）。
   - **C. 加 `-Dpaper.disablePluginRemapping=true`**——禁用全部插件 remapping；**仅适合不依赖任何 NMS 反射的纯现代插件服**，对大多数真实服不现实。
5. **不要**试图删除/替换 reobf 代码本身——它是上游 Paper 的插件兼容机制，删除会破坏 Spigot 插件兼容红线。

---

## 1. 启动时间线拆解（实证日志）

来源 [run/logs/latest.log](../../run/logs/latest.log)（版本 0.1.0-DEV，**首次启动 + 新世界**，22:54:45 开始）：

| 时刻 | 阶段 | 耗时 | 说明 | 稳态是否复现 |
|---|---|---|---|---|
| 22:54:45 | bootstrap + PluginInitializerManager | ~1s | JVM 启动、插件初始化器 | 是（固定） |
| 22:54:46→51 | **`[ReobfServer] Remapping server`** | **5371ms** | **本次分析主角** | **否（缓存命中后跳过）** |
| 22:54:51→54 | DataConverter MCTypeRegistry init | ~809ms（异步线程） | 数据转换器注册，部分阻塞 datapack 加载 | 是 |
| 22:54:54→55 | 环境 / datapack 加载 | ~1s | recipes(1470) + advancements(1584) | 是 |
| 22:54:57→58 | 服务端启动 / 网络 / 密钥 | ~1s | Netty IO、keypair 生成 | 是（keypair 可缓存） |
| 22:54:58→55:01 | **Preparing spawn area** | **~3.6s** | 主世界 3076ms + 末地/下界 | **否（仅首次新世界）** |
| 22:55:02 | `Done` | — | 总计 **17.967s** | — |

> ⚠️ 该日志是「首次启动 + 全新世界」，含 spawn 准备 3.6s（稳态无）。**稳态启动的固定大头只剩 ReobfServer（若未缓存）+ DataConverter + datapack/recipes**。因此 ReobfServer 在每次都触发时确实是稳态启动的最大单项开销。

---

## 2. ReobfServer 机制深度解析（源码实证）

### 2.1 它在做什么

[ReobfServer.java:53-83](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L53-L83)：用 `net.neoforged.art`（AutoRenamingTool）把**整个 Papo server jar**（约 93MB）按 `reobf.tiny` 映射，把 mojang 命名（`net.minecraft.world.entity.MobCategory`）**重命名为 Spigot 命名**（`net.minecraft.server.v1_21_R7.EnumCreatureType` 等），并打上 `Spigot-Namespace` manifest 标记。产物作为**插件 classpath 上的 server jar**，让传统 Spigot 插件（编译时用 Spigot 映射、靠反射访问 NMS）能正常工作。

关键参数：[ReobfServer.java:72](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) `.threads(1)`——**硬编码单线程**重映射整个 jar，这是 5.4s 耗时的直接原因。

### 2.2 触发条件（决定它何时跑）

[ReobfServer.java:29-39](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L29-L39)：

```java
ReobfServer(...) {
    if (this.mappingsChanged()) {        // 缓存 jar 不存在 → 跑 reobf
        this.load = mappings.thenAcceptAsync(this::remap, executor);
    } else {
        // "Have cached reobf server for current mappings." → 跳过
        this.load = CompletableFuture.completedFuture(null);
    }
}
private boolean mappingsChanged() {
    return !Files.exists(this.remappedPath());   // 缓存文件是否存在
}
private Path remappedPath() {
    return this.remapClasspathDir.resolve(MappingEnvironment.mappingsHash() + ".jar");
}
```

**结论**：触发条件 = **缓存文件 `<mappingsHash>.jar` 不存在**。一旦存在，直接 `completedFuture(null)`，零开销跳过。

### 2.3 缓存路径与 hash 构成（决定它何时失效）

缓存目录链（[PluginRemapper.java:61-70](../../paper-server/src/main/java/io/papermc/paper/pluginremap/PluginRemapper.java#L61-L70)）：

```
<pluginsDir>/.paper-remapped/remap-classpath/<mappingsHash>.jar
            └ PAPER_REMAPPED  └ REMAP_CLASSPATH
```

- `<pluginsDir>` 默认 `plugins/`（可被 `-P` 参数覆盖）
- 即缓存默认在 **`plugins/.paper-remapped/remap-classpath/<hash>.jar`**（**隐藏目录**，很容易被运维忽略）

`mappingsHash()` 构成（[MappingEnvironment.java:41-56](../../paper-server/src/main/java/io/papermc/paper/util/MappingEnvironment.java#L41-L56)）：

```java
private static @Nullable String readMappingsHash() {
    final Manifest manifest = JarManifests.manifest(MappingEnvironment.class);
    if (manifest != null) {
        final Object hash = manifest.getMainAttributes().getValue("Included-Mappings-Hash");
        if (hash != null) return hash.toString();   // 首选：manifest 里的固定 hash
    }
    return Hashing.sha256(mappingsStreamIfPresent());  // 回退：reobf.tiny 的 sha256
}
```

**关键**：hash 来源是 manifest 的 `Included-Mappings-Hash` 或 `reobf.tiny` 的 sha256，**两者都只与映射文件内容有关，不含 git commit、构建时间、BUILD_NUMBER**。所以：
- 同一个 Papo 版本 jar → hash 恒定 → 缓存可长期复用
- 只有**升级版本 / mappings 变了 / 手动删了缓存目录**才会让 hash 变或缓存失效 → 重新 reobf

### 2.4 会不会创建 ReobfServer 的前置短路

[PluginRemapper.java:73-76](../../paper-server/src/main/java/io/papermc/paper/pluginremap/PluginRemapper.java#L73-L76)：

```java
public static @Nullable PluginRemapper create(final Path pluginsDir) {
    if (MappingEnvironment.reobf() || !MappingEnvironment.hasMappings()) {
        return null;   // ← 短路：不创建 PluginRemapper → 不做任何 reobf
    }
    return new PluginRemapper(pluginsDir);
}
```

`reobf()` 的判定（[MappingEnvironment.java:33-41](../../paper-server/src/main/java/io/papermc/paper/util/MappingEnvironment.java#L33-L41)）：

```java
private static boolean checkReobf() {
    if (MobCategory.class.getSimpleName().equals("MobCategory"))       return false; // mojang 命名 → mojmap 运行时
    else if (MobCategory.class.getSimpleName().equals("EnumCreatureType")) return true;  // Spigot 命名 → reobf 运行时
}
```

Papo 是 **mojmap** jar → 运行时该类名为 `MobCategory` → `reobf()==false` → **不会被短路** → 会创建 ReobfServer（这正是日志里看到 reobf 的原因）。

> 这条短路是**路径 B（换 reobf jar）能消除 reobf**的法理依据：reobf jar 运行时该类名为 `EnumCreatureType` → `reobf()==true` → `create()` 直接返回 `null` → 没有 ReobfServer。

---

## 3. 头号根因：为什么「每次启动都慢」

既然机制上 reobf 应只跑一次，用户仍感知「每次慢」，最可能的场景（按概率）：

1. **部署流程每次都重建/清空了 `plugins/.paper-remapped/`**（最可能）
   - 该目录是隐藏目录，很多运维面板的「重装」「恢复备份」「同步 plugins」流程不会保留它；
   - 每次缺失 → `mappingsChanged()==true` → 每次 reobf 5+s。

2. **每次都换工作目录 / 容器无持久化**
   - Docker 等环境若没把 `plugins/.paper-remapped/` 挂载为持久卷，容器重启即丢缓存。

3. **无写权限**
   - `AtomicFiles.atomicWrite`（[ReobfServer.java:68](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L68)）若因权限失败抛异常 → 缓存写不进去 → 下次仍判定缺失重来（理论上会抛 RuntimeException 阻止启动，较少见）。

> 次要可能：用户实际跑的是 `runServer`/`runDevServer`（开发任务），这类任务每次构建 jar 且工作目录可能变化，但「实际使用」通常指生产部署，故以上 3 条为主。

**自查方法**（见第 6 节）：在你的服务器 `plugins/` 下找 `.paper-remapped/remap-classpath/` 是否存在 `.jar` 文件。

---

## 4. 各启动阶段：能否替换 / 删除 / 优化

| 阶段 | 能否删除 | 能否替换 | 可优化性 | 说明 |
|---|---|---|---|---|
| **ReobfServer**（remap 5.4s） | ❌ 删代码会破坏 Spigot 插件兼容 | △ 可换 reobf jar 规避 | ✅ **保留缓存即免跑** | 唯一真正的 remapping 开销 |
| Paperclip prepare（下载/拼装 vanilla） | ❌ | — | ✅ 已缓存（`versions/<mc>/paper-<mc>.jar`） | 仓库日志里仅 ~1s，非瓶颈 |
| DataConverter init（~0.8s） | ❌ 存档升级必需 | — | △ 已异步线程化 | 不在 remapping 范畴 |
| recipes/advancements 加载 | ❌ | — | △ 上游已优化 | 非本项目可改 |
| Spawn area 准备（首次 3.6s） | — | — | ✅ 仅首次新世界 | 稳态无 |
| **`threads(1)` 单线程** | — | — | ⚠️ 可改多线程但收益有限（见 5.4） | reobf 只跑一次，提速 ROI 低 |

---

## 5. 优化方案（分场景，含取舍）

### 方案 A：保留缓存目录（零风险，强烈推荐）

**做法**：确保 `plugins/.paper-remapped/` 在部署/重启/备份恢复时**不被删除**。

- 效果：reobf 仅在「首次启动」或「Papo 升级（mappings 变）」时各跑一次，之后永久跳过。
- 代价：无。
- 适用：**所有场景**。这是性价比最高、最该先做的。
- 操作要点：
  - 备份/同步脚本把 `plugins/.paper-remapped/` 一并纳入；
  - Docker 把 `plugins/`（含该隐藏子目录）挂载为持久卷；
  - 面板「重装服务端」时只替换根 jar，不动 `plugins/`。

### 方案 B：改用 reobf bundler jar（从机制上消除 reobf）

**做法**：分发用 `./gradlew :paper-server:createReobfBundlerJar` 产物（reobf 命名 bundler jar）替代当前的 mojmap bundler jar。

- 原理：reobf jar 运行时 `MobCategory` 类名为 `EnumCreatureType` → `reobf()==true` → [PluginRemapper.java:74](../../paper-server/src/main/java/io/papermc/paper/pluginremap/PluginRemapper.java#L74) 直接 `return null` → **不创建 PluginRemapper，ReobfServer 根本不存在**，启动零 remapping。
- 效果：彻底删除启动期的 5.4s reobf（无论缓存与否）。
- 代价 / 风险：
  - 失去 mojmap 模式独有的 **reflection-rewriter 反射转译层**（[PaperReflection.java](../../paper-server/src/main/java/io/papermc/paper/pluginremap/reflect/PaperReflection.java)）——该层把插件对 Spigot 命名的反射调用**转译**回 mojang 命名。reobf jar 下服务端本身即 Spigot 命名，多数反射直接命中，**绝大多数插件不受影响**；仅极少数依赖 mojmap 特定反射语义的插件理论上有差异。
  - Papo 的 202 个性能补丁均在 mojmap 源码树编译，reobf 只重命名不改逻辑，**行为等价，补丁不受 jar 类型影响**。
- 适用：纯性能向、插件以现代 paperweight 插件为主的服；想最大化首启速度。
- **前置确认**：建议先在测试服用 reobf jar 跑一遍全部插件，验证无异常再上生产。
- 落地（可选，如确认要走此路）：在 [paper-server/build.gradle.kts](../../paper-server/build.gradle.kts) 的 `createPapoJar` 改为从 `createReobfBundlerJar` 取产物。

### 方案 C：禁用 plugin remapping（`-Dpaper.disablePluginRemapping=true`）

**做法**：启动加 JVM 参数 `-Dpaper.disablePluginRemapping=true`。

- 原理：[PluginInitializerManager.java:38-40](../../paper-server/src/main/java/io/papermc/paper/plugin/PluginInitializerManager.java#L38-L40) 直接 `pluginRemapper = null`，跳过全部插件 remapping（含 ReobfServer 与插件本身的 reobf）。
- 效果：彻底消除 remapping。
- 代价 / 风险：**任何依赖 NMS 反射 / CraftBukkit 私有反射 / Spigot 命名的传统插件都会失效**。
- 适用：**仅**装的都是不碰 NMS 的纯现代 Paper 插件的服。对大多数真实服（常用 RPG/经济/物理等含 NMS 的插件）**不现实**。
- 建议：仅作「排查对照」——临时开启可验证「reobf 是否就是启动慢的主因」。

### 方案 D（不推荐，仅记录）：把 `threads(1)` 改多线程

[ReobfServer.java:72](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) `.threads(1)` 改为按 CPU 核数。理论上能把单次 reobf 从 5.4s 降到 ~1-2s。

- **不推荐**的原因：reobf 在方案 A 下只跑一次，为「只发生一次的 5s」去改上游 ART 调用并承担回归风险，ROI 极低；且 ART 多线程对签名/manifest 处理有已知的边界 case。**除非你坚持用 mojmap jar 且无法保留缓存（每次必跑）**，才值得考虑。

---

## 6. 如何验证你属于哪种场景（自查清单）

请在**你的生产服务器**上检查（不是本仓库的 run/）：

1. **缓存是否存在**：
   ```bash
   ls -la plugins/.paper-remapped/remap-classpath/
   ```
   - 有 `<hash>.jar` → 缓存正常，稳态启动不应再跑 reobf。若仍每次跑，看第 3 节场景 2/3（持久化/权限）。
   - 无该目录或无 jar → **这就是每次慢的根因**，走方案 A。

2. **抓一次完整启动日志确认 reobf 是否真的每次出现**：
   ```bash
   grep -E "Remapping server|Done remapping|Done \(" logs/latest.log
   ```
   - 每次都有 `Remapping server` → 缓存没保住 → 方案 A。
   - 只首次有 → reobf 非稳态瓶颈，启动慢另有他因（DataConverter / datapack / 插件加载），可再用 spark profiler 采样定位。

3. **当前 jar 类型确认**：
   ```bash
   unzip -p Papo-1.21.11-0.25.1.jar META-INF/MANIFEST.MF | grep -i "implementation-version"
   ```
   确认是 mojmap（本报告分析前提）。

---

## 7. 给本仓库的可执行改进建议（若要落地代码侧）

按风险从低到高，**均需独立分支 + 全量 applyPatches/compileJava/test 验证 + 版本号递增 + release note**：

| 优先级 | 改动 | 文件 | 风险 | 说明 |
|---|---|---|---|---|
| 低（可选） | `createPapoJar` 改用 reobf 产物 | [paper-server/build.gradle.kts](../../paper-server/build.gradle.kts) | 中（插件兼容需实测） | 方案 B 落地，消除运行时 reobf |
| 低 | 启动脚本/文档提示保留 `.paper-remapped` | 文档 | 零 | 方案 A 的配套说明 |
| 不建议 | `threads(1)` → 多线程 | [ReobfServer.java:72](../../paper-server/src/main/java/io/papermc/paper/pluginremap/ReobfServer.java#L72) | 中 | ROI 低，仅特定场景 |

> 注：ReobfServer / PluginRemapper / MappingEnvironment 均为**上游 Paper 代码**（非 Papo 补丁），改动它们等于改上游源码，会增大未来同步上游的成本，且**与 Papo「性能补丁」的定位（只优化热路径分配）不符**。故代码侧改动应谨慎，优先用「部署侧保留缓存」（方案 A）解决。

---

## 附录：与 Papo 性能优化定位的关系

Papo 的 202 个补丁聚焦**运行期 tick 热路径的分配/扫描消除**（见 [note/optimizations.md](../optimizations.md)），**不涉及启动期 remapping**。启动 remapping 是 Paper 上游为插件兼容设计的「一次性」机制，本报告分析的优化也属于**部署/分发层面**，无需新增 Papo 补丁。若用户确认是「每次都丢缓存」型问题，方案 A 即可零代码解决。
