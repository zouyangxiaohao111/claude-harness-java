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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-01 · AgentMessageBus 文件型 mailbox 轮询 + 消息优先级 · 对齐 CC waitForNextPromptOrShutdown
 * (inProcessRunner.ts:689-868，尤其 :763-845 shutdown > team-lead > FIFO)。
 *
 * <p><b>WHY（规则九）</b>：CC 优先级语义的价值是<b>防饿死</b>——peer 消息洪泛时
 * shutdown_request 必须仍能穿透（:763-804 注释明言 "to prevent starvation when peer-to-peer
 * messages flood the queue"），team-lead 消息也须先于 peer 处理（:812-819）。Java 侧若只做
 * FIFO，shutdown/lead 消息会被 peer 消息永久推迟。
 */
@DisplayName("W8-01 · AgentMessageBus 文件型 mailbox 轮询 + 消息优先级")
class AgentMessageBusPriorityPollTest {

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
    @DisplayName("shutdown_request 最高优先：peer 消息在前也先返回 shutdown（inProcessRunner.ts:763-804）")
    void shutdownRequest_beatsFifoPeerMessage() {
        // WHY: CC :763-804 —— shutdown 请求被 peer 洪泛饿死会让 teammate 无法被正常终止
        write("bob", "hello alice");   // FIFO 位置 0
        write("carol", "{\"type\":\"shutdown_request\",\"requestId\":\"r1\",\"from\":\"team-lead\",\"timestamp\":\"2026-01-01T00:00:00.000Z\"}"); // 位置 1

        Optional<AgentMessageBus.MailboxPollResult> polled =
            AgentMessageBus.pollFileMailbox("alice", "research-team");

        assertThat(polled).as("存在未读 shutdown_request 时必须有结果").isPresent();
        assertThat(polled.get().type()).as("shutdown_request 必须优先于 FIFO").isEqualTo(
            AgentMessageBus.MailboxPollResult.TYPE_SHUTDOWN_REQUEST);
        assertThat(polled.get().index()).isEqualTo(1);
        // 已标已读：再次轮询返回 peer 消息
        Optional<AgentMessageBus.MailboxPollResult> next =
            AgentMessageBus.pollFileMailbox("alice", "research-team");
        assertThat(next.get().type()).isEqualTo(AgentMessageBus.MailboxPollResult.TYPE_NEW_MESSAGE);
        assertThat(next.get().from()).isEqualTo("bob");
    }

    @Test
    @DisplayName("team-lead 优先于 peer FIFO（inProcessRunner.ts:812-819）")
    void teamLead_beatsPeerFifo() {
        // WHY: CC :812-819 —— leader 代表用户意图与协调，peer 闲聊不应推迟 leader 指令
        write("bob", "peer chatter");
        write(SwarmConstants.TEAM_LEAD_NAME, "lead directive");

        Optional<AgentMessageBus.MailboxPollResult> polled =
            AgentMessageBus.pollFileMailbox("alice", "research-team");

        assertThat(polled).isPresent();
        assertThat(polled.get().from()).as("team-lead 消息必须优先于 peer").isEqualTo(
            SwarmConstants.TEAM_LEAD_NAME);
    }

    @Test
    @DisplayName("无未读消息返回 empty；普通 peer 消息 FIFO 返回（inProcessRunner.ts:822-826）")
    void emptyWhenNoUnread_andFifoFallback() {
        // WHY: CC :822-826 FIFO 兜底 —— 无 shutdown/lead 时按序消费 peer 消息
        write("bob", "first");
        write("carol", "second");

        Optional<AgentMessageBus.MailboxPollResult> first =
            AgentMessageBus.pollFileMailbox("alice", "research-team");
        assertThat(first.get().from()).isEqualTo("bob");
        assertThat(first.get().index()).isZero();

        Optional<AgentMessageBus.MailboxPollResult> second =
            AgentMessageBus.pollFileMailbox("alice", "research-team");
        assertThat(second.get().from()).isEqualTo("carol");

        // 全部已读 → empty
        assertThat(AgentMessageBus.pollFileMailbox("alice", "research-team")).isEmpty();
    }
}
