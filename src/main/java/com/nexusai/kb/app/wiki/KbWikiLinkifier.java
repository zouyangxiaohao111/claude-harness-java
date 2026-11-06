package com.nexusai.kb.app.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki 交叉链接注入 · W2-7（源侧 {@code wiki_linkify.go} 582 行）。
 *
 * <p><b>做什么</b>：把正文里出现的<b>已有页面名</b>包成 {@code [[slug|显示名]]}。
 *
 * <h2>⭐⭐ 五条不能省的规则（省了就会产生肉眼可见的坏链）</h2>
 * <ol>
 *   <li><b>禁用区间</b>：代码块 / 行内代码 / 已有 {@code [[...]]} / Markdown 链接 /
 *       图片 / 自动链接 里<b>绝不</b>注入 —— 插进去就破坏了原有语法。</li>
 *   <li><b>长名优先</b>：按匹配名长度<b>降序</b>处理，否则「污水处理」会先命中并
 *       阻断「污水处理设施」。</li>
 *   <li><b>只替换第一次</b>：同一页面在正文里只链一次（源侧 {@code used} 集合）。
 *       全链会让正文变成蓝色海洋，且下游渲染成本暴涨。</li>
 *   <li><b>跳过单字中文</b>：中文没有词边界，单字（「水」「风」）做子串匹配会把
 *       「风沙」「水平」也链上 —— 源侧明确跳过。</li>
 *   <li><b>跳过自链接</b>：页面链自己 = 无意义跳转。</li>
 * </ol>
 *
 * <h2>⚠️ ASCII 需要词边界，中文不需要</h2>
 * {@code RAG} 出现在 {@code STORAGE} 里不该被链上 ⇒ ASCII 首尾需检查边界；
 * 中文（CJK）没有这个概念，做子串匹配即可。
 */
public final class KbWikiLinkifier {

    private static final Logger log = LoggerFactory.getLogger(KbWikiLinkifier.class);

    /** 禁止注入的区间（半开区间 [start, end)）。 */
    private record Span(int start, int end) {
    }

    /** 一条可注入的引用。 */
    public record LinkRef(String slug, String matchText) {
    }

    private KbWikiLinkifier() {
    }

    /**
     * 注入交叉链接。
     *
     * @param content  正文
     * @param refs     可链接的页面（slug + 用于匹配的显示名）
     * @param selfSlug 当前页面的 slug（不链自己；可 null）
     * @return 注入结果（正文 + 是否有改动）
     */
    public static LinkifyResult linkify(String content, List<LinkRef> refs, String selfSlug) {
        if (content == null || content.isEmpty() || refs == null || refs.isEmpty()) {
            return new LinkifyResult(content == null ? "" : content, false);
        }

        // ── 准备：过滤 + 按匹配名长度降序（长名优先）──────────────
        List<LinkRef> sorted = new ArrayList<>();
        for (LinkRef r : refs) {
            if (r == null || r.slug() == null || r.slug().isBlank()
                || r.matchText() == null || r.matchText().isBlank()) {
                continue;
            }
            if (r.slug().equals(selfSlug)) {
                continue;   // 不链自己
            }
            if (isSingleHanChar(r.matchText())) {
                // ⭐ 中文单字无词边界 ⇒ 子串匹配会把无关词也链上
                continue;
            }
            sorted.add(r);
        }
        // stable sort：同长度保持输入顺序（结果确定性）
        sorted.sort(Comparator.comparingInt(
            (LinkRef r) -> r.matchText().codePointCount(0, r.matchText().length())).reversed());

        // ── 禁用区间 + 已用 slug ─────────────────────────────────
        Forbidden f = computeForbiddenSpans(content);
        Set<String> used = f.usedSlugs();
        boolean changed = false;

        for (LinkRef ref : sorted) {
            if (used.contains(ref.slug())) {
                continue;   // 同一页面已链过
            }
            int pos = findFirstSafeMatch(content, ref.matchText(), f.spans());
            if (pos < 0) {
                continue;
            }
            int end = pos + ref.matchText().length();
            String replacement = "[[" + ref.slug() + "|" + ref.matchText() + "]]";

            // ⚠️ 用码元下标拼接（Java 的 substring 是 UTF-16 code unit）
            content = content.substring(0, pos) + replacement + content.substring(end);

            // 区间位移 + 新链接自身成为禁用区间（否则后续 ref 会插到 [[...]] 里）
            int delta = replacement.length() - (end - pos);
            List<Span> shifted = shiftSpans(f.spans(), pos, delta);
            shifted.add(new Span(pos, pos + replacement.length()));
            shifted.sort(Comparator.comparingInt(Span::start));
            f = new Forbidden(shifted, used);
            used.add(ref.slug());
            changed = true;
        }

        if (changed) {
            log.info("[Wiki] 交叉链接注入完成 页内链接数={}", used.size());
        }
        return new LinkifyResult(content, changed);
    }

