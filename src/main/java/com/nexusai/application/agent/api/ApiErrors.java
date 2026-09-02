package com.nexusai.application.agent.api;

import com.nexusai.infra.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API errors utility · 对齐 CC services/api/errors.ts.
 *
 * <p><b>ER-IMP-11 重写</b>（DC-08/DC-15 处置）：删除假对齐旧符号（{@code ErrorCategory} 枚举 /
 * {@code classifyError(status,msg)} / {@code buildUserMessage} / {@code shouldPromptToLogin} /
 * {@code isPromptTooLongMessage(String)}），重建 CC 真实行为：
 * <ul>
 *   <li>{@link #classifyApiError(Throwable)} — CC {@code classifyAPIError}（errors.ts:965-1161）25 类
 *       （24 命名 + unknown）分类，返回 Datadog 风格标准错误类型字符串；</li>
 *   <li>{@link #categorizeRetryableApiError(Throwable)} — CC {@code categorizeRetryableAPIError}
 *       （errors.ts:1163-1181），api_retry SDK 载荷 {@code error} 字段（QueryEngine.ts:946-955 消费）；</li>
 *   <li>{@link #startsWithApiErrorPrefix(String)} — CC（errors.ts:55-61），前缀含
 *       {@code 'Please run /login · API Error'}（compact 四文件消费保留）；</li>
 *   <li>{@link #NO_CONTENT_MESSAGE} — CC constants/messages.ts:1 {@code '(no content)'}（空内容消息兜底）。</li>
 * </ul>
 *
 * <p><b>保留符号</b>（compact 模块消费）：{@link #API_ERROR_MESSAGE_PREFIX}、
 * {@link #PROMPT_TOO_LONG_ERROR_MESSAGE}、{@link #startsWithApiErrorPrefix}。
 *
 * <p>{@code REPEATED_529_ERROR_MESSAGE} 为单一来源（本类，A4 决策合并；原
 * ErrorRecoveryConstants 副本已随 C3 拆分删除）。
 */
public final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    /** CC original: API_ERROR_MESSAGE_PREFIX = 'API Error' (errors.ts:54) */
    public static final String API_ERROR_MESSAGE_PREFIX = "API Error";
    /** CC original: PROMPT_TOO_LONG_ERROR_MESSAGE = 'Prompt is too long' (errors.ts:62) */
    public static final String PROMPT_TOO_LONG_ERROR_MESSAGE = "Prompt is too long";
    /** CC original: CUSTOM_OFF_SWITCH_MESSAGE (errors.ts:167) · classifyApiError capacity_off_switch 命中 */
    public static final String CUSTOM_OFF_SWITCH_MESSAGE =
        "Opus is experiencing high load, please use /model to switch to Sonnet";
    /** CC original: CREDIT_BALANCE_TOO_LOW_ERROR_MESSAGE = 'Credit balance is too low' (errors.ts:154) */
    public static final String CREDIT_BALANCE_TOO_LOW_ERROR_MESSAGE =
        "Credit balance is too low";
    /** CC original: API_TIMEOUT_ERROR_MESSAGE = 'Request timed out' (errors.ts:171) */
    public static final String API_TIMEOUT_ERROR_MESSAGE = "Request timed out";
    /** CC original: NO_CONTENT_MESSAGE = '(no content)' (constants/messages.ts:1) */
    public static final String NO_CONTENT_MESSAGE = "(no content)";

    // ════════════════════════════════════════════════════════════════════════
    // 重试参数 · 对齐 CC withRetry.ts:52-55
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: DEFAULT_MAX_RETRIES (withRetry.ts:52) = 10 — 429/529 最大重试次数 */
    public static final int MAX_RETRIES = 10;

    /** CC original: BASE_DELAY_MS (withRetry.ts:55) = 500 — 指数退避基础延迟 (ms) */
    public static final int BASE_DELAY_MS = 500;

    /** CC original: maxDelayMs (withRetry.ts:533) = 32_000 — 指数退避最大延迟 (ms) */
    public static final int MAX_DELAY_MS = 32_000;

    /** CC original: MAX_529_RETRIES (withRetry.ts:54) = 3 — 连续 529 错误触发 fallback 阈值 */
    public static final int MAX_CONSECUTIVE_529 = 3;

    /**
     * CC original: CLAUDE_CODE_MAX_RETRIES env (withRetry.ts:790) — getDefaultMaxRetries 的 env 覆盖。
     *
     * <p>解析链 {@code options.maxRetries ?? env CLAUDE_CODE_MAX_RETRIES ?? DEFAULT_MAX_RETRIES(10)}
     * 见 {@link com.nexusai.application.agent.recovery.WithRetryEngine#getMaxRetries}.
     */
    public static final String ENV_CLAUDE_CODE_MAX_RETRIES = "CLAUDE_CODE_MAX_RETRIES";

    // ════════════════════════════════════════════════════════════════════════
    // 持久重试常量 · 对齐 CC withRetry.ts:96-98 + :100-104
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: PERSISTENT_MAX_BACKOFF_MS = 5 * 60 * 1000 (withRetry.ts:96) — 持久重试最大退避 */
    public static final long PERSISTENT_MAX_BACKOFF_MS = 5 * 60 * 1000L;

    /** CC original: PERSISTENT_RESET_CAP_MS = 6 * 60 * 60 * 1000 (withRetry.ts:97) — 持久重试 reset 上限 */
    public static final long PERSISTENT_RESET_CAP_MS = 6 * 60 * 60 * 1000L;

    /** CC original: HEARTBEAT_INTERVAL_MS = 30_000 (withRetry.ts:98) — 持久重试 keep-alive 心跳分片 */
    public static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    /**
     * 持久重试门控 env · Java NEXUSAI_ 前缀约定（NEXUSAI_EMIT_TOOL_USE_SUMMARIES / QueryConfigAutoConfiguration 同风格）。
     *
     * <p>CC original: CLAUDE_CODE_UNATTENDED_RETRY (withRetry.ts:102) — Java 端以 NEXUSAI_ 前缀改写，
     * 唯一消费方为 {@link com.nexusai.application.agent.recovery.ErrorClassifier#isPersistentRetryEnabled()}
     * （V-PF-4：QueryConfig.unattendedRetryEnabled 字段已删，CC query/config.ts 无等价 gate）。
     */
    public static final String ENV_NEXUSAI_UNATTENDED_RETRY = "NEXUSAI_UNATTENDED_RETRY";

    // ════════════════════════════════════════════════════════════════════════
    // fast mode 禁用门控 env 已删除（F3 用户拍板恒关，2026-08-22）：
    //   原 ENV_NEXUSAI_DISABLE_FAST_MODE（Java NEXUSAI_ 前缀改写 CC CLAUDE_CODE_DISABLE_FAST_MODE
    //   fastMode.ts:39）已删除——非 Anthropic 无 fast-mode 服务端，FastModeRuntimeState.isFastModeEnabled()
    //   恒返回 false，此 env 无消费方（grep 复验：唯一引用 FastModeRuntimeState:104 已随恒关移除）。
    // ════════════════════════════════════════════════════════════════════════
    // fast-mode fallback/cooldown 常量 · 对齐 CC withRetry.ts:799-801 + fastMode.ts:183-317
    // ════════════════════════════════════════════════════════════════════════

    /** CC original: DEFAULT_FAST_MODE_FALLBACK_HOLD_MS = 30 * 60 * 1000 (withRetry.ts:799) — 长/未知 retry-after 回退冷却时长（30 分钟） */
    public static final long DEFAULT_FAST_MODE_FALLBACK_HOLD_MS = 30 * 60 * 1000L;

    /** CC original: SHORT_RETRY_THRESHOLD_MS = 20 * 1000 (withRetry.ts:800) — 短 retry-after 阈值（20 秒，快速重试仍保持 fast mode） */
    public static final long SHORT_RETRY_THRESHOLD_MS = 20 * 1000L;

    /** CC original: MIN_COOLDOWN_MS = 10 * 60 * 1000 (withRetry.ts:801) — 冷却时长下限（10 分钟，避免 flip-flopping） */
    public static final long MIN_COOLDOWN_MS = 10 * 60 * 1000L;

    /** CC original: anthropic-ratelimit-unified-overage-disabled-reason header (withRetry.ts:276) — overage 429 永久禁用 fast mode 的判定 header */
    public static final String HEADER_OVERAGE_DISABLED_REASON = "anthropic-ratelimit-unified-overage-disabled-reason";

    /** CC original: anthropic-ratelimit-unified-reset header (withRetry.ts:815) — 持久 429 的 reset 时间戳 header */
    public static final String HEADER_RATE_LIMIT_UNIFIED_RESET = "anthropic-ratelimit-unified-reset";

    // ════════════════════════════════════════════════════════════════════════
    // max_tokens 上下文溢出调整 · 对齐 CC withRetry.ts:53 + :393
    // ════════════════════════════════════════════════════════════════════════

    /**
     * max_tokens 上下文溢出调整的输出下限 · CC original: FLOOR_OUTPUT_TOKENS (withRetry.ts:53) = 3000。
     *
     * <p>availableContext = max(0, contextLimit - inputTokens - SAFETY_BUFFER)；
     * availableContext &lt; FLOOR_OUTPUT_TOKENS → 不可恢复（CC withRetry.ts:403-408 throw）；
     * 否则 adjustedMaxTokens = max(FLOOR_OUTPUT_TOKENS, availableContext, minRequired)。
     */
    public static final int FLOOR_OUTPUT_TOKENS = 3000;

    /**
     * max_tokens 上下文溢出调整的安全缓冲 · CC original: safetyBuffer (withRetry.ts:393) = 1000。
     *
     * <p>availableContext = max(0, contextLimit - inputTokens - safetyBuffer)。
     */
    public static final int SAFETY_BUFFER = 1000;

    // ════════════════════════════════════════════════════════════════════════
    // Fallback 模型 · 对齐 CC withRetry.ts:337
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 备用模型 ID 默认值（settings 承载）—— <b>仅默认值</b>。
     *
     * <p><b>F4 迁移（用户拍板 2026-08-22）</b>：此值来源从 env 改为 settings.fallbackModelId
     * （V27 建列 fallback_model_id），未配置（null/blank）→ null → 529 快速失败不降级。
     * 运行期经 {@link com.nexusai.application.agent.recovery.TransientErrorHandler#FALLBACK_MODEL_SUPPLIER}
     * 读 settings 单例行（ToolRegistrationConfig @Bean 注入 SettingsMapper）。
     *
     * <p><b>CC 对齐</b>：CC 无 FALLBACK_MODEL_ID env（withRetry.ts:337-351 用按调用传入的
     * {@code options.fallbackModel}，query.ts:188 QueryParams.fallbackModel）；此 env 属 Java
     * 自建，现迁移 settings 配置（行为对齐 CC：无全局默认，未配置即不降级）。
     * 解析优先级：显式传入（非空）优先，settings 兜底默认。
     *
     * <p><b>DC-18 语义修正（ER-IMP-10）</b>：此值仅为 fallback 模型的<b>默认值</b>，
     * 非切换的唯一来源。Java 等价链：
     * {@code QueryParams.fallbackModel → LlmAgentLoop RetryOptions.fallbackModel →
     * LlmAgentLoop handle(fallbackModel) → TransientErrorHandler.tryFallbackModel}。
     * 未配置时为 null，连续 529 达阈值且调用方未传 fallback → 走 REPEATED_529 快速失败。
     *
     * <p><b>实现说明</b>：本常量恒为 null——env 已删除、settings 值为运行期读取。保留此
     * null 常量仅作测试复位值（{@code () -> ApiErrors.FALLBACK_MODEL_ID} = 未配置态 → 不降级）。
     */
    public static final String FALLBACK_MODEL_ID = null;

    /**
     * 连续 529 达阈值后的快速失败消息 · CC original: REPEATED_529_ERROR_MESSAGE
     * (services/api/errors.ts:166) = 'Repeated 529 Overloaded errors'.
     *
     * <p><b>A4 决策对齐</b>：双常量合并——原 ErrorRecoveryConstants 与 ApiErrors 各有副本，
     * 保留本处为单一来源（ErrorRecoveryConstants 已随 C3 拆分删除）。classifyApiError
     * 以 message.contains 命中 → repeated_529 类别。
     */
    public static final String REPEATED_529_ERROR_MESSAGE = "Repeated 529 Overloaded errors";

    /**
     * 「任意主模型 3×529 即降级」开关 env · CC original: FALLBACK_FOR_ALL_PRIMARY_MODELS
     * (withRetry.ts:331)。
     *
     * <p>CC 资格闸（withRetry.ts:330-335）：
     * {@code FALLBACK_FOR_ALL_PRIMARY_MODELS || (!isClaudeAISubscriber() && isNonCustomOpusModel(model))}。
     * 设置此 env（truthy）时跳过 isNonCustomOpusModel 判定，任意主模型连续 529 达阈值均计入；
     * 未设置则仅非自定义 Opus 主模型（{@link com.nexusai.application.agent.recovery.ModelNameUtil#isNonCustomOpusModel}）计入。
     * isClaudeAISubscriber 本项目 N/A（ErrorClassifier:649 恒 false）。
     *
     * <p><b>A3 决策对齐</b>：CC 使用 JavaScript truthy 语义（{@code process.env.X}），
     * 任意非空字符串即开启（含 "false"、"0"）。Java 端以 {@code env != null && !env.isEmpty()}
     * 等价对齐（{@link com.nexusai.application.agent.recovery.TransientErrorHandler#isEligibleFor529Fallback}），
     * 不用 {@link com.nexusai.application.agent.recovery.ErrorClassifier#isEnvTruthy}（仅 {1,true,yes,on}，语义不符）。
     */
    public static final String ENV_FALLBACK_FOR_ALL_PRIMARY_MODELS =
        "FALLBACK_FOR_ALL_PRIMARY_MODELS";

    private ApiErrors() {
        // 纯静态工具类（ER-IMP-11：删除旧实例字段 apiKeySupplier / 构造器）
    }

    /**
     * CC startsWithApiErrorPrefix — errors.ts:55-61。
     *
     * <p>CC 前缀含 {@code 'Please run /login · API Error'}（§5G 实证：Java 旧实现为
     * {@code '/login · '}，前缀文案不符 → ER-IMP-11 修正）。compact 四文件消费保留。
     *
     * @param text 消息文本（可 null）
     * @return true=以 API 错误前缀开始
     */
    public static boolean startsWithApiErrorPrefix(String text) {
        if (text == null) return false;
        return text.startsWith(API_ERROR_MESSAGE_PREFIX)
            || text.startsWith("Please run /login · " + API_ERROR_MESSAGE_PREFIX);
    }

    /**
     * CC categorizeRetryableAPIError — errors.ts:1163-1181。
     *
     * <p>SDK 载荷 {@code error} 字段（QueryEngine.ts:952 {@code error: categorizeRetryableAPIError(...)}）。
     * <pre>
     *   529 / "overloaded_error"   → rate_limit
     *   429                        → rate_limit
     *   401 / 403                  → authentication_failed
     *   status ≥ 408               → server_error
     *   其余                       → unknown
     * </pre>
     *
     * @param error API 错误（Java {@link LlmApiException}；非 LlmApiException → 无 status → unknown）
     * @return 分类字符串（rate_limit / authentication_failed / server_error / unknown）
     */
    public static String categorizeRetryableApiError(Throwable error) {
        if (error instanceof LlmApiException ex) {
            int status = ex.status();
            String message = ex.getMessage() != null ? ex.getMessage() : "";
            if (status == 529 || message.contains("\"type\":\"overloaded_error\"")) {
                return "rate_limit";
            }
            if (status == 429) {
                return "rate_limit";
            }
            if (status == 401 || status == 403) {
                return "authentication_failed";
            }
            if (status >= 408) {
                return "server_error";
            }
        }
        return "unknown";
    }

    /**
     * CC classifyAPIError — errors.ts:965-1161（25 类 = 24 命名 + unknown，grep 自验）。返回标准错误类型字符串（Datadog 标签风格）。
     *
     * <p>判定顺序与 CC 完全一致：
     * aborted → api_timeout → repeated_529 → capacity_off_switch → rate_limit(429) →
     * server_overload(529/"overloaded_error") → prompt_too_long → pdf_too_large →
     * pdf_password_protected → image_too_large → tool_use_mismatch → unexpected_tool_result →
     * duplicate_tool_use_id → invalid_model → credit_balance_low → invalid_api_key →
     * token_revoked → oauth_org_not_allowed → auth_error(401/403) → bedrock_model_access →
     * server_error(≥500) → client_error(400..499) → ssl_cert_error → connection_error → unknown。
     *
     * <p><b>P-18 库函数保留标注（2026-08-15）</b>：CC {@code classifyAPIError} 唯一生产消费为
     * logAPIError 遥测（logging.ts:282 errorType 标签，调用点 claude.ts:2720/:2776），无控制流
     * 消费；Java 无 logAPIError 等价通道（slf4j 日志不承载 errorType 分类标签），本方法保留为
     * 库函数（行为对齐由 ApiErrorsCcTest 全集证明）。真接线落点 LlmAgentLoop:4152（backoff 告警
     * 登记 errorType 遥测）与 ER-IMP-2026-03/04 写集冲突，独立轮次实施。
     * @param error 待分类异常（null → "unknown"）
     * @return 分类字符串
     */
    public static String classifyApiError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage() != null ? error.getMessage() : "";
        String lower = message.toLowerCase();

        // Aborted requests · errors.ts:970-972（TS `instanceof Error` → Java 所有 Throwable 均匹配，仅按 message）
        if (message.equals("Request was aborted.")) {
            return "aborted";
        }
        // Timeout · errors.ts:976-982。CC 仅对 APIConnectionTimeoutError 或 APIConnectionError（连接层）
        //   且 message 含 timeout 判 api_timeout；APIError(status) 不判 api_timeout → 落 status≥500=server_error
        //   （LlmApiException(500,'request timeout')→server_error，grep 自验 errors.ts:977）。
        //   Java 无 APIConnectionError 类，以 SocketException 家族近似（含 SocketTimeoutException——
        //   APIConnectionTimeoutError 子类，且 message 含 timeout）。
        boolean connectionLayer =
            error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.SocketException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException;
        if (connectionLayer && (lower.contains("timed out") || lower.contains("timeout"))) {
            return "api_timeout";
        }
        // Repeated 529 · errors.ts:985-989（A4 合并：单常量来源本类 REPEATED_529_ERROR_MESSAGE）
        if (message.contains(REPEATED_529_ERROR_MESSAGE)) {
            return "repeated_529";
        }
        // Emergency capacity off switch · errors.ts:993-997
        if (message.contains(CUSTOM_OFF_SWITCH_MESSAGE)) {
            return "capacity_off_switch";
        }
        int status = -1;
        if (error instanceof LlmApiException ex) {
            status = ex.status();
        }
        // Rate limit 429 · errors.ts:1000-1004
        if (status == 429) {
            return "rate_limit";
        }
        // Server overload 529 · errors.ts:1007-1013
        if (status == 529 || message.contains("\"type\":\"overloaded_error\"")) {
            return "server_overload";
        }
        // Prompt too long · errors.ts:1015-1021
        if (lower.contains(PROMPT_TOO_LONG_ERROR_MESSAGE.toLowerCase())) {
            return "prompt_too_long";
        }
        // PDF too large · errors.ts:1024-1028（/maximum of \d+ PDF pages/）
        if (java.util.regex.Pattern.compile("maximum of \\d+ PDF pages").matcher(message).find()) {
            return "pdf_too_large";
        }
        // PDF password protected · errors.ts:1031-1035
        if (message.contains("The PDF specified is password protected")) {
            return "pdf_password_protected";
        }
        // Image size errors (400) · errors.ts:1038-1045（独立 AND：'image exceeds' && 'maximum'，grep 自验 1042）
        if (status == 400
            && message.contains("image exceeds")
            && message.contains("maximum")) {
            return "image_too_large";
        }
        // Many-image dimension errors (400) · errors.ts:1047-1055（独立 AND：'image dimensions exceed' && 'many-image'，
        //   grep 自验 1052 —— 拆回两条独立 AND，避免 OR-OR 合并造成交叉误判）
        if (status == 400
            && message.contains("image dimensions exceed")
            && message.contains("many-image")) {
            return "image_too_large";
        }
        // Tool use errors (400) · errors.ts:1060-1086
        if (status == 400
            && message.contains("`tool_use` ids were found without `tool_result` blocks immediately after")) {
            return "tool_use_mismatch";
        }
        if (status == 400 && message.contains("unexpected `tool_use_id` found in `tool_result`")) {
            return "unexpected_tool_result";
        }
        if (status == 400 && message.contains("`tool_use` ids must be unique")) {
            return "duplicate_tool_use_id";
        }
        // Invalid model (400) · errors.ts:1089-1094
        if (status == 400 && lower.contains("invalid model name")) {
            return "invalid_model";
        }
        // Credit balance low · errors.ts:1096-1102
        if (lower.contains(CREDIT_BALANCE_TOO_LOW_ERROR_MESSAGE.toLowerCase())) {
            return "credit_balance_low";
        }
        // Invalid API key · errors.ts:1104-1108（message 含 'x-api-key'）
        if (lower.contains("x-api-key")) {
            return "invalid_api_key";
        }
        // Token revoked (403) · errors.ts:1110-1114
        if (status == 403 && message.contains("OAuth token has been revoked")) {
            return "token_revoked";
        }
        // OAuth org not allowed (401/403) · errors.ts:1116-1121
        if ((status == 401 || status == 403)
            && message.contains("OAuth authentication is currently not allowed for this organization")) {
            return "oauth_org_not_allowed";
        }
        // Generic auth (401/403) · errors.ts:1124-1127
        if (status == 401 || status == 403) {
            return "auth_error";
        }
        // Bedrock-specific errors · errors.ts:1136-1143（isEnvTruthy(CLAUDE_CODE_USE_BEDROCK) && message 含 'model id'，
        //   grep 自验 1137/1141。env 未设 → 分支不触发，与 CC 默认一致）
        if (isEnvTruthy(System.getenv("CLAUDE_CODE_USE_BEDROCK")) && lower.contains("model id")) {
            return "bedrock_model_access";
        }
        // Status-code fallbacks · errors.ts:1144-1149（grep 自验 1147/1148）
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        // Connection errors · errors.ts:1151-1157（grep 自验 1154-1157）
        if (error instanceof java.net.SocketException
            || error instanceof java.net.ConnectException
            || error instanceof java.net.UnknownHostException) {
            // Java 无 APIConnectionError.isSSLError 结构 · SSL 经 javax.net.ssl.SSLException 判定
            if (error instanceof javax.net.ssl.SSLException) {
                return "ssl_cert_error";
            }
            return "connection_error";
        }
        if (message.toLowerCase().contains("ssl")) {
            return "ssl_cert_error";
        }
        return "unknown";
    }

    /** CC original: isEnvTruthy (utils/envUtils.ts:32) — 值 ∈ {1,true,yes,on} 为 true。 */
    private static boolean isEnvTruthy(String envVar) {
        if (envVar == null) return false;
        String normalized = envVar.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }
}
