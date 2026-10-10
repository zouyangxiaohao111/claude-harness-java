package com.nexusai.infra.llm;

/**
 * 流空闲超时（对齐 CC 2.1.296 · progress 五态判决输入）。
 *
 * <p>CC 证据：判决矩阵 exe 223,026,053（{@code Bhr}）；progress 定义 exe 223,030,407（{@code Gan}）：
 * {@code anyBlockFinished ? (anyOutputShown ? "output" : "thinkingOnly")
 *        : (anyOutputShown ? "partialOutput" : (anyEvent ? "started" : "nothing"))}。
 *
 * <p>本异常由 provider 在「看门狗关流后」按 {@code stalledByWatchdog} 标志映射抛出（见
 * AnthropicSdkProvider.doStream：读循环正常退出后与 catch 内两处）；循环据此执行判决矩阵
 * （方案规格 §2.4：重试新流一次 / 保留部分 / 降级非流式 / 终局文案）。
 *
 * <p><b>为什么 {@code extends RuntimeException} 而非 IOException</b>：{@code ErrorClassifier.isConnectionError}
 * 只认 cause 链里的 {@code IOException}（ErrorClassifier.java:258-271）；若继承 IOException，
 * 本异常会被判成「连接错误 → isRetryable=true」从而误入 Path-3 通用 withRetry 重试通道——
 * 而 CC 的 stall 走独立判决矩阵（{@code G_.onStreamFailed}），不进通用重试。
 */
public class StreamIdleTimeoutError extends RuntimeException {

    /** progress 五态（命名/判定顺序均对齐 CC {@code Gan}，exe 223,030,407）。 */
    public enum Progress {
        /** 块已完成且有输出（正文/非思考块）。 */
        OUTPUT,
        /** 块已完成但仅思考（无正文输出）。 */
        THINKING_ONLY,
        /** 有输出但块未完成（吐了一半被卡死）。 */
        PARTIAL_OUTPUT,
        /** 收到过事件但尚无输出。 */
        STARTED,
        /** 一个事件都没收到。 */
        NOTHING
    }

    private final Progress progress;
    private final boolean stopReasonReceived;

    public StreamIdleTimeoutError(Progress progress, boolean stopReasonReceived, String message) {
        super(message);
        this.progress = progress;
        this.stopReasonReceived = stopReasonReceived;
    }

    /** CC {@code Gan} 的 progress（判决矩阵第一维）。 */
    public Progress progress() {
        return progress;
    }

    /** CC {@code stopReasonReceived}：thinkingOnly + stall 时，已收到 stop_reason 则直接保留部分不重试。 */
    public boolean stopReasonReceived() {
        return stopReasonReceived;
    }
}
