package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.ResumeErrorCode;
import com.nexusai.application.agent.subagent.ResumeQuota;
import com.nexusai.application.agent.subagent.TeammateRelauncher;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B3 · teammate 非运行态恢复：按 transcript 全量重建历史 + in-process 重拉。
 *
 * <p>锁住：
 * <ul>
 *   <li>e：成功文案含 {@code resumed it as an in-process teammate with N prior messages}，
 *       且 N == 真正传给 relauncher 的列表长度（CC 偏移 356176932）；</li>
 *   <li>f：meta 缺失 / 不可读两档都不崩，按 CC {@code (no prior transcript)} 重拉；</li>
 *   <li>c（resume 侧）：会话满 → {@code session_full} 原文，且不发起重拉。</li>
 * </ul>
 */
@DisplayName("B3 · teammate 重拉（transcript 重放 N / meta 两档 / 配额满）")
class TeammateRelaunchTest {

    private static final String TEAM = "research-team";
    private static final String SELF = "sess-aaaaaaa1";
    private static final String AGENT_ID = "mate@" + TEAM;

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SendMessageTool tool;
    private AgentNameRegistry agents;
    private final AtomicReference<List<AgentMessage>> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        SessionCwdHolder.set(SELF, tempDir.toAbsolutePath().normalize().toString());
        SessionCwdHolder.setOriginalCwd(SELF, tempDir.toAbsolutePath().normalize().toString());
        Files.createDirectories(tempDir.resolve(SELF).resolve("subagents"));
        writeTeamConfig("team-lead", "mate");

        agents = new AgentNameRegistry();
        agents.register(SELF, "mate", AGENT_ID);
        agents.retireAgent(SELF, "mate", AGENT_ID, "completed", null);

        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(AGENT_ID)).thenReturn(Optional.empty());

        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setAgentNameRegistry(agents);

        tool = new SendMessageTool(new TeamHelpers());
        tool.setRecipientResolver(resolver);
        tool.setAgentNameRegistry(agents);
        tool.setSpawnInProcess(new SpawnInProcess((com.nexusai.application.agent.tasks.TaskFrameworkService) null));
        tool.setBackgroundTaskRunner(runner);
        tool.setSessionDirResolver(id -> tempDir);
        tool.setTeammateRelauncher((name, team, session, agentType, prompt, resumeMessages) -> {
            captured.set(resumeMessages);
            int n = resumeMessages == null ? 0 : resumeMessages.size();
            return TeammateRelauncher.TeammateRelaunchResult.resumed(name + "@" + team, n);
        });
    }

    @AfterEach
    void tearDown() {
        SessionCwdHolder.clear(SELF);
        SessionCwdHolder.clearOriginalCwd(SELF);
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.task.config-dir");
        captured.set(null);
    }

    @Test
    @DisplayName("e · 重拉文案 N == 实际重放条数（transcript 全量重建，非最后一条）")
    void evicted_relaunchWithNPriorMessages() throws Exception {
        AgentTranscript.recordSidechainTranscript(tempDir, SELF, AGENT_ID, List.of(
            msg("user", "first request"),
            msg("assistant", "first answer")));

        AgentToolResult<?> result = tool.execute(block("mate", "continue please"), teamCtx());

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(captured.get()).as("必须把 transcript 历史交给重拉器").isNotNull();
        assertThat(captured.get()).as("整段历史重放（2 条）").hasSize(2);
        assertThat(out.get("message").asText()).isEqualTo(
            "Teammate \"mate\" was not running; resumed it as an in-process teammate with 2 prior "
                + "messages and your message as its next prompt.");
    }

    @Test
    @DisplayName("f-1 · meta 缺失：不崩，按 (no prior transcript) 重拉")
    void metaMissing_relaunchNoPriorTranscript() throws Exception {
        AgentToolResult<?> result = tool.execute(block("mate", "hello"), teamCtx());

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).as("teammate 无 meta 不得按 dSa 拒绝")
            .isTrue();
        assertThat(out.get("message").asText())
            .contains("(no prior transcript) with your message as its next prompt.");
        assertThat(captured.get()).as("无 transcript ⇒ 空历史").isNullOrEmpty();
    }

    @Test
    @DisplayName("f-2 · meta 不可读（坏 JSON）：不崩，同样按 (no prior transcript) 重拉")
    void metaUnreadable_relaunchNoPriorTranscript() throws Exception {
        Path meta = tempDir.resolve(SELF).resolve("subagents")
            .resolve("agent-" + AGENT_ID + ".meta.json");
        Files.writeString(meta, "{not-json");

        AgentToolResult<?> result = tool.execute(block("mate", "hello"), teamCtx());

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).as("meta 坏 JSON 不得打崩恢复").isTrue();
        assertThat(out.get("message").asText()).contains("(no prior transcript)");
    }

    @Test
    @DisplayName("c · 会话满 ⇒ session_full 原文，且不发起重拉（不排队）")
    void quotaFull_returnsSessionFull() throws Exception {
        ResumeQuota quota = new ResumeQuota(1);
        ResumeQuota.Release held = quota.tryAcquire(SELF);
        tool.setResumeQuota(quota);
        try {
            AgentToolResult<?> result = tool.execute(block("mate", "hello"), teamCtx());

            JsonNode out = json.readTree(String.valueOf(result.data()));
            assertThat(out.get("success").asBoolean()).isFalse();
            assertThat(out.get("degradedClass").asText()).isEqualTo("session_full");
            assertThat(out.get("message").asText())
                .isEqualTo(ResumeErrorCode.sessionFullText("mate"));
            assertThat(captured.get()).as("槽满不得发起重拉").isNull();
        } finally {
            held.release();
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private ToolUseBlock block(String to, String message) {
        ObjectNode input = json.createObjectNode();
        input.put("to", to);
        input.put("message", message);
        input.put("summary", "s");
        return new ToolUseBlock(UUID.randomUUID().toString(), "SendMessage", input);
    }

    private Map<String, Object> teamContext() {
        Map<String, Object> appState = new LinkedHashMap<>();
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("teamName", TEAM);
        tc.put("leadAgentId", "team-lead@" + TEAM);
        Map<String, Object> mate = new LinkedHashMap<>();
        mate.put("name", "mate");
        Map<String, Object> mates = new LinkedHashMap<>();
        mates.put(AGENT_ID, mate);
        tc.put("teammates", mates);
        appState.put("teamContext", tc);
        return appState;
    }

    private void writeTeamConfig(String... memberNames) {
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = json.createObjectNode();
        config.put("name", TEAM);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", "team-lead@" + TEAM);
        config.put("leadSessionId", SELF);
        ArrayNode members = config.putArray("members");
        for (String name : memberNames) {
            ObjectNode member = members.addObject();
            member.put("agentId", name + "@" + TEAM);
            member.put("name", name);
            member.put("joinedAt", System.currentTimeMillis());
            member.put("tmuxPaneId", "");
            member.set("subscriptions", json.createArrayNode());
        }
        helpers.writeConfig(TEAM, config.toString());
    }

    private ToolUseContext teamCtx() {
        Map<String, Object> appState = teamContext();
        return ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF,
            PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP,
            List.of(), null, PermissionMode.DEFAULT, Map.of(),
            false, "", null, null, null, null,
            prev -> appState,
            updater -> {
                Map<String, Object> next = updater.apply(appState);
                appState.clear();
                appState.putAll(next);
            },
            null, null);
    }
}
