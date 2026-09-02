package com.nexusai.model.provider.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 响应：Provider 完整信息（嵌套 models） */
public record ProviderDto(
    String id,
    String name,
    ProviderType type,
    String baseUrl,
    String apiKeyMasked,
    Map<String, String> extraHeaders,
    boolean enabled,
    List<ModelDto> models,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
