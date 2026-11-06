package com.nexusai.kb.app.qa;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 查询扩展变体生成 · 对齐 WeKnora {@code chat_pipeline/query_expansion.go:expandQueries}。
 *
 * <h2>⭐⭐ 本地生成，⛔ 不调 LLM</h2>
 * 源侧注释写得很直接：变体是「本地改写」（去停用词、词序调整、关键短语抽取），
 * 目的是让<b>关键词路</b>召回长尾，而不是用一次模型调用换取更聪明的查询。
 * 一次问答里再加一次 LLM 调用会让首字延迟翻倍，收益却只在「关键词路多命中几条」。
 *
 * <h2>四种变体（与源侧逐条对应）</h2>
 * <ol>
 *   <li>去停用词后的关键词串（至少 2 个词才算）；</li>
 *   <li>引号/书名号里的短语；</li>
 *   <li>按常见分隔符切段后长度 &gt; 5 的片段；</li>
 *   <li>去掉疑问词前缀后的整句。</li>
 * </ol>
 * 去重后上限 <b>5</b> 个，长度 &lt; 3 的丢弃。
 *
 * <h2>⚠️ 中文分词</h2>
 * 源侧用 jieba 的 {@code CutForSearch}。本仓没有 jieba，用 <b>bigram</b> 近似
 * （与合并阶段的相似度判定同一取舍，见 {@code KbMergeText}）。
 * 影响：停用词剔除的粒度更粗，变体质量不如源侧 —— 已登记，不假装等价。
 */
final class KbQueryExpansion {

    private KbQueryExpansion() {
    }

    /** 变体数量上限（源侧硬编码 5）。 */
    static final int MAX_VARIANTS = 5;

    /** 变体最短长度（码点）。 */
    private static final int MIN_LEN = 3;

    /** 中文/英文常见停用词（源侧 {@code stopwords} 的精简集）。 */
    private static final Set<String> STOPWORDS = Set.of(
        "的", "是", "在", "了", "和", "与", "或",
        "a", "an", "the", "is", "are", "was", "were", "be", "been",
        "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
        "what", "how", "why", "when", "where", "which", "who");

    /** 中文疑问词前缀（源侧 {@code questionWords}）。 */
    private static final Pattern QUESTION_WORDS = Pattern.compile(
        "^(什么是|什么|如何|怎么|怎样|为什么|为何|哪个|哪些|谁|何时|何地|请问|请告诉我|帮我|我想知道|我想了解)");

    /** 引号 / 书名号包住的短语。 */
    private static final Pattern QUOTED = Pattern.compile(
        "[\"'“”「」『』]([^\"'“”「」『』]+)[\"'“”「」『』]");

    /** 常见分隔符。 */
    private static final Pattern DELIMITERS = Pattern.compile("[,，;；、。！？!?\\s]+");

    /**
     * 生成扩展变体。
     *
     * @param queries 已有查询（改写后 + 原问题）；这些本身不会再作为变体
     * @return 变体列表（恒非 null；可能为空）
     */
    static List<String> expand(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        String primary = null;
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            String trimmed = q.strip();
            seen.add(trimmed.toLowerCase(Locale.ROOT));
            if (primary == null) {
                primary = trimmed;
            }
        }
        if (primary == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(MAX_VARIANTS);

        // ① 去停用词的关键词串
        List<String> keywords = extractKeywords(primary);
        if (keywords.size() >= 2) {
            add(out, seen, String.join(" ", keywords));
        }

        // ② 引号/书名号短语
        var m = QUOTED.matcher(primary);
        while (m.find() && out.size() < MAX_VARIANTS) {
            String phrase = m.group(1);
            if (phrase != null && runeLen(phrase) > 2) {
                add(out, seen, phrase);
            }
        }

        // ③ 分隔符切段（> 5 码点）
        for (String seg : DELIMITERS.split(primary)) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            String s = seg == null ? "" : seg.strip();
            if (runeLen(s) > 5) {
                add(out, seen, s);
            }
        }

        // ④ 去掉疑问词
        String cleaned = QUESTION_WORDS.matcher(primary).replaceFirst("").strip();
        if (!cleaned.equals(primary)) {
            add(out, seen, cleaned);
        }

        if (out.size() > MAX_VARIANTS) {
            return List.copyOf(out.subList(0, MAX_VARIANTS));
        }
        return List.copyOf(out);
    }

    /** 去重 + 长度检查后加入。 */
    private static void add(List<String> out, Set<String> seen, String candidate) {
        if (candidate == null || out.size() >= MAX_VARIANTS) {
            return;
        }
        String s = candidate.strip();
        if (runeLen(s) < MIN_LEN) {
            return;
        }
        if (!seen.add(s.toLowerCase(Locale.ROOT))) {
            return;
        }
        out.add(s);
    }

    /**
     * 提取关键词（去停用词）。
     *
     * <p>分词策略：中文连续段按 <b>bigram</b> 切（源侧用 jieba），非中文按空白/标点切词。
     * bigram 的代价是「覆盖率要求」这类词会被切成「覆盖」「盖率」两个片段 ——
     * 变体召回质量不如源侧，但比整句匹配强。
     *
     * @param text 原文
     * @return 关键词列表（保持出现顺序、去重）
     */
    static List<String> extractKeywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder latin = new StringBuilder();
        List<Integer> hanBuffer = new ArrayList<>();

        Runnable flushLatin = () -> {
            if (latin.length() == 0) {
                return;
            }
            String w = latin.toString();
            latin.setLength(0);
            if (!STOPWORDS.contains(w.toLowerCase(Locale.ROOT))) {
                out.add(w);
            }
        };
        Runnable flushHan = () -> {
            if (hanBuffer.isEmpty()) {
                return;
            }
            int[] cps = hanBuffer.stream().mapToInt(Integer::intValue).toArray();
            hanBuffer.clear();
            if (cps.length == 1) {
                String w = new String(cps, 0, 1);
                if (!STOPWORDS.contains(w)) {
                    out.add(w);
                }
                return;
            }
            // bigram：两个相邻汉字的片段
            for (int i = 0; i + 1 < cps.length; i++) {
                String w = new String(cps, i, 2);
                if (!STOPWORDS.contains(w)) {
                    out.add(w);
                }
            }
        };

        for (int cp : text.codePoints().toArray()) {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flushLatin.run();
                hanBuffer.add(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushHan.run();
                latin.appendCodePoint(cp);
            } else {
                flushLatin.run();
                flushHan.run();
            }
        }
        flushLatin.run();
        flushHan.run();
        return List.copyOf(out);
    }

    /** 码点数（与源侧 {@code utf8.RuneCountInString} 对齐）。 */
    private static int runeLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
