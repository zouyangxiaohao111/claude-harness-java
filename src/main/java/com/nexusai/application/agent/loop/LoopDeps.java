package com.nexusai.application.agent.loop;

import java.util.UUID;

/**
 * queryLoop 的依赖载体 · 对齐 CC {@code query(params, deps)} 的 deps 对象。
 *
 * <p><b>[H7-arch Phase 5 P3] 接口层窄化</b>: 三载体模型（用户批准）——
 * <ol>
 *   <li>{@link LoopDeps} — 窄 IO + context 工厂（本接口，可注入 fake）</li>
 *   <li>{@link AgentLoopContext} — 基础设施 bean 引用（共享，不可变 record）</li>
 *   <li>{@link com.nexusai.application.agent.tool.ToolUseContext} — per-call 隔离输入</li>
 * </ol>
 *
 * <p>P1 前本接口暴露 {@code LlmAgentLoop loop()}（返回承载所有依赖的实例），P3 改为
 * {@link #context()}（返回基础设施容器）。<b>接口不再暴露 {@code LlmAgentLoop} 类型</b>——
 * 主/Subagent/Hook 三路 deps 均只暴露 {@link AgentLoopContext}，达成接口层"删 carrier 模型"。
 *
 * <p><b>过渡说明</b>: 行为方法（applyPerMessageBudget/handleToolCallsTurn 等 19 个）当前经
 * {@link AgentLoopContext#behaviors()} 门面委托 LlmAgentLoop 实例（P1 过渡态）。Behaviors
 * static 化（方法签名加 ctx、loop 内直接调用）后彻底脱离 LlmAgentLoop 实例——后续会话推进。
 *
 * <p><b>CC 对齐</b>: CC {@code query.ts:181-199} 的 deps 是 4 窄 IO（callModel/microcompact/
 * autocompact/uuid）。Java 三载体把 IO 行为（callModel 等）+ context 工厂收敛在本接口，
 * P3 起逐步补齐 4 窄 IO 方法。
 *
 * @see AgentLoopContext
 * @see com.nexusai.application.agent.LlmAgentLoop#queryLoop
 */
public interface LoopDeps {

    /** 是否主循环（主循环 true；subagent/hook false）。决定 session 级 hook 触发等行为。 */
    default boolean isMainLoop() { return false; }

    /**
     * loop 基础设施依赖容器 · 返回共享/派生的 {@link AgentLoopContext}。
     *
     * <p>主循环经 MainLoopDeps 返回 {@code toLoopContext()}；Subagent/Hook 经各自 deps
     * 返回派生 ctx（工具隔离走 {@code withToolRegistry} 或 carrier 实例字段）。queryLoop
     * 只依赖 {@code deps.context()}，不依赖任何 LlmAgentLoop 类型。
     */
    AgentLoopContext context();

    /**
     * [H7-arch Phase 5-2 A2] 生成 query chainId · 对齐 CC {@code deps.uuid()} (deps.ts:21-31)。
     *
     * <p>loop 每轮 queryTracking 递增时首轮调用生成新 chainId；默认
     * {@code UUID.randomUUID().toString()}，测试可覆写固定值。
     */
    default String uuid() { return UUID.randomUUID().toString(); }

    /**
     * [H7-arch Phase 5-2 P3 D6] 解析本次 LLM call 使用的 model · 对齐 CC deps 的模型解析职责。
     *
     * <p>主循环经 {@code MainLoopDeps} override → {@code LlmAgentLoop.getModelForCall()}（读会话级
     * runtimeModelOverride / startupModelFlag / configStorage）；Subagent/Hook deps 默认返回
     * {@code null} → loop 回落 {@code RecoveryState.getCurrentModel()}（= params.modelName() 初始值，
     * fallback 切换语义保留）。<b>null = caller 接管 fallback（params.modelName()）</b>。
     */
    default String resolveModel() { return null; }

    /**
     * [H7-arch Phase 5-2 P3-④] LLM call 入口 · 对齐 CC {@code deps.callModel} (deps.ts:21-31,
     * queryModelWithStreaming 封装)。
     *
     * <p><b>默认实现即真实实现</b>: 委托 {@link ModelCaller#call}（从 {@link #context()} 取 factory
     * → {@code provider.stream} 逐字段透传）。主/Subagent/Hook 三 deps 共用本默认，无需各自 override——
     * loop 内 LLM 调用统一经 {@code params.deps().callModel(request)}，不再直接持有 provider /
     * 直调 stream。匿名 deps（测试）仅需实现 {@link #context()} 即获得真实 LLM 调用能力。
     *
     * <p><b>行为契约</b>: streaming fallback（onStreamingFallback）、错误分类（onError）、工具提取
     * （onAssistantMessage / onToolCallComplete）语义由 {@link ModelCaller} 透传保留。
     *
     * @param request LLM call 请求（12 字段镜像 {@code provider.stream} 签名）
     * @return {@link ModelResponse#SUBMITTED} 占位（异步结果经回调送达）
     */
    default ModelResponse callModel(ModelRequest request) {
        return ModelCaller.call(context(), request);
    }

}
