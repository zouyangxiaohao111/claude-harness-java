package com.nexusai.kb.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /api/v1/knowledge/{id}/spans} 响应体 · 对齐源站
 * WeKnora {@code internal/handler/knowledge.go:785-825} 的 {@code KnowledgeSpansData}。
 *
 * <p><b>JSON 键名逐字照抄源站（snake_case）</b>，用 {@link JsonProperty} 锁死：
 * 前端 {@code frontend/src/utils/knowledgeTrace.ts} 的 {@code KnowledgeTraceNode}
 * 直接按这些键名取值，改名即静默变空。
 *
 * <p>{@code input / output / metadata} 是<b>已解析的 JSON 对象</b>（Map / List / 标量），
 * 不是字符串 —— 前端 {@code prettyJSON} 直接 {@code JSON.stringify} 渲染。
 * 落库是 TEXT，出站前由 {@code KbSpanTreeBuilder} 解析一次；解析失败时退化成原字符串
 * （⛔ 不让一条脏 JSON 把整个端点打成 500）。
 *
 * <p>⚠️ 与源站的两个已知差别（本批有意）：
 * <ol>
 *   <li>源站 {@code last_error} 还下发 {@code stage / code / message} 三个重复字段
 *       （旧前端兼容）；本仓前端 R1-B 的 {@code LastError} 只声明
 *       {@code name / error_code / error_message / finished_at} ⇒ 只下发这四个。</li>
 *   <li>时间一律 {@code yyyy-MM-dd HH:mm:ss}（本仓 kb_* 表统一形态），源站是 RFC3339。</li>
 * </ol>
 */
public record KnowledgeSpansDto(
    @JsonProperty("knowledge_id") String knowledgeId,
    @JsonProperty("attempt") int attempt,
    @JsonProperty("latest_attempt") int latestAttempt,
    @JsonProperty("parse_status") String parseStatus,
    @JsonProperty("current_attempt") int currentAttempt,
    @JsonProperty("current_stage") String currentStage,
    @JsonProperty("trace") SpanNode trace,
    @JsonProperty("last_activity_at") String lastActivityAt,
    @JsonProperty("stall_state") String stallState,
    @JsonProperty("last_error") LastError lastError
) {

    /** span 树节点（源站 {@code types.SpanTreeNode} 的扁平化）。 */
    public record SpanNode(
        @JsonProperty("span_id") String spanId,
        @JsonProperty("parent_span_id") String parentSpanId,
        @JsonProperty("name") String name,
        @JsonProperty("kind") String kind,
        @JsonProperty("status") String status,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("finished_at") String finishedAt,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("input") Object input,
        @JsonProperty("output") Object output,
        @JsonProperty("metadata") Object metadata,
        @JsonProperty("children") List<SpanNode> children
    ) {
    }

    /** 最近一次失败（源站 {@code KnowledgeSpanError}，只保留前端用到的四个键）。 */
    public record LastError(
        @JsonProperty("name") String name,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("finished_at") String finishedAt
    ) {
    }
}
