package com.nexusai.application.agent.prompt;

import java.util.List;
import java.util.Map;

/**
 * System Prompt 三路上下文部件 · 对齐 CC {@code fetchSystemPromptParts} 返回三元组
 * （CC original: {@code { defaultSystemPrompt: string[]; userContext: {[k]: string}; systemContext: {[k]: string} }}
 * (Open-ClaudeCode/src/utils/queryContext.ts:56-73)）。
 *
 * <p>构成 API cache-key 前缀的三个 context 块（CC queryContext.ts:30-43 注释）：
 * <ul>
 *   <li>{@code defaultSystemPrompt} —— 默认系统提示数组（customSystemPrompt 定义时短路为 []，
 *       I-13）；调用方在 custom 场景用 customSystemPrompt 替代它组装最终 systemPrompt</li>
 *   <li>{@code userContext} —— user 通道上下文（claudeMd? + currentDate），前置 meta user 消息</li>
 *   <li>{@code systemContext} —— system 通道上下文（gitStatus? + cacheBreaker?），并入 systemPrompt</li>
 * </ul>
 *
 * @param defaultSystemPrompt 默认系统提示数组元素（CC original: defaultSystemPrompt, queryContext.ts:57）
 * @param userContext         user 通道上下文 map（CC original: userContext, queryContext.ts:58）
 * @param systemContext       system 通道上下文 map（CC original: systemContext, queryContext.ts:59）
 */
public record SystemPromptParts(
    List<String> defaultSystemPrompt,
    Map<String, String> userContext,
    Map<String, String> systemContext
) {
}
