package com.nexusai.application.agent.subagent;

/**
 * Summary 委托接口 · 对齐 CC services/AgentSummary/agentSummary.ts 的拆分点.
 *
 * <p>把 LLM call / transcript 读取 / tool-call filter 三类副作用从 {@link AgentSummaryService}
 * 抽出, 方便测试注入 stub. 实现由调用方提供 (默认实现接 LlmProvider + MessageRepository).
 */
public interface SummarySummarizer {

    /**
     * 读取指定 agent 当前 transcript messages.
     * 返回空列表表示 agent 不存在或尚无消息.
     */
    java.util.List<AgentMessage> readTranscript(String agentId);

    /**
     * 过滤掉未完成的 tool calls (CC filterIncompleteToolCalls).
     * 避免 fork LLM 看到残缺的 tool_use 序列.
     */
    java.util.List<AgentMessage> filterIncompleteToolCalls(java.util.List<AgentMessage> messages);

    /**
     * 调 LLM 生成 1-2 句进度摘要 (CC runForkedAgent).
     *
     * @param agentId fork 目标 agent id
     * @param prompt 包含历史摘要 + 模板的 prompt
     * @return 摘要文本 (trim 后非空); 失败或 LLM 返回空 → null
     */
    String summarize(String agentId, String prompt);

    /**
     * 触发 summary 所需的最少消息数 (CC agentSummary.ts:69 < 3 跳过).
     * 默认 3, 实现可按需调整.
     */
    default int minMessages() { return 3; }
}