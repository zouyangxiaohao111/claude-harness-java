package com.nexusai.application.agent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server headers helper · 对齐 CC services/mcp/headersHelper.ts.
 *
 * <p>L1 语义: 动态生成 MCP server 请求头 (git credential-helper 风格).
 *            - headersHelper script 配置存在 → exec → JSON parse → 合并到 config.headers.
 *            - project/local scope 配置 + 非 non-interactive + 无 trust dialog → 拒绝执行.
 *            - 任何错误 (exec fail/parse fail/non-string value) → 返回 null + log (不阻断连接).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: getMcpHeadersFromHelper(server, config) → Map|null;
 *       getMcpServerHeaders(server, config) → Map (合并 static + dynamic);
 *       3 字段校验 (object / 非 array / 所有 value 是 string).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — headersHelper 缺 → null → 无 dynamic;
 *       headersHelper 存在 → 检查 scope+trust → exec → parse → 校验 → return headers;
 *       merge: static + dynamic (dynamic 覆盖).</li>
 *   <li><b>A3</b>: 状态: NOT_CONFIGURED (无 headersHelper) / BLOCKED (scope+无 trust) / OK / ERROR (parse fail).</li>
 *   <li><b>A4</b>: scope='project'|'local' + 非 non-interactive + 无 trust → null + logEvent;
 *       parse 非 object → throw → null;
 *       value 非 string → throw → null;
 *       exec 抛错 → catch → null;
 *       exit code != 0 → throw → null.</li>
 *   <li><b>A5</b>: 真实场景 — git mcp server 配置 headersHelper="/usr/local/bin/git-cred-helper.sh" →
 *       exec → {"Authorization": "Bearer x"} → 合并到 config.headers.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `execFileNoThrowWithCwd` → 注入式 ShellExecutor;
 *                    TS `getIsNonInteractiveSession` → 注入式 BooleanSupplier;
 *                    TS `checkHasTrustDialogAccepted` → 注入式 BooleanSupplier;
 *                    TS `jsonParse` → 注入式 JsonParser;
 *                    TS `globalThis.Headers` (Web API) → Java Map.
 */
public final class HeadersHelper {

    private static final Logger log = LoggerFactory.getLogger(HeadersHelper.class);
    private static final long EXEC_TIMEOUT_MS = 10_000L;

    private final BooleanSupplier nonInteractiveSupplier;
    private final BooleanSupplier trustDialogAcceptedSupplier;
    private final ShellExecutor shellExecutor;
    private final JsonParser jsonParser;
    private final EventLogger eventLogger;
    private final ErrorLogger errorLogger;

    public HeadersHelper(BooleanSupplier nonInteractiveSupplier,
                          BooleanSupplier trustDialogAcceptedSupplier,
                          ShellExecutor shellExecutor,
                          JsonParser jsonParser,
                          EventLogger eventLogger,
                          ErrorLogger errorLogger) {
        this.nonInteractiveSupplier = Objects.requireNonNull(nonInteractiveSupplier);
        this.trustDialogAcceptedSupplier = Objects.requireNonNull(trustDialogAcceptedSupplier);
        this.shellExecutor = Objects.requireNonNull(shellExecutor);
        this.jsonParser = Objects.requireNonNull(jsonParser);
        this.eventLogger = Objects.requireNonNull(eventLogger);
        this.errorLogger = Objects.requireNonNull(errorLogger);
    }

    /** Generic MCP server config (最小子集,覆盖 sse/http/websocket). */
    public record McpServerConfig(String headersHelper, String url, String scope,
                                  /** [G27②b] 静态请求头 · CC config.headers（headersHelper.ts:129，参与 getMcpServerHeaders 合并） */
                                  Map<String, String> headers) {

        /** 3 参便捷构造（无静态头；兼容既有调用方/测试）。 */
        public McpServerConfig(String headersHelper, String url, String scope) {
            this(headersHelper, url, scope, java.util.Map.of());
        }
    }

