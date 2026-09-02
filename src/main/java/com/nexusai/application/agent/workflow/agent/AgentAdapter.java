package com.nexusai.application.agent.workflow.agent;

import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * agent 后端适配器 · CC original: {@code AgentAdapter}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:58-69)。
 *
 * <p>引擎只依赖此接口；具体后端（Anthropic SDK / 核心 runAgent / OpenAI / 本地模型 / mock）
 * 实现它并注册进 {@link AgentAdapterRegistry}。initialize/dispose 是可选的
 * 生命周期钩子（连接池/资源管理），由调用方经 {@code registry.initializeAll()/disposeAll()}
 * 统一触发。
 *
 * <pre>{@code
 * export interface AgentAdapter {
 *   readonly id: string
 *   readonly capabilities: AgentAdapterCapabilities
 *   run(params: AgentRunParams, ctx: AgentAdapterContext): Promise<AgentRunResult>
 *   initialize?(): Promise<void>
 *   dispose?(): Promise<void>
 * }
 * }</pre>
 *
 * <p>Java 用 default 空实现承载可选的 initialize/dispose（CC {@code ?.()} 可选调用语义），
 * {@link AgentAdapterRegistry#initializeAll()} 对未实现者跳过。
 */
public interface AgentAdapter {

    Logger LOG = LoggerFactory.getLogger(AgentAdapter.class);

    /**
     * 唯一标识 · CC original: {@code id} (agentAdapter.ts:60) — registry 路由 / 日志。
     *
     * @return adapter 唯一 id（如 "claude-code"）
     */
    String id();

    /**
     * 能力声明 · CC original: {@code capabilities} (agentAdapter.ts:61)。
     *
     * @return 结构化输出/工具/流式能力标志
     */
    AgentAdapterCapabilities capabilities();

    /**
     * 执行一次 agent 调用 · CC original: {@code run(params, ctx)} (agentAdapter.ts:63)。
     *
     * <p>返回 ok / skipped / dead 判别联合；dead 携带死因供重试日志/审计。
     *
     * @param params agent() 入参（prompt/schema/model/agentType 等）
     * @param ctx    adapter 运行上下文（host/signal/runId/agentId/onProgress/agent abort 注册）
     * @return 未来完成的运行结果
     */
    CompletableFuture<AgentRunResult> run(AgentRunParams params, AgentAdapterContext ctx);

    /**
     * 初始化（可选）· CC original: {@code initialize?} (agentAdapter.ts:65)。
     * 连接池/资源预热，由 {@link AgentAdapterRegistry#initializeAll()} 触发。
     *
     * @return 完成信号
     */
    default CompletableFuture<Void> initialize() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 释放（可选）· CC original: {@code dispose?} (agentAdapter.ts:67)。
     * 由 {@link AgentAdapterRegistry#disposeAll()} 触发。
     *
     * @return 完成信号
     */
    default CompletableFuture<Void> dispose() {
        return CompletableFuture.completedFuture(null);
    }
}
