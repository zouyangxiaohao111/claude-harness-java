package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [EX-HOOK R5/R7] Worktree hooks（executeWorktreeCreateHook / executeWorktreeRemoveHook）
 * + 工具事件 sessionId=agentId。
 *
 * <p>WHY (规则九 · 验证意图):
 * <ul>
 *   <li>R5: CC hooks.ts:4928-4958 executeWorktreeCreateHook — 第一个 succeeded &&
 *       output.trim() 非空的结果 → worktreePath = output.trim()；无 → throw
 *       'WorktreeCreate hook failed: ...'（:4951-4956）。CC hooks.ts:4967-5003
 *       executeWorktreeRemoveHook — 双源（settings + registered）无配置 → false；
 *       结果空 → false；逐 result 失败 log；返回 true。</li>
 *   <li>R7: CC hooks.ts:2003 sessionId = toolUseContext?.agentId ?? getSessionId() —
 *       子代理工具事件 session_id=agentId（SessionHookStore 按 agentId 注册的 session
 *       hooks 才能命中匹配/查询）；主线程回退主会话。</li>
 * </ul>
 */
@DisplayName("[EX-HOOK R5/R7] Worktree hooks + 工具事件 sessionId=agentId")
class WorktreeHooksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GenericHook.HookResult successWithStdout(String stdout) {
        return new GenericHook.HookResult(false, null, null, null,
        AttachmentMessageDto.hookSuccess("WorktreeCreate:wt", "tu-wt", "WorktreeCreate",
            "", stdout, null, 0, "echo path", 5L),
        null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
    }

