package com.nexusai.application.agent.acp;

import java.util.Map;

/**
 * ACP 事件出口 · 把外部 agent 的实时增量转成前端可见的流事件。
 *
 * <p>抽成接口的目的只有一个：单测里换成记录型实现，不启动 Spring / WebSocket。
 * 生产实现是 {@link AcpStreamPublisher}（{@code @Component}）。
 */
public interface AcpEventSink {

    /** 无副作用实现（无 Spring 环境 / 测试默认值）。 */
    AcpEventSink NOOP = new AcpEventSink() {
        @Override
        public void chunk(String sessionId, String assistantMessageId, String delta, String reasoning) {
        }

        @Override
        public void toolCall(String sessionId, String assistantMessageId, String toolCallId,
                             String toolName, Map<String, Object> arguments) {
        }

        @Override
        public void toolResult(String sessionId, String assistantMessageId, String toolCallId,
                               String result, boolean isError, Long durationMs) {
        }
    };

    /**
     * 当前轮的 assistant 消息 id（工具卡/正文挂哪个气泡的锚点）。
     *
     * @return 取不到返回 null ⇒ 调用方应<b>跳过实时推送</b>（⛔ 不伪造 id 把卡片挂错轮）
     */
    default String resolveAssistantMessageId(String sessionId) {
        return null;
    }

    /** 正文 / 推理增量（二选一非空）。 */
    void chunk(String sessionId, String assistantMessageId, String delta, String reasoning);

    /** 工具卡出现。 */
    void toolCall(String sessionId, String assistantMessageId, String toolCallId,
                  String toolName, Map<String, Object> arguments);

    /** 工具卡填结果。 */
    void toolResult(String sessionId, String assistantMessageId, String toolCallId,
                    String result, boolean isError, Long durationMs);
}
