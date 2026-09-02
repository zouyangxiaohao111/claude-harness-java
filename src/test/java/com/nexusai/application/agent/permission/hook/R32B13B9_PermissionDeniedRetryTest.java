package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b13 · B9 Permission Denied Retry Hook · 对齐 CC Open-ClaudeCode/src/utils/hooks.ts:2887-2892
 * {@code yield retry} + {@code utils/hooks.ts:3529-3562 executePermissionDeniedHooks} +
 * {@code services/tools/toolExecution.ts:1075-1101} retry hook 触发单测.
 *
 * <p><b>WHY (意图验证)</b>: b13 brief 对齐 CC classifier.auto-mode deny → 触发
 * PermissionDenied retry hook → hook 返回 retry=true → caller 注入 isMeta user message
 * 告诉 LLM 可以重试. 本测试验证 Java 端基础版核心契约:
 *
 * <ul>
 *   <li><b>B9.1 GenericHook.HookResult.retry 字段</b>: 13 字段 record + retry=null
 *       默认值 + withRetry() 工厂 + accessor retry() 返回 Boolean</li>
 *   <li><b>B9.2 PermissionDecisionReason.Classifier.classifier 字段</b>: 2 字段 record
 *       (classifier, reason); mode 字段按 DEL-WF1-01 已删除, 'auto-mode' 语义落入
 *       classifier 字段 (对齐 CC permissions.ts:304-306 classifier 变体, 无 mode 字段)</li>
 *   <li><b>B9.3 HookRegistry.executePermissionDeniedRetryCheck</b>: 同步执行
 *       PermissionDenied hook + 聚合 retry 标记</li>
 *   <li><b>B9.4 HookEvent.permissionDenied tool_use_id 透传</b> (P1 fix): hook
 *       接收的 event.data() map 必须包含 tool_use_id 键，与 CC PermissionDeniedHookInput
 *       契约对齐 — 0aaa662 commit 仅注释声称透传, 实际未写入, 本测试覆盖</li>
 * </ul>
 *
 * <h2>测试用例 (9 项)</h2>
 * <ol>
 *   <li>HookResult.withRetry() 工厂 retry=true → proceed() factory retry=null (B9.1 字段契约)</li>
 *   <li>HookResult 13 字段 ctor 全部到位 (P0-3 + H4 + H3 字段契约)</li>
 *   <li>Classifier 2-arg ctor → classifier 字段承载 'auto-mode' 触发 retry (B9.2 字段契约)</li>
 *   <li>executePermissionDeniedRetryCheck 无 hook 注册 → 返回 null (fast path)</li>
 *   <li>executePermissionDeniedRetryCheck hook 返回 withRetry() → 返回 true (retry 消费)</li>
 *   <li>executePermissionDeniedRetryCheck hook 返回 proceed() → 返回 null (不消费 retry)</li>
 *   <li>executePermissionDeniedRetryCheck hook throw 异常 → 兜底返回 null (异常 best-effort)</li>
 *   <li><b>[R32-b13 B9 fix]</b> hook 接收的 event.data() 含 tool_use_id 键
 *       (P1 修复: toolUseId 实际写入 HookEvent)</li>
 *   <li>5-arg HookEvent.permissionDenied 委派 → toolUseId=null 不写入 data map
 *       (向后兼容 WebSocketPermissionPrompter 5-arg 调用)</li>
 * </ol>
 */
