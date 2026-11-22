package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [agent-notice-persist] 主代理 → 子代理通知「入队即落库」契约。
 *
 * <h2>WHY（规则九）</h2>
 * <p>通知的消费点只有两个（{@code LlmAgentLoop.drainPendingAgentMessages} 每轮、
 * {@code SubagentExecutor} 启动段）。子代理**已结束/空闲**时这两个点都不会发生 ⇒ 旧实现下
 * 待办只活在 {@link AgentNameRegistry} 的内存队列里，transcript（子代理会话视图的唯一数据源，
 * {@code GET /sessions/{sid}/subagents/{agentId}/transcript}）永远没有这条行，用户看到的
 * 就是「主代理明明发了，子代理那边什么都没有」。
 *
 * <p>本测试钉住的三条不变量：
 * <ol>
 *   <li><b>未消费也可见</b>：无活消费点时入队 == 落 transcript（重启后仍可读回）；</li>
 *   <li><b>有活消费点时不被丢弃</b>：仍保留内存队列给下一轮 drain；重复写同一 uuid 不产生第二行；</li>
 *   <li><b>终态兜底</b>：agent 在一轮结束后才收到消息（残留队列）时，注销前必须补写成当前链上的可见行。</li>
 * </ol>
 * ⛔ 全部用临时目录（{@code setSessionDirResolver}），绝不碰用户真实 config home。
 */
@DisplayName("[agent-notice-persist] 子代理通知入队即落库")
class AgentNotificationPersistenceTest {

    @TempDir
    Path tmpDir;

    private static final String SESSION_ID = "sess-agent-notice";
    private static final String AGENT_HEX = "a0123456789abcdef";

    private String agentUuid() {
        return AGENT_HEX;
    }

    private AgentNameRegistry registry(BackgroundTaskStatus status) {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.setSessionDirResolver(s -> tmpDir);
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        if (status == null) {
            when(runner.getTask(agentUuid())).thenReturn(Optional.empty());
        } else {
            when(runner.getTask(agentUuid())).thenReturn(Optional.of(task(status)));
        }
        registry.setBackgroundTaskRunner(runner);
        return registry;
    }

    private static BackgroundTask task(BackgroundTaskStatus status) {
        return new BackgroundTask(
            AGENT_HEX, TaskType.LOCAL_AGENT, status,
            "working", null, 1L, null, null, "", 0L, false, null, true, SESSION_ID,
            null, null, null, null, null, null);
    }

    private List<AgentMessage> readTranscript() {
        return AgentTranscript.getAgentTranscript(tmpDir, SESSION_ID, AGENT_HEX)
            .orElseThrow(() -> new AssertionError("transcript 不存在：通知没有落库"))
            .messages();
    }

