package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，
 * websearch_results 表已停写；保留实体供未来审计，不物理删除。</b>
 *
 * <p>WebSearch 详细原始搜索结果持久化记录 · 通道3（DB 存库，供前端后续查询/审计）。
 *
 * <p><b>CC 对齐</b>：{@code results} 为 JSON 数组字符串，元素对齐 {@code searchHitSchema}
 * {@code {title, url}}（WebSearchTool.ts:43-46）；{@code query}/{@code durationSeconds} 对齐
 * {@code outputSchema}（WebSearchTool.ts:56-66）。{@code toolUseId} = WebSearchTool 调用块 id
 * （CC tool_use_id 为 server-tool-use 内部 id，WebSearchTool.ts:49；Java 无该通道，取
 * {@code ToolUseBlock.id()} 作关联键，与消息流 tool_use 块绑定）。
 *
 * <p><b>主键语义</b>：{@code id} = {@code toolUseId}（单次 execute = 1 次搜索 = 1 行，天然唯一）。
 *
 * <p><b>createdAt</b>：DB {@code datetime('now')} 兜底，insert 不显式 set
 * （对齐 ChatService 插 ToolCallRecord 不 set createdAt 的既有约定）。
 */
@Deprecated
@Table("websearch_results")
public class WebSearchResultRecord {
    @Id private String id;               // = toolUseId（ToolUseBlock.id()）
    private String sessionId;            // CC 无；Java 侧归属字段（ctx.sessionId()）
    private String toolUseId;            // = WebSearchTool 调用块 id（与消息流 tool_use 块关联）
    private String query;                // CC outputSchema.query
    private String results;              // JSON array of {title, url}
    private Double durationSeconds;      // CC outputSchema.durationSeconds
    private String createdAt;            // DB default datetime('now')，insert 不 set

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getToolUseId() { return toolUseId; }
    public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getResults() { return results; }
    public void setResults(String results) { this.results = results; }
    public Double getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
