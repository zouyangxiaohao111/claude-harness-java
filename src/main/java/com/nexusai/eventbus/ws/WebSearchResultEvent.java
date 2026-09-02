package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.tool.impl.web.SearchEngine.SearchHit;

import java.util.List;

/**
 * <b>2026-08-23 前端仅从工具输入输出（tool_result §33.2 outputShape）抽取 WebSearch 格式，
 * 本事件前端不消费、不再发布；保留类供未来审计，不物理删除。</b>
 *
 * <p>WebSearch 详细原始搜索结果事件 · 通道2（前端审计/展示，原始 hits 不入 LLM 返回）。
 *
 * <p>topic: {@code /topic/sessions/{sessionId}/websearch-results}（session 级，对齐 token-warning
 * 先例，LlmAgentLoop.java:1783-1784）。{@code userMessageId = null}（工具侧无该 id，@JsonInclude
 * NON_NULL 自动省略）。
 *
 * <p><b>CC 对齐</b>：{@code results} 元素 = {@code searchHitSchema} {@code {title, url}}
 * （WebSearchTool.ts:43-46）；{@code toolUseId} = {@code ToolUseBlock.id()}（CC tool_use_id 为
 * server-tool-use 内部 id，WebSearchTool.ts:49；Java 无该通道，取调用块 id 作关联键，前端凭此
 * 与消息流 tool_use 块绑定）。
 *
 * <p>出站 JSON（示例）：
 * <pre>
 * {
 *   "type": "websearch.result",
 *   "sessionId": "sess-uuid",
 *   "ts": 1755916800000,
 *   "toolUseId": "toolu_01Hxxxx",
 *   "query": "claude code",
 *   "results": [ { "title": "Anthropic", "url": "https://www.anthropic.com" } ],
 *   "durationSeconds": 0.42
 * }
 * </pre>
 */
@Deprecated
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSearchResultEvent extends StreamEvent {

    private final String toolUseId;
    private final String query;
    private final List<SearchHit> results;
    private final double durationSeconds;

    public WebSearchResultEvent(String sessionId, String toolUseId, String query,
                                List<SearchHit> results, double durationSeconds) {
        super("websearch.result", sessionId, null);   // userMessageId = null（session 级 topic）
        this.toolUseId = toolUseId;
        this.query = query;
        this.results = results;
        this.durationSeconds = durationSeconds;
    }

    public String getToolUseId() { return toolUseId; }
    public String getQuery() { return query; }
    public List<SearchHit> getResults() { return results; }
    public double getDurationSeconds() { return durationSeconds; }
}
