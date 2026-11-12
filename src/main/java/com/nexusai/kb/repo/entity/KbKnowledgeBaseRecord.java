package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 知识库主表（{@code kb_knowledge_base}）记录 · V256 建表。
 *
 * <p><b>源侧对照</b>：WeKnora v0.9.0（revision 80e8345）{@code knowledge_bases} 表的
 * <b>最终 30 列形态</b>（初始 13 列 + 12 个迁移的增删）。
 *
 * <p><b>⚠️ 为什么不是 13 列</b>：初始建表 {@code 000000_init.up.sql} 只有 13 列，但后续：
 * <ul>
 *   <li>000001 加了 {@code type / is_temporary / faq_config / question_generation_config}，
 *       <b>删了 {@code rerank_model_id}</b></li>
 *   <li>000004 删了 {@code vlm_model_id}</li>
 *   <li>000014/000016/000031/000036/000037/000043/000068/000080/000101 又加了
 *       {@code storage_provider_config / is_pinned / pinned_at / asr_config / vector_store_id /
 *       wiki_config / indexing_strategy / creator_id / storage_backend_id / auto_tag_config /
 *       profile_config / generated_profile}</li>
 * </ul>
 * 照初始表建表会漏 17 列，且多一个源侧已删除的 {@code rerank_model_id}。
 *
 * <p><b>列映射</b>（源侧 → 目标侧）：
 * <table>
 *   <tr><th>源侧列</th><th>目标列</th><th>差异</th></tr>
 *   <tr><td>created_at</td><td>create_time</td><td>zjky BaseDO 命名</td></tr>
 *   <tr><td>updated_at</td><td>update_time</td><td>zjky BaseDO 命名</td></tr>
 *   <tr><td>deleted_at (timestamp)</td><td>deleted (0/1)</td><td>zjky 用逻辑删标志，不记删除时刻</td></tr>
 *   <tr><td>tenant_id</td><td>tenant_id</td><td>—</td></tr>
 *   <tr><td>（无）</td><td>creator / updater</td><td>zjky BaseDO 新增</td></tr>
 * </table>
 *
 * <p><b>audit 字段</b>继承 {@link KbBaseRecord}，由 {@link KbAuditInsertListener} /
 * {@link KbAuditUpdateListener} 自动填充。
 *
 * <p><b>⚠️ 时间字段为什么在子类重声明</b>：MyBatis-Flex 读字段注解时不看父类
 * ⇒ {@code getCreateTime} / {@code getUpdateTime} 在本类用
 * {@code @Column(typeHandler = ...)} 覆盖，挂上 SQLite 时间格式化。
 */
