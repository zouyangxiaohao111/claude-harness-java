package com.nexusai.kb.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 主题计数视图（批次 W9）· 对齐源侧 {@code types.MemoryTopicView}（{@code memory.go:1031}）。
 *
 * @param id         主题计数行 ID
 * @param topic      主题标签
 * @param aliases    同一主题的不同说法
 * @param hits       被问到几次
 * @param threshold  达到这个次数会自动提升为长期关注。⚠️ 必须返回：
 *                   用户看到 2/3 才知道「再问一次就记住了」，只给 hits 是半个信息。
 * @param lastSeenAt 最后一次问到的时刻
 */
public record KbMemoryTopicView(
    String id,
    String topic,
    List<String> aliases,
    int hits,
    int threshold,
    @JsonProperty("last_seen_at") String lastSeenAt
) {
}
