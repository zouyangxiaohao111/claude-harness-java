package com.nexusai.kb.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文档亲和视图（批次 W9）· 对齐源侧 {@code types.MemoryDocView}（{@code memory.go:1060}）。
 *
 * @param id              亲和行 ID
 * @param knowledgeId     知识条目 ID
 * @param knowledgeBaseId 所属知识库
 * @param title           标题（列表展示用）
 * @param hits            被引用几次
 * @param lastUsedAt      最后一次被引用
 */
public record KbMemoryDocView(
    String id,
    @JsonProperty("knowledge_id") String knowledgeId,
    @JsonProperty("knowledge_base_id") String knowledgeBaseId,
    String title,
    int hits,
    @JsonProperty("last_used_at") String lastUsedAt
) {
}
