package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.infra.llm.LlmApiException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErrorClassifier 分类链测试 · 对齐 CC withRetry.ts 分类函数全集。
 *
 * <p><b>WHY (意图验证)</b>: 分类器是 withRetry 循环的"该不该重试"单一裁决点——类型化状态码
 * 判定（is529Error）、前台/后台 529 甄别（shouldRetry529）、凭证自愈（OAuth 吊销/Bedrock/Vertex）、
 * shouldRetry 全集决策链任一偏差都会导致「capacity cascade 时后台 529 全量重试放大网关」或
 * 「凭证失效直接 FATAL 无法自愈」。断言锁定 CC withRetry.ts 每条决策分支。
 */
class ErrorClassifierTest {

    @AfterEach
    void restoreEnv() {
        ErrorClassifier.ENV_READER = System::getenv;
    }

    // ════════════════════════════════════════════════════════════════════
    // is529Error · CC withRetry.ts:610-621
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("is529Error: status=529 → true（CC:617）")
    void is529ErrorTrueOnStatus529() {
        assertThat(ErrorClassifier.is529Error(new LlmApiException(529, Map.of(), "overloaded"))).isTrue();
    }

    @Test
    @DisplayName("is529Error: 流式下 status 丢失但 message 含 overloaded_error → true（CC:619）")
    void is529ErrorTrueOnOverloadedMessage() {
        assertThat(ErrorClassifier.is529Error(new LlmApiException(500, Map.of(),
            "{\"type\":\"overloaded_error\"}"))).isTrue();
    }

    @Test
    @DisplayName("is529Error: 非 LlmApiException → false（类型闸，CC:611-613）")
    void is529ErrorFalseOnNonApiError() {
        assertThat(ErrorClassifier.is529Error(new RuntimeException("529"))).isFalse();
    }

