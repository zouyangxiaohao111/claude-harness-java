package com.nexusai.application.agent.tool;

import java.util.Objects;

/**
 * [R32-b15 Stage 2 C5] 父 assistant message lineage 句柄 ·
 * 对齐 CC {@code Open-ClaudeCode/src/services/tools/toolOrchestration.ts:130-139,152-172}
 * 按 {@code tool_use.id} 查找父 assistant message.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * Java 端此前 tool execution 无法按 tool_use_id 稳定关联到源 assistant message,
 * 导致 telemetry 归因 / UI 渲染 / transcript 嵌套视图缺失父 envelope 信息.
 * 本 record 提供不可变父句柄, 由 {@link StreamingToolExecutor#add} 重载接受,
 * 写入 {@link com.nexusai.model.session.dto.ChatMessageDto#assistantMessageId}
 * 与 {@code sourceToolAssistantUUID} 等归因字段.
 *
 * <p><b>字段</b>:
 * <ul>
 *   <li>{@link #assistantMessageId} — Java 端稳定 ID (UUID), 由 LlmAgentLoop
 *       在 turn 开始预分配 (CC {@code message.id} 镜像). 用于 tool_result DTO 与
 *       telemetry 归因.</li>
 *   <li>{@link #requestId} — provider request ID (对齐 CC
 *       {@code runToolUse(...).assistantMessage.requestId}). provider 未暴露时为 null.</li>
 * </ul>
 *
 * <p><b>线性查找</b>: 不维护反向索引 —— LlmAgentLoop 维护 turn-local
 * {@code toolUseId → assistantMessageId} 映射, 顶层入口 {@code runTools}
 * 用 tool_call.id 索引后构造本 record 传给
 * {@link StreamingToolExecutor#add(ToolUseBlock, ToolParent, java.util.function.Consumer)}.
 *
 * <p><b>CC 偏离论证</b>: CC {@code AssistantMessage} 含 message.content /
 * finishReason / message.metadata 等多字段; Java 端 AssistantMessage (record)
 * 仅 4 字段 (content / finishReason / toolCalls / reasoning) 且 provider 不暴露
 * requestId. 因此本 record 只承载 Java 端可可靠提供的
 * {@code assistantMessageId} (+ 可选 {@code requestId}), 完整 AssistantMessage
 * envelope 数据不在 C5 范围 (R3 后续). 不伪造 requestId (CLAUDE.md 规则 12 · Fail loud).
 *
 * <p><b>线程安全</b>: 不可变 record, 多线程只读无锁.
 *
 * @since R32-b15 Stage 2
 */
public record ToolParent(
    String assistantMessageId,
    String requestId
) {

    public ToolParent {
        // CLAUDE.md 规则 12 · Fail loud: assistantMessageId 是 lineage 必填字段,
        // null 时上层必须显式构造 (不允许默认值掩盖 lineage 缺口).
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            throw new IllegalArgumentException("assistantMessageId is null/blank");
        }
    }

    /**
     * 工厂: 仅含 assistantMessageId (provider 未暴露 requestId 时使用).
     *
     * @param assistantMessageId 稳定父消息 ID (LlmAgentLoop 预分配)
     * @return ToolParent 实例
     */
    public static ToolParent of(String assistantMessageId) {
        return new ToolParent(
            Objects.requireNonNull(assistantMessageId, "assistantMessageId"),
            null);
    }

    /**
     * 工厂: 含 requestId (provider 在 {@link com.nexusai.infra.llm.AssistantMessage}
     * 中暴露时使用).
     */
    public static ToolParent of(String assistantMessageId, String requestId) {
        return new ToolParent(
            Objects.requireNonNull(assistantMessageId, "assistantMessageId"),
            requestId);
    }
}
