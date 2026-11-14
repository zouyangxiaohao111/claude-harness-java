package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求 · 字段对齐源侧 {@code KnowledgeBase} 结构体（{@code internal/types/knowledgebase.go:59}）。
 *
 * <p><b>源侧必填</b>（Go {@code binding:"required"}）：只有 {@code name}。
 * 其余字段留空 = 保持默认（源侧 x-agent-hint 原话：
 * "A field you leave out keeps its default."）。
 *
 * <p><b>⚠️ 本批只接收源侧主路径所需的字段</b>：
 * 源侧结构体有 30+ 字段，其中多数依赖后续批次的表（{@code vector_store_id} 依赖 B3、
 * {@code storage_backend_id} 依赖 B10）。本批<b>不接收</b>这些字段 —— 传了也不生效是更坏的契约。
 * 后续批次会扩展本 record（源侧也允许字段随版本增长）。
 *
 * @param name                知识库名（必填）
 * @param description         描述
 * @param type                类型：document | faq | wiki；空 = document
 * @param chunkingConfig      分块配置 JSON；空 = 用 DB 默认值
 * @param embeddingModelId    嵌入模型 ID（源侧 NOT NULL）
 * @param summaryModelId      摘要模型 ID（源侧 NOT NULL）
 */
public record CreateKnowledgeBaseRequest(
    @JsonProperty("name")
    @NotBlank(message = "知识库名不能为空")
    @Size(max = 255, message = "知识库名最长 255 字符")
    String name,

    @JsonProperty("description")
    String description,

    @JsonProperty("type")
    String type,

    @JsonProperty("chunking_config")
    String chunkingConfig,

    @JsonProperty("embedding_model_id")
    String embeddingModelId,

    @JsonProperty("summary_model_id")
    String summaryModelId,

    /**
     * 索引策略 JSON —— **Wiki / RAG 能力的唯一开关**。
     *
     * <p>源侧创建时同一个请求就带上它（`KnowledgeBaseEditorModal.vue:1690-1694`）：
     * <pre>
     * data.indexing_strategy = {
     *   vector_enabled:  true,   // RAG 检索（分块 + 向量 + 关键词）
     *   keyword_enabled: true,
     *   wiki_enabled:    false,  // Wiki 知识库
     *   graph_enabled:   false,  // 知识图谱
     * }
     * </pre>
     *
     * <p>⚠️ <b>为什么必须支持它</b>：源侧注释写死
     * 「Wiki enablement is carried solely by `indexing_strategy.wiki_enabled`」
     * （`KnowledgeBaseEditorModal.vue:1675`）。本仓原先 create 不收这个字段，
     * 前端那个「Wiki 知识库」勾选框只能灰置 —— 而 `patch` 是收的（`:301`），
     * 于是「创建时不能开、创建后才能改」，与源侧的创建流程不一致。
     *
     * <p>null = 不传 ⇒ 落库为 NULL（与源侧「未配置」同义，Tab 恒定显示的判据在
     * `KBPostProcessConfig` 侧，不受影响）。
     */
    @JsonProperty("indexing_strategy")
    String indexingStrategy,

    /**
     * 抽取配置 JSON（`extract_config`）—— 图谱开关的**第二个**字段。
     *
     * <p>⭐ 为什么创建时必须能写它：图谱派发判据是两字段的合取
     * （{@code KbPostProcessConfig.graphEnabled}）：
     * <pre>
     * indexing_strategy.graph_enabled  ∧  extract_config.enabled
     * </pre>
     * 只写前者 ⇒ {@code extract_config} 为空 ⇒ {@code enabled} 取缺省 false
     * ⇒ 用户勾了「知识图谱」但一个实体都不会抽，且零日志零报错。
     * 源侧也是创建时同一个请求带上它（`KnowledgeBaseEditorModal.vue` 的 `data`）。
     *
     * <p>null = 不传 ⇒ 落库为 NULL（DB 列可空，下游 parse 成「未配置」）。
     */
    @JsonProperty("extract_config")
    String extractConfig,

    /**
     * 图片管线 · 是否生成图片描述（{@code image_caption_enabled}，V286）。
     *
     * <p>null = 用默认（<b>true</b>，与改造前恒跑 caption 一致）；
     * false = 入库时不再调 caption 提示词。
     */
    @JsonProperty("image_caption_enabled")
    Boolean imageCaptionEnabled,

    /**
     * 图片管线 · 是否做 OCR（{@code image_ocr_enabled}，V286）。
     *
     * <p>null = 用默认（<b>true</b>）；false = 入库时不再调 OCR 提示词。
     */
    @JsonProperty("image_ocr_enabled")
    Boolean imageOcrEnabled,

    /**
     * 图片管线方案（{@code image_pipeline}，V286）：{@code default} | {@code smartocr}。
     *
     * <p>null/空白 = {@code default}（caption / OCR 各一次调用）；
     * {@code smartocr} = 先一次「观察」（描述 + 是否含文字），只对含文字的图再跑 OCR。
     * ⚠️ 其它值 ⇒ 400（值域在 {@code KnowledgeBaseService} 校验）。
     */
    @JsonProperty("image_pipeline")
    String imagePipeline,

    /**
     * Wiki 配置 JSON（{@code wiki_config}）—— 只放 Wiki 专属可调项
     * （{@code auto_generate} / {@code synthesis_model_id} / {@code max_pages_per_ingest} …）。
     *
     * <p>⚠️ Wiki 的**开关**不在这里，而在 {@code indexing_strategy.wiki_enabled}
     * （源侧注释原话："Wiki enablement is carried solely by indexing_strategy.wiki_enabled"）。
     *
     * <p>null / 空白 = 不设（落库 NULL）。
     */
    @JsonProperty("wiki_config")
    String wikiConfig,

    /**
     * 图片处理配置 JSON（{@code image_processing_config}）。
     *
     * <p>⚠️ 与 {@code image_pipeline} 不同：这一列承载属性观察 / 图片向量 / 方案槽位参数
     * （源侧 {@code buildImageProcessingConfig} 产出的整份快照）。
     * null / 空白 = 用 DB 默认 {@code {"enable_multimodal": false, "model_id": ""}}。
     */
    @JsonProperty("image_processing_config")
    String imageProcessingConfig,

    /** ASR 语音识别配置 JSON（{@code asr_config}；null / 空白 = 不设）。 */
    @JsonProperty("asr_config")
    String asrConfig,

    /** 问题生成配置 JSON（{@code question_generation_config}；null / 空白 = 不设）。 */
    @JsonProperty("question_generation_config")
    String questionGenerationConfig,

    /** 自动打标配置 JSON（{@code auto_tag_config}；null / 空白 = 不设）。 */
    @JsonProperty("auto_tag_config")
    String autoTagConfig,

    /** 知识库画像配置 JSON（{@code profile_config}；null / 空白 = 不设）。 */
    @JsonProperty("profile_config")
    String profileConfig,

    /** FAQ 配置 JSON（{@code faq_config}；null / 空白 = 不设）。 */
    @JsonProperty("faq_config")
    String faqConfig,

    /** 绑定的向量库 ID（{@code vector_store_id}；null / 空白 = 不绑）。 */
    @JsonProperty("vector_store_id")
    String vectorStoreId,

    /** 绑定的存储后端 ID（{@code storage_backend_id}；null / 空白 = 不绑）。 */
    @JsonProperty("storage_backend_id")
    String storageBackendId
) {
    /**
     * 6 参兼容构造器（不带索引策略 / 抽取配置 / 图片管线 / 其余配置）。
     *
     * <p>⚠️ 仅供**既有测试**使用 —— 那些用例只关心名称/类型/模型，不涉及这些配置。
     * 生产链路（`KnowledgeBaseController#create` → Jackson 反序列化）走规范构造器。
     * 保留它是为了让「加一个可选字段」不用改调用点（纯噪音改动）。
     */
    public CreateKnowledgeBaseRequest(
        String name, String description, String type,
        String chunkingConfig, String embeddingModelId, String summaryModelId
    ) {
        this(name, description, type, chunkingConfig, embeddingModelId, summaryModelId, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 8 参兼容构造器（带索引策略 / 抽取配置，不带图片管线三项与其余配置）。
     *
     * <p>⚠️ 同样只为既有测试保留（{@code KnowledgeBaseServiceTest} 等按位置传 8 个参数）。
     * 图片管线三项恒 null = 用默认（true / true / default）。
     */
    public CreateKnowledgeBaseRequest(
        String name, String description, String type,
        String chunkingConfig, String embeddingModelId, String summaryModelId,
        String indexingStrategy, String extractConfig
    ) {
        this(name, description, type, chunkingConfig, embeddingModelId, summaryModelId,
            indexingStrategy, extractConfig, null, null, null, null, null, null, null, null, null,
            null, null, null);
    }

    /**
     * 11 参兼容构造器（含图片管线三项，不含本次新增的 9 项配置）。
     *
     * <p>⚠️ 既有测试 {@code KbImagePipelineConfigDbTest} 按位置传 11 个参数。
     * 新增 9 项恒 null = 不设。
     */
    public CreateKnowledgeBaseRequest(
        String name, String description, String type,
        String chunkingConfig, String embeddingModelId, String summaryModelId,
        String indexingStrategy, String extractConfig,
        Boolean imageCaptionEnabled, Boolean imageOcrEnabled, String imagePipeline
    ) {
        this(name, description, type, chunkingConfig, embeddingModelId, summaryModelId,
            indexingStrategy, extractConfig, imageCaptionEnabled, imageOcrEnabled, imagePipeline,
            null, null, null, null, null, null, null, null, null);
    }
}
