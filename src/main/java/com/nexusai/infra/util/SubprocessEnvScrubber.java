package com.nexusai.infra.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * SubprocessEnvScrubber · 对齐 CC utils/subprocessEnv.ts.
 *
 * <p>L1 语义: GHA sub-process env scrubber — 移除敏感 secrets 防止 prompt-injection 外泄。
 * <ul>
 *   <li>{@link #SCRUB_VARS} — 22 个敏感 env vars 列表 (Anthropic + OTLP headers + Cloud + GitHub Actions)</li>
 *   <li>{@link #subprocessEnv(env, isScrubEnabled, proxyEnv)} — 返 scrubbed env map</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SCRUB_VARS Set (22 项) + subprocessEnv static method</li>
 *   <li><b>A2 Golden Trace</b>: isScrubEnabled=true + ANTHROPIC_API_KEY in env → scrubbed out;INPUT_ANTHROPIC_API_KEY also scrubbed;proxyEnv merged if non-empty</li>
 *   <li><b>A3 副作用</b>: 返新 Map 不修改原 env</li>
 *   <li><b>A4 边界</b>: null env→空 map;null isScrubEnabled=false→原 env 返 (proxyEnv merged);proxyEnv null→空</li>
 *   <li><b>A5 业务场景</b>: GitHub Action claude-code-action run;外层 process 保留 ANTHROPIC_API_KEY;Bash tool spawn 移除 (防止 ${ANTHROPIC_API_KEY} 注入外泄)</li>
 * </ul>
 *
 * <p>L3 升级: TS `as const` readonly tuple → Java Set&lt;String&gt; 不可变;
 * TS function feature('X') → Java BooleanSupplier 注入式;
 * TS process.env indexed → Java Map 注入式.
 */
public final class SubprocessEnvScrubber {

    public static final Set<String> SCRUB_VARS = Set.of(
        // Anthropic auth — claude re-reads these per-request
        "ANTHROPIC_API_KEY",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_FOUNDRY_API_KEY",
        "ANTHROPIC_CUSTOM_HEADERS",
        // OTLP exporter headers
        "OTEL_EXPORTER_OTLP_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        // Cloud provider creds
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "AWS_BEARER_TOKEN_BEDROCK",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "AZURE_CLIENT_SECRET",
        "AZURE_CLIENT_CERTIFICATE_PATH",
        // GitHub Actions OIDC
        "ACTIONS_ID_TOKEN_REQUEST_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_URL",
        // GitHub Actions artifact/cache
        "ACTIONS_RUNTIME_TOKEN",
        "ACTIONS_RUNTIME_URL",
        // claude-code-action duplicates
        "ALL_INPUTS",
        "OVERRIDE_GITHUB_TOKEN",
        "DEFAULT_WORKFLOW_TOKEN",
        "SSH_SIGNING_KEY");

    private SubprocessEnvScrubber() {}

    /**
     * Returns a copy of {@code env} with sensitive secrets stripped.
     *
     * @param env             source env map (null → empty result)
     * @param isScrubEnabled  CLAUDE_CODE_SUBPROCESS_ENV_SCRUB gate
     * @param proxyEnv        CCR upstreamproxy env (may be empty)
     * @return new env map (does not mutate inputs)
     */
    public static Map<String, String> subprocessEnv(
        Map<String, String> env,
        BooleanSupplier isScrubEnabled,
        Map<String, String> proxyEnv) {
        if (env == null) env = Map.of();
        if (proxyEnv == null) proxyEnv = Map.of();
        if (isScrubEnabled == null || !isScrubEnabled.getAsBoolean()) {
            // Not scrubbed → return env + proxyEnv (if non-empty)
            if (proxyEnv.isEmpty()) return env;
            java.util.Map<String, String> result = new java.util.HashMap<>(env);
            result.putAll(proxyEnv);
            return result;
        }
        java.util.Map<String, String> result = new java.util.HashMap<>(env);
        result.putAll(proxyEnv);
        for (String k : SCRUB_VARS) {
            result.remove(k);
            // Also strip the GitHub Actions "INPUT_<NAME>" auto-created duplicate
            result.remove("INPUT_" + k);
        }
        return result;
    }
}
