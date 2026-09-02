package com.nexusai.application.agent.workflow.agent;

import jakarta.annotation.Nullable;

/**
 * adapter 能力声明 · CC original: {@code AgentAdapterCapabilities}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:11-18)。
 *
 * <p>引擎/脚本据此降级（如后端不支持 schema → 改用 text + parse）。
 *
 * <pre>{@code
 * export type AgentAdapterCapabilities = {
 *   structuredOutput: boolean   // 支持 schema 结构化输出（agent(schema) 直接返回对象）
 *   tools?: boolean             // 支持工具调用（仅核心 agent 后端有）
 *   stream?: boolean            // 支持流式（v1 引擎不消费，预留）
 * }
 * }</pre>
 *
 * @param structuredOutput 支持 schema 结构化输出 · CC original: structuredOutput (agentAdapter.ts:13)，必填
 * @param tools            支持工具调用 · CC original: tools? (agentAdapter.ts:15)，可选（null=未声明）
 * @param stream           支持流式 · CC original: stream? (agentAdapter.ts:17)，可选（null=未声明）
 */
public record AgentAdapterCapabilities(
        boolean structuredOutput,
        @Nullable Boolean tools,
        @Nullable Boolean stream
) {

    /** 便捷构造：纯文本后端（无结构化输出/工具/流式）。 */
    public static AgentAdapterCapabilities textOnly() {
        return new AgentAdapterCapabilities(false, null, null);
    }

    /** 便捷构造：完整能力（结构化输出 + 工具调用）。 */
    public static AgentAdapterCapabilities full() {
        return new AgentAdapterCapabilities(true, true, false);
    }
}
