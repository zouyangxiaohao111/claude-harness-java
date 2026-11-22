package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.AgentTranscript;
import com.nexusai.application.agent.subagent.ResumeErrorCode;
import com.nexusai.application.agent.subagent.StopMarkers;
import com.nexusai.application.agent.subagent.TeammateResumeGate;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B3 · 用户 Stop 后拒绝恢复（k9 原文）+ 四变量判据（lineage 跳过探测）+ 成功后双清。
 *
 * <p>判据（CC 偏移 333967189 / 356171000）：
 * {@code (meta.stoppedByUser || 伴生文件 || 内存 stoppedByUser) && !userInitiated && !lineage}。
 *
 * <p>lineage 方向按 CC 收窄：只有<b>成员（后代）恢复 lead（祖先）</b>为真；
 * lead / 主会话恢复成员（父恢复子）与兄弟之间一律 false。
 */
@DisplayName("B3 · teammate 用户 Stop 拒绝恢复（k9 / lineage / 双清）")
class TeammateStopMarkerTest {

    private static final String TEAM = "research-team";
    private static final String SELF = "sess-aaaaaaa1";
    private static final String AGENT_ID = "mate@" + TEAM;

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SendMessageTool tool;
    private AgentNameRegistry agents;
    private StopMarkers markers;
    private final AtomicInteger relaunchCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        SessionCwdHolder.set(SELF, tempDir.toAbsolutePath().normalize().toString());
        SessionCwdHolder.setOriginalCwd(SELF, tempDir.toAbsolutePath().normalize().toString());
        Files.createDirectories(tempDir.resolve(SELF).resolve("subagents"));
        writeTeamConfig("team-lead", "mate");

