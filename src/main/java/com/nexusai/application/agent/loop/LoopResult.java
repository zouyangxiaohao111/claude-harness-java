package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;

/**
 * queryLoop 纯函数返回值 · 对齐 CC query() 的 yield 聚合。
 *
 * <p>WHY: CC query() 是 AsyncGenerator 逐条 yield；Java 单返回值，故聚合为 LoopResult。
 * run()/runStream()/Subagent/Hook 均从 {@link #finalState()} 取 AgentState
 * （含 messages/budgetTracker/exitReason）提取所需信息。
 *
 * @param finalState      最终 AgentState（含 messages/budgetTracker/exitReason，持久化通道不变）
 * @param totalTurns      总轮数
 * @param totalDurationMs 总耗时 ms（[R-A3] A-3 补填：queryLoop 入口起算的开始-结束时间，
 *                        对齐 CC agentToolUtils.ts:352 `Date.now() - startTime`）
 * @param aborted         是否被 abort
 */
public record LoopResult(
    AgentState finalState,
    int totalTurns,
    long totalDurationMs,
    boolean aborted
) {
    // [H7-arch Phase 5 P5 C1] newMessages 死字段已删：SubagentExecutor/ExecAgentHook 用
    // finalState.messages() + initialMsgCount，不消费 result.newMessages()（审计 C1）。
    // [IMP-SUB-03 返工] totalToolUseCount 死字段已删（H7-arch Phase 5 审计 C2）：
    //   全仓 grep 确认零消费方（run 只用 finalState；ExecAgentHook 只用 totalTurns），
    //   构造点恒硬编码 0 —— 曾误导为"真实工具调用计数"的潜在陷阱。真实计数由
    //   SubagentExecutor.countToolUses（CC agentToolUtils.ts:262-274）在 finalizeAgentTool
    //   等价站点计算，经 SubagentResult.totalToolUseCount 透传（SubagentExecutor.java:3701）。
    // [R-A3] totalDurationMs 已补填：LlmAgentLoop.queryLoop 入口捕获 loopStartTime，
    //   loop() 返回后以 System.currentTimeMillis() - loopStartTime 填充（审计 C3 死字段
    //   陷阱已清除，对齐 CC agentToolUtils.ts:352 Date.now() - startTime）。
    // compact ctor 无兜底逻辑（纯 record）。
}
