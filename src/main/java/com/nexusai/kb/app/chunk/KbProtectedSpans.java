package com.nexusai.kb.app.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受保护片段识别 · 对齐 WeKnora {@code chunker.protectedSpans}（{@code splitter.go:118-205}）。
 *
 * <p><b>为什么要「保护」</b>：这些内容被切开后语义会碎（公式断成两半、代码块断开、
 * Markdown 链接被拆）。分块器把它们当作<b>原子单元</b>，不在其中切断。
 *
 * <p><b>⭐ 正则必须逐字照抄</b>（源侧 {@code splitter.go:126-134}）：
 * <pre>
 *   (?s)\$\$.*?\$\$                                              LaTeX 块级公式
 *   !\[[^\]\n]{0,200}\]\([^)\n]{1,500}\)                        Markdown 图片
 *   \[[^\]\n]{1,200}\]\([^)\n]{1,500}\)                         Markdown 链接
 *   (?m)[ ]*(?:\|[^|\n]*)+\\|[\r\n]+\s*(?:\|\s*:?-{3,}:?\s*)+\|?[\r\n]+  表格表头+分隔行
 *   (?m)[ ]*(?:\|[^|\n]*)+\\|[\r\n]+                            表格数据行
 *   (?s)```(?:\w+)?[\r\n].*?```                                 围栏代码块
 *   `[^`\r\n]+`                                                 Markdown 行内代码
 * </pre>
 *
 * <p><b>⚠️ 为什么链接/图片模式带长度上限</b>（源侧注释迁移过来）：
 * CommonMark 禁止它们跨空行；早期无上限的 {@code [^\]]*} 会让 OCR 残留的一个孤立
 * {@code [} 吞掉整段，导致「受保护片段」覆盖全文、分块彻底失效。
 *
 * <p><b>span 用 byte 偏移</b>（Java 中改用 char 偏移）：Java 的 {@link String#indexOf}
 * 与正则给出的都是 char 偏移，而「rune」在 Java 里要用 code point 计数（emoji 是 2 个 char）。
 * 本类返回的是 <b>char 偏移</b>；转换到 code point 偏移由调用方
 * {@link KbTextUnits#codePointOffset} 统一处理 —— ⛔ 不要在这两个坐标系间手工换算。
 */
final class KbProtectedSpans {

    /** 受保护区间（char 偏移，左闭右开）。 */
    record Span(int start, int end) {
    }

    /** 受保护模式（顺序与源侧一致；顺序影响同起点时的取舍）。 */
    private static final List<Pattern> PATTERNS = List.of(
        // LaTeX 块级公式
        Pattern.compile("\\$\\$.*?\\$\\$", Pattern.DOTALL),
        // Markdown 图片：单行、有界
        Pattern.compile("!\\[[^\\]\\n]{0,200}\\]\\([^)\\n]{1,500}\\)"),
        // Markdown 链接：单行、有界
        Pattern.compile("\\[[^\\]\\n]{1,200}\\]\\([^)\\n]{1,500}\\)"),
        // 表格表头 + 分隔行
        Pattern.compile("[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?[\\r\\n]+",
            Pattern.MULTILINE),
        // 表格数据行
        Pattern.compile("[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+", Pattern.MULTILINE),
        // 围栏代码块
        Pattern.compile("```(?:\\w+)?[\\r\\n].*?```", Pattern.DOTALL),
        // Markdown 行内代码
        Pattern.compile("`[^`\\r\\n]+`")
    );

    private KbProtectedSpans() {
    }

    /**
     * 找出所有<b>互不重叠</b>的受保护区间（char 偏移，已排序）。
     *
     * <p>算法逐行对齐源侧 {@code splitter.go:171-205}：
     * <ol>
     *   <li>所有模式各自 findAll，收集非空匹配</li>
     *   <li>按 (start 升序, end 降序) 排序 —— 同起点时<b>长的优先</b></li>
     *   <li>顺序扫描去重叠：只有 {@code m.start >= lastEnd} 才收，否则丢弃</li>
     * </ol>
     * ⚠️ 第 2 步的「同起点取长」很关键：`代码块` 与行内代码可能同起点，
     * 取长的才不会被短的截断。
     *
     * @param text 原文
     * @return 排序后的不重叠区间；无匹配时返回空列表
     */
    static List<Span> find(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Span> all = new ArrayList<>();
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (m.end() > m.start()) {
                    all.add(new Span(m.start(), m.end()));
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        // 源侧：a.start != b.start ? cmp(start) : cmp(b.end, a.end)（end 降序）
        all.sort(Comparator.<Span>comparingInt(Span::start)
            .thenComparing(Comparator.comparingInt(Span::end).reversed()));

        List<Span> result = new ArrayList<>(all.size());
        int lastEnd = 0;
        for (Span s : all) {
            if (s.start() >= lastEnd) {
                result.add(s);
                lastEnd = s.end();
            }
        }
        return result;
    }
}
