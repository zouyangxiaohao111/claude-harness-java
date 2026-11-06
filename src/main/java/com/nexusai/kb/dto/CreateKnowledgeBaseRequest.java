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
    String summaryModelId
) {
}
