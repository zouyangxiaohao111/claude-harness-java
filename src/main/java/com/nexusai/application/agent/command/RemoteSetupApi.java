package com.nexusai.application.agent.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote-setup API (CCR GitHub token import) · 对齐 CC commands/remote-setup/api.ts.
 *
 * <p>L1 语义: 4 个公开 API.
 *            - RedactedGithubToken: GitHub token 包装器,String/JSON/inspect 全部显示 [REDACTED:gh-token].
 *            - importGithubToken: POST 到 /v1/code/github/import-token 验证+存储 token.
 *            - createDefaultEnvironment: 首次用户自动建 default env (best-effort).
 *            - isSignedIn/getCodeWebUrl: 工具方法.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: RedactedGithubToken (toString/toJSON → [REDACTED:gh-token]);ImportTokenResult;
 *       ImportTokenError (4 种);5 个 public function;isSignedIn/getCodeWebUrl.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — prepareApiRequest (auth) → POST /v1/code/github/import-token →
 *       200 ok → return {ok:true, result} else {ok:false, error};
 *       错误分类: not_signed_in/invalid_token/server/network.</li>
 *   <li><b>A3</b>: 状态: ANON (not signed in) / SIGNED_IN (token valid) / TOKEN_VALIDATED;
 *       createDefaultEnvironment: SKIPPED (existing) / CREATED / FAILED.</li>
 *   <li><b>A4</b>: 401 → not_signed_in;400 → invalid_token;其他非 2xx → server;
 *       axios throw → network;prepareApiRequest throw → not_signed_in;
 *       RedactedGithubToken 不暴露 raw value (除 reveal()).</li>
 *   <li><b>A5</b>: 真实场景 — user 输入 GitHub PAT → import → CCR 验证 → 存 Fernet →
 *       第一次用户自动创建 anthropic_cloud default env.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS class RedactedGithubToken with private field → Java final field + reveal();
 *                    TS axios.post → 注入式 HttpPoster;
 *                    TS `process.env` → 注入式 Supplier;
 *                    TS `validateStatus: () => true` → 注入式 (test 控制 status);
 *                    TS `Symbol.for('nodejs.util.inspect.custom')` → 覆盖 toString 即可.
 */
public final class RemoteSetupApi {

    private static final Logger log = LoggerFactory.getLogger(RemoteSetupApi.class);
    private static final String CCR_BYOC_BETA = "ccr-byoc-2025-07-29";
    private static final long POST_TIMEOUT_MS = 15_000L;

    private final Supplier<AuthInfo> authSupplier;
    private final Supplier<String> baseUrlSupplier;
    private final Function<HttpPostRequest, HttpPostResponse> httpPoster;
    private final Function<String, Map<String, String>> oauthHeadersSupplier;
    private final Supplier<java.util.List<EnvironmentInfo>> environmentsSupplier;

    public RemoteSetupApi(Supplier<AuthInfo> authSupplier,
                            Supplier<String> baseUrlSupplier,
                            Function<HttpPostRequest, HttpPostResponse> httpPoster,
                            Function<String, Map<String, String>> oauthHeadersSupplier,
                            Supplier<java.util.List<EnvironmentInfo>> environmentsSupplier) {
        this.authSupplier = Objects.requireNonNull(authSupplier);
        this.baseUrlSupplier = Objects.requireNonNull(baseUrlSupplier);
        this.httpPoster = Objects.requireNonNull(httpPoster);
        this.oauthHeadersSupplier = Objects.requireNonNull(oauthHeadersSupplier);
        this.environmentsSupplier = Objects.requireNonNull(environmentsSupplier);
    }

    /** CC AuthInfo — {accessToken, orgUUID}. */
    public record AuthInfo(String accessToken, String orgUUID) {}

    /** RedactedGithubToken — wrap raw token, hide in toString/toJSON. */
    public static final class RedactedGithubToken {
        private final String value;
        public RedactedGithubToken(String raw) { this.value = raw; }
        public String reveal() { return value; }
        @Override public String toString() { return "[REDACTED:gh-token]"; }
        public String toJSON() { return "[REDACTED:gh-token]"; }
    }

    public record ImportTokenResult(String githubUsername) {}

