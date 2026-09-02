package com.nexusai.application.agent.cli;

import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

/**
 * URL → Transport 路由 · 对齐 CC cli/transports/transportUtils.ts:16-45 getTransportForUrl.
 *
 * <p>L1 语义: 给定 URL → 根据 env 变量 + URL protocol 选 transport. 优先级:
 *            1. CCR_V2 (CLAUDE_CODE_USE_CCR_V2) → SSETransport (改 protocol 为 http/https + 拼 /worker/events/stream)
 *            2. WS/WS 协议 + POST_V2 → HybridTransport; 否则 WebSocketTransport
 *            3. 其他 protocol → 抛 "Unsupported protocol"
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `dispatch(url, headers, sessionId, refreshHeaders, env, sseFactory, hybridFactory, wsFactory) → Transport`</li>
 *   <li><b>A2 Golden Trace</b>: CCR_V2 + wss URL → SSETransport URL=http + path '/worker/events/stream' / WS 协议 + POST_V2 → Hybrid / WS 协议无 POST_V2 → WebSocket / http URL → 抛异常</li>
 *   <li><b>A3</b>: 纯 dispatcher; transport 实例化由调用方注入 (Factory)</li>
 *   <li><b>A4</b>: 空 env (CCR_V2 未设, POST_V2 未设) + WS URL → WebSocket (默认)</li>
 *   <li><b>A5</b>: 真实 ws://session.example.com/sessions/123 → URL 派生 SSE = https://.../worker/events/stream (CCR_V2)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Supplier&lt;String&gt; env 注入 (CC process.env); Supplier&lt;Transport&gt; factory 注入测试可控.
 */
public final class TransportUrlDispatcher {

    /** Transport 占位接口. */
    public interface Transport {}

    /** 各 transport factory 函数签名. */
    @FunctionalInterface public interface SseTransportFactory {
        Transport create(URI url, Map<String, String> headers, String sessionId,
                         Supplier<Map<String, String>> refreshHeaders);
    }
    @FunctionalInterface public interface HybridTransportFactory {
        Transport create(URI url, Map<String, String> headers, String sessionId,
                         Supplier<Map<String, String>> refreshHeaders);
    }
    @FunctionalInterface public interface WebSocketTransportFactory {
        Transport create(URI url, Map<String, String> headers, String sessionId,
                         Supplier<Map<String, String>> refreshHeaders);
    }

    private TransportUrlDispatcher() {}

    /**
     * CC getTransportForUrl.
     *
     * @param url             目标 URL
     * @param headers         请求 header
     * @param sessionId       session id
     * @param refreshHeaders  动态 header 刷新函数
     * @param env             环境变量快照 (CC process.env subset)
     * @param sseFactory      SSETransport 工厂 (CCR_V2 路径)
     * @param hybridFactory   HybridTransport 工厂 (WS + POST_V2)
     * @param wsFactory       WebSocketTransport 工厂 (WS 默认)
     * @return 选中的 transport 实例
     */
    public static Transport dispatch(URI url,
                                      Map<String, String> headers,
                                      String sessionId,
                                      Supplier<Map<String, String>> refreshHeaders,
                                      Map<String, String> env,
                                      SseTransportFactory sseFactory,
                                      HybridTransportFactory hybridFactory,
                                      WebSocketTransportFactory wsFactory) {
        if (isEnvTruthy(env.get("CLAUDE_CODE_USE_CCR_V2"))) {
            // CC: wss → https, ws → http; 拼 /worker/events/stream
            URI sseUrl = url;
            String scheme = url.getScheme();
            if ("wss".equalsIgnoreCase(scheme)) {
                sseUrl = replaceScheme(url, "https");
            } else if ("ws".equalsIgnoreCase(scheme)) {
                sseUrl = replaceScheme(url, "http");
            }
            String path = sseUrl.getPath();
            String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            sseUrl = URI.create(sseUrl.getScheme() + "://" + sseUrl.getAuthority() + trimmed + "/worker/events/stream"
                + (sseUrl.getQuery() != null ? "?" + sseUrl.getQuery() : ""));
            return sseFactory.create(sseUrl, headers, sessionId, refreshHeaders);
        }

        String scheme = url.getScheme();
        if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
            if (isEnvTruthy(env.get("CLAUDE_CODE_POST_FOR_SESSION_INGRESS_V2"))) {
                return hybridFactory.create(url, headers, sessionId, refreshHeaders);
            }
            return wsFactory.create(url, headers, sessionId, refreshHeaders);
        }
        throw new IllegalArgumentException("Unsupported protocol: " + scheme);
    }

    private static URI replaceScheme(URI url, String newScheme) {
        return URI.create(newScheme + "://" + url.getAuthority()
            + url.getPath()
            + (url.getQuery() != null ? "?" + url.getQuery() : "")
            + (url.getFragment() != null ? "#" + url.getFragment() : ""));
    }

    /** CC isEnvTruthy — 接受 "1", "true", "yes" 等 truthy 字符串. */
    private static boolean isEnvTruthy(String value) {
        if (value == null) return false;
        String s = value.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }
}