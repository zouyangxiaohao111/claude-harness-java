package com.nexusai.application.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * picomatch 语义 glob 匹配器 · 对齐 CC {@code picomatch.isMatch(path, patterns, {dot:true})}
 * （claudemd.ts:572，claudeMdExcludes 命中判定）。
 *
 * <p><b>为什么单独实现</b>：CC 的 claudeMdExcludes 用 picomatch（非 ignore/gitignore）匹配
 * 绝对路径（settings/types.ts:1053 "Patterns are matched against absolute file paths using
 * picomatch"）。既有 {@link ClaudemdGlob} 是 gitignore 语义子集，与 picomatch 存在四项行为差异
 * （探查 cm-f1-claudemd-engine △-3）：extglob（{@code @(a|b)}/{@code !(...)}）、中段
 * {@code **} 语义（picomatch 仅完整段 globstar，中段 {@code a**b} 退化为单 {@code *}）、
 * basename 匹配规则（picomatch 全路径锚定，不做任意深度 basename）、dot 文件处理
 * （picomatch {@code dot:true} 显式）。本类按 CC 实际 picomatch v2 行为自实现（Java 无等价
 * 库），正则形态经逐 pattern 对照 {@code makeRe({dot:true})} 输出核验。
 *
 * <p><b>关键语义</b>（均以 picomatch v2.3.2 实测为准）：
 * <ul>
 *   <li>{@code *} → {@code [^/]*?}；段起点 {@code *} 加 dot-guard + {@code (?=.)}；
 *       中段/非完整段 {@code **}（{@code a**b}、{@code **.md}）退化为单 {@code *}</li>
 *   <li>{@code **} 完整段 globstar：仅当作为完整段时具备跨段语义；前导/中段/尾段展开形式
 *       逐字对照 picomatch（尾段 {@code /**} 在前段非 {@code *} 结尾时允许零段
 *       {@code |$}，前段以 {@code *} 结尾则必须消费 ≥1 段）</li>
 *   <li>{@code dot:true}：{@code *}/{@code ?} 可匹配段首 dot；但 {@code .}/{@code ..} 段
 *       恒被排除（dot-guard）</li>
 *   <li>extglob：{@code @(a|b)} → {@code (?:a|b)}；{@code !(a|b)} → {@code (?:(?!(?:a|b))[^/]*?)}；
 *       {@code +(..)}/{@code *(..)}/{@code ?(..)} → {@code (?:..)+} / {@code (?:..)*} /
 *       {@code (?:..)?}；negate 在段末加 {@code $} 锚定</li>
 *   <li>brace：{@code {a,b}} → {@code (?:a|b)}；{@code {1..3}} → {@code [1-3]}</li>
 *   <li>字符类：{@code [ab]} → 字面 bracket 文本与类双重匹配（picomatch quirk）；
 *       {@code [^a]} 为否定（排除 {@code /}）；{@code [!a]} <b>不是</b> 否定（picomatch 与
 *       gitignore 不同，仅字符类含 {@code !}）</li>
 *   <li>pattern 级前导 {@code !}：整个 pattern 否定（{@code !foo} 匹配除 {@code foo} 外
 *       一切），对齐 picomatch makeRe negated 包装</li>
 * </ul>
 *
 * <p>说明：CC {@code picomatch.isMatch(str, patterns, opts)} 对数组取 any-match（任一 pattern
 * 命中即 true），本类 {@link #isMatch} 同语义；空 pattern 过滤（CC {@code .filter(p => p.length > 0)}）
 * 由调用方完成。
 */
final class ClaudemdPicomatch {

    /** dot:true 下 picomatch NO_DOTS 等价段起点守卫：段不得恰为 {@code .} / {@code ..}。 */
    private static final String SEG_GUARD = "(?!(?:^|[/])\\.{1,2}(?:[/]|$))";
    /** dot:true 下 picomatch NO_DOTS_SLASH 等价：紧随字面 {@code /} 之后的守卫（能阻断 {@code ..}）。 */
    private static final String SEG_GUARD_AFTER_SLASH = "(?!\\.{1,2}(?:[/]|$))";
    /** picomatch globstar(opts) 核心：惰性跨段游标（逐字符过 dot-guard）。 */
    private static final String RUN = "(?:(?:(?!(?:^|[/])\\.{1,2}(?:[/]|$)).)*?)";
    /** picomatch STAR：段内任意字符（不跨 /）。 */
    private static final String STAR = "[^/]*?";
    /** picomatch ONE_CHAR。 */
    private static final String ONE_CHAR = "(?=.)";

    private ClaudemdPicomatch() {
    }

    /**
     * 任一 pattern 命中 → true · CC {@code picomatch.isMatch(path, patterns, {dot:true})}。
     * 空/空串 pattern 跳过（CC 在 resolveExcludePatterns 后 filter 空串）。dot:true 恒开。
     */
    static boolean isMatch(String path, List<String> patterns) {
        if (path == null || patterns == null) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            // picomatch.test 快路径：input === glob 恒命中（picomatch.js:128）
            if (path.equals(p)) {
                return true;
            }
            if (Pattern.matches(toRegex(p), path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单 pattern → 全锚定 Java regex · 对齐 picomatch makeRe({@code {dot:true}}) 输出。
     * pattern 级前导 {@code !} 处理对齐 picomatch negate 检测（parse.js:437-446）：
     * 连续前导 {@code !}（遇 {@code !(extglob} 停止），偶数抵消不否定，奇数否定（CC compileRe
     * negated 包装 {@code ^(?!<inner>).*$}，{@code !foo} 匹配除 {@code foo} 外一切）。
     */
    static String toRegex(String pattern) {
        int len = pattern.length();
        int bang = 0;
        while (bang < len && pattern.charAt(bang) == '!') {
            // 后随 ( 且非 (? → extglob（!(a)），非 negate 前缀
            if (bang + 1 < len && pattern.charAt(bang + 1) == '('
                && (bang + 2 >= len || pattern.charAt(bang + 2) != '?')) {
                break;
            }
            bang++;
        }
        String body = pattern.substring(bang);
        if (bang % 2 == 1) {
            return "^(?!" + toRegex(body) + ").*$";
        }
        if (body.isEmpty()) {
            return "^(?:)$"; // 全 ! 抵消后空体 → 仅匹配空串（picomatch `!` 处理为 negated 空）
        }
        return "^(?:" + toBody(body) + ")$";
    }

    /**
     * pattern → 未锚定 body（外层 {@code ^(?:...)$} 由 {@link #toRegex} 加）。
     * 先切段，按位置组装 globstar / 普通段，段间以 {@code /} 分隔。尾随 {@code /}（末段为空）
     * 单独产出字面 {@code [/]}（除非前一 globstar 形态已含尾部 {@code [/]}）。
     */
    private static String toBody(String pattern) {
        boolean anchored = pattern.startsWith("/");
        if (anchored) {
            pattern = pattern.substring(1);
            if (pattern.isEmpty()) {
                return "[/]"; // 裸 "/" → 匹配根
            }
        }
        String[] raw = pattern.split("/", -1);
        int n = raw.length;
        boolean hasTrailingEmpty = n > 0 && raw[n - 1].isEmpty();
        StringBuilder sb = new StringBuilder();
        if (anchored) {
            sb.append('/');
        }
        boolean prevEndsWithStar = false;          // 前段末字符为 *
        boolean prevSuppressesSep = false;         // 前段为「含尾 /」globstar（非锚前导/中段）
        boolean prevWasGlobstar = false;           // 前段为 globstar（用于 **/** 折叠）
        boolean emittedTrailingSlash = false;      // 中段/尾斜杠 globstar 已含尾部 [/]（a/**/ 形态）
        for (int i = 0; i < n; i++) {
            String seg = raw[i];
            boolean isFirst = (i == 0);
            boolean isLast = (i == n - 1);
            if (seg.isEmpty()) {
                // 尾随 / → 字面 [/]（除非前一 globstar 形态已含）；双斜杠中段空段不产出
                if (isLast && hasTrailingEmpty && !emittedTrailingSlash) {
                    sb.append('/');
                }
                prevWasGlobstar = false;
                prevSuppressesSep = false;
                prevEndsWithStar = false;
                continue;
            }
            boolean followingContent = hasFollowingContent(raw, i, n);
            if (seg.equals("**")) {
                if (prevWasGlobstar) {
                    // **/** 折叠：picomatch 剥连续 /**, 只保留首个 globstar
                    continue;
                }
                String emit;
                boolean suppressSep;
                if (isFirst && !anchored && followingContent) {
                    emit = "(?:^|[/]|" + RUN + "[/])"; // 非锚前导 **/x
                    suppressSep = true;
                } else if (isFirst && anchored && followingContent) {
                    emit = SEG_GUARD_AFTER_SLASH + RUN; // '/**/x' 锚后 globstar
                    suppressSep = false;
                } else if (isFirst && !anchored) {
                    // 整 pattern '**' 或 '**/'
                    emit = SEG_GUARD + RUN;
                    suppressSep = true;
                    if (hasTrailingEmpty) {
                        emittedTrailingSlash = true; // '**/' 尾斜杠被吸收（picomatch **/ 编译为前导形态）
                    }
                } else if (isFirst && anchored) {
                    // '/**' 或 '/**/'
                    emit = SEG_GUARD_AFTER_SLASH + RUN;
                    suppressSep = false;
                } else if (!followingContent) {
                    if (hasTrailingEmpty) {
                        // a/**/ → 中段含尾斜杠形态（trailing / 已内嵌）
                        emit = "(?:[/]" + SEG_GUARD_AFTER_SLASH + RUN + "[/]|[/])";
                        emittedTrailingSlash = true;
                    } else if (prevEndsWithStar) {
                        // 前段以 * 结尾 → ** 必须消费 ≥1 段（无 |$）
                        emit = "[/]" + SEG_GUARD_AFTER_SLASH + RUN;
                    } else {
                        emit = "(?:[/]" + SEG_GUARD_AFTER_SLASH + RUN + "|$)";
                    }
                    suppressSep = false;
                } else {
                    // 中段 /**/：rest[1] 有内容 → 加 |$；仅尾随 / → 不加
                    boolean endParen = (i + 1 < n) && !raw[i + 1].isEmpty();
                    emit = "(?:[/]" + SEG_GUARD_AFTER_SLASH + RUN + "[/]|[/]" + (endParen ? "|$" : "") + ")";
                    suppressSep = true;
                }
                sb.append(emit);
                prevSuppressesSep = suppressSep;
                prevEndsWithStar = true;
                prevWasGlobstar = true;
                continue;
            }
            // 普通段
            if (i > 0 && !prevSuppressesSep) {
                sb.append('/');
            }
            sb.append(segmentRegex(seg, isFirst && i == 0, isPatternEnd(raw, i, n, hasTrailingEmpty)));
            prevEndsWithStar = seg.endsWith("*");
            prevWasGlobstar = false;
            prevSuppressesSep = false;
        }
        // picomatch 尾段可选斜杠（strictSlashes 默认 false）
        if (shouldAppendOptionalSlash(raw, n, anchored)) {
            sb.append("[/]?");
        }
        return sb.toString();
    }

    /** i 之后是否存在非空且非 globstar 的段（决定 globstar 前导/中段/尾段形态）。 */
    private static boolean hasFollowingContent(String[] raw, int i, int n) {
        for (int k = i + 1; k < n; k++) {
            if (!raw[k].isEmpty() && !raw[k].equals("**")) {
                return true;
            }
        }
        return false;
    }

    /** i 之后是否无任何非空段且无尾随 /（negate extglob 段末 $ 判定用）。 */
    private static boolean isPatternEnd(String[] raw, int i, int n, boolean hasTrailingEmpty) {
        if (hasTrailingEmpty) {
            return false;
        }
        for (int k = i + 1; k < n; k++) {
            if (!raw[k].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * picomatch 尾段 {@code [/]?} 规则（strictSlashes 默认 false 时）：
     * (a) 一般解析器 maybe_slash：末 token 为 star（末段以单个 {@code *} 结尾）或 bracket（纯字符类）；
     * (b) create() 快路径：整 pattern 恰为 {@code **} 或单段 {@code *.ext} 通配基。
     * 末段为完整段 globstar {@code **}（如 {@code /x/**}）不追加（其形态已含 {@code |$}）。
     */
    private static boolean shouldAppendOptionalSlash(String[] raw, int n, boolean anchored) {
        String lastSeg = segmentsLast(raw, n);
        if (lastSeg.isEmpty()) {
            return false;
        }
        if (lastSeg.equals("**")) {
            // 仅整 pattern = '**'（非锚单段，create 快路径）追加；'/x/**' 等多段不追加
            return !anchored && segmentsCount(raw, n) == 1;
        }
        if (lastSeg.endsWith("*")) {
            // 单个 * 结尾（*、foo*、b*）→ maybe_slash；a** 等以 ** 结尾的非完整段由上面排除
            return !lastSeg.endsWith("**");
        }
        if (isPureBracketClass(lastSeg)) {
            return true;
        }
        // 单段 *.md / **.md 形式（create 快路径）
        int dot = lastSeg.lastIndexOf('.');
        if (dot > 0) {
            String base = lastSeg.substring(0, dot);
            String ext = lastSeg.substring(dot + 1);
            if (ext.matches("\\w+") && (base.equals("*") || base.equals("**") || base.equals("*.*"))) {
                return true;
            }
        }
        return false;
    }

    /** 非空段计数（排除空段；globstar 段计入）。 */
    private static int segmentsCount(String[] raw, int n) {
        int c = 0;
        for (int k = 0; k < n; k++) {
            if (!raw[k].isEmpty()) {
                c++;
            }
        }
        return c;
    }

    /** 最后一个非空段（尾段可选斜杠判定用）。 */
    private static String segmentsLast(String[] raw, int n) {
        for (int k = n - 1; k >= 0; k--) {
            if (!raw[k].isEmpty()) {
                return raw[k];
            }
        }
        return "";
    }

    private static boolean isPureBracketClass(String seg) {
        return seg.startsWith("[") && seg.endsWith("]") && findMatching(seg, 0) == seg.length() - 1;
    }

    // ════════════════════════════════════════════════════════════════
    // 段内容翻译（* ? [ ] { } extglob 转义 字面量）
    // ════════════════════════════════════════════════════════════════

    /**
     * 普通段（非完整段 globstar）→ regex 片段。atPatternStart=true 时 extglob 的
     * {@code (?=.)} 前缀才生效（对齐 picomatch extglobOpen：仅 whole-pattern 起点）。
     */
    private static String segmentRegex(String seg, boolean atPatternStart, boolean isPatternEnd) {
        StringBuilder sb = new StringBuilder();
        // 段起点 *（非 *(extglob)）：dot-guard + (?=.)（紧邻 * 时无 (?=.)，对齐 picomatch）。
        // guard 用 NO_DOTS_SLASH（picomatch 一般解析器 dot:true 分支，parse.js:1238-1240）
        if (seg.charAt(0) == '*' && (seg.length() < 2 || seg.charAt(1) != '(')) {
            sb.append(SEG_GUARD_AFTER_SLASH);
            if (seg.length() < 2 || seg.charAt(1) != '*') {
                sb.append(ONE_CHAR);
            }
        }
        translateRange(seg, 0, seg.length(), sb, atPatternStart, isPatternEnd);
        return sb.toString();
    }

    /** 在 [from,to) 区间内逐字符翻译段内容（支持嵌套，保持绝对索引供 extglob $ 判定）。 */
    private static void translateRange(String seg, int from, int to, StringBuilder sb, boolean atPatternStart, boolean isPatternEnd) {
        int i = from;
        while (i < to) {
            char c = seg.charAt(i);
            switch (c) {
                case '*' -> {
                    // *(extglob) 优先；否则段内 **（非完整段 globstar）退化为单 *
                    if (i + 1 < to && seg.charAt(i + 1) == '(') {
                        i = extglob(seg, i, to, sb, atPatternStart, atTruePatternStart(from, i, atPatternStart), isPatternEnd);
                    } else {
                        int j = i;
                        while (j < to && seg.charAt(j) == '*') {
                            j++;
                        }
                        sb.append(STAR);
                        i = j;
                    }
                }
                case '?' -> {
                    // 若为 ?(extglob) 由下一分支处理；单 ? 为单字符
                    if (i + 1 < to && seg.charAt(i + 1) == '(') {
                        i = extglob(seg, i, to, sb, atPatternStart, atTruePatternStart(from, i, atPatternStart), isPatternEnd);
                    } else {
                        sb.append("[^/]");
                        i++;
                    }
                }
                case '[' -> {
                    int close = findMatching(seg, i);
                    if (close < 0 || close >= to) {
                        sb.append("\\[");
                        i++;
                    } else {
                        bracketClass(seg, i, close, sb);
                        i = close + 1;
                    }
                }
                case '{' -> {
                    int close = findMatching(seg, i);
                    if (close < 0 || close >= to) {
                        sb.append("\\{");
                        i++;
                    } else {
                        brace(seg, i, close, sb, atPatternStart, isPatternEnd);
                        i = close + 1;
                    }
                }
                case '@', '!', '+' -> {
                    if (i + 1 < to && seg.charAt(i + 1) == '(') {
                        i = extglob(seg, i, to, sb, atPatternStart, atTruePatternStart(from, i, atPatternStart), isPatternEnd);
                    } else {
                        sb.append(escapeLiteral(c));
                        i++;
                    }
                }
                case '\\' -> {
                    if (i + 1 < to) {
                        sb.append(Pattern.quote(String.valueOf(seg.charAt(i + 1))));
                        i += 2;
                    } else {
                        sb.append("\\\\");
                        i++;
                    }
                }
                default -> {
                    sb.append(escapeLiteral(c));
                    i++;
                }
            }
        }
    }

    /** extglob 是否位于整 pattern 起点（from==0 且 i==from 且 atPatternStart）——picomatch
     *  extglobOpen 的 {@code state.output ? '' : ONE_CHAR} 判定（parse.js:505）。嵌套/后段不补。 */
    private static boolean atTruePatternStart(int from, int i, boolean atPatternStart) {
        return atPatternStart && from == 0 && i == from;
    }

    /** 字符类：非否定 → 字面 bracket 文本 + 类双重匹配；[^a] → 否定类（排除 /）。 */
    private static void bracketClass(String seg, int open, int close, StringBuilder sb) {
        String cls = seg.substring(open + 1, close);
        if (cls.startsWith("^")) {
            sb.append("[^").append(escapeClassBody(cls.substring(1))).append("/]");
        } else {
            sb.append("(?:(?:\\[").append(escapeLiteral(cls)).append("\\]|[")
                .append(escapeClassBody(cls)).append("]))");
        }
    }

    /**
     * brace：逗号分隔 → 交替组；N..M / a..c 数字/字母区间 → 字符类；无逗号无区间 → 字面
     * {@code \{foo\}}（picomatch {foo} 编译为转义字面量，非分组）。
     */
    private static void brace(String seg, int open, int close, StringBuilder sb, boolean atPatternStart, boolean isPatternEnd) {
        String content = seg.substring(open + 1, close);
        List<int[]> parts = splitTopLevelChar(seg, open + 1, close, ',');
        if (parts.size() > 1) {
            sb.append("(?:");
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) {
                    sb.append('|');
                }
                translateRange(seg, parts.get(k)[0], parts.get(k)[1], sb, atPatternStart, isPatternEnd);
            }
            sb.append(')');
            return;
        }
        // {1..3} / {a..c} 区间
        int dots = content.indexOf("..");
        if (dots > 0) {
            String a = content.substring(0, dots);
            String b = content.substring(dots + 2);
            if (a.length() == 1 && b.length() == 1 && Character.isDigit(a.charAt(0)) && Character.isDigit(b.charAt(0))) {
                sb.append('[').append(a).append('-').append(b).append(']');
                return;
            }
            if (a.length() == 1 && b.length() == 1 && Character.isLetter(a.charAt(0)) && Character.isLetter(b.charAt(0))) {
                sb.append('[').append(a).append('-').append(b).append(']');
                return;
            }
        }
        // 无逗号无区间 → 字面 {content}
        sb.append("\\{");
        translateRange(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
        sb.append("\\}");
    }

    /** extglob：@(..) → (?:..)；!(..) → negate（段末加 $）；+(..) → (?:..)+；*(..) → (?:..)*；?(..) → (?:..)?。 */
    private static int extglob(String seg, int i, int to, StringBuilder sb, boolean atPatternStart, boolean atSegStart, boolean isPatternEnd) {
        char kind = seg.charAt(i);
        int open = i + 1;
        int close = findMatching(seg, open);
        if (close < 0 || close >= to) {
            sb.append(escapeLiteral(kind));
            return i + 1;
        }
        // negate 段末 $ 判定：extglob 位于整 pattern 末（isPatternEnd）且 close 之后仅剩 ')' 闭合符
        // （picomatch eos/仅剩 ) 判定 parse.js:545-556；嵌套 @(a|!(b)) 或后随 /** 均不加 $）
        boolean negateAtEnd = isPatternEnd && restIsOnlyClosers(seg, close + 1, seg.length());
        switch (kind) {
            case '@' -> {
                sb.append("(?:");
                alternation(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
                sb.append(')');
            }
            case '!' -> {
                if (atPatternStart && atSegStart) {
                    sb.append(ONE_CHAR);
                }
                sb.append("(?:(?!(?:");
                alternation(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
                sb.append(negateAtEnd ? ")$)" : "))");
                sb.append(STAR).append(')');
            }
            case '+' -> {
                if (atPatternStart && atSegStart) {
                    sb.append(ONE_CHAR);
                }
                sb.append("(?:");
                alternation(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
                sb.append(")+");
            }
            case '*' -> {
                if (atPatternStart && atSegStart) {
                    sb.append(ONE_CHAR);
                }
                sb.append("(?:");
                alternation(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
                sb.append(")*");
            }
            case '?' -> {
                if (atPatternStart && atSegStart) {
                    sb.append(ONE_CHAR);
                }
                sb.append("(?:");
                alternation(seg, open + 1, close, sb, atPatternStart, isPatternEnd);
                sb.append(")?");
            }
            default -> throw new IllegalStateException("unreachable extglob kind: " + kind);
        }
        return close + 1;
    }

    /** 顶层 | 分隔翻译各备选（支持嵌套括号）。 */
    private static void alternation(String seg, int from, int to, StringBuilder sb, boolean atPatternStart, boolean isPatternEnd) {
        List<int[]> parts = splitTopLevelChar(seg, from, to, '|');
        for (int k = 0; k < parts.size(); k++) {
            if (k > 0) {
                sb.append('|');
            }
            translateRange(seg, parts.get(k)[0], parts.get(k)[1], sb, atPatternStart, isPatternEnd);
        }
    }

    /** 在 [from,to) 内按顶层字符 c 切分（括号/类/brace 深度感知）。 */
    private static List<int[]> splitTopLevelChar(String seg, int from, int to, char c) {
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char ch = seg.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == c && depth == 0) {
                parts.add(new int[]{start, i});
                start = i + 1;
            }
        }
        parts.add(new int[]{start, to});
        return parts;
    }

    /** open 位置之后是否只剩 ')' 闭合符（negate $ 判定）。 */
    private static boolean restIsOnlyClosers(String seg, int from, int to) {
        for (int i = from; i < to; i++) {
            if (seg.charAt(i) != ')') {
                return false;
            }
        }
        return true;
    }

    /** 从 open 位置找匹配闭合符（同类括号深度感知，含跨类误配防护）。 */
    private static int findMatching(String seg, int open) {
        char openC = seg.charAt(open);
        char closeC;
        if (openC == '(') {
            closeC = ')';
        } else if (openC == '[') {
            closeC = ']';
        } else if (openC == '{') {
            closeC = '}';
        } else {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == openC) {
                depth++;
            } else if (c == closeC) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String escapeLiteral(char c) {
        return switch (c) {
            case '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\', '*', '?', '-' -> "\\" + c;
            default -> String.valueOf(c);
        };
    }

    private static String escapeLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(escapeLiteral(s.charAt(i)));
        }
        return sb.toString();
    }

    /** 字符类内部转义（\ 与 ]；^ 仅非首位时不特殊）。 */
    private static String escapeClassBody(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ']') {
                sb.append('\\').append(c);
            } else if (c == '^' && i == 0) {
                sb.append("\\^");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
