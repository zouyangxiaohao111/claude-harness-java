package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.infra.llm.OutboundProxy;
import com.nexusai.infra.llm.ProviderConfigAssembler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cloud Code Assist 的 project 解析客户端（{@code loadCodeAssist} → 必要时 {@code onboardUser} + LRO 轮询）。
 *
 * <p>协议取证：{@code google-gemini/gemini-cli@08e9a20} {@code code_assist/server.ts:73-74,545-551}
 * 与 {@code code_assist/setup.ts:153-333}：
 * <ul>
 *   <li>{@code POST {host}/v1internal:loadCodeAssist} body
 *       {@code {cloudaicompanionProject?, metadata:{ideType,platform,pluginType,duetProject?}}}；</li>
 *   <li>响应有 {@code currentTier} → project 取 {@code cloudaicompanionProject}；</li>
 *   <li>无 {@code currentTier} → 取 {@code allowedTiers} 中 {@code isDefault} 的 tier（否则 LEGACY），
 *       {@code POST {host}/v1internal:onboardUser}（FREE tier 不带 project，其余带）；
 *       未完成则按 {@code name} 每 5s 轮询 {@code GET {host}/v1internal/{name}}；</li>
 *   <li>project = {@code lro.response.cloudaicompanionProject.id}；仍无 → {@code ineligibleTiers}
 *       里取 {@code reasonMessage} 或「需要 GCP project」。</li>
 * </ul>
 *
 * <p><b>纯 HTTP + 注入边界</b>：{@link HttpClient} / {@link Clock} / {@link Sleeper} 全部可注入，
 * 单测用本地 stub 服务或 fake client，⛔ 不启 Spring、不发真实认证请求。
 *
 * <p><b>总 deadline + 取消</b>：{@link #resolveProject(String, Duration)} 对整条链（含轮询）共用
 * 一个 deadline；被 {@link Thread#interrupt()} 打断即用 {@link GoogleCodeAssistException.Code#ABORTED}
 * 退出，不把半成品 project 当成成功。
 *
 * <p><b>脱敏</b>：异常 message 只含 HTTP 状态码与服务端 {@code reasonMessage}（去控制字符 + 截断），
 * ⛔ 不含 access token / 请求头 / 请求体。
 */
@Component
public class GoogleCodeAssistClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleCodeAssistClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 整条解析链的默认总 deadline（工程值；用户未指定）。 */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(90);

    /** LRO 轮询间隔（官方 setup.ts:283 为 5000ms）。 */
    public static final long POLL_INTERVAL_MILLIS = 5000L;

    private static final String API_VERSION = "v1internal";

    private final HttpClient httpClient;
    private final Clock clock;
    private final Sleeper sleeper;
    /** API 基址（host + /v1internal）；生产恒为 {@link #PRODUCTION_API_BASE}，单测可注入本地桩。 */
    private final String apiBase;

    /** 生产 API 基址（官方 server.ts:73-74：host + v1internal）。 */
    static final String PRODUCTION_API_BASE =
        ProviderConfigAssembler.GOOGLE_CLOUD_CODE_BASE_URL + "/" + API_VERSION;

    /** 可注入的等待器（单测不真等 5s）。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public GoogleCodeAssistClient() {
        this(newHttpClient(), Clock.systemUTC(), Thread::sleep);
    }

    GoogleCodeAssistClient(HttpClient httpClient, Clock clock, Sleeper sleeper) {
        this(httpClient, clock, sleeper, PRODUCTION_API_BASE);
    }

    GoogleCodeAssistClient(HttpClient httpClient, Clock clock, Sleeper sleeper, String apiBase) {
        this.httpClient = httpClient;
        this.clock = clock;
        this.sleeper = sleeper;
        this.apiBase = apiBase;
    }

    /**
     * 与 Claude 链同口径的出站 client：Cloud Code 在境外，设置页「出站线路」配置后应走代理。
     * ⛔ 不用 {@code selectorFor}（白名单不含 googleapis），用每次实时求值的 dynamicSelector。
     */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(OutboundProxy.dynamicSelector())
            .build();
    }

    public String resolveProject(String accessToken) throws GoogleCodeAssistException {
        return resolveProject(accessToken, DEFAULT_DEADLINE);
    }

    /**
     * 解析当前账号的 Cloud Code project。
     *
     * @param accessToken OAuth access token（调用方保证非空；本类不打印）
     * @param deadline    总超时（含 onboard 轮询）；null/非正 → {@link #DEFAULT_DEADLINE}
     * @return 非空 project id
     * @throws GoogleCodeAssistException 未登录 / 无资格 / 缺 GCP project / 超时 / 取消 / 传输失败
     */
    public String resolveProject(String accessToken, Duration deadline)
            throws GoogleCodeAssistException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.NOT_AUTHENTICATED,
                "未登录 Google 订阅");
        }
        Duration effective = deadline == null || deadline.isZero() || deadline.isNegative()
            ? DEFAULT_DEADLINE : deadline;
        long deadlineAt = clock.millis() + effective.toMillis();

        JsonNode load = post("loadCodeAssist", loadBody(), accessToken, deadlineAt);
        if (load.path("currentTier").isObject()) {
            String project = projectOf(load.get("cloudaicompanionProject"));
            if (project == null) {
                throw ineligibleOrProjectRequired(load);
            }
            return project;
        }

        // 无 currentTier 且没有可用 allowedTiers：官方语义 = 无资格（ineligibleTiers）或必须先绑 GCP
        // project（ProjectIdRequired）。⛔ 不能凭空走 LEGACY onboarding 把「无资格」拖成超时。
        JsonNode allowedTiers = load.path("allowedTiers");
        if (!allowedTiers.isArray() || allowedTiers.isEmpty()) {
            throw ineligibleOrProjectRequired(load);
        }

        JsonNode tier = defaultTier(load);
        String tierId = tier.path("id").asText("");
        ObjectNode onboard = JSON.createObjectNode();
        if (!tierId.isBlank()) {
            onboard.put("tierId", tierId);
        }
        // 官方 setup.ts:257-274：FREE tier 不带 project（带则 Precondition Failed）；非 FREE 需要用户
        // GCP project，但本仓 v1 无该配置项 ⇒ 字段整体省略（等价 JSON undefined），由服务端按官方
        // 语义回 ineligible / ProjectIdRequired，如实报因，⛔ 不编造 project。
        onboard.set("metadata", metadata());

        JsonNode lro = post("onboardUser", onboard, accessToken, deadlineAt);
        while (!lro.path("done").asBoolean(false)) {
            String name = lro.path("name").asText("");
            if (name.isBlank()) {
                throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.PROTOCOL_ERROR,
                    "Google 订阅 onboarding 未返回操作名，无法继续等待");
            }
            sleepUntilNextPoll(deadlineAt);
            // name 已是完整操作资源名（形如 operations/xxx）；apiBase 已含 /v1internal，⛔ 不再拼版本。
            lro = get(name, accessToken, deadlineAt);
        }

        String project = projectOf(lro.path("response").get("cloudaicompanionProject"));
        if (project == null) {
            throw ineligibleOrProjectRequired(load);
        }
        return project;
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    private JsonNode post(String method, JsonNode body, String accessToken, long deadlineAt)
            throws GoogleCodeAssistException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + ":" + method))
            .timeout(remaining(deadlineAt))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "nexusai-google-subscription/1.0")
            .header("Authorization", "Bearer " + accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        return send(req, method);
    }

    private JsonNode get(String path, String accessToken, long deadlineAt)
            throws GoogleCodeAssistException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/" + path))
            .timeout(remaining(deadlineAt))
            .header("Accept", "application/json")
            .header("User-Agent", "nexusai-google-subscription/1.0")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        return send(req, path);
    }

    private JsonNode send(HttpRequest req, String label) throws GoogleCodeAssistException {
        try {
            HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = resp.statusCode();
            if (sc == 401) {
                throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.NOT_AUTHENTICATED,
                    "Google 订阅登录已失效，请重新登录");
            }
            if (sc == 403) {
                // 403 在 Cloud Code 语义里多为「无资格 / 需先接受条款(VALIDATION_REQUIRED)」，
                // 不是登录失效：如实透出服务端 reasonMessage，并保留官方验证链接（仅 https）。
                throw forbidden(resp.body());
            }
            if (sc < 200 || sc >= 300) {
                throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.HTTP_ERROR,
                    "Cloud Code 返回 HTTP " + sc + remoteMessageSuffix(resp.body()));
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(body);
        } catch (GoogleCodeAssistException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.ABORTED,
                "登录已取消", e);
        } catch (Exception e) {
            log.warn("[GoogleCodeAssist] 请求失败 endpoint={}: {}", label, e.toString());
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.TRANSPORT_ERROR,
                "无法连接 Cloud Code（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    /**
     * 403 → 可读原因（脱敏）。优先 {@code error.message}；{@code VALIDATION_REQUIRED} 的
     * {@code details[].metadata.validationUrl} 仅接受 https 链接，避免把任意串当可点击链接。
     */
    private static GoogleCodeAssistException forbidden(String body) {
        String message = null;
        String validationUrl = null;
        try {
            JsonNode root = body == null || body.isBlank() ? null : JSON.readTree(body);
            JsonNode error = root == null ? null : root.path("error");
            if (error != null && error.isObject()) {
                message = error.path("message").asText(null);
                JsonNode details = error.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        String url = d.path("metadata").path("validationUrl").asText(null);
                        if (url != null && url.startsWith("https://")) {
                            validationUrl = url;
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败不影响「无资格」这个结论
        }
        String reason = sanitize(message);
        StringBuilder sb = new StringBuilder("当前 Google 账号无 Gemini Code Assist 资格或被拒绝");
        if (reason != null && !reason.isBlank()) {
            sb.append("：").append(reason);
        }
        if (validationUrl != null) {
            sb.append("。请先完成验证：").append(sanitize(validationUrl));
        }
        return new GoogleCodeAssistException(GoogleCodeAssistException.Code.INELIGIBLE, sb.toString());
    }

    /** 非 2xx 时附上服务端 error.message（脱敏 + 截断；无则空串）。 */
    private static String remoteMessageSuffix(String body) {
        try {
            if (body == null || body.isBlank()) return "";
            String msg = JSON.readTree(body).path("error").path("message").asText("");
            String safe = sanitize(msg);
            return safe == null || safe.isBlank() ? "" : "（" + safe + "）";
        } catch (Exception e) {
            return "";
        }
    }

    // ───────────────────────────── 解析 ─────────────────────────────

    private static ObjectNode loadBody() {
        ObjectNode body = JSON.createObjectNode();
        body.set("metadata", metadata());
        return body;
    }

    private static ObjectNode metadata() {
        ObjectNode m = JSON.createObjectNode();
        m.put("ideType", "IDE_UNSPECIFIED");
        m.put("platform", "PLATFORM_UNSPECIFIED");
        m.put("pluginType", "GEMINI");
        return m;
    }

    private static JsonNode defaultTier(JsonNode load) {
        JsonNode allowed = load.path("allowedTiers");
        if (allowed.isArray()) {
            for (JsonNode t : allowed) {
                if (t.path("isDefault").asBoolean(false)) {
                    return t;
                }
            }
        }
        ObjectNode legacy = JSON.createObjectNode();
        legacy.put("id", "LEGACY");
        legacy.put("name", "");
        legacy.put("description", "");
        legacy.put("userDefinedCloudaicompanionProject", true);
        return legacy;
    }

    private static String projectOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String s = node.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (node.isObject()) {
            String id = node.path("id").asText("");
            if (!id.isBlank()) {
                return id.trim();
            }
            String name = node.path("name").asText("");
            return name.isBlank() ? null : name.trim();
        }
        return null;
    }

    private static GoogleCodeAssistException ineligibleOrProjectRequired(JsonNode load) {
        JsonNode tiers = load.path("ineligibleTiers");
        if (tiers.isArray() && !tiers.isEmpty()) {
            JsonNode first = tiers.get(0);
            String reason = sanitize(first.path("reasonMessage").asText(""));
            if (reason == null || reason.isBlank()) {
                reason = sanitize(first.path("tierName").asText(""));
            }
            String msg = reason == null || reason.isBlank()
                ? "当前 Google 账号无 Gemini Code Assist 订阅资格"
                : "当前 Google 账号无订阅资格：" + reason;
            return new GoogleCodeAssistException(
                GoogleCodeAssistException.Code.INELIGIBLE, msg);
        }
        return new GoogleCodeAssistException(
            GoogleCodeAssistException.Code.PROJECT_REQUIRED,
            "该 Google 账号需要先绑定 Cloud 项目（GCP project）后才能使用 Gemini Code Assist");
    }

    private void sleepUntilNextPoll(long deadlineAt) throws GoogleCodeAssistException {
        long remaining = deadlineAt - clock.millis();
        if (remaining <= 0) {
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.TIMEOUT,
                "Google 订阅项目初始化超时，请稍后重试");
        }
        long wait = Math.min(POLL_INTERVAL_MILLIS, remaining);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.ABORTED,
                "登录已取消", e);
        }
    }

    private Duration remaining(long deadlineAt) throws GoogleCodeAssistException {
        long left = deadlineAt - clock.millis();
        if (left <= 0) {
            throw new GoogleCodeAssistException(GoogleCodeAssistException.Code.TIMEOUT,
                "Google 订阅项目初始化超时，请稍后重试");
        }
        return Duration.ofMillis(left);
    }

    /** 错误文案脱敏：去控制字符 / HTML 尖括号，压缩空白，截断 200。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cntrl}]", " ")
            .replace('<', ' ').replace('>', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** project 解析失败的可读异常（受检：调用方必须转成用户可见错误）。 */
    public static final class GoogleCodeAssistException extends Exception {

        public enum Code {
            NOT_AUTHENTICATED,
            INELIGIBLE,
            PROJECT_REQUIRED,
            TIMEOUT,
            ABORTED,
            PROTOCOL_ERROR,
            HTTP_ERROR,
            TRANSPORT_ERROR
        }

        private final Code code;

        public GoogleCodeAssistException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public GoogleCodeAssistException(Code code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}
