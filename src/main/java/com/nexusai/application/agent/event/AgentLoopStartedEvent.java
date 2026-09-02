package com.nexusai.application.agent.event;

import com.nexusai.application.agent.AgentState;

/**
 * AgentLoop 启动事件。{@link com.nexusai.application.agent.LlmAgentLoop#run} 开始时
 * 发布一次（包含输入校验 + state 初始化 + 首条 user message 追加之后）。
 *
 * <p>典型用途：metrics 计数器、audit log、trace span 起点。
 *
 * <p><b>状态引用语义</b>：{@link #state()} 是 mutable AgentState 的引用，listener
 * 应作为<b>只读快照</b>对待。如需异步处理（@Async），listener 内部应自行 snapshot。
 * 不要 mutate state，否则影响 loop 后续行为。
 *
 * @see AgentLoopExitedEvent
 * @see AgentTurnStartedEvent
 */
public record AgentLoopStartedEvent(AgentState state) {

    public AgentLoopStartedEvent {
        if (state == null) throw new IllegalArgumentException("state is null");
    }

    public String modelName() {
        // 从 messages[0]（user 消息）取不到 modelName —— 这里只暴露 state 引用
        // 真实场景中 listener 可从外部 closure 拿到 modelName（见 LlmAgentLoop.run）
        return null;
    }
}
