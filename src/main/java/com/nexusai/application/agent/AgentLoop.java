package com.nexusai.application.agent;

import com.nexusai.infra.llm.ProviderConfig;

import java.util.UUID;

/**
 * AgentLoop 抽象 · Harness 层入口。
 *
 * <p>设计原则：<b>不强制单例</b>。每个实现/实例可独立绑定到不同 session / user / config。
 * 默认实现见 {@link LlmAgentLoop}（基于 LlmProvider.stream）。
 *
 * <h2>为什么不是 Spring 单例</h2>
 * <ul>
 *   <li>不同 session / user 可能需要不同配置（maxTurns / cancel policy / streaming 行为）</li>
 *   <li>未来可能扩展为：{@code MockAgentLoop}（测试）/ {@code BatchAgentLoop}（批处理）/
 *       {@code DistributedAgentLoop}（分布式 worker）等</li>
 *   <li>调用方按需 {@code new}，避免 Spring 容器与 harness 生命周期耦合</li>
 *   <li>可测试性：{@code new LlmAgentLoop(mockFactory)} 直接构造，无需 Spring 上下文</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>
 *   // 普通调用 — RunRequest.user(...) 主线程便捷
 *   LlmProviderFactory factory = ...;  // Spring 注入或手动 new
 *   AgentLoop loop = new LlmAgentLoop(factory);
 *   AgentState state = loop.run(RunRequest.user(prompt, config, "gpt-4o", systemPrompt));
 *
 *   // Session 调用 — RunRequest.session(...) 显式传 sessionId/agentId 接入权限系统
 *   // 主线程须传 agentId=null（对齐 CC !context.agentId，RunRequest.java:133-138 ER-IMP-02 R-TOK）
 *   UUID sessionUuid = ...;
 *   AgentState state = loop.run(RunRequest.session(prompt, sessionUuid, null,
 *       config, "gpt-4o", systemPrompt));
 * </pre>
 *
 * <h2>扩展点（为后面预留）</h2>
 * <ul>
 *   <li>实现可加 instance-level config（maxTurns / cancelPolicy / streamingStrategy）</li>
 *   <li>实现可加 instance-level state（per-conversation 缓存 / metrics）</li>
 *   <li>实现可换底层传输（不只是 LlmProvider.stream，可以是 WebSocket / gRPC stream）</li>
 * </ul>
 *
 * <h2>与 CC query.ts 的关系</h2>
 * 一个 {@code AgentLoop.run()} 调用对应 CC 的一个 query session（不是单个 turn）。
 * 内部循环（{@code while (state.needsFollowUp())}）对应 CC query.ts:1062 的 follow-up 检查。
 * 6 条退出路径对齐见 {@link AgentState.ExitReason}。
 *
 * @see LlmAgentLoop
 * @see AgentState
 * @see RunRequest
 *
 * <p><b>R29 简化</b>：唯一契约 = {@link #run(RunRequest)} —— 对齐 CC query.ts:219 query(params).
 * 老 4-arg / 6-arg {@code run} 重载已删除（PR 4 兼容代码全量迁移 {@code RunRequest.user(...)}
 * / {@code RunRequest.session(...)} 静态工厂）。
 */
public interface AgentLoop {

    /**
     * 跑一次 agent loop。Phase A 无工具版本会得到 1 条 assistant 消息后退出。
     *
     * <p><b>R29 唯一契约</b>：必传 {@link RunRequest}（含 12 字段：userPrompt / config / modelName /
     * querySource / sessionId / agentId / systemPrompt / maxTurns / taskBudget / fallbackModel /
     * skipCacheWrite / maxOutputTokensOverride）。sessionId / agentId 为 null → 权限系统不可用。
     *
     * <p>{@link LlmAgentLoop} 内部把 sessionId/agentId 透传给
     * {@link AgentState} 构造器，再由
     * {@link com.nexusai.application.agent.permission.PermissionContextBuilder}
     * 读出构造 {@link com.nexusai.application.agent.permission.ToolUseContext}。
     *
     * @param params 调用参数（userPrompt 必传 + querySource 必传）
     * @return 最终 state（含 {@link AgentState#exitReason()}）
     */
    AgentState run(RunRequest params);

    /**
     * A13: 流式契约 · 对齐 CC query.ts AsyncGenerator Terminal|Continue.
     *
     * <p>修正文件对比统计报告 §8.1 A13：Java 原 run() 同步返回终态 AgentState, 无法满足
     * "带停止原因的模型事件流"消费方 (REPL/SDK/CCR Remote). 本方法返回 Stream&lt;AgentEvent&gt;
     * 模拟 TS AsyncGenerator, 最后一条必为 {@link AgentEvent.Terminal}.
     *
     * <p>默认实现: 调用 run(RunRequest) + 单条 Terminal 事件. 子类可重写以暴露 turn/chunk/tool 事件.
     *
     * <p><b>L3 idiom</b>: TS {@code for await (const event of query(...))} →
     * Java {@code events.filter(e -> e instanceof Terminal).findFirst()}.
     */
    default java.util.stream.Stream<AgentEvent> runStream(String userPrompt,
                                                          String sessionId,
                                                          UUID agentId,
                                                          ProviderConfig config,
                                                          String modelName,
                                                          String systemPrompt) {
        // R29: 内部委托 run(RunRequest.session(...)) —— 唯一契约
        AgentState finalState = run(RunRequest.session(userPrompt, sessionId, agentId, config, modelName, systemPrompt, null));
        return java.util.stream.Stream.of(
            (AgentEvent) new AgentEvent.Terminal(sessionId, finalState.exitReason(), finalState.lastError()));
    }
}