    /** Shell executor (注入). */
    @FunctionalInterface
    public interface ShellExecutor {
        ExecResult exec(String command, String[] args, long timeoutMs);
    }

    public record ExecResult(int code, String stdout, String stderr) {}

    /** JSON parser (注入). */
    @FunctionalInterface
    public interface JsonParser { Object parse(String json); }

    /** Event logger. */
    @FunctionalInterface
    public interface EventLogger { void log(String event, Map<String, Object> data); }

    @FunctionalInterface
    public interface ErrorLogger { void log(String serverName, String message); }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** CC getMcpHeadersFromHelper — 主链. */
    public Map<String, String> getMcpHeadersFromHelper(String serverName, McpServerConfig config) {
        if (config == null || config.headersHelper() == null || config.headersHelper().isEmpty()) {
            return null;
        }

        // Security check for project/local scope
        String scope = config.scope();
        if ((scope == null || "project".equals(scope) || "local".equals(scope))
            && !nonInteractiveSupplier.getAsBoolean()
            && !trustDialogAcceptedSupplier.getAsBoolean()) {
            log.error("[HeadersHelper] invoked before trust check for server '{}'", serverName);
            eventLogger.log("tengu_mcp_headersHelper_missing_trust", Map.of("server", serverName));
            return null;
        }

        try {
            ExecResult result = shellExecutor.exec(config.headersHelper(), new String[0], EXEC_TIMEOUT_MS);
            if (result.code() != 0 || result.stdout() == null || result.stdout().isEmpty()) {
                throw new RuntimeException("headersHelper for MCP server '" + serverName
                    + "' did not return a valid value");
            }
            Object parsed = jsonParser.parse(result.stdout().trim());
            if (parsed == null || !(parsed instanceof Map) || ((Map<?, ?>) parsed) instanceof java.util.List) {
                throw new RuntimeException("headersHelper for MCP server '" + serverName
                    + "' must return a JSON object with string key-value pairs");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> rawMap = (Map<String, Object>) parsed;
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawMap.entrySet()) {
                if (!(e.getValue() instanceof String)) {
                    throw new RuntimeException("headersHelper for MCP server '" + serverName
                        + "' returned non-string value for key '" + e.getKey() + "'");
                }
                headers.put(e.getKey(), (String) e.getValue());
            }
            log.debug("[HeadersHelper] retrieved {} headers for server '{}'", headers.size(), serverName);
            return headers;
        } catch (Exception e) {
            log.error("[HeadersHelper] error for server '{}': {}", serverName, e.getMessage());
            errorLogger.log(serverName, "Error getting headers from headersHelper: " + e.getMessage());
            return null;
        }
    }

    /**
     * CC getMcpServerHeaders（headersHelper.ts:125-138）— 合并 static + dynamic。
     *
     * <p>[G27②b TR-E3-Q5] 静态头核验+接线：旧实现 {@code parseStaticHeaders} 恒返回空
     * （静态头通道死代码——config.headers 被丢弃，与 CC {@code config.headers || {}} 分歧）；
     * 现直接合并 {@link McpServerConfig#headers()}。动态头覆盖静态头（headersHelper.ts:133-137
     * {@code {...staticHeaders, ...dynamicHeaders}}）。注：Java 生产尚无 getMcpServerHeaders 调用方
     * （transport 头经 env/TransportConfig 承载，登记受控差距；本方法为 CC API 面完整实现）。
     */
    public Map<String, String> getMcpServerHeaders(String serverName, McpServerConfig config) {
        Map<String, String> staticHeaders = config == null || config.headers() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(config.headers());
        Map<String, String> dynamicHeaders = getMcpHeadersFromHelper(serverName, config);
        if (dynamicHeaders != null) {
            staticHeaders.putAll(dynamicHeaders);  // dynamic 覆盖 static
        }
        return staticHeaders;
    }
}
