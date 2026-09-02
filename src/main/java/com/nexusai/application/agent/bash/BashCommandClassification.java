package com.nexusai.application.agent.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * [G2] Bash 命令搜索/读取分类 · 对齐 CC {@code BashTool.tsx:95-172 isSearchOrReadBashCommand}
 * + {@code Open-ClaudeCode/src/utils/bash/commands.ts:85-… splitCommandWithOperators}。
 *
 * <p>CC 真源 (grep 实证, 不信注释读实际 TS):
 * <ul>
 *   <li>{@code BASH_SEARCH_COMMANDS} = {find, grep, rg, ag, ack, locate, which, whereis}
 *       （BashTool.tsx:60）</li>
 *   <li>{@code BASH_READ_COMMANDS}   = {cat, head, tail, less, more, wc, stat, file, strings,
 *       jq, awk, cut, sort, uniq, tr}（BashTool.tsx:63-68）</li>
 *   <li>{@code BASH_LIST_COMMANDS}   = {ls, tree, du}（BashTool.tsx:72）</li>
 *   <li>{@code BASH_SEMANTIC_NEUTRAL_COMMANDS} = {echo, printf, true, false, :}（BashTool.tsx:77-78）</li>
 * </ul>
 *
 * <p><b>WHY（CC 语义陷阱, BashTool.tsx:139-149）</b>: 任一非三集命令段 → 整条不折叠
 * （{@code isSearch/isRead/isList} 全 false）——例如 {@code cat file | bq} 中 {@code bq}
 * 不是 search/read/list → 整个管线不折叠；{@code ls dir && echo "---" && ls dir2}
 * 因 {@code echo} 是语义中性命令（BashTool.tsx:77-78）仍折叠为 list。
 *
 * <p><b>Java 简化（与 CC 的行为差）</b>: CC 用 shell-quote 库解析成 token（含操作符独立
 * 成段 + heredoc 提取 + 注释 + glob 还原）；Java 端实现引号感知的等价切分（操作符独立成段、
 * heredoc 体不按换行切段、续行合并）。对分类语义关键路径（操作符重定向跳读、
 * 引号内操作符不切分）行为一致。
 */
public final class BashCommandClassification {

    private static final Logger log = LoggerFactory.getLogger(BashCommandClassification.class);

    /** CC original: BASH_SEARCH_COMMANDS（BashTool.tsx:60）。 */
    private static final Set<String> BASH_SEARCH_COMMANDS =
            Set.of("find", "grep", "rg", "ag", "ack", "locate", "which", "whereis");

    /** CC original: BASH_READ_COMMANDS（BashTool.tsx:63-68）。 */
    private static final Set<String> BASH_READ_COMMANDS =
            Set.of("cat", "head", "tail", "less", "more", "wc", "stat", "file", "strings",
                    "jq", "awk", "cut", "sort", "uniq", "tr");

    /** CC original: BASH_LIST_COMMANDS（BashTool.tsx:72）。 */
    private static final Set<String> BASH_LIST_COMMANDS = Set.of("ls", "tree", "du");

    /** CC original: BASH_SEMANTIC_NEUTRAL_COMMANDS（BashTool.tsx:77-78）——任何位置的语义中性命令。 */
    private static final Set<String> BASH_SEMANTIC_NEUTRAL_COMMANDS = Set.of("echo", "printf", "true", "false", ":");

    private BashCommandClassification() {
    }

    /**
     * 搜索/读取分类结果 · 对齐 CC {@code BashTool.tsx:95-100 isSearchOrReadBashCommand}
     * 返回的 {@code {isSearch, isRead, isList}}。
     *
     * @param isSearch CC original: isSearch — 搜索操作（grep/find/glob）
     * @param isRead   CC original: isRead — 读取操作（cat/head/tail/file read）
     * @param isList   CC original: isList — 目录列表操作（ls/tree/du）
     */
    public record SearchReadClassification(boolean isSearch, boolean isRead, boolean isList) {

        /** 是否可折叠 · 对齐 CC collapseReadSearch.ts:220 {@code isCollapsible = isSearch||isRead||isList}。 */
        public boolean isCollapsible() {
            return isSearch || isRead || isList;
        }
    }

