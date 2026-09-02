package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreeEventLog;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [R5 结果驱动] Worktree hooks 工具层接线 — EnterWorktreeTool / ExitWorktreeTool。
 *
 * <p>WHY (规则九 · 验证意图): 09 §7.4 登记 — CC worktree.ts:716-740 createWorktreeForSession
 * 有 WorktreeCreate hook 配置 → executeWorktreeCreateHook(slug) 拿 worktreePath（hookBased，
 * 不调 git worktree）；无 hook → git worktree。worktree.ts:815-855 cleanupWorktree：
 * hookBased → executeWorktreeRemoveHook(worktreePath)，hookRan=true 跳过 git remove；
 * hookRan=false → warn 保留；非 hookBased → git worktree remove --force。
 *
 * <p>行为对齐点（CC 真源，非注释）:
 * <ul>
 *   <li>create hookBased: hooks.ts:4944-4947 第一个 succeeded && output.trim() 非空 →
 *       worktreePath = output.trim()；:4948-4956 无成功 → throw 'WorktreeCreate hook failed'</li>
 *   <li>create 输出: EnterWorktreeTool.ts:40-44 outputSchema worktreeBranch optional —
 *       hookBased 无 git 分支，省略该键</li>
 *   <li>remove hookBased: worktree.ts:817-826 hookRan → 移除；:827-830 无 WorktreeRemove hook
 *       → warn 保留（不 git remove）</li>
 * </ul>
 *
 * @see EnterWorktreeTool
 * @see ExitWorktreeTool
 * @since R5 结果驱动 worktree hooks 接线
 */
