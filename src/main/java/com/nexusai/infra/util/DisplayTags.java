package com.nexusai.infra.util;

import java.util.regex.Pattern;

/**
 * DisplayTags · 对齐 CC utils/displayTags.ts.
 *
 * <p>L1 语义: 3 个 pure function 剥除系统注入的 XML-like wrapper tags,用于 UI title 显示
 * (/rewind / /resume / bridge session titles)。
 * <ul>
 *   <li>{@link #stripDisplayTags} — 剥除所有 lowercase XML tag blocks;空 → 返原文</li>
 *   <li>{@link #stripDisplayTagsAllowEmpty} — 同上但允许返空 (纯 XML msg 检测)</li>
 *   <li>{@link #stripIdeContextTags} — 仅剥 ide_opened_file / ide_selection (UP-arrow resubmit)</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 3 静态 method + 2 private Pattern 常量</li>
 *   <li><b>A2 Golden Trace</b>: '<foo>bar</foo>hello' → 'hello';ide tag 上下文保留 lowercase HTML;特殊 tag 不匹配 (uppercase / startsWith !)</li>
 *   <li><b>A3 纯函数</b>: stateless;compile Pattern once</li>
 *   <li><b>A4 边界</b>: null/empty → 原样;空 → strip 后空 → stripDisplayTags 返原,stripDisplayTagsAllowEmpty 返 ''</li>
 *   <li><b>A5 业务场景</b>: rewind UI title 隐藏 IDE context + hook output;仅纯 XML msg 检测 command-only prompt</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS regex literal with backreference → Java Pattern.compile;
 * TS String.replace(regex, '') → Java replaceAll(String regex, "");
 * capture group backreference \\1。
 */
public final class DisplayTags {

    private static final Pattern XML_TAG_BLOCK = Pattern.compile(
        "<([a-z][\\w-]*)(?:\\s[^>]*)?>[\\s\\S]*?<\\/\\1>\\n?");
    private static final Pattern IDE_CONTEXT_TAGS = Pattern.compile(
        "<(ide_opened_file|ide_selection)(?:\\s[^>]*)?>[\\s\\S]*?<\\/\\1>\\n?");

    private DisplayTags() {}

    /** Strip XML-like tags; if empty → return original (better to show something). */
    public static String stripDisplayTags(String text) {
        if (text == null) return null;
        String result = text.replaceAll(XML_TAG_BLOCK.pattern(), "").trim();
        return result.isEmpty() ? text : result;
    }

    /** Strip XML-like tags; allow empty result. */
    public static String stripDisplayTagsAllowEmpty(String text) {
        if (text == null) return null;
        return text.replaceAll(XML_TAG_BLOCK.pattern(), "").trim();
    }

    /** Strip only IDE-injected context tags (ide_opened_file, ide_selection). */
    public static String stripIdeContextTags(String text) {
        if (text == null) return null;
        return text.replaceAll(IDE_CONTEXT_TAGS.pattern(), "").trim();
    }
}
