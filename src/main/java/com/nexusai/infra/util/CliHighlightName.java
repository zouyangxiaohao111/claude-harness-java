package com.nexusai.infra.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * CliHighlightName · 对齐 CC utils/cliHighlight.ts.
 *
 * <p>L1 语义: file extension → 语言名 (用于 telemetry + permission dialog 渲染)。
 * 共享 cli-highlight 加载 promise + 缓存 highlight.js.getLanguage。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: getLanguageName(filePath, langRegistry)→String;无 extension → "unknown"</li>
 *   <li><b>A2 Golden Trace</b>: ".ts"→"TypeScript";".java"→"Java";".py"→"Python";空 ext→"unknown"</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output (langRegistry 注入式)</li>
 *   <li><b>A4 边界</b>: null filePath→"unknown";registry returns null→"unknown"</li>
 *   <li><b>A5 业务场景</b>: telemetry OTel counter 的 language attribute;permission dialog 高亮</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS highlight.js dynamic import → Java Supplier&lt;Map&gt; (caller wired);
 * TS lodash memoize → Java LinkedHashMap cache;
 * TS path.extname → Java substring(last '.') + 1。
 */
public final class CliHighlightName {

    public static final String UNKNOWN = "unknown";

    private CliHighlightName() {}

    /**
     * Resolve language name from a file path. Caller injects the language registry
     * (typically populated from highlight.js via cli-highlight).
     *
     * @param filePath     absolute or relative file path (".ts", ".py", etc.)
     * @param langRegistry extension-to-language record; null treated as empty
     * @return language name (e.g. "TypeScript") or {@link #UNKNOWN}
     */
    public static String getLanguageName(String filePath,
                                        Map<String, LanguageEntry> langRegistry) {
        if (filePath == null || filePath.isEmpty()) return UNKNOWN;
        int dot = filePath.lastIndexOf('.');
        if (dot == -1 || dot == filePath.length() - 1) return UNKNOWN;
        String ext = filePath.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (ext.isEmpty()) return UNKNOWN;
        if (langRegistry == null) return UNKNOWN;
        LanguageEntry entry = langRegistry.get(ext);
        return entry == null ? UNKNOWN : entry.name();
    }

    /** Sub-record representing an entry in highlight.js's language registry. */
    public record LanguageEntry(String name, Map<String, Object> meta) {}
}
