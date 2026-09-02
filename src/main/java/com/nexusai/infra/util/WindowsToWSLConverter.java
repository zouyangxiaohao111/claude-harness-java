package com.nexusai.infra.util;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WindowsToWSLConverter · 对齐 CC utils/idePathConversion.ts.
 *
 * <p>L1 语义: Windows IDE ↔ WSL Claude 路径转换。
 * 实际 wslpath 调用由 caller wired(本类用 BiFunction 注入 wslpath 结果);
 * 失败 fallback 到手动转换。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: toLocalPath + toIDEPath 2 method + wslDistroName 字段 + wslpathInvoker 注入式</li>
 *   <li><b>A2 Golden Trace</b>: C:\Users\foo → /mnt/c/Users/foo (toLocalPath);/mnt/c/Users/foo → C:\Users\foo (toIDEPath);\wsl\Ubuntu\path → /path in different distro→原样</li>
 *   <li><b>A3 纯函数</b>: wslpathInvoker 注入式可测;无副作用</li>
 *   <li><b>A4 边界</b>: null path→null;空→空;不同 distro→原样;wslpath 失败→手动 fallback</li>
 *   <li><b>A5 业务场景</b>: Windows VSCode 显示 WSL Claude 的 diff: \wsl$ (Ubuntu dist) (home)(user)(file.java) → /home/user/file.java</li>
 * </ul>
 *
 * <p>L3 升级: TS child_process.execFileSync('wslpath') → Java BiFunction 注入式 (testable);
 * TS regex match → Java Pattern.
 */
public final class WindowsToWSLConverter {

    private static final Pattern WSL_UNC = Pattern.compile(
        "^\\\\\\\\wsl(?:\\.localhost|\\$)\\\\([^\\\\]+)(.*)$");

    private final String wslDistroName;
    private final BiFunction<String[], String, String> wslpathInvoker;

    public WindowsToWSLConverter(String wslDistroName) {
        this(wslDistroName, null);
    }

    public WindowsToWSLConverter(String wslDistroName, BiFunction<String[], String, String> wslpathInvoker) {
        this.wslDistroName = wslDistroName;
        this.wslpathInvoker = wslpathInvoker;
    }

    /** Convert Windows path → WSL path. Falls back to manual conversion on failure. */
    public String toLocalPath(String windowsPath) {
        if (windowsPath == null || windowsPath.isEmpty()) return windowsPath;
        if (wslDistroName != null) {
            Matcher m = WSL_UNC.matcher(windowsPath);
            if (m.find() && !m.group(1).equals(wslDistroName)) {
                return windowsPath; // different distro, return original (with backslashes)
            }
        }
        if (wslpathInvoker != null) {
            try {
                return wslpathInvoker.apply(new String[]{"-u", windowsPath}, "windowsPath").trim();
            } catch (RuntimeException ignored) {
                // fall through to manual
            }
        }
        // Manual fallback: C:\foo → /mnt/c/foo
        String converted = windowsPath.replace("\\", "/");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Z]):").matcher(converted);
        if (m.find()) {
            String letter = m.group(1).toLowerCase();
            converted = "/mnt/" + letter + converted.substring(m.end());
        }
        return converted;
    }

    /** Convert WSL path → Windows path. */
    public String toIDEPath(String wslPath) {
        if (wslPath == null || wslPath.isEmpty()) return wslPath;
        if (wslpathInvoker != null) {
            try {
                return wslpathInvoker.apply(new String[]{"-w", wslPath}, "wslPath").trim();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return wslPath;
    }

    public static boolean checkWSLDistroMatch(String windowsPath, String wslDistroName) {
        Matcher m = WSL_UNC.matcher(windowsPath);
        if (m.find()) {
            return m.group(1).equals(wslDistroName);
        }
        return true; // not WSL UNC, no mismatch
    }
}
