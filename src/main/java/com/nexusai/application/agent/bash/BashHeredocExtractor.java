package com.nexusai.application.agent.bash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heredoc 提取工具 · 逐字移植 CC {@code utils/bash/heredoc.ts:113-687}（quotedOnly 语义）
 * + {@code tools/BashTool/bashSecurity.ts:317-578}（isSafeHeredoc / stripSafeHeredocSubstitutions）。
 *
 * <p>三条导出语义：
 * <ul>
 *   <li>{@link #stripQuotedHeredocBodies} ——heredoc.ts:113-687 {@code extractHeredocs(quotedOnly:true)}：
 *       仅剥引号/转义定界符的 heredoc body（字面量，无 shell 展开）→ {@code __HEREDOC_n__} 占位保留同行内容。
 *       $'/$\" 引号、首 {@code <<} 前反引号、未配平 {@code ((} → 整体 bail 原样返回（安全方向）。</li>
 *   <li>{@link #stripSafeHeredocSubstitutions} ——bashSecurity.ts:521-578：剥 {@code $(cat <<'DELIM'…DELIM)}
 *       安全替换后返回 remainder，无命中返回 null。供 legacy misparsing gate 重检 remainder。</li>
 *   <li>{@link #isSafeHeredoc} ——bashSecurity.ts:317-514：行级闭合匹配的"可证安全"早放行判定。</li>
 * </ul>
 */
public final class BashHeredocExtractor {

    private BashHeredocExtractor() {
    }

    // CC heredoc.ts:69-71 HEREDOC_START_PATTERN
    // (?<!<)<<(?!<)(-)?[ \t]*(?:(['"])(\\?\w+)\2|\\?(\w+))
    private static final Pattern HEREDOC_START =
            Pattern.compile("(?<!<)<<(?!<)(-)?[ \\t]*(?:(['\"])(\\\\?\\w+)\\2|\\\\?(\\w+))");

    // CC bashSecurity.ts:12 / :325 / :525
    private static final Pattern HEREDOC_IN_SUBSTITUTION = Pattern.compile("\\$\\(.*<<");
    private static final Pattern SAFE_HEREDOC =
            Pattern.compile("\\$\\(cat[ \\t]*<<(-?)[ \\t]*(?:'+([A-Za-z_]\\w*)'+|\\\\([A-Za-z_]\\w*))");

    private static final Pattern CLOSE_PAREN = Pattern.compile("^([ \\t]*)\\)");
    private static final Pattern SAME_LINE_WS_ONLY = Pattern.compile("^[ \\t]*$");
    private static final Pattern ANSI_OR_LOCALE_QUOTE = Pattern.compile("\\$['\"]");
    private static final Pattern REMAINING_SAFE_CHARS = Pattern.compile("^[a-zA-Z0-9 \\t\"'.\\-/_@=,:+~]*$");

    private static final String PLACEHOLDER_PREFIX = "__HEREDOC_";
    private static final String PLACEHOLDER_SUFFIX = "__";

    private record HeredocInfo(int operatorStart, int operatorEnd, int contentStart, int contentEnd) {
    }

    private static boolean isBashWordTerminator(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '|' || c == '&'
                || c == ';' || c == '(' || c == ')' || c == '<' || c == '>';
    }

    private static boolean isPstEofTokenAfter(char c) {
        return c == ')' || c == '}' || c == '`' || c == '|' || c == '&'
                || c == ';' || c == '(' || c == '<' || c == '>';
    }

    /**
     * 逐字移植 CC {@code extractHeredocs(command, { quotedOnly: true }).processedCommand}
     * （heredoc.ts:113-687）。bail 时原样返回 command（安全方向）。
     */
    public static String stripQuotedHeredocBodies(String command) {
        if (!command.contains("<<")) {
            return command;
        }

        // 1. $'...' / $"..." (ANSI-C / locale quoting) → bail
        if (ANSI_OR_LOCALE_QUOTE.matcher(command).find()) {
            return command;
        }
        // 2. backtick before first << → bail (backtick 嵌套 + PST_EOFTOKEN 早闭合)
        int firstHeredocPos = command.indexOf("<<");
        if (firstHeredocPos > 0 && command.substring(0, firstHeredocPos).indexOf('`') >= 0) {
            return command;
        }
        // 3. unbalanced (( before first << → << may be arithmetic bit-shift
        if (firstHeredocPos > 0) {
            String before = command.substring(0, firstHeredocPos);
            int openArith = countOccurrences(before, "((");
            int closeArith = countOccurrences(before, "))");
            if (openArith > closeArith) {
                return command;
            }
        }

        List<HeredocInfo> matches = new ArrayList<>();
        List<int[]> skippedRanges = new ArrayList<>(); // [contentStart, contentEnd]

        // Incremental quote/comment scanner state
        final int[] scanPos = {0};
        final boolean[] scanInSingleQuote = {false};
        final boolean[] scanInDoubleQuote = {false};
        final boolean[] scanInComment = {false};
        final boolean[] scanDqEscapeNext = {false};
        final int[] scanPendingBackslashes = {0};

        Matcher m = HEREDOC_START.matcher(command);
        while (m.find()) {
            int startIndex = m.start();
            advanceScan(command, startIndex, scanPos, scanInSingleQuote, scanInDoubleQuote,
                    scanInComment, scanDqEscapeNext, scanPendingBackslashes);

            if (scanInSingleQuote[0] || scanInDoubleQuote[0]) continue;
            if (scanInComment[0]) continue;
            if (scanPendingBackslashes[0] % 2 == 1) continue;

            boolean insideSkipped = false;
            for (int[] skipped : skippedRanges) {
                if (startIndex > skipped[0] && startIndex < skipped[1]) {
                    insideSkipped = true;
                    break;
                }
            }
            if (insideSkipped) continue;

            String fullMatch = m.group();
            boolean isDash = "-".equals(m.group(1));
            String delimiter = m.group(3) != null ? m.group(3) : m.group(4);
            int operatorEnd = startIndex + fullMatch.length();

            // Check 1: quoted delimiter 的闭合引号必须被 \2 真正匹配
            String quoteChar = m.group(2);
            if (quoteChar != null && command.charAt(operatorEnd - 1) != quoteChar.charAt(0)) {
                continue;
            }

            boolean isEscapedDelimiter = fullMatch.indexOf('\\') >= 0;
            boolean isQuotedOrEscaped = quoteChar != null || isEscapedDelimiter;

            // Check 2: 后一字符必须是 bash word terminator（仅 0x20/0x09/0x0A + |&;()<>）
            if (operatorEnd < command.length()
                    && !isBashWordTerminator(command.charAt(operatorEnd))) {
                continue;
            }

            // 找第一个不在引号内的换行（逻辑命令行）
            int firstNewlineOffset = findFirstUnquotedNewline(command, operatorEnd);
            if (firstNewlineOffset == -1) continue;

            // same-line 尾随反斜杠奇偶：奇数 → 行续接，heredoc-before-continuation 顺序会误解析 → bail
            String sameLine = command.substring(operatorEnd, operatorEnd + firstNewlineOffset);
            int trailingBackslashes = 0;
            for (int j = sameLine.length() - 1; j >= 0; j--) {
                if (sameLine.charAt(j) == '\\') trailingBackslashes++;
                else break;
            }
            if (trailingBackslashes % 2 == 1) continue;

            int contentStart = operatorEnd + firstNewlineOffset;
            String afterNewline = command.substring(contentStart + 1);
            String[] contentLines = afterNewline.split("\n", -1);

            int closingLineIndex = -1;
            for (int i = 0; i < contentLines.length; i++) {
                String line = contentLines[i];
                String stripped = isDash ? line.replaceFirst("^\\t*", "") : line;
                if (stripped.equals(delimiter)) {
                    closingLineIndex = i;
                    break;
                }
                String eofCheck = isDash ? line.replaceFirst("^\\t*", "") : line;
                if (eofCheck.length() > delimiter.length() && eofCheck.startsWith(delimiter)
                        && isPstEofTokenAfter(eofCheck.charAt(delimiter.length()))) {
                    closingLineIndex = -1;
                    break;
                }
            }

            // quotedOnly: 非引号/转义定界符 → 记录 content range（供 nesting 拒绝）但跳过
            if (!isQuotedOrEscaped) {
                int skipContentEnd;
                if (closingLineIndex == -1) {
                    skipContentEnd = command.length();
                } else {
                    int skipLen = joinLen(contentLines, closingLineIndex + 1);
                    skipContentEnd = contentStart + 1 + skipLen;
                }
                skippedRanges.add(new int[]{contentStart, skipContentEnd});
                continue;
            }

            if (closingLineIndex == -1) continue;

            int contentLen = joinLen(contentLines, closingLineIndex + 1);
            int contentEnd = contentStart + 1 + contentLen;

            // overlap with skipped ranges → skip
            boolean overlaps = false;
            for (int[] skipped : skippedRanges) {
                if (contentStart < skipped[1] && skipped[0] < contentEnd) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;

            matches.add(new HeredocInfo(startIndex, operatorEnd, contentStart, contentEnd));
        }

        if (matches.isEmpty()) {
            return command;
        }

        // Filter nested heredocs
        List<HeredocInfo> topLevel = new ArrayList<>();
        for (HeredocInfo candidate : matches) {
            boolean nested = false;
            for (HeredocInfo other : matches) {
                if (candidate == other) continue;
                if (candidate.operatorStart > other.contentStart
                        && candidate.operatorStart < other.contentEnd) {
                    nested = true;
                    break;
                }
            }
            if (!nested) topLevel.add(candidate);
        }
        if (topLevel.isEmpty()) {
            return command;
        }

        // dedup contentStart
        Set<Integer> contentStarts = new HashSet<>();
        for (HeredocInfo h : topLevel) contentStarts.add(h.contentStart);
        if (contentStarts.size() < topLevel.size()) {
            return command;
        }

        // sort by contentEnd desc → replace from end to start（保持更靠前索引不变）
        topLevel.sort((a, b) -> Integer.compare(b.contentEnd, a.contentEnd));

        String salt = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String result = command;
        for (int i = 0; i < topLevel.size(); i++) {
            int placeholderIndex = topLevel.size() - 1 - i;
            HeredocInfo info = topLevel.get(i);
            String placeholder = PLACEHOLDER_PREFIX + placeholderIndex + "_" + salt + PLACEHOLDER_SUFFIX;
            result = result.substring(0, info.operatorStart)
                    + placeholder
                    + result.substring(info.operatorEnd, info.contentStart)
                    + result.substring(info.contentEnd);
        }
        return result;
    }

    private static int joinLen(String[] lines, int count) {
        if (count <= 0) return 0;
        int len = 0;
        for (int i = 0; i < count; i++) {
            if (i > 0) len += 1; // newline
            len += lines[i].length();
        }
        return len;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void advanceScan(String command, int target, int[] pos,
            boolean[] inSingle, boolean[] inDouble, boolean[] inComment,
            boolean[] dqEscapeNext, int[] pendingBackslashes) {
        for (int i = pos[0]; i < target; i++) {
            char ch = command.charAt(i);

            if (ch == '\n') inComment[0] = false;

            if (inSingle[0]) {
                if (ch == '\'') inSingle[0] = false;
                continue;
            }
            if (inDouble[0]) {
                if (dqEscapeNext[0]) {
                    dqEscapeNext[0] = false;
                    continue;
                }
                if (ch == '\\') {
                    dqEscapeNext[0] = true;
                    continue;
                }
                if (ch == '"') inDouble[0] = false;
                continue;
            }

            if (ch == '\\') {
                pendingBackslashes[0]++;
                continue;
            }
            boolean escaped = pendingBackslashes[0] % 2 == 1;
            pendingBackslashes[0] = 0;
            if (escaped) continue;

            if (ch == '\'') inSingle[0] = true;
            else if (ch == '"') inDouble[0] = true;
            else if (!inComment[0] && ch == '#') inComment[0] = true;
        }
        pos[0] = target;
    }

    private static int findFirstUnquotedNewline(String command, int from) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int k = from; k < command.length(); k++) {
            char ch = command.charAt(k);
            if (inSingle) {
                if (ch == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (ch == '\\') {
                    k++;
                    continue;
                }
                if (ch == '"') inDouble = false;
                continue;
            }
            if (ch == '\n') {
                return k - from;
            }
            int backslashCount = 0;
            for (int j = k - 1; j >= from && command.charAt(j) == '\\'; j--) {
                backslashCount++;
            }
            if (backslashCount % 2 == 1) continue;
            if (ch == '\'') inSingle = true;
            else if (ch == '"') inDouble = true;
        }
        return -1;
    }

    /**
     * 逐字移植 CC {@code stripSafeHeredocSubstitutions}（bashSecurity.ts:521-578）。
     *
     * @return 剥除 {@code $(cat <<'DELIM'…DELIM)} 后的 remainder；无安全替换命中返回 null。
     */
    public static String stripSafeHeredocSubstitutions(String command) {
        if (!HEREDOC_IN_SUBSTITUTION.matcher(command).find()) return null;

        List<int[]> ranges = new ArrayList<>();
        boolean found = false;
        Matcher m = SAFE_HEREDOC.matcher(command);
        while (m.find()) {
            int matchIndex = m.start();
            if (matchIndex > 0 && command.charAt(matchIndex - 1) == '\\') continue;
            String delimiter = m.group(2) != null ? m.group(2) : m.group(3);
            if (delimiter == null) continue;
            boolean isDash = "-".equals(m.group(1));
            int operatorEnd = m.end();

            String afterOperator = command.substring(operatorEnd);
            int openLineEnd = afterOperator.indexOf('\n');
            if (openLineEnd == -1) continue;
            if (!SAME_LINE_WS_ONLY.matcher(afterOperator.substring(0, openLineEnd)).matches()) continue;

            int bodyStart = operatorEnd + openLineEnd + 1;
            String[] bodyLines = command.substring(bodyStart).split("\n", -1);
            for (int i = 0; i < bodyLines.length; i++) {
                String rawLine = bodyLines[i];
                String line = isDash ? rawLine.replaceFirst("^\\t*", "") : rawLine;
                if (line.startsWith(delimiter)) {
                    String after = line.substring(delimiter.length());
                    int closePos = -1;
                    if (CLOSE_PAREN.matcher(after).find()) {
                        int lineStart = bodyStart + joinLen(bodyLines, i) + (i > 0 ? 1 : 0);
                        closePos = command.indexOf(')', lineStart);
                    } else if (after.isEmpty()) {
                        String nextLine = i + 1 < bodyLines.length ? bodyLines[i + 1] : null;
                        if (nextLine != null && CLOSE_PAREN.matcher(nextLine).find()) {
                            int nextLineStart = bodyStart + joinLen(bodyLines, i + 1) + 1;
                            closePos = command.indexOf(')', nextLineStart);
                        }
                    }
                    if (closePos != -1) {
                        ranges.add(new int[]{matchIndex, closePos + 1});
                        found = true;
                    }
                    break;
                }
            }
        }
        if (!found) return null;

        StringBuilder result = new StringBuilder(command);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] r = ranges.get(i);
            result.delete(r[0], r[1]);
        }
        return result.toString();
    }

    /**
     * 逐字移植 CC {@code isSafeHeredoc}（bashSecurity.ts:317-514）。
     *
     * <p>EARLY-ALLOW：仅允许 {@code [prefix] $(cat <<'DELIM'…DELIM) [suffix]} 且 prefix 存在
     * （替换在参数位非命令位）、闭定界符为 FIRST 行级闭合、remainder 只含安全字符、
     * remainder 再跑全链仍 passthrough。须"可证安全"。
     *
     * @param command                  命令原文
     * @param remainingPassesValidators 对 remainder 重跑主链返回"非 ask"的判定
     *                                 （等价 CC {@code bashCommandIsSafe_DEPRECATED(remaining).behavior === 'passthrough'}）
     */
    public static boolean isSafeHeredoc(String command, Predicate<String> remainingPassesValidators) {
        if (!HEREDOC_IN_SUBSTITUTION.matcher(command).find()) return false;

        record SafeHeredoc(int start, int operatorEnd, String delimiter, boolean isDash) {}
        List<SafeHeredoc> safeHeredocs = new ArrayList<>();
        Matcher m = SAFE_HEREDOC.matcher(command);
        while (m.find()) {
            String delimiter = m.group(2) != null ? m.group(2) : m.group(3);
            if (delimiter != null) {
                safeHeredocs.add(new SafeHeredoc(m.start(), m.end(), delimiter, "-".equals(m.group(1))));
            }
        }
        if (safeHeredocs.isEmpty()) return false;

        record Verified(int start, int end) {}
        List<Verified> verified = new ArrayList<>();

        for (SafeHeredoc sh : safeHeredocs) {
            String afterOperator = command.substring(sh.operatorEnd());
            int openLineEnd = afterOperator.indexOf('\n');
            if (openLineEnd == -1) return false;
            String openLineTail = afterOperator.substring(0, openLineEnd);
            if (!SAME_LINE_WS_ONLY.matcher(openLineTail).matches()) return false;

            int bodyStart = sh.operatorEnd() + openLineEnd + 1;
            String[] bodyLines = command.substring(bodyStart).split("\n", -1);

            int closingLineIdx = -1;
            int closeParenLineIdx = -1;
            int closeParenColIdx = -1;

            for (int i = 0; i < bodyLines.length; i++) {
                String rawLine = bodyLines[i];
                String line = sh.isDash() ? rawLine.replaceFirst("^\\t*", "") : rawLine;

                if (line.equals(sh.delimiter())) {
                    closingLineIdx = i;
                    String nextLine = i + 1 < bodyLines.length ? bodyLines[i + 1] : null;
                    if (nextLine == null) return false;
                    Matcher pm = CLOSE_PAREN.matcher(nextLine);
                    if (!pm.find()) return false;
                    closeParenLineIdx = i + 1;
                    closeParenColIdx = pm.group(1).length();
                    break;
                }

                if (line.startsWith(sh.delimiter())) {
                    String afterDelim = line.substring(sh.delimiter().length());
                    Matcher pm = CLOSE_PAREN.matcher(afterDelim);
                    if (pm.find()) {
                        closingLineIdx = i;
                        closeParenLineIdx = i;
                        String tabStripped = sh.isDash() ? rawLine.replaceFirst("^\\t*", "") : rawLine;
                        int tabPrefix = rawLine.length() - tabStripped.length();
                        closeParenColIdx = tabPrefix + sh.delimiter().length() + pm.group(1).length();
                        break;
                    }
                    if (!afterDelim.isEmpty() && isPstEofTokenAfter(afterDelim.charAt(0))) {
                        return false; // ambiguous early-closure pattern
                    }
                }
            }

            if (closingLineIdx == -1) return false;

            int endPos = bodyStart;
            for (int i = 0; i < closeParenLineIdx; i++) {
                endPos += bodyLines[i].length() + 1;
            }
            endPos += closeParenColIdx + 1;

            verified.add(new Verified(sh.start(), endPos));
        }

        // reject nested matches
        for (Verified outer : verified) {
            for (Verified inner : verified) {
                if (inner == outer) continue;
                if (inner.start() > outer.start() && inner.start() < outer.end()) {
                    return false;
                }
            }
        }

        List<Verified> sorted = new ArrayList<>(verified);
        sorted.sort((a, b) -> Integer.compare(b.start(), a.start()));
        StringBuilder remaining = new StringBuilder(command);
        for (Verified v : sorted) {
            remaining.delete(v.start(), v.end());
        }

        String trimmedRemaining = remaining.toString().trim();
        if (trimmedRemaining.length() > 0) {
            int firstStart = Integer.MAX_VALUE;
            for (Verified v : verified) firstStart = Math.min(firstStart, v.start());
            String prefix = command.substring(0, firstStart);
            if (prefix.trim().isEmpty()) {
                return false;
            }
        }

        if (!REMAINING_SAFE_CHARS.matcher(remaining.toString()).matches()) return false;

        if (remainingPassesValidators != null && !remainingPassesValidators.test(remaining.toString())) {
            return false;
        }

        return true;
    }
}
