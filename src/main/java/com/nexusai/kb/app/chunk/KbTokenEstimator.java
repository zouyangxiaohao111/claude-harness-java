package com.nexusai.kb.app.chunk;

/**
 * 语言感知的 token 粗估 · 对齐 WeKnora {@code chunker/tokens.go:1-159}。
 *
 * <p><b>为什么不引 tokenizer</b>（源侧 {@code tokens.go:3-6} 原文）：不引 tiktoken 之类的依赖，
 * 改用「每语言 chars/token 比值」估算。数值<b>刻意保守</b>（略高估 token 数），
 * 让分块稳定落在模型上限之内。
 *
 * <p><b>三个用途</b>：
 * <ol>
 *   <li>{@link #charsForTokenLimit(int, String)} —— 把 {@code token_limit} 换成字符预算，
 *       用于「配置了 token 上限就自动收缩 chunkSize」（{@code strategy.go:338-347}）</li>
 *   <li>{@link #detectLanguage(String)} —— 粗粒度语言判定，只用于启发式派发，
 *       ⛔ <b>不是</b>正经语言识别</li>
 *   <li>{@link #approxTokenCount(String, String)} —— 预览接口给每块报「约多少 token」</li>
 * </ol>
 *
 * <p><b>⚠️ 与源站的偏差（有意，已核实）</b>：
 * <ul>
 *   <li>{@code hasGermanWords} 的 {@code sample} 上限在源侧是 <b>512 字节</b>（{@code s[:sample]} 切字节），
 *       Java 版按 <b>512 个 char</b> 截断 —— 中文里 char 与 byte 差 3 倍，
 *       但该方法只用于「德语停用词探测」，中文文本不会命中德语词，行为无差异。</li>
 *   <li>{@code containsLower} 在源侧是手写的字节级比较（只对 ASCII 大小写折叠）；
 *       Java 版用 {@link String#regionMatches(boolean, int, String, int, int)} 忽略大小写，
 *       对德语小写停用词等价。</li>
 * </ul>
 */
public final class KbTokenEstimator {

    /** 英语（源侧 {@code LangEnglish}）。 */
    public static final String LANG_ENGLISH = "en";

    /** 德语（源侧 {@code LangGerman}）。 */
    public static final String LANG_GERMAN = "de";

    /** 中文（源侧 {@code LangChinese}）。 */
    public static final String LANG_CHINESE = "zh";

    /** 混合 / 未知（源侧 {@code LangMixed}）。 */
    public static final String LANG_MIXED = "mixed";

    /** 语言探测只看前 4096 字符（源侧 {@code profiler.go:173-176} 的 sample 上限）。 */
    private static final int LANG_SAMPLE_CHARS = 4096;

    /** 德语停用词探测只看前 512（源侧 {@code tokens.go:111}）。 */
    private static final int GERMAN_SAMPLE_CHARS = 512;

    /**
     * 每语言 chars/token 比值（源侧 {@code tokens.go:24-29} 逐字照抄）。
     *
     * <p>数值偏保守，让估算略微高估 token 数。
     */
    private static final double EN_CHARS_PER_TOKEN = 4.0;
    private static final double DE_CHARS_PER_TOKEN = 4.5;
    private static final double ZH_CHARS_PER_TOKEN = 1.7;
    private static final double MIXED_CHARS_PER_TOKEN = 3.0;

    /** 安全系数 0.9 —— 宁可低于模型上限，不要超（源侧 {@code tokens.go:157}）。 */
    private static final double SAFETY_FACTOR = 0.9;

    private KbTokenEstimator() {
    }

    /**
     * 取语言的 chars/token 比值 · 未知语言回落 mixed（源侧 {@code tokens.go:49-52} / {@code :153-156}）。
     *
     * @param lang 语言标识
     * @return 比值
     */
    private static double charsPerToken(String lang) {
        if (lang == null) {
            return MIXED_CHARS_PER_TOKEN;
        }
        return switch (lang) {
            case LANG_ENGLISH -> EN_CHARS_PER_TOKEN;
            case LANG_GERMAN -> DE_CHARS_PER_TOKEN;
            case LANG_CHINESE -> ZH_CHARS_PER_TOKEN;
            default -> MIXED_CHARS_PER_TOKEN;
        };
    }

