package com.nexusai.kb.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆设置（批次 W9）· 对齐源侧 {@code types.MemorySettings}（{@code memory.go:975}）。
 *
 * <p><b>⭐ 为什么把三个开关都返回</b>：UI 直接渲染这个值，不再自己做合并。
 * 源侧注释：「so 'why is my memory off' has exactly one answer」。
 * <p>{@code userEnabled} 在工作区关着时<b>仍然返回</b> —— 管理员重新打开后，
 * 用户自己的开关位置不丢。
 */
public record KbMemorySettingsDto(
    @JsonProperty("workspace_enabled") boolean workspaceEnabled,
    @JsonProperty("user_enabled") boolean userEnabled,
    /** 实际是否生效 = workspaceEnabled ∧ userEnabled。 */
    boolean effective,
    @JsonProperty("write_mode") String writeMode,
    @JsonProperty("item_count") int itemCount,
    @JsonProperty("max_items") int maxItems
) {
}
