package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 检索命中项 · 对齐 WeKnora {@code types.SearchResult}（{@code search.go:159-215}）。
 *
 * <p><b>只出本批能做实的字段</b>（⛔ 不返回假值）：
 * <table>
 *   <tr><th>源字段</th><th>本批</th><th>原因</th></tr>
 *   <tr><td>{@code metadata}</td><td>不返回</td><td>依赖 B9 FAQ 元数据</td></tr>
 *   <tr><td>{@code image_info} / {@code matched_images}</td><td>不返回</td><td>依赖 B6 多模态</td></tr>
 *   <tr><td>{@code parent_chunk_id} / {@code sub_chunk_id}</td><td>不返回</td><td>父子块模式未移植</td></tr>
 *   <tr><td>{@code chunk_metadata}</td><td>不返回</td><td>B6 生成问题</td></tr>
 *   <tr><td>{@code vector_score}</td><td>不返回</td>
 *       <td>RRF 融合后原始相似度已不参与排序（源侧该字段也是 {@code json:"-"}）</td></tr>
 * </table>
 *
 * @param id               块 ID
 * @param content          块正文（⛔ 不含表头，保位置不变式）
 * @param contextHeader    表头（前端可展示为「所属章节」）
 * @param knowledgeId      所属文档 ID
 * @param knowledgeTitle   文档标题
 * @param chunkIndex       块在文档内的序号
 * @param score            融合分数 [0,1]
 * @param matchType        命中来源：vector | keyword | hybrid
 */
public record SearchResultDto(
    @JsonProperty("id") String id,
    @JsonProperty("content") String content,
    @JsonProperty("context_header") String contextHeader,
    @JsonProperty("knowledge_id") String knowledgeId,
    @JsonProperty("knowledge_title") String knowledgeTitle,
    @JsonProperty("chunk_index") int chunkIndex,
    @JsonProperty("score") double score,
    @JsonProperty("match_type") String matchType
) {
}
