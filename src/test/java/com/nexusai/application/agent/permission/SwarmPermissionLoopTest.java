package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.application.agent.team.SwarmLeaderPermissionDispatcher;
import com.nexusai.application.agent.team.SwarmPermissionSync;
import com.nexusai.application.agent.team.TeammateMailbox;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * swarm 权限请求-响应回环闭环测试（REV-FIX-6 / WF-3 缝隙4 + ML-3 队列推送形态）。
 *
 * <p><b>验证意图（规则九）</b>：REV-FIX-6 之前，worker 经 mailbox 发出的权限请求无任何 leader
 * 侧对端（{@code sendPermissionResponseViaMailbox} / {@code resolvePermission} /
 * {@code processMailboxPermissionResponse} 均 0 调用方）——请求发出去即悬挂，mailbox 响应永不被
 * 消费。若实现回退为 no-op stub（恒不消费 / 恒不响应），本回环测试必红。
 *
 * <p>对齐 CC 活动回环（纯 mailbox 通道）：worker 侧 {@code swarmWorkerHandler.ts:81-123}
 * registerCallback → sendPermissionRequestViaMailbox；leader 侧 {@code useInboxPoller.ts:250-364}
 * getLeaderToolUseConfirmQueue → 构建 ToolUseConfirm（onAllow/onReject/onAbort →
 * sendPermissionResponseViaMailbox）→ setter 队列推送（dedup by toolUseID）；worker 侧
 * {@code useInboxPoller.ts:366-397} processMailboxPermissionResponse。
 *
 * <p><b>ML-3</b>：leader 侧对齐 CC 队列推送形态（{@link LeaderPermissionBridge.SetToolUseConfirmQueueFn}
 * updater 函数 + dedup），删除 Java-only {@code LeaderConfirmHandler}/{@code LeaderDecision}
 * （一次性 ask→future 模型）。测试经捕获式 queue setter 拿取 dispatcher 推送的
 * {@link LeaderPermissionBridge.ToolUseConfirmEntry}，手动触发 onAllow 模拟 leader 批准。
 */
@DisplayName("swarm 权限请求-响应回环闭环（REV-FIX-6 / ML-3）")
class SwarmPermissionLoopTest {

    @TempDir
    Path tempConfigHome;

    private static final String WORKER = "worker-a";
    private static final String TEAM = "my-team";

    /**
     * [T2 · 会话分桶] 本 team 的 leader 会话 = leader ToolUseConfirm setter 的桶键
     * （生产由 TeamCreateTool 落盘进 config.json {@code leadSessionId} 并据此注册；
     * 本测试写进 config + 用同键注册，两侧同键是分桶生效的前提）。
     */
    private static final String LEAD_SESSION = "sess-lead-t2-0001";

    /** 捕获 dispatcher 推送的 ToolUseConfirm 队列（模拟 React 状态队列）。 */
    private final AtomicReference<List<LeaderPermissionBridge.ToolUseConfirmEntry>> capturedQueue =
            new AtomicReference<>();