    /**
     * @param content 处理后的正文
     * @param changed 是否发生了改动
     */
    public record LinkifyResult(String content, boolean changed) {
    }

    /** 禁用区间 + 正文里已用的 slug。 */
    private record Forbidden(List<Span> spans, Set<String> usedSlugs) {
    }

    /**
     * 找出禁止注入的区间，并收集正文里<b>已有</b>的 wiki 链接 slug。
     *
     * <p>顺序扫描：遇到 {@code ```} / {@code `} / {@code [[} / {@code [} / {@code ![} /
     * {@code <} 就整段吃掉。
     */
    static Forbidden computeForbiddenSpans(String s) {
        return computeForbiddenSpansInternal(s);
    }

    private static Forbidden computeForbiddenSpansInternal(String s) {
        List<Span> spans = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        int n = s.length();
        int i = 0;

        while (i < n) {
            // ── 围栏代码块 ``` 或 ~~~
            if (isFenceStart(s, i)) {
                int runLen = fenceRun(s, i);
                char ch = s.charAt(i);
                int end = findFenceEnd(s, i + runLen, ch, runLen);
                spans.add(new Span(i, end));
                i = end;
                continue;
            }

            char c = s.charAt(i);
            switch (c) {
                case '`' -> {
                    int run = 1;
                    while (i + run < n && s.charAt(i + run) == '`') {
                        run++;
                    }
                    int close = findInlineCodeClose(s, i + run, run);
                    if (close < 0) {
                        i += run;
                        continue;
                    }
                    spans.add(new Span(i, close + run));
                    i = close + run;
                }
                case '[' -> {
                    // [[slug|name]] wiki 链接：记录 slug
                    if (i + 1 < n && s.charAt(i + 1) == '[') {
                        int close = s.indexOf("]]", i + 2);
                        if (close >= 0) {
                            int end = close + 2;
                            String inner = s.substring(i + 2, close);
                            String slug = extractWikiSlug(inner);
                            if (!slug.isEmpty()) {
                                used.add(slug);
                            }
                            spans.add(new Span(i, end));
                            i = end;
                            continue;
                        }
                    }
                    // [text](url)
                    int md = matchMarkdownLink(s, i);
                    if (md > 0) {
                        spans.add(new Span(i, md));
                        i = md;
                        continue;
                    }
                    // [text][label]
                    int refStyle = matchReferenceStyleLink(s, i);
                    if (refStyle > 0) {
                        spans.add(new Span(i, refStyle));
                        i = refStyle;
                        continue;
                    }
                    // 引用式定义行：[label]: url
                    int defEnd = matchReferenceDefinition(s, i);
                    if (defEnd > 0) {
                        spans.add(new Span(i, defEnd));
                        i = defEnd;
                        continue;
                    }
                    i++;
                }
                case '!' -> {
                    // ![alt](url)
                    if (i + 1 < n && s.charAt(i + 1) == '[') {
                        int md = matchMarkdownLink(s, i + 1);
                        if (md > 0) {
                            spans.add(new Span(i, md));
                            i = md;
                            continue;
                        }
                    }
                    i++;
                }
                case '<' -> {
                    // <https://...> 自动链接 / <tag>
                    int auto = matchAutolink(s, i);
                    if (auto > 0) {
                        spans.add(new Span(i, auto));
                        i = auto;
                        continue;
                    }
                    i++;
                }
                default -> i++;
            }
        }
        spans.sort(Comparator.comparingInt(Span::start));
        return new Forbidden(spans, used);
    }

