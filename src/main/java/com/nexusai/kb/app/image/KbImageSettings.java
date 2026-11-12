package com.nexusai.kb.app.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;

/**
 * 知识库「图片管线」配置快照 · 对齐源站
 * {@code image_pipeline_default.go:59-76}（caption / OCR 两个开关）+
 * {@code image_pipeline_smartocr.go}（先观察后决定是否 OCR）。
 *
 * <p><b>⭐ 为什么单独抽一个不可变快照</b>：{@link KbImageEnrichService} 会在
 * <b>工作线程</b>里逐图分支（caption / OCR / smartocr），如果每次都回库读
 * {@link KbKnowledgeBaseRecord}，就变成「N 张图 N 次读库」且<b>读到的可能是改了一半的配置</b>
 * （用户在入库过程中改开关）。⇒ 在派发/入口处读一次，冻结成本对象传下去。
 *
 * <p><b>⚠️ 缺省语义 = 全开 + default 管线</b>：这两条同时保证「老库升级后行为不变」
 * ——改造前是恒跑 caption + OCR（等价两个开关都开），且没有 smartocr 这条分支。
 *
 * @param captionEnabled 是否生成图片描述（false ⇒ 不调 caption 提示词）
 * @param ocrEnabled     是否做 OCR（false ⇒ 不调 OCR 提示词；smartocr 下即使观察到有文字也不做）
 * @param pipeline       管线方案：{@link #PIPELINE_DEFAULT} | {@link #PIPELINE_SMART_OCR}
 * @param ocrOnUnobserved smartocr 的兜底条款（源站 {@code ocr_on_unobserved}，默认 <b>true</b>）：
 *                        观察调用失败、或模型没给出可判定属性时，<b>仍继续 OCR</b>
 *                        ——「宁可多花一次调用，也不要静默丢字」。仅 smartocr 管线读它。
 */
