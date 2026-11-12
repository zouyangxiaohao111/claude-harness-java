package com.nexusai.infra.llm;

import com.anthropic.models.messages.MessageParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [thinking-passthrough 2026-11-12] anthropic 通道「thinking 模式必须回传 thinking 块」的 wire 形态回归。
 *
 * <p><b>现象</b>：ant 真端点偶发 400 ——
 * {@code The `content[].thinking` in the thinking mode must be passed back to the API.}
 *
 * <p><b>真机实测的触发条件</b>（DeepSeek /anthropic 端点，6 种形态对照）：
 * <ul>
 *   <li>不回 thinking + 上游<b>认识</b>该 tool_use id → 200（按 id 恢复成功）</li>
 *   <li>不回 thinking + 上游<b>不认识</b>该 id → 400（即上述原文）</li>
 *   <li>回任意 thinking 块（签名空/假/正文被改都行）→ 200（本端点不校验签名）</li>
 * </ul>
 *
 * <p><b>CC 对照</b>：CC 对同模型历史<b>原样透传</b> assistant content（含 thinking 块 + signature），
 * 换模型才剥（{@code [Thinking removed]} 占位），400 时还有「剥离后重试」兜底。
 *
 * <p><b>本测试锁定的断言面</b>：{@code buildSdkMessages} 的 assistant 分支必须在 text/tool_use
 * <b>之前</b>输出 thinking 块，且签名取消息上留存的 {@code thinkingSignature}；无 reasoning 的消息
 * 不得多出 thinking 块（出站字节与改动前一致）。
 */
@DisplayName("[thinking 回传] anthropic wire：assistant 历史的 thinking 块必须原样前置")
class AnthropicThinkingPassthroughWireTest {

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-think", Role.user, "user", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    /** assistant（可带 reasoning/signature/toolCalls）。 */
    private static ChatMessageDto assistant(String text, String reasoning, String signature,
                                            String... callIds) {
        List<ToolCallDto> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new ToolCallDto(id, "Edit", "{}", null, null));
        }
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-think", Role.assistant, "assistant", text,
            reasoning, calls.isEmpty() ? null : calls, null, null, null, null,
            OffsetDateTime.now(), null, null,
            null, null, null, null, false, false)
            .withThinkingSignature(signature);
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-think", Role.tool, "tool", content,
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, null,
            null, null, null, null, false, false);
    }

    @SuppressWarnings("unchecked")
    private static List<MessageParam> wire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return (List<MessageParam>) m.invoke(null, history);
    }

    private static String dump(List<MessageParam> msgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < msgs.size(); i++) {
            sb.append("[WIRE ").append(i).append("] role=").append(msgs.get(i).role())
                .append(" :: ").append(msgs.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static MessageParam findAssistantWith(List<MessageParam> msgs, String needle) {
        for (MessageParam p : msgs) {
            if (p.role() == MessageParam.Role.ASSISTANT && p.toString().contains(needle)) {
                return p;
            }
        }
        return null;
    }

    @Test
    @DisplayName("工具轮 assistant：thinking 块必须在 tool_use 之前，且带原签名")
    void toolUseAssistant_mustCarryThinkingBlockFirst() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("改一下这个文件。"),
            assistant("", "我需要先看看文件再改。", "sig-abc-123", "call_00_AAA"),
            toolResult("call_00_AAA", "File a updated"));

        List<MessageParam> msgs = wire(history);
        String d = dump(msgs);

        MessageParam asst = findAssistantWith(msgs, "call_00_AAA");
        assertThat(asst).as("wire 应含该 assistant 消息\n%s", d).isNotNull();
        String s = asst.toString();
        assertThat(s).as("assistant content 必须含 thinking 块（缺失 ⇒ 未知 tool_use id 时上游 400）\n%s", d)
            .contains("thinking");
        assertThat(s).as("thinking 正文必须原样回传\n%s", d).contains("我需要先看看文件再改。");
        assertThat(s).as("签名必须原样回传\n%s", d).contains("sig-abc-123");
        assertThat(s.indexOf("thinking")).as("thinking 块必须在 tool_use 之前（CC wire 顺序）\n%s", d)
            .isLessThan(s.indexOf("call_00_AAA"));
    }

    @Test
    @DisplayName("纯文本 assistant：同样回传 thinking 块")
    void plainTextAssistant_mustCarryThinkingBlock() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("先说结论。"),
            assistant("结论是这样。", "让我想想。", "sig-plain-1"),
            user("继续。"));

        List<MessageParam> msgs = wire(history);
        String d = dump(msgs);

        MessageParam asst = findAssistantWith(msgs, "结论是这样。");
        assertThat(asst).as("wire 应含该 assistant 消息\n%s", d).isNotNull();
        String s = asst.toString();
        assertThat(s).as("纯文本 assistant 也必须带 thinking 块\n%s", d).contains("让我想想。");
        assertThat(s).as("签名必须原样回传\n%s", d).contains("sig-plain-1");
        assertThat(s.indexOf("thinking")).as("thinking 在 text 之前\n%s", d)
            .isLessThan(s.indexOf("结论是这样。"));
    }

    @Test
    @DisplayName("无 reasoning 的 assistant：不得多出 thinking 块（出站字节零变化）")
    void assistantWithoutReasoning_mustNotAddThinkingBlock() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("你好"),
            assistant("你好，有什么可以帮你？", null, null),
            user("继续"));

        List<MessageParam> msgs = wire(history);
        String d = dump(msgs);
        assertThat(d).as("无 reasoning 的消息不得出现 thinking 块\n%s", d).doesNotContain("thinking");
    }

    @Test
    @DisplayName("缺签名（第三方端点未签发）：仍回传 thinking 块，签名按空串")
    void missingSignature_stillSendsThinkingBlock() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("改一下。"),
            assistant("", "先看文件。", null, "call_00_BBB"),
            toolResult("call_00_BBB", "ok"));

        List<MessageParam> msgs = wire(history);
        String d = dump(msgs);

        MessageParam asst = findAssistantWith(msgs, "call_00_BBB");
        assertThat(asst).as("wire 应含该 assistant 消息\n%s", d).isNotNull();
        assertThat(asst.toString()).as("thinking 正文仍必须在\n%s", d).contains("先看文件。");
    }

    @Test
    @DisplayName("响应侧：流式 signature_delta 必须捕获进 AssistantMessage.thinkingSignature")
    void streamSignatureDelta_capturedIntoAssistantMessage() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        state.signature.append("sig-from-delta");
        state.reasoning.append("思考正文");

        AssistantMessage msg = AnthropicSdkProvider.buildAssistantMessage(state);

        assertThat(msg.thinkingSignature())
            .as("signature_delta 必须落到 AssistantMessage.thinkingSignature")
            .isEqualTo("sig-from-delta");
        assertThat(msg.reasoning()).isEqualTo("思考正文");
    }

    @Test
    @DisplayName("响应侧：无 signature_delta 时 thinkingSignature 为 null（不造假）")
    void noSignatureDelta_leavesSignatureNull() {
        AnthropicSdkProvider.StreamState state = new AnthropicSdkProvider.StreamState();
        state.reasoning.append("只有思考，没有签名");

        AssistantMessage msg = AnthropicSdkProvider.buildAssistantMessage(state);

        assertThat(msg.thinkingSignature()).as("未捕获到签名时必须为 null").isNull();
    }
}
