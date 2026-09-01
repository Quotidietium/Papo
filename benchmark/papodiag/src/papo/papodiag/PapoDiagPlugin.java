package papo.papodiag;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 批次117：PapoDiag 零配置停摆诊断插件——实例数据收集摩擦清零。
 *
 * 用法：把 PapoDiag.jar 丢进 plugins/ 重启，正常游玩；出现卡顿时插件自动把
 * 证据追加到 plugins/PapoDiag/stall-report.txt——把这个文件发给开发者即可。
 * 无需任何启动参数、命令或手动提取。
 *
 * 机制：主线程每 tick 更新心跳时间戳；看门狗守护线程每 25ms 检查心跳年龄，
 * ≥150ms（远超 50ms 名义 tick）判定停摆进行中，立即抓取主线程当前栈
 * （Thread.getAllStackTraces——正是批次114 定位首 join 冻结所用证据形态）、
 * 在线玩家、已启用插件、滚动 tick 时长历史，追加写入报告文件。5 秒去抖防止
 * 同一停摆风暴重复刷屏；文件按行追加，永不覆盖历史。
 */
public final class PapoDiagPlugin extends JavaPlugin {

    private volatile long lastHeartbeatNanos;
    private volatile long[] tickHistoryMicros = new long[20];
    private volatile int tickHistoryIndex;
    private volatile long lastReportMs;
    private java.io.File reportFile;
    private volatile Thread watchdogThread;

    @Override
    public void onEnable() {
        final java.io.File dir = new java.io.File(getDataFolder().getAbsolutePath());
        // dataFolder 已由 Paper 创建；报告文件在其下
        dir.mkdirs();
        this.reportFile = new java.io.File(dir, "stall-report.txt");
        this.lastHeartbeatNanos = System.nanoTime();

        // 心跳：主线程每 tick 一拍 + 滚动时长历史
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            final long now = System.nanoTime();
            final long gap = now - this.lastHeartbeatNanos;
            this.lastHeartbeatNanos = now;
            final long[] h = this.tickHistoryMicros;
            h[this.tickHistoryIndex % h.length] = gap / 1000;
            this.tickHistoryIndex++;
        }, 1L, 1L);

        // 看门狗：停摆进行中抓主线程栈（停摆中主线程正卡在哪一目了然）
        final Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(25);
                    final long ageNanos = System.nanoTime() - this.lastHeartbeatNanos;
                    if (ageNanos >= 150_000_000L) {
                        final long now = System.currentTimeMillis();
                        if (now - this.lastReportMs < 5_000L) {
                            continue; // 去抖：同一停摆风暴只记一次
                        }
                        this.lastReportMs = now;
                        report(ageNanos, now);
                    }
                } catch (final InterruptedException e) {
                    return;
                } catch (final Throwable t) {
                    // 诊断插件绝不能影响服务器：报告失败静默
                }
            }
        }, "PapoDiag-Watchdog");
        watchdog.setDaemon(true);
        this.watchdogThread = watchdog;
        watchdog.start();
        this.getLogger().info("PapoDiag active: stalls >=150ms will be captured to "
            + this.reportFile.getAbsolutePath());
    }

    /**
     * 批次119审计修复：禁用/关服时停掉看门狗。否则插件禁用后心跳停止，
     * 看门狗会把"停摆"报告每 5 秒追加进报告文件直到 JVM 退出（~17MB/天），
     * 且 /reload 或插件管理器卸载后线程永存。interrupt 使 sleep 立即退出；
     * 重启用新线程，旧线程不会再被唤醒（每线程各自响应自己的 interrupt）。
     */
    @Override
    public void onDisable() {
        final Thread t = this.watchdogThread;
        this.watchdogThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void report(final long ageNanos, final long nowMs) {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append('[').append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date(nowMs)))
            .append("] STALL ageMs=").append(ageNanos / 1_000_000)
            .append(" online=").append(Bukkit.getOnlinePlayers().size()).append('\n');
        // 主线程栈（停摆现场）
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) {
                sb.append("  main-thread state=").append(t.getState()).append('\n');
                final StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    sb.append("    at ").append(st[i]).append('\n');
                }
                break;
            }
        }
        // 滚动 tick 时长（µs，旧→新）
        sb.append("  recentTicksMicros=");
        final long[] h = this.tickHistoryMicros;
        final int idx = this.tickHistoryIndex;
        for (int i = 0; i < h.length; i++) {
            sb.append(h[(idx + i) % h.length]).append(i == h.length - 1 ? "" : ",");
        }
        sb.append('\n');
        // 插件清单（定位嫌疑插件）
        sb.append("  plugins=");
        for (final org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
            sb.append(p.getName()).append(p.isEnabled() ? "" : "(off)").append(' ');
        }
        sb.append('\n');
        // 在线玩家
        sb.append("  players=");
        for (final org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            sb.append(p.getName()).append(' ');
        }
        sb.append('\n');
        synchronized (this) {
            try {
                final java.io.PrintWriter out = new java.io.PrintWriter(new java.io.FileWriter(this.reportFile, true));
                out.print(sb);
                out.close();
            } catch (final java.io.IOException ignored) {
            }
        }
    }
}
