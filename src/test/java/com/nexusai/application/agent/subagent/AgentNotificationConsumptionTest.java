package com.nexusai.application.agent.subagent;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SendMessage 通知消费链 · 后端契约测试（队列记录 → 消费行 → 转录往返 → 读侧合并）。
 *
 * <p>钉住的不变量（每一条对应一个可观测消费者）：
 * <ol>
 *   <li>入队记录携带稳定 ID / 来源 / 摘要 / 会话 —— 缺任一，前端通知条就没有来源或无法幂等去重；</li>
 *   <li>消费行是 {@code role=user + isMeta=true + subtype=agent_message}，摘要只在
 *       {@code structuredOutput.agent_summary}（provider 不序列化，模型只看到正文）；</li>
 *   <li>转录写入/读回保留 summary —— 否则刷新后通知条退回"无摘要"；</li>
 *   <li>读侧相邻 user 合并不得吞掉通知行 —— 否则 isMeta/author/subtype/summary 被洗成普通提问。</li>
 * </ol>
 */
@DisplayName("[agent-notice] SendMessage 通知消费契约")
class AgentNotificationConsumptionTest {

    @TempDir
    Path tmpDir;

    @Test
    @DisplayName("队列记录携带 id/from/summary/sessionId/origin，drainPending 只消费一次")
    void queueCarriesRecordFieldsAndDrainsOnce() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.queue("agent-111", "sess-1", "team-lead", "请补充审计结论",
            "补查审计结论", "coordinator");
        registry.queue("agent-111", "sess-1", "researcher", "第二条", null, "peer");

        List<AgentNameRegistry.PendingAgentMessage> first = registry.drainPending("agent-111");
        assertThat(first).hasSize(2);
        AgentNameRegistry.PendingAgentMessage m0 = first.get(0);
        assertThat(m0.id()).as("稳定 ID（实时/历史按 uuid 去重）").isNotBlank();
        assertThat(m0.sessionId()).isEqualTo("sess-1");
        assertThat(m0.from()).isEqualTo("team-lead");
        assertThat(m0.summary()).isEqualTo("补查审计结论");
        assertThat(m0.text()).isEqualTo("请补充审计结论");
        assertThat(m0.origin()).isEqualTo("coordinator");
        assertThat(first.get(1).id()).as("每条记录各自独立 ID").isNotEqualTo(m0.id());

        assertThat(registry.drainPending("agent-111"))
            .as("drain 取走即清 —— 第二轮不得重复消费").isEmpty();
    }

    @Test
    @DisplayName("消费行契约：id/author/subtype/isMeta/createdAt + 摘要只进 structuredOutput")
    void notificationMessageCarriesContract() {
        AgentNameRegistry.PendingAgentMessage pm = new AgentNameRegistry.PendingAgentMessage(
            "id-1", "agent-111", "sess-1", "team-lead", "补查审计结论", "请补充审计结论",
            "coordinator", "2026-11-19T10:00:00Z");

        ChatMessageDto dto = AgentNotificationMessages.toChatMessage(pm);

        assertThat(dto.id()).isEqualTo("id-1");
        assertThat(dto.sessionId()).isEqualTo("sess-1");
        assertThat(dto.role()).isEqualTo(Role.user);
        assertThat(dto.author()).as("来源 = 发送方 agent 名").isEqualTo("team-lead");
        assertThat(dto.subtype()).isEqualTo("agent_message");
        assertThat(dto.isMeta()).as("非真人输入").isTrue();
        assertThat(dto.content()).as("正文原样（coordinator 包壳只在模型副本）").isEqualTo("请补充审计结论");
        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.structuredOutput())
            .as("摘要仅 UI 载体；provider 不序列化该字段")
            .containsEntry("agent_summary", "补查审计结论");
    }

    @Test
    @DisplayName("转录写入/读回保留 summary 与 author/subtype/isMeta")
    void transcriptRoundTripsNoticeFields() {
        Path sessionDir = tmpDir.resolve("proj");
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("role", "user");
        notice.put("content", "请补充审计结论");
        notice.put("author", "team-lead");
        notice.put("subtype", "agent_message");
        notice.put("isMeta", true);
        notice.put("summary", "补查审计结论");
        String agentId = "a1111111111111111";

        AgentTranscript.recordSidechainTranscript(sessionDir, "sess-1", agentId, List.of(notice));

        List<AgentMessage> messages = AgentTranscript.getAgentTranscript(sessionDir, "sess-1", agentId)
            .orElseThrow().messages();
        AgentMessage loaded = messages.stream()
            .filter(m -> "agent_message".equals(m.subtype()))
            .findFirst().orElseThrow();
        assertThat(loaded.author()).isEqualTo("team-lead");
        assertThat(loaded.isMeta()).isTrue();
        assertThat(loaded.summary()).as("刷新后通知条仍要有摘要").isEqualTo("补查审计结论");
    }

    @Test
    @DisplayName("读侧相邻 user 合并不得吞通知行（字段会被洗成普通提问）")
    void userMergeDoesNotSwallowNoticeRow() {
        AgentMessage notice = new AgentMessage("user", "通知正文", false, null, false,
            "u1", null, List.of(), null, null, null, true, "team-lead", "agent_message", "摘要");
        // 空白 assistant 会触发「相邻 user 合并」分支（filterWhitespaceOnlyAssistantMessages 前提）
        AgentMessage blankAssistant = AgentMessage.of("assistant", "");
        AgentMessage ask = AgentMessage.of("user", "真实提问");

        List<AgentMessage> out = MessageFilters.filterWhitespaceOnlyAssistantMessages(
            List.of(notice, blankAssistant, ask));

        assertThat(out).as("通知行独立成条，不与相邻提问合并").hasSize(2);
        assertThat(out.get(0).subtype()).isEqualTo("agent_message");
        assertThat(out.get(0).summary()).isEqualTo("摘要");
        assertThat(out.get(1).content()).isEqualTo("真实提问");
    }
}
