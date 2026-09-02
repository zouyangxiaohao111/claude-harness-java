package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.infra.util.SwarmConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-02 · AutonomousAgentLoop 运行循环生产化 · 对齐 CC inProcessRunner.ts:689-1416。
 *
 * <p><b>WHY（规则九）</b>：
 * <ul>
 *   <li><b>pendingUserMessages 内存优先</b>（:705-739）：CC 每轮 poll 最先检查内存队列——转录视图
 *       注入的用户消息必须<b>立即</b>被消费（不等 500ms poll），否则用户输入被延迟/被 mailbox 淹没。</li>
 *   <li><b>shutdown_request 交模型决策</b>（:1364-1381，S-2 修正）：不再自动回 shutdown_approved，
 *       而是格式化为 teammate-message XML 作为下一轮 prompt——让模型决定（approve/reject 工具）。
 *       教学版自动批准绕过模型（TeamMessageBus stub 前科），必须修正。</li>
 *   <li><b>onIdleCallbacks 唤醒</b>（M-7，:1318-1326）：轮末置 isIdle:true 并调用所有已注册回调
 *       （leader 的 engine.waitForIdle 等待），清空回调防重复触发。</li>
 * </ul>
 */
@DisplayName("W8-02 · AutonomousAgentLoop 运行循环（pendingUserMessages 优先 + shutdown 交模型 + idle 唤醒）")
class AutonomousAgentLoopRunLoopTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private void write(String from, String text) {
        TeammateMailbox.writeToMailbox("alice",
            TeammateMailbox.TeammateMessage.of(from, text, TeammateMailbox.isoNow(), null),
            "research-team");
    }

    @Test
    @DisplayName("waitForNextPromptOrShutdown: pendingUserMessages 内存优先于文件 mailbox（inProcessRunner.ts:705-739）")
    void pendingUserMessages_beatsFileMailbox() {
        // WHY: CC :705-739 内存队列最先检查（pollCount 0 先查）——转录视图注入的消息必须立即消费，
        //      不等 500ms poll；否则用户输入会被 mailbox 中的旧消息抢先。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        // mailbox 中先有一条未读 peer 消息（FIFO 位置 0）
        write("bob", "old peer message");
        // 内存 pendingUserMessages 注入新消息
        loop.injectUserMessage("new user input");

        AutonomousAgentLoop.WaitResult result = loop.waitForNextPromptOrShutdown();

        assertThat(result.type()).as("内存队列必须优先于文件 mailbox").isEqualTo(
            AutonomousAgentLoop.WaitResult.TYPE_NEW_MESSAGE);
        assertThat(result.from()).as("pendingUserMessages 来源标记为 user").isEqualTo("user");
        assertThat(result.text()).as("必须消费内存新消息").isEqualTo("new user input");
        // mailbox 中的旧消息未被消费（仍未被标已读）
        List<TeammateMailbox.TeammateMessage> all =
            TeammateMailbox.readMailbox("alice", "research-team");
        assertThat(all).anyMatch(m -> !m.read() && "bob".equals(m.from()));
    }

    @Test
    @DisplayName("waitForNextPromptOrShutdown: 文件 mailbox shutdown_request 交回（inProcessRunner.ts:763-804）")
    void fileMailbox_shutdownRequestReturned() {
        // WHY: CC :763-804 shutdown_request 最高优先——teammate 必须感知 leader 的终止意图
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        write("bob", "hello");
        write(SwarmConstants.TEAM_LEAD_NAME,
            "{\"type\":\"shutdown_request\",\"requestId\":\"r1\",\"from\":\"team-lead\",\"timestamp\":\"2026-01-01T00:00:00.000Z\"}");

        AutonomousAgentLoop.WaitResult result = loop.waitForNextPromptOrShutdown();

        assertThat(result.type()).as("shutdown_request 必须被识别").isEqualTo(
            AutonomousAgentLoop.WaitResult.TYPE_SHUTDOWN_REQUEST);
    }

    @Test
    @DisplayName("dispatchShutdownRequest: 格式化为 teammate-message XML 交模型决策，不自动批准（inProcessRunner.ts:1364-1381，S-2）")
    void shutdownRequest_formattedForModel_notAutoApproved() {
        // WHY: CC :1364-1381 —— shutdown_request 必须交给模型用 approveShutdown/rejectShutdown 工具决策，
        //      而不是教学版 stub 自动回 shutdown_approved（绕过模型）。这是 S-2 修正的核心。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setTeamName("research-team");

        String original = "{\"type\":\"shutdown_request\",\"requestId\":\"r1\",\"from\":\"team-lead\",\"timestamp\":\"2026-01-01T00:00:00.000Z\"}";
        String nextPrompt = loop.formatShutdownRequestAsPrompt(SwarmConstants.TEAM_LEAD_NAME, original);

        assertThat(nextPrompt).as("必须用 teammate-message XML 包裹").startsWith("<teammate-message teammate_id=\"team-lead\">");
        assertThat(nextPrompt).as("必须保留原始 shutdown_request JSON 全文").contains("\"type\":\"shutdown_request\"");
        assertThat(nextPrompt).as("必须以 XML 闭合标签结尾").endsWith("</teammate-message>");
    }

    @Test
    @DisplayName("transitionToIdle: 置 isIdle + 调用 onIdleCallbacks + 清空回调（M-7，inProcessRunner.ts:1318-1326）")
    void transitionToIdle_invokesAndClearsCallbacks() {
        // WHY: CC :1318-1326 —— leader 用 onIdleCallbacks 等待 teammate 空闲（engine.waitForIdle）；
        //      轮末必须触发回调并清空，否则 leader 永久阻塞或回调重复触发。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        AtomicInteger callbackCount = new AtomicInteger(0);
        loop.addOnIdleCallback(callbackCount::incrementAndGet);
        loop.addOnIdleCallback(callbackCount::incrementAndGet);

        loop.transitionToIdle();

        assertThat(loop.isIdle()).as("轮末必须置 isIdle:true").isTrue();
        assertThat(callbackCount.get()).as("所有已注册 idle 回调必须被调用").isEqualTo(2);
        assertThat(loop.idleCallbackCount()).as("回调调用后必须清空").isZero();
    }

    @Test
    @DisplayName("sendIdleNotification: 向 team-lead inbox 写入 idle_notification 结构化消息（inProcessRunner.ts:569-589）")
    void sendIdleNotification_writesToLeaderMailbox() {
        // WHY: CC sendIdleNotification → createIdleNotification → writeToMailbox(TEAM_LEAD_NAME,...)
        //      —— leader 通过 idle_notification 感知 teammate 已空闲（UI 状态 / 唤醒等待）
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setTeamName("research-team");

        loop.sendIdleNotification("available", null, "task-42", null, null);

        List<TeammateMailbox.TeammateMessage> leadInbox =
            TeammateMailbox.readMailbox(SwarmConstants.TEAM_LEAD_NAME, "research-team");
        assertThat(leadInbox).as("必须向 team-lead 收件箱写入 idle_notification").isNotEmpty();
        String text = leadInbox.get(0).text();
        assertThat(text).as("信封 text 必须是结构化 idle_notification JSON").contains("\"type\":\"idle_notification\"");
        assertThat(text).as("必须含 idleReason").contains("\"idleReason\":\"available\"");
        // T-B P-8: idle_notification 必须携带 completedTaskId（teammateMailbox.ts:402）
        assertThat(text).as("必须含 completedTaskId").contains("\"completedTaskId\":\"task-42\"");
    }
}
