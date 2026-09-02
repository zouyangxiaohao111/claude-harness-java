package com.nexusai.application.agent.loop;

/**
 * [H7-arch Phase 5-2 P3-④] callModel 同步返回载体（空 record 占位）。
 *
 * <p><b>ER-IMP-11 javadoc 兑现</b>：本 record 不是 CC {@code queryModelWithStreaming} 的
 * {@code StreamEvent | AssistantMessage | SystemAPIErrorMessage} 联合返回载体 —— 那是异步回调通道。
 * 真实的响应（assistant message / api_error / chunk）经 {@link com.nexusai.infra.llm.LlmProvider#stream}
 * 回调（onAssistantMessage / onError / onComplete）逐段送达，本 record 仅承载"本次 call 已提交"信号。
 *
 * <p>api_retry 事件流载荷（重试期间 yield 的 SystemAPIErrorMessage / ApiRetryEvent）由重试路径
 * （LlmAgentLoop Path 3 backoff + persistentChunkedSleep）经 wsTemplate 事件流推送，与本占位无关联。
 *
 * <p>空 record（无字段）：语义 = provider.stream 已触发；实际响应走回调通道。
 */
public record ModelResponse() {
    /** 默认占位实例：callModel 已触发 provider.stream。 */
    public static final ModelResponse SUBMITTED = new ModelResponse();
}
