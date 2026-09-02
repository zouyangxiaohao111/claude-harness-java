package com.nexusai.application.agent.api;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap API contract · 对齐 CC services/api/bootstrap.ts.
 *
 * <p>L1 语义: 启动时拉 API bootstrap 数据 (client_data + additional_model_options),
 *            持久化到 global config cache;数据未变 → skip write;
 *            isEssentialTrafficOnly=true → skip;
 *            3P provider → skip;
 *            OAuth preferred (user:profile scope), fallback API key.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: BootstrapResponse record + ModelOption record; fetchBootstrapData() → void;
 *       HttpFetcher interface; isEssentialTrafficOnly + provider + OAuth/API key 注入.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — fetchBootstrapAPI → 成功 → saveGlobalConfig;
 *       数据未变 → skip write;HTTP 失败 → silently catch.</li>
 *   <li><b>A3</b>: 注入式 (essentialOnlySupplier + providerSupplier + authSupplier + httpFetcher + configSupplier + configSaver);
 *       silent failure (log + return).</li>
 *   <li><b>A4</b>: essentialOnly=true → skip;3P provider → skip;无 OAuth 无 API key → skip.</li>
 *   <li><b>A5</b>: 真实场景 — CLI 启动时拉 client_data cache 避免每次网络调用.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Zod schema → Java record (dataUnchanged 用 equals);
 *                    TS lodash isEqual → Java record.equals;
 *                    TS silent catch → Java try/catch + log.
 */
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    public record ModelOption(String value, String label, String description) {}

    public record BootstrapResponse(
        Map<String, Object> clientData,
        java.util.List<ModelOption> additionalModelOptions) {}

    private final java.util.function.BooleanSupplier essentialOnlySupplier;
    private final Supplier<String> providerSupplier;
    private final Supplier<String> oauthTokenSupplier;
    private final Supplier<String> apiKeySupplier;
    private final java.util.function.BooleanSupplier hasProfileScopeSupplier;
    private final HttpFetcher httpFetcher;
    private final Supplier<ConfigState> configSupplier;
    private final java.util.function.BiConsumer<ConfigState, ConfigState> configSaver;

    public Bootstrap(java.util.function.BooleanSupplier essentialOnlySupplier,
            Supplier<String> providerSupplier,
            Supplier<String> oauthTokenSupplier,
            Supplier<String> apiKeySupplier,
            java.util.function.BooleanSupplier hasProfileScopeSupplier,
            HttpFetcher httpFetcher,
            Supplier<ConfigState> configSupplier,
            java.util.function.BiConsumer<ConfigState, ConfigState> configSaver) {
        this.essentialOnlySupplier = Objects.requireNonNull(essentialOnlySupplier);
        this.providerSupplier = Objects.requireNonNull(providerSupplier);
        this.oauthTokenSupplier = Objects.requireNonNull(oauthTokenSupplier);
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier);
        this.hasProfileScopeSupplier = Objects.requireNonNull(hasProfileScopeSupplier);
        this.httpFetcher = httpFetcher == null ? (e, h) -> null : httpFetcher;
        this.configSupplier = Objects.requireNonNull(configSupplier);
        this.configSaver = configSaver == null ? (a, b) -> {} : configSaver;
    }

    public Bootstrap() {
        this(() -> false, () -> "firstParty", () -> null, () -> null, () -> false, null,
            ConfigState::empty, (a, b) -> {});
    }

    public record ConfigState(
        Map<String, Object> clientDataCache,
        java.util.List<ModelOption> additionalModelOptionsCache) {
        public static ConfigState empty() {
            return new ConfigState(Map.of(), java.util.List.of());
        }
    }

    public interface HttpFetcher {
        Object fetch(String endpoint, Map<String, String> headers);
    }

    /** CC fetchBootstrapData — 主链. */
    public void fetchBootstrapData() {
        try {
            if (essentialOnlySupplier.getAsBoolean()) return;
            String provider = providerSupplier.get();
            if (!"firstParty".equals(provider)) return;

            String oauthToken = oauthTokenSupplier.get();
            String apiKey = apiKeySupplier.get();
            boolean hasUsableOAuth = oauthToken != null && !oauthToken.isBlank()
                && hasProfileScopeSupplier.getAsBoolean();
            if (!hasUsableOAuth && (apiKey == null || apiKey.isBlank())) return;

            String endpoint = "https://api.anthropic.com/api/claude_cli/bootstrap";
            Map<String, String> headers;
            if (hasUsableOAuth) {
                headers = Map.of(
                    "Authorization", "Bearer " + oauthToken,
                    "anthropic-beta", "oauth-2025-04-20",
                    "Content-Type", "application/json",
                    "User-Agent", "claude-code-java");
            } else {
                headers = Map.of(
                    "x-api-key", apiKey,
                    "Content-Type", "application/json",
                    "User-Agent", "claude-code-java");
            }
            Object response = httpFetcher.fetch(endpoint, headers);
            if (response == null) return;
            BootstrapResponse parsed = parseBootstrapResponse(response);
            if (parsed == null) return;

            ConfigState current = configSupplier.get();
            if (java.util.Objects.equals(current.clientDataCache(), parsed.clientData())
                && java.util.Objects.equals(current.additionalModelOptionsCache(),
                    parsed.additionalModelOptions())) {
                return; // 数据未变, skip write
            }
            configSaver.accept(current, new ConfigState(
                parsed.clientData(), parsed.additionalModelOptions()));
        } catch (Exception ex) {
            log.warn("fetchBootstrapData failed: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static BootstrapResponse parseBootstrapResponse(Object response) {
        if (!(response instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>) response;
        Map<String, Object> clientData = (Map<String, Object>) map.get("client_data");
        Object optionsObj = map.get("additional_model_options");
        java.util.List<ModelOption> options = java.util.List.of();
        if (optionsObj instanceof java.util.List<?> list) {
            java.util.List<ModelOption> result = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String model = m.get("model") == null ? "" : m.get("model").toString();
                    String name = m.get("name") == null ? "" : m.get("name").toString();
                    String desc = m.get("description") == null ? "" : m.get("description").toString();
                    result.add(new ModelOption(model, name, desc));
                }
            }
            options = result;
        }
        return new BootstrapResponse(clientData, options);
    }
}