package com.nexusai.model.provider.dto;

import java.util.Map;

/** 更新 Provider 请求体（PATCH 语义：null 字段不改） */
public record ProviderUpdateRequest(
    String name,
    ProviderType type,
    String baseUrl,
    String apiKey,
    Map<String, String> extraHeaders,
    Boolean enabled
) {}
