package com.nexusai.kb.config.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 知识库域配置（{@code kb_config}）· V273 建表 · 批次 R0。
 *
 * <p><b>为什么要独立成表</b>（用户 2026-11-05 裁定「单开一张 kb_config」）：
 * 这些配置只服务知识库，塞在公共 {@code settings} 单行里会有两个代价 ——
 * ① 多个会话同时改 {@code settings} 会互相覆盖（W8 已实际发生过一次字段被整体覆盖）；
 * ② 将来把知识库整块拆出去时，还要把公共表一起带走。
 *
 * <p><b>⭐ 什么该进来、什么不该</b>：
 * <ul>
 *   <li><b>进本表</b>：知识库域独有的全局默认 —— 检索驱动 / 默认存储后端 /
 *       模型全局默认（向量 + 重排）/ 记忆四开关</li>
 *   <li><b>不进本表</b>：其它功能域的东西。{@code settings} 是跨功能共享的单行，
 *       多会话并发 PUT 会整体覆盖（W8 实际丢过字段）</li>
 * </ul>
 *
 * <p>⚠️ <b>2026-11-05 最终裁定</b>：原 {@code settings.kb_*} 共 8 列全部搬到本表，
 * settings 一个不留；两个模型字段去掉 {@code kb_} 前缀（{@code embedding_model_id} /
 * {@code rerank_model_id}）。旧版 javadoc 里「模型不从 settings 搬」的说法已作废。
 *
 * <p>⚠️ 单行表：{@code id} 恒为 {@value #SINGLETON_ID}（与 {@code settings} 同款）。
 */
@Table(
    value = "kb_config",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbConfigRecord extends KbBaseRecord {

    /** 单行主键。 */
    public static final String SINGLETON_ID = "kb";

    @Id
    private String id;

    /** 全局默认检索驱动（null = 未配 ⇒ 解析时回落 sqlite）。 */
    private String retrieverEngine;

    /** 默认存储后端 ID（null = 回落内置本地后端）。 */
    private String defaultStorageBackendId;

    /**
     * 全局默认向量模型 ID（null = 未显式配置 ⇒ 运行时自动选用第一个启用的 embedding 模型）。
     *
     * <p>⚠️ 与 {@code kb_knowledge_base.embedding_model_id} 的分工：
     * 本列是<b>全局默认</b>，库上的列是<b>单库覆盖</b>；解析顺序 = 请求显式 → 单库 → 本列 → 自动选用。
     */
    private String embeddingModelId;

    /** 全局默认重排模型 ID（null = 自动选用第一个启用的 rerank 模型；都没有 = 不重排）。 */
    private String rerankModelId;

    /** 长期记忆工作区开关（null = 未配 ⇒ 按 true）。 */
    private Integer memoryEnabled;

    /** 记忆写入模式：explicit_only（默认）| auto。 */
    private String memoryWriteMode;

    /** 记忆容量上限（null/≤0 = 默认 200）。 */
    private Integer memoryMaxItems;

    /** 兴趣提升阈值（null/≤0 = 默认 3）。 */
    private Integer memoryInterestThreshold;

    // ── 问答链参数（批次 QA-1 · V277）────────────────────────────────
    // 全部可空，NULL = 用代码默认值（读侧兜底）。⛔ DDL 不写 DEFAULT，否则
    // 「未配置」与「配成默认值」无法区分。

    /** 每路检索返回条数（NULL ⇒ 30）。 */
    private Integer embeddingTopK;

    /** 向量相似度下限（NULL ⇒ 0.2；0 = 不过滤）。 */
    private Double vectorThreshold;

    /** 关键词分数下限（NULL ⇒ 0.3；0 = 不过滤）。 */
    private Double keywordThreshold;

    /** 重排分数下限（NULL ⇒ 0.3；0 = 不过滤）。 */
    private Double rerankThreshold;

    /** 重排后保留条数（NULL ⇒ 30）。 */
    private Integer rerankTopK;

    /** 最终进上下文的段数上限（NULL ⇒ 12；配成与 rerankTopK 相同 = 与源侧一致）。 */
    private Integer contextMaxChunks;

    /** 多轮窗口（NULL ⇒ 5；**0 = 显式关闭多轮**，与 NULL 语义不同）。 */
    private Integer maxRounds;

    /** 查询改写开关（NULL ⇒ 开）。 */
    private Integer enableRewrite;

    /** 查询扩展开关（NULL ⇒ 开）。 */
    private Integer enableQueryExpansion;

    /** FAQ 优先策略（NULL ⇒ 关）。 */
    private Integer faqPriorityEnabled;

    /** FAQ 分数加成（NULL ⇒ 1.0 = 不加成）。 */
    private Double faqScoreBoost;

    /** FAQ 精确命中阈值（用 boost 前分数比较；NULL ⇒ 0.9）。 */
    private Double faqDirectAnswerThreshold;

    /** 联网检索结果条数（NULL ⇒ 5）。 */
    private Integer webSearchMaxResults;

    /** 是否抓取网页正文（NULL ⇒ 关）。 */
    private Integer webFetchEnabled;

    /** 抓取正文前 N 条（NULL ⇒ 3）。 */
    private Integer webFetchTopN;

    /** DuckDB 数据分析开关（NULL ⇒ 关）。 */
    private Integer dataAnalysisEnabled;

    /** 上下文模板（NULL ⇒ 走本仓内置格式）。 */
    private String contextTemplate;

    /** 非检索意图的系统提示词覆盖（JSON）。 */
    private String intentPrompts;

    /**
     * 主对话「引入知识库」开关（批次 QA-12）。
     *
     * <p>1 = 主线程可用 KB 专属工具（{@code search_knowledge} / {@code list_documents} /
     * {@code read_document} / {@code query_knowledge_graph}）；NULL/0 = 不引入。
     *
     * <p>⚠️ 与会话绑定 agent 的 {@code tools:} 名单**取交**：开关只决定「是否允许」，
     * agent 名单只决定「允许里用哪几个」。两者任一为否 ⇒ 不暴露。
     */
    private Integer kbToolsEnabled;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getRetrieverEngine() { return retrieverEngine; }
    public void setRetrieverEngine(String v) { this.retrieverEngine = v; }
    public String getDefaultStorageBackendId() { return defaultStorageBackendId; }
    public void setDefaultStorageBackendId(String v) { this.defaultStorageBackendId = v; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { this.embeddingModelId = v; }
    public String getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(String v) { this.rerankModelId = v; }
    public Integer getMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(Integer v) { this.memoryEnabled = v; }
    public String getMemoryWriteMode() { return memoryWriteMode; }
    public void setMemoryWriteMode(String v) { this.memoryWriteMode = v; }
    public Integer getMemoryMaxItems() { return memoryMaxItems; }
    public void setMemoryMaxItems(Integer v) { this.memoryMaxItems = v; }
    public Integer getMemoryInterestThreshold() { return memoryInterestThreshold; }
    public void setMemoryInterestThreshold(Integer v) { this.memoryInterestThreshold = v; }

    public Integer getEmbeddingTopK() { return embeddingTopK; }
    public void setEmbeddingTopK(Integer v) { this.embeddingTopK = v; }
    public Double getVectorThreshold() { return vectorThreshold; }
    public void setVectorThreshold(Double v) { this.vectorThreshold = v; }
    public Double getKeywordThreshold() { return keywordThreshold; }
    public void setKeywordThreshold(Double v) { this.keywordThreshold = v; }
    public Double getRerankThreshold() { return rerankThreshold; }
    public void setRerankThreshold(Double v) { this.rerankThreshold = v; }
    public Integer getRerankTopK() { return rerankTopK; }
    public void setRerankTopK(Integer v) { this.rerankTopK = v; }
    public Integer getContextMaxChunks() { return contextMaxChunks; }
    public void setContextMaxChunks(Integer v) { this.contextMaxChunks = v; }
    public Integer getMaxRounds() { return maxRounds; }
    public void setMaxRounds(Integer v) { this.maxRounds = v; }
    public Integer getEnableRewrite() { return enableRewrite; }
    public void setEnableRewrite(Integer v) { this.enableRewrite = v; }
    public Integer getEnableQueryExpansion() { return enableQueryExpansion; }
    public void setEnableQueryExpansion(Integer v) { this.enableQueryExpansion = v; }
    public Integer getFaqPriorityEnabled() { return faqPriorityEnabled; }
    public void setFaqPriorityEnabled(Integer v) { this.faqPriorityEnabled = v; }
    public Double getFaqScoreBoost() { return faqScoreBoost; }
    public void setFaqScoreBoost(Double v) { this.faqScoreBoost = v; }
    public Double getFaqDirectAnswerThreshold() { return faqDirectAnswerThreshold; }
    public void setFaqDirectAnswerThreshold(Double v) { this.faqDirectAnswerThreshold = v; }
    public Integer getWebSearchMaxResults() { return webSearchMaxResults; }
    public void setWebSearchMaxResults(Integer v) { this.webSearchMaxResults = v; }
    public Integer getWebFetchEnabled() { return webFetchEnabled; }
    public void setWebFetchEnabled(Integer v) { this.webFetchEnabled = v; }
    public Integer getWebFetchTopN() { return webFetchTopN; }
    public void setWebFetchTopN(Integer v) { this.webFetchTopN = v; }
    public Integer getDataAnalysisEnabled() { return dataAnalysisEnabled; }
    public void setDataAnalysisEnabled(Integer v) { this.dataAnalysisEnabled = v; }
    public String getContextTemplate() { return contextTemplate; }
    public void setContextTemplate(String v) { this.contextTemplate = v; }
    public String getIntentPrompts() { return intentPrompts; }
    public void setIntentPrompts(String v) { this.intentPrompts = v; }
    public Integer getKbToolsEnabled() { return kbToolsEnabled; }
    public void setKbToolsEnabled(Integer v) { this.kbToolsEnabled = v; }
}