@DisplayName("[R5] Worktree hooks 工具层接线（EnterWorktreeTool / ExitWorktreeTool）")
class WorktreeToolHooksTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd(SESSION.toString());
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearWorktreeSession(SESSION.toString());
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearOriginalCwd(SESSION.toString());
    }

    /**
     * 注册当前会话 worktree 会话对象 — IMP-F1 会话作用域守卫：ExitWorktreeTool 只操作
     * {@code WorktreeCwdTracker.getWorktreeSession(sessionKey)} 返回的会话 worktree（对齐 CC
     * {@code getCurrentWorktreeSession()}）。直调 execute 前必须先登记，否则守卫返回 no-op。
     *
     * @param slug 会话 worktree 名（工作 treeName，对齐 CC WorktreeSession.worktreeName）
     */
    private static void registerSession(String slug) {
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setWorktreeSession(SESSION.toString(),
            new com.nexusai.application.agent.worktree.WorktreeCwdTracker.WorktreeSession(
                "/tmp/worktrees/" + slug, "worktree-" + slug, slug, false, SESSION.toString()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // EnterWorktreeTool · create
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC worktree.ts:720-733 — hasWorktreeCreateHook() → executeWorktreeCreateHook(slug)
     * → worktreePath 来自 hook stdout（trim），hookBased=true，不调 git createWorktree。
     * 输出 worktreeBranch 省略（CC outputSchema optional）。
     */
    @Test
    @DisplayName("有 WorktreeCreate hook → 创建走 hook stdout 路径（不调 git createWorktree）")
    void createHookBased_usesHookStdoutPath() throws Exception {
        HookRegistry registry = new HookRegistry();
        registry.register("wt-create", event -> successWithStdout("/tmp/worktrees/feature-x\n"));
        WorktreeService service = new WorktreeService((WorktreeEventLog) null);
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(enterCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode out = JSON.readTree((String) result.data());
        assertThat(out.path("worktreePath").asText())
            .as("hook stdout trim 即 worktreePath（CC worktree.ts:724-727 hookResult.worktreePath）")
            .isEqualTo(Path.of("/tmp/worktrees/feature-x").toString()); // Windows 分隔符无关断言（断点 1ce82468 既有失败修复）
        assertThat(out.has("worktreeBranch"))
            .as("hookBased 无 git 分支 — CC outputSchema worktreeBranch optional，省略")
            .isFalse();
        assertThat(out.path("message").asText())
            .as("消息标记 hook-based 路径")
            .contains("hook-based");
        assertThat(com.nexusai.application.agent.worktree.WorktreeCwdTracker.getCwd(SESSION.toString()))
            .as("session cwd 覆盖到 hook 路径（对齐 CC utils/worktree.ts:156 currentWorktreeSession）")
            .isEqualTo(Path.of("/tmp/worktrees/feature-x"));
    }

    /**
     * WHY: CC worktree.ts:735-778 — 无 WorktreeCreate hook 配置 → git worktree 路径不变
     * （回归守卫：hook 接线不得破坏既有 git 创建）。
     */
    @Test
    @DisplayName("无 WorktreeCreate hook → git 路径（mock WorktreeService，验证 createWorktree 被调）")
    void createNoHook_usesGitPath() throws Exception {
        HookRegistry registry = new HookRegistry(); // 无任何注册 → hasHookForEvent false
        WorktreeService service = mock(WorktreeService.class);
        Path wt = Path.of("/repo/.nexusai/worktrees/feature-x");
        when(service.createWorktree(any(), eq("feature-x")))
            .thenReturn(new WorktreeCreateResult.Created(wt, "feature-x", Path.of("/repo")));
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(enterCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        verify(service).createWorktree(any(), eq("feature-x"));
        JsonNode out = JSON.readTree((String) result.data());
        assertThat(out.path("worktreePath").asText()).isEqualTo(wt.toString());
        assertThat(out.path("worktreeBranch").asText())
            .as("git 路径输出分支名（CC worktreeSession.worktreeBranch）")
            .isEqualTo("feature-x");
    }

    /**
     * WHY: CC hooks.ts:4948-4956 — 无成功输出 → throw 'WorktreeCreate hook failed: ...'；
     * 工具层必须把该错误转为工具错误（对齐 CC throw，错误留给 LLM 自纠）。
     */
    @Test
    @DisplayName("WorktreeCreate hook 全失败 → 工具错误（对齐 CC throw 'WorktreeCreate hook failed'）")
    void createHookFails_returnsToolError() {
        HookRegistry registry = new HookRegistry();
        registry.register("wt-fail", event -> failureWithStderr("git worktree add failed"));
        WorktreeService service = new WorktreeService((WorktreeEventLog) null);
        EnterWorktreeTool tool = new EnterWorktreeTool(service);
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(enterCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(String.valueOf(result.data()))
            .as("hook 失败明细透传（CC :4951-4956 failedOutputs）")
            .contains("WorktreeCreate hook failed")
            .contains("git worktree add failed");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ExitWorktreeTool · remove / keep
    // ════════════════════════════════════════════════════════════════════════

    /**
     * WHY: CC worktree.ts:817-826 — hookBased remove → executeWorktreeRemoveHook(worktreePath)，
     * hookRan=true → 跳过 git remove；hookInput.worktree_path = hook 创建时的真实路径
     * （非 slug 推导路径）。
     */
    @Test
    @DisplayName("hookBased remove + WorktreeRemove hook 配置 → 走 remove hook，跳过 git remove")
    void removeHookBased_skipsGitRemove() {
        registerSession("feature-x");
        WorktreeService service = mock(WorktreeService.class);
        when(service.consumeHookBasedWorktree("feature-x"))
            .thenReturn(Path.of("/tmp/worktrees/feature-x"));
        HookRegistry registry = new HookRegistry();
        AtomicReference<HookEvent> captured = new AtomicReference<>();
        registry.register("wt-remove", event -> {
            captured.set(event);
            return GenericHook.HookResult.proceed();
        });
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(exitRemoveCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(captured.get().type()).isEqualTo(HookEventType.WORKTREE_REMOVE);
        assertThat(captured.get().data())
            .as("CC hookInput.worktree_path 透传（hook 创建时的真实路径）")
            .containsEntry("worktree_path", Path.of("/tmp/worktrees/feature-x").toString()); // Windows 分隔符无关断言（断点 1ce82468 既有失败修复）
        verify(service, never()).removeWorktree(any(), any(), anyBoolean());
        assertThat(String.valueOf(result.data()))
            .as("消息标记 hook-based 移除")
            .contains("hook-based");
    }

    /**
     * WHY: CC worktree.ts:827-830 — hookBased remove 但无 WorktreeRemove hook 配置 →
     * hookRan=false → warn 'No WorktreeRemove hook configured, hook-based worktree left'，
     * 保留 worktree，绝不 fallback 到 git remove（worktree 不在 git 登记中，git remove 必失败）。
     */
    @Test
    @DisplayName("hookBased remove + 无 WorktreeRemove hook → hookRan=false，warn 保留（不 git remove）")
    void removeHookBased_noRemoveHook_leavesWorktree() {
        registerSession("feature-x");
        WorktreeService service = mock(WorktreeService.class);
        when(service.consumeHookBasedWorktree("feature-x"))
            .thenReturn(Path.of("/tmp/worktrees/feature-x"));
        HookRegistry registry = new HookRegistry(); // 无 WorktreeRemove 配置 → executeWorktreeRemoveHook false
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        tool.setHookRegistry(registry);

        AgentToolResult<?> result = tool.execute(exitRemoveCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(String.valueOf(result.data()))
            .as("CC :827-830 'hook-based worktree left' 语义透传")
            .contains("left")
            .contains("no WorktreeRemove hook configured");
        verify(service, never()).removeWorktree(any(), any(), anyBoolean());
    }

    /**
     * WHY: CC worktree.ts:834-855 — 非 hookBased → git worktree remove --force + branch -D
     * （回归守卫：hook 接线不得破坏既有 git remove）。
     */
    @Test
    @DisplayName("非 hookBased remove → git worktree remove（mock WorktreeService，验证 removeWorktree 被调）")
    void removeNonHookBased_usesGitRemove() {
        registerSession("feature-x");
        WorktreeService service = mock(WorktreeService.class); // consumeHookBasedWorktree 默认 null
        when(service.countChanges(any(), any()))
            .thenReturn(new WorktreeService.WorktreeChanges(0, 0, false));
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        tool.setHookRegistry(new HookRegistry());

        AgentToolResult<?> result = tool.execute(exitRemoveCall("feature-x"), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        verify(service).removeWorktree(any(), eq("feature-x"), eq(false));
        // [IMP-C2 返工 R1 修正] 真实 remove 消息对齐 CC ExitWorktreeTool.ts:318
        //   "Exited and removed worktree at ${path}... Session is now back in ${cwd}"（原 "removed worktree + branch"
        //   为陈旧文案，与 CC 实际消息不符；且非 hookBased 路径 data 为 String 而非 Map，summary 直返 data）。
        assertThat(summary(result)).contains("Exited and removed worktree");
    }

    /**
     * WHY: CC keepWorktree (worktree.ts:780-799) 清 currentWorktreeSession — hookBased 登记
     * 必须同步消费清除，否则后续 remove 会把已 keep 的 hook worktree 误判为 hookBased 再删。
     */
    @Test
    @DisplayName("hookBased keep → 消费登记（worktree 保留，不 git remove）")
    void keepHookBased_clearsRegistration() {
        registerSession("default");
        WorktreeService service = mock(WorktreeService.class);
        ExitWorktreeTool tool = new ExitWorktreeTool(service);
        tool.setHookRegistry(new HookRegistry());

        AgentToolResult<?> result = tool.execute(keepCall(), ctx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        verify(service).keepWorktree(any(), eq("default"));
        verify(service).consumeHookBasedWorktree(eq("default"));
        verify(service, never()).removeWorktree(any(), any(), anyBoolean());
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    private static ToolUseBlock enterCall(String name) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("name", name);
        return new ToolUseBlock("enter-" + name, EnterWorktreeTool.NAME, input);
    }

    // [IMP-F1 / DC-F1-01] ExitWorktree 无 name 参数（会话作用域守卫，slug 从 getWorktreeSession 解析）。
    //   name 仅作 callId 区分；会话 worktree 由各测试 registerSession(slug) 登记。
    private static ToolUseBlock exitRemoveCall(String name) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("action", "remove");
        return new ToolUseBlock("exit-remove-" + name, ExitWorktreeTool.NAME, input);
    }

    private static ToolUseBlock keepCall() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("action", "keep");
        return new ToolUseBlock("exit-keep", ExitWorktreeTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT);
    }

    /** 对齐 WorktreeHooksTest 同款构造（CC :3336-3340 exit 0 → stdout）。 */
    private static GenericHook.HookResult successWithStdout(String stdout) {
        return new GenericHook.HookResult(false, null, null, null,
        AttachmentMessageDto.hookSuccess("WorktreeCreate:wt", "tu-wt", "WorktreeCreate",
            "", stdout, null, 0, "echo path", 5L),
        null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
    }

    /** [IMP-C2] successWithStructuredOutput 折入 data(Map) 后，模型侧渲染文本在 "summary" 键。 */
    private static String summary(AgentToolResult<?> result) {
        Object data = result.data();
        if (data instanceof Map<?, ?> m && m.containsKey("summary")) {
            return String.valueOf(m.get("summary"));
        }
        return String.valueOf(data);
    }

    /** 对齐 WorktreeHooksTest 同款构造（CC :3336-3340 失败 → stderr）。 */
    private static GenericHook.HookResult failureWithStderr(String stderr) {
        return new GenericHook.HookResult(false, null, null, null,
        AttachmentMessageDto.hookNonBlockingError("WorktreeCreate:wt-fail", "tu-1",
            "WorktreeCreate", stderr, "", 1),
        null, null, null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
    }
}
