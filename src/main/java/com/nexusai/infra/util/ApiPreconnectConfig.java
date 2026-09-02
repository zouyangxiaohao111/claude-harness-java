package com.nexusai.infra.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ApiPreconnectConfig · 对齐 CC utils/apiPreconnect.ts.
 *
 * <p>L1 语义: API preconnect 决策 — fired once per session to warm TLS + connection pool。
 * <ul>
 *   <li>{@code shouldPreconnect(enabledFn, skippedFn, targetFn)} → bool</li>
 *   <li>{@code buildAuthHeaders(tokenFn, customHeadersFn)} → Map</li>
 * </ul>
 *
 * <p>CC 注释: 只有 enabled=true 且 !skipped 才会 preconnect;GHA 跳过 (proxy/mTLS/unix socket 错误 transport)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + 注入式 Supplier</li>
 *   <li><b>A2 Golden Trace</b>: enabled=true, skipped=false→true;enabled=false→false;skipped=true→false;build headers含 x-custom + Authorization token</li>
 *   <li><b>A3 副作用</b>: 注入式 supplier 抽象;无 IO</li>
 *   <li><b>A4 边界</b>: null supplier→false;null token→omits Authorization;empty custom→omits x-custom</li>
 *   <li><b>A5 业务场景</b>: Bun TLS keep-alive warmup 在 first API call 前</li>
 * </ul>
 *
 * <p>L3 升级: TS object spread → Java LinkedHashMap;
 * TS environment variables → Java Supplier 注入式;
 * TS void function → Java void static method.
 */
public final class ApiPreconnectConfig {

    /** Variables whose presence (or truthy value) skips preconnect. */
    public static final String[] SKIP_VARS = {
        "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
        "ANTHROPIC_UNIX_SOCKET", "CLAUDE_CODE_CLIENT_CERT", "CLAUDE_CODE_CLIENT_KEY"
    };

    /** Env vars whose truthy value forces preconnect off. */
    public static final String[] FORCE_OFF_VARS = {
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"
    };

    private ApiPreconnectConfig() {}

    /**
     * Should we preconnect (warm TLS handshake) before first API call?
     * Returns false if disabled, skipped (proxy/mTLS/unix), or in non-1P auth.
     */
    public static boolean shouldPreconnect(
        Supplier<Boolean> enabledFn,
        Supplier<Boolean> skippedFn) {
        if (enabledFn == null || !enabledFn.get()) return false;
        if (skippedFn != null && skippedFn.get()) return false;
        return true;
    }

    /**
     * Build auth + custom headers for the preconnect HEAD request.
     * @param tokenFn         supplies the bearer token (null = omit Authorization)
     * @param customHeadersFn supplies custom headers map (null/empty = omit)
     * @return LinkedHashMap preserving insertion order
     */
    public static java.util.Map<String, String> buildAuthHeaders(
        Supplier<String> tokenFn,
        Supplier<Map<String, String>> customHeadersFn) {
        Map<String, String> headers = new LinkedHashMap<>();
        String token = tokenFn == null ? null : tokenFn.get();
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        Map<String, String> custom = customHeadersFn == null ? null : customHeadersFn.get();
        if (custom != null) {
            for (Map.Entry<String, String> e : custom.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey(), e.getValue());
                }
            }
        }
        return headers;
    }
}
