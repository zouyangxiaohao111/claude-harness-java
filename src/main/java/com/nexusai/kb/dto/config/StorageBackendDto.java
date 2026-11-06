package com.nexusai.kb.dto.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 存储后端 DTO（批次 W8）· 对齐源侧 {@code types.StorageBackend}（{@code storagebackend.go:26-37}）。
 *
 * <p>⚠️ {@code config} 里的 access_key_id / secret_access_key 已脱敏为 {@code ******}。
 */
public record StorageBackendDto(
    String id,
    String name,
    String provider,
    Map<String, Object> config,
    /** user | env */
    String source,
    /** active | disabled */
    String status,
    @JsonProperty("legacy_alias") boolean legacyAlias,
    /** 本机此刻能否真正使用（local=true，minio/cos=false）。 */
    @JsonProperty("available") boolean available,
    /** 不可用原因（可用时为空串）。⛔ 不能只说 available=false 而不说为什么。 */
    @JsonProperty("unavailable_reason") String unavailableReason,
    /** 是否为当前默认存储后端（settings.default_storage_backend_id 指向它）。 */
    @JsonProperty("is_default") boolean isDefault,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("updated_at") String updatedAt
) {
}