public record KbImageSettings(boolean captionEnabled, boolean ocrEnabled, String pipeline,
                              boolean ocrOnUnobserved) {

    /** 源站参数名：属性没观察到时是否仍跑 OCR（默认 true）。 */
    public static final String KEY_OCR_ON_UNOBSERVED = "ocr_on_unobserved";

    /** 旧三参构造：{@code ocr_on_unobserved} 取源站默认 {@code true}。 */
    public KbImageSettings(boolean captionEnabled, boolean ocrEnabled, String pipeline) {
        this(captionEnabled, ocrEnabled, pipeline, true);
    }

    /** 默认管线：caption / OCR 各一次模型调用（与改造前行为逐字一致）。 */
    public static final String PIPELINE_DEFAULT = "default";

    /** 观察型管线：先一次「观察」调用（图片属性 + 描述），再按属性判定是否跑 OCR。 */
    public static final String PIPELINE_SMART_OCR = "smartocr";

    /** 全开 + 默认管线（无库配置可读时的兜底；也是老库升级后的实际值）。 */
    public static final KbImageSettings DEFAULTS = new KbImageSettings(true, true, PIPELINE_DEFAULT);

    /** JSON 解析器（只读 {@code image_processing_config} 里的一两个键，静态复用）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 源站槽位键：{@code image_pipeline_params} 里的 {@code smartocr.ocr_on_unobserved}。
     *
     * <p>源站 {@code internal/types/knowledgebase.go:571-577} 的
     * {@code ImageProcessingConfig.ImagePipelineParams} 就是这张按「流水线 id.field」拼键的 map
     * （{@code image_pipeline_slot_test.go:15-20} 断言了这对拼装规则），
     * 所以本仓读同一个键，配置写法与源站一致。
     */
    public static final String SLOT_KEY_OCR_ON_UNOBSERVED = "smartocr." + KEY_OCR_ON_UNOBSERVED;

    /**
     * 规范化不变式：{@code pipeline} 只可能是 {@link #PIPELINE_DEFAULT} 或
     * {@link #PIPELINE_SMART_OCR}。
     *
     * <p>⚠️ 写入口（{@code KnowledgeBaseService}）已对非法值返回 400；这里再做一次
     * fail-soft 归一（null / 空白 / 未知 ⇒ default），避免库里存了脏值就让整条图片链抛异常
     * —— 图片理解是文档入库的<b>增强</b>步骤，不该因为一个拼错的枚举把文档判失败。
     */
    public KbImageSettings {
        pipeline = normalizePipeline(pipeline);
    }

    /** 是否走「先观察后决定」的 smartocr 管线。 */
    public boolean smartOcr() {
        return PIPELINE_SMART_OCR.equals(pipeline);
    }

    /** 是否还有任何要做的事（两个开关都关 ⇒ 调用方应直接返回，不建线程池）。 */
    public boolean anyEnabled() {
        return captionEnabled || ocrEnabled;
    }

    /**
     * 由三列原始值构造（null 开关 = 用默认 true）。
     *
     * @param captionEnabled {@code image_caption_enabled}（1/0，可 null）
     * @param ocrEnabled     {@code image_ocr_enabled}（1/0，可 null）
     * @param pipeline       {@code image_pipeline}（可 null/空白 ⇒ default）
     * @return 配置快照（永不 null）
     */
    public static KbImageSettings of(Boolean captionEnabled, Boolean ocrEnabled, String pipeline) {
        return new KbImageSettings(
            captionEnabled == null || captionEnabled,
            ocrEnabled == null || ocrEnabled,
            pipeline);
    }

    /**
     * 由知识库行解析。
     *
     * @param kb 知识库行（null ⇒ {@link #DEFAULTS}）
     * @return 配置快照（永不 null）
     */
    public static KbImageSettings from(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return DEFAULTS;
        }
        return new KbImageSettings(
            onOff(kb.getImageCaptionEnabled()),
            onOff(kb.getImageOcrEnabled()),
            kb.getImagePipeline(),
            ocrOnUnobservedOf(kb));
    }

    /**
     * 读 {@code image_processing_config.image_pipeline_params["smartocr.ocr_on_unobserved"]}。
     *
     * <p>取值顺序：槽位键（源站写法）→ 扁平 {@code ocr_on_unobserved}（本仓旧/简写）
     * → 默认 {@code true}。
     *
     * <p><b>⚠️ 为什么解析失败也返回 true</b>：这个参数决定「属性没观察到时要不要继续 OCR」，
     * fail-safe 的方向是<b>继续 OCR</b>——宁可多花一次模型调用，也不要静默丢字。
     * json 坏掉时返回 false 会让整批图片悄悄不做 OCR，比多花钱更糟。
     *
     * @param kb 知识库行（null 或字段为空 ⇒ true）
     * @return 是否在「属性没观察到」时仍做 OCR
     */
    static boolean ocrOnUnobservedOf(KbKnowledgeBaseRecord kb) {
        if (kb == null) {
            return true;
        }
        String raw = kb.getImageProcessingConfig();
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            JsonNode cfg = JSON.readTree(raw);
            JsonNode v = cfg.path("image_pipeline_params").path(SLOT_KEY_OCR_ON_UNOBSERVED);
            if (v.isMissingNode() || v.isNull()) {
                v = cfg.path(KEY_OCR_ON_UNOBSERVED);
            }
            // 非布尔（字符串 "true"/数字等）不认，走默认；只认显式 false 关闭
            return !v.isBoolean() || v.asBoolean();
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 值域校验用：是否是合法管线名。
     *
     * @param raw 原始值
     * @return true = default / smartocr（大小写不敏感、允许首尾空白）
     */
    public static boolean isValidPipeline(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;   // 空白 = 未指定 ⇒ 落默认，不算非法
        }
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return PIPELINE_DEFAULT.equals(t) || PIPELINE_SMART_OCR.equals(t);
    }

    /**
     * 写入口用的规范化（非法值由调用方先拦成 400，这里只做大小写/空白归一）。
     *
     * @param raw 原始值
     * @return default / smartocr（null/空白 ⇒ default）
     */
    public static String normalizePipeline(String raw) {
        if (raw == null || raw.isBlank()) {
            return PIPELINE_DEFAULT;
        }
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return PIPELINE_SMART_OCR.equals(t) ? PIPELINE_SMART_OCR : PIPELINE_DEFAULT;
    }

    /** SQLite 的 1/0 → boolean；null ⇒ true（列 NOT NULL，null 只可能来自未回填的旧对象）。 */
    private static Boolean onOff(Integer v) {
        return v == null ? Boolean.TRUE : v != 0;
    }
}
