package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态错误文案的**用户可读性**守门测试 · {@link ApiErrors#userFacingErrorMessage(Throwable)}。
 *
 * <p><b>WHY（用户实测）</b>：重试耗尽 / run 抛错后，前端错误卡正文直接是上游原始串
 * （{@code HTTP 550:}、{@code Request failed}）。用户原话：「不跟 CC 一样显示 额度不足 啊 啥的」。
 * {@link ApiErrors#classifyApiError} 的 25 类此前只喂遥测（该类 javadoc 自述），本方法把它接到展示面。
 *
 * <p><b>判据</b>（都必须是「中文结论 + 原始串补充」，而不是纯原串）：
 * <ul>
 *   <li>余额/额度类 → 必须出现「额度不足」（含 CC 原串、OpenAI 兼容网关的 insufficient_quota、402 三种形态）；</li>
 *   <li>限流 / 过载 / 鉴权 / 连接 → 各自的结论词；</li>
 *   <li>未识别类型 → 原样返回原始串（⛔ 不得编造原因）。</li>
 * </ul>
 */
class UserFacingErrorMessageTest {

    /** 造一个带 body 的 API 异常（body 是 classification 的实际输入面）。 */
    private static LlmApiException api(int status, String body) {
        return new LlmApiException(status, Map.of("retry-after", List.of("1")), body);
    }

    @Test
    @DisplayName("CC 原串 'Credit balance is too low' → 用户看到「额度不足」")
    void creditBalanceLow() {
        String msg = ApiErrors.userFacingErrorMessage(api(400, ApiErrors.CREDIT_BALANCE_TOO_LOW_ERROR_MESSAGE));

        assertThat(msg).contains("额度不足");
        assertThat(msg).contains("Credit balance is too low");   // 原始串保留作排查线索
    }

    @Test
    @DisplayName("OpenAI 兼容网关（DeepSeek 等）insufficient_quota → 同样译成「额度不足」")
    void insufficientQuota() {
        String msg = ApiErrors.userFacingErrorMessage(api(400,
            "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}"));

        assertThat(msg).contains("额度不足");
    }

    @Test
    @DisplayName("402（需要付费）→ 译成「额度不足」，不透出纯 HTTP 串")
    void paymentRequired() {
        String msg = ApiErrors.userFacingErrorMessage(api(402, ""));

        assertThat(msg).contains("额度不足");
        assertThat(msg).doesNotStartWith("HTTP 402");
    }

    @Test
    @DisplayName("429 → 限流；529 → 过载；401 → 鉴权；每种都是中文结论开头")
    void otherKnownClasses() {
        assertThat(ApiErrors.userFacingErrorMessage(api(429, "rate"))).contains("请求过于频繁");
        assertThat(ApiErrors.userFacingErrorMessage(api(529, "{\"type\":\"overloaded_error\"}"))).contains("过载");
        assertThat(ApiErrors.userFacingErrorMessage(api(401, "invalid x-api-key"))).contains("鉴权失败");
    }

    @Test
    @DisplayName("连接类 IOException → 「连不上模型服务」")
    void connectionError() {
        String msg = ApiErrors.userFacingErrorMessage(
            new java.net.ConnectException("Connection refused"));

        assertThat(msg).contains("连不上模型服务");
    }

    @Test
    @DisplayName("未识别类型 → 原样返回原始串（⛔ 不编造原因）")
    void unknownPassesThroughRaw() {
        String raw = "some completely unknown upstream failure";
        String msg = ApiErrors.userFacingErrorMessage(new RuntimeException(raw));

        assertThat(msg).isEqualTo(raw);
    }

    @Test
    @DisplayName("null 异常 → 通用可读文案（不得 NPE / 不得空串）")
    void nullSafe() {
        assertThat(ApiErrors.userFacingErrorMessage(null)).isNotBlank();
    }
}
