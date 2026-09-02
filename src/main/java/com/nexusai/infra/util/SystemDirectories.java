package com.nexusai.infra.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SystemDirectories · 对齐 CC utils/systemDirectories.ts.
 *
 * <p>L1 语义: 跨平台系统目录解析 — Windows / macOS / Linux / WSL 各自的 Desktop/Documents/Downloads 路径。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: getSystemDirectories(env, homedir, platform)→Map&lt;String,String&gt;;Platform 枚举 (windows/macos/linux/wsl/unknown)</li>
 *   <li><b>A2 Golden Trace</b>: macos→~/Desktop+~/Documents+~/Downloads;linux+env XDG_DESKTOP_DIR=/custom→/custom;windows+USERPROFILE→USERPROFILE/Desktop</li>
 *   <li><b>A3 纯函数</b>: 注入式 env + homedir + platform (testable)</li>
 *   <li><b>A4 边界</b>: null env→empty map;unknown platform→defaults (macos-like);null homedir→empty</li>
 *   <li><b>A5 业务场景</b>: Claude Code 桌面 launcher 写 ~/Desktop/claude.md;wsl→/mnt/c/Users/...</li>
 * </ul>
 *
 * <p>L3 升级: TS switch/case → Java switch expression;
 * TS platform env check → Java Supplier inject;
 * TS object literal → Java LinkedHashMap.
 */
public final class SystemDirectories {

    public enum Platform { windows, macos, linux, wsl, unknown }

    public record Options(
        Map<String, String> env,
        String homedir,
        Platform platform) {}

    private SystemDirectories() {}

    public static Map<String, String> getSystemDirectories(Options opts) {
        if (opts == null) return new LinkedHashMap<>();
        Map<String, String> env = opts.env() == null ? Map.of() : opts.env();
        String home = opts.homedir() == null ? "" : opts.homedir();
        Platform platform = opts.platform() == null ? Platform.unknown : opts.platform();

        // defaults
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("HOME", home);
        defaults.put("DESKTOP", home + "/Desktop");
        defaults.put("DOCUMENTS", home + "/Documents");
        defaults.put("DOWNLOADS", home + "/Downloads");

        return switch (platform) {
            case windows -> {
                String userProfile = env.getOrDefault("USERPROFILE", home);
                Map<String, String> r = new LinkedHashMap<>();
                r.put("HOME", home);
                r.put("DESKTOP", userProfile + "/Desktop");
                r.put("DOCUMENTS", userProfile + "/Documents");
                r.put("DOWNLOADS", userProfile + "/Downloads");
                yield r;
            }
            case linux, wsl -> {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("HOME", home);
                r.put("DESKTOP", env.getOrDefault("XDG_DESKTOP_DIR", defaults.get("DESKTOP")));
                r.put("DOCUMENTS", env.getOrDefault("XDG_DOCUMENTS_DIR", defaults.get("DOCUMENTS")));
                r.put("DOWNLOADS", env.getOrDefault("XDG_DOWNLOAD_DIR", defaults.get("DOWNLOADS")));
                yield r;
            }
            case macos, unknown -> defaults;
        };
    }
}
