package com.nexusai.model.session.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.impl.web.SearchEngine.SearchHit;
import com.nexusai.repository.session.entity.WebSearchResultRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * WebSearch 详细搜索结果查询 DTO · 通道3 查询 API 出站。
 *
 * <p>{@code results} 为 {@code List<{title, url}>}（对齐 CC {@code searchHitSchema}，
 * WebSearchTool.ts:43-46），从 {@code websearch_results.results} JSON 列反序列化。
 * JSON 反序列化失败 → 空列表 + warn（fail-loud），不使查询 API 500。
 *
 * <p><b>已过期（2026-08-23）</b>：通道3 查询 API（前端仅从工具输入输出 tool_result §33.2
 * outputShape 抽取，不消费历史查询）。仅由已 {@code @Deprecated}
 * {@link com.nexusai.apis.session.WebSearchResultController} 引用，保留供审计。
 */
@Deprecated
public record WebSearchResultDto(
        String id,
        String sessionId,
        String toolUseId,
        String query,
        List<SearchHit> results,
        Double durationSeconds,
        String createdAt
) {
    private static final Logger log = LoggerFactory.getLogger(WebSearchResultDto.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 静态工厂：Entity → DTO（results JSON 反序列化；脏数据兜底为空列表 + warn）。 */
    public static WebSearchResultDto from(WebSearchResultRecord r) {
        List<SearchHit> hits = List.of();
        if (r.getResults() != null && !r.getResults().isBlank()) {
            try {
                hits = MAPPER.readValue(r.getResults(),
                        new TypeReference<List<SearchHit>>() {});
            } catch (Exception e) {
                log.warn("[WebSearchResultDto] results JSON 反序列化失败, 返回空列表（不使查询 API 失败）: "
                        + "toolUseId={} err={}", r.getToolUseId(), e.getMessage());
                hits = List.of();
            }
        }
        return new WebSearchResultDto(r.getId(), r.getSessionId(), r.getToolUseId(),
                r.getQuery(), hits, r.getDurationSeconds(), r.getCreatedAt());
    }
}
