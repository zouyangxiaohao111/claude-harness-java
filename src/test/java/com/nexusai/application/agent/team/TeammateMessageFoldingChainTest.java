package com.nexusai.application.agent.team;

import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-04 · TeammateMessageFoldingChain（Java 消息折叠链）· 对齐 CC Messages.tsx:520 渲染链中
 * collapseTeammateShutdowns 步骤（utils/collapseTeammateShutdowns.ts:3-55）。
 *
 * <p><b>WHY（规则九）</b>：CC 在 Messages.tsx:520 对出站消息列表应用折叠链，连续 in-process
 * teammate shutdown task_status attachment 折叠为单条 teammate_shutdown_batch —— 否则前端
 * transcript 会收到多条「Teammate @x shut down gracefully」洪泛。Java 侧出站消息组装点接入
 * 本链（OPD-TP-07），本测试验证折叠语义与 CC 完全一致。
 */
@DisplayName("W8-04 · TeammateMessageFoldingChain 折叠链（对齐 CC Messages.tsx:520 / collapseTeammateShutdowns.ts）")
class TeammateMessageFoldingChainTest {

    /** 构建一条 in_process_teammate completed 的 task_status attachment 消息（CC isTeammateShutdownAttachment 命中）。 */
    private static ChatMessageDto teammateCompleted(String taskId) {
        String payload = "{\"type\":\"task_status\",\"taskId\":\"" + taskId
            + "\",\"taskType\":\"in_process_teammate\",\"description\":\"alice\",\"status\":\"completed\",\"deltaSummary\":null,\"outputFilePath\":null}";
        return new ChatMessageDto(
            "msg-" + taskId, "sess-1", null, "attachment", payload, null,
            null, null, null, null, "刚刚", null,
            null, null, null, null, null, null, false, false,
            null, "task_status", false, null, null, null,
            null); // DEC-04 usage
    }

    /** 构建一条非 teammate（local_agent）的 task_status attachment —— 折叠不应命中。 */
    private static ChatMessageDto localAgentCompleted(String taskId) {
        String payload = "{\"type\":\"task_status\",\"taskId\":\"" + taskId
            + "\",\"taskType\":\"local_agent\",\"description\":\"bg\",\"status\":\"completed\",\"deltaSummary\":null,\"outputFilePath\":null}";
        return new ChatMessageDto(
            "msg-" + taskId, "sess-1", null, "attachment", payload, null,
            null, null, null, null, "刚刚", null,
            null, null, null, null, null, null, false, false,
            null, "task_status", false, null, null, null,
            null); // DEC-04 usage
    }

    /** 普通 assistant 消息 —— 透传。 */
    private static ChatMessageDto assistant(String text) {
        return new ChatMessageDto(
            "msg-a1", "sess-1", com.nexusai.model.session.dto.Role.assistant, null, text, null,
            null, null, null, null, "刚刚", null,
            null, null, null, null, null, null, false, false,
            null, null, false, null, null, null,
            null); // DEC-04 usage
    }

    @Test
    @DisplayName("单条 teammate shutdown → 原样保留（CC collapseTeammateShutdowns.ts:35-37 count==1）")
    void singleShutdown_preservedAsIs() {
        // WHY: CC :35-37 —— 单条不折叠，保留原 attachment（避免 lossy 折叠 UI 单条信息）
        List<ChatMessageDto> in = List.of(teammateCompleted("t1"));

        List<ChatMessageDto> out = TeammateMessageFoldingChain.collapse(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).subtype()).isEqualTo("task_status");
        assertThat(out.get(0).content()).contains("\"status\":\"completed\"");
    }

    @Test
    @DisplayName("连续多条 teammate shutdown → 折叠为单条 teammate_shutdown_batch 含 count（CC :38-46）")
    void consecutiveShutdowns_collapseToBatchWithCount() {
        // WHY: CC :38-46 —— 多个 teammate 先后 shut down 时前端应合并为一条 batch 通知
        List<ChatMessageDto> in = List.of(
            teammateCompleted("t1"),
            teammateCompleted("t2"),
            teammateCompleted("t3"));

        List<ChatMessageDto> out = TeammateMessageFoldingChain.collapse(in);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).subtype()).isEqualTo("teammate_shutdown_batch");
        assertThat(out.get(0).content()).contains("\"count\":3");
    }

    @Test
    @DisplayName("非 teammate 的 task_status（local_agent）不折叠，逐条透传（CC isTeammateShutdownAttachment:6-11）")
    void nonTeammateShutdowns_passedThrough() {
        // WHY: CC :6-11 —— 仅 taskType==='in_process_teammate' && status==='completed' 命中折叠；
        //      local_agent 等 background task 完成属于 collapseBackgroundBashNotifications 职责（链内另一步骤）
        List<ChatMessageDto> in = List.of(
            localAgentCompleted("bg1"),
            localAgentCompleted("bg2"));

        List<ChatMessageDto> out = TeammateMessageFoldingChain.collapse(in);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).subtype()).isEqualTo("task_status");
        assertThat(out.get(1).subtype()).isEqualTo("task_status");
    }

    @Test
    @DisplayName("非连续（teammate shutdown 夹杂普通消息）只折叠连续段，其余透传保序（CC while-scan 语义）")
    void interruptedByNormalMessage_onlyConsecutiveSegmentCollapsed() {
        // WHY: CC :24-51 —— 仅连续命中段折叠，非命中消息插入其间则各自保留；顺序不得重排
        List<ChatMessageDto> in = List.of(
            teammateCompleted("t1"),
            teammateCompleted("t2"),
            assistant("normal text"),
            teammateCompleted("t3"));

        List<ChatMessageDto> out = TeammateMessageFoldingChain.collapse(in);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).subtype()).isEqualTo("teammate_shutdown_batch");
        assertThat(out.get(0).content()).contains("\"count\":2");
        assertThat(out.get(1).content()).isEqualTo("normal text");
        assertThat(out.get(2).subtype()).isEqualTo("task_status");
    }

    @Test
    @DisplayName("null/empty 输入 → 空列表（纯函数边界，CC 折叠链输入为空安全）")
    void nullAndEmpty_returnsEmpty() {
        assertThat(TeammateMessageFoldingChain.collapse(null)).isEmpty();
        assertThat(TeammateMessageFoldingChain.collapse(List.of())).isEmpty();
    }

    @Test
    @DisplayName("teammateTaskStatusAttachment 产物是折叠链可命中的 task_status attachment（完成通知链闭环）")
    void teammateTaskStatusAttachment_feedsFoldingChain() {
        // WHY: W8-04 完成通知链 —— 终端转换产出的 task_status attachment 必须被折叠链识别，
        //      否则「多 teammate 先后 shut down」前端仍收洪泛（通知链断开 = 折叠链无输入）
        ChatMessageDto att = TeammateMessageFoldingChain.teammateTaskStatusAttachment(
            "t1", "alice", "completed", "sess-1");

        assertThat(att.author()).isEqualTo("attachment");
        assertThat(att.subtype()).isEqualTo("task_status");
        assertThat(att.content()).contains("\"taskType\":\"in_process_teammate\"")
            .contains("\"status\":\"completed\"");

        // 2 条该产物 → 折叠为 1 条 batch(count=2)：证明完成通知链产物直接喂折叠链
        List<ChatMessageDto> out = TeammateMessageFoldingChain.collapse(List.of(att, att));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).subtype()).isEqualTo("teammate_shutdown_batch");
        assertThat(out.get(0).content()).contains("\"count\":2");
    }
}
