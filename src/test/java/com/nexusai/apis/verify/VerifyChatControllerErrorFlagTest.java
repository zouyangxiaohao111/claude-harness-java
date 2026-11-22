package com.nexusai.apis.verify;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [错误通道 C8 · CCJ-ERR-011/014] verify 端点的 tool_call isError 必须取执行层真值。
 *
 * <p><b>WHY</b>：CC 的 tool_result {@code is_error} 是执行层产出的块属性，不是从文案推导
 * （services/tools/toolExecution.ts 错误分支直构 {@code is_error:true}）。本仓 verify 端点
 * 曾按 {@code startsWith("Error") || contains("dangerous"|"not found"|"no such tool")} 猜测，
 * 双向都错：
 * <ul>
 *   <li>未登记文案（Edit 的 {@code "path is empty"}）判不出错误 → 失败被当成功；</li>
 *   <li>成功结果里客观含有 "not found" 等字样时 → 误判失败。</li>
 * </ul>
 * 现改为直接读 {@link ChatMessageDto#isError()}。
 */
@DisplayName("verify · tool_call isError 取执行层真值（不按文案猜）")
class VerifyChatControllerErrorFlagTest {

    private static ChatMessageDto assistantWithCalls(String id, ToolCallDto... calls) {
        return new ChatMessageDto(
            id, "sess-1", Role.assistant, "assistant",
            null, null, List.of(calls), null,
            null, null, null, null,
            null, null,
            null, null, List.of());
    }

    private static ChatMessageDto toolResult(String toolCallId, String content, boolean isError) {
        // 20 参兼容构造器：末两参 = (isMeta, isError)
        return new ChatMessageDto(
            "msg-tool-" + toolCallId, "sess-1", Role.tool, "tool",
            content, null, null, null,
            null, null, null, null,
            toolCallId, "asst-1",
            null, List.of(), List.of(), null,
            false, isError);
    }

    @Test
    @DisplayName("未登记错误文案（path is empty）+ isError=true → 记录为失败")
    void unregisteredErrorTextStillReportedAsError() {
        List<ChatMessageDto> messages = List.of(
            assistantWithCalls("asst-1", new ToolCallDto("t-edit", "Edit", "{}", null, null)),
            toolResult("t-edit", "path is empty", true));

        List<VerifyChatController.ToolCallRecord> records = VerifyChatController.collectToolCalls(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).isError()).isTrue();
        assertThat(records.get(0).result()).isEqualTo("path is empty");
    }

    @Test
    @DisplayName("成功内容含 not found + isError=false → 不得误判为失败")
    void successfulContentContainingNotFoundIsNotError() {
        List<ChatMessageDto> messages = List.of(
            assistantWithCalls("asst-1", new ToolCallDto("t-grep", "Grep", "{}", null, null)),
            toolResult("t-grep", "0 matches; pattern not found in 3 files", false));

        List<VerifyChatController.ToolCallRecord> records = VerifyChatController.collectToolCalls(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).isError()).isFalse();
    }

    @Test
    @DisplayName("结果按 toolCallId 回填到正确调用（不串位）")
    void resultsArePairedByToolCallId() {
        List<ChatMessageDto> messages = List.of(
            assistantWithCalls("asst-1",
                new ToolCallDto("t-a", "Edit", "{\"a\":1}", null, null),
                new ToolCallDto("t-b", "Write", "{\"b\":2}", null, null)),
            toolResult("t-b", "boom", true),
            toolResult("t-a", "ok", false));

        List<VerifyChatController.ToolCallRecord> records = VerifyChatController.collectToolCalls(messages);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).name()).isEqualTo("Edit");
        assertThat(records.get(0).result()).isEqualTo("ok");
        assertThat(records.get(0).isError()).isFalse();
        assertThat(records.get(1).name()).isEqualTo("Write");
        assertThat(records.get(1).result()).isEqualTo("boom");
        assertThat(records.get(1).isError()).isTrue();
    }
}
