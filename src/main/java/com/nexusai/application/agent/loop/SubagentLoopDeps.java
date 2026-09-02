package com.nexusai.application.agent.loop;

/**
 * Subagent LoopDeps 实现 · 对齐 CC {@code runAgent} 复用 {@code query()} 时共享 deps（LoopDeps 对齐面）。
 *
 * <p><b>[H7-arch Phase 5 P3] 接口层窄化</b>: 原 record 持 {@code LlmAgentLoop}（fresh prototype
 * carrier），现改持 {@link AgentLoopContext}（基础设施容器）——接口不再依赖 LlmAgentLoop 类型。
 *
 * <p><b>工具隔离</b>: CC subagent 靠 {@code agentToolUseContext}（隔离 tools）而非换全局 registry。
 * [H7-arch Phase 5-2 P3-③⑤] Java 迁移对齐：base TUC = {@code subagentCtx.toolUseContext()}
 * + {@code withAvailableTools(effectiveTools)}，context 由 {@code AgentLoopContextFactory.shared()} 构造。
 * 工具来源 = per-turn TUC 的 {@code availableTools()}（经 ToolRegistry.from 适配），不触碰共享 registry。
 *
 * <p>{@code isMainLoop()=false}：对齐 CC subagent 非 main loop 语义。
 *
 * @see com.nexusai.application.agent.LlmAgentLoop.MainLoopDeps
 * @see com.nexusai.application.agent.LlmAgentLoop#queryLoop
 */
public record SubagentLoopDeps(AgentLoopContext context) implements LoopDeps {
    @Override
    public boolean isMainLoop() { return false; }
}