    private static GenericHook.HookResult failureWithStderr(String stderr) {
        return new GenericHook.HookResult(false, null, null, null,
        AttachmentMessageDto.hookNonBlockingError("WorktreeCreate:wt-fail", "tu-1",
            "WorktreeCreate", stderr, "", 1),
        null, null, null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // R5 · executeWorktreeCreateHook
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC hooks.ts:4944-4947 — 第一个 succeeded && output.trim() 非空的结果，
     * worktreePath = output.trim()（hook stdout 即裸路径）。
     */
    @Test
    @DisplayName("create: 成功输出 → worktreePath = stdout.trim()（CC hooks.ts:4944-4947）")
    void createHook_successOutput_returnsWorktreePath() {
        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("wt-create", event -> {
            captured.set(event);
            return successWithStdout("/tmp/worktrees/feature-x\n");
        });

        String path = registry.executeWorktreeCreateHook("feature-x");

        assertThat(path)
            .as("hook stdout trim 即 worktree path（CC successfulResult.output.trim()）")
            .isEqualTo("/tmp/worktrees/feature-x");
        assertThat(captured.get().type()).isEqualTo(HookEventType.WORKTREE_CREATE);
        assertThat(captured.get().data())
            .as("CC hookInput.name 透传")
            .containsEntry("name", "feature-x");
    }

    /**
     * WHY: 多 hook 时取第一个成功且输出非空的结果（CC results.find），非最后一个。
     */
    @Test
    @DisplayName("create: 首个失败 + 次个成功 → 取成功者输出")
    void createHook_firstFailSecondSuccess_usesSuccess() {
        HookRegistry registry = new HookRegistry();
        registry.register("wt-fail", event -> failureWithStderr("git worktree add failed"));
        registry.register("wt-ok", event -> successWithStdout("/tmp/worktrees/ok-wt"));

        String path = registry.executeWorktreeCreateHook("ok-wt");

        assertThat(path).isEqualTo("/tmp/worktrees/ok-wt");
    }

    /**
     * WHY: CC hooks.ts:4948-4956 — 无成功输出 → throw 'WorktreeCreate hook failed: ...'
     * 含失败明细（command: output.trim() || 'no output'）。
     */
    @Test
    @DisplayName("create: 全失败 → throw IllegalArgumentException 含失败明细（CC hooks.ts:4951-4956）")
    void createHook_allFail_throwsWithDetails() {
        HookRegistry registry = new HookRegistry();
        registry.register("wt-fail", event -> failureWithStderr("git worktree add failed"));

        assertThatThrownBy(() -> registry.executeWorktreeCreateHook("feature-x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WorktreeCreate hook failed")
            .hasMessageContaining("git worktree add failed");
    }

    // ════════════════════════════════════════════════════════════════════════
    // R5 · executeWorktreeRemoveHook
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC hooks.ts:4973-4978 — snapshotHooks + registeredHooks 双源空 → false
     * （无配置不执行，调用方走 git-worktree 默认路径）。
     */
    @Test
    @DisplayName("remove: 无配置 → false（CC hooks.ts:4973-4978）")
    void removeHook_noConfig_returnsFalse() {
        HookRegistry registry = new HookRegistry();

        assertThat(registry.executeWorktreeRemoveHook("/tmp/worktrees/feature-x")).isFalse();
    }

    /**
     * WHY: CC hooks.ts:4984-5001 — 有配置 → 执行（results 非空）→ 逐失败 log → true。
     */
    @Test
    @DisplayName("remove: 有配置且执行 → true（CC hooks.ts:4984-5001）")
    void removeHook_configured_returnsTrue() {
        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("wt-remove", event -> {
            captured.set(event);
            return GenericHook.HookResult.proceed();
        });

        assertThat(registry.executeWorktreeRemoveHook("/tmp/worktrees/feature-x")).isTrue();
        assertThat(captured.get().type()).isEqualTo(HookEventType.WORKTREE_REMOVE);
        assertThat(captured.get().data())
            .as("CC hookInput.worktree_path 透传")
            .containsEntry("worktree_path", "/tmp/worktrees/feature-x");
    }

    // ════════════════════════════════════════════════════════════════════════
    // R7 · CC 双轨语义：载荷 session_id 恒主会话；匹配 key = agentId ?? sessionId
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC 双轨（hooks.ts:315 载荷 createBaseHookInput 无 sessionId 参数 → 主会话；
     * hooks.ts:2003 匹配 key = toolUseContext?.agentId ?? getSessionId()）。子代理
     * 工具事件：载荷 session_id = 主会话（子代理身份只进 agent_id 字段），匹配 key
     * 用 agentId（SessionHookStore key=agentId 的 frontmatter hooks 才能命中）。
     */
    @Test
    @DisplayName("子代理 TUC（agentId 非 null）→ 载荷 session_id=主会话、agent_id=agentId")
    void subagentToolEvent_payloadSessionIdIsMainSession_agentIdFieldIsAgentId() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("capture", event -> {
            captured.set(event);
            return GenericHook.HookResult.proceed();
        });
        ToolUseContext ctx = ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);

        registry.executePreToolUse("Bash", JSON.createObjectNode().put("k", "v"), ctx,
            "tu-1");

        assertThat(captured.get().sessionId())
            .as("载荷 session_id 恒主会话（CC createBaseHookInput 无 sessionId 参数 → getSessionId()）")
            .isEqualTo(sessionId.toString());
        assertThat(captured.get().agentId())
            .as("子代理身份进 agent_id 字段（CC createBaseHookInput agentInfo.agentId）")
            .isEqualTo(agentId.toString());
    }

    /**
     * WHY: 匹配 key = agentId ?? sessionId（CC hooks.ts:2003）——按 agentId 注册的
     * session hooks（frontmatter hooks, SessionHookStore key=agentId）在子代理
     * 工具事件下必须命中。
     */
    @Test
    @DisplayName("子代理工具事件 → 按 agentId 注册的 session hooks 命中（匹配 key=agentId）")
    void subagentToolEvent_sessionHooksMatchByAgentId() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = UUID.randomUUID();
        HookRegistry registry = new HookRegistry();
        registry.addSessionHook(agentId.toString(), HookEventType.PRE_TOOL_USE, "Bash",
            new CommandHook("echo session-hook", null, null, null, null, null, null, null),
            null, null);
        // 载荷 session_id=主会话、agent_id=agentId（CC 双轨，与工具执行路径一致）
        HookEvent event = HookEvent.toolPre("Bash", JSON.createObjectNode().put("k", "v"),
            sessionId.toString(), agentId.toString(), "tu-1");

        // [IMP-HR-07 · OPD-WF6-01-05 测试调和] isSessionHookEligible 要求事件 ∈ CC appState 发射点
        // 集合 且 会话活跃（LlmAgentLoop.isSessionRunning）。子代理事件匹配 key=agentId（CC hooks.ts:2003
        // agentId ?? getSessionId），但活跃判定以事件 sessionId（主会话）为准——子代理运行于主会话 agent
        // 循环内，主会话必在 RUNNING_SESSIONS。markRunning 建立该状态（对齐 HookRegistryTest 6a 同款 seam）。
        LlmAgentLoop.markRunning(sessionId);
        try {
            List<MatchedHook> matched = registry.getMatchingHooks(event);

            assertThat(matched)
                .as("按 agentId 注册的 session hook 在子代理工具事件被命中（CC hooks.ts:2003 匹配 key=agentId ?? getSessionId()）")
                .anySatisfy(m -> assertThat(m.hookSource()).isEqualTo("settings"));
        } finally {
            LlmAgentLoop.markIdle(sessionId);
        }
    }

