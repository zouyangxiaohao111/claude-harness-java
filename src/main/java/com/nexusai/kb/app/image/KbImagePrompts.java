package com.nexusai.kb.app.image;

import com.nexusai.kb.app.prompt.KbPromptLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片理解提示词（中文）· 对齐 WeKnora
 * {@code internal/application/service/image_multimodal.go:31-78}
 * （{@code vlmOCRPrompt} / {@code buildVLMCaptionPrompt}）。
 *
 * <p><b>⭐ 三条提示词各自守一个「模型输出契约」</b>，每条都必须能被代码识别出来：
 * <ol>
 *   <li><b>caption</b>：一句话说明，且「图里没内容」时回 {@code NO_CONTENT}。
 *       ⛔ 不能把 {@code NO_CONTENT} 当成说明存下来 —— 图库里会显示成一堆
 *       「NO_CONTENT」标题，看起来像解析失败。</li>
 *   <li><b>OCR</b>：只转写可见文字，且「没有可辨认文字」时回 {@code No text content.}。
 *       源侧 {@code ocr_sanitizer.go:191-200} 会对这句做大小写 + 尾部标点归一后再比对，
 *       本类沿用同一口径（见 {@link #isNoTextReply}）。</li>
 *   <li><b>观察</b>（smartocr）：按源站属性名回 {@code contain.text} /
 *       {@code contain.data_visual} + {@code DESCRIPTION}，由 {@link #parseObserve}
 *       做封闭集校验（值非法 ⇒ 属性缺失，⛔ 不填默认值）。</li>
 * </ol>
 *
 * <p><b>为什么提示词外置成 md</b>：本仓铁律要求知识库提示词全部放
 * {@code resources/kb/prompt/}（由 {@link KbPromptLoader} 加载，缺失即抛）。
 * ⛔ 不要为了「省一个文件」把它们写成 Java 字符串常量。
 */
public final class KbImagePrompts {

    private KbImagePrompts() {
    }

    /** 图片说明系统提示词（一句话描述，40 字以内）。 */
    public static final String CAPTION = KbPromptLoader.cached("image/caption.md");

    /** 图片 OCR 系统提示词（只转写可见文字）。 */
    public static final String OCR = KbPromptLoader.cached("image/ocr.md");

    /**
     * 观察提示词（smartocr 管线）· 一次调用同时产出「图片属性 + 描述」。
     *
     * <p>对齐源站 {@code image_multimodal.go:535 buildImageAttrsPrompt} +
     * {@code types.ImageAttrRegistry}：模型按<b>属性名逐字</b>回
     * {@code contain.text} / {@code contain.data_visual}，描述走 {@code DESCRIPTION}。
     * 输出契约由 {@link #parseObserve} 解析，判定由
     * {@link KbImageAttrDecision#decideOcr} 做。
     */
    public static final String OBSERVE = KbPromptLoader.cached("image/observe.md");

    /** caption 提示词约定的「图里没有可描述内容」回包（大写，逐字）。 */
    public static final String CAPTION_NO_CONTENT = "NO_CONTENT";

    /**
     * 已知的「没有文字」回包形态（小写、去尾部标点后比对）。
     *
     * <p>⛔ 只认这几条：模型很爱自作主张回「There is no text in this image.」之类的长句，
     * 若用「包含 no text」这种模糊判据，会把一行真实文字（比如 "No text content specified"）
     * 误判成空 —— OCR 结果就整段消失且不报错。
     */
    private static final Set<String> NO_TEXT_REPLIES = Set.of(
        "no text content", "no text", "no visible text", "no recognizable text",
        "无文字", "没有文字", "无可辨认文字");

    /**
     * OCR 回包是否是「图里没有文字」。
     *
     * <p>归一规则对齐源侧 {@code isKnownEmptyReply}：转小写、trim、去掉尾部标点。
     *
     * @param text 模型原始回包（可 null）
     * @return true = 应视为空（不落 OCR 字段）
     */
    public static boolean isNoTextReply(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.strip().toLowerCase(Locale.ROOT);
        // 去尾部标点（中英文都要：模型时而回中文句号）
        int end = lower.length();
        while (end > 0 && ".!?。！？，,".indexOf(lower.charAt(end - 1)) >= 0) {
            end--;
        }
        return NO_TEXT_REPLIES.contains(lower.substring(0, end).strip());
    }

    /**
     * smartocr「观察」调用的结构化结果（对齐源站 {@code types.AttrObservation}）。
     *
     * @param caption  图片描述（null / 空白 = 没拿到可用描述 ⇒ 调用方保留原值）
     * @param attrs    观察到的属性（键 = 源站属性名，值 = 合法取值；<b>缺失 = 没观察到</b>，
     *                 ⛔ 不用默认值补齐 —— 那正是 {@code ocr_on_unobserved} 的分支）
     * @param observed 回包里是否<b>至少有一行</b>格式正确的属性（源站 {@code AttrObservation.Observed}）
     */
    public record Observed(String caption, Map<String, Object> attrs, boolean observed) {

        /** 规范化：attrs 不可变、永不为 null（调用方无需判空）。 */
        public Observed {
            attrs = attrs == null || attrs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        }

        /**
         * 是否值得跑 OCR —— 按源站默认 {@code ocr_on_unobserved=true} 判定的便捷访问器。
         *
         * <p>⚠️ 生产代码用 {@link #attrs()} + {@link KbImageAttrDecision#decideOcr}
         * 显式传管线配置；这个方法只是同一判据的默认值形态（解析层单测/日志用）。
         */
        public boolean hasText() {
            return KbImageAttrDecision.decideOcr(attrs, true);
        }

        /** 观察没给任何可用属性（异常回包 / 空白 / 纯散文）。 */
        static Observed unobserved(String caption) {
            return new Observed(caption, Map.of(), false);
        }
    }

    /** 源站 registry 的属性名（逐字照抄，见 {@code types.ImageAttrRegistry}）。 */
    private static final String[] ATTR_NAMES = {
        KbImageAttrDecision.ATTR_CONTAIN_TEXT,
        KbImageAttrDecision.ATTR_CONTAIN_DATA_VISUAL,
        KbImageAttrDecision.ATTR_LAYOUT_MARGIN,
        KbImageAttrDecision.ATTR_LAYOUT_UPRIGHT};

    /**
     * 描述行的键（大小写不敏感）。
     *
     * <p>源站只认 {@code DESCRIPTION}；{@code caption/描述/说明} 是本仓旧观察提示词的
     * 别名，留着只为兼容「模型沿用旧格式」的回包，不改变属性解析。
     */
    private static final Set<String> DESCRIPTION_KEYS = Set.of(
        "description", "caption", "描述", "说明");

    /**
     * 旧观察格式的标记（{@code HAS_TEXT: yes|no}）。新提示词不再要求，但模型可能沿用
     * 旧格式，故做一次显式映射：{@code yes} ⇒ {@code contain.text=block}；
     * {@code no} ⇒ {@code contain.text=none} + {@code contain.data_visual=false}
     * （旧契约里「no」的语义就是「纯照片/图形/装饰/空白」，不含数据图）。
     */
    private static final Set<String> LEGACY_HAS_TEXT_KEYS = Set.of(
        "has_text", "hastext", "有文字", "是否有文字", "是否含文字");

    /**
     * 解析观察回包（smartocr 管线）· 对齐源站
     * {@code types/image_attrs.go:388-433 ParseImageAttrsResponse}。
     *
     * <p><b>⭐ 逐行协议（不是正则猜自然语言）</b>：
     * <ol>
     *   <li>剥行首的 Markdown 列表/粗体标记（{@code * _ # -} 与空白）；</li>
     *   <li>按<b>第一个</b>冒号（半角/全角）切成 {@code key: value}；</li>
     *   <li>属性名归一（小写、空格/连字符→下划线、去 {@code * #}），再与 registry
     *       逐名匹配（{@code contain.text} 或 {@code contain_text} 都认）；</li>
     *   <li>值按<b>封闭集</b>校验：extent 属性必须在合法取值里，presence 属性只能是
     *       true/false。⚠️ 值非法 ⇒ 该属性<b>不落</b>（缺失 = 没观察到），
     *       ⛔ 绝不用默认值补齐 —— 补了就把「没答」伪装成「答了 block」。</li>
     *   <li>其余行（含 {@code DESCRIPTION}）按描述收集；整段空白 ⇒ 无描述、无属性。</li>
     * </ol>
     *
     * <p><b>⭐ 兜底语义</b>：一条格式正确的属性都没有（空白 / 纯散文 / 全部值非法）
     * ⇒ {@code attrs} 为空、{@code observed=false}；是否继续 OCR 由
     * {@link KbImageAttrDecision#decideOcr}{@code (attrs, ocrOnUnobserved)} 决定
     * （默认 {@code true} ⇒ 继续，宁可多跑一次也不静默丢字）。
     *
     * @param reply 模型原始回包（可 null）
     * @return 解析结果（永不 null）
     */
    public static Observed parseObserve(String reply) {
        if (reply == null || reply.isBlank()) {
            return Observed.unobserved(null);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        List<String> descriptions = new ArrayList<>();
        for (String rawLine : reply.split("\\R")) {
            String line = stripLineMarkers(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            String[] kv = cutAttrLine(line);
            if (kv != null) {
                String key = normalizeAttrKey(kv[0]);
                String value = kv[1];
                if (DESCRIPTION_KEYS.contains(key)) {
                    if (!value.isBlank()) {
                        descriptions.add(value.strip());
                    }
                    continue;
                }
                String canonical = canonicalAttrName(key);
                if (canonical != null) {
                    Object coerced = coerceAttrValue(canonical, value);
                    // 值非法 ⇒ 什么都不写（保持「没观察到」），⛔ 不填默认值
                    if (coerced != null) {
                        attrs.put(canonical, coerced);
                    }
                    continue;
                }
                if (LEGACY_HAS_TEXT_KEYS.contains(key) && applyLegacyHasText(value, attrs)) {
                    continue;
                }
                // 不认识的键 ⇒ 当描述正文（与源站一致：不丢模型写的散文）
            }
            if ("description".equalsIgnoreCase(line.strip())) {
                continue;
            }
            descriptions.add(line);
        }
        String caption = descriptions.isEmpty()
            ? null
            : stripLeadingProseMarkers(String.join(" ", descriptions));
        return new Observed(caption, attrs, !attrs.isEmpty());
    }

    /** 剥行首的列表/粗体标记（源站 {@code trimAttrMarkers}：{@code * _ # -} 与空白）。 */
    private static String stripLineMarkers(String rawLine) {
        String line = rawLine.strip();
        while (!line.isEmpty() && "*-•#_".indexOf(line.charAt(0)) >= 0) {
            line = line.substring(1).strip();
        }
        return line;
    }

    /**
     * 切「key: value」（源站 {@code cutAttrLine}），额外兼容全角冒号。
     *
     * @return 长度 2 的数组；不含冒号或键为空 ⇒ null
     */
    private static String[] cutAttrLine(String line) {
        int ascii = line.indexOf(':');
        int wide = line.indexOf('：');
        int idx;
        if (ascii < 0) {
            idx = wide;
        } else if (wide < 0) {
            idx = ascii;
        } else {
            idx = Math.min(ascii, wide);
        }
        if (idx < 0) {
            return null;
        }
        String key = line.substring(0, idx).strip();
        if (key.isEmpty()) {
            return null;
        }
        return new String[]{key, line.substring(idx + 1).strip()};
    }

    /** 属性名归一（源站 {@code lookupAttrSpec}）：小写、空格/连字符→下划线、去 {@code * #}。 */
    private static String normalizeAttrKey(String key) {
        return key.strip().toLowerCase(Locale.ROOT)
            .replace(' ', '_').replace('-', '_')
            .replace("*", "").replace("#", "");
    }

    /** 归一后的键 ⇒ registry 里的规范属性名（{@code contain.text} / {@code contain_text} 都认）。 */
    private static String canonicalAttrName(String normalizedKey) {
        for (String name : ATTR_NAMES) {
            if (name.equals(normalizedKey) || name.replace('.', '_').equals(normalizedKey)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 值按封闭集校验（源站 {@code coerceAttrValue}）。
     *
     * @return 合法值（extent ⇒ String，presence ⇒ Boolean）；非法 ⇒ null（属性保持缺失）
     */
    private static Object coerceAttrValue(String canonicalName, String rawValue) {
        String v = rawValue.strip().toLowerCase(Locale.ROOT)
            .replace("*", "").replace("#", "");
        switch (canonicalName) {
            case KbImageAttrDecision.ATTR_CONTAIN_TEXT:
                return KbImageAttrDecision.CONTAIN_TEXT_VALUES.contains(v) ? v : null;
            case KbImageAttrDecision.ATTR_LAYOUT_MARGIN:
                return KbImageAttrDecision.LAYOUT_MARGIN_VALUES.contains(v) ? v : null;
            case KbImageAttrDecision.ATTR_CONTAIN_DATA_VISUAL:
            case KbImageAttrDecision.ATTR_LAYOUT_UPRIGHT:
                if ("true".equals(v)) {
                    return Boolean.TRUE;
                }
                return "false".equals(v) ? Boolean.FALSE : null;
            default:
                return null;
        }
    }

    /** 旧格式 {@code HAS_TEXT} 的显式映射（见 {@link #LEGACY_HAS_TEXT_KEYS}）。 */
    private static boolean applyLegacyHasText(String rawValue, Map<String, Object> attrs) {
        Boolean has = parseYesNo(rawValue);
        if (has == null) {
            return false;
        }
        if (has) {
            attrs.put(KbImageAttrDecision.ATTR_CONTAIN_TEXT, "block");
        } else {
            attrs.put(KbImageAttrDecision.ATTR_CONTAIN_TEXT, "none");
            attrs.put(KbImageAttrDecision.ATTR_CONTAIN_DATA_VISUAL, Boolean.FALSE);
        }
        return true;
    }

    /** 描述行首可能带列表标记，去掉让 caption 以正文开头（源站同口径）。 */
    private static String stripLeadingProseMarkers(String text) {
        String t = text.strip();
        int start = 0;
        while (start < t.length() && "-*# ".indexOf(t.charAt(start)) >= 0) {
            start++;
        }
        return t.substring(start).strip();
    }

    /** yes/no 归一（兼容 true/false、y/n、有/无、是/否；仅旧格式 {@code HAS_TEXT} 用）。 */
    private static Boolean parseYesNo(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT)
            .replace("\"", "").replace("'", "");
        int end = v.length();
        while (end > 0 && ".!?。！？，,".indexOf(v.charAt(end - 1)) >= 0) {
            end--;
        }
        v = v.substring(0, end).strip();
        if (v.isEmpty()) {
            return null;
        }
        return switch (v) {
            case "yes", "y", "true", "1", "有", "是" -> Boolean.TRUE;
            case "no", "n", "false", "0", "无", "没有", "否" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * caption 回包是否无效（空 / 约定的无内容标记）。
     *
     * @param text 模型原始回包（可 null）
     * @return true = 不该写进 {@code caption} 字段
     */
    public static boolean isNoCaption(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.strip();
        // 模型偶尔会把标记包进引号或加句号 ⇒ 归一后比对
        String norm = t.replace("\"", "").replace("'", "").strip();
        int end = norm.length();
        while (end > 0 && ".!?。！？".indexOf(norm.charAt(end - 1)) >= 0) {
            end--;
        }
        return CAPTION_NO_CONTENT.equalsIgnoreCase(norm.substring(0, end).strip());
    }
}
