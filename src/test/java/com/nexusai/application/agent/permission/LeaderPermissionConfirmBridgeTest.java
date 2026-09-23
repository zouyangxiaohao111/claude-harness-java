package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [Batch2 C1] leader ToolUseConfirm setter 生产注册 + STOMP 桥 · 对齐 CC useInboxPoller.ts:259-350
 * + leaderPermissionBridge.ts registry。**[T2] 起注册/注销按会话分桶。**
 *
 * <p>WHY（规则九 · 验证意图）：
 * <ul>
 *   <li><b>生产注册（按会话）</b>：[T2] 起 leader 会话注册后
 *       {@link LeaderPermissionBridge#getLeaderToolUseConfirmQueue(String)} 该会话桶非 null ——
 *       否则 {@link com.nexusai.application.agent.team.SwarmLeaderPermissionDispatcher} 该会话
 *       setter==null → leader inbox 权限请求恒自动 deny（探查 C1 P1 断链）；</li>
 *   <li><b>跨会话隔离</b>：只注册会话 A ⇒ 会话 B 的桶必须为空（⛔ 不得借用 A 的表面 ——
 *       改前进程级单槽正是这样把 A 的弹窗表面借给了 B）；</li>
 *   <li><b>桥接推送</b>：dispatcher 推入的 entry（key=toolUseId）经 STOMP 推<b>注册时闭合的那一个会话</b>
 *       {@code /topic/sessions/{leadSessionId}/permission-requests} —— leader 前端弹窗可见；</li>
 *   <li><b>响应回灌</b>：前端 decision=allow/deny → entry.onAllow(updatedInput, updates) /
 *       onReject(feedback) 触发 → sendPermissionResponseViaMailbox + resolvePermission 闭环；</li>
 *   <li><b>分流</b>：未知 requestId → onResponse 返回 false（交 WebSocketPermissionPrompter）。</li>
 * </ul>
 *
 * <p><b>[T2] 改前的用例差异</b>：原推送目标会话靠 {@code teamHelpers.readConfig(team).leadSessionId}
 * 反查（进程级 team 名），故本测试需写 config.json；改后目标会话 = 注册会话本身（闭合进 setter），
 * 测试不再需要 config 文件 —— 这正是「setter 知道自己服务哪个会话」的直接体现。
 */
@DisplayName("Batch2 C1 · leader ToolUseConfirm setter 生产注册（按会话）+ STOMP 桥")
class LeaderPermissionConfirmBridgeTest {

    private static final String LEAD_SESSION = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_SESSION = "22222222-2222-2222-2222-222222222222";

    private SimpMessagingTemplate ws;
    private LeaderPermissionConfirmBridge bridge;

    private void newBridgeWithWs() {
        ws = mock(SimpMessagingTemplate.class);
        bridge = new LeaderPermissionConfirmBridge();
        bridge.setWs(ws);
    }

    @AfterEach
    void tearDown() {
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(LEAD_SESSION);
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(OTHER_SESSION);
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
    @DisplayName("按会话注册后该会话桶非 null（生产确认表面 = Web STOMP）")
    void registerSetter_registersProductionSetterForSession() {
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION))
                .as("生产注册后本会话 dispatcher setter 非 null → leader inbox 权限请求不再恒 deny")
                .isNotNull();
    }

    @Test
    @DisplayName("[T2] 只注册 A 会话 ⇒ B 会话桶为空（跨会话不串台：表面各服务各会话）")
    void registerSetter_otherSessionStaysEmpty() {
        // WHY: 改前是进程级单槽 —— 注册一次即对<b>所有</b>会话可见，B 会话的权限请求会被推到
        //   A 的弹窗（跨会话表面串台）。本断言钉死「会话维度隔离」，改回单槽即 RED。
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);

        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION)).isNotNull();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(OTHER_SESSION))
                .as("未注册会话必须为空（⛔ 不借用其它会话的表面）").isNull();
    }

    @Test
    @DisplayName("[T2] 注销后该会话桶为空（注册/注销成对）")
    void unregisterSetter_clearsOnlyThatSession() {
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        bridge.registerSetter(OTHER_SESSION);

        assertThat(bridge.unregisterSetter(LEAD_SESSION)).as("本会话确有注册 → removed=true").isTrue();

        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION)).isNull();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(OTHER_SESSION))
                .as("邻会话表面不得被误摘").isNotNull();
    }

    @Test
    @DisplayName("无 SimpMessagingTemplate → 不注册 setter（CC useInboxPoller.ts:346-350 无表面丢弃语义）")
    void registerSetter_withoutWs_skips() {
        LeaderPermissionConfirmBridge noWs = new LeaderPermissionConfirmBridge();
        // ws 保持 null
        noWs.registerSetter(LEAD_SESSION);
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION))
                .as("ws null → setter 保持 null（无确认表面）").isNull();
    }

    @Test
    @DisplayName("onConfirmQueueUpdate 推新 entry → confirmEntries 含 toolUseId + STOMP 推送注册会话")
    void onConfirmQueueUpdate_pushesToStompLeaderSession() {
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-1", "Bash", Map.of("command", "ls"), allow, reject, abort);

        LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION).apply(queue -> {
            List<LeaderPermissionBridge.ToolUseConfirmEntry> next = new ArrayList<>(queue);
            next.add(entry);
            return next;
        });

        // STOMP 推送验证（目标会话 = 注册时闭合的会话）
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
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        Map<String, Object> input = Map.of("command", "ls");
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-2", "Bash", input, allow, reject, abort);
        bridge.onConfirmQueueUpdate(LEAD_SESSION, queue -> {
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
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        AtomicReference<Map<String, Object>> allow = new AtomicReference<>();
        AtomicReference<String> reject = new AtomicReference<>();
        AtomicReference<Boolean> abort = new AtomicReference<>(false);
        LeaderPermissionBridge.ToolUseConfirmEntry entry =
                entry("tool-use-3", "Bash", Map.of("command", "rm -rf"), allow, reject, abort);
        bridge.onConfirmQueueUpdate(LEAD_SESSION, queue -> {
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
        newBridgeWithWs();
        bridge.registerSetter(LEAD_SESSION);
        // 不推任何 entry
        boolean consumed = bridge.onResponse("main-loop-tool-block-id", "allow", List.of(),
                null, List.of(), null, null);
        assertThat(consumed).as("非本桥请求（主 loop ToolUseBlock.id）→ 不消费，交 prompter").isFalse();
    }
}
