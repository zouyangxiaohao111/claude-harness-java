package com.nexusai.domain.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.mcp.HttpMcpTransport;
import com.nexusai.application.agent.mcp.McpAuthError;
import com.nexusai.application.agent.mcp.McpAuthHeaderProvider;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.connector.ConnectorToolInfo;
import com.nexusai.model.connector.dto.TestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 连接器「测试连接」· 真 MCP initialize 握手（不是 GET 探活）。
 *
 * <p>复用生产传输层 {@link HttpMcpTransport} + {@link McpAuthHeaderProvider}：
 * 后者按 server 名解析连接器加密凭据并注入 Bearer —— 所以这个测试同时也验证了
 * 「授权 → 运行时 Bearer」整条链路。流程：
 * <ol>
 *   <li>{@code initialize}（Streamable HTTP POST，带 Authorization）；</li>
 *   <li>{@code notifications/initialized}（失败不阻断，部分 server 不校验）；</li>
 *   <li>{@code tools/list}（证明工具管道可用）。</li>
 * </ol>
 *
 * <p>{@code probeLevel}：{@code mcp_initialize}=initialize 成功；{@code connect_only}=能发起请求
 * 但 MCP 握手失败（含 401 凭据无效/协议错误）；{@code none}=尚未连接。
 */
@Component
public class McpConnectorProbe {

    private static final Logger log = LoggerFactory.getLogger(McpConnectorProbe.class);
    private static final long PROBE_TIMEOUT_SECONDS = 20L;
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpAuthHeaderProvider authHeaderProvider;

    public McpConnectorProbe(McpAuthHeaderProvider authHeaderProvider) {
        this.authHeaderProvider = authHeaderProvider;
    }

    /** 旧签名（OAuth/Bearer）：无模板凭据值。 */
    public TestResponse probe(ConnectorDefinition def, boolean hasCredential) {
        return probe(def, Map.of(), hasCredential);
    }

    /**
     * @param def           连接器定义（mcpUrl/key/transport）
     * @param values        已解密凭据字段（URL/header 模板占位值；OAuth 传空 map）
     * @param hasCredential 本地是否已有可用凭据；否则直接返回 none
     */
    public TestResponse probe(ConnectorDefinition def, Map<String, String> values,
                              boolean hasCredential) {
        if (!hasCredential) {
            return new TestResponse(false, "尚未连接，请先完成授权", 0L, "none", List.of(),
                    null, "no_credentials");
        }
        String endpoint;
        Map<String, String> headers;
        try {
            endpoint = ConnectorEndpointResolver.endpoint(def, values);
            headers = ConnectorEndpointResolver.headers(def, values);
        } catch (Exception e) {
            return new TestResponse(false, e.getMessage(), 0L, "none", List.of(),
                    null, "missing_credentials");
        }
        long started = System.currentTimeMillis();
        HttpMcpTransport transport = new HttpMcpTransport(authHeaderProvider);
        McpTransport.TransportConfig config = new McpTransport.TransportConfig(
                endpoint, List.of(), headers.isEmpty() ? null : headers, null,
                def.key(), def.mcpTransport());
        try {
            transport.start(config);
            JsonNode init = transport.sendRequest("initialize", Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "NexusAI", "version", "1.0")))
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String negotiated = init == null ? "" : init.path("protocolVersion").asText("");
            if (negotiated.isBlank()) {
                return connectOnly("initialize 响应缺少 protocolVersion", started);
            }
            // MCP 规范要求 initialize 之后发 initialized；个别 server 不校验，失败不阻断
            try {
                transport.sendNotification("notifications/initialized", Map.of());
            } catch (Exception e) {
                log.debug("[ConnectorProbe] notifications/initialized 失败（继续 tools/list）: {}", e.toString());
            }
            JsonNode tools = transport.sendRequest("tools/list", Map.of())
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ConnectorToolInfo> toolInfos = toToolInfos(tools);
            long ms = System.currentTimeMillis() - started;
            return new TestResponse(true,
                    "MCP initialize 成功（protocol=" + negotiated + "，tools=" + toolInfos.size() + "）",
                    ms, "mcp_initialize", toolInfos);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - started;
            Throwable cause = rootCause(e);
            if (containsAuthError(cause)) {
                return new TestResponse(false,
                        "凭据无效或已过期（401），请重新授权", ms, "connect_only", List.of(),
                        null, "auth_failed");
            }
            return new TestResponse(false, "MCP 握手失败：" + safeMessage(cause),
                    ms, "connect_only", List.of(), null, "handshake_failed");
        } finally {
            try {
                transport.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private static TestResponse connectOnly(String message, long started) {
        return new TestResponse(false, message, System.currentTimeMillis() - started,
                "connect_only", List.of());
    }

    /** tools/list result → 前端可渲染的工具清单（name + description，上限 100 条防爆）。 */
    static List<ConnectorToolInfo> toToolInfos(JsonNode toolsResult) {
        if (toolsResult == null || !toolsResult.path("tools").isArray()) {
            return List.of();
        }
        List<ConnectorToolInfo> out = new ArrayList<>();
        for (JsonNode t : toolsResult.path("tools")) {
            String name = t.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            out.add(new ConnectorToolInfo(name, t.path("description").asText("")));
            if (out.size() >= 100) {
                break;
            }
        }
        return out;
    }

    private static boolean containsAuthError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof McpAuthError) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("requires re-authorization"))) {
                return true;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