    // ── 匹配 ──────────────────────────────────────────────────────

    /**
     * 找第一个「安全」出现位置。
     *
     * <p>「安全」= 不在禁用区间内，且（对 ASCII 首尾）有词边界。
     *
     * @return 码元下标；找不到返回 -1
     */
    static int findFirstSafeMatch(String haystack, String needle, List<Span> forbidden) {
        if (needle == null || needle.isEmpty()) {
            return -1;
        }
        boolean needsBoundary = hasAsciiWordEdge(needle);
        int start = 0;
        while (start <= haystack.length() - needle.length()) {
            int pos = haystack.indexOf(needle, start);
            if (pos < 0) {
                return -1;
            }
            int end = pos + needle.length();
            if (spanContains(forbidden, pos, end)) {
                start = pos + 1;
                continue;
            }
            if (needsBoundary && !hasWordBoundary(haystack, pos, end)) {
                start = pos + 1;
                continue;
            }
            return pos;
        }
        return -1;
    }

    /** 首或尾是 ASCII 字母/数字 ⇒ 需要词边界检查。 */
    static boolean hasAsciiWordEdge(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int first = s.codePointAt(0);
        int last = s.codePointBefore(s.length());
        return isAsciiWordRune(first) || isAsciiWordRune(last);
    }

    private static boolean isAsciiWordRune(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
    }

    /**
     * 检查词边界：两侧相邻字符不能是 ASCII 单词字符。
     *
     * <p>例：{@code STORAGE} 里的 {@code RAG} ⇒ 左侧 {@code O} 是单词字符 ⇒ 不是边界。
     */
    static boolean hasWordBoundary(String s, int pos, int end) {
        if (pos > 0) {
            int prev = s.codePointBefore(pos);
            if (isAsciiWordRune(prev)) {
                return false;
            }
        }
        if (end < s.length()) {
            int next = s.codePointAt(end);
            if (isAsciiWordRune(next)) {
                return false;
            }
        }
        return true;
    }

    /** 单个汉字（无词边界，不能做子串注入）。 */
    static boolean isSingleHanChar(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.codePointCount(0, s.length()) != 1) {
            return false;
        }
        int cp = s.codePointAt(0);
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private static boolean spanContains(List<Span> spans, int pos, int end) {
        for (Span sp : spans) {
            if (pos < sp.end() && end > sp.start()) {
                return true;   // 有交集
            }
        }
        return false;
    }

    /** 位移：start >= pivot 的区间整体平移 delta；跨越 pivot 的区间尾部延展。 */
    private static List<Span> shiftSpans(List<Span> spans, int pivot, int delta) {
        List<Span> out = new ArrayList<>(spans.size() + 1);
        for (Span sp : spans) {
            if (sp.start() >= pivot) {
                out.add(new Span(sp.start() + delta, sp.end() + delta));
            } else if (sp.end() > pivot) {
                out.add(new Span(sp.start(), sp.end() + delta));
            } else {
                out.add(sp);
            }
        }
        return out;
    }

    // ── 语法扫描 ──────────────────────────────────────────────────

    /** 行首 ``` 或 ~~~ 才算围栏开始（前面只能是行首或不超过 3 个空格）。 */
    private static boolean isFenceStart(String s, int i) {
        char ch = s.charAt(i);
        if (ch != '`' && ch != '~') {
            return false;
        }
        // 必须行首（或最多 3 空格缩进）
        int lineStart = s.lastIndexOf('\n', i - 1) + 1;
        String indent = s.substring(lineStart, i);
        if (!indent.isBlank() || indent.length() > 3) {
            return false;
        }
        // 至少 3 个连续
        return fenceRun(s, i) >= 3;
    }

    private static int fenceRun(String s, int i) {
        char ch = s.charAt(i);
        int run = 0;
        while (i + run < s.length() && s.charAt(i + run) == ch) {
            run++;
        }
        return run;
    }

