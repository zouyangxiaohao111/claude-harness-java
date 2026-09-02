package com.nexusai.application.agent.permission.hook;

import java.util.Map;

/**
 * Elicitation hook 响应 · 对齐 CC {@code ElicitationResponse} (utils/hooks.ts:335-336,
 * MCP SDK {@code ElicitResult} re-export: {@code { action, content? }}).
 *
 * <p><b>[Session I-2 拍板]</b>: AggregatedHookResult / ParsedHookJSONOutput 的
 * {@code elicitationResponse} / {@code elicitationResultResponse} 由 {@code Object}
 * 升级为本 record（对齐 CC 类型化; CC types/hooks.ts:138/143 content 为
 * {@code z.record(z.string(), z.unknown()).optional()} — Java 侧 {@link Map}, 可 null).
 *
 * @param action  CC original: {@code action} — Elicitation 语义为
 *                'accept'|'decline'|'cancel'（MCP SDK ElicitResult 泛化为 request/respond/notify）;
 *                非 null
 * @param content CC original: {@code content} — optional 载荷 map, 可 null（hook 只返 action）
 */
public record ElicitationResponse(String action, Map<String, Object> content) {

    public ElicitationResponse {
        if (action == null) {
            throw new IllegalArgumentException("action is null");
        }
        if (content != null) {
            content = Map.copyOf(content);
        }
    }
}
