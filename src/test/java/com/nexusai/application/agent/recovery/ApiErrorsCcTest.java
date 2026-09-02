package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiErrors CC 对齐定向测试 · errors.ts classifyAPIError(965-1161) / categorizeRetryableAPIError(1163-1181) /
 * startsWithApiErrorPrefix(55-61) / NO_CONTENT_MESSAGE(constants/messages.ts:1)。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>:
 * <ol>
 *   <li><b>26 类分类全集</b> — CC classifyAPIError 是 analytics 的标准错误类型；删旧 ErrorCategory/
 *       classifyError（DC-08）后必须证明新 classifyApiError 行为对齐 CC，否则重试/遥测分类失真。</li>
 *   <li><b>categorizeRetryableAPIError 载荷</b> — QueryEngine.ts:952 的 api_retry 事件 error 字段用其
 *       分类；错分类会让前端「重试中」展示错误类型。</li>
 *   <li><b>前缀文案修正</b> — §5G 实证 Java 旧 {@code '/login · '} 与 CC {@code 'Please run /login · '} 不符；
 *       compact 四文件消费 startsWithApiErrorPrefix，文案错会导致 PTL 摘要漏检。</li>
 * </ol>
 */
class ApiErrorsCcTest {

    private LlmApiException api(int status, String body) {
        return new LlmApiException(status, Collections.emptyMap(), body);
    }

    // ─────────── classifyApiError（CC errors.ts:965-1161）───────────

    @Test
    @DisplayName("aborted：Request was aborted.")
    void aborted() {
        assertThat(ApiErrors.classifyApiError(new RuntimeException("Request was aborted.")))
            .isEqualTo("aborted");
    }

    @Test
    @DisplayName("api_timeout：仅连接层异常（errors.ts:976-982）")
    void apiTimeout() {
        // CC: APIConnectionTimeoutError（Java 以 SocketTimeoutException 近似）→ api_timeout
        assertThat(ApiErrors.classifyApiError(new java.net.SocketTimeoutException("connect timed out")))
            .isEqualTo("api_timeout");
        // CC: APIConnectionError && message 含 timeout（Java 以 SocketException/ConnectException/UnknownHostException 近似）
        assertThat(ApiErrors.classifyApiError(new java.net.SocketException("connection timeout")))
            .isEqualTo("api_timeout");
        // LlmApiException 非连接层异常：status=500 → server_error（errors.ts:977 APIError(status) 不判 api_timeout）
        assertThat(ApiErrors.classifyApiError(api(500, "request timeout"))).isEqualTo("server_error");
        // LlmApiException(0,'connection timeout')：CC=unknown（APIError 非 APIConnectionError → 落 unknown）
        assertThat(ApiErrors.classifyApiError(api(0, "connection timeout"))).isEqualTo("unknown");
    }

    @Test
    @DisplayName("repeated_529 / capacity_off_switch / server_overload")
    void repeatedAndCapacity() {
        assertThat(ApiErrors.classifyApiError(new RuntimeException(ApiErrors.REPEATED_529_ERROR_MESSAGE)))
            .isEqualTo("repeated_529");
        assertThat(ApiErrors.classifyApiError(new RuntimeException(ApiErrors.CUSTOM_OFF_SWITCH_MESSAGE)))
            .isEqualTo("capacity_off_switch");
        assertThat(ApiErrors.classifyApiError(api(529, "overloaded")))
            .isEqualTo("server_overload");
        assertThat(ApiErrors.classifyApiError(api(200, "{\"type\":\"overloaded_error\"}")))
            .isEqualTo("server_overload");
    }

    @Test
    @DisplayName("rate_limit(429) / prompt_too_long / credit_balance_low")
    void rateAndPrompt() {
        assertThat(ApiErrors.classifyApiError(api(429, "rate limit"))).isEqualTo("rate_limit");
        assertThat(ApiErrors.classifyApiError(api(400, "Prompt is too long")))
            .isEqualTo("prompt_too_long");
        assertThat(ApiErrors.classifyApiError(new RuntimeException("Credit balance is too low")))
            .isEqualTo("credit_balance_low");
    }

    @Test
    @DisplayName("pdf_too_large / pdf_password_protected / image_too_large")
    void pdfAndImage() {
        assertThat(ApiErrors.classifyApiError(new RuntimeException("maximum of 3 PDF pages")))
            .isEqualTo("pdf_too_large");
        assertThat(ApiErrors.classifyApiError(new RuntimeException("The PDF specified is password protected")))
            .isEqualTo("pdf_password_protected");
        // 两条独立 AND（errors.ts:1038-1045 / :1047-1055，grep 自验 1042/1052）：
        //   'image exceeds'&&'maximum' 或 'image dimensions exceed'&&'many-image'
        assertThat(ApiErrors.classifyApiError(api(400, "image exceeds maximum")))
            .isEqualTo("image_too_large");
        assertThat(ApiErrors.classifyApiError(api(400, "image dimensions exceed many-image limit")))
            .isEqualTo("image_too_large");
        // [ER-IMP-11 修正] 交叉误判：'image exceeds' 配 'many-image'（无 'maximum'）不命中任一独立 AND
        //   → 落 400 client_error（OR-OR 合并旧实现会误判 image_too_large）
        assertThat(ApiErrors.classifyApiError(api(400, "image exceeds many-image limit")))
            .isEqualTo("client_error");
        assertThat(ApiErrors.classifyApiError(api(400, "image dimensions exceed maximum")))
            .isEqualTo("client_error");
    }