    /**
     * 粗估 token 数 · 对齐源侧 {@code ApproxTokenCount}（{@code tokens.go:33-38}）。
     *
     * @param s    文本
     * @param lang 语言（空 / 未知 → mixed）
     * @return token 估算（空文本 0；非空至少 1）
     */
    public static int approxTokenCount(String s, String lang) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return approxTokenCountFromRuneLen(KbTextUnits.codePointLength(s), lang);
    }

    /**
     * 已知 rune 长度时的估算版 · 对齐源侧 {@code ApproxTokenCountFromRuneLen}（{@code tokens.go:45-58}）。
     *
     * <p>热路径用：预览接口每块只算一次 rune 长度，再复用。
     *
     * @param runeLen code point 数
     * @param lang    语言
     * @return token 估算
     */
    public static int approxTokenCountFromRuneLen(int runeLen, String lang) {
        if (runeLen <= 0) {
            return 0;
        }
        double approx = (double) runeLen / charsPerToken(lang);
        if (approx < 1) {
            return 1;
        }
        // Go: int(approx + 0.5) —— 四舍五入
        return (int) (approx + 0.5);
    }

    /**
     * 探测语言 · 对齐源侧 {@code DetectLanguage}（{@code tokens.go:64-97}）。
     *
     * <p>判据（逐条照抄）：
     * <ol>
     *   <li>统计 CJK（汉字/谚文/平假名/片假名）与拉丁字母数</li>
     *   <li>两种文字<b>各有 ≥15%</b> ⇒ {@code mixed}</li>
     *   <li>CJK 占比 &gt; 30% ⇒ {@code zh}</li>
     *   <li>有德语变音符或德语停用词 ⇒ {@code de}</li>
     *   <li>否则 {@code en}</li>
     * </ol>
     *
     * @param s 文本（调用方可只传前 4096 字符）
     * @return {@code en} / {@code de} / {@code zh} / {@code mixed}
     */
    public static String detectLanguage(String s) {
        if (s == null || s.isEmpty()) {
            return LANG_MIXED;
        }
        int cjk = 0;
        int latin = 0;
        int umlaut = 0;
        int n = s.length();
        for (int i = 0; i < n; ) {
            int cp = s.codePointAt(i);
            int width = Character.charCount(cp);
            i += width;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA) {
                cjk++;
            } else if (isGermanUmlaut(cp)) {
                umlaut++;
                latin++;
            } else if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                latin++;
            }
        }
        int total = cjk + latin;
        if (total == 0) {
            return LANG_MIXED;
        }
        double cjkRatio = (double) cjk / (double) total;
        double latinRatio = (double) latin / (double) total;
        if (cjkRatio >= 0.15 && latinRatio >= 0.15) {
            return LANG_MIXED;
        }
        if (cjkRatio > 0.3) {
            return LANG_CHINESE;
        }
        if (umlaut > 0 || hasGermanWords(s)) {
            return LANG_GERMAN;
        }
        return LANG_ENGLISH;
    }

    /**
     * token 上限 → 字符预算 · 对齐源侧 {@code CharsForTokenLimit}（{@code tokens.go:149-159}）。
     *
     * <p>{@code int(tokens * ratio * 0.9)} —— 向下取整，确保不超模型窗口。
     *
     * @param tokens token 上限（&lt;=0 ⇒ 返回 0，表示「没有 token 约束」）
     * @param lang   语言
     * @return 字符预算（0 = 无约束）
     */
    public static int charsForTokenLimit(int tokens, String lang) {
        if (tokens <= 0) {
            return 0;
        }
        return (int) ((double) tokens * charsPerToken(lang) * SAFETY_FACTOR);
    }

    /**
     * 语言探测用的采样窗口（前 4096 字符）· 对齐源侧 {@code profiler.go:173-176}。
     *
     * @param text 全文
     * @return 采样
     */
    static String languageSample(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= LANG_SAMPLE_CHARS) {
            return text;
        }
        // 不在代理对中间切开
        int cut = LANG_SAMPLE_CHARS;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut);
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 德语变音符 · 对齐源侧 {@code isGermanUmlaut}（{@code tokens.go:99-105}）。 */
    private static boolean isGermanUmlaut(int cp) {
        return cp == 'ä' || cp == 'ö' || cp == 'ü'
            || cp == 'Ä' || cp == 'Ö' || cp == 'Ü'
            || cp == 'ß';
    }

    /** 德语停用词探测 · 对齐源侧 {@code hasGermanWords}（{@code tokens.go:110-121}）。 */
    private static boolean hasGermanWords(String s) {
        String sample = s.length() > GERMAN_SAMPLE_CHARS ? s.substring(0, GERMAN_SAMPLE_CHARS) : s;
        String[] words = {" der ", " die ", " das ", " und ", " ist ", " nicht ", " mit ", " auf "};
        for (String w : words) {
            if (containsIgnoreCaseAscii(sample, w)) {
                return true;
            }
        }
        return false;
    }

    /** 忽略 ASCII 大小写的包含判断（源侧 {@code containsLower}，{@code tokens.go:123-144}）。 */
    private static boolean containsIgnoreCaseAscii(String haystack, String needle) {
        if (haystack.length() < needle.length()) {
            return false;
        }
        int limit = haystack.length() - needle.length();
        for (int i = 0; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }
}