@Table(
    value = "kb_knowledge_base",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbKnowledgeBaseRecord extends KbBaseRecord {

    /** 主键 · 应用层生成 UUID（源侧 uuid_generate_v4()）。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 知识库名（NOT NULL）。 */
    private String name;

    /** 描述（可空）。 */
    private String description;

    /** 类型：document | faq | wiki（空 = document）。源 000001 加。 */
    private String type;

    /** 临时知识库（对 UI 隐藏）。源 000001 加。 */
    private Integer isTemporary;

    /**
     * 分块配置 JSON。源侧默认值：
     * {@code {"chunk_size": 512, "chunk_overlap": 50, "split_markers": ["\n\n", "\n", "。"], "keep_separator": true}}
     */
    private String chunkingConfig;

    /** 图片处理配置 JSON（默认 {@code {"enable_multimodal": false, "model_id": ""}}）。 */
    private String imageProcessingConfig;

    /** 旧版对象存储配置（源侧列名 {@code cos_config}）。 */
    private String cosConfig;

    /** VLM 配置 JSON。 */
    private String vlmConfig;

    /**
     * 知识图谱存储配置 JSON（V281）。
     *
     * <p>{@code {"store":"local"|"neo4j","uri":...,"username":...,"password_encrypted":...}}；
     * 缺省 {@code "{}"} = 本地 SQLite 两张表。
     */
    private String graphStoreConfig;

    /** 抽取配置 JSON（可空）。 */
    private String extractConfig;

    /** FAQ 配置 JSON（源 000001 加）。 */
    private String faqConfig;

    /** 问题生成配置 JSON（源 000001 加）。 */
    private String questionGenerationConfig;

    /** 自动打标配置 JSON（源 000080 加）。 */
    private String autoTagConfig;

    /** 知识库画像配置 JSON（源 000101 加）。 */
    private String profileConfig;

    /** 已生成的画像 JSON（源 000101 加）。 */
    private String generatedProfile;

    /** ASR 配置 JSON（源 000031 加）。 */
    private String asrConfig;

    /** 对象存储 provider 选择（源 000014 加）。 */
    private String storageProviderConfig;

    /** Wiki 配置 JSON（源 000037 加）。 */
    private String wikiConfig;

    /** 索引策略（源 000037 加）。 */
    private String indexingStrategy;

    /**
     * 嵌入模型 ID（NOT NULL）。
     */
    private String embeddingModelId;
    /**
     * 重排模型 ID（V264 加列 · 每库可覆盖全局默认）。
     *
     * <p>⚠️ 源侧 {@code rerank_model_id} 已被迁移 000001 DROP（改成租户级 RetrievalConfig）。
     * 本仓无租户级检索配置表，但用户 2026-11-04 明确要求「每库可覆盖」⇒ 补回此列。
     * 这不是「照抄源侧」，是用户裁定优先。
     */
    private String rerankModelId;
    /** 摘要模型 ID（NOT NULL）。 */
    private String summaryModelId;

    /**
     * 检索驱动覆盖（V268 加）。
     *
     * <p>null/空 = 用全局默认（{@code settings.kb_retriever_engine}），全局也没配则 sqlite。
     * <p>⚠️ 跨驱动检索不融合 —— 一次查询只打一个驱动。
     */
    private String retrieverEngine;

    /** 绑定的向量库 ID（源 000036 加）。 */
    private String vectorStoreId;

    /** 绑定的存储后端 ID（源 000068 加）。 */
    private String storageBackendId;

    /** 创建者 ID（源 000043 加，RBAC 用）。 */
    private String creatorId;

    /** 是否置顶（源 000016 加）。 */
    private Integer isPinned;

    /** 置顶时间（源 000016 加）。 */
    private String pinnedAt;

    /**
     * 图片管线 · 是否生成 caption（V286）。
     *
     * <p>1/0；DB 默认 1（= 改造前恒定跑 caption，升级零行为变化）。
     * 消费点：{@code KbImageEnrichService}（false ⇒ 不调 caption 提示词）。
     */
    private Integer imageCaptionEnabled;

    /**
     * 图片管线 · 是否做 OCR（V286）。
     *
     * <p>1/0；DB 默认 1。消费点：{@code KbImageEnrichService}
     * （false ⇒ 不调 OCR 提示词；smartocr 下即使观察到有文字也不 OCR）。
     */
    private Integer imageOcrEnabled;

    /**
     * 图片管线方案（V286）：{@code default} | {@code smartocr}。
     *
     * <p>{@code default} = caption / OCR 各一次调用；
     * {@code smartocr} = 先一次「观察」调用（描述 + 是否含文字），只对含文字的图再跑 OCR。
     * ⛔ 值域在 {@code KnowledgeBaseService} 入口校验（其它值 400），本列 NOT NULL DEFAULT 'default'。
     */
    private String imagePipeline;

    // ── getter / setter ──────────────────────────────────────────────
    //
    // ⚠️ audit 字段（create_time / update_time / creator / updater / deleted / tenant_id）
    //    全部继承自 KbBaseRecord，其 @Column 注解标在父类**字段**上。
    //    MyBatis-Flex 的 TableInfoFactory.doGetFields 走 ClassUtil.applyAllClass
    //    （从子类一路 getSuperclass() 到 Object），父类字段会被扫到 ⇒ 不需要在子类重声明。
    //    ⛔ @Column 只能标字段，标 getter 会编译失败（annotation type not applicable）。

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getIsTemporary() { return isTemporary; }
    public void setIsTemporary(Integer isTemporary) { this.isTemporary = isTemporary; }
    public String getChunkingConfig() { return chunkingConfig; }
    public void setChunkingConfig(String chunkingConfig) { this.chunkingConfig = chunkingConfig; }
    public String getImageProcessingConfig() { return imageProcessingConfig; }
    public void setImageProcessingConfig(String v) { this.imageProcessingConfig = v; }
    public String getCosConfig() { return cosConfig; }
    public void setCosConfig(String cosConfig) { this.cosConfig = cosConfig; }
    public String getVlmConfig() { return vlmConfig; }
    public void setVlmConfig(String vlmConfig) { this.vlmConfig = vlmConfig; }

    public String getGraphStoreConfig() { return graphStoreConfig; }
    public void setGraphStoreConfig(String v) { this.graphStoreConfig = v; }
    public String getExtractConfig() { return extractConfig; }
    public void setExtractConfig(String extractConfig) { this.extractConfig = extractConfig; }
    public String getFaqConfig() { return faqConfig; }
    public void setFaqConfig(String faqConfig) { this.faqConfig = faqConfig; }
    public String getQuestionGenerationConfig() { return questionGenerationConfig; }
    public void setQuestionGenerationConfig(String v) { this.questionGenerationConfig = v; }
    public String getAutoTagConfig() { return autoTagConfig; }
    public void setAutoTagConfig(String autoTagConfig) { this.autoTagConfig = autoTagConfig; }
    public String getProfileConfig() { return profileConfig; }
    public void setProfileConfig(String profileConfig) { this.profileConfig = profileConfig; }
    public String getGeneratedProfile() { return generatedProfile; }
    public void setGeneratedProfile(String generatedProfile) { this.generatedProfile = generatedProfile; }
    public String getAsrConfig() { return asrConfig; }
    public void setAsrConfig(String asrConfig) { this.asrConfig = asrConfig; }
    public String getStorageProviderConfig() { return storageProviderConfig; }
    public void setStorageProviderConfig(String v) { this.storageProviderConfig = v; }
    public String getWikiConfig() { return wikiConfig; }
    public void setWikiConfig(String wikiConfig) { this.wikiConfig = wikiConfig; }
    public String getIndexingStrategy() { return indexingStrategy; }
    public void setIndexingStrategy(String indexingStrategy) { this.indexingStrategy = indexingStrategy; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String embeddingModelId) { this.embeddingModelId = embeddingModelId; }
    public String getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(String rerankModelId) { this.rerankModelId = rerankModelId; }
    public String getSummaryModelId() { return summaryModelId; }
    public void setSummaryModelId(String summaryModelId) { this.summaryModelId = summaryModelId; }
    public String getRetrieverEngine() { return retrieverEngine; }
    public void setRetrieverEngine(String retrieverEngine) { this.retrieverEngine = retrieverEngine; }
    public String getVectorStoreId() { return vectorStoreId; }
    public void setVectorStoreId(String vectorStoreId) { this.vectorStoreId = vectorStoreId; }
    public String getStorageBackendId() { return storageBackendId; }
    public void setStorageBackendId(String storageBackendId) { this.storageBackendId = storageBackendId; }
    public String getCreatorId() { return creatorId; }
    public void setCreatorId(String creatorId) { this.creatorId = creatorId; }
    public Integer getIsPinned() { return isPinned; }
    public void setIsPinned(Integer isPinned) { this.isPinned = isPinned; }
    public String getPinnedAt() { return pinnedAt; }
    public void setPinnedAt(String pinnedAt) { this.pinnedAt = pinnedAt; }
    public Integer getImageCaptionEnabled() { return imageCaptionEnabled; }
    public void setImageCaptionEnabled(Integer v) { this.imageCaptionEnabled = v; }
    public Integer getImageOcrEnabled() { return imageOcrEnabled; }
    public void setImageOcrEnabled(Integer v) { this.imageOcrEnabled = v; }
    public String getImagePipeline() { return imagePipeline; }
    public void setImagePipeline(String imagePipeline) { this.imagePipeline = imagePipeline; }
}