    /**
     * WHY: 主线程（agentId null）→ 载荷 session_id = 主会话（CC ?? getSessionId() 回退分支）。
     */
    @Test
    @DisplayName("主线程 TUC（agentId null）→ 事件 session_id = sessionId（CC ?? getSessionId()）")
    void mainThreadToolEvent_sessionIdIsSessionId() {
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HookRegistry registry = new HookRegistry();
        AtomicReference<String> capturedSession = new AtomicReference<>();
        registry.register("capture", event -> {
            capturedSession.set(event.sessionId());
            return GenericHook.HookResult.proceed();
        });
        // Java 主线程惯例: agentId == sessionId（LlmAgentLoop.deriveQuerySource 同款判定）。
        ToolUseContext ctx = ToolUseContext.of(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);

        registry.executePreToolUse("Bash", JSON.createObjectNode().put("k", "v"), ctx,
            "tu-1");

        assertThat(capturedSession.get())
            .as("主线程工具事件 session_id=主会话（CC ?? getSessionId() 回退）")
            .isEqualTo(sessionId.toString());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S5 H4 / D-06b] hasWorktreeCreateHook · 对齐 CC hooks.ts:4910-4920
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: 源1 — settings 配置快照非空 → true（CC :4911-4912
     * {@code getHooksConfigFromSnapshot()?.['WorktreeCreate']}）。
     */
    @Test
    @DisplayName("hasWorktreeCreateHook: settings 快照源命中 → true（CC hooks.ts:4911-4912）")
    void hasWorktreeCreateHook_snapshotSource_returnsTrue() {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.WORKTREE_CREATE,
                new CommandHook("echo /tmp/wt", null, null, null, null, null, null, null),
                "*", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);

        assertThat(registry.hasWorktreeCreateHook()).isTrue();
    }

    /**
     * WHY: 源2 — registered programmatic hooks（null/空 filter = 全部事件）→ true
     * （CC :4913-4914 getRegisteredHooks()?.['WorktreeCreate']）。
     */
    @Test
    @DisplayName("hasWorktreeCreateHook: registered 源命中 → true（CC hooks.ts:4913-4914）")
    void hasWorktreeCreateHook_registeredSource_returnsTrue() {
        HookRegistry registry = new HookRegistry();
        registry.register("wt-create", event -> GenericHook.HookResult.proceed(),
            HookEventType.WORKTREE_CREATE);

        assertThat(registry.hasWorktreeCreateHook()).isTrue();
    }

    /**
     * WHY: managedOnly 时 registered 源排除 plugin hooks（CC :4915-4919
     * {@code !(managedOnly && 'pluginRoot' in matcher)}）—— plugin hook 在 execution 层被跳过，
     * gate 必须镜像过滤，否则 executeWorktreeCreateHook 空执行 throw 阻塞 git-worktree 回退。
     */
    @Test
    @DisplayName("hasWorktreeCreateHook: managedOnly → plugin hook 排除（false），SDK 回调保留（true）")
    void hasWorktreeCreateHook_managedOnly_filtersPluginHooks() {
        HookRegistry registry = new HookRegistry();
        registry.registerPluginHook("plugin:demo:WorktreeCreate", "demo",
            event -> GenericHook.HookResult.proceed(), HookEventType.WORKTREE_CREATE);
        // managedOnly 由 policySettings.allowManagedHooksOnly 注入（HooksSettings 构造 supplier）
        HooksSettings settings = new HooksSettings(
            key -> "allowManagedHooksOnly".equals(key) ? Boolean.TRUE : null);
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        registry.setHooksConfigSnapshot(snapshot);

        assertThat(registry.hasWorktreeCreateHook())
            .as("managedOnly + 仅 plugin hook → false（CC managedOnly 过滤）")
            .isFalse();

        // SDK 回调（非 plugin）→ managedOnly 下仍命中
        registry.register("sdk-wt-create", event -> GenericHook.HookResult.proceed(),
            HookEventType.WORKTREE_CREATE);
        assertThat(registry.hasWorktreeCreateHook()).isTrue();
    }

    /**
     * WHY (D-06b RED 条件): 仅 session hook 配置 → gate 必须 false（CC hasWorktreeCreateHook
     * 无 session 源；旧三源门控（WorktreeCreate 事件 + sessionKey）误报 true →
     * executeWorktreeCreateHook 空执行 throw，本应走 git worktree 回退）。
     */
    @Test
    @DisplayName("hasWorktreeCreateHook: 仅 session hook 配置 → false（D-06b，session 源不参与）")
    void hasWorktreeCreateHook_sessionOnly_returnsFalse() {
        HookRegistry registry = new HookRegistry();
        registry.addSessionHook("sess-1", HookEventType.WORKTREE_CREATE, "*",
            new CommandHook("echo session-wt", null, null, null, null, null, null, null),
            null, null);

        assertThat(registry.hasWorktreeCreateHook()).isFalse();
    }

    /**
     * WHY: 无任何源 → false（CC 双源空 → false）。
     */
    @Test
    @DisplayName("hasWorktreeCreateHook: 无配置 → false")
    void hasWorktreeCreateHook_nothing_returnsFalse() {
        HookRegistry registry = new HookRegistry();

        assertThat(registry.hasWorktreeCreateHook()).isFalse();
    }
}
