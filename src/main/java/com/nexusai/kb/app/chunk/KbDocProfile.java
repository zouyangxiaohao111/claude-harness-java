package com.nexusai.kb.app.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档画像 · 对齐 WeKnora {@code chunker.DocProfile} / {@code ProfileDocument}
 * （{@code profiler.go:17-200}）。
 *
 * <p><b>为什么要先画像再切分</b>（源侧 {@code profiler.go:1-5}）：一趟廉价的扫描
 * （几个正则 + 数 rune）就能判断「这份文档有没有 Markdown 标题骨架 / 有没有编号章节 /
 * 有没有分页符」，从而决定走 Tier1 还是 Tier2。没有画像就只能一刀切用递归分隔符，
 * 结构化文档的章节会被拦腰切断。
 *
 * <p><b>19 个字段逐个对应源侧 struct tag</b>（见各组件 JavaDoc），
 * 这些名字是<b>预览接口的公开线缆格式</b>，⛔ 不要改。
 *
 * <p><b>⚠️ 本类同时承载 {@code patterns.go} 的正则常量</b>：
 * 源侧它们是独立文件 {@code patterns.go}，本批文件清单不允许新增
 * {@code KbChunkPatterns}，而画像与两个 splitter 必须用<b>同一套</b>正则
 * （两处不一致会出现「画像说有 10 个标题，heading splitter 一个都没找到」的静默错位）。
 * ⇒ 统一放在此处的「patterns.go 区」，逐字照抄源侧 {@code patterns.go:24-60}。
 */
