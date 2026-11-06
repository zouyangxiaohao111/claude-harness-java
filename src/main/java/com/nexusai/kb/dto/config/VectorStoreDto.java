package com.nexusai.kb.dto.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 向量库实例 DTO（批次 W8）· 对齐源侧 {@code types.VectorStoreResponse}（{@code vectorstore.go}）。
 *
 * <p>⚠️ 字段名逐字对齐源侧 JSON：{@code engine_type} / {@code connection_config} / {@code index_config}。
 * 源侧是 snake_case，本仓 DTO 惯例是 camelCase + {@code @JsonProperty} ⇒ 这里显式标注。
 */
public record VectorStoreDto(
    String id,
    String name,
    @JsonProperty("engine_type") String engineType,
    /**
     * 连接配置。⚠️ 敏感字段（password/api_key）在响应中已替换为
     * {@code KbSecrets.REDACTED}，不是真实凭据。
     */
    @JsonProperty("connection_config") Map<String, Object> connectionConfig,
    @JsonProperty("index_config") Map<String, Object> indexConfig,
    /** 源侧无此字段；本仓补 —— 前端需要知道「它现在能不能用」。 */
    @JsonProperty("available") boolean available,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt
) {
}