    @Test
    @DisplayName("子代理已结束（无活消费点）→ 入队即落子代理转录，未 drain 也可见，重启后仍可读回")
    void endedAgentNoticePersistsImmediately() throws Exception {
        Files.createDirectories(tmpDir.resolve(SESSION_ID));
        // 模拟真实会话目录已有 transcript（agent 刚结束），通知挂在链尾。
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "done");
        assistant.put("timestamp", "2026-11-19T10:00:00Z");
        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION_ID, AGENT_HEX,
            List.of(assistant));

        AgentNameRegistry registry = registry(null); // getTask 空 → 无活消费点
        registry.queue(agentUuid(), SESSION_ID, "team-lead", "请补充审计结论",
            "补查审计结论", "coordinator");

        List<AgentMessage> messages = readTranscript();
        AgentMessage notice = messages.stream()
            .filter(m -> "agent_message".equals(m.subtype()))
            .findFirst().orElseThrow(() -> new AssertionError("通知行没有落进子代理转录"));
        assertThat(notice.role()).as("通知是 user 行（可被模型消费）").isEqualTo("user");
        assertThat(notice.isMeta()).as("非真人输入").isTrue();
        assertThat(notice.author()).as("来源 = 主代理标识").isEqualTo("team-lead");
        assertThat(notice.content()).isEqualTo("请补充审计结论");
        assertThat(notice.summary()).as("通知条摘要落盘（刷新后不丢）").isEqualTo("补查审计结论");
        assertThat(notice.uuid()).as("稳定 uuid（实时/历史去重）").isNotBlank();
        assertThat(messages.get(messages.size() - 1).uuid())
            .as("通知挂在原链尾之后，不能成为截断历史的新链头").isEqualTo(notice.uuid());
        assertThat(registry.hasPending(agentUuid()))
            .as("无读者时不再驻留内存队列（入队即落库已完成投递）").isFalse();
    }

    @Test
    @DisplayName("子代理运行中 → 保留内存队列给下一轮 drain，同时已落盘（实时可见）；同 uuid 不重复落行")
    void runningAgentKeepsQueueAndDoesNotDuplicateOnDrain() throws Exception {
        Files.createDirectories(tmpDir.resolve(SESSION_ID));
        AgentNameRegistry registry = registry(BackgroundTaskStatus.RUNNING);
        registry.queue(agentUuid(), SESSION_ID, "team-lead", "继续做任务 1", null, "coordinator");

        assertThat(registry.hasPending(agentUuid()))
            .as("运行中的 agent 必须保留待办给下一轮 drain").isTrue();
        List<AgentNameRegistry.PendingAgentMessage> drained = registry.drainPending(agentUuid());
        assertThat(drained).hasSize(1);

        // 消费侧 appendListener 会再写一次同一行（state.appendMessage → recordSidechainTranscript）：
        // 必须按 uuid 去重，否则视图里出现两条通知条。
        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION_ID, AGENT_HEX,
            List.of(AgentNotificationMessages.toTranscriptMap(drained.get(0))),
            drained.get(0).id());

        List<AgentMessage> messages = readTranscript();
        assertThat(messages.stream().filter(m -> "agent_message".equals(m.subtype())).count())
            .as("同一通知只落一行").isEqualTo(1);
    }

    @Test
    @DisplayName("终态兜底：残留队列（一轮结束后才收到）在注销时补写成当前链上的可见行")
    void leftoverQueueIsMadeVisibleOnUnregister() throws Exception {
        Files.createDirectories(tmpDir.resolve(SESSION_ID));
        AgentNameRegistry registry = registry(BackgroundTaskStatus.RUNNING);
        registry.register(SESSION_ID, "researcher", agentUuid());
        registry.queue(agentUuid(), SESSION_ID, "team-lead", "这条一直没被消费", null, "coordinator");

        // 模拟消费点从未发生、agent 随后又写了一轮（通知成为孤儿分支：不在最新 leaf 的链上）
        Map<String, Object> later = new LinkedHashMap<>();
        later.put("role", "assistant");
        later.put("content", "final answer");
        later.put("timestamp", "2026-11-19T11:00:00Z");
        AgentTranscript.recordSidechainTranscript(tmpDir, SESSION_ID, AGENT_HEX,
            List.of(later), null);

        registry.unregister(SESSION_ID, "researcher", agentUuid());

        List<AgentMessage> messages = readTranscript();
        assertThat(messages.stream().anyMatch(m ->
            "agent_message".equals(m.subtype()) && "这条一直没被消费".equals(m.content())))
            .as("注销时残留通知必须补写成可见行（不能静默丢）").isTrue();
        assertThat(registry.hasPending(agentUuid()))
            .as("注销后不得残留内存队列").isFalse();
    }

    @Test
    @DisplayName("落盘行字段：isMeta/subtype/author/summary 齐全，且不含 peer_source（不冒充跨会话来信）")
    void transcriptRowIsAgentNoticeNotPeerMessage() {
        AgentNameRegistry.PendingAgentMessage pm = new AgentNameRegistry.PendingAgentMessage(
            "id-1", agentUuid(), SESSION_ID, "team-lead", "补查审计结论", "请补充审计结论",
            "coordinator", "2026-11-19T10:00:00Z");

        Map<String, Object> row = AgentNotificationMessages.toTranscriptMap(pm);

        assertThat(row).containsEntry("role", "user")
            .containsEntry("subtype", "agent_message")
            .containsEntry("isMeta", true)
            .containsEntry("author", "team-lead")
            .containsEntry("summary", "补查审计结论")
            .containsEntry("uuid", "id-1");
        assertThat(row).doesNotContainKey("structuredOutput");
        assertThat(String.valueOf(row)).as("子代理通知不是跨会话来信").doesNotContain("peer_source");
    }
}
