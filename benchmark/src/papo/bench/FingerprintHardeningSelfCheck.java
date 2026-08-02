package papo.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批次51 / fingerprint-hardening：行为自检（非 JMH 性能基准——本轮是安全加固，无前后性能差异）。
 *
 * 忠实复刻 GlobalConfiguration.FingerprintHardening 中三个开关的 resolve / shouldBroadcast 逻辑，
 * 逐一验证全分支（含非法 mode 与空 customValue 的回退），确保加固开关的行为正确、且默认值
 * （REAL / ALL）与现状完全一致（兼容性红线）。
 *
 * 对应源码：
 *   brand-payload:    GlobalConfiguration.FingerprintHardening.BrandPayload.resolve(String)
 *   status:           GlobalConfiguration.FingerprintHardening.Status.resolve(String,String)
 *   plugin-channels:  GlobalConfiguration.FingerprintHardening.PluginChannels.shouldBroadcast(String)
 */
public final class FingerprintHardeningSelfCheck {

    private FingerprintHardeningSelfCheck() {}

    // ---- 复刻 resolve / shouldBroadcast 逻辑（与 GlobalConfiguration 逐字一致）----

    static String resolveBrand(final String mode, final String customValue, final String realBrand) {
        if ("VANILLA".equals(mode)) return "vanilla";
        if ("CUSTOM".equals(mode)) return (customValue != null && !customValue.isEmpty()) ? customValue : realBrand;
        return realBrand;
    }

    static String resolveStatus(final String versionString, final String customValue, final String realVersionName, final String mcVersion) {
        if ("VANILLA".equals(versionString)) return mcVersion;
        if ("CUSTOM".equals(versionString)) return (customValue != null && !customValue.isEmpty()) ? customValue : realVersionName;
        return realVersionName;
    }

    static boolean shouldBroadcast(final String broadcastMode, final List<String> allowedChannels, final String channel) {
        if ("NONE".equals(broadcastMode)) return false;
        if ("WHITELIST".equals(broadcastMode)) return allowedChannels.contains(channel);
        return true; // ALL (default) or unknown
    }

    // 复刻 GlobalConfiguration.FingerprintHardening.Commands.papoResolveDefault（返回串而非枚举，便于独立自检）
    static String resolveCommandDefault(final String playerVisibleDefaults) {
        if ("op".equalsIgnoreCase(playerVisibleDefaults)) return "OP";
        if ("false".equalsIgnoreCase(playerVisibleDefaults)) return "FALSE";
        return "TRUE"; // true (default) / unknown
    }

    private static int checks = 0;
    private static void check(final boolean cond, final String msg) {
        checks++;
        if (!cond) { System.out.println("FAIL: " + msg); System.exit(1); }
    }

    public static void main(final String[] args) {
        final String realBrand = "Papo";
        final String mcVersion = "1.21.11";
        final String realVersionName = "Papo 1.21.11";

        // === brand-payload ===
        // 默认 REAL（兼容性：与现状一致）
        check(resolveBrand("REAL", "", realBrand).equals("Papo"), "brand REAL");
        check(resolveBrand("REAL", "ignored", realBrand).equals("Papo"), "brand REAL ignores customValue");
        // VANILLA
        check(resolveBrand("VANILLA", "", realBrand).equals("vanilla"), "brand VANILLA");
        // CUSTOM
        check(resolveBrand("CUSTOM", "MyServer", realBrand).equals("MyServer"), "brand CUSTOM");
        check(resolveBrand("CUSTOM", "", realBrand).equals("Papo"), "brand CUSTOM empty -> REAL fallback");
        check(resolveBrand("CUSTOM", null, realBrand).equals("Papo"), "brand CUSTOM null -> REAL fallback");
        // 非法 mode 回退 REAL
        check(resolveBrand("GARBAGE", "", realBrand).equals("Papo"), "brand unknown mode -> REAL");

        // === status.version-string ===
        check(resolveStatus("REAL", "", realVersionName, mcVersion).equals("Papo 1.21.11"), "status REAL");
        check(resolveStatus("VANILLA", "", realVersionName, mcVersion).equals("1.21.11"), "status VANILLA -> mc version only");
        check(resolveStatus("CUSTOM", "Secret 2.0", realVersionName, mcVersion).equals("Secret 2.0"), "status CUSTOM");
        check(resolveStatus("CUSTOM", "", realVersionName, mcVersion).equals("Papo 1.21.11"), "status CUSTOM empty -> REAL");
        check(resolveStatus("GARBAGE", "", realVersionName, mcVersion).equals("Papo 1.21.11"), "status unknown -> REAL");

        // === plugin-channels ===
        final List<String> allowed = new ArrayList<>(Arrays.asList("bungeecord:main", "myplugin:safe"));
        // 默认 ALL（兼容性：广播全部）
        check(shouldBroadcast("ALL", allowed, "anything:channel"), "channels ALL broadcasts any");
        check(shouldBroadcast("ALL", allowed, "secretplugin:hidden"), "channels ALL broadcasts hidden too");
        // WHITELIST
        check(shouldBroadcast("WHITELIST", allowed, "bungeecord:main"), "channels WHITELIST allows listed");
        check(!shouldBroadcast("WHITELIST", allowed, "secretplugin:hidden"), "channels WHITELIST blocks unlisted");
        // NONE
        check(!shouldBroadcast("NONE", allowed, "bungeecord:main"), "channels NONE blocks even listed");
        // 非法 mode 回退 ALL
        check(shouldBroadcast("GARBAGE", allowed, "x:y"), "channels unknown mode -> ALL");

        // === commands.playerVisibleDefaults（V5 命令权限默认）===
        // 默认 true=TRUE（兼容性：人人可用 /plugins，现状）
        check(resolveCommandDefault("true").equals("TRUE"), "cmd true -> TRUE");
        check(resolveCommandDefault("op").equals("OP"), "cmd op -> OP（仅 OP 可见插件清单）");
        check(resolveCommandDefault("false").equals("FALSE"), "cmd false -> FALSE");
        check(resolveCommandDefault("OP").equals("OP"), "cmd case-insensitive OP");
        check(resolveCommandDefault("False").equals("FALSE"), "cmd case-insensitive FALSE");
        check(resolveCommandDefault("").equals("TRUE"), "cmd empty -> TRUE fallback");
        check(resolveCommandDefault("garbage").equals("TRUE"), "cmd unknown -> TRUE fallback");

        System.out.println("ALL OK (" + checks + " checks)");
    }
}
