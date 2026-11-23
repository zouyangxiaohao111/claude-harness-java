package com.nexusai.domain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.DefaultOAuthHttpClient;
import com.nexusai.application.agent.mcp.LoopbackCallbackHandler;
import com.nexusai.application.agent.mcp.McpAuth;
import com.nexusai.application.agent.mcp.McpOAuth;
import com.nexusai.application.agent.mcp.OauthPort;
import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.infra.exception.BadGatewayException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.mcp.McpServer;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 管理页的 OAuth2 授权编排（{@code /api/v1/mcp/{id}/oauth/*}）。
 *
 * <p><b>为什么不直接暴露 {@code performMCPOAuthFlow}</b>：它是同步阻塞 300s 的方法（等浏览器回调），
 * 与 start/poll HTTP 形状不匹配。这里照 {@code ConnectorAuthFlow} 的范式：后台线程跑流程、
 * 用 {@code onAuthorizationUrl} 拿到授权 URL 后立刻返回；前端按 state 轮询内存结果。
 *
 * <p>复用既有的 {@link OauthPort} + {@link LoopbackCallbackHandler}（不另写回调），
 * 与 {@code McpAuthHeaderProvider}/{@code McpToolPool} 共用同一个 env 镜像解析口
 * （{@link McpOAuth#oauthServerConfig}），保证「面板填的 OAuth 配置」在三条运行时路径一致生效。
 */
@Service
public class McpOAuthFlowService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthFlowService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long STATE_TTL_MS = 5 * 60 * 1000L;
    private static final long OUTCOME_TTL_MS = 15 * 60 * 1000L;
    private static final long URL_WAIT_SECONDS = 20L;

    private final McpServerMapper mcpServerMapper;
    private final McpAuth mcpAuth;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();

    public McpOAuthFlowService(McpServerMapper mcpServerMapper, McpOAuthTokenService tokenStore) {
        this.mcpServerMapper = mcpServerMapper;
        // 与 McpAuthHeaderProvider/McpToolPool 同构：DB tokenStore + 真实 HTTP + loopback 回调 + 端口分配
        this.mcpAuth = new McpAuth(null, new DefaultOAuthHttpClient(),
                u -> { }, new LoopbackCallbackHandler(), null, tokenStore, new OauthPort());
    }

    /** 授权结果（内存短 TTL）。 */
    record Outcome(String state, String status, String message, Long expiresAt, long updatedAt) {
    }

    /** server 配置快照（从 DB 行读取：env 镜像承载 headers + oauth）。 */
    record ServerInfo(String name, String type, String url, Map<String, String> env) {
    }

    /**
     * 发起授权：后台跑 OAuth 流程，拿到授权 URL 即返回（不阻塞 300s）。
     *
     * @throws NotFoundException   server 不存在
     * @throws ValidationException stdio server 不支持 OAuth
     * @throws BadGatewayException 生成授权 URL 失败/超时
     */
    public StartResult start(String id) {
        ServerInfo si = info(id);
        McpAuth.OAuthServerConfig cfg = McpOAuth.oauthServerConfig(si.type(), si.url(), si.env());
        CompletableFuture<String> urlFuture = new CompletableFuture<>();
        AtomicReference<String> stateRef = new AtomicReference<>();

        executor.execute(() -> {
            try {
                McpAuth.AuthResult result = mcpAuth.performMCPOAuthFlow(si.name(), cfg, url -> {
                    String state = queryParam(url, "state");
                    stateRef.set(state);
                    if (state != null) {
                        outcomes.put(state, new Outcome(state, "PENDING", null, null,
                                System.currentTimeMillis()));
                    }
                    urlFuture.complete(url);
                }, true);
                String state = stateRef.get();
                if (state != null) {
                    Long expiresAt = result.success() && result.tokens() != null
                            ? result.tokens().expiresAt() : null;
                    outcomes.put(state, new Outcome(state,
                            result.success() ? "AUTHORIZED" : "ERROR",
                            result.success() ? null : result.errorMessage(),
                            expiresAt, System.currentTimeMillis()));
                }
            } catch (Exception e) {
                String state = stateRef.get();
                log.warn("[McpOAuthFlow] 授权流程异常 server={}: {}", si.name(), e.toString());
                if (state == null) {
                    urlFuture.completeExceptionally(e);
                } else {
                    outcomes.put(state, new Outcome(state, "ERROR", e.getMessage(), null,
                            System.currentTimeMillis()));
                }
            }
        });

        String authorizeUrl;
        try {
            authorizeUrl = urlFuture.get(URL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new BadGatewayException("无法生成授权 URL：" + e.getMessage());
        }
        String state = queryParam(authorizeUrl, "state");
        if (state == null || state.isBlank()) {
            throw new BadGatewayException("授权 URL 缺少 state（无法建立轮询关联）");
        }
        pruneOutcomes();
        return new StartResult(authorizeUrl, state, queryParam(authorizeUrl, "redirect_uri"),
                2, (int) (STATE_TTL_MS / 1000));
    }

    /** 轮询结果：PENDING / AUTHORIZED / ERROR。 */
    public PollResult poll(String id, String state) {
        info(id);
        if (state == null || state.isBlank()) {
            throw new ValidationException("缺少必填参数 state（请传 start 返回的 state）");
        }
        pruneOutcomes();
        Outcome outcome = outcomes.get(state);
        if (outcome == null) {
            return new PollResult("ERROR", "授权请求不存在或已过期，请重新发起", null);
        }
        return switch (outcome.status()) {
            case "AUTHORIZED" -> new PollResult("AUTHORIZED", null, outcome.expiresAt());
            case "ERROR" -> new PollResult("ERROR", outcome.message(), null);
            default -> new PollResult("PENDING", null, null);
        };
    }

    /**
     * 断开授权：复用 {@link McpAuth#revokeServerTokens}（RFC 7009 服务端吊销 + 恒清本地凭据），
     * 然后按 server 名清理内存 outcome。
     */
    public RevokedResult logout(String id) {
        ServerInfo si = info(id);
        String serverKey = McpOAuth.getServerKey(si.name(), si.type(), si.url(),
                McpOAuth.headersOnly(si.env()));
        mcpAuth.revokeServerTokens(si.name(), serverKey, si.url(), false);
        // 未完成的轮询结果留在内存里到 TTL 自然过期即可（按 state 隔离，不误伤其它 server）
        pruneOutcomes();
        log.info("[McpOAuthFlow] 已断开 OAuth server={}", si.name());
        return new RevokedResult(true);
    }

    private ServerInfo info(String id) {
        McpServerRecord record = mcpServerMapper.selectOneById(id);
        if (record == null) {
            throw new NotFoundException("MCP server " + id + " not found");
        }
        McpServer server = record.toDomain();
        String type = server.getType() == null ? "stdio" : server.getType();
        if ("stdio".equals(type)) {
            throw new ValidationException("stdio server 不支持 OAuth 授权");
        }
        Map<String, String> env = envMap(server.getEnv());
        return new ServerInfo(server.getName(), type, server.getCommand(), env);
    }

    private void pruneOutcomes() {
        long cutoff = System.currentTimeMillis() - OUTCOME_TTL_MS;
        outcomes.values().removeIf(o -> o.updatedAt() < cutoff);
    }

    private static Map<String, String> envMap(String envJson) {
        if (envJson == null || envJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = JSON.readTree(envJson);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isValueNode()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 从 URL query 取参数（URLDecoder，RFC 6749 query 语义）。 */
    static String queryParam(String url, String key) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return null;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(k)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ── 返回载体 ──

    /** start 响应。 */
    public record StartResult(String authorizeUrl, String state, String redirectUri,
                              int interval, int expiresIn) {
    }

    /** poll 响应。 */
    public record PollResult(String state, String message, Long expiresAt) {
    }

    /** logout 响应。 */
    public record RevokedResult(boolean revoked) {
    }
}
