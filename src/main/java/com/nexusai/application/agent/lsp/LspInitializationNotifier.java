package com.nexusai.application.agent.lsp;

/**
 * LspInitializationNotifier · 对齐 CC hooks/notifs/useLspInitializationNotification.tsx:11-75。
 *
 * <p>L1 语义: 轮询 LSP 状态, 在管理器初始化失败或某个 LSP server 进入 error 态时弹通知, 并去重
 * (同一 source:errorMessage 只通知一次)。displayName 对 "plugin:xxx" 前缀取 xxx。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: POLL_INTERVAL_MS=5000; dedupKey / stateErrorKey / notificationKey / displayName /
 *       buildMessage; TIMEOUT_MS=8000; PRIORITY="medium"</li>
 *   <li><b>A2 Golden Trace</b>: source=pyright, msg=boom → notificationKey=lsp-error-pyright, "LSP for pyright failed"</li>
 *   <li><b>A3 纯函数</b>: key/message 仅依赖 (source, errorMessage)</li>
 *   <li><b>A4 边界</b>: "plugin:foo" → displayName=foo; 无 ':' 的 plugin 前缀回落原 source; dedupKey 拼接稳定</li>
 *   <li><b>A5 业务场景</b>: lsp-manager 初始化失败 → dedupKey="lsp-manager:..." 首次通知, 二次去重</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS React useInterval + useRef(Set 去重) + JSX → Java 纯静态 key/message 构造,
 * 去重 Set 与轮询留给 Java 端调用方 (NotificationQueue)。
 */
public final class LspInitializationNotifier {

    /** CC useLspInitializationNotification.tsx:10 LSP_POLL_INTERVAL_MS */
    public static final long POLL_INTERVAL_MS = 5000L;
    /** CC :68 timeoutMs */
    public static final long TIMEOUT_MS = 8000L;
    /** CC :67 priority */
    public static final String PRIORITY = "medium";

    private LspInitializationNotifier() {}

    /** CC :38 errorKey = `${source}:${errorMessage}` (去重键) */
    public static String dedupKey(String source, String errorMessage) {
        return source + ":" + errorMessage;
    }

    /** CC :47 stateErrorKey = `generic-error:${source}:${errorMessage}` (appState.plugins.errors 去重) */
    public static String stateErrorKey(String source, String errorMessage) {
        return "generic-error:" + source + ":" + errorMessage;
    }

    /** CC :65 notification key = `lsp-error-${source}` */
    public static String notificationKey(String source) {
        return "lsp-error-" + source;
    }

    /** CC :64 displayName — "plugin:xxx" → "xxx", 否则原样 */
    public static String displayName(String source) {
        if (source.startsWith("plugin:")) {
            String[] parts = source.split(":", -1);
            return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : source;
        }
        return source;
    }

    /** CC :66 message text = "LSP for {displayName} failed" */
    public static String buildMessage(String source) {
        return "LSP for " + displayName(source) + " failed";
    }
}
