package com.nexusai.kb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识列表分页响应 · 对齐 WeKnora {@code internal_handler.PageResponse}。
 *
 * <p><b>源侧形状</b>（{@code docs/swagger.json}）：
 * <pre>
 * { "success": true, "data": [...], "page": 1, "page_size": 20, "total": 37 }
 * </pre>
 * ⚠️ {@code data} 在 swagger 里是空 schema（{@code "data": {}}）—— 实际元素是
 * {@code types.Knowledge}。
 *
 * <p><b>为什么单独一个 record 而不是套用现有分页类型</b>：NexusAI 现有分页是
 * {@code {items, total, page, pageSize}}（camelCase），源侧是
 * {@code {data, total, page, page_size}}。复刻目标是外部调用方可无感迁移
 * ⇒ 这里按源侧形状出站，⛔ 不套用本仓分页类型。
 *
 * @param success  恒 true（源侧语义；失败走 RFC 7807，不进本结构）
 * @param data     当前页数据
 * @param page     页码（1 起）
 * @param pageSize 每页数量
 * @param total    匹配总数
 * @param <T>      元素类型
 */
public record KnowledgePageResponse<T>(
    @JsonProperty("success") boolean success,
    @JsonProperty("data") java.util.List<T> data,
    @JsonProperty("page") int page,
    @JsonProperty("page_size") int pageSize,
    @JsonProperty("total") long total
) {

    /** 构造一个成功响应。 */
    public static <T> KnowledgePageResponse<T> of(
        java.util.List<T> data, int page, int pageSize, long total) {
        return new KnowledgePageResponse<>(true, data, page, pageSize, total);
    }
}
