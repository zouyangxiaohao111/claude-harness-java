package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.node.TextNode;
import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401 自愈判据（C2e-2）：{@link ResponsesSdkProvider#isAuthError(Throwable)} 与可读错误文案。
 *
 * <p>判定决定「要不要强制刷新 OAuth token 并重试一次」：
 * <ul>
 *   <li>401 一律算（本地 expires_at 说没过期也照样算 —— 时钟漂移 / 多实例并发刷新）</li>
 *   <li>403 只认 body 明说 token 被撤销（其余 403 是权限问题，重登无用）</li>
 *   <li>真实链路里异常可能被包了几层，必须沿 cause 链找</li>
 * </ul>
 */
@DisplayName("[Responses] 401 自愈判据")
class ResponsesAuthRetryDecisionTest {

    private static LlmApiException api(int status, String body) {
        return new LlmApiException(status, Map.of(), body);
    }

    /**
     * SDK 原生异常（{@code doStream} 翻译前就是这个类型）：{@link OpenAIServiceException}
     * 是 Kotlin abstract class，测试用子类给出状态码与 body。
     */
    private static final class FakeServiceException extends OpenAIServiceException {
        private final int status;
        private final JsonValue body;

        FakeServiceException(int status, String body) {
            super("fake service error status=" + status, null);
            this.status = status;
            this.body = JsonValue.fromJsonNode(TextNode.valueOf(body == null ? "" : body));
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Headers headers() {
            return Headers.builder().build();
        }

        @Override
        public JsonValue body() {
            return body;
        }

        @Override
        public Optional<String> code() {
            return Optional.empty();
        }

        @Override
        public Optional<String> param() {
            return Optional.empty();
        }

        @Override
        public Optional<String> type() {
            return Optional.empty();
        }
    }

    @Test
    void nullIsNotAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(null)).isFalse();
    }

    @Test
    void unauthorizedLlmApiExceptionIsAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(api(401, "unauthorized"))).isTrue();
    }

    @Test
    void forbiddenWithRevokedTokenIsAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(
            api(403, "{\"error\":{\"message\":\"token has been revoked\"}}"))).isTrue();
    }

    @Test
    void forbiddenWithoutRevokedTokenIsNotAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(
            api(403, "insufficient permissions"))).isFalse();
    }

    @Test
    void otherStatusesAreNotAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(api(400, "bad request"))).isFalse();
        assertThat(ResponsesSdkProvider.isAuthError(api(500, "internal error"))).isFalse();
    }

    @Test
    void sdkServiceException401IsAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(
            new FakeServiceException(401, "unauthorized"))).isTrue();
    }

    @Test
    void sdkServiceException403RevokedIsAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(
            new FakeServiceException(403, "token has been revoked"))).isTrue();
    }

    @Test
    void sdkServiceException403OtherIsNotAuthError() {
        assertThat(ResponsesSdkProvider.isAuthError(
            new FakeServiceException(403, "forbidden"))).isFalse();
    }

    @Test
    void authErrorInsideCauseChainIsDetected() {
        Throwable wrapped = new RuntimeException("outer",
            new IllegalStateException("middle", api(401, "expired")));
        assertThat(ResponsesSdkProvider.isAuthError(wrapped)).isTrue();
    }

    @Test
    void hintKeepsStatusAndAppendsReloginCopy() {
        RuntimeException hinted = ResponsesSdkProvider.withReloginHint(api(401, "token expired"));
        assertThat(hinted).isInstanceOf(LlmApiException.class);
        assertThat(((LlmApiException) hinted).status()).isEqualTo(401);
        assertThat(hinted.getMessage()).contains("登录已失效");
    }
}
