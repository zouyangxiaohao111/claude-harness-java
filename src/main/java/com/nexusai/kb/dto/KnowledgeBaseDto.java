package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识库响应 DTO · 出站 JSON 字段名逐字对齐 WeKnora {@code KnowledgeBaseView}。
 *
 * <p><b>契约真源</b>：源侧 {@code docs/swagger.json} 的
 * {@code internal_handler.KnowledgeBaseView}（36 个字段）。本 DTO 只取其中
 * <b>知识库本体</b>的字段；源侧的统计字段（{@code chunk_count} / {@code knowledge_count} /
 * {@code processing_count} / {@code share_count}）与权限字段（{@code my_permission} /
 * {@code capabilities}）依赖后续批次（B2/B12/B14）的表，本批<b>不返回</b>
 * —— 返回恒 0 或空会是假数据。
 *
 * <p><b>为什么用 snake_case</b>：NexusAI 全局是 Jackson 默认 camelCase，但源侧 API 契约是
 * snake_case。复刻目标是「外部调用方可无感迁移」⇒ 本 DTO 逐字段 {@code @JsonProperty} 钉死。
 * ⛔ 不给全局加 {@code PropertyNamingStrategies.SNAKE_CASE}（会改现有所有端点的契约）。
 *
 * @param id                    知识库 ID
 * @param name                  知识库名
 * @param description           描述（可空）
 * @param type                  类型：document | faq | wiki
 * @param isTemporary           是否临时库
 * @param chunkingConfig        分块配置 JSON
 * @param imageProcessingConfig 图片处理配置 JSON
 * @param embeddingModelId      嵌入模型 ID
 * @param summaryModelId        摘要模型 ID
 * @param extractConfig         抽取配置 JSON（可空）
 * @param vectorStoreId         绑定的向量库 ID（可空）
 * @param storageBackendId      绑定的存储后端 ID（可空）
 * @param retrieverEngine       检索驱动（null = 用全局默认；见 V268）
 * @param isPinned              是否置顶（W4）
 * @param pinnedAt              置顶时间（取消置顶时清空；用于多置顶项的稳定排序）
 * @param generatedProfile      已生成的知识库画像（W4）
 * @param createdAt             创建时间
 * @param updatedAt             更新时间
 */
public record KnowledgeBaseDto(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("type") String type,
    @JsonProperty("is_temporary") boolean isTemporary,
    @JsonProperty("chunking_config") String chunkingConfig,
    @JsonProperty("image_processing_config") String imageProcessingConfig,
    @JsonProperty("embedding_model_id") String embeddingModelId,
    @JsonProperty("summary_model_id") String summaryModelId,
    @JsonProperty("extract_config") String extractConfig,
    @JsonProperty("vector_store_id") String vectorStoreId,
    @JsonProperty("storage_backend_id") String storageBackendId,
    @JsonProperty("retriever_engine") String retrieverEngine,
    @JsonProperty("is_pinned") boolean isPinned,
    @JsonProperty("pinned_at") String pinnedAt,
    @JsonProperty("generated_profile") String generatedProfile,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt
) {
}
