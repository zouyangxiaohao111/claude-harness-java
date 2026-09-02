package com.nexusai.application.agent.workflow.agent;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.AgentProgressUpdate;
import com.nexusai.application.agent.workflow.HostHandle;
import jakarta.annotation.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * adapter.run 的运行上下文 · CC original: {@code AgentAdapterContext}
 * (Open-ClaudeCode/packages/workflow-engine/src/agentAdapter.ts:21-51)。
 *
 * <pre>{@code
 * export type AgentAdapterContext = {
 *   host: HostHandle            // 不透明 host 句柄（核心 adapter 用；独立后端忽略）
 *   signal: AbortSignal         // 取消信号（与 workflow 同一 signal）
 *   runId: string               // 当前 workflow runId（日志/追踪）
 *   agentId: number             // 引擎层 agent 序列号（hooks.agentIdSeq 递增，=面板 RunProgress.agents[].id）
 *   onProgress?: (update) => void       // 进行中进度上报（backend 循环累计 token/tool 时调用）
 *   registerAgentAbort?: (agentId, ac) => void   // backend 创建 AbortController 后注入，供 service.kill(runId, agentId) 精确 abort
 *   unregisterAgentAbort?: (agentId) => void     // agent 完成/失败时注销（幂等）
 * }
 * }</pre>
 *
 * <p><b>agentId 语义（勿混）</b>：这是<b>引擎层数字序号</b>（hooks.agentIdSeq，与面板
 * {@code RunProgress.agents[].id} 同源），不是核心层 {@code AgentId}（字符串，用于 sub-agent
 * 追踪、由后端内部创建）。本字段是 registerAgentAbort/unregisterAgentAbort 的 key，
 * 使 {@code service.kill(runId, agentId)} 能精确路由到后端创建的 AbortController。
 *
 * @param host                不透明 host 句柄（透传）· CC original: host (agentAdapter.ts:23)
 * @param signal              取消信号（同一 workflow signal）· CC original: signal (agentAdapter.ts:25)
 * @param runId               当前 workflow runId · CC original: runId (agentAdapter.ts:27)
 * @param agentId             引擎层 agent 数字序号（≠核心层 AgentId 字符串）·
 *                            CC original: agentId (agentAdapter.ts:34)
 * @param onProgress          进行中进度上报 · CC original: onProgress? (agentAdapter.ts:39)，可选
 * @param registerAgentAbort  登记 agent 级 AbortController · CC original: registerAgentAbort?
 *                            (agentAdapter.ts:45)，可选
 * @param unregisterAgentAbort 注销 agent 级 AbortController · CC original: unregisterAgentAbort?
 *                            (agentAdapter.ts:50)，可选
 */
public record AgentAdapterContext(
        HostHandle host,
        AbortController signal,
        String runId,
        int agentId,
        @Nullable Consumer<AgentProgressUpdate> onProgress,
        @Nullable BiConsumer<Integer, AbortController> registerAgentAbort,
        @Nullable Consumer<Integer> unregisterAgentAbort
) {
}