        agents = new AgentNameRegistry();
        agents.register(SELF, "mate", AGENT_ID);
        markers = new StopMarkers();

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
        tool.setStopMarkers(markers);
        tool.setTeammateRelauncher((name, team, session, agentType, prompt, resumeMessages) -> {
            relaunchCalls.incrementAndGet();
            return com.nexusai.application.agent.subagent.TeammateRelauncher.TeammateRelaunchResult
                .resumed(name + "@" + team, resumeMessages == null ? 0 : resumeMessages.size());
        });
    }

    @AfterEach
    void tearDown() {
        SessionCwdHolder.clear(SELF);
        SessionCwdHolder.clearOriginalCwd(SELF);
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.task.config-dir");
    }

    @Test
    @DisplayName("a · 用户 Stop 后模型投递 → k9 原文，且不发起重拉（且 lead 恢复成员也不再是 lineage）")
    void userStopped_sendRefused() throws Exception {
        // [lineage] 任务书收窄后 lead→member 不是 lineage（父恢复子），主会话同样被 k9 拦住。
        //   本用例仍用同队另一成员（兄弟）锁「非血统发起方」。
        writeTeamConfig("team-lead", "mate", "mate2");
        agents.register(SELF, "mate2", "mate2@" + TEAM);
        markers.markStopped(AGENT_ID);

        AgentToolResult<?> result = tool.execute(block("mate", "continue please"),
            teamCtx().withTeammateIdentity(new TeammateIdentity(
                "mate2@" + TEAM, "mate2", TEAM, "#aabbcc", false, SELF)));

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isFalse();
        assertThat(out.get("degradedClass").asText()).isEqualTo("not_reachable");
        assertThat(out.get("message").asText())
            .as("SendMessage 侧 k9 原文（CC 偏移 356171000）")
            .isEqualTo(ResumeErrorCode.stoppedByUserSendText("mate"));
        assertThat(relaunchCalls.get()).as("用户 Stop 后不得重拉").isZero();
    }

    @Test
    @DisplayName("伴生文件：非用户/非血统 → 拒绝；userInitiated 或 lineage → 跳过探测")
    void companionMarker_gateHonoursUserInitiatedAndLineage() {
        AgentTranscript.markStoppedByUser(tempDir, SELF, AGENT_ID);

        assertThat(TeammateResumeGate.refused(tempDir, SELF, AGENT_ID, false, false, markers))
            .as("模型自动恢复必须被拒绝").isTrue();
        assertThat(TeammateResumeGate.refused(tempDir, SELF, AGENT_ID, true, false, markers))
            .as("用户显式 resume 放行").isFalse();
        assertThat(TeammateResumeGate.refused(tempDir, SELF, AGENT_ID, false, true, markers))
            .as("lineage 连伴生探测都跳过（CC A=lineage!==undefined）").isFalse();
    }

    @Test
    @DisplayName("lineage a · 主线（team lead = 成员的父）恢复成员 → 父恢复子不是 lineage，k9 拒绝")
    void leadResume_isNotLineage_k9() throws Exception {
        writeTeamConfig("team-lead", "mate");
        AgentTranscript.markStoppedByUser(tempDir, SELF, AGENT_ID);

        AgentToolResult<?> result = tool.execute(block("mate", "continue please"), teamCtx());

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean())
            .as("父恢复子不是 lineage（CC 只在恢复祖先时传 lineage 集合）").isFalse();
        assertThat(out.get("message").asText())
            .isEqualTo(ResumeErrorCode.stoppedByUserSendText("mate"));
        assertThat(relaunchCalls.get()).as("不得借 lineage 复活被用户停掉的成员").isZero();
    }

    @Test
    @DisplayName("lineage a2 · 成员恢复 lead（后代发起祖先）→ teammateLinked=true")
    void memberResumeLead_isLineage() {
        String lead = "team-lead@" + TEAM;
        String member = AGENT_ID;

        assertThat(com.nexusai.application.agent.subagent.AgentLineage
            .teammateLinked(member, lead, lead))
            .as("成员是后代、lead 是祖先 ⇒ 唯一为真的方向").isTrue();
        assertThat(com.nexusai.application.agent.subagent.AgentLineage
            .teammateLinked(lead, member, lead))
            .as("lead 恢复成员（父恢复子）").isFalse();
        assertThat(com.nexusai.application.agent.subagent.AgentLineage
            .teammateLinked(null, member, lead))
            .as("主会话（即 lead）恢复成员").isFalse();
    }

    @Test
    @DisplayName("lineage b · 同队另一成员（兄弟，非祖先/后代）+ 入参伪造 lineage → 仍 k9 拒绝")
    void siblingResume_isNotLineage_k9_evenIfInputForged() throws Exception {
        writeTeamConfig("team-lead", "mate", "mate2");
        agents.register(SELF, "mate2", "mate2@" + TEAM);
        markers.markStopped(AGENT_ID);

        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "continue please");
        input.put("summary", "s");
        input.put("lineage", true);          // ⛔ 伪造成本：判定不读工具入参
        input.put("userInitiated", true);
        ToolUseBlock forged = new ToolUseBlock(UUID.randomUUID().toString(), "SendMessage", input);

        AgentToolResult<?> result = tool.execute(forged,
            teamCtx().withTeammateIdentity(new TeammateIdentity(
                "mate2@" + TEAM, "mate2", TEAM, "#aabbcc", false, SELF)));

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).as("兄弟成员不是同一血统").isFalse();
        assertThat(out.get("message").asText()).isEqualTo(ResumeErrorCode.stoppedByUserSendText("mate"));
        assertThat(relaunchCalls.get()).isZero();
    }

    @Test
    @DisplayName("恢复成功后双清：内存标记 + 磁盘伴生文件都消失")
    void success_clearsBothMarkers() {        markers.markStopped(AGENT_ID);
        AgentTranscript.markStoppedByUser(tempDir, SELF, AGENT_ID);
        Path sidecar = AgentTranscript.getStoppedByUserPath(tempDir, SELF, AGENT_ID);
        assertThat(sidecar).as("前提：伴生文件已写").exists();
        assertThat(markers.isStopped(AGENT_ID)).isTrue();

        TeammateResumeGate.clearAfterResume(tempDir, SELF, AGENT_ID, markers);

        assertThat(markers.isStopped(AGENT_ID)).as("内存标记必须清").isFalse();
        assertThat(sidecar).as("磁盘伴生文件必须清（否则下次又被它拦）").doesNotExist();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

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
