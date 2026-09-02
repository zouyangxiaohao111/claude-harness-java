package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamMemorySyncTypes;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.SyncState;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.TeamMemoryContent;
import com.nexusai.application.agent.team.TeamMemorySyncTypes.TeamMemoryData;
import com.nexusai.infra.llm.StructuredOutputsSupport;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Team Memory HTTP 客户端 · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/index.ts}
 * 端点契约（GET/PUT {@code ?repo={owner/repo}} + 404/304/412/结构化 413）。
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code getTeamMemorySyncEndpoint} index.ts:163-167
 * （仅 {@code ?repo=} 参数，无 {@code ?org=} —— 旧 Java 实现用 {@code ?org=&repo=} 是偏差）；
 * {@code fetchTeamMemoryOnce} :188-306（If-None-Match 剥引号 :208、304 notModified :219-224、
 * 404 isEmpty :226-232、checksum 取 body.checksum 或 ETag 剥引号 :248-254）；
 * {@code fetchTeamMemoryHashes} :315-385（{@code ?view=hashes}，:326）；
 * {@code fetchTeamMemory} :387-410（MAX_RETRIES=3 指数退避 :394-407）；
 * {@code uploadTeamMemory} :462-553（PUT :485 + 顶层 {@code {entries}} :487 + If-Match 剥引号 :481 +
 * 412 conflict :495-500 + 结构化 413 解析 :533-541）。
 *
 * <p><b>鉴权</b>：CC 用 first-party OAuth Bearer（isUsingOAuth :151-160 + getAuthHeaders :169-184）——
 * provider 必须是 firstParty + first-party base URL + accessToken 带 inference/profile 双 scope。
 * Java 端 [IMP-CM-07] 落地真实 OAuth 判定：默认构造器注入 {@link FirstPartyOAuthAuthHeaderProvider}，
 * {@code isAuthAvailable()} = {@link #isFirstPartyOAuthAvailable()}（header 非空语义，默认 provider
 * 仅在真实 OAuth 可用时产 header）→ 整链惰性由真实 OAuth 可用性决定（OPD-CM3-08：不强行全部启用，
 * 登录后 sync/watcher 可启动）。测试可注入假 provider 驱动全链。
 *
 * <p>超时 30s（CC TEAM_MEMORY_SYNC_TIMEOUT_MS=30_000）。
 */
@Component
public class TeamMemoryHttpClient {

    private static final Logger log = LoggerFactory.getLogger(TeamMemoryHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TEAM_MEMORY_SYNC_TIMEOUT_MS = 30_000;
    /** CC MAX_RETRIES=3（fetchTeamMemory :90/:394，attempt 1..MAX_RETRIES+1=4 次）。 */
    private static final int MAX_RETRIES = 3;

    // ─── First-party OAuth 常量 · CC constants/oauth.ts:33-36 + utils/auth.ts:1260 ───

    /** CC original: {@code CLAUDE_AI_INFERENCE_SCOPE}（constants/oauth.ts:33）= 'user:inference'。 */
    public static final String CLAUDE_AI_INFERENCE_SCOPE = "user:inference";
    /** CC original: {@code CLAUDE_AI_PROFILE_SCOPE}（constants/oauth.ts:34）= 'user:profile'。 */
    public static final String CLAUDE_AI_PROFILE_SCOPE = "user:profile";
    /** CC original: {@code OAUTH_BETA_HEADER}（constants/oauth.ts:36）= 'oauth-2025-04-20'。 */
    public static final String OAUTH_BETA_HEADER = "oauth-2025-04-20";
    /** CC original: {@code CLAUDE_CODE_OAUTH_TOKEN} env（auth.ts:1260）· Java 可读的 first-party OAuth token 源。 */
    public static final String CLAUDE_CODE_OAUTH_TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN";

    /**
     * OAuth token 快照 · 对齐 CC {@code OAuthTokens}（services/oauth/types.ts）。
     * 仅承载 {@code accessToken} + {@code scopes}（Java 判定用）。
     */
    public record OAuthTokens(String accessToken, Set<String> scopes) {
        public boolean hasAccessToken() {
            return accessToken != null && !accessToken.isBlank();
        }
        public boolean hasScope(String scope) {
            return scopes != null && scopes.contains(scope);
        }
    }

    /** 鉴权 header 注入器（默认 {@link FirstPartyOAuthAuthHeaderProvider} = 真实 OAuth 判定）· IMP-CM-07。 */
    @FunctionalInterface
    public interface AuthHeaderProvider {
        Map<String, String> headers();
        /** 无鉴权（恒空 header → isAuthAvailable false）· 测试/显式 no-auth 场景。 */
        AuthHeaderProvider NONE = Map::of;
    }

    /**
     * 真实 first-party OAuth 鉴权头 · 对齐 CC {@code getAuthHeaders}（index.ts:169-184）。
     * 仅当 {@link #isFirstPartyOAuthAvailable()}（= CC isUsingOAuth 语义）成立时产出
     * {@code Authorization: Bearer <token>} + {@code anthropic-beta} + {@code User-Agent}；
     * 否则空 header → {@code isAuthAvailable()} false → 整链惰性（不炸）。
     */
    public static final class FirstPartyOAuthAuthHeaderProvider implements AuthHeaderProvider {
        private final Supplier<OAuthTokens> tokensSupplier;
        private final java.util.function.BooleanSupplier availabilitySupplier;

        public FirstPartyOAuthAuthHeaderProvider() {
            this(TeamMemoryHttpClient::readClaudeCodeOAuthTokens, TeamMemoryHttpClient::isFirstPartyOAuthAvailable);
        }

        /**
         * 注入式构造器（测试隔离 / 未来 keychain token 源）。
         *
         * @param tokensSupplier      OAuth token 源（null → 读 env CLAUDE_CODE_OAUTH_TOKEN）
         * @param availabilitySupplier 可用性判定（null → {@link #isFirstPartyOAuthAvailable()} env 语义）
         */
        public FirstPartyOAuthAuthHeaderProvider(Supplier<OAuthTokens> tokensSupplier,
                                                 java.util.function.BooleanSupplier availabilitySupplier) {
            this.tokensSupplier = tokensSupplier == null ? TeamMemoryHttpClient::readClaudeCodeOAuthTokens : tokensSupplier;
            this.availabilitySupplier = availabilitySupplier == null
                ? TeamMemoryHttpClient::isFirstPartyOAuthAvailable : availabilitySupplier;
        }

        @Override
        public Map<String, String> headers() {
            if (!availabilitySupplier.getAsBoolean()) {
                return Map.of();
            }
            OAuthTokens tokens = tokensSupplier.get();
            if (tokens == null || !tokens.hasAccessToken()) {
                return Map.of();
            }
            return Map.of(
                "Authorization", "Bearer " + tokens.accessToken(),
                "anthropic-beta", OAUTH_BETA_HEADER,
                // [B-3 登记 · IMP-MV2-40] △-10：CC getClaudeCodeUserAgent() = `claude-code/${VERSION}`
                //   （userAgent.ts:8-9，index.ts:179 使用）；Java 硬编码 'claude-code-java'。服务端按
                //   UA 统计/风控时可见差异，重试/鉴权行为无影响 —— 登记不修（TeamMemorySyncTest
                //   ua_header_sent_toServer 固化现状）。
                "User-Agent", "claude-code-java");
        }
    }

    /** fetch 结果 · CC TeamMemorySyncFetchResult（types.ts:77-87）。 */
    public record FetchResult(
        boolean success,
        TeamMemoryData data,
        boolean isEmpty,       // 404: 服务端无任何数据
        boolean notModified,   // 304: ETag 匹配, 本地仍最新
        String checksum,
        String error,
        boolean skipRetry,
        String errorType,      // auth/timeout/network/parse/unknown
        Integer httpStatus
    ) {
        public static FetchResult ok(TeamMemoryData data, String checksum) {
            return new FetchResult(true, data, false, false, checksum, null, false, null, null);
        }
        public static FetchResult notModified(String checksum) {
            return new FetchResult(true, null, false, true, checksum, null, false, null, null);
        }
        public static FetchResult empty() {
            return new FetchResult(true, null, true, false, null, null, false, null, null);
        }
        public static FetchResult error(String error, boolean skipRetry, String errorType, Integer httpStatus) {
            return new FetchResult(false, null, false, false, null, error, skipRetry, errorType, httpStatus);
        }
    }

    /** push 结果 · CC TeamMemorySyncPushResult（types.ts:107-124）。 */
    // [B-8 登记 · IMP-MV2-40] △-A20：本 record 11 字段 = CC 8 字段超集（+serverErrorCode/
    //   serverMaxEntries/serverReceivedEntries，融合自 CC TeamMemorySyncUploadResult:129-156 的
    //   413 解析字段）。3 个超集字段有真实消费（push 失败路径 SyncService → emitPush 遥测
    //   error_code/server_max_entries/server_received_entries，对齐 CC logPush index.ts:1245-1254），
    //   非 0 消费 → 保留登记，非删除候选。
    public record PushResult(
        boolean success,
        int filesUploaded,
        String checksum,
        boolean conflict,          // 412 Precondition Failed
        String error,
        String errorType,          // auth/timeout/network/conflict/unknown/no_oauth/no_repo
        Integer httpStatus,
        String serverErrorCode,    // team_memory_too_many_entries
        Integer serverMaxEntries,
        Integer serverReceivedEntries,
        /** 因检测到 secret 而跳过的文件 · CC original: {@code skippedSecrets}（types.ts:114，
         *  {@code SkippedSecretFile[]}，可选字段）—— CC :986/:1042 仅非空时随成功结果返回。 */
        java.util.List<com.nexusai.application.agent.team.TeamMemorySyncTypes.SkippedSecretFile> skippedSecrets
    ) {
    }

    /** hash-only probe 结果 · CC TeamMemoryHashesResult（types.ts:94-102）。 */
    public record HashesResult(
        boolean success,
        Long version,
        String checksum,
        Map<String, String> entryChecksums,
        String error,
        String errorType,
        Integer httpStatus
    ) {
        public static HashesResult ok(Long version, String checksum, Map<String, String> entryChecksums) {
            return new HashesResult(true, version, checksum, entryChecksums, null, null, null);
        }
        public static HashesResult emptyOk() {
            return new HashesResult(true, null, null, new LinkedHashMap<>(), null, null, null);
        }
        public static HashesResult error(String error, String errorType, Integer httpStatus) {
            return new HashesResult(false, null, null, null, error, errorType, httpStatus);
        }
    }

    /** 单次 PUT 上传结果 · CC TeamMemorySyncUploadResult（types.ts:129-156）。 */
    public record UploadResult(
        boolean success,
        String checksum,
        String lastModified,
        boolean conflict,
        String error,
        String errorType,
        Integer httpStatus,
        String serverErrorCode,
        Integer serverMaxEntries,
        Integer serverReceivedEntries
    ) {
        public static UploadResult ok(String checksum, String lastModified) {
            return new UploadResult(true, checksum, lastModified, false, null, null, null, null, null, null);
        }
        public static UploadResult conflictResult() {
            return new UploadResult(false, null, null, true, "ETag mismatch", "unknown", 412, null, null, null);
        }
        public static UploadResult fail(String error, String errorType, Integer httpStatus,
                                        String serverErrorCode, Integer serverMaxEntries, Integer serverReceivedEntries) {
            return new UploadResult(false, null, null, false, error, errorType, httpStatus,
                serverErrorCode, serverMaxEntries, serverReceivedEntries);
        }
    }

    private final HttpClient httpClient;
    private final AuthHeaderProvider authHeaderProvider;

    public TeamMemoryHttpClient() {
        // followRedirects(NORMAL) 对齐 CC axios 默认跟随重定向（followRedirects=true maxRedirects=5）
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build(),
            new FirstPartyOAuthAuthHeaderProvider());
    }

    public TeamMemoryHttpClient(HttpClient httpClient, AuthHeaderProvider authHeaderProvider) {
        this.httpClient = httpClient;
        this.authHeaderProvider = authHeaderProvider;
    }

    /**
     * 鉴权可用性 · 对齐 CC {@code isUsingOAuth}（index.ts:151-160）+ {@code isTeamMemorySyncAvailable}
     * （index.ts:762-764）。默认构造器注入 {@link FirstPartyOAuthAuthHeaderProvider} → header 非空语义 =
     * 真实 first-party OAuth 可用性；注入式构造器（测试）由调用方 provider 语义驱动。
     */
    public boolean isAuthAvailable() {
        return !authHeaderProvider.headers().isEmpty();
    }

    // ─── First-party OAuth 可用性判定 · CC index.ts:151-160 + providers.ts:8-14/25-37 + auth.ts:1260 ───

    /**
     * CC original: {@code getAPIProvider}（utils/model/providers.ts:8-14）· env truthy
     * {@code CLAUDE_CODE_USE_BEDROCK/VERTEX/FOUNDRY} → 对应非 firstParty provider，否则 firstParty。
     */
    static String apiProvider() {
        if (TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK"))) return "bedrock";
        if (TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_VERTEX"))) return "vertex";
        if (TaskSystemConfig.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_FOUNDRY"))) return "foundry";
        return "firstParty";
    }

    /**
     * [W4-1] base URL 来源 · env 路删除（ANTHROPIC_BASE_URL 不再读，用户拍板彻底删除 env）→ 改读
     * DB provider baseUrl（主链 ProviderConfig 同源）。CC 真源：providers.ts:26
     * {@code process.env.ANTHROPIC_BASE_URL}。默认（未注入 DB mapper / 无 provider）→ null →
     * first-party 语义（同 CC env 未设 → true）。Spring 侧 {@link #setProviderMapper} 安装 DB 读取；
     * 测试可替换（静态 hook 同 {@code FastModeRuntimeState.ENV_READER} 约定）。
     */
    static volatile Supplier<String> baseUrlSource = () -> null;

    /**
     * CC original: {@code isFirstPartyAnthropicBaseUrl}（utils/model/providers.ts:25-37）·
     * DB provider baseUrl 空 → first-party；否则 host 必须为 {@code api.anthropic.com}。
     */
    static boolean isFirstPartyAnthropicBaseUrl() {
        String baseUrl = baseUrlSource.get();
        boolean firstParty = StructuredOutputsSupport.isFirstPartyAnthropicBaseUrl(baseUrl);
        if (log.isDebugEnabled()) {
            log.debug("TeamMemoryHttpClient first-party baseUrl 判定: baseUrl={} → {}（[W4-1] DB provider 来源）",
                baseUrl, firstParty);
        }
        return firstParty;
    }

    // [① OAuth 处置登记 · IMP-MV2-40] △-A3/△-16（02 B07，门 OPD-MM-03）：生产恒 no_oauth 为
    //   OPD-CM3-08 拍板的有意设计（「不强行全部启用」）——默认接受 no_oauth 登记，不接线。
    //   若未来裁决接线 CC 等效：须补 keychain/fd token 源（auth.ts:1255-1270 三分支）+ 每次
    //   fetch/upload 前 checkAndRefreshOAuthTokenIfNeeded 等价（index.ts:194/:320/:469，△-16 一并）。
    /**
     * CC original: {@code getClaudeAIOAuthTokens}（utils/auth.ts:1255-1270）env 分支 ·
     * {@code CLAUDE_CODE_OAUTH_TOKEN} env → inference-only token（scopes=['user:inference']，
     * auth.ts:1265）；无 env → null。
     */
    static OAuthTokens readClaudeCodeOAuthTokens() {
        String token = System.getenv(CLAUDE_CODE_OAUTH_TOKEN_ENV);
        if (token == null || token.isBlank()) {
            return null;
        }
        return new OAuthTokens(token, Set.of(CLAUDE_AI_INFERENCE_SCOPE));
    }

    /**
     * CC original: {@code isUsingOAuth}（index.ts:151-160）= provider firstParty && first-party base URL
     * && accessToken && scopes 含 inference + profile 双 scope。
     *
     * <p>[IMP-CM-07] 严格对齐 CC：env token 仅 inference scope（auth.ts:1265）→ 不满足 profile scope
     * → 默认惰性（不强行全部启用，OPD-CM3-08 拍板「接线保持与 CC 一致」）；登录后（未来 keychain/
     * 完整 OAuth token 源）双 scope 齐 → 可用。Java 侧无需读 CC 编译期宏（feature 开关由 IMP-CM-08
     * 可配置项承载，IM-09 双门控拆分）。
     *
     * @param provider            api provider（CC getAPIProvider）
     * @param firstPartyBaseUrl   是否 first-party base URL（CC isFirstPartyAnthropicBaseUrl）
     * @param tokensSupplier      OAuth token 源
     */
    static boolean isFirstPartyOAuthAvailable(String provider, boolean firstPartyBaseUrl,
                                              Supplier<OAuthTokens> tokensSupplier) {
        if (!"firstParty".equals(provider)) {
            return false;
        }
        if (!firstPartyBaseUrl) {
            return false;
        }
        OAuthTokens tokens = tokensSupplier.get();
        return tokens != null
            && tokens.hasAccessToken()
            && tokens.hasScope(CLAUDE_AI_INFERENCE_SCOPE)
            && tokens.hasScope(CLAUDE_AI_PROFILE_SCOPE);
    }

    /**
     * env 语义下的 first-party OAuth 可用性（provider + base URL + token 全部读 env）·
     * 供 {@link FirstPartyOAuthAuthHeaderProvider} 默认构造 / {@link TeamMemPaths} / {@code MemoryFileDetection}
     * / {@code SessionFileAccessHooks} 生产构造引用。public 因跨包（permission.hook）消费。
     */
    public static boolean isFirstPartyOAuthAvailable() {
        return isFirstPartyOAuthAvailable(apiProvider(), isFirstPartyAnthropicBaseUrl(),
            TeamMemoryHttpClient::readClaudeCodeOAuthTokens);
    }

    /**
     * [W4-1] 旁路改 DB：注入 {@link ProviderMapper} 后将 {@link #baseUrlSource} 切换为 DB 读取
     * （首个 enabled provider 的 baseUrl，主链 ProviderConfig 同源）。{@code required=false}：
     * 测试/孤立运行不注入 → 保持默认 null（first-party）。同 CompactThresholdSystem#setProviderMapper
     * 的 W2/W3 注入风格。
     */
    @Autowired(required = false)
    public void setProviderMapper(ProviderMapper providerMapper) {
        if (providerMapper != null) {
            baseUrlSource = () -> firstEnabledProviderBaseUrl(providerMapper);
            log.info("TeamMemoryHttpClient: ANTHROPIC_BASE_URL env 路删除，first-party 判定改读 DB provider baseUrl");
        }
    }

    /**
     * [W4-1] 首个 enabled provider 的 baseUrl（近似主链实际生效的 Anthropic 端点）。
     * 无 provider / baseUrl 全空 / 读取失败 → null → first-party 语义。
     */
    private static String firstEnabledProviderBaseUrl(ProviderMapper providerMapper) {
        try {
            List<ProviderRecord> list =
                providerMapper.selectListByQuery(QueryWrapper.create().where("enabled = ?", true));
            for (ProviderRecord r : list) {
                if (r.getBaseUrl() != null && !r.getBaseUrl().isBlank()) {
                    return r.getBaseUrl();
                }
            }
        } catch (Exception e) {
            log.warn("TeamMemoryHttpClient: DB provider baseUrl 读取失败, 回落 first-party: {}", e.toString());
        }
        return null;
    }

    // ─── Endpoint ───────────────────────────────────────────────

    /**
     * 端点 · CC original: {@code getTeamMemorySyncEndpoint}（index.ts:163-167）
     * = {@code {baseUrl}/api/claude_code/team_memory?repo={slug}}。仅 {@code ?repo=} 参数
     * （旧 Java 的 {@code ?org=} 偏差删除）。encodeURIComponent 用 URLEncoder（owner/repo 无空格）。
     */
    String endpoint(String baseUrl, String repoSlug) {
        return baseUrl + "/api/claude_code/team_memory?repo="
            + URLEncoder.encode(repoSlug, StandardCharsets.UTF_8);
    }

    /** 剥引号 · CC :208 {@code etag.replace(/"/g, '')} / :481。 */
    private static String stripQuotes(String s) {
        return s == null ? null : s.replace("\"", "");
    }

    // ─── Fetch（pull）───────────────────────────────────────────

    /**
     * 单次 GET · CC original: {@code fetchTeamMemoryOnce}（index.ts:188-306）。
     * If-None-Match 带引号包裹（剥内部引号）；304 → notModified；404 → isEmpty + 清 lastKnownChecksum；
     * 200 → 解析 TeamMemoryData，checksum 取 body.checksum 或 ETag 剥引号。
     */
    public FetchResult fetchOnce(SyncState state, String baseUrl, String repoSlug, String etag) {
        if (!isAuthAvailable()) {
            return FetchResult.error("No OAuth token available for team memory sync", true, "auth", null);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(baseUrl, repoSlug)))
                .timeout(Duration.ofMillis(TEAM_MEMORY_SYNC_TIMEOUT_MS))
                .header("Accept", "application/json")
                // CC axios 默认 followRedirects=true maxRedirects=5（index.ts:212-217）。java.net.http 的
                // redirect 策略是 client 级（HttpClient.Builder.followRedirects），无法 per-request 设置；
                // 生产接线在默认构造器（HttpClient.Redirect.NORMAL）。注入构造器（测试）由调用方控制 client。
                .GET();
            authHeaderProvider.headers().forEach(builder::header);
            if (etag != null && !etag.isEmpty()) {
                builder.header("If-None-Match", "\"" + stripQuotes(etag) + "\"");
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 304) {
                if (log.isDebugEnabled()) {
                    log.debug("team-memory-sync: not modified (304)");
                }
                return FetchResult.notModified(etag);
            }
            if (code == 404) {
                if (log.isDebugEnabled()) {
                    log.debug("team-memory-sync: no remote data (404)");
                }
                state.lastKnownChecksum = null;
                return FetchResult.empty();
            }
            // CC axios validateStatus 仅接受 200/304/404（index.ts:215-217）—— 3xx 触发 axios 拒绝 →
            // classifyAxiosError 'http' → errorType 'unknown' + httpStatus（:297-303）。未被
            // followRedirects 消化掉的 3xx（无 Location / HTTPS→HTTP 降级）在此显式转 error。
            if (code >= 300 && code < 400) {
                return FetchResult.error("HTTP " + code, false, "unknown", code);
            }
            // TMS-07（G-56）：401/403 → auth + skipRetry:true + 文案含响应体 · CC classifyAxiosError
            // 401/403→'auth'（errors.ts:232）+ fetch 侧 skipRetry:true（index.ts:277-284）
            // `Not authorized for team memory sync: {body}`。旧实现落入 code>=400 通用分支 →
            // errorType 'unknown' + skipRetry:false → 对 401 也重试 4 次（DRIFT-6 扩展）。
            if (code == 401 || code == 403) {
                String body = resp.body() == null ? "" : resp.body();
                return FetchResult.error("Not authorized for team memory sync: " + body,
                    true, "auth", code);
            }
            if (code >= 400) {
                return FetchResult.error("HTTP " + code, false, "unknown", code);
            }
            JsonNode json;
            try {
                json = MAPPER.readTree(resp.body());
            } catch (Exception e) {
                // 200 但 body 非 JSON —— 与 schema 校验同一失败语义（CC safeParse(undefined) 失败）
                return FetchResult.error("Invalid team memory response format", true, "parse", null);
            }
            // TMS-08（G-57）：200 响应体 Zod 等价校验 · CC TeamMemoryDataSchema().safeParse
            // （types.ts:29-38 + index.ts:234-245）。缺 organizationId/content 等 → skipRetry:true
            // + errorType 'parse'。旧实现 json.path 空默认值 → 畸形 200 体静默变空数据（DRIFT-11）。
            TeamMemoryData data = parseTeamMemoryData(json);
            if (data == null) {
                return FetchResult.error("Invalid team memory response format", true, "parse", null);
            }
            Map<String, String> entries = data.content().entries();
            Map<String, String> checksums = data.content().entryChecksums();
            // checksum 取 body.checksum 或 ETag 剥引号（CC :248-254）
            String bodyChecksum = data.checksum();
            String responseChecksum = (!bodyChecksum.isEmpty() ? bodyChecksum
                : resp.headers().firstValue("ETag").orElse(""));
            responseChecksum = stripQuotes(responseChecksum);
            if (responseChecksum != null && !responseChecksum.isEmpty()) {
                state.lastKnownChecksum = responseChecksum;
            }
            if (log.isDebugEnabled()) {
                log.debug("team-memory-sync: fetched successfully (checksum: {})",
                    responseChecksum == null ? "none" : responseChecksum);
            }
            return FetchResult.ok(data, responseChecksum);
            // [B-4 登记 · IMP-MV2-40] △-11/13/14：30s 请求超时抛 java.net.http.HttpTimeoutException
            //   （extends IOException，JDK 公开 API 事实）→ 落下方 catch(IOException) → 'network'；
            //   CC axios 超时 code='ECONNABORTED' → 'timeout'（errors.ts:233 + index.ts:285-290）。
            //   重试行为两端一致（network 非 skipRetry 仍重试），仅 errorType/遥测/文案偏移 —— 登记不修。
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("team-memory-sync: fetch network error: {}", e.getMessage());
            }
            return FetchResult.error("Cannot connect to server", false, "network", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.error("Team memory sync request timeout", false, "timeout", null);
        } catch (Exception e) {
            return FetchResult.error(e.getMessage(), true, "parse", null);
        }
    }

    /**
     * 带重试的 GET · CC original: {@code fetchTeamMemory}（index.ts:387-410）。MAX_RETRIES=3 指数退避
     * （getRetryDelay 等价：500*2^(attempt-1)ms + 0.25 抖动，32000ms 上限）；success/skipRetry 提前返回。
     */
    public FetchResult fetch(SyncState state, String baseUrl, String repoSlug, String etag) {
        FetchResult last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            last = fetchOnce(state, baseUrl, repoSlug, etag);
            if (last.success() || last.skipRetry()) {
                return last;
            }
            if (attempt > MAX_RETRIES) {
                return last;
            }
            long delayMs = retryDelayMs(attempt);
            if (log.isDebugEnabled()) {
                log.debug("team-memory-sync: retry {}/{} delay={}ms", attempt, MAX_RETRIES, delayMs);
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** getRetryDelay 等价 · CC services/api/withRetry.ts:530-547
     *  {@code base = min(BASE_DELAY_MS * 2^(attempt-1), maxDelayMs)} + {@code jitter = random()*0.25*base}，
     *  {@code BASE_DELAY_MS=500}（withRetry.ts:55）、{@code maxDelayMs=32000}（:544 默认参）。 */

    /**
     * Zod 等价校验 · CC original: {@code TeamMemoryDataSchema().safeParse}（types.ts:29-38 +
     * index.ts:234-245）。schema（zod v4）：
     * <pre>
     * organizationId: z.string(), repo: z.string(), version: z.number(),
     * lastModified: z.string(), checksum: z.string(),
     * content: { entries: z.record(z.string(), z.string()),
     *            entryChecksums: z.record(z.string(), z.string()).optional() }
     * </pre>
     * 任何字段缺失 / 类型不符 / entries 值非 string → 返回 null（调用方转 errorType 'parse' +
     * skipRetry:true）。z.object 默认剥离未知字段，仅必填字段校验。
     *
     * @param json 200 响应体（已由调用方 readTree 解析；非 JSON 由调用方先行转 parse 错误）
     * @return 校验通过的 TeamMemoryData，或 null（畸形响应）
     */
    private static TeamMemoryData parseTeamMemoryData(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        if (!json.path("organizationId").isTextual()
            || !json.path("repo").isTextual()
            || !json.path("version").isNumber()
            || !json.path("lastModified").isTextual()
            || !json.path("checksum").isTextual()) {
            return null;
        }
        JsonNode content = json.path("content");
        if (!content.isObject()) {
            return null;
        }
        JsonNode entriesNode = content.path("entries");
        if (!entriesNode.isObject()) {
            return null;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = entriesNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isTextual()) {
                return null;
            }
            entries.put(e.getKey(), e.getValue().asText());
        }
        Map<String, String> checksums = new LinkedHashMap<>();
        JsonNode checksumsNode = content.path("entryChecksums");
        if (!checksumsNode.isMissingNode()) {
            if (!checksumsNode.isObject()) {
                return null;
            }
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = checksumsNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isTextual()) {
                    return null;
                }
                checksums.put(e.getKey(), e.getValue().asText());
            }
        }
        return new TeamMemoryData(
            json.path("organizationId").asText(),
            json.path("repo").asText(),
            json.path("version").asLong(),
            json.path("lastModified").asText(),
            json.path("checksum").asText(),
            new TeamMemoryContent(entries, checksums));
    }

    static long retryDelayMs(int attempt) {
        long base = Math.min(500L * (1L << (attempt - 1)), 32000L);
        long jitter = (long) (Math.random() * 0.25 * base);
        return base + jitter;
    }

    /**
     * hash-only 探针 · CC original: {@code fetchTeamMemoryHashes}（index.ts:315-385）
     * = GET {@code ?view=hashes}。404 → lastKnownChecksum=null + 空 entryChecksums；200 → 取
     * checksum + entryChecksums；缺 entryChecksums → probe 失败（push 方 fail，watcher 下次重试）。
     */

    public HashesResult fetchHashes(SyncState state, String baseUrl, String repoSlug) {
        if (!isAuthAvailable()) {
            return HashesResult.error("No OAuth token available for team memory sync", "auth", null);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                    URI.create(endpoint(baseUrl, repoSlug) + "&view=hashes"))
                .timeout(Duration.ofMillis(TEAM_MEMORY_SYNC_TIMEOUT_MS))
                .header("Accept", "application/json")
                .GET();
            authHeaderProvider.headers().forEach(builder::header);
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 404) {
                state.lastKnownChecksum = null;
                return HashesResult.emptyOk();
            }
            // CC validateStatus 仅接受 200/404（index.ts:330）—— 3xx 触发 axios 拒绝 → classifyAxiosError
            // 'http' → errorType 'unknown' + httpStatus（:346-352）。未被 followRedirects 消化的 3xx
            // （无 Location / HTTPS→HTTP 降级）在此显式转 error，勿落入 readTree（镜像 fetchOnce :241-243）。
            if (code >= 300 && code < 400) {
                return HashesResult.error("HTTP " + code, "unknown", code);
            }
            // TMS-07（G-56）：401/403 → auth · CC fetchTeamMemoryHashes auth 分支（index.ts:365-371）
            // 文案 'Not authorized' + errorType 'auth' + httpStatus。旧实现落入 code>=400 →
            // 'HTTP {code}' + 'unknown'（DRIFT-6 扩展）。
            if (code == 401 || code == 403) {
                return HashesResult.error("Not authorized", "auth", code);
            }
            if (code >= 400) {
                return HashesResult.error("HTTP " + code, "unknown", code);
            }
            JsonNode json = MAPPER.readTree(resp.body());
            String bodyChecksum = json.path("checksum").asText("");
            String checksum = (!bodyChecksum.isEmpty() ? bodyChecksum
                : resp.headers().firstValue("ETag").orElse(""));
            checksum = stripQuotes(checksum);
            JsonNode entryChecksumsNode = json.path("entryChecksums");
            // [B-12 登记 · IMP-MV2-40] △-22：Java isObject() 拒绝数组 vs CC `typeof !== 'object'`
            //   对数组放行（index.ts:344）→ 畸形服务端（entryChecksums 为数组）下 Java 显式 probe 失败
            //   （push 失败）vs CC 静默继续。可触发性极低（协议违规），Java 更严为安全方向 —— 登记不修。
            if (!entryChecksumsNode.isObject()) {
                // 需 anthropic/anthropic#283027；缺 entryChecksums 视为 probe 失败
                return HashesResult.error("Server did not return entryChecksums (?view=hashes unsupported)",
                    "parse", null);
            }
            Map<String, String> entryChecksums = new LinkedHashMap<>();
            entryChecksumsNode.fields().forEachRemaining(e -> entryChecksums.put(e.getKey(), e.getValue().asText()));
            if (checksum != null && !checksum.isEmpty()) {
                state.lastKnownChecksum = checksum;
            }
            long version = json.path("version").asLong(0);
            return HashesResult.ok(version > 0 ? version : null, checksum, entryChecksums);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HashesResult.error("Timeout", "timeout", null);
            // [B-4 登记 · IMP-MV2-40] △-13：fetchHashes 超时（HttpTimeoutException extends IOException）
            //   落 catch(Exception) → 'unknown' vs CC 'timeout'（index.ts:373）；重试行为一致，登记不修。
        } catch (Exception e) {
            return HashesResult.error(e.getMessage(), "unknown", null);
        }
    }

    // ─── Upload（push）───────────────────────────────────────────

    /**
     * PUT 上传 · CC original: {@code uploadTeamMemory}（index.ts:462-553）。请求体顶层
     * {@code {entries}}（旧 Java 嵌套 content/organizationId/repo 的偏差删除）；If-Match 带引号
     * 包裹（剥内部引号）；412 → conflict；413 → 解析结构化 too_many_entries（error_code + max_entries +
     * received_entries，缓存供下次 push 裁剪）；200 → checksum 取 body.checksum，更新 lastKnownChecksum。
     */
    public UploadResult upload(SyncState state, String baseUrl, String repoSlug,
                               Map<String, String> entries, String ifMatchChecksum) {
        if (!isAuthAvailable()) {
            return UploadResult.fail("No OAuth token available for team memory sync", "auth", null, null, null, null);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entries", entries);
            String json = MAPPER.writeValueAsString(body);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(baseUrl, repoSlug)))
                .timeout(Duration.ofMillis(TEAM_MEMORY_SYNC_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            authHeaderProvider.headers().forEach(builder::header);
            if (ifMatchChecksum != null && !ifMatchChecksum.isEmpty()) {
                builder.header("If-Match", "\"" + stripQuotes(ifMatchChecksum) + "\"");
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 412) {
                if (log.isInfoEnabled()) {
                    log.info("team-memory-sync: conflict (412 Precondition Failed)");
                }
                return UploadResult.conflictResult();
            }
            // CC axios validateStatus 仅接受 200/412（index.ts:491）—— 3xx 触发 axios 拒绝 →
            // classifyAxiosError 'http'（errors.ts:237-238）→ errorType 'unknown' + httpStatus
            // （index.ts:524-525）。未被 followRedirects 消化的 3xx（无 Location / HTTPS→HTTP
            // 降级）在此显式转失败，勿落入 200 路径：空体/HTML 落 readTree 抛 parse 后仍返回
            // UploadResult.ok(null,null) success:true（fail-open）→ 服务端未接收但本地
            // serverChecksums 已更新（△-18）。镜像 fetchOnce :382-383 / fetchHashes :568-570。
            if (code >= 300 && code < 400) {
                return UploadResult.fail("HTTP " + code, "unknown", code, null, null, null);
            }
            // TMS-07（G-56）：401/403 → auth · CC uploadTeamMemory catch 分支（index.ts:524-525）
            // errorType = kind==='http'||'other' ? 'unknown' : kind → 401/403（classifyAxiosError
            // 'auth'，errors.ts:232）→ 'auth' + httpStatus。旧实现落入 code>=400 通用分支 →
            // 'unknown'（DRIFT-6 扩展）。文案沿用 "HTTP {code}"（CC 为 axios message，表达差异 N/A）。
            if (code == 401 || code == 403) {
                return UploadResult.fail("HTTP " + code, "auth", code, null, null, null);
            }
            if (code >= 400) {
                String serverErrorCode = null;
                Integer serverMaxEntries = null;
                Integer serverReceivedEntries = null;
                if (code == 413) {
                    // 结构化 413（anthropic/anthropic#293258）：error.details.error_code +
                    // max_entries + received_entries。对齐 CC TeamMemoryTooManyEntriesSchema
                    // （types.ts:47-57）：z.literal('team_memory_too_many_entries') + z.number().int().positive()
                    // —— error_code 非该 literal 或 max/received 非正整数（字符串/0/负/小数）时 safeParse
                    // 失败 → 三项全留 null，不缓存畸形值（旧实现 canConvertToInt 接受 0/负 → 下次 push
                    // 按 serverMaxEntries 裁剪到 0 条全空，CM-D1 △-2，IMP-CM-10）。
                    try {
                        JsonNode err = MAPPER.readTree(resp.body());
                        JsonNode details = err.path("error").path("details");
                        String ec = details.path("error_code").asText("");
                        if ("team_memory_too_many_entries".equals(ec)
                            && isPositiveInt(details.path("max_entries"))
                            && isPositiveInt(details.path("received_entries"))) {
                            serverErrorCode = ec;
                            serverMaxEntries = details.path("max_entries").asInt();
                            serverReceivedEntries = details.path("received_entries").asInt();
                        }
                    } catch (Exception parseEx) {
                        // 非结构化 413（gateway HTML）—— 无 error_code 可学，走泛化失败
                    }
                }
                return UploadResult.fail("HTTP " + code, "unknown", code,
                    serverErrorCode, serverMaxEntries, serverReceivedEntries);
            }
            String responseChecksum = null;
            String lastModified = null;
            try {
                JsonNode jsonNode = MAPPER.readTree(resp.body());
                String bodyChecksum = jsonNode.path("checksum").asText("");
                if (!bodyChecksum.isEmpty()) {
                    responseChecksum = bodyChecksum;
                }
                // CC original: lastModified（index.ts:514 `response.data?.lastModified`）—— 200 响应体解析
                String bodyLastModified = jsonNode.path("lastModified").asText("");
                if (!bodyLastModified.isEmpty()) {
                    lastModified = bodyLastModified;
                }
            } catch (Exception parseEx) {
                // 200 但 body 非 JSON（罕见）—— checksum/lastModified 留 null，幂等重试无害
            }
            if (responseChecksum != null) {
                state.lastKnownChecksum = responseChecksum;
            }
            if (log.isDebugEnabled()) {
                log.debug("team-memory-sync: uploaded {} entries (checksum: {})",
                    entries.size(), responseChecksum == null ? "none" : responseChecksum);
            }
            return UploadResult.ok(responseChecksum, lastModified);
            // [B-4 登记 · IMP-MV2-40] △-11：upload 超时同 fetchOnce —— HttpTimeoutException（extends
            //   IOException）落本 catch(IOException) → 'network' vs CC 'timeout'（index.ts:524-525）；
            //   重试行为一致（network 非 skipRetry），仅 errorType/遥测偏移 —— 登记不修。
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("team-memory-sync: upload network error: {}", e.getMessage());
            }
            return UploadResult.fail("Cannot connect to server", "network", null, null, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UploadResult.fail("Team memory sync request timeout", "timeout", null, null, null, null);
        } catch (Exception e) {
            return UploadResult.fail(e.getMessage(), "unknown", null, null, null, null);
        }
    }

    /**
     * CC {@code z.number().int().positive()} 等价（types.ts:52-53）：JSON 数值 + 整型 + &gt; 0。
     * 拒绝字符串（z.number 拒字符串）、0/负（z.positive 拒）、小数（z.int 拒）—— 畸形 413 不缓存。
     */
    private static boolean isPositiveInt(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return false;
        }
        double d = node.asDouble();
        return d == Math.floor(d) && d > 0 && node.canConvertToInt();
    }
}
