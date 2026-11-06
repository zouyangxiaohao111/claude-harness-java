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
    String retrieverEngine
) {
}
