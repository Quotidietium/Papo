# 批次 81 报告（2026-08-26）— 多核调度系列集成冒烟验证（批次78-80 真实服务器实证）

主题：**稳定性验证轮**（goal："多核调度之后的服务器核心稳定性"）。批次78（池 sizing）、
79（玩家存档下放）、80（worker 曲线 + level.dat 下放）改动面覆盖启动/存档/关服三条管线，
此前仅有单元级验证。本批用发布 jar 真实启动专用服（fresh install，eula+offline mode，
固定 seed，view/sim distance 8，100s 后 `stop`），0.54.0 与 0.51.0（批次78 前基线）各一轮，
同机同参对拍。

## 1. sizing 生效实证（启动日志）

| 指标 | 0.51.0（旧 auto） | 0.54.0（新 auto） |
|---|---|---|
| Netty 事件循环 | 4（平默认） | **8**（cores/4，spigot.yml 物化 `netty-threads: 8`） |
| Worker 线程 | 8（cores/4） | **12**（cores/2 clamp[2,12]） |
| Region IO 线程 | 1（平 auto） | **4**（cores/8 clamp[1,4]） |

三批 sizing 决策在真实专用服全部按公式生效；fresh install 的 spigot.yml 物化路径验证通过。

## 2. 关键管线行为

- **启动/worldgen**：0.54.0 Done (15.410s) vs 0.51.0 Done (15.841s)——同量级（spawn 区
  worldgen 规模小，12 vs 8 worker 差异在此不放大；两轮均含 JVM 预热噪声，不作为性能依据）。
- **关服序列**（0.54.0 全程逐行核验）：`stop` → Saving players → 3 世界 Saving chunks →
  ChunkHolderManager 全部保存+halt → "All dimensions are saved"（此处含批次80 的
  `awaitAll` level.dat 等待）→ RegionFile I/O flush → worker/IO 池 60s 排水 → **exit 0**。
- **落盘产物**：`world/level.dat` 1523B 合法 gzip+NBT（magic `0a00…`，批次80 异步写路径
  产出）；region 文件正常生成（12 worker 的 worldgen 产出）；`playerdata/` 空目录正常。
- **错误扫描**：两轮 boot.log 全文 `ERROR|Exception` 计数 **0/0**。

## 3. 结论

批次78-80 的多核调度改动在真实服务器全生命周期（boot→worldgen→自动存档周期→干净关服）
零异常、行为符合设计；关服契约（全量保存等待+池排水）逐行符合。多核调度系列的池预算面
（netty/worker/regionIO/player-file/level.dat）至此**集成验证封闭**。

## 留档（本批否定/边界）

- 杂项池 sizing（Util.DIMENSION_DATA_IO_POOL 固定4 / BACKGROUND_EXECUTOR cap8 可 -D 覆盖 /
  ioPool 运行时仅冷路径）——运行时负载不足以成批，留档。
- 实体 unload 存档异步化（批次80 已否决）重申不实施。
- boot 时间对比仅作同量级 sanity，非性能结论（样本1、噪声大）。

## 验证链

0.54.0 boot exit0 + 零异常 + sizing 三值生效 + level.dat 校验 + 关服序列逐行 → 0.51.0
对照 boot exit0 + 旧 sizing 三值确认 + 零异常。
