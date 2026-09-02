package com.nexusai.application.agent.subagent;

import java.util.List;

/**
 * Agent transcript 中的一条消息 · 对齐 CC {@code utils/messages.ts} Message 简化.
 *
 * <p>实现里通常映射 user/assistant/text/tool_use 等; CC 用 type 字段判别, 这里用 role + content
 * 简化. isApiError 标识该消息为 API 错误消息 (CC isApiErrorMessage 跳过语义).
 *
 * <p><b>[S5 P0 差异 4] record 契约扩展</b>: 对齐 CC sessionStorage.ts transcript 消息结构,
 * 补 6 字段 (用户已授权未上线可破约, 不留兼容壳):
 * <ul>
 *   <li>{@code agentId} —— CC original: {@code msg.agentId} (Open-ClaudeCode/src/utils/sessionStorage.ts:4201
 *       {@code msg.agentId === agentId && msg.isSidechain}) — 过滤本 agent 消息</li>
 *   <li>{@code isSidechain} —— CC original: {@code msg.isSidechain} (sessionStorage.ts:995/1042/1225)
 *       — sidechain (sub-agent transcript) 消息标记</li>
 *   <li>{@code uuid} —— CC original: {@code msg.uuid} (sessionStorage.ts:4210-4214 leaf 查找)</li>
 *   <li>{@code parentUuid} —— CC original: {@code msg.parentUuid} (sessionStorage.ts:2069-2094
 *       buildConversationChain parentUuid 链重建)</li>
 *   <li>{@code toolCalls} —— CC original: assistant 消息 content 内 {@code tool_use} 块 id/name/input
 *       (messages.ts:2795 filterUnresolvedToolUses 收集 tool_use id)</li>
 *   <li>{@code toolCallId} —— CC original: {@code tool_result.tool_use_id} (runAgent.ts:875-880
 *       filterIncompleteToolCalls 收集有结果的 tool_use id)</li>
 * </ul>
 */
public record AgentMessage(
    String role,        // "user" | "assistant" | "system" | "tool"
    String content,
    boolean isApiError,
    String agentId,     // CC original: msg.agentId (sessionStorage.ts:4201)
    boolean isSidechain, // CC original: msg.isSidechain (sessionStorage.ts:995)
    String uuid,         // CC original: msg.uuid (sessionStorage.ts:4210)
    String parentUuid,   // CC original: msg.parentUuid (sessionStorage.ts:2069)
    List<ToolCallInfo> toolCalls, // CC original: tool_use blocks (messages.ts:2795)
    String toolCallId    // CC original: tool_result.tool_use_id (runAgent.ts:875)
) {
    /** CC original: tool_use 块 id/name/input (messages.ts:2795 block.id / block.name). */
    public record ToolCallInfo(String id, String name, String arguments) {}

    public static AgentMessage of(String role, String content) {
        return new AgentMessage(role, content, false, null, false, null, null, List.of(), null);
    }
}