    /** dispatcher（[T2] 需注入 TeamHelpers 以解析 leadSessionId 桶键）。 */
    private SwarmLeaderPermissionDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("nexusai.task.config-dir", tempConfigHome.toString());
        System.setProperty("nexusai.team.name", TEAM);
        // swarms opt-in（isAgentSwarmsEnabled 的 sysprop-override seam，agentSwarmsEnabled.ts:32）
        System.setProperty("nexusai.experimental.agent-teams", "true");
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(LEAD_SESSION);
        SwarmPermissionPoller.clearAllPendingCallbacks();
        capturedQueue.set(null);
        dispatcher = new SwarmLeaderPermissionDispatcher();
        dispatcher.setTeamHelpers(new com.nexusai.application.agent.team.TeamHelpers());
        writeTeamConfig();
    }

    /** 写 team config：使 getLeaderName 经 {@code members.find(agentId === leadAgentId)}
     *  精确解析 leader 名 "team-lead"（对齐 CC permissionSync.ts:651-667），而非依赖旧 code path
     *  的 "team-lead" 兜底（ML-2 已删除：team 文件缺失 → 返回 null）。
     *  [T2] 同时落盘 {@code leadSessionId} —— dispatcher 据此定位本会话的确认表面桶。 */
    private void writeTeamConfig() throws Exception {
        Path teamDir = tempConfigHome.resolve("teams").resolve(TEAM);
        java.nio.file.Files.createDirectories(teamDir);
        String cfg = "{\"leadAgentId\":\"lead@my-team\",\"leadSessionId\":\"" + LEAD_SESSION + "\",\"members\":["
            + "{\"agentId\":\"lead@my-team\",\"name\":\"team-lead\"},"
            + "{\"agentId\":\"worker-a@my-team\",\"name\":\"worker-a\"}]}";
        java.nio.file.Files.writeString(teamDir.resolve("config.json"), cfg);
    }

    /** 注册捕获式 queue setter · 对齐 CC leaderPermissionBridge.ts:28-32 registerLeaderToolUseConfirmQueue
     *  （[T2] 按 leader 会话注册 —— 生产里等价物 = TeamCreateTool 建 team 成功后按 leadSessionId 注册）。 */
    private void registerCapturingQueueSetter() {
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue(LEAD_SESSION, updater ->
                capturedQueue.set(updater.apply(List.of())));
    }

    /** [T2 反面对照] 把捕获式 setter 注册到<b>另一个会话</b>（模拟「别的会话有确认表面」）。 */
    private void registerCapturingQueueSetterForOtherSession() {
        LeaderPermissionBridge.registerLeaderToolUseConfirmQueue("sess-other-9999", updater ->
                capturedQueue.set(updater.apply(List.of())));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.task.config-dir");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.agent.name");
        System.clearProperty("nexusai.agent.color");
        System.clearProperty("nexusai.experimental.agent-teams");
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue(LEAD_SESSION);
        LeaderPermissionBridge.unregisterLeaderToolUseConfirmQueue("sess-other-9999");
        SwarmPermissionPoller.clearAllPendingCallbacks();
    }

    private void asWorker() {
        System.setProperty("nexusai.agent.name", WORKER);
    }

    private void asLeader() {
        System.setProperty("nexusai.agent.name", "team-lead");
    }

    @Test
    @DisplayName("端到端回环：worker 请求→leader dispatch 推队列→模拟 onAllow→resolved/+mailbox 响应→worker pollMailboxResponses→onAllow")
    void loop_endToEnd() {
        asWorker();

        // 1. worker 侧：注册 callback + 写 pending + 发权限请求到 leader 邮箱
        //    （CC 双通道并存：useSwarmPermissionPoller 磁盘 resolved/ + useInboxPoller mailbox）
        String requestId = SwarmPermissionSync.generateRequestId();
        AtomicReference<Map<String, Object>> receivedInput = new AtomicReference<>();
        SwarmPermissionPoller.registerPermissionCallback(requestId, "tooluse-1",
            outcome -> receivedInput.set(outcome.updatedInput()),
            feedback -> { });

        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            requestId, "Bash", "tooluse-1", Map.of("command", "ls"), "run ls", List.of());
        SwarmPermissionSync.writePermissionRequest(req);
        boolean sent = SwarmPermissionSync.sendPermissionRequestViaMailbox(req);
        assertThat(sent).as("mailbox 发送必须成功").isTrue();
        assertThat(TeammateMailbox.readMailbox("team-lead", TEAM)).hasSize(1);

        // 2. leader 侧：注册捕获式队列 setter + dispatch 读邮箱 → 推 ToolUseConfirm 队列（对齐 CC useInboxPoller:250-364）
        asLeader();
        registerCapturingQueueSetter();
        int handled = dispatcher.dispatchOnce();
        assertThat(handled).as("leader 必须处理 1 条权限请求").isEqualTo(1);

        // dispatcher 必须推送 1 条 ToolUseConfirmEntry 到队列（dedup by toolUseId，CC :340-345）
        List<LeaderPermissionBridge.ToolUseConfirmEntry> queue = capturedQueue.get();
        assertThat(queue).as("leader 必须推送权限提示到 ToolUseConfirm 队列").isNotNull().hasSize(1);
        LeaderPermissionBridge.ToolUseConfirmEntry entry = queue.get(0);

        // 模拟 leader 批准 → onAllow(updatedInput, permissionUpdates)（CC :305-320）
        entry.onAllow().accept(Map.of("command", "ls -la"),
            List.of(new PermissionUpdate.AddDirectories(PermissionUpdate.Destination.SESSION, List.of("/workspace"))));

        // leader 决策双通道落地：resolved/（喂磁盘 poller）+ worker 邮箱 permission_response（mailbox 主通道）
        assertThat(SwarmPermissionSync.readResolvedPermission(requestId, TEAM)).isNotNull();
        assertThat(SwarmPermissionSync.readResolvedPermission(requestId, TEAM).status()).isEqualTo("approved");
        List<TeammateMailbox.TeammateMessage> workerInbox = TeammateMailbox.readMailbox(WORKER, TEAM);
        assertThat(workerInbox).hasSize(1);
        TeammateMailbox.PermissionResponseMessage resp = TeammateMailbox.isPermissionResponse(workerInbox.get(0).text());
        assertThat(resp).as("worker 邮箱必须收到 permission_response").isNotNull();
        assertThat(resp.subtype()).isEqualTo("success");

        // 3. worker 侧：pollMailboxResponses 消费 mailbox 响应 → onAllow(updatedInput)
        //    （对齐 CC useInboxPoller:366-397 + inProcessRunner:386-433）
        asWorker();
        new SwarmPermissionPoller().pollMailboxResponses();
        assertThat(receivedInput.get()).as("onAllow 必须收到 leader 的 updatedInput").isEqualTo(Map.of("command", "ls -la"));
        // 已标已读（对齐 CC markMessageAsReadByIndex :403）
        assertThat(TeammateMailbox.readMailbox(WORKER, TEAM).get(0).read()).isTrue();
    }

    @Test
    @DisplayName("集成路径：SwarmWorkerPermissionHandler.handle() 生产链 + leader dispatch 推队列 + onAllow → decisionFuture.complete(allow, permissionUpdates)")
    void loop_viaWorkerHandler() throws Exception {
        asWorker();

        // 生产构造器：真实 mailbox 发送 + 真实 callback 注册（防 race 先注册后发）
        SwarmWorkerPermissionHandler handler = new SwarmWorkerPermissionHandler(null);
        CompletableFuture<SwarmWorkerPermissionHandler.PermissionDecision> future = handler.handle(
            new SwarmWorkerPermissionHandler.Params("Bash", "tooluse-2", Map.of("command", "ls"),
                "run ls", Map.of("command", "ls"), null));
        assertThat(future).as("swarms 启用 + worker 上下文 → handle 必须非 null").isNotNull();

        // 捕获 worker 发到 leader 邮箱的请求 id
        List<TeammateMailbox.TeammateMessage> leaderInbox = TeammateMailbox.readMailbox("team-lead", TEAM);
        assertThat(leaderInbox).hasSize(1);
        TeammateMailbox.PermissionRequestMessage parsed = TeammateMailbox.isPermissionRequest(leaderInbox.get(0).text());
        assertThat(parsed).as("leader 邮箱必须收到 permission_request").isNotNull();

        // leader 侧：dispatch 推队列 + 模拟 onAllow（含 permissionUpdates —— [REV-FIX-6 gap3] 透传）
        asLeader();
        registerCapturingQueueSetter();
        dispatcher.dispatchOnce();
        List<LeaderPermissionBridge.ToolUseConfirmEntry> queue = capturedQueue.get();
        assertThat(queue).as("leader 必须推送权限提示到队列").isNotNull().hasSize(1);
        queue.get(0).onAllow().accept(Map.of("command", "ls -la"),
            List.of(new PermissionUpdate.AddDirectories(PermissionUpdate.Destination.SESSION, List.of("/workspace"))));

        // worker 侧：消费 mailbox 响应 → decisionFuture.complete(allow)
        asWorker();
        new SwarmPermissionPoller().pollMailboxResponses();
        SwarmWorkerPermissionHandler.PermissionDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision).isNotNull();
        assertThat(decision.behavior()).isEqualTo("allow");
        assertThat(decision.updatedInput()).isEqualTo(Map.of("command", "ls -la"));
        assertThat(decision.permissionUpdates()).as("allow 必须透传 leader 的 permissionUpdates（gap3）").hasSize(1);
    }

    @Test
    @DisplayName("无确认表面 → R1 降级自动 deny，不悬挂 worker（对齐 CC :346-350 + 免悬挂增强）")
    void loop_noSurface_autoDeny() {
        asWorker();
        SwarmPermissionPoller.registerPermissionCallback("perm-req-deny", "tooluse-3",
            outcome -> { }, feedback -> { });
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "perm-req-deny", "Bash", "tooluse-3", Map.of("command", "rm"), "rm -rf /", List.of());
        assertThat(SwarmPermissionSync.sendPermissionRequestViaMailbox(req)).isTrue();

        asLeader();
        // 不注册确认表面（queue setter）→ 自动 deny（R1：mailbox 请求无 STOMP 会话，不悬挂 worker）
        int handled = dispatcher.dispatchOnce();
        assertThat(handled).isEqualTo(1);

        List<TeammateMailbox.TeammateMessage> workerInbox = TeammateMailbox.readMailbox(WORKER, TEAM);
        assertThat(workerInbox).hasSize(1);
        TeammateMailbox.PermissionResponseMessage resp = TeammateMailbox.isPermissionResponse(workerInbox.get(0).text());
        assertThat(resp).as("auto-deny 必须回 error 响应").isNotNull();
        assertThat(resp.subtype()).isEqualTo("error");
    }

    @Test
    @DisplayName("[T2 跨会话隔离] 别的会话注册了确认表面 ⇒ 本 team 请求仍走自动 deny，⛔ 不得借用其表面")
    void loop_otherSessionSurface_isNotBorrowed() {
        // WHY（本任务要修的断裂）：改前 LeaderPermissionBridge 是**进程级单槽** —— 只要**任意**
        //   会话注册过表面，本 team 的权限请求就会被推到**那个会话**的弹窗（跨会话串台：用户看到的
        //   是别人会话的权限弹窗，而本会话 leader 永远等不到决策面 → 权限断裂）。
        //   改后按会话分桶：本 team 的桶键 = config.leadSessionId，别的会话的注册对本 team 不可见。
        asWorker();
        SwarmPermissionPoller.registerPermissionCallback("perm-req-x", "tooluse-x",
            outcome -> { }, feedback -> { });
        SwarmPermissionSync.SwarmPermissionRequest req = SwarmPermissionSync.createPermissionRequest(
            "perm-req-x", "Bash", "tooluse-x", Map.of("command", "rm"), "rm -rf /", List.of());
        assertThat(SwarmPermissionSync.sendPermissionRequestViaMailbox(req)).isTrue();

        asLeader();
        // 反面对照：把「有确认表面」的注册放到**另一个会话**（本 team 的 leadSessionId 桶仍空）
        registerCapturingQueueSetterForOtherSession();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(LEAD_SESSION))
            .as("前置：本 team 会话桶必须为空（对照有效的前提）").isNull();

        int handled = dispatcher.dispatchOnce();

        assertThat(handled).isEqualTo(1);
        assertThat(capturedQueue.get())
            .as("⛔ 不得把本 team 的请求推给别的会话的确认表面（改回单槽即 RED）").isNull();
        List<TeammateMailbox.TeammateMessage> workerInbox = TeammateMailbox.readMailbox(WORKER, TEAM);
        assertThat(workerInbox).hasSize(1);
        TeammateMailbox.PermissionResponseMessage resp =
            TeammateMailbox.isPermissionResponse(workerInbox.get(0).text());
        assertThat(resp).as("无本会话表面 → R1 自动 deny（不把 worker 悬挂在别人的弹窗上）").isNotNull();
        assertThat(resp.subtype()).isEqualTo("error");
    }
}