    /**
     * 是否搜索/读取命令 · 对齐 CC {@code BashTool.tsx:95-172 isSearchOrReadBashCommand}。
     *
     * <p>CC 语义（逐条对齐）:
     * <ol>
     *   <li>{@code splitCommandWithOperators} 解析失败 / 空命令 → 全 false（BashTool.tsx:101-115）。</li>
     *   <li>重定向操作符（{@code >}/{\@code >>}/{\@code >\&}）跳读下一个（重定向目标）
     *       （BashTool.tsx:121-125）。</li>
     *   <li>操作符段（{@code ||}/{\@code &&}/{\@code |}/{\@code ;}）跳过（BashTool.tsx:126-128）。</li>
     *   <li>段首命令名取第一个空白分隔 token（BashTool.tsx:129-133）。</li>
     *   <li>语义中性命令（echo 等）跳过，不影响 hasNonNeutralCommand（BashTool.tsx:140-142）。</li>
     *   <li>非中性命令且不在三命令集 → 整条全 false（BashTool.tsx:147-151）。</li>
     *   <li>全为中性命令 → 全 false（BashTool.tsx:160-165）。</li>
     * </ol>
     *
     * @param command bash 命令串（LLM 给的 command 参数）
     * @return 三态分类（isSearch/isRead/isList）
     */
    public static SearchReadClassification classify(String command) {
        if (command == null || command.isBlank()) {
            return new SearchReadClassification(false, false, false);
        }
        List<String> parts;
        try {
            parts = splitCommandWithOperators(command);
        } catch (Exception e) {
            // CC BashTool.tsx:102-105 catch：语法异常 → 不视为 search/read
            if (log.isDebugEnabled()) {
                log.debug("BashCommandClassification 切分异常按非搜索/读取处理: err={}", e.toString());
            }
            return new SearchReadClassification(false, false, false);
        }
        if (parts.isEmpty()) {
            return new SearchReadClassification(false, false, false);
        }

        boolean hasSearch = false;
        boolean hasRead = false;
        boolean hasList = false;
        boolean hasNonNeutralCommand = false;
        boolean skipNextAsRedirectTarget = false;
        for (String part : parts) {
            if (skipNextAsRedirectTarget) {
                skipNextAsRedirectTarget = false;
                continue;
            }
            if (part.equals(">") || part.equals(">>") || part.equals(">&")) {
                skipNextAsRedirectTarget = true;
                continue;
            }
            if (part.equals("||") || part.equals("&&") || part.equals("|") || part.equals(";")) {
                continue;
            }
            String trimmed = part.trim();
            int ws = trimmed.indexOf(' ');
            String baseCommand = ws < 0 ? trimmed : trimmed.substring(0, ws);
            if (baseCommand.isEmpty()) {
                continue;
            }
            if (BASH_SEMANTIC_NEUTRAL_COMMANDS.contains(baseCommand)) {
                continue;
            }
            hasNonNeutralCommand = true;
            boolean isPartSearch = BASH_SEARCH_COMMANDS.contains(baseCommand);
            boolean isPartRead = BASH_READ_COMMANDS.contains(baseCommand);
            boolean isPartList = BASH_LIST_COMMANDS.contains(baseCommand);
            if (!isPartSearch && !isPartRead && !isPartList) {
                return new SearchReadClassification(false, false, false);
            }
            if (isPartSearch) {
                hasSearch = true;
            }
            if (isPartRead) {
                hasRead = true;
            }
            if (isPartList) {
                hasList = true;
            }
        }
        // 只有语义中性命令（如 echo foo）→ 不可折叠
        if (!hasNonNeutralCommand) {
            return new SearchReadClassification(false, false, false);
        }
        if (log.isDebugEnabled()) {
            log.debug("BashCommandClassification 分类完成: command={} isSearch={} isRead={} isList={}",
                    command, hasSearch, hasRead, hasList);
        }
        return new SearchReadClassification(hasSearch, hasRead, hasList);
    }

