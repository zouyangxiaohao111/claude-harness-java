package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 混合检索请求 · 对齐 WeKnora {@code types.SearchParams}（{@code search.go:259-291}）。
 *
 * <p><b>本批实现的字段</b>（其余依赖 B4 重排 / B9 标签）：
 * <table>
 *   <tr><th>字段</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code query_text}</td><td>必填</td><td>查询文本</td></tr>
 *   <tr><td>{@code match_count}</td><td>10</td><td>返回条数；源侧 x-agent 上限 30</td></tr>
 *   <tr><td>{@code disable_vector_match}</td><td>false</td><td>关掉向量路（只走关键词）</td></tr>
 *   <tr><td>{@code disable_keywords_match}</td><td>false</td><td>关掉关键词路（只走向量）</td></tr>
 *   <tr><td>{@code knowledge_ids}</td><td>—</td><td>限定在这些文档内检索</td></tr>
 * </table>
 *
 * <p><b>未实现的字段</b>（⛔ 不静默忽略 —— 传了会 400）：
 * {@code vector_threshold} / {@code keyword_threshold}（B4 阈值调优）、
 * {@code rerank}（B4）、{@code tag_ids} / {@code scope_tag_ids}（B9）、
 * {@code only_recommended}（B9 推荐位）、{@code query_embedding}（调用方直接给向量，
 * 本批由服务端算）、{@code knowledge_base_ids}（跨库检索）、
 * {@code skip_context_enrichment}（B4 上下文扩展）。
 *
 * @param queryText            查询文本
 * @param matchCount           返回条数
 * @param disableVectorMatch   关闭向量召回
 * @param disableKeywordsMatch 关闭关键词召回
 * @param knowledgeIds         限定文档 ID 列表（空 = 整个库）
 * @param rerankModelId        重排模型 ID（null/空 = 不重排，直接用 RRF 顺序）
 * @param rerankTopN           重排后保留条数（null = 用模型级默认）
 */
public record HybridSearchRequest(
    @JsonProperty("query_text") String queryText,
    @JsonProperty("match_count") Integer matchCount,
    @JsonProperty("disable_vector_match") Boolean disableVectorMatch,
    @JsonProperty("disable_keywords_match") Boolean disableKeywordsMatch,
    @JsonProperty("knowledge_ids") java.util.List<String> knowledgeIds,
    @JsonProperty("rerank_model_id") String rerankModelId,
    @JsonProperty("rerank_top_n") Integer rerankTopN
) {
}
