package com.nexusai.kb.dto.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 创建/更新向量库请求体（批次 W8）。
 *
 * <p>⚠️ 用 {@code Map} 而不是强类型字段：7 种引擎的连接字段各不相同（见
 * {@code KbVectorStoreTypes}），强类型会逼出一堆「别的引擎用不上」的 null 字段。
 * 校验在 Service 里按「该类型的必填字段清单」动态做。
 *
 * <p>⚠️ PATCH 语义：{@code null} = 不改。三个字段都是对象/字符串，Jackson 能区分
 * 「没传」与「传了 null」—— 但这里统一按「没传 = null = 不改」，与模型端点一致。
 */
public record VectorStoreRequest(
    String name,
    @JsonProperty("engine_type") String engineType,
    @JsonProperty("connection_config") Map<String, Object> connectionConfig,
    @JsonProperty("index_config") Map<String, Object> indexConfig
) {
}
