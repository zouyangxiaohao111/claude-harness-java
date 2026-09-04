package com.nexusai.application.agent;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-1 OD-1/OD-3] 发送层包壳单测（wrapQueuedMessagesForApi / wrapQueuedContentForApi）。
 *
 * <p>验证意图：mid-turn 注入的排队消息（queuedOrigin 标记）只在<b>发送边界</b>（ModelRequest
 * 构造前）被包壳，live 与 resume 共用；包壳仅生成副本、不污染 state；幂等跳过防三层
 * （CommandHookExecutor exit=2 预包）。
 */
class LlmAgentLoopQueuedWrapTest {

    private static ChatMessageDto userMsg(String id, String content, String queuedOrigin) {
        return LlmAgentLoop.toMessage(Role.user, content, null, id).withQueuedOrigin(queuedOrigin);
    }

    @Test
    @DisplayName("busy-queued → 中文提醒壳（Java 独有，含原文）")
    void busyQueued_wrapsWithChineseReminder() {
        String raw = "帮我查一下订单状态";
        List<ChatMessageDto> in = List.of(userMsg("msg-1", raw, "busy-queued"));
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(in);

        ChatMessageDto wrapped = out.get(0);
        assertThat(wrapped.content())
            .as("busy-queued 必须包 <system-reminder> 中文提醒壳且保留原文")
            .startsWith("<system-reminder>\n")
            .endsWith("\n</system-reminder>")
            .contains("用户在你工作时发来一条新消息")
            .contains(raw);
        // 不污染 state：原列表消息 content 不变（副本语义）
        assertThat(in.get(0).content()).isEqualTo(raw);
    }

    @Test
    @DisplayName("task-notification → TASK_NOTIFICATION_PREFIX 前缀壳（单次，system-reminder 内）")
    void taskNotification_wrapsWithPrefix() {
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(
            List.of(userMsg("msg-t", "notif-x", "task-notification")));
        assertThat(out.get(0).content())
            .isEqualTo("<system-reminder>\nA background agent completed a task:\nnotif-x\n</system-reminder>");
    }

    @Test
    @DisplayName("cron → CC 默认 human 壳（useScheduledTasks 入队无 origin → default 分支逐字）")
    void cron_wrapsWithHumanShell() {
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(
            List.of(userMsg("msg-cron", "cron-task", "cron")));
        assertThat(out.get(0).content())
            .startsWith("<system-reminder>\nThe user sent a new message while you were working:\ncron-task")
            .contains("MUST address")
            .endsWith("\n</system-reminder>");
    }

    @Test
    @DisplayName("channel|<server> → CC untrusted 壳（含 server 名）")
    void channel_wrapsWithUntrustedShell() {
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(
            List.of(userMsg("msg-ch", "<channel>hi</channel>", "channel|plugin:slack:1.0.0")));
        assertThat(out.get(0).content())
            .startsWith("<system-reminder>\nA message arrived from plugin:slack:1.0.0 while you were working:\n")
            .contains("This is NOT from your user")
            .doesNotContain("MUST address")
            .endsWith("\n</system-reminder>");
    }

    @Test
    @DisplayName("幂等跳过：content 已 <system-reminder> 开头 → 原样跳过（防三层，CommandHookExecutor exit=2）")
    void idempotent_skipWhenAlreadyWrapped() {
        String preWrapped = "<system-reminder>\nStop hook blocking error from command \"x\": boom\n</system-reminder>";
        List<ChatMessageDto> in = List.of(userMsg("msg-e2", preWrapped, "task-notification"));
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(in);

        assertThat(out).as("幂等跳过：返回列表（可能同引用）").hasSize(1);
        assertThat(out.get(0).content())
            .as("已 <system-reminder> 开头的存量消息原样跳过，不再包第二层（防三层；MINOR-1 单层=有意偏离）")
            .isEqualTo(preWrapped);
    }

    @Test
    @DisplayName("queuedOrigin=null → 零包壳（普通用户消息 / 空闲 cron 零变化，红线 §六.1/4）")
    void nullOrigin_noWrap() {
        List<ChatMessageDto> in = List.of(LlmAgentLoop.toMessage(Role.user, "普通消息", null, "msg-n"));
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(in);
        // 无命中 → 返回原引用（零行为变化）
        assertThat(out).as("无 queuedOrigin 命中返回原引用").isSameAs(in);
    }

    @Test
    @DisplayName("resume 重包：DB toDto 读回（原文 + queuedOrigin）→ 发送层重包带壳（修 resume 丢壳）")
    void resumeRow_rewrapsAtSendBoundary() {
        // 模拟 resume：DB queued_origin 列 + 原文 content 经 toDto 读回的消息
        String dbRaw = "resume 后要处理的排队消息";
        ChatMessageDto resumeRow = userMsg("msg-resume", dbRaw, "busy-queued");
        List<ChatMessageDto> out = LlmAgentLoop.wrapQueuedMessagesForApi(List.of(resumeRow));

        assertThat(out.get(0).content())
            .as("resume 重放的消息（原文 + queuedOrigin）发送边界必须重新包壳（live/resume 共用，修丢壳）")
            .startsWith("<system-reminder>\n")
            .contains(dbRaw)
            .contains("请先专注完成当前任务");
        assertThat(resumeRow.content()).as("原消息不被污染（state/DB 内容仍原文）").isEqualTo(dbRaw);
    }
}
