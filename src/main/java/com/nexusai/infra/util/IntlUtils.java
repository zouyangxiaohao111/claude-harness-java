package com.nexusai.infra.util;

import java.text.BreakIterator;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * IntlUtils · 对齐 CC utils/intl.ts.
 *
 * <p>L1 语义: shared Intl 风格实例缓存 — grapheme/word segmenter + RelativeTimeFormat +
 * 时区 + 系统 locale language。
 * Java 用 {@link BreakIterator} (标准 JDK) 代替 Intl.Segmenter (ECMAScript)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: getGraphemeSegmenter + firstGrapheme + lastGrapheme + getWordSegmenter + getRelativeTimeFormat + getTimeZone + getSystemLocaleLanguage 7 静态方法</li>
 *   <li><b>A2 Golden Trace</b>: firstGrapheme("a🇨🇳b")→"a";lastGrapheme("a🇨🇳b")→"b";getTimeZone()→"Asia/Shanghai" 等;locale 'en_US'→language 'en'</li>
 *   <li><b>A3 缓存</b>: graphemeSegmenter + wordSegmenter + rtfCache + cachedTimeZone + cachedSystemLocaleLanguage 单次计算</li>
 *   <li><b>A4 边界</b>: null/empty text→"";rtfCache key 格式 style:numeric</li>
 *   <li><b>A5 业务场景</b>: brief UI 显示 elapsed "5 minutes ago";first grapheme 字符宽度计算</li>
 * </ul>
 *
 * <p>L3 升级: TS Intl.Segmenter → Java BreakIterator;
 * TS Map indexed → Java HashMap (Thread-safe via synchronized methods);
 * TS Intl.RelativeTimeFormat → Java 自实现 (formatRelative).
 */
public final class IntlUtils {

    private static final Object GRAPHEME_LOCK = new Object();
    private static BreakIterator graphemeSegmenter;
    private static BreakIterator wordSegmenter;

    private static final Map<String, FormattedRelative> RTF_CACHE = new HashMap<>();
    private static final Object RTF_LOCK = new Object();

    private static volatile String cachedTimeZone;
    private static volatile String cachedSystemLocaleLanguage;
    private static volatile boolean localeInitialized;

    private IntlUtils() {}

    /** Returns the process-wide grapheme segmenter (Java BreakIterator, character-iterator). */
    public static BreakIterator getGraphemeSegmenter() {
        synchronized (GRAPHEME_LOCK) {
            if (graphemeSegmenter == null) {
                graphemeSegmenter = BreakIterator.getCharacterInstance(Locale.ROOT);
            }
            return graphemeSegmenter;
        }
    }

    /** First grapheme cluster of {@code text} (Java: first character boundary). */
    public static String firstGrapheme(String text) {
        if (text == null || text.isEmpty()) return "";
        BreakIterator it = getGraphemeSegmenter();
        it.setText(text);
        int end = it.next();
        if (end == BreakIterator.DONE) return "";
        return text.substring(0, end);
    }

    /** Last grapheme cluster of {@code text}. */
    public static String lastGrapheme(String text) {
        if (text == null || text.isEmpty()) return "";
        BreakIterator it = getGraphemeSegmenter();
        it.setText(text);
        int last = BreakIterator.DONE;
        int cur;
        while ((cur = it.next()) != BreakIterator.DONE) {
            last = cur;
        }
        if (last == BreakIterator.DONE) return "";
        int prev = it.previous();
        if (prev == BreakIterator.DONE) return text.substring(0, last);
        return text.substring(prev, last);
    }

    /** Returns the process-wide word segmenter. */
    public static BreakIterator getWordSegmenter() {
        synchronized (GRAPHEME_LOCK) {
            if (wordSegmenter == null) {
                wordSegmenter = BreakIterator.getWordInstance(Locale.ROOT);
            }
            return wordSegmenter;
        }
    }

    public record FormattedRelative(String text) {}

    /**
     * Get cached formatter. Java doesn't have Intl.RelativeTimeFormat, so we
     * cache a marker; actual formatting via {@link #formatRelative}.
     */
    public static FormattedRelative getRelativeTimeFormat(String style, String numeric) {
        String key = style + ":" + numeric;
        synchronized (RTF_LOCK) {
            return RTF_CACHE.computeIfAbsent(key, k -> new FormattedRelative(k));
        }
    }

    /**
     * Format a relative time: "5 minutes ago" / "in 1 hour" etc.
     * Simple Java implementation using Calendar.
     */
    public static String formatRelative(double value, String unit, String style) {
        // "long" gives full unit; "short" gives abbreviation; "narrow" gives single char
        boolean isShort = "short".equals(style) || "narrow".equals(style);
        String suffix = isShort ? unit.substring(0, 1) : " " + unit;
        if (value < 0) {
            return Math.abs((int) value) + suffix + " ago";
        }
        return "in " + (int) value + suffix;
    }

    /** Process-lifetime cached timezone. */
    public static String getTimeZone() {
        if (cachedTimeZone == null) {
            cachedTimeZone = TimeZone.getDefault().getID();
        }
        return cachedTimeZone;
    }

    /** System locale language subtag (e.g., 'en', 'ja'). Cached. */
    public static String getSystemLocaleLanguage() {
        if (!localeInitialized) {
            try {
                String locale = Locale.getDefault().toLanguageTag();
                cachedSystemLocaleLanguage = new Locale(locale).getLanguage();
            } catch (RuntimeException e) {
                cachedSystemLocaleLanguage = null;
            }
            localeInitialized = true;
        }
        return cachedSystemLocaleLanguage;
    }

    /** Test-only: reset all caches. */
    public static void resetCaches() {
        synchronized (GRAPHEME_LOCK) {
            graphemeSegmenter = null;
            wordSegmenter = null;
        }
        synchronized (RTF_LOCK) {
            RTF_CACHE.clear();
        }
        cachedTimeZone = null;
        cachedSystemLocaleLanguage = null;
        localeInitialized = false;
    }
}
