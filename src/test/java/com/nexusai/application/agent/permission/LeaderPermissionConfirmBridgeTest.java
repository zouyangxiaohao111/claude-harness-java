package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Batch2 C1] leader ToolUseConfirm setter 生产注册 + STOMP 桥 · 对齐 CC useInboxPoller.ts:259-350
 * + leaderPermissionBridge.ts registry。
 *
 * <p>WHY（规则九 · 验证意图）：
 * <ul>
 *   <li><b>生产注册</b>：@PostConstruct 后 {@link LeaderPermissionBridge#getLeaderToolUseConfirmQueue()}
 *       ≠ null —— 否则 {@link SwarmLeaderPermissionDispatcher} setter==null → leader inbox 权限请求
 *       恒自动 deny（探查 C1 P1 断链）；</li>
 *   <li><b>桥接推送</b>：dispatcher 推入的 entry（key=toolUseId）经 STOMP 推 leader 会话
 *       {@code /topic/sessions/{leadSessionId}/permission-requests}（leadSessionId 经 team config
 *       路由）—— leader 前端弹窗可见；</li>
 *   <li><b>响应回灌</b>：前端 decision=allow/deny → entry.onAllow(updatedInput, updates) /
 *       onReject(feedback) 触发 → sendPermissionResponseViaMailbox + resolvePermission 闭环；</li>
 *   <li><b>分流</b>：未知 requestId → onResponse 返回 false（交 WebSocketPermissionPrompter）。</li>
 * </ul>
 */
@DisplayName("Batch2 C1 · leader ToolUseConfirm setter 生产注册 + STOMP 桥")
class LeaderPermissionConfirmBridgeTest {

    private static final String TEAM = "confirm-team";
    private static final String LEAD_SESSION = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    private SimpMessagingTemplate ws;
    private TeamHelpers teamHelpers;
    private LeaderPermissionConfirmBridge bridge;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.team.name", TEAM);
        ws = mock(SimpMessagingTemplate.class);
        teamHelpers = new TeamHelpers();
        bridge = new LeaderPermissionConfirmBridge();
        bridge.setWs(ws);
        bridge.setTeamHelpers(teamHelpers);
    }

    @AfterEach
    void tearDown() {
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue();
        System.clearProperty("nexusai.team.name");
        TaskSystemConfig.clearForTest();
    }

    private void writeTeamConfigWithLeadSession() {
        ObjectNode config = new com.fasterxml.jackson.databind.node.JsonNodeFactory(false).objectNode();
        config.put("name", TEAM);
        config.put("leadAgentId", "team-lead@" + TEAM);
        config.put("leadSessionId", LEAD_SESSION);
        config.putArray("members");
        teamHelpers.writeConfig(TEAM, config.toString());
    }

    private LeaderPermissionBridge.ToolUseConfirmEntry entry(String toolUseId, String toolName,
            Map<String, Object> input, AtomicReference<Map<String, Object>> allowCapture,
            AtomicReference<String> rejectCapture, AtomicReference<Boolean> abortCapture) {
        return new LeaderPermissionBridge.ToolUseConfirmEntry(
                toolName, toolUseId, "desc for " + toolName, input,
                "worker1", "cyan", System.currentTimeMillis(),
                (updatedInput, updates) -> allowCapture.set(updatedInput),
                rejectCapture::set,
                () -> abortCapture.set(true));
    }

    @Test
    @DisplayName("注册 setter 后 getLeaderToolUseConfirmQueue() != null（生产确认表面 = Web STOMP）")
    void registerSetter_registersProductionSetter() {
        bridge.registerSetter();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue())
                .as("生产注册后 dispatcher setter 非 null → leader inbox 权限请求不再恒 deny")
                .isNotNull();
    }

    @Test
    @DisplayName("无 SimpMessagingTemplate → 不注册 setter（CC useInboxPoller.ts:346-350 无表面丢弃语义）")
    void registerSetter_withoutWs_skips() {
        LeaderPermissionConfirmBridge noWs = new LeaderPermissionConfirmBridge();
        noWs.setTeamHelpers(teamHelpers); // ws 保持 null
        noWs.registerSetter();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue())
                .as("ws null → setter 保持 null（无确认表面）").isNull();
    }

    @Test
    @DisplayName("onConfirmQueueUpdate 推新 entry → confirmEntries 含 toolUseId + STOMP 推送 leader 会话")
    void onConfirmQueueUpdate_pushesToStompLeaderSession() {
        writeTeamConfigWithLeadSession();
        bridge.registerSetter();
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-1", "Bash", Map.of("command", "ls"), allow, reject, abort);

        LeaderPermissionBridge.getLeaderToolUseConfirmQueue().apply(queue -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });

        // STOMP 推送验证
        ArgumentCaptor<MessagePermissionRequestEvent> captor =
                ArgumentCaptor.forClass(MessagePermissionRequestEvent.class);
        verify(ws).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/sessions/" + LEAD_SESSION + "/permission-requests"),
                captor.capture());
        MessagePermissionRequestEvent event = captor.getValue();
        assertThat(event.getRequestId()).as("requestId = worker toolUseId（前端响应回灌本桥）")
                .isEqualTo("tool-use-1");
        assertThat(event.getToolName()).isEqualTo("Bash");
        assertThat(event.getSessionId()).isEqualTo(LEAD_SESSION);
        assertThat(event.getDescription()).isEqualTo("desc for Bash");
        assertThat(event.getWorkerBadgeColor())
                .as("[perm-timeout #132] pushToStomp 必须携带 entry.workerBadgeColor（前端渲染彩色徽标）")
                .isEqualTo("cyan");
    }

    @Test
    @DisplayName("响应 allow → entry.onAllow(updatedInput, updates) 触发（resolvePermission + mailbox 闭环）")
    void onResponse_allow_triggersOnAllow() {
        writeTeamConfigWithLeadSession();
        bridge.registerSetter();
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        Map<String, Object> input = Map.of("command", "ls");
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-2", "Bash", input, allow, reject, abort);
        bridge.onConfirmQueueUpdate(queue -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });

        boolean consumed = bridge.onResponse("tool-use-2", "allow", List.of(),
                null, List.of(), null, null);

        assertThat(consumed).as("命中本桥 entry → 消费").isTrue();
        assertThat(allow.get()).as("allow → onAllow(updatedInput)").isEqualTo(input);
        assertThat(reject.get()).isNull();
        assertThat(abort.get()).isFalse();
    }

    @Test
    @DisplayName("响应 deny → entry.onReject(feedback) 触发")
    void onResponse_deny_triggersOnReject() {
        writeTeamConfigWithLeadSession();
        bridge.registerSetter();
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-3", "Bash", Map.of("command", "rm -rf"), allow, reject, abort);
        bridge.onConfirmQueueUpdate(queue -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });

        boolean consumed = bridge.onResponse("tool-use-3", "deny", null,
                "dangerous", List.of(), null, null);

        assertThat(consumed).isTrue();
        assertThat(reject.get()).as("deny → onReject(feedback)").isEqualTo("dangerous");
        assertThat(allow.get()).isNull();
    }

    @Test
    @DisplayName("未知 requestId → onResponse 返回 false（交 WebSocketPermissionPrompter，requestId 空间不碰撞）")
    void onResponse_unknownRequestId_returnsFalse() {
        writeTeamConfigWithLeadSession();
        bridge.registerSetter();
        // 不推任何 entry
        boolean consumed = bridge.onResponse("main-loop-tool-block-id", "allow", List.of(),
                null, List.of(), null, null);
        assertThat(consumed).as("非本桥请求（主 loop ToolUseBlock.id）→ 不消费，交 prompter").isFalse();
    }
}
