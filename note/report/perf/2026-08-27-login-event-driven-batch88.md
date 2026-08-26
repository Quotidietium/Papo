# 批次 88 报告（2026-08-27）— 登录相位事件驱动完成（join 总 80→14ms，−82%）

主题：批次 87 剩余串行面的继续清算。相位分解显示稳态 join 剩余 35ms 中登录相位占
21ms——`ServerLoginPacketListenerImpl.tick()` 中 VERIFYING→LoginSuccess 的转移只在
tick 边界发生：auth 线程完成认证（offline：建档+AsyncPlayerPreLoginEvent）置 VERIFYING
后，LoginSuccess 要等下个 tickChildren（0-50ms 量化；bot 稳态回环与 20-tick 周期近似
锁相，实测中位 21ms 低估了随机相位客户端的期望等待 ~25ms）。

## 机制（0251）

`startClientVerification`（auth 线程）置 VERIFYING 后，立即经 `server.execute`（主线程
任务队列，包处理器本就运行的 tick 间窗口）推进 `verifyLoginAndFinishConnectionSetup`，
守卫与常规 tick() 路径逐字相同（disconnecting / awaiting cookies / state==VERIFYING）；
单次执行由主线程串行化 + 状态转移在守卫块内共同保证。停机尾部 executor 拒绝时常规
tick 路径兜底。WAITING_FOR_DUPE_DISCONNECT（重复登出等待，罕见）维持 tick 轮询。

## 结果

| 指标 | 0.55.0（87 前） | 0.56.0（87 后） | **0.57.0-b88（88 后）** |
|---|---|---|---|
| 登录相位 gap | 21ms | 21ms | **4ms** |
| 稳态重连 join（相位基准） | 80ms | 32-35ms | **14/15ms（−82%）** |
| 四态冒烟稳态 mean | 83.5ms | 37.4ms | **19.0ms（vs 0.54.0 基线 −76%）** |
| 20-bot burst（相位基准 lastSpawn） | — | ~690ms | **568ms** |
| fat-dat 20-bot burst（vs 0.54.0） | 持平 | 持平偏好 | **偏好（best 3348 vs 3478，p50 −132ms）** |

门：全量 test ✓ / 四态冒烟 10/10 零异常 ✓ / 相位基准 ×2 零错误 ✓ / fat-dat burst
6 轮 120 bot 零门错误 ✓。

## join 管线两轮清算总账（批次 87+88）

稳态 join 80ms → 14ms：登录 21→4（事件驱动）、配置管道 4（本就快）、prepare_spawn
量化 46→0（事件驱动+CAS）、placeNewPlayer 9→8。剩余 14ms 为真实工作（auth 线程跳转、
注册表包发送、实体放置），无 tick 量化成分。**join 延迟的 tick 量化面全部清零。**

## 判例

- 登录/配置两阶段的"状态机只在 tick 推进"是 join 延迟的结构性来源；事件驱动 + 主线程
  串行化 + 状态转移守卫 = 统一安全配方（87/88 两处同构实现，一处补 CAS 因重入、一处
  无共享竞态源天然单发）。
- 验证矩阵的 bot 回环可能与 tick 周期锁相，量化类等待的测量需注意相位偏差。
