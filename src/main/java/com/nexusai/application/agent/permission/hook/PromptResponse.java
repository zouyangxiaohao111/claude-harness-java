package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Prompt 响应 · 对齐 CC {@code Open-ClaudeCode/src/types/hooks.ts:44-47}
 * {@code PromptResponse} (type alias)。
 *
 * <p>WHY: CC execCommandHook 收到 {@link PromptRequest} 后, 经 {@code requestPrompt} 回调
 * 得到 {@code PromptResponse}, 序列化写回 hook stdin (每行一个 JSON + '\n'),
 * hook 据此继续执行。
 *
 * <p><b>CC 真源字段 (types/hooks.ts:44-47)</b>:
 * <ul>
 *   <li>{@code prompt_response} (:45) — 请求 ID (回显 promptRequest.prompt)</li>
 *   <li>{@code selected} (:46) — 用户选中的选项 key</li>
 * </ul>
 *
 * @param promptResponse CC original: {@code prompt_response} (types/hooks.ts:45);
 *                       请求 ID, 写回 stdin 时与 {@link PromptRequest#prompt()} 对应
 * @param selected       CC original: selected (types/hooks.ts:46); 用户选中选项 key
 */
public record PromptResponse(
    @JsonProperty("prompt_response") String promptResponse,
    String selected
) {
}