    /** 找匹配的收尾围栏；找不到 ⇒ 到文末（未闭合围栏把余下全文都算禁用）。 */
    private static int findFenceEnd(String s, int from, char ch, int minLen) {
        int i = from;
        while (i < s.length()) {
            int nl = s.indexOf('\n', i);
            int lineStart = (i == 0 || s.charAt(i - 1) == '\n') ? i : (nl < 0 ? s.length() : nl + 1);
            if (lineStart >= s.length()) {
                break;
            }
            int p = lineStart;
            while (p < s.length() && s.charAt(p) == ' ') {
                p++;
            }
            if (p < s.length() && s.charAt(p) == ch && fenceRun(s, p) >= minLen) {
                int e = p + fenceRun(s, p);
                int nl2 = s.indexOf('\n', e);
                return nl2 < 0 ? s.length() : nl2 + 1;
            }
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        return s.length();
    }

    /** 找行内代码的收尾（相同长度的反引号串）。 */
    private static int findInlineCodeClose(String s, int from, int runLen) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '`') {
                int run = fenceRun(s, i);
                if (run == runLen) {
                    return i;
                }
                i += run;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * 匹配 {@code [text](url)}，返回结束下标；不匹配返回 -1。
     *
     * <p>⚠️ 括号要计数：URL 里常有 {@code ...} 之外的 {@code (}。
     */
    private static int matchMarkdownLink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        int close = findClosingBracket(s, i);
        if (close < 0) {
            return -1;
        }
        if (close + 1 >= s.length() || s.charAt(close + 1) != '(') {
            return -1;
        }
        int depth = 0;
        for (int p = close + 1; p < s.length(); p++) {
            char c = s.charAt(p);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return p + 1;
                }
            } else if (c == '\n') {
                return -1;   // 链接不跨行
            }
        }
        return -1;
    }

    /** 匹配 {@code [text][label]} / {@code [text][]}。 */
    private static int matchReferenceStyleLink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        int close = findClosingBracket(s, i);
        if (close < 0) {
            return -1;
        }
        if (close + 1 >= s.length() || s.charAt(close + 1) != '[') {
            return -1;
        }
        int end = s.indexOf(']', close + 2);
        if (end < 0) {
            return -1;
        }
        return end + 1;
    }

    /** 引用式定义行 {@code [label]: url}。 */
    private static int matchReferenceDefinition(String s, int i) {
        int lineStart = s.lastIndexOf('\n', i - 1) + 1;
        if (lineStart != i) {
            return -1;   // 必须行首
        }
        int close = findClosingBracket(s, i);
        if (close < 0) {
            return -1;
        }
        if (close + 1 >= s.length() || s.charAt(close + 1) != ':') {
            return -1;
        }
        int nl = s.indexOf('\n', close);
        return nl < 0 ? s.length() : nl + 1;
    }

    /** 找配对的 {@code ]}（支持嵌套 {@code [}）。 */
    private static int findClosingBracket(String s, int open) {
        int depth = 0;
        for (int p = open; p < s.length(); p++) {
            char c = s.charAt(p);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return p;
                }
            } else if (c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    /** 匹配 {@code <https://...>} 或 HTML 标签。 */
    private static int matchAutolink(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '<') {
            return -1;
        }
        int close = s.indexOf('>', i + 1);
        if (close < 0) {
            return -1;
        }
        String inner = s.substring(i + 1, close);
        if (inner.contains("\n")) {
            return -1;
        }
        // URL 或看起来像标签（字母开头且不含 CJK）都算
        if (inner.startsWith("http://") || inner.startsWith("https://")
            || inner.matches("^/?[A-Za-z][^<>]*$")) {
            return close + 1;
        }
        return -1;
    }

    /**
     * 从 {@code [[...]]} 的内层取 slug。
     *
     * <p>{@code [[a|b]] → "a"}；{@code [[a]] → "a"}；空 ⇒ ""。
     */
    static String extractWikiSlug(String inner) {
        if (inner == null) {
            return "";
        }
        int pipe = inner.indexOf('|');
        String slug = (pipe >= 0 ? inner.substring(0, pipe) : inner).trim();
        return slug.isEmpty() ? "" : slug;
    }
}