    @Test
    @DisplayName("is529Error: 非 529 状态 → false（CC:617）")
    void is529ErrorFalseOnOtherStatus() {
        assertThat(ErrorClassifier.is529Error(new LlmApiException(503, Map.of(), "unavailable"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // shouldRetry529 前台/后台甄别 · CC withRetry.ts:84-89 + :62-82
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("shouldRetry529: undefined → true（保守，CC:87）")
    void shouldRetry529UndefinedIsTrue() {
        assertThat(ErrorClassifier.shouldRetry529((QuerySource) null)).isTrue();
        assertThat(ErrorClassifier.shouldRetry529((String) null)).isTrue();
    }

    @Test
    @DisplayName("shouldRetry529: 前台来源（repl_main_thread/sdk/compact/hook_agent/user）→ true（CC:62-82）")
    void shouldRetry529ForegroundSources() {
        for (QuerySource qs : new QuerySource[]{
            QuerySource.REPL_MAIN_THREAD, QuerySource.SDK, QuerySource.COMPACT,
            QuerySource.HOOK_AGENT, QuerySource.USER}) {
            assertThat(ErrorClassifier.shouldRetry529(qs))
                .as("前台来源 %s 应重试 529", qs).isTrue();
        }
    }

    @Test
    @DisplayName("shouldRetry529: 后台来源（session_memory/extract_memories/auto_dream/subagent/fork）→ false（CC:57-61 capacity cascade 放大）")
    void shouldRetry529BackgroundSources() {
        for (QuerySource qs : new QuerySource[]{
            QuerySource.SESSION_MEMORY, QuerySource.EXTRACT_MEMORIES, QuerySource.AUTO_DREAM,
            QuerySource.SUBAGENT, QuerySource.FORK}) {
            assertThat(ErrorClassifier.shouldRetry529(qs))
                .as("后台来源 %s 不应重试 529", qs).isFalse();
        }
    }

    @Test
    @DisplayName("shouldRetry529(String): 未知/后台来源 canonical 串 → false（CC:62-82 集合外不重试）")
    void shouldRetry529UnknownStringIsFalse() {
        assertThat(ErrorClassifier.shouldRetry529("side_question")).isFalse();
        // [ER-IMP-2026-02] SUBAGENT/FORK canonical 串（agent:subagent / agent:builtin:fork）
        // 已移出前台集合 → false；CC 生产 subagent 动态串 agent:builtin:<type> 集合外 → false
        assertThat(ErrorClassifier.shouldRetry529("agent:subagent")).isFalse();
        assertThat(ErrorClassifier.shouldRetry529("agent:builtin:fork")).isFalse();
        assertThat(ErrorClassifier.shouldRetry529("agent:builtin:general")).isFalse();
        // [IMP2-05 精确化后] 运行时 agentType 级精确值（promptCategory.ts:16-28 →
        //   AgentTool.tsx:609 的 agent:builtin:<type>/agent:custom/agent:default）是 fromString
        //   未知名（枚举映射外）→ 后台来源不重试 529（CC withRetry.ts:62-82 "New sources default
        //   to no-retry"；capacity cascade 时精确化不引入后台 529 放大）。
        assertThat(ErrorClassifier.shouldRetry529("agent:builtin:Explore")).isFalse();
        assertThat(ErrorClassifier.shouldRetry529("agent:custom")).isFalse();
        assertThat(ErrorClassifier.shouldRetry529("agent:default")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // isTransientCapacityError · CC withRetry.ts:106-110
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isTransientCapacityError: 仅 529/429 两类（CC:106-110）")
    void isTransientCapacityErrorOnly529And429() {
        assertThat(ErrorClassifier.isTransientCapacityError(new LlmApiException(529, Map.of(), "x"))).isTrue();
        assertThat(ErrorClassifier.isTransientCapacityError(new LlmApiException(429, Map.of(), "x"))).isTrue();
        assertThat(ErrorClassifier.isTransientCapacityError(new LlmApiException(503, Map.of(), "x"))).isFalse();
        assertThat(ErrorClassifier.isTransientCapacityError(new LlmApiException(408, Map.of(), "x"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // isStaleConnectionError · CC withRetry.ts:112-118
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isStaleConnectionError: ECONNRESET/Connection reset → true（CC:117）")
    void isStaleConnectionErrorTrueOnReset() {
        Throwable e = new RuntimeException("api call failed",
            new IOException("Connection reset by peer"));
        assertThat(ErrorClassifier.isStaleConnectionError(e)).isTrue();
    }

    @Test
    @DisplayName("isStaleConnectionError: EPIPE/Broken pipe → true（CC:117）")
    void isStaleConnectionErrorTrueOnEpipe() {
        Throwable e = new RuntimeException("write failed",
            new IOException("Broken pipe"));
        assertThat(ErrorClassifier.isStaleConnectionError(e)).isTrue();
    }

    @Test
    @DisplayName("isStaleConnectionError: 非连接错误（HTTP 429）→ false（类型闸，CC:113-115）")
    void isStaleConnectionErrorFalseOnHttpError() {
        assertThat(ErrorClassifier.isStaleConnectionError(new LlmApiException(429, Map.of(), "rate"))).isFalse();
    }

    @Test
    @DisplayName("isStaleConnectionError: 非 IOException 包装（message 含 Connection reset）→ false（ER-IMP-2026-02 类型闸收紧，CC:113-115）")
    void isStaleConnectionErrorFalseOnPlainRuntimeMessage() {
        Throwable e = new RuntimeException("Connection reset by peer");
        assertThat(ErrorClassifier.isStaleConnectionError(e)).isFalse();
    }

    @Test
    @DisplayName("isConnectionError: LlmApiException 400 body 含 request timed out → false（ER-IMP-2026-02 收紧：仅 IOException 类型闸，无消息子串）")
    void isConnectionErrorFalseOnHttpBodyTimeoutText() {
        assertThat(ErrorClassifier.isConnectionError(
            new LlmApiException(400, Map.of(), "request timed out"))).isFalse();
    }

    @Test
    @DisplayName("isConnectionError: IOException 包装（含 cause 链）→ true（CC:753-755 APIConnectionError 类型闸等价）")
    void isConnectionErrorTrueOnIOException() {
        assertThat(ErrorClassifier.isConnectionError(
            new RuntimeException("boom", new IOException("Connection refused")))).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 凭证自愈 · CC withRetry.ts:623-694
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isOAuthTokenRevokedError: 403 + 'OAuth token has been revoked' → true（CC:623-629）")
    void isOAuthTokenRevokedErrorTrue() {
        assertThat(ErrorClassifier.isOAuthTokenRevokedError(new LlmApiException(403, Map.of(),
            "OAuth token has been revoked"))).isTrue();
        assertThat(ErrorClassifier.isOAuthTokenRevokedError(new LlmApiException(403, Map.of(), "other"))).isFalse();
        assertThat(ErrorClassifier.isOAuthTokenRevokedError(new LlmApiException(401, Map.of(),
            "OAuth token has been revoked"))).isFalse();
    }

    @Test
    @DisplayName("isBedrockAuthError: env 关 → false；env 开 + 403 → true（CC:631-644）")
    void isBedrockAuthErrorEnvGated() {
        ErrorClassifier.ENV_READER = name -> "CLAUDE_CODE_USE_BEDROCK".equals(name) ? "false" : null;
        assertThat(ErrorClassifier.isBedrockAuthError(new LlmApiException(403, Map.of(), "The security token included in the request is invalid"))).isFalse();

        ErrorClassifier.ENV_READER = name -> "CLAUDE_CODE_USE_BEDROCK".equals(name) ? "true" : null;
        assertThat(ErrorClassifier.isBedrockAuthError(new LlmApiException(403, Map.of(), "The security token included in the request is invalid"))).isTrue();
    }

    @Test
    @DisplayName("isVertexAuthError: env 开 + invalid_grant → true（CC:670-682）")
    void isVertexAuthErrorEnvGated() {
        ErrorClassifier.ENV_READER = name -> "CLAUDE_CODE_USE_VERTEX".equals(name) ? "true" : null;
        assertThat(ErrorClassifier.isVertexAuthError(new RuntimeException("invalid_grant"))).isTrue();
        assertThat(ErrorClassifier.isVertexAuthError(new LlmApiException(401, Map.of(), "Unauthorized"))).isTrue();
        assertThat(ErrorClassifier.isVertexAuthError(new LlmApiException(500, Map.of(), "boom"))).isFalse();
    }

    @Test
    @DisplayName("handleAwsCredentialError/handleGcpCredentialError: 命中返回 true（可重试）")
    void handleCredentialErrorsReturnTrueOnHit() {
        ErrorClassifier.ENV_READER = name -> switch (name) {
            case "CLAUDE_CODE_USE_BEDROCK" -> "true";
            case "CLAUDE_CODE_USE_VERTEX" -> "true";
            default -> null;
        };
        assertThat(ErrorClassifier.handleAwsCredentialError(
            new LlmApiException(403, Map.of(), "The security token included in the request is invalid"))).isTrue();
        assertThat(ErrorClassifier.handleGcpCredentialError(new RuntimeException("invalid_grant"))).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // shouldRetry 全集 · CC withRetry.ts:696-787
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("shouldRetry: 408/409 → true（CC:760-764）")
    void shouldRetryTimeoutAndLock() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(408, Map.of(), "timeout"))).isTrue();
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(409, Map.of(), "conflict"))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: 5xx 全集（含 501/505-599）→ true（CC:784）")
    void shouldRetryAll5xx() {
        for (int status : new int[]{500, 501, 502, 503, 504, 505, 599}) {
            assertThat(ErrorClassifier.shouldRetry(new LlmApiException(status, Map.of(), "server error")))
                .as("status %d 应可重试", status).isTrue();
        }
    }

    @Test
    @DisplayName("shouldRetry: 401 → clearApiKeyHelperCache + false（BUG1-401：认证失败直接失败，不按退避重试）")
    void shouldRetry401IsFalse() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(401, Map.of(), "token expired"))).isFalse();
    }

    @Test
    @DisplayName("shouldRetry: 429 → 非订阅 true（CC:767-769；Java 无订阅层 N/A → 非订阅）")
    void shouldRetry429IsTrueForNonSubscriber() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(429, Map.of(), "rate limited"))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: OAuth revoked 403 → true（CC:779-781）")
    void shouldRetryRevoked403IsTrue() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(403, Map.of(),
            "OAuth token has been revoked"))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: 400（非 overflow）→ false（CC:757-785 无命中）")
    void shouldRetry400IsFalse() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(400, Map.of(), "bad request"))).isFalse();
        // [ER-IMP-2026-02] 收紧后 4xx body 含 timeout 文本的非 IOException 不再判连接错误 → false
        assertThat(ErrorClassifier.shouldRetry(
            new LlmApiException(400, Map.of(), "request timed out"))).isFalse();
    }

    @Test
    @DisplayName("shouldRetry: x-should-retry:true → true（CC:737-742；非订阅）")
    void shouldRetryXShouldRetryTrue() {
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(400, Map.of("x-should-retry", List.of("true")), "bad"))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: x-should-retry:false → false（CC:746-751，非 ant）")
    void shouldRetryXShouldRetryFalse() {
        ErrorClassifier.ENV_READER = name -> null;
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(500, Map.of("x-should-retry", List.of("false")), "boom"))).isFalse();
    }

    @Test
    @DisplayName("shouldRetry: 连接错误 → true（CC:753-755 APIConnectionError）")
    void shouldRetryConnectionErrorIsTrue() {
        assertThat(ErrorClassifier.shouldRetry(new RuntimeException("boom",
            new IOException("Connection refused")))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: CCR 模式 401 → true（CC:712-717）")
    void shouldRetryCcr401IsTrue() {
        ErrorClassifier.ENV_READER = name -> "CLAUDE_CODE_REMOTE".equals(name) ? "true" : null;
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(401, Map.of(), "jwt blip"))).isTrue();
    }

    @Test
    @DisplayName("shouldRetry: persistent 模式 429/529 → true（CC:704-706）")
    void shouldRetryPersistentTransientIsTrue() {
        ErrorClassifier.ENV_READER = name -> "NEXUSAI_UNATTENDED_RETRY".equals(name) ? "true" : null;
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(429, Map.of(), "rate"))).isTrue();
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(529, Map.of(), "overloaded"))).isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // max_tokens 上下文溢出 · CC withRetry.ts:550-595（X-37，联动 ER-IMP-08）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseMaxTokensContextOverflowError: 400 + 溢出消息 → record（CC:569-594）")
    void parseMaxTokensContextOverflowError() {
        MaxTokensOverflowError r = ErrorClassifier.parseMaxTokensContextOverflowError(
            new LlmApiException(400, Map.of(),
                "input length and `max_tokens` exceed context limit: 188059 + 20000 > 200000"));
        assertThat(r).isNotNull();
        assertThat(r.inputTokens()).isEqualTo(188059);
        assertThat(r.maxTokens()).isEqualTo(20000);
        assertThat(r.contextLimit()).isEqualTo(200000);
        // 溢出错误应可重试（CC:727-729）
        assertThat(ErrorClassifier.shouldRetry(new LlmApiException(400, Map.of(),
            "input length and `max_tokens` exceed context limit: 100 + 50 > 200"))).isTrue();
    }

    @Test
    @DisplayName("parseMaxTokensContextOverflowError: 非 400 或消息不匹配 → null（CC:557-567）")
    void parseMaxTokensContextOverflowErrorReturnsNullOnMismatch() {
        assertThat(ErrorClassifier.parseMaxTokensContextOverflowError(
            new LlmApiException(500, Map.of(), "input length and `max_tokens` exceed context limit: 1 + 2 > 3"))).isNull();
        assertThat(ErrorClassifier.parseMaxTokensContextOverflowError(
            new LlmApiException(400, Map.of(), "other message"))).isNull();
        assertThat(ErrorClassifier.parseMaxTokensContextOverflowError(new RuntimeException("boom"))).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // extractRetryAfterSeconds · CC withRetry.ts:519-528（只读 header）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractRetryAfterSeconds: 只读 Retry-After header，消息正则不回退（DC-04/05）")
    void extractRetryAfterSecondsReadsHeaderOnly() {
        assertThat(ErrorClassifier.extractRetryAfterSeconds(
            new LlmApiException(429, Map.of("retry-after", List.of("120")), "rate"))).isEqualTo(120L);
        // 消息含 retry-after 但 header 缺失 → null（CC 只读 header，不解析消息文本）
        assertThat(ErrorClassifier.extractRetryAfterSeconds(
            new LlmApiException(429, Map.of(), "retry-after=120"))).isNull();
    }
}
