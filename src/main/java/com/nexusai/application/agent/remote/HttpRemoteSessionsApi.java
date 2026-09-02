package com.nexusai.application.agent.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * CCR Sessions API REST 传输层 · 对齐 CC utils/teleport/api.ts + utils/teleport.tsx 真源语义。
 *
 * <p>实现 {@link RemoteSessionsApi}，直连 claude.ai CCR Sessions API。三原语：
 * <ul>
 *   <li>{@link #fetchSession} — api.ts:289-327（GET /v1/sessions/{id}，404/401 分别抛
 *       {@link RemoteSessionsApi.SessionNotFoundException}/{@link RemoteSessionsApi.SessionExpiredException}）</li>
 *   <li>{@link #pollEvents} — teleport.tsx:633-715（GET events，after_id 增量翻页 ≤50 页，
 *       过滤 env_manager_log/control_response，sessionStatus 经 fetchSession 获取）</li>
 *   <li>{@link #archiveSession} — teleport.tsx:1200（POST archive，200/409 成功，best-effort）</li>
 * </ul>
 *
 * <p><b>auth（CC prepareApiRequest api.ts:181-198 等价）</b>: 注入式
 * {@code Supplier<AuthContext>}（accessToken + organizationUuid，可 null）。缺失时：
 * fetchSession/pollEvents 抛错（对齐 prepareApiRequest :186-190/:193-195），
 * archiveSession 静默返回（对齐 teleport.tsx:1202-1204）。
 *
 * <p><b>HTTP 注入</b>: {@code getExecutor}/{@code postExecutor} 可注入（测试用 stub），
 * 缺省走 {@link java.net.http.HttpClient}。
 */
public final class HttpRemoteSessionsApi implements RemoteSessionsApi {

    private static final Logger log = LoggerFactory.getLogger(HttpRemoteSessionsApi.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC api.ts:19 — anthropic-beta 头值 */
    public static final String CCR_BYOC_BETA = "ccr-byoc-2025-07-29";

    /** CC api.ts:276-282 getOAuthHeaders — anthropic-version */
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /** CC teleport.tsx:658 MAX_EVENT_PAGES — 游标安全阀 */
    public static final int MAX_EVENT_PAGES = 50;

    /** CC api.ts:302 fetchSession timeout 15000 */
    public static final int FETCH_SESSION_TIMEOUT_MS = 15_000;

    /** CC teleport.tsx:667 pollEvents timeout 30000 */
    public static final int POLL_EVENTS_TIMEOUT_MS = 30_000;

    /** CC teleport.tsx:1214 archive timeout 10000 */
    public static final int ARCHIVE_TIMEOUT_MS = 10_000;

    /** CC api.ts:399 sendEvent timeout 30000 */
    public static final int SEND_EVENT_TIMEOUT_MS = 30_000;

    /** CC api.ts:442 updateSessionTitle（axios 未设 timeout）— Java 默认 30000 */
    public static final int UPDATE_TITLE_TIMEOUT_MS = 30_000;

    /** auth 上下文 · 等价 CC prepareApiRequest 返回 {accessToken, orgUUID}（api.ts:181-198） */
    public record AuthContext(String accessToken, String organizationUuid) {
    }

    /** HTTP GET 结果（注入式执行器产物） */
    public record HttpResponse(int status, String body) {
    }

    /** HTTP GET 执行器（注入式；缺省 HttpClient） */
    @FunctionalInterface
    public interface HttpGetExecutor {
        HttpResponse get(String url, Map<String, String> headers, Map<String, String> params, int timeoutMs)
            throws Exception;
    }

    /** HTTP POST 执行器（注入式；缺省 HttpClient） */
    @FunctionalInterface
    public interface HttpPostExecutor {
        HttpResponse post(String url, Map<String, String> headers, String body, int timeoutMs)
            throws Exception;
    }

    /** HTTP PATCH 执行器（注入式；缺省 HttpClient）— CC api.ts:442 updateSessionTitle 用。 */
    @FunctionalInterface
    public interface HttpPatchExecutor {
        HttpResponse patch(String url, Map<String, String> headers, String body, int timeoutMs)
            throws Exception;
    }

    private final Supplier<String> baseUrlSupplier;
    private final Supplier<AuthContext> authSupplier;
    private final HttpGetExecutor getExecutor;
    private final HttpPostExecutor postExecutor;
    private final HttpPatchExecutor patchExecutor;

    /** 缺省构造 — HttpClient 直连（生产），auth/baseUrl 由外部注入。 */
    public HttpRemoteSessionsApi(Supplier<String> baseUrlSupplier, Supplier<AuthContext> authSupplier) {
        this(baseUrlSupplier, authSupplier, defaultGet(), defaultPost(), defaultPatch());
    }

    /** GET/POST 注入构造（测试用）。 */
    public HttpRemoteSessionsApi(Supplier<String> baseUrlSupplier, Supplier<AuthContext> authSupplier,
                                 HttpGetExecutor getExecutor, HttpPostExecutor postExecutor) {
        this(baseUrlSupplier, authSupplier, getExecutor, postExecutor, defaultPatch());
    }

    /** 全注入构造（测试用）。 */
    public HttpRemoteSessionsApi(Supplier<String> baseUrlSupplier, Supplier<AuthContext> authSupplier,
                                 HttpGetExecutor getExecutor, HttpPostExecutor postExecutor,
                                 HttpPatchExecutor patchExecutor) {
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.authSupplier = Objects.requireNonNull(authSupplier);
        this.getExecutor = getExecutor == null ? defaultGet() : getExecutor;
        this.postExecutor = postExecutor == null ? defaultPost() : postExecutor;
        this.patchExecutor = patchExecutor == null ? defaultPatch() : patchExecutor;
    }

    // ────────────────────────────────────────────────────────────────────
    // RemoteSessionsApi 实现
    // ────────────────────────────────────────────────────────────────────

    @Override
    public SessionResource fetchSession(String sessionId) {
        AuthContext auth = requireAuth("fetchSession");
        Map<String, String> headers = oauthHeaders(auth);
        String url = baseUrlSupplier.get() + "/v1/sessions/" + sessionId;
        HttpResponse resp;
        try {
            resp = getExecutor.get(url, headers, Map.of(), FETCH_SESSION_TIMEOUT_MS);
        } catch (Exception e) {
            throw new RemoteSessionsApi.RemoteApiException(
                "Failed to fetch session: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        if (resp == null) {
            throw new RemoteSessionsApi.RemoteApiException("Failed to fetch session: no response");
        }
        if (resp.status() != 200) {
            // api.ts:307-324 — validateStatus<500；404/401 特定消息
            if (resp.status() == 404) {
                throw new RemoteSessionsApi.SessionNotFoundException(sessionId);
            }
            if (resp.status() == 401) {
                throw new RemoteSessionsApi.SessionExpiredException();
            }
            throw new RemoteSessionsApi.RemoteApiException(
                apiMessage(resp.body(), "Failed to fetch session: HTTP " + resp.status()));
        }
        try {
            JsonNode node = JSON.readTree(resp.body());
            String status = node.path("session_status").isMissingNode() ? null : node.path("session_status").asText();
            Map<String, Object> raw = JSON.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
            return new SessionResource(sessionId, status, raw);
        } catch (IOException e) {
            throw new RemoteSessionsApi.RemoteApiException("Failed to parse session response: " + e.getMessage());
        }
    }

    @Override
    public PollResult pollEvents(String sessionId, String afterId) {
        AuthContext auth = requireAuth("pollEvents");
        Map<String, String> headers = ccrHeaders(auth);
        String eventsUrl = baseUrlSupplier.get() + "/v1/sessions/" + sessionId + "/events";

        List<Map<String, Object>> sdkMessages = new ArrayList<>();
        String cursor = afterId;
        try {
            for (int page = 0; page < MAX_EVENT_PAGES; page++) {
                Map<String, String> params = new LinkedHashMap<>();
                if (cursor != null && !cursor.isEmpty()) {
                    params.put("after_id", cursor);
                }
                HttpResponse resp = getExecutor.get(eventsUrl, headers, params, POLL_EVENTS_TIMEOUT_MS);
                if (resp == null || resp.status() != 200) {
                    // teleport.tsx:669-671 — 非 200 抛错
                    throw new RemoteSessionsApi.RemoteApiException(
                        "Failed to fetch session events: HTTP " + (resp == null ? "no response" : resp.status()));
                }
                JsonNode data = JSON.readTree(resp.body());
                JsonNode eventsArr = data.get("data");
                if (eventsArr == null || !eventsArr.isArray()) {
                    // teleport.tsx:673-675 — 无效 events 响应抛错
                    throw new RemoteSessionsApi.RemoteApiException("Invalid events response");
                }
                for (JsonNode event : eventsArr) {
                    if (event == null || !event.isObject()) {
                        continue;
                    }
                    // teleport.tsx:677-685 — 过滤 env_manager_log / control_response，要求 session_id 字段
                    String type = event.path("type").asText("");
                    if ("env_manager_log".equals(type) || "control_response".equals(type)) {
                        continue;
                    }
                    if (!event.has("session_id")) {
                        continue;
                    }
                    sdkMessages.add(JSON.convertValue(event,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        }));
                }
                JsonNode lastId = data.get("last_id");
                if (lastId == null || lastId.isNull()) {
                    break; // teleport.tsx:686
                }
                cursor = lastId.asText();
                JsonNode hasMore = data.get("has_more");
                if (hasMore == null || !hasMore.asBoolean()) {
                    break; // teleport.tsx:688
                }
            }
        } catch (RemoteSessionsApi.RemoteApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteSessionsApi.RemoteApiException(
                "Failed to fetch session events: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        // teleport.tsx:697-715 — fetch session 元数据（branch/sessionStatus）；失败仅 debug 日志不抛
        String branch = null;
        String sessionStatus = null;
        try {
            SessionResource session = fetchSession(sessionId);
            sessionStatus = session.sessionStatus();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.pollEvents: 获取 session {} 元数据失败: {}", sessionId, e.getMessage());
            }
        }
        return new PollResult(sdkMessages, cursor, sessionStatus, branch);
    }

    @Override
    public void archiveSession(String sessionId) {
        // teleport.tsx:1200-1204 — 无 accessToken 直接返回（best-effort）
        AuthContext auth;
        try {
            auth = requireAuth("archiveSession");
        } catch (RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.archiveSession: 无 auth，跳过归档 {}", sessionId);
            }
            return;
        }
        Map<String, String> headers = ccrHeaders(auth);
        String url = baseUrlSupplier.get() + "/v1/sessions/" + sessionId + "/archive";
        try {
            HttpResponse resp = postExecutor.post(url, headers, "{}", ARCHIVE_TIMEOUT_MS);
            if (resp != null && (resp.status() == 200 || resp.status() == 409)) {
                // teleport.tsx:1217-1219 — 200/409 视为成功
                if (log.isDebugEnabled()) {
                    log.debug("HttpRemoteSessionsApi.archiveSession: 已归档 {}", sessionId);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.archiveSession: 归档失败 HTTP {} session={}",
                    resp == null ? "no response" : resp.status(), sessionId);
            }
        } catch (Exception e) {
            log.warn("HttpRemoteSessionsApi.archiveSession: 归档异常 session={} 错误={}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean sendEventToRemoteSession(String sessionId, Object content, String uuid) {
        // CC api.ts:366-416 — 全部异常（含 prepareApiRequest 无 token）→ false，不抛
        try {
            AuthContext auth = requireAuth("sendEventToRemoteSession");
            Map<String, String> headers = ccrHeaders(auth);
            String url = baseUrlSupplier.get() + "/v1/sessions/" + sessionId + "/events";

            // CC :376-385 userEvent（parent_tool_use_id 固定 null）
            Map<String, Object> userEvent = new LinkedHashMap<>();
            userEvent.put("uuid", uuid != null && !uuid.isEmpty() ? uuid : java.util.UUID.randomUUID().toString());
            userEvent.put("session_id", sessionId);
            userEvent.put("type", "user");
            userEvent.put("parent_tool_use_id", null);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", content);
            userEvent.put("message", message);
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("events", List.of(userEvent));

            String body = JSON.writeValueAsString(requestBody);
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.sendEventToRemoteSession: POST {} 发送用户事件", url);
            }
            HttpResponse resp = postExecutor.post(url, headers, body, SEND_EVENT_TIMEOUT_MS);
            // CC :402-407 — 200/201 成功
            if (resp != null && (resp.status() == 200 || resp.status() == 201)) {
                return true;
            }
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.sendEventToRemoteSession: 状态 {} 视为失败", resp == null ? "no response" : resp.status());
            }
            return false;
        } catch (Exception e) {
            log.warn("HttpRemoteSessionsApi.sendEventToRemoteSession: 发送事件到会话 {} 失败: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateSessionTitle(String sessionId, String title) {
        try {
            AuthContext auth = requireAuth("updateSessionTitle");
            Map<String, String> headers = ccrHeaders(auth);
            String url = baseUrlSupplier.get() + "/v1/sessions/" + sessionId;

            String body = JSON.writeValueAsString(new LinkedHashMap<>(Map.of("title", title == null ? "" : title)));
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.updateSessionTitle: PATCH {} 标题=\"{}\"", url, title);
            }
            HttpResponse resp = patchExecutor.patch(url, headers, body, UPDATE_TITLE_TIMEOUT_MS);
            // CC :451-456 — 200 成功
            if (resp != null && resp.status() == 200) {
                return true;
            }
            if (log.isDebugEnabled()) {
                log.debug("HttpRemoteSessionsApi.updateSessionTitle: 状态 {} 视为失败", resp == null ? "no response" : resp.status());
            }
            return false;
        } catch (Exception e) {
            log.warn("HttpRemoteSessionsApi.updateSessionTitle: 更新会话 {} 标题失败: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    /** CC prepareApiRequest（api.ts:181-198）— accessToken/orgUUID 缺失抛错。 */
    private AuthContext requireAuth(String op) {
        AuthContext auth = authSupplier.get();
        if (auth == null || auth.accessToken() == null || auth.accessToken().isEmpty()) {
            throw new RemoteSessionsApi.RemoteApiException(
                "Claude Code web sessions require authentication with a Claude.ai account. "
                    + "API key authentication is not sufficient. Please run /login to authenticate, or check your "
                    + "authentication status with /status.");
        }
        if (auth.organizationUuid() == null || auth.organizationUuid().isEmpty()) {
            throw new RemoteSessionsApi.RemoteApiException("Unable to get organization UUID");
        }
        return auth;
    }

    /** CC getOAuthHeaders（api.ts:276-282）— fetchSession 用。 */
    private Map<String, String> oauthHeaders(AuthContext auth) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer " + auth.accessToken());
        headers.put("content-type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        return headers;
    }

    /** CC teleport.tsx:644-648 + api.ts:296-299 — poll/archive 用（含 beta + org 头）。 */
    private Map<String, String> ccrHeaders(AuthContext auth) {
        Map<String, String> headers = oauthHeaders(auth);
        headers.put("anthropic-beta", CCR_BYOC_BETA);
        headers.put("x-organization-uuid", auth.organizationUuid());
        return headers;
    }

    /** api.ts:309-323 — 提取 error.message 作为错误描述。 */
    private static String apiMessage(String body, String fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode msg = node.path("error").path("message");
            if (!msg.isMissingNode() && !msg.isNull()) {
                return msg.asText();
            }
        } catch (IOException ignored) {
            // fallthrough
        }
        return fallback;
    }

    /** 缺省 GET 执行器 — java.net.http.HttpClient。 */
    private static HttpGetExecutor defaultGet() {
        return (url, headers, params, timeoutMs) -> {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String fullUrl = url;
            if (params != null && !params.isEmpty()) {
                StringBuilder q = new StringBuilder(url);
                if (!url.contains("?")) {
                    q.append('?');
                } else {
                    q.append('&');
                }
                boolean first = !url.contains("?");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    if (!first) {
                        q.append('&');
                    }
                    first = false;
                    q.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                }
                fullUrl = q.toString();
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(fullUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET();
            headers.forEach(builder::header);
            java.net.http.HttpResponse<String> resp = client.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            return new HttpResponse(resp.statusCode(), resp.body());
        };
    }

    /** 缺省 POST 执行器 — java.net.http.HttpClient。 */
    private static HttpPostExecutor defaultPost() {
        return (url, headers, body, timeoutMs) -> {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, java.nio.charset.StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            java.net.http.HttpResponse<String> resp = client.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            return new HttpResponse(resp.statusCode(), resp.body());
        };
    }

    /** 缺省 PATCH 执行器 — java.net.http.HttpClient（CC api.ts:442 axios.patch 等价）。 */
    private static HttpPatchExecutor defaultPatch() {
        return (url, headers, body, timeoutMs) -> {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .method("PATCH",
                    HttpRequest.BodyPublishers.ofString(body == null ? "" : body, java.nio.charset.StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            java.net.http.HttpResponse<String> resp = client.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            return new HttpResponse(resp.statusCode(), resp.body());
        };
    }
}