class R32B13B9_PermissionDeniedRetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HookRegistry hookRegistry;

    @BeforeEach
    void setUp() {
        hookRegistry = new HookRegistry();
    }

    private ToolUseContext createCtx() {
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    private JsonNode createInput() {
        return JSON.createObjectNode().put("command", "ls -la");
    }

    // ─────────── B9.1 GenericHook.HookResult.retry 字段 ───────────

    @Test
    @DisplayName("B9.1.1 HookResult.withRetry() → retry=true; proceed() → retry=null (字段契约)")
    void hookResultRetryField_contract() {
        // proceed(): retry 字段默认 null (向后兼容老代码)
        GenericHook.HookResult proceed = GenericHook.HookResult.proceed();
        assertThat(proceed.retry()).isNull();
        assertThat(proceed.preventContinuation()).isFalse();

        // withRetry(): retry=true (R32-b13 B9 新工厂, 对齐 CC yield {retry: true})
        GenericHook.HookResult retry = GenericHook.HookResult.withRetry();
        assertThat(retry.retry()).isTrue();
        assertThat(retry.preventContinuation()).isFalse();
        // withRetry() 不阻止 continuation (与 CC 行为一致: retry 是允许, 不是阻止)
        assertThat(retry.stopReason()).isNull();
    }

    @Test
    @DisplayName("[P0-3 + H4 + H3 + S07] HookResult ctor → 14 字段全部到位 (H3 改 additionalContext 单值 String + 加 hook; S07 加 permissionRequestResult)")
    void hookResultP03Fields_contract() {
        // [H4+H3+S07] HookResult 14 字段 (H3 改 additionalContext 单值 String + 新增 hook 字段;
        //   S07 新增 permissionRequestResult 顶层回填)
        // 字段顺序: preventContinuation, blockingError(HookBlockingError), systemMessage,
        //   additionalContext (String), message (Object), updatedInput, updatedMCPToolOutput,
        //   retry, hookPermissionDecisionReason, outcome, stopReason, permissionBehavior,
        //   permissionRequestResult, hook, initialUserMessage, watchPaths,
        //   elicitationResponse, elicitationResultResponse
        GenericHook.HookResult full = new GenericHook.HookResult(true,                                              // preventContinuation
        new HookBlockingError("blocking error text", null),// blockingError [H4] HookBlockingError record
        List.of("sys msg"),                                // systemMessages [H-WF5a-02 折叠链项3] List
        List.of("ctx1"),                                   // additionalContexts [H-WF5a-02 折叠链项2] List
        "user msg",                                        // message (Object)
        null,                                              // updatedInput
        null,                                              // updatedMCPToolOutput
        null,                                              // retry
        "permissionReason",                                // hookPermissionDecisionReason
        GenericHook.HookOutcome.BLOCKING,                  // outcome
        "stop reason",                                     // stopReason
        null,                                              // permissionBehavior
        null,                                              // permissionRequestResult (S07)
        null,                                              // hook
        null, null, null, null);                           // +4 awaiting (2026-08-12 △-01)
        assertThat(full.preventContinuation()).isTrue();
        assertThat(full.stopReason()).isEqualTo("stop reason");
        assertThat(full.blockingError().blockingError()).isEqualTo("blocking error text");
        assertThat(full.systemMessages()).containsExactly("sys msg");
        // [H-WF5a-02] additionalContexts List 全保留 → 断言 List 值
        assertThat(full.additionalContexts()).containsExactly("ctx1");
        assertThat(full.message()).isEqualTo("user msg");
        assertThat(full.retry()).isNull();
        assertThat(full.hookPermissionDecisionReason()).isEqualTo("permissionReason");
        assertThat(full.permissionRequestResult())
            .as("S07: 未提供时 permissionRequestResult 必须为 null")
            .isNull();
    }

    // ─────────── B9.2 PermissionDecisionReason.Classifier.classifier 字段 ───────────

    @Test
    @DisplayName("B9.2.1 Classifier 2-arg ctor → classifier 字段承载 auto-mode (对齐 CC permissions.ts:304-306，无 mode 字段)")
    void classifierField_contract() {
        // [WF-1 DEL-WF1-01] 删除 mode 字段, auto-mode 语义落入 classifier 字段.
        //   完整"无 mode 字段/无 3-arg ctor"验证见 {@link PermissionDecisionReasonCompatCleanTest}.

        // auto-mode 分类器决策 → classifier 字段 == 'auto-mode' (CC 构造侧: classifierApprovals.ts:48
        //   CLASSIFIER_APPROVALS.set {classifier: 'auto-mode'}; type 变体: permissions.ts:304-306)
        PermissionDecisionReason.Classifier autoMode =
            new PermissionDecisionReason.Classifier("auto-mode", "dangerous command");
        assertThat(autoMode.classifier()).isEqualTo("auto-mode");
        assertThat(autoMode.reason()).isEqualTo("dangerous command");

        // bash_allow 等其他 classifier 值 → 不触发 retry hook (classifier != 'auto-mode')
        PermissionDecisionReason.Classifier bashAllow =
            new PermissionDecisionReason.Classifier("bash_allow", "dangerous command");
        assertThat(bashAllow.classifier()).isEqualTo("bash_allow");
    }

    // ─────────── B9.3 HookRegistry.executePermissionDeniedRetryCheck ───────────

    @Test
    @DisplayName("B9.3.1 无 hook 注册 → executePermissionDeniedRetryCheck 返回 null (fast path)")
    void noHookRegistered_returnsNull() {
        // 全新 HookRegistry → 无 hook
        Boolean retry = hookRegistry.executePermissionDeniedRetryCheck(
            "Bash", "call_001", createInput(), "permission denied",
            createCtx());

        // 无 hook → fast path 返回 null (与 CC hasHookForEvent(...)=false → return 等价)
        assertThat(retry).isNull();
    }

    @Test
    @DisplayName("B9.3.2 hook 返回 withRetry() → executePermissionDeniedRetryCheck 返回 true (retry 消费)")
    void hookReturnsRetry_returnsTrue() {
        // 注册一个 GenericHook, 返回 HookResult.withRetry()
        hookRegistry.register("retry-allow", event -> {
            // 验证事件类型 (防御性)
            assertThat(event.type()).isEqualTo(HookEventType.PERMISSION_DENIED);
            assertThat(event.toolName()).isEqualTo("Bash");
            // R32-b13 B9: hook 同意 retry → 返回 withRetry()
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        Boolean retry = hookRegistry.executePermissionDeniedRetryCheck(
            "Bash", "call_002", createInput(), "classifier denied dangerous command",
            createCtx());

        // hook 返回 retry=true → caller 消费后注入 isMeta user message 告诉 LLM 可以重试
        assertThat(retry).isTrue();
    }

    @Test
    @DisplayName("B9.3.3 hook 返回 proceed() → executePermissionDeniedRetryCheck 返回 null (不消费 retry)")
    void hookReturnsProceed_returnsNull() {
        hookRegistry.register("no-retry", event ->
            GenericHook.HookResult.proceed(), HookEventType.PERMISSION_DENIED);

        Boolean retry = hookRegistry.executePermissionDeniedRetryCheck(
            "Bash", "call_003", createInput(), "permission denied", createCtx());

        // proceed() → retry=null → caller 不注入 isMeta user message, 走原有 deny 路径
        assertThat(retry).isNull();
    }

    @Test
    @DisplayName("B9.3.4 hook throw 异常 → executePermissionDeniedRetryCheck 兜底返回 null (异常 best-effort)")
    void hookThrows_returnsNullDefensively() {
        hookRegistry.register("hook-throws", event -> {
            throw new RuntimeException("hook simulated failure");
        }, HookEventType.PERMISSION_DENIED);

        Boolean retry = hookRegistry.executePermissionDeniedRetryCheck(
            "Bash", "call_004", createInput(), "permission denied", createCtx());

        // 异常被 HookRegistry.executeEvent 吞掉 → executePermissionDeniedRetryCheck 拿到 null
        assertThat(retry).isNull();
    }

    // ─────────── B9.4 tool_use_id 透传 (P1 fix) ───────────

    @Test
    @DisplayName("B9.4.1 [P1 fix] hook 接收的 event.data() 含 tool_use_id 键 = call_use_005 (toolUseId 实际写入)")
    void hookReceivesToolUseIdInEventData() {
        // [R32-b13 B9 P1 fix] P1 reviewer 发现 0aaa662 commit 的 executePermissionDeniedRetryCheck
        // 接收 toolUseId 但实际未写入 HookEvent. 本测试验证修复后 hook 真的能从 event.data() 读到
        // tool_use_id (CC PermissionDeniedHookInput 契约字段).
        final String expectedToolUseId = "call_use_005";
        final String[] capturedToolUseId = new String[1];

        hookRegistry.register("tooluseid-capture", event -> {
            // 验证事件类型 + toolName (防御性断言)
            assertThat(event.type()).isEqualTo(HookEventType.PERMISSION_DENIED);
            assertThat(event.toolName()).isEqualTo("Bash");
            // [P1 fix 关键断言] tool_use_id 必须在 data map 中, 且值 = 传入的 expectedToolUseId
            assertThat(event.data())
                .as("[P1 fix] HookEvent.data map 必须包含 tool_use_id 键, 与 CC PermissionDeniedHookInput 对齐")
                .isNotNull()
                .containsKey("tool_use_id");
            Object actualToolUseId = event.data().get("tool_use_id");
            assertThat(actualToolUseId)
                .as("[P1 fix] tool_use_id 值必须 == 传入的 expectedToolUseId")
                .isEqualTo(expectedToolUseId);
            capturedToolUseId[0] = (String) actualToolUseId;
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        Boolean retry = hookRegistry.executePermissionDeniedRetryCheck(
            "Bash", expectedToolUseId, createInput(), "classifier denied dangerous command",
            createCtx());

        // 验证 retry=true (原契约) + tool_use_id 实际被 hook 读到 (P1 fix 验证)
        assertThat(retry).isTrue();
        assertThat(capturedToolUseId[0])
            .as("[P1 fix] hook 实际读到的 tool_use_id 必须 == 传入值 (闭环验证)")
            .isEqualTo(expectedToolUseId);
    }

    @Test
    @DisplayName("B9.4.2 [backward compat] 5-arg HookEvent.permissionDenied 委派 → tool_use_id 不写入 (向后兼容 WebSocket)")
    void hookEventPermissionDenied5Arg_delegatesBackwardCompat() {
        // [backward compat] WebSocketPermissionPrompter.java:347 调用 5-arg 版本, 传入 null 占位.
        // 5-arg 版本必须委派到 6-arg 版本, toolUseId=null → data map 不包含 tool_use_id 键.
        // WHY: 5-arg ctor 保留是为了不破坏现有 caller (WebSocketPermissionPrompter),
        //      该 caller 不持有 toolUseId, 不应写入空字符串/null 造成契约混淆.
        HookEvent event = HookEvent.permissionDenied(
            "Bash", createInput(), "User denied via WebSocket", null, null);

        // 验证: reason 字段必写入, tool_use_id 不写入 (null toolUseId → 跳过)
        assertThat(event.type()).isEqualTo(HookEventType.PERMISSION_DENIED);
        assertThat(event.data())
            .as("[backward compat] 5-arg ctor 委派, reason 必写入")
            .containsEntry("reason", "User denied via WebSocket")
            .as("[backward compat] 5-arg ctor (toolUseId=null) → 不写 tool_use_id 键, 避免契约混淆")
            .doesNotContainKey("tool_use_id");
    }
}