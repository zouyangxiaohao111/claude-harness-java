package com.nexusai.kb.app.image;

import java.util.List;
import java.util.Map;

/**
 * smartocr 图片管线的「要不要花第二次调用做 OCR」判定 · 对齐 WeKnora
 * {@code internal/application/service/image_attr_decision.go:33-62}（{@code DecideOCR}）
 * + {@code internal/types/image_attrs.go:318-327}（{@code DefaultImageActions}）。
 *
 * <h2>⭐ 源站判据（逐字）</h2>
 * <pre>
 * ocr = (contain.text == "block")
 *     || (contain.data_visual == true)
 *     || 判定所读的属性未被观察到（保守：onUnobserved）
 * </pre>
 * 三条合起来的意思：
 * <ul>
 *   <li><b>有正文</b>（{@code contain.text == block}）⇒ 读；</li>
 *   <li><b>是数据图</b>（{@code contain.data_visual == true}）⇒ 读（图表的坐标轴/图例
 *       文字量看起来是 sparse，但必须读出来）；</li>
 *   <li><b>判定要读的属性缺失</b> ⇒ 这条属性既不能算命中、也<b>不能算否定</b>
 *       （把「没观察到」当成「观察到了 false」会把失败的观察伪装成自信的否定）⇒
 *       交给管线的 {@code ocr_on_unobserved} 决定，默认 {@code true}（宁可多花一次调用，
 *       也不要静默丢字）。</li>
 * </ul>
 *
 * <p><b>⭐ 与旧 7 分类表的等价性</b>（源站注释原话）：decorative / logo / photo
 * （文字可靠地没有、或已被描述覆盖）跳过 OCR；text_screenshot / table / chart / other 跑 OCR。
 *
 * <p><b>⛔ 这是纯函数</b>：不读库、不看开关、不调模型 —— 只有 {@code attrs} 与
 * {@code ocrOnUnobserved} 两个入参，所以可以被单测直接跑真值表。
 */
public final class KbImageAttrDecision {

    private KbImageAttrDecision() {
    }

    /** 「图里有多少正文」属性（源站 {@code contain.text}，取值 none &lt; sparse &lt; block）。 */
    public static final String ATTR_CONTAIN_TEXT = "contain.text";

    /** 「是否数据图」属性（源站 {@code contain.data_visual}，取值 true / false）。 */
    public static final String ATTR_CONTAIN_DATA_VISUAL = "contain.data_visual";

    /** 版面留白（源站 {@code layout.margin}：none | small | large，on-demand 注入）。 */
    public static final String ATTR_LAYOUT_MARGIN = "layout.margin";

    /** 文字是否正立（源站 {@code layout.upright}：true | false，on-demand 注入）。 */
    public static final String ATTR_LAYOUT_UPRIGHT = "layout.upright";

    /** {@code contain.text} 的合法取值（源站 registry 顺序：low → high）。 */
    static final List<String> CONTAIN_TEXT_VALUES = List.of("none", "sparse", "block");

    /** {@code layout.margin} 的合法取值。 */
    static final List<String> LAYOUT_MARGIN_VALUES = List.of("none", "small", "large");

    /** 一条「属性 = 值」条件（源站 {@code ImageAttrCondition}）。 */
    public record Condition(String prop, String is) {
    }

    /**
     * 内置 OCR 策略表（源站 {@code DefaultImageActions().OCR.On}，顺序逐字照抄）。
     *
     * <p>知识库自定义策略目前不落库（本仓只实现这一个内置表），所以这里就是唯一真源。
     */
    static final List<Condition> DEFAULT_OCR_ON = List.of(
        new Condition(ATTR_CONTAIN_TEXT, "block"),
        new Condition(ATTR_CONTAIN_DATA_VISUAL, "true"));

    /**
     * 观察到的属性 ⇒ 是否跑 OCR。
     *
     * <p>实现逐条对应源站 {@code DecideOCR}：遍历策略条件；属性<b>缺失</b>记
     * {@code incomplete=true} 并跳过（⛔ 不当成「条件不满足」）；命中任一条件立即返回
     * {@code true}；遍历完没命中 ⇒ {@code incomplete ? ocrOnUnobserved : false}。
     *
     * <p>值比较口径 = Go 的 {@code fmt.Sprint(v) == cond.Is}：布尔 {@code true} 与字符串
     * {@code "true"} 都能命中（{@link String#valueOf(Object)} 对 Boolean 得到 {@code "true"}）。
     *
     * @param attrs            观察到的属性（可 null / 空 ⇒ 全部按「未观察到」处理）
     * @param ocrOnUnobserved  属性没观察到时的兜底（源站 {@code ocr_on_unobserved}，默认 true）
     * @return true = 继续跑 OCR
     */
    public static boolean decideOcr(Map<String, Object> attrs, boolean ocrOnUnobserved) {
        boolean incomplete = false;
        for (Condition cond : DEFAULT_OCR_ON) {
            // 源站口径 = Go 的 `v, ok := attrs[prop]`：只有「键不存在」才算没观察到；
            // 键存在但值是 false 是**观察到的否定**，不能并入 incomplete。
            boolean present = attrs != null && attrs.containsKey(cond.prop());
            if (!present) {
                incomplete = true;
                continue;
            }
            if (cond.is().equals(String.valueOf(attrs.get(cond.prop())))) {
                return true;
            }
        }
        return incomplete && ocrOnUnobserved;
    }

    /**
     * 某条属性是否被观察到（源站 {@code ImageAttrs.Observed}）。
     *
     * <p>⚠️ 「值是 false」和「没有这个键」是两件事：前者是观察到的否定，后者是没答案。
     *
     * @param attrs 属性表（可 null）
     * @param name  属性名
     * @return true = 键存在
     */
    public static boolean observed(Map<String, Object> attrs, String name) {
        return attrs != null && attrs.containsKey(name);
    }
}
