package com.nexusai.application.agent.tool;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edit 匹配算法引擎 · 对齐 CC {@code Open-ClaudeCode/src/tools/FileEditTool/utils.ts}
 * （纯函数, 无 Spring 依赖）。
 *
 * <p>承载 CC 完整匹配算法, 替代 EditFileTool 旧裸 {@code indexOf} 匹配路径 (E-D5):
 * <ul>
 *   <li>{@link #normalizeQuotes} — 弯引号→直引号 1:1 归一化 (utils.ts:31-37)</li>
 *   <li>{@link #stripTrailingWhitespace} — 去每行尾随空白但保留行尾 CRLF/LF/CR (utils.ts:44-63)</li>
 *   <li>{@link #findActualString} — 精确 includes → 归一化双侧 indexOf → 取文件真实子串 (utils.ts:73-93)</li>
 *   <li>{@link #preserveQuoteStyle} — old==actual 直接返回; 弯引号开合启发应用到 new (utils.ts:104-141)</li>
 *   <li>{@link #applyEditToFile} — 空 new_string 尾换行特判防残留空行 (utils.ts:206-228)</li>
 *   <li>{@link #desanitizeMatchString} — DESANITIZATIONS 18 项逐项 replaceAll (utils.ts:531-574)</li>
 *   <li>{@link #normalizeEdit} — stripTrailingWhitespace + desanitize 兜底 (.md/.mdx 跳过) (utils.ts:584-647)</li>
 *   <li>{@link #getPatchForEdits} — 子串守卫 + 空文件特例 + 未变更抛错 (utils.ts:262-353)</li>
 *   <li>{@link #areFileEditsEquivalent} — 字面相同快路径 + 应用后比较 (utils.ts:663-730)</li>
 * </ul>
 */
public final class EditMatchEngine {

    private EditMatchEngine() {
    }

    // CC original: LEFT_SINGLE_CURLY_QUOTE (utils.ts:21)
    private static final char LEFT_SINGLE_CURLY = '‘';
    // CC original: RIGHT_SINGLE_CURLY_QUOTE (utils.ts:22)
    private static final char RIGHT_SINGLE_CURLY = '’';
    // CC original: LEFT_DOUBLE_CURLY_QUOTE (utils.ts:23)
    private static final char LEFT_DOUBLE_CURLY = '“';
    // CC original: RIGHT_DOUBLE_CURLY_QUOTE (utils.ts:24)
    private static final char RIGHT_DOUBLE_CURLY = '”';

    // CC original: DESANITIZATIONS (utils.ts:531-550) — 实测 18 项
    private static final List<Map.Entry<String, String>> DESANITIZATIONS = List.of(
        entry("<fnr>", "<function_results>"),
        entry("<n>", "<name>"),
        entry("</n>", "</name>"),
        entry("<o>", "<output>"),
        entry("</o>", "</output>"),
        entry("<e>", "<error>"),
        entry("</e>", "</error>"),
        entry("<s>", "<system>"),
        entry("</s>", "</system>"),
        entry("<r>", "<result>"),
        entry("</r>", "</result>"),
        entry("< META_START >", "<META_START>"),
        entry("< META_END >", "<META_END>"),
        entry("< EOT >", "<EOT>"),
        entry("< META >", "<META>"),
        entry("< SOS >", "<SOS>"),
        entry("\n\nH:", "\n\nHuman:"),
        entry("\n\nA:", "\n\nAssistant:")
    );

    private static Map.Entry<String, String> entry(String from, String to) {
        return new AbstractMap.SimpleImmutableEntry<>(from, to);
    }

    /**
     * 弯引号归一化为直引号 · 对齐 CC {@code normalizeQuotes} (utils.ts:31-37).
     *
     * @param str 待归一化字符串
     * @return 四个弯引号全部替换为直引号的字符串; 替换 1:1, 长度不变
     */
    public static String normalizeQuotes(String str) {
        return str
            .replace(String.valueOf(LEFT_SINGLE_CURLY), "'")
            .replace(String.valueOf(RIGHT_SINGLE_CURLY), "'")
            .replace(String.valueOf(LEFT_DOUBLE_CURLY), "\"")
            .replace(String.valueOf(RIGHT_DOUBLE_CURLY), "\"");
    }

    /**
     * 去每行尾随空白但保留行尾符 · 对齐 CC {@code stripTrailingWhitespace} (utils.ts:44-63).
     * 行内容去 {@code \s+$}, 行尾 CRLF/LF/CR 原样保留。
     *
     * <p>注意: 不能用 {@code str.split("(\\r\\n|\\n|\\r)", -1)} 复刻 JS
     * {@code split(/(\r\n|\n|\r)/)} —— Java split 不把捕获组元素放进结果数组
     * (与 JS 不同), 会丢掉行尾符。必须手工扫描逐行处理。
     */
    public static String stripTrailingWhitespace(String str) {
        StringBuilder result = new StringBuilder(str.length());
        int start = 0;
        int i = 0;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == '\n' || c == '\r') {
                // 行内容部分 (到当前行尾符之前) 去尾随空白
                result.append(str.substring(start, i).replaceFirst("\\s+$", ""));
                if (c == '\r' && i + 1 < str.length() && str.charAt(i + 1) == '\n') {
                    result.append("\r\n");
                    i += 2;
                } else {
                    result.append(c);
                    i++;
                }
                start = i;
            } else {
                i++;
            }
        }
        // 最后一段 (最后一个行尾符之后) 也按行内容处理
        result.append(str.substring(start).replaceFirst("\\s+$", ""));
        return result.toString();
    }

    /**
     * 在文件内容中定位与搜索串匹配的真实子串 · 对齐 CC {@code findActualString} (utils.ts:73-93).
     * 先精确 {@code contains}; 失败后双侧 quote 归一化再 {@code indexOf},
     * 命中则返回文件中弯引号保真的真实子串。
     *
     * @return 文件中的真实子串; 未命中返回 {@code null}
     */
    public static String findActualString(String fileContent, String searchString) {
        if (fileContent.contains(searchString)) {
            return searchString;
        }
        String normalizedSearch = normalizeQuotes(searchString);
        String normalizedFile = normalizeQuotes(fileContent);
        int searchIndex = normalizedFile.indexOf(normalizedSearch);
        if (searchIndex != -1) {
            // 归一化是 1:1, 长度不变, 用原始 searchString 长度切文件真实子串
            return fileContent.substring(searchIndex, searchIndex + searchString.length());
        }
        return null;
    }

    /**
     * 弯引号保真 · 对齐 CC {@code preserveQuoteStyle} (utils.ts:104-141).
     * old==actual 说明无归一化发生, 直接返回 new; 否则按文件实际含有的弯引号类型,
     * 用开合启发把 new 中的直引号转换成弯引号 (含单引号收缩格守卫)。
     */
    public static String preserveQuoteStyle(String oldString, String actualOldString, String newString) {
        if (oldString.equals(actualOldString)) {
            return newString;
        }
        boolean hasDoubleQuotes = actualOldString.indexOf(LEFT_DOUBLE_CURLY) != -1
            || actualOldString.indexOf(RIGHT_DOUBLE_CURLY) != -1;
        boolean hasSingleQuotes = actualOldString.indexOf(LEFT_SINGLE_CURLY) != -1
            || actualOldString.indexOf(RIGHT_SINGLE_CURLY) != -1;
        if (!hasDoubleQuotes && !hasSingleQuotes) {
            return newString;
        }
        String result = newString;
        if (hasDoubleQuotes) {
            result = applyCurlyDoubleQuotes(result);
        }
        if (hasSingleQuotes) {
            result = applyCurlySingleQuotes(result);
        }
        return result;
    }

    /**
     * 开引号上下文启发 · 对齐 CC {@code isOpeningContext} (utils.ts:143-158).
     * 字符串开头或前字符为空白/开括号/破折号 → 判定为开引号。
     */
    private static boolean isOpeningContext(int[] chars, int index) {
        if (index == 0) {
            return true;
        }
        int prev = chars[index - 1];
        return prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r'
            || prev == '(' || prev == '[' || prev == '{'
            || prev == '—' || prev == '–'; // em dash / en dash
    }

    /** 弯双引号应用 · 对齐 CC {@code applyCurlyDoubleQuotes} (utils.ts:160-169). */
    private static String applyCurlyDoubleQuotes(String str) {
        int[] chars = str.codePoints().toArray();
        StringBuilder result = new StringBuilder(str.length());
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '"') {
                result.appendCodePoint(isOpeningContext(chars, i) ? LEFT_DOUBLE_CURLY : RIGHT_DOUBLE_CURLY);
            } else {
                result.appendCodePoint(chars[i]);
            }
        }
        return result.toString();
    }

    /** 弯单引号应用 · 对齐 CC {@code applyCurlySingleQuotes} (utils.ts:171-194). */
    private static String applyCurlySingleQuotes(String str) {
        int[] chars = str.codePoints().toArray();
        StringBuilder result = new StringBuilder(str.length());
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\'') {
                // 收缩格撇号 (don't / it's): 前后都是字母 → 右弯引号, 不按开合启发
                boolean prevIsLetter = i > 0 && Character.isLetter(chars[i - 1]);
                boolean nextIsLetter = i < chars.length - 1 && Character.isLetter(chars[i + 1]);
                if (prevIsLetter && nextIsLetter) {
                    result.append(RIGHT_SINGLE_CURLY);
                } else {
                    result.appendCodePoint(isOpeningContext(chars, i) ? LEFT_SINGLE_CURLY : RIGHT_SINGLE_CURLY);
                }
            } else {
                result.appendCodePoint(chars[i]);
            }
        }
        return result.toString();
    }

    /**
     * 应用单条编辑 · 对齐 CC {@code applyEditToFile} (utils.ts:206-228).
     *
     * <p>关键不变量: CC 用 {@code String.prototype.replace} (仅首个) / {@code replaceAll},
     * 且替换值走函数式 replacer 不解释 {@code $} 引用。Java {@link String#replace} 对全部
     * 字面出现生效, 故非 replaceAll 分支必须用 {@link #replaceFirstLiteral} 只替换首个。
     *
     * <p>空 {@code newString} 尾换行特判: 当 {@code oldString} 不以 \n 结尾且文件中含
     * {@code oldString + '\n'} 时, 把该行连同换行一起删除, 防止残留空行 (utils.ts:211-227)。
     */
    public static String applyEditToFile(String originalContent, String oldString, String newString, boolean replaceAll) {
        if (!newString.isEmpty()) {
            return replaceAll
                ? originalContent.replace(oldString, newString)
                : replaceFirstLiteral(originalContent, oldString, newString);
        }
        boolean stripTrailingNewline = !oldString.endsWith("\n") && originalContent.contains(oldString + "\n");
        String search = stripTrailingNewline ? oldString + "\n" : oldString;
        return replaceAll
            ? originalContent.replace(search, newString)
            : replaceFirstLiteral(originalContent, search, newString);
    }

    /** 只替换首个字面出现 (等价 CC {@code String.prototype.replace(search, replacer)}). */
    private static String replaceFirstLiteral(String content, String search, String replace) {
        int idx = content.indexOf(search);
        if (idx < 0) {
            return content;
        }
        return content.substring(0, idx) + replace + content.substring(idx + search.length());
    }

    /** CC original: {@code {from, to}} 单条替换记录 (utils.ts:557-574). */
    public record Desanitization(String from, String to) {
    }

    /** CC original: {@code {result, appliedReplacements}} (utils.ts:558-559). */
    public record DesanitizedMatch(String result, List<Desanitization> appliedReplacements) {
    }

    /**
     * desanitize 归一化 · 对齐 CC {@code desanitizeMatchString} (utils.ts:557-574).
     * 按 DESANITIZATIONS 顺序逐项 replaceAll, 记录实际应用的替换, 供调用方同步应用到 new_string。
     */
    public static DesanitizedMatch desanitizeMatchString(String matchString) {
        String result = matchString;
        List<Desanitization> applied = new ArrayList<>();
        for (Map.Entry<String, String> entry : DESANITIZATIONS) {
            String before = result;
            result = result.replace(entry.getKey(), entry.getValue());
            if (!before.equals(result)) {
                applied.add(new Desanitization(entry.getKey(), entry.getValue()));
            }
        }
        return new DesanitizedMatch(result, applied);
    }

    /** CC original: {@code normalizeFileEditInput} 单条 edit 结果 (utils.ts:584-647). */
    public record NormalizedEdit(String oldString, String newString) {
    }

    /**
     * 归一化单条编辑 · 对齐 CC {@code normalizeFileEditInput} (utils.ts:584-647).
     * 非 markdown 时 new_string 先 stripTrailingWhitespace; 精确命中保持 old;
     * 精确失败则 desanitize old, 命中的替换同步应用到 new_string; 都失败返回原样。
     */
    public static NormalizedEdit normalizeEdit(String filePath, String fileContent, String oldString, String newString) {
        boolean isMarkdown = filePath != null && filePath.toLowerCase().matches(".*\\.(md|mdx)$");
        String normalizedNewString = isMarkdown ? newString : stripTrailingWhitespace(newString);
        if (fileContent.contains(oldString)) {
            return new NormalizedEdit(oldString, normalizedNewString);
        }
        DesanitizedMatch desanitized = desanitizeMatchString(oldString);
        if (fileContent.contains(desanitized.result())) {
            String desanitizedNewString = normalizedNewString;
            for (Desanitization d : desanitized.appliedReplacements()) {
                desanitizedNewString = desanitizedNewString.replace(d.from(), d.to());
            }
            return new NormalizedEdit(desanitized.result(), desanitizedNewString);
        }
        return new NormalizedEdit(oldString, normalizedNewString);
    }

    /** CC original: {@code FileEdit} 单条 edit (types.ts). */
    public record EditMatch(String oldString, String newString, boolean replaceAll) {
    }

    /** CC original: {@code getPatchForEdits} 返回 {patch, updatedFile} (utils.ts:262-353). */
    public record EditMatchResult(String updatedFile, List<StructuredPatchHunk> patch) {
    }

    /**
     * 应用编辑列表并返回更新后内容 + 显示用 patch · 对齐 CC {@code getPatchForEdits} (utils.ts:262-353).
     *
     * <ul>
     *   <li>子串守卫: old_string 去尾 \n 后是前次 new_string 的子串 → 抛错防重叠写坏文件 (utils.ts:278-290)</li>
     *   <li>空文件特例: 单 edit old==new=='' → 返回空 patch + 空 updatedFile (utils.ts:265-276)</li>
     *   <li>单条未变更 (old 不存在) → 抛错 "String not found" (utils.ts:301-304)</li>
     *   <li>整体未变更 (编辑结果与原文相同) → 抛错 "match exactly" (utils.ts:306-309)</li>
     * </ul>
     *
     * @throws IllegalStateException 子串守卫/未变更/未找到时
     */
    public static EditMatchResult getPatchForEdits(String fileContents, List<EditMatch> edits) {
        String updatedFile = fileContents;
        List<String> appliedNewStrings = new ArrayList<>();

        // Special case for empty files (utils.ts:265-276).
        if (fileContents.isEmpty() && edits.size() == 1
            && edits.get(0) != null && edits.get(0).oldString().isEmpty()
            && edits.get(0).newString().isEmpty()) {
            return new EditMatchResult("", List.of());
        }

        for (EditMatch edit : edits) {
            // Strip trailing newlines from old_string before checking (utils.ts:277)
            String oldStringToCheck = edit.oldString().replaceAll("\\n+$", "");

            for (String previousNewString : appliedNewStrings) {
                if (!oldStringToCheck.isEmpty() && previousNewString.contains(oldStringToCheck)) {
                    throw new IllegalStateException(
                        "Cannot edit file: old_string is a substring of a new_string from a previous edit.");
                }
            }

            String previousContent = updatedFile;
            updatedFile = edit.oldString().isEmpty()
                ? edit.newString()
                : applyEditToFile(updatedFile, edit.oldString(), edit.newString(), edit.replaceAll());

            if (updatedFile.equals(previousContent)) {
                throw new IllegalStateException("String not found in file. Failed to apply edit.");
            }
            appliedNewStrings.add(edit.newString());
        }

        if (updatedFile.equals(fileContents)) {
            throw new IllegalStateException("Original and edited file match exactly. Failed to apply edit.");
        }

        // 显示用 patch (CC getPatchFromContents 等价物, E1 StructuredPatchGenerator)
        List<StructuredPatchHunk> patch = StructuredPatchGenerator.getPatch(fileContents, updatedFile);
        return new EditMatchResult(updatedFile, patch);
    }

    /**
     * 判定两组 edit 是否等价 · 对齐 CC {@code areFileEditsEquivalent} (utils.ts:663-730).
     * 字面相同 → true; 否则分别应用, 双抛错比错误信息, 单抛错 → false, 都成功比 updatedFile。
     */
    public static boolean areFileEditsEquivalent(List<EditMatch> edits1, List<EditMatch> edits2, String originalContent) {
        if (edits1.size() == edits2.size()) {
            boolean identical = true;
            for (int i = 0; i < edits1.size(); i++) {
                EditMatch e1 = edits1.get(i);
                EditMatch e2 = edits2.get(i);
                if (!e1.oldString().equals(e2.oldString())
                    || !e1.newString().equals(e2.newString())
                    || e1.replaceAll() != e2.replaceAll()) {
                    identical = false;
                    break;
                }
            }
            if (identical) {
                return true;
            }
        }

        ApplyOutcome o1 = applyForComparison(edits1, originalContent);
        ApplyOutcome o2 = applyForComparison(edits2, originalContent);
        if (o1.error() != null && o2.error() != null) {
            return o1.error().equals(o2.error());
        }
        if (o1.error() != null || o2.error() != null) {
            return false;
        }
        return Objects.equals(o1.result().updatedFile(), o2.result().updatedFile());
    }

    private record ApplyOutcome(String error, EditMatchResult result) {
    }

    /** 应用一组 edit, 成功返回 null error, 失败返回错误信息 (对齐 CC errorMessage(e)). */
    private static ApplyOutcome applyForComparison(List<EditMatch> edits, String originalContent) {
        try {
            return new ApplyOutcome(null, getPatchForEdits(originalContent, edits));
        } catch (RuntimeException e) {
            return new ApplyOutcome(e.getMessage(), null);
        }
    }
}
