package com.nexusai.application.agent.workflow;

import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.UUID;

/**
 * HostHandle 内部载荷 · CC original: {@code WorkflowHostBundle}
 * (Open-ClaudeCode/src/workflow/hostHandle.ts:12-17)。
 *
 * <p>核心侧在每次工具调用时构造；引擎不检查内部（不透明耦合缝）。
 *
 * <p>{@code canUseTool} / {@code parentMessage} 对引擎 opaque，Java 端 P0 以
 * {@code Object} 承载（真实 CanUseToolFn/AssistantMessage 类型在核心层，P1 adapter 解包时 cast）。
 *
 * @param toolUseContext CC original: {@code toolUseContext} (hostHandle.ts:13) —
 *                       复用 {@link ToolUseContext}（含 setAppState/toolUseId/agentId）
 * @param canUseTool     CC original: {@code canUseTool} (hostHandle.ts:14) — 工具可用判定（不透明）
 * @param parentMessage  CC original: {@code parentMessage?} (hostHandle.ts:15) — 父消息（可空；
 *                       panel 启动路径缺省——claudeCodeBackend 从不读它）
 * @param agentId        CC original: {@code agentId?} (hostHandle.ts:16) — 工具调用上下文 agentId
 */
public record WorkflowHostBundle(ToolUseContext toolUseContext, Object canUseTool,
                                 Object parentMessage, UUID agentId) {

    /**
     * 从 toolUseContext/canUseTool 构建 bundle · CC original: {@code buildHostBundle}
     * (hostHandle.ts:23-34)。
     *
     * <p>{@code parentMessage} 可空（panel 启动路径缺省）；{@code agentId = toolUseContext.agentId}
     * （hostHandle.ts:32）。
     *
     * @param toolUseContext 核心侧工具调用上下文（必填）
     * @param canUseTool     工具可用判定（透传）
     * @param parentMessage  父消息（可空）
     * @return 完整 WorkflowHostBundle
     */
    public static WorkflowHostBundle build(ToolUseContext toolUseContext, Object canUseTool,
                                           Object parentMessage) {
        UUID agentId = toolUseContext != null ? toolUseContext.agentId() : null;
        return new WorkflowHostBundle(toolUseContext, canUseTool, parentMessage, agentId);
    }
}
