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
    @JsonProperty("updated_at") String updatedAt,
    /**
     * 索引策略 JSON（含 {@code wiki_enabled} / {@code graph_enabled}）。
     *
     * <p>⭐ 前端靠 `indexing_strategy.wiki_enabled` 决定是否显示 Wiki / 图谱 tab
     * —— 源站 {@code KnowledgeBase.vue:99} 的 {@code isWiki} 就是这个判据。
     * 之前没返回 ⇒ 前端拿不到 ⇒ 两个 tab 永远隐藏（**静默丢失功能**，零报错）。
     *
     * <p>⚠️ 这是**读侧**字段：`PATCH` 能写、`GET` 原来读不回来，本批补上出站。
     */
    @JsonProperty("indexing_strategy") String indexingStrategy,
    /** Wiki 配置 JSON（含 {@code auto_generate} / {@code synthesis_model_id}）。 */
    @JsonProperty("wiki_config") String wikiConfig,
    /**
     * 问题生成配置（`question_generation_config`）。
     *
     * <p>⭐ 读侧必须回传：设置弹窗的「AI 问题生成」开关靠它判断当前状态。
     * 之前只写不读 ⇒ 保存成功后重新拉取仍是 `null` ⇒ **开关永远显示关闭**，
     * 而且展开的 3 个子字段永远不出现（用户以为没保存）。
     * 后端 `KbPostProcessConfig` 真消费它（`enabled` / `model_id` /
     * `question_count` / `custom_instructions`）。
     */
    @JsonProperty("question_generation_config") String questionGenerationConfig,
    /** 自动打标配置（`auto_tag_config`）；同上，后端真消费 4 键。 */
    @JsonProperty("auto_tag_config") String autoTagConfig,
    /**
     * ASR 配置（`asr_config`）。
     *
     * <p>⚠️ 本仓**零消费者**（无执行链读它）—— 回传只为让开关状态能正确回显，
     * 界面上已明确告知「保存了也不会转写」。
     */
    @JsonProperty("asr_config") String asrConfig,
    /** 画像配置（`profile_config`）；⚠️ 同样零消费者，理由同上。 */
    @JsonProperty("profile_config") String profileConfig,
    /**
     * FAQ 配置（`faq_config`）。
     *
     * <p>⭐ 读侧必须回传：FAQ 型库的「索引方式」单选项靠它判断当前值。
     * `faq_config.index_mode` **后端真消费**
     * （`KbFaqMetadata.resolveIndexMode` → `KbFaqService` 渲染 FAQ 正文）。
     */
    @JsonProperty("faq_config") String faqConfig
) {

    /**
     * 兼容构造器 · 不带最后 4 个配置字段（既有测试与旧调用点用）。
     *
     * <p>⚠️ 只为不破坏 20 参调用点（`KbWikiToolScopeThreeStateTest` 等）——
     * 新增的 4 个字段在这些场景恒 `null`，等价于「未配置」，与引入前一致。
     * ⛔ 生产代码（`KnowledgeBaseService.toDto`）必须走全参构造器，
     * 否则前端开关又会读不到值。
     */
    public KnowledgeBaseDto(
        String id, String name, String description, String type, boolean isTemporary,
        String chunkingConfig, String imageProcessingConfig, String embeddingModelId,
        String summaryModelId, String extractConfig, String vectorStoreId,
        String storageBackendId, String retrieverEngine, boolean isPinned, String pinnedAt,
        String generatedProfile, String createdAt, String updatedAt,
        String indexingStrategy, String wikiConfig
    ) {
        this(id, name, description, type, isTemporary, chunkingConfig, imageProcessingConfig,
            embeddingModelId, summaryModelId, extractConfig, vectorStoreId, storageBackendId,
            retrieverEngine, isPinned, pinnedAt, generatedProfile, createdAt, updatedAt,
            indexingStrategy, wikiConfig, null, null, null, null, null);
    }
}
