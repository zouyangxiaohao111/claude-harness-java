package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * 更新知识库请求 · 全字段可空 = 局部更新（null 表示「不改这一项」）。
 *
 * <p><b>源侧语义</b>：{@code PUT /knowledge-bases/{id}} 的请求体与创建同结构；
 * 源侧创建提示词写明 "A field you leave out keeps its default."
 * ⇒ 更新时留空 = 保持原值。
 *
 * <p><b>⚠️ 不可变字段</b>：{@code embedding_model_id} 在源侧有约束
 * —— "Cannot change after the knowledge base has documents."
 * 本批尚无文档（B2 才建文档表）⇒ 本批允许改，但 B2 落地后必须加这道校验。
 * 已在 {@code BATCH_PLAN.md} B2 的验收判据里登记。
 *
 * @param name             知识库名（null = 不改）
 * @param description      描述（null = 不改）
 * @param chunkingConfig   分块配置 JSON（null = 不改）
 * @param embeddingModelId 嵌入模型 ID（null = 不改）
 * @param summaryModelId   摘要模型 ID（null = 不改）
 * @param retrieverEngine  检索驱动（null = 不改；"" = 清空回落全局默认；见 V268）
 */
public record UpdateKnowledgeBaseRequest(
    @JsonProperty("name")
    @Size(max = 255, message = "知识库名最长 255 字符")
    String name,

    @JsonProperty("description")
    String description,

    @JsonProperty("chunking_config")
    String chunkingConfig,

    @JsonProperty("embedding_model_id")
    String embeddingModelId,

    @JsonProperty("summary_model_id")
    String summaryModelId,

    @JsonProperty("retriever_engine")
    String retrieverEngine,

    /** 图片管线 · 是否生成图片描述（null = 不改；V286）。 */
    @JsonProperty("image_caption_enabled")
    Boolean imageCaptionEnabled,

    /** 图片管线 · 是否做 OCR（null = 不改；V286）。 */
    @JsonProperty("image_ocr_enabled")
    Boolean imageOcrEnabled,

    /**
     * 图片管线方案（null = 不改；{@code default} | {@code smartocr}，其它值 ⇒ 400；V286）。
     *
     * <p>⚠️ 与 {@code retriever_engine} 不同，这里<b>不接受空串清空</b> —— 该列 NOT NULL
     * 且值域是枚举，「清空」没有意义（要回默认就传 {@code default}）。
     */
    @JsonProperty("image_pipeline")
    String imagePipeline,

    /** Wiki 配置 JSON（null = 不改）。开关在 {@code indexing_strategy.wiki_enabled}。 */
    @JsonProperty("wiki_config")
    String wikiConfig,

    /** 图片处理配置 JSON（null = 不改）。 */
    @JsonProperty("image_processing_config")
    String imageProcessingConfig,

    /** ASR 配置 JSON（null = 不改）。 */
    @JsonProperty("asr_config")
    String asrConfig,

    /** 问题生成配置 JSON（null = 不改）。 */
    @JsonProperty("question_generation_config")
    String questionGenerationConfig,

    /** 自动打标配置 JSON（null = 不改）。 */
    @JsonProperty("auto_tag_config")
    String autoTagConfig,

    /** 知识库画像配置 JSON（null = 不改）。 */
    @JsonProperty("profile_config")
    String profileConfig,

    /** FAQ 配置 JSON（null = 不改）。 */
    @JsonProperty("faq_config")
    String faqConfig,

    /** 绑定的向量库 ID（null = 不改；"" = 解绑）。 */
    @JsonProperty("vector_store_id")
    String vectorStoreId,

    /** 绑定的存储后端 ID（null = 不改；"" = 解绑）。 */
    @JsonProperty("storage_backend_id")
    String storageBackendId
) {
    /**
     * 6 参兼容构造器（既有测试用；其余字段恒 null = 不改）。
     */
    public UpdateKnowledgeBaseRequest(
        String name, String description, String chunkingConfig,
        String embeddingModelId, String summaryModelId, String retrieverEngine
    ) {
        this(name, description, chunkingConfig, embeddingModelId, summaryModelId, retrieverEngine,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 9 参兼容构造器（含图片管线三项，不含本次新增的 9 项配置）。
     *
     * <p>⚠️ 既有测试 {@code KbImagePipelineConfigDbTest} 按位置传 9 个参数。
     * 新增 9 项恒 null = 不改。
     */
    public UpdateKnowledgeBaseRequest(
        String name, String description, String chunkingConfig,
        String embeddingModelId, String summaryModelId, String retrieverEngine,
        Boolean imageCaptionEnabled, Boolean imageOcrEnabled, String imagePipeline
    ) {
        this(name, description, chunkingConfig, embeddingModelId, summaryModelId, retrieverEngine,
            imageCaptionEnabled, imageOcrEnabled, imagePipeline,
            null, null, null, null, null, null, null, null, null);
    }
}