    @Test
    @DisplayName("bedrock_model_access：env 门控（errors.ts:1136-1143）")
    void bedrockModelAccessEnvGated() {
        // CLAUDE_CODE_USE_BEDROCK 默认未设（isEnvTruthy=false）→ 'model id' 不触发 bedrock_model_access
        //   → 落 400 client_error（CC 默认行为与 Java 一致，grep 自验 errors.ts:1137/1141）
        assertThat(ApiErrors.classifyApiError(api(400, "model id: anthropic.claude-v2:1 not found")))
            .isEqualTo("client_error");
        assertThat(ApiErrors.classifyApiError(api(500, "Bedrock model id unavailable")))
            .isEqualTo("server_error");
    }

    @Test
    @DisplayName("tool_use_mismatch / unexpected_tool_result / duplicate_tool_use_id")
    void toolUseErrors() {
        assertThat(ApiErrors.classifyApiError(api(400,
            "`tool_use` ids were found without `tool_result` blocks immediately after")))
            .isEqualTo("tool_use_mismatch");
        assertThat(ApiErrors.classifyApiError(api(400, "unexpected `tool_use_id` found in `tool_result`")))
            .isEqualTo("unexpected_tool_result");
        assertThat(ApiErrors.classifyApiError(api(400, "`tool_use` ids must be unique")))
            .isEqualTo("duplicate_tool_use_id");
    }

    @Test
    @DisplayName("invalid_model / invalid_api_key / token_revoked / oauth_org_not_allowed / auth_error")
    void authAndModel() {
        assertThat(ApiErrors.classifyApiError(api(400, "Invalid model name: foo")))
            .isEqualTo("invalid_model");
        assertThat(ApiErrors.classifyApiError(new RuntimeException("x-api-key invalid")))
            .isEqualTo("invalid_api_key");
        assertThat(ApiErrors.classifyApiError(api(403, "OAuth token has been revoked")))
            .isEqualTo("token_revoked");
        assertThat(ApiErrors.classifyApiError(api(401,
            "OAuth authentication is currently not allowed for this organization")))
            .isEqualTo("oauth_org_not_allowed");
        assertThat(ApiErrors.classifyApiError(api(403, "forbidden"))).isEqualTo("auth_error");
    }

    @Test
    @DisplayName("server_error(≥500) / client_error(4xx 兜底) / ssl_cert_error / unknown")
    void statusFallbackAndUnknown() {
        assertThat(ApiErrors.classifyApiError(api(500, "internal"))).isEqualTo("server_error");
        assertThat(ApiErrors.classifyApiError(api(418, "teapot"))).isEqualTo("client_error");
        assertThat(ApiErrors.classifyApiError(new RuntimeException("ssl certificate error")))
            .isEqualTo("ssl_cert_error");
        assertThat(ApiErrors.classifyApiError(new RuntimeException("random"))).isEqualTo("unknown");
        assertThat(ApiErrors.classifyApiError(null)).isEqualTo("unknown");
    }

    // ─────────── categorizeRetryableApiError（CC errors.ts:1163-1181）───────────

    @Test
    @DisplayName("categorizeRetryableApiError：529/429→rate_limit，401/403→authentication_failed，≥408→server_error，else unknown")
    void categorizeRetryable() {
        assertThat(ApiErrors.categorizeRetryableApiError(api(529, "overloaded"))).isEqualTo("rate_limit");
        assertThat(ApiErrors.categorizeRetryableApiError(api(200, "{\"type\":\"overloaded_error\"}")))
            .isEqualTo("rate_limit");
        assertThat(ApiErrors.categorizeRetryableApiError(api(429, "rate"))).isEqualTo("rate_limit");
        assertThat(ApiErrors.categorizeRetryableApiError(api(401, "unauthorized"))).isEqualTo("authentication_failed");
        assertThat(ApiErrors.categorizeRetryableApiError(api(408, "timeout"))).isEqualTo("server_error");
        assertThat(ApiErrors.categorizeRetryableApiError(api(200, "ok"))).isEqualTo("unknown");
        assertThat(ApiErrors.categorizeRetryableApiError(new RuntimeException("plain"))).isEqualTo("unknown");
    }

    // ─────────── startsWithApiErrorPrefix（CC errors.ts:55-61 前缀文案修正）───────────

    @Test
    @DisplayName("startsWithApiErrorPrefix：'Please run /login · API Error' 前缀（§5G 实证修正），旧 '/login · ' 前缀不再命中")
    void startsWithPrefix() {
        assertThat(ApiErrors.startsWithApiErrorPrefix("API Error: 429 (rate limit)")).isTrue();
        assertThat(ApiErrors.startsWithApiErrorPrefix("Please run /login · API Error: 401 (token expired)")).isTrue();
        assertThat(ApiErrors.startsWithApiErrorPrefix("/login · API Error: 401")).isFalse();
        assertThat(ApiErrors.startsWithApiErrorPrefix(null)).isFalse();
        assertThat(ApiErrors.startsWithApiErrorPrefix("normal text")).isFalse();
    }

    @Test
    @DisplayName("NO_CONTENT_MESSAGE 对齐 CC constants/messages.ts:1")
    void noContentMessage() {
        assertThat(ApiErrors.NO_CONTENT_MESSAGE).isEqualTo("(no content)");
    }

    @Test
    @DisplayName("PROMPT_TOO_LONG_ERROR_MESSAGE 保留常量（compact 消费）；REPEATED_529 单一来源在 ApiErrors（A4 合并）")
    void preservedConstants() {
        assertThat(ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE).isEqualTo("Prompt is too long");
        assertThat(ApiErrors.REPEATED_529_ERROR_MESSAGE).isEqualTo("Repeated 529 Overloaded errors");
    }
}
