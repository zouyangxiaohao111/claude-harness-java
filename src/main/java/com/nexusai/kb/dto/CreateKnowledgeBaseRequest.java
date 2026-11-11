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
    String indexingStrategy
) {
    /**
     * 6 参兼容构造器（不带索引策略）。
     *
     * <p>⚠️ 仅供**既有测试**使用 —— 那些用例只关心名称/类型/模型，不涉及索引策略。
     * 生产链路（`KnowledgeBaseController#create` → Jackson 反序列化）走规范构造器。
     * 保留它是为了让「加一个可选字段」不用改 18 处调用点（纯噪音改动）。
     */
    public CreateKnowledgeBaseRequest(
        String name, String description, String type,
        String chunkingConfig, String embeddingModelId, String summaryModelId
    ) {
        this(name, description, type, chunkingConfig, embeddingModelId, summaryModelId, null);
    }
}
