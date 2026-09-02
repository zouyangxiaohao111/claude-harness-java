package com.nexusai.application.agent.workflow;

/**
 * host 工厂 · CC original: {@code HostFactory}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:129-133)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一。由核心侧提供：从工具调用的核心上下文构造
 * {@link WorkflowHostContext}。参数对引擎是不透明的（CC {@code unknown}）；
 * 核心侧 hostFactory 知道真实类型（toolUseContext/canUseTool/parentMessage）。
 *
 * <p><b>W-1d 装配域</b>：{@link WorkflowPortsImpl#hostFactory()} 提供实现
 * （makeHostFactory 等价，src/workflow/ports.ts:42-65）。W-1c 引擎不使用 hostFactory。
 */
public interface HostFactory {

    /**
     * 构造 host 上下文 · CC original: ports.ts:129-133。
     *
     * @param args 不透明参数（{@code context} = 核心侧 ToolUseContext，canUseTool/parentMessage 透传）
     * @return {@link WorkflowHostContext}（handle + cwd + budgetTotal + toolUseId）
     */
    WorkflowHostContext create(HostFactoryArgs args);

    /**
     * host 工厂入参 · CC original: ports.ts:130-132
     * {@code {context, canUseTool, parentMessage}}（三者对引擎均 {@code unknown}）。
     *
     * @param context       CC original: {@code context} — 工具调用核心上下文（核心侧知类型）
     * @param canUseTool    CC original: {@code canUseTool} — 工具可用判定（不透明透传）
     * @param parentMessage CC original: {@code parentMessage} — 父消息（可空）
     */
    record HostFactoryArgs(Object context, Object canUseTool, Object parentMessage) {
    }
}
