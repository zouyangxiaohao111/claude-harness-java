package com.nexusai.infra.util;

import java.util.function.Function;

/**
 * SystemThemeDetector · 对齐 CC utils/systemTheme.ts.
 *
 * <p>L1 语义: terminal dark/light mode 检测 via OSC 11 RGB 查询 (cached)。
 * <ul>
 *   <li>{@code parseOscRgb(data)} → Rgb (single rgb:RRRR/GGGG/BBBB or #RRGGBB)</li>
 *   <li>{@code themeFromOscColor(data)} → SystemTheme ('dark'|'light') via BT.709 luminance</li>
 *   <li>{@code getSystemThemeName()} / {@code setCachedSystemTheme(theme)}</li>
 *   <li>{@code resolveThemeSetting(setting)} → 'auto' resolves to system theme</li>
 * </ul>
 *
 * <p>2 静态方法 + SystemTheme enum + cached state (reset via setCached).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 5 静态方法 + SystemTheme enum + parseOscRgb 私有 helper</li>
 *   <li><b>A2 Golden Trace</b>: rgb:0/0/0→dark;rgb:ffff/ffff/ffff→light;#000000→dark;#FFFFFF→light;invalid format→undefined</li>
 *   <li><b>A3 副作用</b>: cached mutable state;parseOscRgb 纯函数</li>
 *   <li><b>A4 边界</b>: null/empty data→undefined;非 rgb 前缀→undefined;short hex→0;超出 4 位 hex→无效</li>
 *   <li><b>A5 业务场景</b>: 'auto' theme resolve to detected terminal bg;reactive update on OSC 11 response</li>
 * </ul>
 *
 * <p>L3 升级: TS regex.match → Java Pattern.matcher;
 * TS ColorFGBG env var → Java String.split;
 * TS hex parseInt → Java Integer.parseInt.
 */
public final class SystemThemeDetector {

    public enum SystemTheme { dark, light }

    public record Rgb(double r, double g, double b) {}

    private static volatile SystemTheme cachedSystemTheme = null;
    private static final Function<String, String> ENV_GETTER = System::getenv;

    private SystemThemeDetector() {}

    /** Get cached theme (CC: 'auto' resolver uses this without await). */
    public static SystemTheme getSystemThemeName() {
        if (cachedSystemTheme == null) {
            cachedSystemTheme = detectFromColorFgBg(ENV_GETTER.apply("COLORFGBG"));
            if (cachedSystemTheme == null) cachedSystemTheme = SystemTheme.dark;
        }
        return cachedSystemTheme;
    }

    /** Update the cached theme (CC: called by watcher on OSC 11 response). */
    public static void setCachedSystemTheme(SystemTheme theme) {
        cachedSystemTheme = theme;
    }

    /**
     * Parse OSC color response into RGB tuple.
     * Accepts: {@code rgb:RRRR/GGGG/BBBB} or {@code #RRGGBB} / {@code #RRRRGGGGBBBB}.
     */
    public static Rgb parseOscRgb(String data) {
        if (data == null) return null;
        // rgb:RRRR/GGGG/BBBB or rgba:...
        java.util.regex.Matcher m1 = java.util.regex.Pattern
            .compile("^rgba?:([0-9a-f]{1,4})/([0-9a-f]{1,4})/([0-9a-f]{1,4})",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(data);
        if (m1.find()) {
            return new Rgb(
                hexComponent(m1.group(1)),
                hexComponent(m1.group(2)),
                hexComponent(m1.group(3)));
        }
        // #RRGGBB or #RRRRGGGGBBBB
        java.util.regex.Matcher m2 = java.util.regex.Pattern
            .compile("^#([0-9a-f]+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(data);
        if (m2.find()) {
            String hex = m2.group(1);
            if (hex.length() % 3 != 0) return null;
            int n = hex.length() / 3;
            return new Rgb(
                hexComponent(hex.substring(0, n)),
                hexComponent(hex.substring(n, 2 * n)),
                hexComponent(hex.substring(2 * n)));
        }
        return null;
    }

    /** Convert OSC RGB to dark/light via BT.709 relative luminance. */
    public static SystemTheme themeFromOscColor(String data) {
        Rgb rgb = parseOscRgb(data);
        if (rgb == null) return null;
        // BT.709 luminance: weighted RGB; midpoint 0.5 split
        double luminance = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
        return luminance > 0.5 ? SystemTheme.light : SystemTheme.dark;
    }

    /** Resolve 'auto' theme setting to actual theme; otherwise pass through. */
    public static String resolveThemeSetting(String setting) {
        if ("auto".equals(setting)) return getSystemThemeName().name();
        return setting;
    }

    /** Convert 1–4 digit hex to [0, 1] float. */
    private static double hexComponent(String hex) {
        long max = (1L << (4 * hex.length())) - 1;
        return Long.parseLong(hex, 16) / (double) max;
    }

    /**
     * Read $COLORFGBG for synchronous initial guess.
     * Returns 'dark' for bg 0–6/8, 'light' for bg 7/9–15.
     */
    private static SystemTheme detectFromColorFgBg(String colorfgbg) {
        if (colorfgbg == null || colorfgbg.isEmpty()) return null;
        String[] parts = colorfgbg.split(";");
        String bgStr = parts[parts.length - 1];
        if (bgStr.isEmpty()) return null;
        try {
            int bg = Integer.parseInt(bgStr);
            if (bg < 0 || bg > 15) return null;
            return (bg <= 6 || bg == 8) ? SystemTheme.dark : SystemTheme.light;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