public record KbDocProfile(
    /** 总 code point 数（源侧 {@code total_chars}，{@code profiler.go:96}）。 */
    int totalChars,
    /** 按 {@code \n} 切出的行数（源侧 {@code total_lines}，{@code :99-100}）。 */
    int totalLines,
    /** 行长均值（源侧 {@code avg_line_len}，{@code :150-155}）。代码块行不计入。 */
    double avgLineLen,
    /** 行长标准差（源侧 {@code std_line_len}，{@code :156-162}）。 */
    double stdLineLen,
    /** 各级标题计数：level(1..6) → 次数（源侧 {@code md_heading_counts}）。 */
    Map<Integer, Integer> mdHeadingCounts,
    /** 标题总数（源侧 {@code md_heading_total}）。 */
    int mdHeadingTotal,
    /** 编号段落数，如 {@code 1. Intro} / {@code 2.3 Methods}（源侧 {@code numbered_section_count}）。 */
    int numberedSectionCount,
    /** 短全大写行数（源侧 {@code all_caps_short_line_count}）。 */
    int allCapsShortLineCount,
    /** 连续 3 个换行的出现次数（源侧 {@code blank_paragraph_breaks}，{@code :169}）。 */
    int blankParagraphBreaks,
    /** 换页符 {@code \f} 个数（源侧 {@code form_feed_count}，{@code :97}）。 */
    int formFeedCount,
    /** 视觉分隔线（{@code ---} / {@code ===} / {@code ***} / {@code ___}）行数（源侧 {@code visual_sep_count}）。 */
    int visualSepCount,
    /** 德语章节标记数（{@code Kapitel 3}，源侧 {@code german_chapter_count}）。 */
    int germanChapterCount,
    /** 英语章节标记数（{@code Chapter 3}，源侧 {@code english_chapter_count}）。 */
    int englishChapterCount,
    /** 中文章节标记数（{@code 第三章}，源侧 {@code chinese_chapter_count}）。 */
    int chineseChapterCount,
    /** 页脚 {@code Page 3 of 9} 行数（源侧 {@code repeated_footer_count}）。 */
    int repeatedFooterCount,
    /** 是否含 Markdown 表格行（源侧 {@code has_tables}，{@code :145-147}）。 */
    boolean hasTables,
    /** 是否含围栏代码块（源侧 {@code has_code}，{@code :109-115}）。 */
    boolean hasCode,
    /** 代码块字符占比（源侧 {@code code_ratio}，{@code :165-167}）。 */
    double codeRatio,
    /** 探测到的语言（源侧 {@code detected_langs}；mixed 时给三种，{@code :177-182}）。 */
    List<String> detectedLangs
) {

    public KbDocProfile {
        mdHeadingCounts = mdHeadingCounts == null
            ? Map.of() : Map.copyOf(mdHeadingCounts);
        detectedLangs = detectedLangs == null ? List.of() : List.copyOf(detectedLangs);
    }

    // ════════════════════════════════════════════════════════════════════
    // patterns.go 区 · 逐字照抄 WeKnora {@code patterns.go:11-97}
    // ════════════════════════════════════════════════════════════════════

    /** 边界优先级 · 换页符（源侧 {@code PrioFormFeed}，{@code patterns.go:13}）。 */
    static final int PRIO_FORM_FEED = 100;

    /** 边界优先级 · 编号标题（源侧 {@code PrioNumberedHead}，{@code :14}）。 */
    static final int PRIO_NUMBERED_HEAD = 90;

    /** 边界优先级 · 章节标记（源侧 {@code PrioChapterMarker}，{@code :15}）。 */
    static final int PRIO_CHAPTER_MARKER = 85;

    /** 边界优先级 · 全大写标题（源侧 {@code PrioAllCapsHeading}，{@code :16}）。 */
    static final int PRIO_ALL_CAPS_HEADING = 70;

    /** 边界优先级 · 视觉分隔线（源侧 {@code PrioVisualSep}，{@code :17}）。 */
    static final int PRIO_VISUAL_SEP = 60;

    /** 边界优先级 · 页脚（源侧 {@code PrioPageFooter}，{@code :18}）。 */
    static final int PRIO_PAGE_FOOTER = 50;

    /** 边界优先级 · 连续空行（源侧 {@code PrioBlankBlock}，{@code :19}）。 */
    static final int PRIO_BLANK_BLOCK = 40;

    /**
     * ATX 标题（源侧 {@code MarkdownHeadingPattern}，{@code patterns.go:24}）：
     * {@code (?m)^(#{1,6})\s+(.+?)\s*#*\s*$}。组 1 = 井号，组 2 = 标题文本。
     */
    static final Pattern MARKDOWN_HEADING = Pattern.compile(
        "^(#{1,6})\\s+(.+?)\\s*#*\\s*$", Pattern.MULTILINE);

    /** 换页符（源侧 {@code FormFeedPattern}，{@code :28}）。 */
    static final Pattern FORM_FEED = Pattern.compile("\\f");

    /**
     * 编号段落（源侧 {@code NumberedSectionPattern}，{@code :34}）：
     * {@code 1. Intro} / {@code 2.3 Methods} / {@code IV. Results} / {@code 2.2.1 用户与权限}。
     * 多级数字后的句点是可选的。
     */
    static final Pattern NUMBERED_SECTION = Pattern.compile(
        "^[ \\t]*(?:\\d+(?:\\.\\d+){1,3}\\.?|(?:\\d+|[IVX]{1,5})\\.)[ \\t]+\\S.{0,200}$",
        Pattern.MULTILINE);

    /** 短全大写标题行（源侧 {@code AllCapsHeadingPattern}，{@code :39}）。 */
    static final Pattern ALL_CAPS_HEADING = Pattern.compile(
        "^[ \\t]*([A-ZÄÖÜ][A-ZÄÖÜ \\-]{3,80}):?\\s*$", Pattern.MULTILINE);

    /** 视觉分隔线（源侧 {@code VisualSeparatorPattern}，{@code :43}）。 */
    static final Pattern VISUAL_SEPARATOR = Pattern.compile(
        "^[ \\t]*(?:-{3,}|={3,}|\\*{3,}|_{3,})[ \\t]*$", Pattern.MULTILINE);

    /** 三个及以上连续换行（源侧 {@code ExcessiveBlanksPattern}，{@code :47}）。 */
    static final Pattern EXCESSIVE_BLANKS = Pattern.compile("\\n{3,}");

    /** 页脚 {@code Seite X von Y} / {@code Page X of Y}（源侧 {@code PageFooterPattern}，{@code :50}）。 */
    static final Pattern PAGE_FOOTER = Pattern.compile(
        "^[ \\t]*(?:Seite|Page|页码?)\\s+\\d+(?:\\s*(?:von|of|/)\\s*\\d+)?[ \\t]*$",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 德语章节标记（源侧 {@code GermanChapterPattern}，{@code :53}）。 */
    static final Pattern GERMAN_CHAPTER = Pattern.compile(
        "^[ \\t]*(?:Kapitel|Abschnitt|Teil)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$",
        Pattern.MULTILINE);

    /** 英语章节标记（源侧 {@code EnglishChapterPattern}，{@code :56}）。 */
    static final Pattern ENGLISH_CHAPTER = Pattern.compile(
        "^[ \\t]*(?:Chapter|Section|Part)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$",
        Pattern.MULTILINE);

    /** 中文章节标记 {@code 第一章} / {@code 第3节}（源侧 {@code ChineseChapterPattern}，{@code :60}）。 */
    static final Pattern CHINESE_CHAPTER = Pattern.compile(
        "^[ \\t]*第[ \\t]*[一二三四五六七八九十百千零〇0-9]+[ \\t]*(?:章|节|節|部分|篇)[ \\t]?.{0,200}$",
        Pattern.MULTILINE);

    /**
     * 按标题语言取章节正则 · 对齐源侧 {@code ChapterPatternsForLangs}（{@code patterns.go:78-97}）。
     *
     * <p>空 / 未知语言列表 ⇒ 三种全给（自动探测的文档三种都可能命中）。
     *
     * @param langs 语言提示
     * @return 适用的章节正则（非空）
     */
    static List<Pattern> chapterPatternsForLangs(List<String> langs) {
        if (langs == null || langs.isEmpty()) {
            return List.of(GERMAN_CHAPTER, ENGLISH_CHAPTER, CHINESE_CHAPTER);
        }
        List<Pattern> out = new ArrayList<>(3);
        for (String lang : langs) {
            if (KbTokenEstimator.LANG_GERMAN.equals(lang)) {
                out.add(GERMAN_CHAPTER);
            } else if (KbTokenEstimator.LANG_ENGLISH.equals(lang)) {
                out.add(ENGLISH_CHAPTER);
            } else if (KbTokenEstimator.LANG_CHINESE.equals(lang)) {
                out.add(CHINESE_CHAPTER);
            }
        }
        if (out.isEmpty()) {
            return List.of(GERMAN_CHAPTER, ENGLISH_CHAPTER, CHINESE_CHAPTER);
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════
    // 派生指标 · 对齐 profiler.go:47-85
    // ════════════════════════════════════════════════════════════════════

    /**
     * 标题密度 = 标题数 / 总行数（源侧 {@code HeadingDensity}，{@code profiler.go:48-53}）。
     *
     * @return 密度；无行时 0
     */
    public double headingDensity() {
        if (totalLines == 0) {
            return 0;
        }
        return (double) mdHeadingTotal / (double) totalLines;
    }

    /**
     * 主导标题层级（源侧 {@code DominantHeadingLevel}，{@code profiler.go:63-78}）。
     *
     * <p>优先序：
     * <ol>
     *   <li>最浅的、出现 ≥3 次的层级 —— 这才是文档真正的结构骨架</li>
     *   <li>否则最深的、至少出现一次的层级 —— 让「H1 + 几个 H2」的小文档也能切细一点</li>
     * </ol>
     *
     * @return 1..6；无标题 0
     */
    public int dominantHeadingLevel() {
        if (mdHeadingTotal == 0) {
            return 0;
        }
        for (int level = 1; level <= 6; level++) {
            if (mdHeadingCounts.getOrDefault(level, 0) >= 3) {
                return level;
            }
        }
        for (int level = 6; level >= 1; level--) {
            if (mdHeadingCounts.getOrDefault(level, 0) > 0) {
                return level;
            }
        }
        return 0;
    }

    /**
     * 启发式标记总数（源侧 {@code HeuristicMarkerTotal}，{@code profiler.go:81-85}）。
     *
     * <p>刻意<b>不含</b> {@code RepeatedFooterCount}（源侧同样不含）——
     * 页脚是「重复噪音」，单独不足以证明文档有章节结构。
     *
     * @return 标记总数
     */
    public int heuristicMarkerTotal() {
        return numberedSectionCount
            + germanChapterCount + englishChapterCount + chineseChapterCount
            + allCapsShortLineCount + visualSepCount + formFeedCount;
    }

    /**
     * 第 {@code level} 级标题数。
     *
     * @param level 1..6
     * @return 次数
     */
    public int headingCount(int level) {
        return mdHeadingCounts.getOrDefault(level, 0);
    }

    /**
     * 画像主入口 · 对齐源侧 {@code ProfileDocument}（{@code profiler.go:88-185}）。
     *
     * <p>一趟扫描（源侧同一趟）：
     * <ol>
     *   <li>先统计总字符、换页符、行数</li>
     *   <li>逐行：围栏代码块内的行<b>跳过后面的标记检测</b>（代码里的 {@code # } 不是标题），
     *       但计入 {@code codeChars} 用于 {@code code_ratio}</li>
     *   <li>非代码行：记行长 → 标题 → 编号 → 三语章节标记 → 全大写 → 分隔线 → 页脚 → 表格</li>
     *   <li>算行长均值/标准差、代码占比、连续空行数、语言采样</li>
     * </ol>
     *
     * @param text 原文
     * @return 画像（永不为 null）
     */
    public static KbDocProfile profile(String text) {
        Map<Integer, Integer> headingCounts = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return new KbDocProfile(0, 0, 0.0, 0.0, headingCounts, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, 0.0, List.of());
        }

        int totalChars = KbTextUnits.codePointLength(text);
        int formFeedCount = countMatches(FORM_FEED, text);
        String[] lines = text.split("\n", -1);
        int totalLines = lines.length;

        // ── 第一遍：逐行标记 + 行长统计 ────────────────────────────────
        long[] lengths = new long[totalLines];
        int lengthCount = 0;
        KbMarkdownFence fence = new KbMarkdownFence();
        int codeChars = 0;
        boolean hasCode = false;
        boolean hasTables = false;
        int mdHeadingTotal = 0;
        int numbered = 0;
        int german = 0;
        int english = 0;
        int chinese = 0;
        int allCaps = 0;
        int visualSep = 0;
        int footers = 0;

        for (String line : lines) {
            KbMarkdownFence.ConsumeResult fr = fence.consume(line);
            if (fr.code()) {
                hasCode = true;
                if (!fr.delimiter()) {
                    codeChars += KbTextUnits.codePointLength(line);
                }
                continue;
            }

            int runeLen = KbTextUnits.codePointLength(line);
            lengths[lengthCount++] = runeLen;

            Matcher hm = MARKDOWN_HEADING.matcher(line);
            if (hm.find()) {
                int level = hm.group(1).length();
                if (level >= 1 && level <= 6) {
                    headingCounts.merge(level, 1, Integer::sum);
                    mdHeadingTotal++;
                }
                // 源侧：命中标题即 continue（不再计入编号/章节等标记）
                continue;
            }
            if (NUMBERED_SECTION.matcher(line).find()) {
                numbered++;
            }
            if (GERMAN_CHAPTER.matcher(line).find()) {
                german++;
            }
            if (ENGLISH_CHAPTER.matcher(line).find()) {
                english++;
            }
            if (CHINESE_CHAPTER.matcher(line).find()) {
                chinese++;
            }
            if (ALL_CAPS_HEADING.matcher(line).find()) {
                allCaps++;
            }
            if (VISUAL_SEPARATOR.matcher(line).find()) {
                visualSep++;
            }
            if (PAGE_FOOTER.matcher(line).find()) {
                footers++;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                hasTables = true;
            }
        }

        // ── 行长均值 / 标准差（源侧 :150-163）──────────────────────────
        double avg = 0.0;
        double std = 0.0;
        if (lengthCount > 0) {
            double sum = 0;
            for (int i = 0; i < lengthCount; i++) {
                sum += lengths[i];
            }
            avg = sum / lengthCount;
            double variance = 0;
            for (int i = 0; i < lengthCount; i++) {
                double d = lengths[i] - avg;
                variance += d * d;
            }
            variance /= lengthCount;
            std = Math.sqrt(variance);
        }

        double codeRatio = totalChars > 0 ? (double) codeChars / (double) totalChars : 0.0;
        // ⚠️ 口径必须与源侧 strings.Count(text, "\n\n\n") 一致：<b>字面量非重叠</b>计数。
        //    "\n\n\n\n\n\n" 在 Go 里得 2；用正则 \n{3,} 只得 1（整段算一个 run）⇒ 会少报。
        int blankParagraphBreaks = countLiteralNonOverlapping(text, "\n\n\n");

        // ── 语言采样（源侧 :173-182）──────────────────────────────────
        String lang = KbTokenEstimator.detectLanguage(KbTokenEstimator.languageSample(text));
        List<String> detectedLangs = KbTokenEstimator.LANG_MIXED.equals(lang)
            ? List.of(KbTokenEstimator.LANG_ENGLISH, KbTokenEstimator.LANG_GERMAN,
                KbTokenEstimator.LANG_CHINESE)
            : List.of(lang);

        return new KbDocProfile(totalChars, totalLines, avg, std, headingCounts, mdHeadingTotal,
            numbered, allCaps, blankParagraphBreaks, formFeedCount, visualSep,
            german, english, chinese, footers,
            hasTables, hasCode, codeRatio, detectedLangs);
    }

    /**
     * 选策略链 · 对齐源侧 {@code SelectStrategy}（{@code profiler.go:215-236}）。
     *
     * <p>判据逐条照抄：
     * <ul>
     *   <li>Tier1：{@code mdHeadingTotal >= 3 && 密度 > 0.005 && 主导层级 > 0}</li>
     *   <li>Tier2：{@code heuristicMarkerTotal >= 5} 或有换页符 或 有任一章节标记</li>
     *   <li>Tier3：无条件追加 legacy（兜底，保证总有结果）</li>
     * </ul>
     *
     * @param p 画像（null ⇒ 只有 legacy）
     * @return 有序策略链
     */
    public static List<KbStrategyTier> selectStrategy(KbDocProfile p) {
        if (p == null) {
            return List.of(KbStrategyTier.LEGACY);
        }
        List<KbStrategyTier> chain = new ArrayList<>(3);
        if (p.mdHeadingTotal >= 3 && p.headingDensity() > 0.005 && p.dominantHeadingLevel() > 0) {
            chain.add(KbStrategyTier.HEADING);
        }
        if (p.heuristicMarkerTotal() >= 5
            || p.formFeedCount > 0
            || p.germanChapterCount + p.englishChapterCount + p.chineseChapterCount > 0) {
            chain.add(KbStrategyTier.HEURISTIC);
        }
        chain.add(KbStrategyTier.LEGACY);
        return chain;
    }

    // ── 内部辅助 ────────────────────────────────────────────────────

    /** 数匹配次数（源侧 {@code strings.Count} 的等价物，用正则版）。 */
    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }

    /**
     * {@code strings.Count} 的等价物：<b>字面量子串非重叠</b>计数。
     *
     * <p>⚠️ 与 {@code Pattern} 的差别：{@code \n{3,}} 会把一整段连续换行算作 1 次，
     * 而 Go 的 {@code strings.Count("\n\n\n\n\n\n", "\n\n\n")} = 2（每 3 个算一次）。
     */
    private static int countLiteralNonOverlapping(String text, String literal) {
        if (text == null || text.isEmpty() || literal.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(literal, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + literal.length();
        }
    }

    /**
     * 围栏代码块跟踪器 · 对齐源侧 {@code markdownFence}（{@code fenced_code.go:1-53}）。
     *
     * <p><b>为什么需要它</b>：Markdown 代码块里的 {@code # 注释}、{@code 1. 数字列表}、
     * {@code ---} 分隔线全都<b>不是</b>文档结构。不屏蔽它们，画像会误判「这是一篇高度结构化的文档」，
     * 进而选错策略。
     *
     * <p>⚠️ 只跟踪顶层反引号 / 波浪号围栏，<b>不</b>解析列表/引用等容器块（源侧同样不解析）。
     */
    static final class KbMarkdownFence {

        /** 当前围栏标记字符（0 = 不在围栏内）。 */
        private char marker;
        /** 当前围栏的标记长度（&gt;=3）。 */
        private int length;

        /**
         * 消费一行。
         *
         * @param line 行（可含尾部 {@code \r}）
         * @return 是否属于代码块 + 是否围栏分隔行本身
         */
        ConsumeResult consume(String line) {
            boolean inside = length > 0;
            String s = line == null ? "" : line;
            // 扫描器按 LF 切行；CRLF 文档需去掉行尾 CR（源侧同样处理）
            if (s.endsWith("\r")) {
                s = s.substring(0, s.length() - 1);
            }
            int indent = 0;
            while (indent < s.length() && s.charAt(indent) == ' ') {
                indent++;
            }
            if (indent > 3 || indent == s.length()) {
                return new ConsumeResult(inside, false);
            }
            String body = s.substring(indent);
            char m = body.charAt(0);
            if (m != '`' && m != '~') {
                return new ConsumeResult(inside, false);
            }
            int runLen = 0;
            while (runLen < body.length() && body.charAt(runLen) == m) {
                runLen++;
            }
            if (runLen < 3) {
                return new ConsumeResult(inside, false);
            }
            String rest = body.substring(runLen);
            if (inside) {
                if (m == marker && runLen >= length && rest.strip().isEmpty()) {
                    marker = 0;
                    length = 0;
                    return new ConsumeResult(true, true);
                }
                return new ConsumeResult(true, false);
            }
            // 反引号围栏的 info string 里不能再有反引号
            if (m == '`' && rest.indexOf('`') >= 0) {
                return new ConsumeResult(false, false);
            }
            marker = m;
            length = runLen;
            return new ConsumeResult(true, true);
        }

        /**
         * 消费结果。
         *
         * @param code      该行是否属于代码块（含分隔行）
         * @param delimiter 该行是否是围栏分隔行本身
         */
        record ConsumeResult(boolean code, boolean delimiter) {
        }
    }
}
