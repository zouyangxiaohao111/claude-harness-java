package com.nexusai.application.agent.tool.impl;

/**
 * s31 R-P1-7: 子 Agent 未找到异常 · 对齐 CC AgentTool.tsx:345-353.
 *
 * <p>SubagentExecutor 解析 user-specified {@code subagent_type} 时, 若 resolveAgentDefinition
 * 返回 null 则抛此异常. 之前审计偏差: 静默 fallback 至 general-purpose, 模型以为指定了 X
 * 实则跑通用. 现在显式抛错 → 外层 (SubagentTool.executeSync / executeAsync) catch 后
 * 转 ToolResult.error 返回父 Agent, 模型可调整策略.
 *
 * <p>包内可见 (package-private) — 仅 SubagentExecutor / SubagentTool 内部使用, 暴露给父
 * Agent 的字符串由 outer try-catch 标准化.
 */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String message) {
        super(message);
    }
}
