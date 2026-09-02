package com.nexusai.application.agent.workflow;

import java.util.concurrent.CompletableFuture;

/**
 * agent() hook 的后端 · CC original: {@code AgentRunner}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:40-45)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「后台任务执行」。引擎 hooks.agent 在 registry 无 adapter 时
 * 回落此路径；P0 按 DEC-P0-04 fail-fast（registry 必设，达此路径即抛错），P1 注入真 adapter。
 *
 * @see WorkflowPortsImpl
 */
public interface AgentRunner {

    /**
     * 运行 agent 至结果 · CC original: {@code runAgentToResult(params, host): Promise<AgentRunResult>}
     * (ports.ts:41-44)。
     *
     * @param params CC original: {@code params} — AgentRunParams（prompt/schema/model/...）
     * @param host   CC original: {@code host} — 不透明 HostHandle（透传，引擎不检查内部）
     * @return AgentRunResult 三态（ok/skipped/dead）CompletableFuture
     */
    CompletableFuture<AgentRunResult> runAgentToResult(AgentRunParams params, HostHandle host);
}
