package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.anthropic.models.messages.MessageParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [专项 · fork 部分配对 400 · 2026-10-10] anthropic 通道「一轮多 tool_use」的 wire 形态回归。
 *
 * <p><b>WHY（现象）</b>：ant 真端点 e2e 中，SESSION_MEMORY fork 规律性地一轮发 3 个 Edit（calls=3，
 * 3 results 全执行），随后 turn=2 稳定 400：
 * {@code messages.8: `tool_use` ids were found without `tool_result` blocks immediately after:
 * call_01_...}（DeepSeek ant 端点对「immediately after」严格检查）。
 *
 * <p><b>根因假设（本测试锁定的断言面）</b>：{@code AnthropicSdkProvider.buildSdkMessages} 对
 * <b>每一条</b> Role.tool 消息各起一个 {@code MessageParam(role=USER)}（无合并）⇒ 一轮 N 个
 * tool_use 的 N 条结果被拆成 N 条<b>连续 user 消息</b>；服务端在 assistant 的「下一条」里只看到
 * 第 1 个 result ⇒ 报其余 id「without tool_result immediately after」。
 * CC 的 wire 形态是「N 个 tool_result 合并进<b>同一个</b> user 消息的 content 数组」
 * （ToolResultPairingRepair 头注 47 行亦记载此映射差异）；openai 通道因协议本身「每条 tool 一消息」
 * 不受影响，主链因「从未出现一轮多 tool_use」未暴露——仅 fork（Edit×N 批量改笔记）稳定触发。
 *
 * <p><b>测试意图（规则九）</b>：不做「现状快照」，直接断言 <b>CC 形态</b>（紧随 assistant 的
 * 单条 user 必须含全部 id）。<b>本测试在修复前应为红</b>——红即该 400 的最小复现；
 * 修复（合并连续 tool 结果）后转绿即为回归防线。
 */
@DisplayName("[专项] anthropic 多工具轮 wire 形态：N 个 tool_result 必须合并在紧随的一条 user 里")
class AnthropicMultiToolWireTest {

    private static ChatMessageDto user(String text) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-wire", Role.user, "user", text,
            null, null, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto assistant(String text, String... callIds) {
        List<ToolCallDto> calls = new java.util.ArrayList<>();
        for (String id : callIds) {
            calls.add(new ToolCallDto(id, "Edit", "{}", null, null));
        }
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-wire", Role.assistant, "assistant", text,
            null, calls, null, null, null, null, OffsetDateTime.now(), null, null,
            null, null, null, null, false, false);
    }

    private static ChatMessageDto toolResult(String toolCallId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), "sess-wire", Role.tool, "tool", content,
            null, null, null, null, null, null, OffsetDateTime.now(), toolCallId, null,
            null, null, null, null, false, false);
    }

    @SuppressWarnings("unchecked")
    private static List<MessageParam> wire(List<ChatMessageDto> history) throws Exception {
        Method m = AnthropicSdkProvider.class.getDeclaredMethod("buildSdkMessages", List.class);
        m.setAccessible(true);
        return (List<MessageParam>) m.invoke(null, history);
    }

    /** wire 全貌（供断言失败信息/诊断）。 */
    private static String dump(List<MessageParam> msgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < msgs.size(); i++) {
            sb.append("[WIRE ").append(i).append("] role=").append(msgs.get(i).role())
              .append(" :: ").append(msgs.get(i)).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("fork 场景复刻：assistant(3 tool_use) + 3 tool_result → 紧随的一条 user 必须含全部 3 个 id")
    void multiToolResults_mustMergeIntoOneUserMessage_immediatelyAfter() throws Exception {
        List<ChatMessageDto> history = List.of(
            user("请先改三个文件再总结。"),
            assistant("", "call_00_AAA", "call_01_BBB", "call_02_CCC"),
            toolResult("call_00_AAA", "File a updated"),
            toolResult("call_01_BBB", "File b updated"),
            toolResult("call_02_CCC", "File c updated"));

        List<MessageParam> msgs = wire(history);
        String dumpStr = dump(msgs);

        int aIdx = -1;
        for (int i = 0; i < msgs.size(); i++) {
            String s = msgs.get(i).toString();
            if (msgs.get(i).role() == MessageParam.Role.ASSISTANT && s.contains("call_00_AAA")) {
                aIdx = i;
                break;
            }
        }
        assertThat(aIdx).as("wire 应含带 tool_use 的 assistant 消息\n%s", dumpStr).isGreaterThanOrEqualTo(0);
        assertThat(aIdx + 1).as("assistant 后应还有消息\n%s", dumpStr).isLessThan(msgs.size());

        MessageParam next = msgs.get(aIdx + 1);
        String nextStr = next.toString();
        assertThat(next.role()).as("紧随 assistant 的应是 user（tool_result 载体）\n%s", dumpStr)
            .isEqualTo(MessageParam.Role.USER);
        assertThat(nextStr).as("call_00 的 result 应在紧随的一条里\n%s", dumpStr).contains("call_00_AAA");
        assertThat(nextStr).as("call_01 的 result 必须也在【同一条】里（现状：被拆到下一条 → DeepSeek ant 报 without tool_result immediately after）\n%s", dumpStr).contains("call_01_BBB");
        assertThat(nextStr).as("call_02 的 result 必须也在【同一条】里\n%s", dumpStr).contains("call_02_CCC");
    }
}
