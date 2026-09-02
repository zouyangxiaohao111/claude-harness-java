package com.nexusai.application.agent.loop;

/**
 * Hook Agent LoopDeps 实现 · 对齐 CC {@code execAgentHook.ts:167} 复用主 {@code query()}
 * （{@code querySource:'hook_agent'}）。
 *
 * <p><b>[H7-arch Phase 5 P3] 接口层窄化</b>: 原 record 持 {@code LlmAgentLoop}（fresh prototype
 * carrier），现改持 {@link AgentLoopContext}（基础设施容器）——接口不再依赖 LlmAgentLoop 类型。
 *
 * <p><b>工具隔离</b>: CC agent hook 靠 {@code agentToolUseContext}（隔离 tools + SyntheticOutputTool）。
 * [H7-arch Phase 5-2 P3-③⑤] Java 迁移对齐：base TUC = {@code ToolUseContext.of(hookAgentId, sessionId)}
 * + {@code withAvailableTools(effectiveTools)} + {@code withNonInteractiveSession(true)}，context 由
 * {@code AgentLoopContextFactory.shared()} 构造。工具来源 = per-turn TUC 的 {@code availableTools()}。
 *
 * <p>{@code isMainLoop()=false}：对齐 CC hook agent 非 main loop 语义（不触发 session 级
 * 主循环 hook，STOP hook 由 {@code StructuredOutputEnforcementHook} 按 hookAgentId 自过滤接管）。
 *
 * @see com.nexusai.application.agent.LlmAgentLoop.MainLoopDeps
 * @see SubagentLoopDeps
 * @see com.nexusai.application.agent.LlmAgentLoop#queryLoop
 */
public record HookLoopDeps(AgentLoopContext context) implements LoopDeps {
    @Override
    public boolean isMainLoop() { return false; }
}
