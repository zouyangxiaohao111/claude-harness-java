package com.nexusai.infra.util;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * BriefTimestampFormatter · 对齐 CC utils/formatBriefTimestamp.ts.
 *
 * <p>L1 语义: brief/chat 消息标签的 ISO 时间戳格式化 (按年龄分档):
 * <ul>
 *   <li>当天 → "HH:MM"</li>
 *   <li>1-6 天前 → "Weekday HH:MM"</li>
 *   <li>≥7 天前 → "Weekday Mon DD, HH:MM"</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #format(String, long, String)} (isoString, nowMs, locale) → String;NaN 输入 → ""</li>
 *   <li><b>A2 Golden Trace</b>: 0 day → "13:30" (HH:MM);1-6 → "Sunday 4:15";7+ → "Sunday Feb 20 4:30"</li>
 *   <li><b>A3 纯函数</b>: 无副作用;同 input → 同 output</li>
 *   <li><b>A4 边界</b>: invalid isoString → "";locale null → 系统默认</li>
 *   <li><b>A5 业务场景</b>: brief 页面显示 "13:30" (今天) vs "Sunday 4:15" (本周) vs "Sunday Feb 20 4:30" (历史)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Date.toLocaleString(locale) → Java DateTimeFormatter.ofPattern;
 * CC locale 解析 (POSIX → BCP47) → Java Locale.forLanguageTag (简化);
 * 注入式 Instant/Clock (caller 控制 now)。
 */
public final class BriefTimestampFormatter {

    private static final long MS_PER_DAY = 86_400_000L;

    private BriefTimestampFormatter() {}

    /**
     * Format an ISO timestamp for display.
     *
     * @param isoString ISO-8601 timestamp string; invalid → ""
     * @param nowMs     reference time in ms (test injectable)
     * @param localeTag BCP 47 tag (e.g., "en-US") or null for system default
     */
    public static String format(String isoString, long nowMs, String localeTag) {
        if (isoString == null || isoString.isEmpty()) return "";
        java.time.Instant instant;
        try {
            instant = java.time.Instant.parse(isoString);
        } catch (java.time.format.DateTimeParseException e) {
            return "";
        }
        long ts = instant.toEpochMilli();
        java.time.ZonedDateTime d = instant.atZone(java.time.ZoneId.systemDefault());
        long startNow = startOfDay(nowMs);
        long startThen = startOfDay(ts);
        long daysAgo = Math.round((startNow - startThen) / (double) MS_PER_DAY);
        java.util.Locale locale = localeTag == null
            ? java.util.Locale.getDefault()
            : java.util.Locale.forLanguageTag(localeTag);
        if (daysAgo == 0) {
            return d.format(java.time.format.DateTimeFormatter
                .ofPattern("HH:mm").withLocale(locale));
        }
        if (daysAgo > 0 && daysAgo < 7) {
            return d.format(java.time.format.DateTimeFormatter
                .ofPattern("EEEE HH:mm").withLocale(locale));
        }
        return d.format(java.time.format.DateTimeFormatter
            .ofPattern("EEEE MMM d HH:mm").withLocale(locale));
    }

    private static long startOfDay(long ms) {
        java.time.ZonedDateTime z = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault());
        return z.toLocalDate().atStartOfDay(z.getZone()).toInstant().toEpochMilli();
    }
}