    public sealed interface ImportTokenError permits
        ImportTokenError.NotSignedIn, ImportTokenError.InvalidToken,
        ImportTokenError.Server, ImportTokenError.Network {
        record NotSignedIn() implements ImportTokenError {}
        record InvalidToken() implements ImportTokenError {}
        record Server(int status) implements ImportTokenError {}
        record Network() implements ImportTokenError {}
    }

    public record HttpPostRequest(String url, Map<String, String> headers, Object body, long timeoutMs) {}
    public record HttpPostResponse(int status, String body) {}
    public record EnvironmentInfo(String name, String kind) {}

    /** CC isSignedIn. */
    public boolean isSignedIn() {
        try { authSupplier.get(); return true; }
        catch (Exception e) { return false; }
    }

    /** CC getCodeWebUrl. */
    public String getCodeWebUrl(String claudeAiOrigin) {
        return claudeAiOrigin + "/code";
    }

    /** CC importGithubToken — POST token 到 CCR 验证. */
    public ImportResult importGithubToken(RedactedGithubToken token) {
        AuthInfo auth;
        try { auth = authSupplier.get(); }
        catch (Exception e) {
            return ImportResult.error(new ImportTokenError.NotSignedIn());
        }

        String url = baseUrlSupplier.get() + "/v1/code/github/import-token";
        Map<String, String> headers = new java.util.LinkedHashMap<>(oauthHeadersSupplier.apply(auth.accessToken()));
        headers.put("anthropic-beta", CCR_BYOC_BETA);
        headers.put("x-organization-uuid", auth.orgUUID);

        try {
            Map<String, String> body = new java.util.LinkedHashMap<>();
            body.put("token", token.reveal());
            HttpPostResponse resp = httpPoster.apply(
                new HttpPostRequest(url, headers, body, POST_TIMEOUT_MS));
            if (resp.status() == 200) {
                String username = extractUsername(resp.body());
                return ImportResult.ok(new ImportTokenResult(username));
            }
            if (resp.status() == 400) {
                return ImportResult.error(new ImportTokenError.InvalidToken());
            }
            if (resp.status() == 401) {
                return ImportResult.error(new ImportTokenError.NotSignedIn());
            }
            log.debug("import-token returned {}", resp.status());
            return ImportResult.error(new ImportTokenError.Server(resp.status()));
        } catch (Exception e) {
            log.debug("import-token network error: {}", e.getMessage());
            return ImportResult.error(new ImportTokenError.Network());
        }
    }

    /** CC createDefaultEnvironment — best-effort default env. */
    public boolean createDefaultEnvironment() {
        AuthInfo auth;
        try { auth = authSupplier.get(); }
        catch (Exception e) { return false; }

        if (!environmentsSupplier.get().isEmpty()) {
            return true;
        }

        String url = baseUrlSupplier.get() + "/v1/environment_providers/cloud/create";
        Map<String, String> headers = new java.util.LinkedHashMap<>(oauthHeadersSupplier.apply(auth.accessToken()));
        headers.put("x-organization-uuid", auth.orgUUID);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Default");
        body.put("kind", "anthropic_cloud");
        body.put("description", "Default - trusted network access");
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("environment_type", "anthropic");
        config.put("cwd", "/home/user");
        config.put("init_script", null);
        config.put("environment", java.util.Map.of());
        config.put("languages", java.util.List.of(
            new java.util.LinkedHashMap<>(java.util.Map.of("name", "python", "version", "3.11")),
            new java.util.LinkedHashMap<>(java.util.Map.of("name", "node", "version", "20"))));
        config.put("network_config", new java.util.LinkedHashMap<>(java.util.Map.of(
            "allowed_hosts", java.util.List.of(), "allow_default_hosts", true)));
        body.put("config", config);

        try {
            HttpPostResponse resp = httpPoster.apply(
                new HttpPostRequest(url, headers, body, POST_TIMEOUT_MS));
            return resp.status() >= 200 && resp.status() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /** CC extractUsername — naive JSON parse. */
    private static String extractUsername(String body) {
        if (body == null) return null;
        int idx = body.indexOf("\"github_username\"");
        if (idx < 0) return null;
        int colon = body.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    /** Result wrapper. */
    public record ImportResult(boolean ok, ImportTokenResult result, ImportTokenError error) {
        public static ImportResult ok(ImportTokenResult r) { return new ImportResult(true, r, null); }
        public static ImportResult error(ImportTokenError e) { return new ImportResult(false, null, e); }
    }
}
