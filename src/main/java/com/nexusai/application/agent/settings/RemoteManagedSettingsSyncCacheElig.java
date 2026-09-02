package com.nexusai.application.agent.settings;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote managed settings 资格检查 · 对齐 CC services/remoteManagedSettings/syncCache.ts.
 *
 * <p>L1 语义: 检查当前用户是否有资格拉远程托管 settings.
 *            - 3p provider 用户 → 不合格
 *            - 自定义 base URL 用户 → 不合格
 *            - CLAUDE_CODE_ENTRYPOINT === 'local-agent' (cowork VM) → 不合格
 *            - OAuth tokens with subscriptionType=null (CCD/CCR/Agent SDK/CI) → 合格 (API 返回空,误报成本低)
 *            - OAuth with inference scope + enterprise/team → 合格
 *            - Console API key 实际存在 → 合格
 *            - 其他 → 不合格
 *            缓存到 cached (module-level) 避免重复 IO.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: isRemoteManagedSettingsEligible() → boolean;cached 变量 (undefined → 重新计算);
 *       resetSyncCache 清 cached;setEligibility(leaf cache) 同步.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 短路 cached → getAPIProvider 检查 → firstParty base URL →
 *       entrypoint → OAuth tokens (subscriptionType=null 或 enterprise/team) → API key → setEligibility.</li>
 *   <li><b>A3</b>: 状态: NOT_COMPUTED → COMPUTED_TRUE → COMPUTED_FALSE;
 *       cached 持久化结果;reset 清除.</li>
 *   <li><b>A4</b>: provider !== 'firstParty' → false;
 *       custom base URL → false;
 *       entrypoint='local-agent' → false;
 *       OAuth tokens throw/null → skip → fallback API key;
 *       API key throw → catch (CI 环境) → false.</li>
 *   <li><b>A5</b>: 真实场景 — enterprise OAuth user → 合格 + cached;team OAuth user → 合格;
 *       Claude.ai Pro user with personal token (sub=null) → 合格 (API 决策);
 *       API key user (console) → 合格;3p user → 不合格.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `getAPIProvider()` → 注入式 Supplier;
 *                    TS `isFirstPartyAnthropicBaseUrl()` → BooleanSupplier;
 *                    TS `process.env.CLAUDE_CODE_ENTRYPOINT` → 注入式 Supplier;
 *                    TS `getClaudeAIOAuthTokens()` → 注入式 Supplier (token object);
 *                    TS `getAnthropicApiKeyWithSource` → 注入式 ApiKeySupplier;
 *                    TS `setEligibility` → 同步调用 (与 leaf cache 同步).
 */
public final class RemoteManagedSettingsSyncCacheElig {

    private static final Logger log = LoggerFactory.getLogger(RemoteManagedSettingsSyncCacheElig.class);
    private static final String CLAUDE_AI_INFERENCE_SCOPE = "user:inference";
    private static final String LOCAL_AGENT_ENTRYPOINT = "local-agent";

    private final Supplier<String> apiProviderSupplier;
    private final BooleanSupplier firstPartyBaseUrlCheck;
    private final Supplier<String> entrypointSupplier;
    private final Supplier<OAuthTokens> oauthTokensSupplier;
    private final ApiKeySupplier apiKeySupplier;
    private final EligibilitySetter leafEligibilitySetter;
    private final Runnable leafResetter;

    private Boolean cached = null;

    public RemoteManagedSettingsSyncCacheElig(Supplier<String> apiProviderSupplier,
                                              BooleanSupplier firstPartyBaseUrlCheck,
                                              Supplier<String> entrypointSupplier,
                                              Supplier<OAuthTokens> oauthTokensSupplier,
                                              ApiKeySupplier apiKeySupplier,
                                              EligibilitySetter leafEligibilitySetter,
                                              Runnable leafResetter) {
        this.apiProviderSupplier = Objects.requireNonNull(apiProviderSupplier);
        this.firstPartyBaseUrlCheck = Objects.requireNonNull(firstPartyBaseUrlCheck);
        this.entrypointSupplier = Objects.requireNonNull(entrypointSupplier);
        this.oauthTokensSupplier = Objects.requireNonNull(oauthTokensSupplier);
        this.apiKeySupplier = apiKeySupplier;             // may be null (Console path)
        this.leafEligibilitySetter = Objects.requireNonNull(leafEligibilitySetter);
        this.leafResetter = Objects.requireNonNull(leafResetter);
    }

    /** OAuth tokens (CC 最小子集). */
    public record OAuthTokens(String accessToken, String subscriptionType,
                                java.util.List<String> scopes) {}

    /** API key + source. */
    public record ApiKeyWithSource(String key, String source) {}

    /** Eligibility setter (与 leaf cache 同步). */
    @FunctionalInterface
    public interface EligibilitySetter { boolean set(boolean eligible); }

    @FunctionalInterface
    public interface ApiKeySupplier {
        ApiKeyWithSource get(boolean skipRetrievingKeyFromApiKeyHelper) throws Exception;
    }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** CC resetSyncCache — 清 cached + 同步 leaf. */
    public void resetSyncCache() {
        cached = null;
        leafResetter.run();
    }

    /** CC isRemoteManagedSettingsEligible — 主链. */
    public boolean isRemoteManagedSettingsEligible() {
        if (cached != null) return cached;

        // 3p provider 不查 settings endpoint
        if (!"firstParty".equals(apiProviderSupplier.get())) {
            return cached = leafEligibilitySetter.set(false);
        }

        // 自定义 base URL 不查 settings endpoint
        if (!firstPartyBaseUrlCheck.getAsBoolean()) {
            return cached = leafEligibilitySetter.set(false);
        }

        // cowork VM 不适用 server-managed settings
        if (LOCAL_AGENT_ENTRYPOINT.equals(entrypointSupplier.get())) {
            return cached = leafEligibilitySetter.set(false);
        }

        // OAuth 先查 (多数 Claude.ai 用户无 API key)
        OAuthTokens tokens = oauthTokensSupplier.get();

        // 外注 token (subscriptionType=null) 视为合格 (API 决策)
        if (tokens != null && tokens.accessToken() != null
            && tokens.subscriptionType() == null) {
            return cached = leafEligibilitySetter.set(true);
        }

        // enterprise/team + inference scope
        if (tokens != null && tokens.accessToken() != null
            && tokens.scopes() != null && tokens.scopes().contains(CLAUDE_AI_INFERENCE_SCOPE)
            && ("enterprise".equals(tokens.subscriptionType())
                || "team".equals(tokens.subscriptionType()))) {
            return cached = leafEligibilitySetter.set(true);
        }

        // Console API key 实际存在 → 合格
        try {
            ApiKeyWithSource ak = apiKeySupplier.get(true);
            if (ak != null && ak.key() != null && !ak.key().isEmpty()) {
                return cached = leafEligibilitySetter.set(true);
            }
        } catch (Exception e) {
            log.debug("[RemoteSyncCache] API key check failed: {}", e.getMessage());
        }

        return cached = leafEligibilitySetter.set(false);
    }
}