    /**
     * 引号感知操作符切分 · 对齐 CC {@code splitCommandWithOperators}（commands.ts:85-…）
     * 的「操作符独立成段」语义（Java 简化实现）。
     *
     * <p>行为:
     * <ul>
     *   <li>反斜杠续行合并（奇数个反斜杠 + 换行 → 移除，对齐 commands.ts:99-115）。</li>
     *   <li>heredoc（{@code <<TAG}）体不按换行切段（对齐 commands.ts:88-97 extractHeredocs）。</li>
     *   <li>单/双引号内的操作符不切分（shell-quote 等价）。</li>
     *   <li>操作符（{@code >>}, {@code >\&}, {@code >}, {@code \&\&}, {@code ||}, {@code |},
     *       {@code ;}）独立成段；换行作为命令边界。</li>
     * </ul>
     *
     * @param command bash 命令串
     * @return 段列表（含操作符独立段）
     */
    static List<String> splitCommandWithOperators(String command) {
        String joined = joinContinuations(command);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = joined.length();
        int i = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < n) {
            char c = joined.charAt(i);
            if (!inSingle && !inDouble && c == '\\' && i + 1 < n) {
                current.append(c).append(joined.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                i++;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                i++;
                continue;
            }
            if (!inSingle && !inDouble) {
                // heredoc 检测: <<（非 <<<）→ 跳过正文（含换行）直到结束标记
                if (c == '<' && i + 1 < n && joined.charAt(i + 1) == '<'
                        && !(i + 2 < n && joined.charAt(i + 2) == '<')) {
                    current.append("<<");
                    i += 2;
                    i = consumeHeredoc(joined, i, current);
                    continue;
                }
                // 双字符操作符
                if (i + 1 < n) {
                    String two = joined.substring(i, i + 2);
                    if (two.equals(">>") || two.equals(">&") || two.equals("&&") || two.equals("||")) {
                        flush(current, parts);
                        parts.add(two);
                        i += 2;
                        continue;
                    }
                }
                char op = c;
                if (op == '>' || op == '|' || op == ';' || op == '&') {
                    flush(current, parts);
                    parts.add(String.valueOf(op));
                    i++;
                    continue;
                }
                if (op == '\n') {
                    flush(current, parts);
                    i++;
                    continue;
                }
            }
            current.append(c);
            i++;
        }
        flush(current, parts);
        return parts;
    }

    /** 反斜杠续行合并 · 对齐 CC commands.ts:99-115（奇数个反斜杠 + 换行 → 移除反斜杠和换行）。 */
    private static String joinContinuations(String command) {
        StringBuilder sb = new StringBuilder(command.length());
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (c == '\\') {
                int backslashCount = 0;
                int j = i;
                while (j < n && command.charAt(j) == '\\') {
                    backslashCount++;
                    j++;
                }
                if (j < n && command.charAt(j) == '\n') {
                    if (backslashCount % 2 == 1) {
                        // 奇数：移除转义反斜杠和换行（续行）
                        sb.append("\\".repeat(backslashCount - 1));
                        i = j + 1;
                        continue;
                    }
                    // 偶数：全部配对为转义序列，换行是命令分隔符 —— 保留
                    sb.append("\\".repeat(backslashCount));
                    i = j;
                    continue;
                }
                sb.append("\\".repeat(backslashCount));
                i = j;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * 跳过 heredoc 正文。cc {@code <<TAG} 开始，直到独立 TAG 行或字符串结束；
     * 正文原样追加到 {@code current}（不按换行切段），返回结束后的下标。
     */
    private static int consumeHeredoc(String joined, int from, StringBuilder current) {
        // 读取结束标记（TAG 或 <<-TAG）
        int j = from;
        int len = joined.length();
        if (j < len && joined.charAt(j) == '-') {
            current.append('-');
            j++;
        }
        StringBuilder tag = new StringBuilder();
        while (j < len && !Character.isWhitespace(joined.charAt(j)) && joined.charAt(j) != '\n') {
            tag.append(joined.charAt(j));
            j++;
        }
        current.append(tag);
        if (tag.length() == 0) {
            return j; // << 后无标记：按普通文本继续
        }
        String delimiter = tag.toString().trim();
        StringBuilder body = new StringBuilder();
        boolean closed = false;
        // 逐行读取，找独立结束行
        while (j < len) {
            int nl = joined.indexOf('\n', j);
            String line = nl < 0 ? joined.substring(j) : joined.substring(j, nl);
            if (line.trim().equals(delimiter)) {
                body.append(line);
                current.append('\n').append(body);
                return nl < 0 ? len : nl + 1;
            }
            body.append('\n').append(line);
            if (nl < 0) {
                break;
            }
            j = nl + 1;
        }
        current.append('\n').append(body);
        return len;
    }

    /** 冲刷当前文本段（trim 后非空才加入）。 */
    private static void flush(StringBuilder sb, List<String> parts) {
        String s = sb.toString().trim();
        if (!s.isEmpty()) {
            parts.add(s);
        }
        sb.setLength(0);
    }
}
