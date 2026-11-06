package com.nexusai.kb.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆条目 DTO（批次 W9）· 对齐源侧 {@code types.MemoryItem}（{@code memory.go:288}）。
 *
 * <p>⚠️ {@code tenant_id} / {@code subject_id} 刻意不出站：行已经按主体过滤过，
 * UI 不该需要「忽略某个字段」。源侧注释为此专门说明过。
 */
public record KbMemoryItemDto(
    String id,
    String kind,
    String content,
    String topic,
    @JsonProperty("normalized_key") String normalizedKey,
    int importance,
    String origin,
    String status,
    @JsonProperty("source_session_id") String sourceSessionId,
    @JsonProperty("source_message_id") String sourceMessageId,
    @JsonProperty("valid_from") String validFrom,
    @JsonProperty("invalid_at") String invalidAt,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("superseded_by") String supersededBy,
    @JsonProperty("last_used_at") String lastUsedAt,
    @JsonProperty("use_count") int useCount,
    @JsonProperty("created_at") String createdAt
) {
}
