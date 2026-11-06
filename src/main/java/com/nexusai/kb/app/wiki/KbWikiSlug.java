package com.nexusai.kb.app.wiki;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Wiki slug 规范化 · W2-3。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_slug.go} 的 {@code slugify} / {@code normalizeWikiSlug}。
 *
 * <h2>为什么 slug 要单独一个类</h2>
 * slug 是<b>永久寻址键</b>：
 * <ul>
 *   <li>{@code [[entity/acme-corp|Acme]]} 这种 wiki 链接靠它解析；</li>
 *   <li>换 slug = 页面所有入链全断，而且<b>不会报错</b> —— 只会变成死链；</li>
 *   <li>所以「同一个名字必须永远得到同一个 slug」是硬要求（确定性，不依赖随机数/时间）。</li>
 * </ul>
 *
 * <h2>⚠️ 中文名的处理（本仓与源侧的差异）</h2>
 * 源侧要求「非拉丁名用拼音」。本仓<b>不做拼音转换</b> —— 引入拼音库会带来
 * 多音字歧义（「重庆」vs「重」，源侧同样有这个问题，只是它选了一个库的实现）。
 * 本仓的做法：保留 CJK 字符本身，只把空白/标点转成连字符。
 * <p>结果：{@code 农村污水 → entity/农村污水}。
 * <p>⭐ 语义不变（slug 仍是确定且唯一的），只是形态不同；⛔ 不要因为「看着不像 latin」
 * 就去加拼音 —— 那会让同一实体在两个系统间产生不同 slug，反而更难对齐。
 */
public final class KbWikiSlug {

    /** 页面类型前缀（唯一合法集合）。 */
    public static final String ENTITY = "entity";
    public static final String CONCEPT = "concept";
    public static final String SUMMARY = "summary";
    public static final String INDEX = "index";

    /** slug 最大长度（码点）· 源侧限制为 200。 */
    private static final int MAX_LEN = 200;

    private KbWikiSlug() {
    }

    /**
     * 把人类可读名转成 slug 主体（不含类型前缀）。
     *
     * <p>规则（确定性，幂等）：
     * <ol>
     *   <li>NFKC 归一（全角字母/数字转半角）；</li>
     *   <li>小写；</li>
     *   <li>去掉组合变音符号（café → cafe）；</li>
     *   <li>非「字母/数字/CJK」一律转连字符；</li>
     *   <li>连续连字符折叠；首尾连字符去掉；</li>
     *   <li>截断到 {@value #MAX_LEN} 码点；</li>
     *   <li>空结果 ⇒ {@code "untitled"}（⛔ 不返回空串 —— 空 slug 会让唯一索引失效）。</li>
     * </ol>
     *
     * @param name 名字（可为 null）
     * @return 非空的 slug 主体
     */
    public static String slugify(String name) {
        if (name == null) {
            return "untitled";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC);
        s = s.toLowerCase(Locale.ROOT);
        // 去掉组合变音符号（\p{M} = combining marks）
        s = s.replaceAll("\\p{M}", "");

        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasDash = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
                lastWasDash = false;
            } else if (!lastWasDash) {
                sb.append('-');
                lastWasDash = true;
            }
        }

        String out = trimDashes(sb.toString());
        // 截断按码点，⛔ 不用 substring（会切断代理对）
        if (out.codePointCount(0, out.length()) > MAX_LEN) {
            int end = out.offsetByCodePoints(0, MAX_LEN);
            out = trimDashes(out.substring(0, end));
        }
        return out.isEmpty() ? "untitled" : out;
    }

    /**
     * 组装完整 slug：{@code <type>/<slugify(name)>}。
     *
     * @param type entity / concept / summary / index
     * @param name 名字
     * @return 完整 slug
     * @throws IllegalArgumentException 类型不在合法集合内
     */
    public static String of(String type, String name) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!ENTITY.equals(t) && !CONCEPT.equals(t) && !SUMMARY.equals(t) && !INDEX.equals(t)) {
            throw new IllegalArgumentException("非法的 wiki 页面类型: " + type);
        }
        return t + "/" + slugify(name);
    }

    /**
     * 摘要页 slug：用 <b>knowledgeId</b> 而不是标题（对齐源侧）。
     *
     * <p>⭐⭐ 为什么不用标题：摘要页的 slug 会被下游模型读进「可用 wiki 页面」清单里。
     * 用标题 = 把<b>文件名/标题</b>泄漏给模型，正好违反「文件名永不喂给模型」那条铁律
     * （扫描件叫 {@code MX5280.pdf} 时尤其糟）。
     *
     * @param knowledgeId 文档 ID
     * @return {@code summary/<id>}
     */
    public static String summarySlug(String knowledgeId) {
        return SUMMARY + "/" + slugify(knowledgeId);
    }

    /**
     * 规范化一个已有 slug（大小写/连字符/前后缀）。
     *
     * <p>用于「模型返回的 slug」和「用户手填的 slug」的收口 ——
     * 模型经常返回 {@code Entity/Acme Corp} 这种，必须先归一才能与库里的比对。
     *
     * @param raw 原始 slug
     * @return 规范化后的完整 slug；无法识别类型时返回 null（调用方应丢弃该条）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return null;
        }
        String type = s.substring(0, slash).trim().toLowerCase(Locale.ROOT);
        if (!ENTITY.equals(type) && !CONCEPT.equals(type) && !SUMMARY.equals(type)
            && !INDEX.equals(type)) {
            return null;
        }
        return type + "/" + slugify(s.substring(slash + 1));
    }

    /** 取 slug 的类型前缀（{@code entity/x} → {@code entity}）。 */
    public static String typeOf(String slug) {
        if (slug == null) {
            return null;
        }
        int slash = slug.indexOf('/');
        return slash <= 0 ? null : slug.substring(0, slash);
    }

    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }
}
