package com.nexusai.application.agent.bash;

/**
 * BashTool output utilities · 对齐 CC tools/BashTool/utils.ts (223 LOC).
 *
 * <p>L1 语义: 存活 helper functions（IMP-DEL1 · TR-B1-⊕-8 删除 4 个孤儿方法
 * resizeShellImageOutput/stdErrAppendShellResetMessage/resetCwdIfOutsideProject/
 * createContentSummary，CC 由 UI 消费，Java 无调用方）:
 *            - stripEmptyLines: 去除首尾空白行.
 *            - isImageOutput: 检测 base64 data URI.
 *            - parseDataUri: 拆 mediaType + base64 payload.
 *            - buildImageToolResult: 构造 image tool_result block.
 *            - formatOutput: 输出格式化 (truncate + line count).
 */
public final class BashOutputUtils {

    /** 输出截断默认阈值 · 对齐 CC {@code BASH_MAX_OUTPUT_DEFAULT} @
     *  utils/shell/outputLimits.ts:5（getMaxOutputLength 默认 30_000；上限 150_000）。 */
    public static final int BASH_MAX_OUTPUT_DEFAULT = 30_000;

    /** Formatted output (CC formatOutput). */
    public record FormattedOutput(int totalLines, String truncatedContent, boolean isImage) {}

    /** CC stripEmptyLines. */
    public static String stripEmptyLines(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n");
        int start = 0;
        while (start < lines.length && lines[start] != null && lines[start].trim().isEmpty()) start++;
        int end = lines.length - 1;
        while (end >= 0 && lines[end] != null && lines[end].trim().isEmpty()) end--;
        if (start > end) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * 是否为 base64 image data URI · 对齐 CC utils.ts:49-51
     * {@code return /^data:image\/[a-z0-9.+_-]+;base64,/i.test(content)}。
     *
     * <p>前缀锚 find（全锚 + 换行边界）：CC 是 prefix test 非全串匹配，且<b>不 trim</b>
     * —— 前导空白使 CC test 为 false（本实现同样不做 trim，find() 锚定字符串起始），
     * 尾部换行/任意内容不影响结果（旧 Java {@code trim().matches(...)} 有 trim 语义偏差，
     * 且 {@code .matches} 全串匹配遇尾部换行 false，双偏差，见探查 D3）。字符集
     * {@code [a-z0-9.+_-]} + {@code i} 旗标（CC 同款，注意含 {@code _}）。
     */
    public static boolean isImageOutput(String content) {
        if (content == null) return false;
        return IMAGE_OUTPUT_PREFIX.matcher(content).find();
    }

    /** 前缀锚 regex · CC utils.ts:50 {@code /^data:image\/[a-z0-9.+_-]+;base64,/i}。 */
    private static final java.util.regex.Pattern IMAGE_OUTPUT_PREFIX = java.util.regex.Pattern.compile(
        "^data:image/[a-z0-9.+_-]+;base64,", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** CC parseDataUri. */
    public record DataUri(String mediaType, String data) {}

    public static DataUri parseDataUri(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^data:([^;]+);base64,(.+)$")
            .matcher(trimmed);
        if (!m.matches()) return null;
        return new DataUri(m.group(1), m.group(2));
    }

    /** CC buildImageToolResult. */
    public record ImageToolResult(String toolUseId, String mediaType, String data) {}

    public static ImageToolResult buildImageToolResult(String stdout, String toolUseId) {
        DataUri parsed = parseDataUri(stdout);
        if (parsed == null) return null;
        return new ImageToolResult(toolUseId, parsed.mediaType(), parsed.data());
    }

    /**
     * CC formatOutput（utils.ts:133-165）：单参签名，截断阈值取 CC getMaxOutputLength()
     * 默认 {@link #BASH_MAX_OUTPUT_DEFAULT}=30000（outputLimits.ts:5）。
     *
     * @param content 原始输出
     * @return 截断 + 行数计数结果
     */
    public static FormattedOutput formatOutput(String content) {
        return formatOutput(content, BASH_MAX_OUTPUT_DEFAULT);
    }

    /** CC formatOutput（utils.ts:133-165）。 */
    public static FormattedOutput formatOutput(String content, long maxOutputLength) {
        boolean isImage = isImageOutput(content);
        if (isImage) {
            return new FormattedOutput(1, content, true);
        }
        if (content == null) content = "";
        if (content.length() <= maxOutputLength) {
            int lines = countChar(content, '\n') + 1;
            return new FormattedOutput(lines, content, false);
        }
        String truncatedPart = content.substring(0, (int) maxOutputLength);
        int remaining = countChar(content, '\n', (int) maxOutputLength) + 1;
        String truncated = truncatedPart + "\n\n... [" + remaining + " lines truncated] ...";
        int total = countChar(content, '\n') + 1;
        return new FormattedOutput(total, truncated, false);
    }

    // --- helpers ---

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static int countChar(String s, char c, int max) {
        int n = 0;
        for (int i = max; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
