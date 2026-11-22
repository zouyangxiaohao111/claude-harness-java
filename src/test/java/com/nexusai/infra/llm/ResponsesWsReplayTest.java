package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses WS 增量契约的纯逻辑测试（不联网）。
 *
 * <p><b>WHY</b>：{@code previous_response_id} 增量的前提是「下一轮从历史重建的 item 摘要 ==
 * 上一轮服务端 output 摘要」。这条不成立时不会报错，只会**永久退化为全量重发**（缓存悄悄失效）——
 * 正是用户报的「前缀缓存总被打断」。这里把等价性钉死在单测里。
 */
@DisplayName("[Responses] WS 签名回放与增量摘要")
class ResponsesWsReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SIG = "{\"kind\":\"responses_output\",\"items\":["
        + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc\",\"summary\":[\"s1\",\"s2\"]},"
        + "{\"type\":\"message\",\"id\":\"msg_1\",\"text\":\"hello\"},"
        + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"Bash\",\"arguments\":\"{}\"}"
        + "]}";

    @Test
    void replayItemsDigestEqualsSignatureDigest() {
        List<ResponseInputItem> replayed = ResponsesSdkProvider.replayFromSignature(SIG);
        assertThat(replayed).isNotNull().hasSize(3);

        List<String> fromItems = ResponsesSdkProvider.digestInput(replayed);
        List<String> fromSig = ResponsesSdkProvider.digestSignatureItems(SIG);

        assertThat(fromItems).isEqualTo(fromSig);
        assertThat(fromItems).contains("rs|rs_1|enc|s1s2");
        assertThat(fromItems).contains("om|msg_1|hello");
        assertThat(fromItems).contains("fc|fc_1|call_1|Bash|{}");
    }

    @Test
    @DisplayName("回放 item 形态对齐 codex：reasoning/function_call 不带 status，message 带 status+id")
    void replayItemsAlignCodexShape() {
        List<ResponseInputItem> replayed = ResponsesSdkProvider.replayFromSignature(SIG);
        assertThat(replayed).isNotNull();
        int messages = 0;
        for (ResponseInputItem it : replayed) {
            if (it.isReasoning()) {
                assertThat(it.asReasoning().status()).as("reasoning 不带 status").isEmpty();
            } else if (it.isFunctionCall()) {
                assertThat(it.asFunctionCall().status()).as("function_call 不带 status").isEmpty();
                assertThat(it.asFunctionCall().id()).as("fc id 保留").isPresent();
            } else if (it.isResponseOutputMessage()) {
                messages++;
                assertThat(it.asResponseOutputMessage().status())
                    .as("message 带 status=completed（codex 形态）")
                    .isEqualTo(ResponseOutputMessage.Status.COMPLETED);
                assertThat(it.asResponseOutputMessage().id()).as("message id 保留").isNotBlank();
            }
        }
        assertThat(messages).as("assistant 文本仍走 output message 形态").isEqualTo(1);
    }

    @Test
    @DisplayName("WS 请求带 client_metadata.session_id（codex ResponsesApiRequest 同形）")
    void wsRequestCarriesClientMetadata() {
        var req = ResponsesSdkProvider.buildWsRequest(
            "gpt-6.1-sol", "sys", List.of(), null, 128000, "sess-1", "high", null);
        assertThat(req._additionalProperties())
            .containsKey("client_metadata");
        assertThat(String.valueOf(req._additionalProperties().get("client_metadata")))
            .contains("session_id");
    }

    @Test
    @DisplayName("续接失效/连接上限判据对齐 codex 错误码")
    void recoverableWsReconnectCodes() {
        assertThat(ResponsesSdkProvider.isRecoverableWsReconnect("previous_response_not_found")).isTrue();
        assertThat(ResponsesSdkProvider.isRecoverableWsReconnect(
            "Previous response was not found. Retrying the full request.")).isTrue();
        assertThat(ResponsesSdkProvider.isRecoverableWsReconnect(
            "websocket_connection_limit_reached")).isTrue();
        assertThat(ResponsesSdkProvider.isRecoverableWsReconnect("rate limited")).isFalse();
        assertThat(ResponsesSdkProvider.isRecoverableWsReconnect(null)).isFalse();
    }

    @Test
    void replayIsDeterministic() {        assertThat(ResponsesSdkProvider.digestInput(
            ResponsesSdkProvider.replayFromSignature(SIG))).isEqualTo(
            ResponsesSdkProvider.digestInput(ResponsesSdkProvider.replayFromSignature(SIG)));
    }

    @Test
    void nonResponsesSignatureIsIgnored() {
        // Anthropic 的 thinking 块签名等非 responses 形状 → null（回落到旧 EasyInputMessage 逻辑）
        assertThat(ResponsesSdkProvider.replayFromSignature("{\"signature\":\"abc\"}")).isNull();
        assertThat(ResponsesSdkProvider.replayFromSignature(null)).isNull();
        assertThat(ResponsesSdkProvider.replayFromSignature("  ")).isNull();
        assertThat(ResponsesSdkProvider.replayFromSignature("not json")).isNull();
    }

    @Test
    void blankSignatureHasNoDigests() {
        assertThat(ResponsesSdkProvider.digestSignatureItems(null)).isEmpty();
        assertThat(ResponsesSdkProvider.digestSignatureItems("")).isEmpty();
    }

    @Test
    void effortCarriesSummaryAuto() throws Exception {
        // 末字段：对齐 pi（reasoning.summary = "auto"），否则上游不发 reasoning_summary_text.delta
        assertThat(JSON.writeValueAsString(ResponsesSdkProvider.reasoningOf("gpt-6.1-sol", "high")))
            .contains("\"effort\":\"high\"")
            .contains("\"summary\":\"auto\"");
        assertThat(ResponsesSdkProvider.reasoningOf("gpt-6.1-sol", "off")).isNull();
    }
}
