package com.nexusai.common;

import org.slf4j.MDC;

/**
 * 请求上下文 · 用 SLF4J MDC 注入 {@code sessionId} / {@code requestId}，
 * logback pattern 自动打印前缀，让日志能追踪到具体会话/请求。
 *
 * <h2>用法</h2>
 * <pre>{@code
 *   RequestContext.set("sess-xxx", "msg-yyy");
 *   try {
 *       log.info("processing...");     // logback 输出: [sess-xxx] [msg-yyy] processing...
 *   } finally {
 *       RequestContext.clear();
 *   }
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>MDC 基于 {@link ThreadLocal}，必须 {@link #clear()} 否则线程复用会泄漏到下个请求。
 * ChatService / LlmAgentLoop 都在 try/finally 里调用，保证清理。
 *
 * <h2>给 AI 看</h2>
 * <p>日志格式统一为 {@code [sessionId=...] [reqId=...] logger: message}，
 * AI 可直接用正则 / 文本匹配抽取 session 状态、turn 计数、tool calls。
 */
public final class RequestContext {

    public static final String SESSION_ID = "sessionId";
    public static final String REQ_ID = "reqId";

    private RequestContext() {}

    /** 设置当前线程的 session + request id。 */
    public static void set(String sessionId, String requestId) {
        if (sessionId != null) MDC.put(SESSION_ID, sessionId);
        if (requestId != null) MDC.put(REQ_ID, requestId);
    }

    /** 清理当前线程的所有 MDC（线程复用前必调）。 */
    public static void clear() {
        MDC.remove(SESSION_ID);
        MDC.remove(REQ_ID);
    }

    /** 仅设置 sessionId（HTTP 入口还不知道 user message id 时用）。 */
    public static void setSession(String sessionId) {
        if (sessionId != null) MDC.put(SESSION_ID, sessionId);
    }

    /** 获取 sessionId（logback pattern 也通过 %{sessionId} 读）。 */
    public static String sessionId() {
        return MDC.get(SESSION_ID);
    }

    public static String requestId() {
        return MDC.get(REQ_ID);
    }
}
