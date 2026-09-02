package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.hook.PermissionBehavior;
import com.nexusai.application.agent.permission.hook.ElicitationResponse;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [impl-I-4 T6 + F6 rework] URL elicitation 两阶段状态机测试（-32042 识别 → 请求 → 等待 → 用户门重试）。
 *
 * <p>WHY（规则九 + 反射 F6）：CC client.ts:2946-2996 两阶段中，<b>完成通知只激活「Retry now」按钮</b>，
 * 重试由用户 {@code onWaitingDismiss('retry')} 点击驱动；等待期 {@code showCancel:true} 用户可取消。
 * 旧实现（F3）accept 后完成通知<b>自动重试</b>（无用户二次确认门）且无等待期 cancel 通道——与 CC 相悖。
 * 本测试验意图：accept（Phase 1 同意）为 no-op 不触发重试；完成通知只启用按钮；重试必须用户点
 * {@code retryConfirm}（Retry now）/ 取消必须 {@code cancel}（Cancel，showCancel:true）。
 */
@DisplayName("[impl-I-4 T6/F6] elicitation 两阶段状态机（用户门重试）")
class McpElicitationStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompletableFuture<JsonNode> elicitationError(String elicitationId) {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("jsonrpc", "2.0").put("id", 1);
        ObjectNode error = err.putObject("error");
        error.put("code", -32042).put("message", "URL elicitation required");
        ObjectNode data = error.putObject("data");
        data.putArray("elicitations").addObject()
            .put("mode", "url")
            .put("url", "https://example.com/consent")
            .put("elicitationId", elicitationId)
            .put("message", "Please open URL");
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        f.completeExceptionally(new IllegalStateException("JSON-RPC error: " + err.path("error")));
        return f;
    }

    private static CompletableFuture<JsonNode> successResult(String text) {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        return CompletableFuture.completedFuture(result);
    }

    // ═══════════ 1. -32042 → 无响应器 → auto-decline（fail-closed）═══════════

    @Test
    @DisplayName("-32042 → 无响应器 auto-decline → decline 文本（fail-closed，不悬挂）")
    void elicitation_noResponder_autoDecline() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        McpElicitationStateMachine.ElicitationOutcome outcome =
            machine.callWithElicitationRetry("srv", "tool", () -> elicitationError("el-1"));
        assertThat(outcome.declined()).isTrue();
        assertThat(outcome.declineMessage())
            .contains("URL elicitation was declined")
            .contains("requires the user to open a URL");
        // 进入 PENDING 队列（前端可查）
        assertThat(machine.pendingElicitations()).hasSize(1);
        assertThat(machine.pendingElicitations().get(0).elicitationId()).isEqualTo("el-1");
    }

    // ═══════════ 2. accept（Phase 1 同意）→ 完成通知只启用按钮 → 用户点 Retry now → 重试成功 ═══════════

    /**
     * WHY（规则九 + 反射 F6）：CC client.ts:2950-2996 完成通知只置 {@code completed:true} 激活
     * 「Retry now」按钮（elicitationHandler.ts:186-199），<b>不 resolve 重试 Promise</b>；重试由
     * {@code onWaitingDismiss('retry')} 用户点击驱动。旧 F3 accept 后完成通知自动重试与此相悖。
     * 本测试验意图：accept 后完成通知<b>不</b>重试（calls 仍 1），用户点 {@code retryConfirm}
     * （= Retry now）才重试成功。
     */
    @Test
    @DisplayName("accept → 完成通知只启用按钮（不重试）→ 用户点 Retry now → 重试成功")
    void elicitation_accept_completionEnables_retryNow_retries() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");   // Phase 1 同意，no-op
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return elicitationError("el-2");   // 首次 -32042
                }
                return successResult("ok");             // Retry now 后重试成功
            }));
        try {
            awaitPending(machine, "el-2");
            // 完成通知 → 置 completed（启用 Retry now 按钮），但不触发重试
            machine.markElicitationCompleted("el-2");
            assertThat(calls.get()).isEqualTo(1);
            // 等待完成标记被状态机消费（无重试发生）
            Thread.sleep(100);
            assertThat(calls.get()).isEqualTo(1);   // 完成通知不自动重试（F6 意图）
            // 用户点 Retry now → 重试成功
            machine.retryConfirm("el-2");
            McpElicitationStateMachine.ElicitationOutcome outcome = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome.declined()).isFalse();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * WHY（规则九 + 反射 F6）：验<b>意图</b>——accept 后未点 Retry now 前<b>不</b>重试（阻塞等待），
     * 完成通知到达也不重试；用户点 {@code retryConfirm} 才重试。旧 F3 accept 后等完成通知自动重试
     * 与此意图相悖（F6 收口为「用户门重试」）。
     */
    @Test
    @DisplayName("accept 后未点 Retry now → 阻塞不重试（完成通知也不触发）→ 点 Retry now → 重试")
    void elicitation_accept_blocksUntilRetryNow() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");   // 仅 Phase 1 同意，不驱动
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return elicitationError("el-2");   // 首次 -32042
                }
                return successResult("ok");             // Retry now 后重试成功
            }));
        try {
            // 等待进入 PENDING + 已发出首次调用
            awaitPending(machine, "el-2");
            // 关键意图断言：accept 后未点 Retry now → 不会立即重试（仍只有首次调用）
            assertThat(calls.get()).isEqualTo(1);
            Thread.sleep(200);
            assertThat(calls.get()).isEqualTo(1);   // 未点 Retry now，仍不重试
            // 完成通知到达 → 只启用按钮，仍不重试
            machine.markElicitationCompleted("el-2");
            Thread.sleep(200);
            assertThat(calls.get()).isEqualTo(1);   // 完成通知不触发重试（F6 意图）
            // 用户点 Retry now → 重试成功
            machine.retryConfirm("el-2");
            McpElicitationStateMachine.ElicitationOutcome outcome = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome.declined()).isFalse();
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    // ═══════════ 3. accept → 用户点 Cancel（showCancel:true）→ decline 文本 ═══════════

    /**
     * WHY（规则九 + 反射 F6）：CC waitingState {@code showCancel:true}（client.ts:2947）+
     * {@code onWaitingDismiss('cancel')}（:2990-2995）→ resolve cancel → decline 文本。旧 F3 等待期
     * <b>无 cancel 通道</b>（仅 60s 超时 fail-closed）——F6 补 {@code cancel} 二次通道。验意图：等待期
     * 用户点 Cancel → decline 文本返回，不悬挂、不重试。
     */
    @Test
    @DisplayName("accept → 等待期用户点 Cancel（showCancel:true）→ decline 文本")
    void elicitation_accept_thenCancel_declines() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                calls.incrementAndGet();
                return elicitationError("el-7");   // 恒 -32042
            }));
        try {
            awaitPending(machine, "el-7");
            assertThat(calls.get()).isEqualTo(1);
            // 等待期用户点 Cancel（showCancel:true）
            machine.cancel("el-7");
            McpElicitationStateMachine.ElicitationOutcome outcome = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome.declined()).isTrue();
            assertThat(outcome.declineMessage()).contains("canceled by the user");
            // Cancel 已出队（前端不再展示待确认）
            assertThat(machine.pendingElicitations()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    // ═══════════ 4. accept → 无用户决策 → 超时 → decline（fail-closed）═══════════

    /**
     * WHY（规则九 + 反射 F6）：fail-closed——accept 后用户既不点 Retry now 也不点 Cancel（前端未实现/
     * 无操作）→ {@link McpElicitationStateMachine#setDecisionTimeoutMs} 超时 → decline 文本，不悬挂工具调用。
     */
    @Test
    @DisplayName("accept 后无用户决策 → 超时 → decline 文本（fail-closed，不悬挂）")
    void elicitation_accept_withoutUserDecision_timesOut() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(100);
        machine.setResponder((serverName, elicitation) -> "accept");
        McpElicitationStateMachine.ElicitationOutcome outcome =
            machine.callWithElicitationRetry("srv", "tool", () -> elicitationError("el-6"));
        assertThat(outcome.declined()).isTrue();
        assertThat(outcome.declineMessage()).contains("timed out waiting for user confirmation");
        // 超时后从 PENDING 出队（不悬挂）
        assertThat(machine.pendingElicitations()).isEmpty();
    }

    // ═══════════ 5. decline → decline 文本 ═══════════

    @Test
    @DisplayName("-32042 → 用户 decline → decline 文本")
    void elicitation_decline_returnsDeclineText() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setResponder((serverName, elicitation) -> "decline");
        McpElicitationStateMachine.ElicitationOutcome outcome =
            machine.callWithElicitationRetry("srv", "tool", () -> elicitationError("el-3"));
        assertThat(outcome.declined()).isTrue();
        assertThat(outcome.declineMessage()).contains("URL elicitation was declined");
    }

    // ═══════════ 6. 重试超过 3 次 → 抛原错误 ═══════════

    /**
     * WHY（规则九 + 反射 F6）：CC MAX_URL_ELICITATION_RETRIES=3（client.ts:2850）。每次重试需用户点
     * Retry now（retryConfirm）。旧 F3 测试 responder accept + 同步 markElicitationCompleted 自动重试；
     * F6 改为每次 {@code retryConfirm}（用户门）驱动。
     */
    @Test
    @DisplayName("重试超过 3 次 → 抛原错误（CC MAX_URL_ELICITATION_RETRIES=3 :2850）")
    void elicitation_retryExhausted_throws() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                calls.incrementAndGet();
                return elicitationError("el-4");   // 恒 -32042
            }));
        try {
            // 每次用户点 Retry now 重试一次；3 次后第 4 次调用抛原错误
            for (int i = 0; i < McpElicitationStateMachine.MAX_URL_ELICITATION_RETRIES; i++) {
                awaitPending(machine, "el-4");
                machine.retryConfirm("el-4");
            }
            // executor future 包 ExecutionException → 取 cause（原 IllegalStateException）
            assertThatThrownBy(() -> {
                try {
                    future.get(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ee) {
                    throw ee.getCause();
                }
            })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON-RPC error");
            // 1 次初始 + 3 次重试（每次用户点 Retry now）= 4 次调用
            assertThat(calls.get()).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
    }

    // ═══════════ 7. 完成通知置 completed（elicitationHandler.ts:186-199）═══════════

    /**
     * WHY（规则九 + 反射 F6）：完成通知只置 {@code completed:true}（启用 Retry now 按钮），
     * <b>不出队</b>（队列事件保留，前端 dialog 据 flag 激活按钮）；用户点 Retry now 才出队重试。
     */
    @Test
    @DisplayName("markElicitationCompleted: 完成通知 → 置 completed（启用 Retry now，不出队）")
    void completionNotification_setsCompleted_keepsInQueue() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return elicitationError("el-5");
                }
                return successResult("ok");
            }));
        try {
            awaitPending(machine, "el-5");
            // 完成通知 → 置 completed:true（不出队，前端可查 completed 以启用 Retry now）
            machine.markElicitationCompleted("el-5");
            assertThat(machine.pendingElicitations()).hasSize(1);
            assertThat(machine.pendingElicitations().get(0).completed()).isTrue();
            // 用户点 Retry now → 出队重试成功
            machine.retryConfirm("el-5");
            McpElicitationStateMachine.ElicitationOutcome outcome = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome.declined()).isFalse();
            assertThat(machine.pendingElicitations()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    // ═══════════ 8. 无效 elicitations（缺字段）→ 抛原错误 ═══════════

    @Test
    @DisplayName("elicitations 校验失败（缺 url/elicitationId/message）→ 抛原错误（CC :2876-2897）")
    void invalidElicitations_throws() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setResponder((serverName, elicitation) -> "accept");
        ObjectNode err = MAPPER.createObjectNode();
        err.put("jsonrpc", "2.0").put("id", 1);
        ObjectNode error = err.putObject("error");
        error.put("code", -32042).put("message", "URL elicitation required");
        // data.elicitations[0] 缺 url（仅 mode + elicitationId）→ 校验失败
        error.putObject("data").putArray("elicitations").addObject()
            .put("mode", "url").put("elicitationId", "el-bad");
        assertThatThrownBy(() -> machine.callWithElicitationRetry(
            "srv", "tool",
            () -> {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalStateException("JSON-RPC error: " + error));
                return f;
            }))
            .isInstanceOf(IllegalStateException.class);
    }

    // ═══════════ 9. 非 -32042 错误 → 直接抛 ═══════════

    @Test
    @DisplayName("非 -32042（如 -32601 unknown tool）→ 直接抛不重试")
    void nonElicitationError_throws() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        assertThatThrownBy(() -> machine.callWithElicitationRetry(
            "srv", "tool",
            () -> {
                CompletableFuture<JsonNode> f = new CompletableFuture<>();
                f.completeExceptionally(new IllegalStateException("JSON-RPC error: {\"code\":-32601,\"message\":\"unknown tool\"}"));
                return f;
            }))
            .isInstanceOf(IllegalStateException.class);
    }

    /** 轮询等待指定 elicitation 进入 PENDING 队列（后台线程阻塞时的同步点）。 */
    private static void awaitPending(McpElicitationStateMachine machine, String elicitationId)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (machine.pendingElicitations().stream()
                .anyMatch(p -> p.elicitationId().equals(elicitationId))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("elicitation " + elicitationId + " 未进入 PENDING 队列（超时）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [WF-B] Elicitation hook 接线（对齐 CC client.ts:2924-2940 / :3000-3006）
    // ════════════════════════════════════════════════════════════════════════

    private static GenericHook.HookResult resultFor(ElicitationResponse elicitationResponse,
                                                    ElicitationResponse elicitationResultResponse) {
        return new GenericHook.HookResult(false, null, null, null, null,
            null, null, null, null, GenericHook.HookOutcome.SUCCESS, null, PermissionBehavior.ALLOW,
            null, null, null, null, elicitationResponse, elicitationResultResponse);
    }

    /**
     * 构造按事件分流的 ElicitationHandler stub：Elicitation 事件 → requestDecision；
     * ElicitationResult 事件 → resultDecision（null = 无决策）。
     */
    private static ElicitationHandler hookHandler(ElicitationResponse requestDecision,
                                                  ElicitationResponse resultDecision) {
        return new ElicitationHandler(new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                if (event.type() == HookEventType.ELICITATION) {
                    return resultFor(requestDecision, null);
                }
                return resultFor(null, resultDecision);
            }
        });
    }

    // ═══════════ 10. hook accept 预解析 → 跳过 UI 直接重试（CC client.ts:2939-2940）═══════════

    /**
     * WHY（规则九）：CC callMCPToolWithUrlElicitationRetry 在入队 UI 前先跑
     * runElicitationHooks（client.ts:2924）；hook 返回 accept → 「skip the UI and proceed to
     * retry」（:2939-2940）。旧 Java 无 hook 预解析 → URL elicitation 只能走 responder（前端未接线
     * auto-decline）。本测试验意图：hook accept 跳过 UI/队列，无 responder 也重试成功。
     */
    @Test
    @DisplayName("hook accept 预解析 → 跳过 UI 直接重试（无 responder 也成功，CC :2939-2940）")
    void hookAccept_skipsUi_retries() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setElicitationHandler(hookHandler(new ElicitationResponse("accept", null), null));
        // 无 responder —— 若进入 UI 路径会 auto-decline；hook accept 跳过 UI → 重试成功
        AtomicInteger calls = new AtomicInteger();
        McpElicitationStateMachine.ElicitationOutcome outcome =
            machine.callWithElicitationRetry("srv", "tool", () -> {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return elicitationError("el-hook-1");
                }
                return successResult("ok");
            });
        assertThat(outcome.declined()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        // hook accept 未进 PENDING 队列（跳过 UI）
        assertThat(machine.pendingElicitations()).isEmpty();
    }

    // ═══════════ 11. hook decline 预解析 → decline 文本（CC client.ts:2934-2938）═══════════

    /**
     * WHY（规则九）：CC :2934-2938 hook 决策非 accept → 返回「... by a hook」decline 文本。
     * 验意图：hook decline 在入队 UI 前拦截，返回 decline 文本。
     */
    @Test
    @DisplayName("hook decline 预解析 → decline 文本（by a hook，CC :2934-2938）")
    void hookDecline_returnsDeclineText() {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setElicitationHandler(hookHandler(new ElicitationResponse("decline", null), null));

        McpElicitationStateMachine.ElicitationOutcome outcome =
            machine.callWithElicitationRetry("srv", "tool", () -> elicitationError("el-hook-2"));

        assertThat(outcome.declined()).isTrue();
        assertThat(outcome.declineMessage()).contains("declined by a hook");
    }

    // ═══════════ 12. ElicitationResult hook override 用户 Retry now → decline ═══════════

    /**
     * WHY（规则九）：CC :3000-3006 用户决策（retry→{action:'accept'}）后跑
     * runElicitationResultHooks，finalResult.action 非 accept → decline 文本。验意图：
     * ElicitationResult hook 可 override 用户 Retry now 为 decline（✗-1 生产接线）。
     */
    @Test
    @DisplayName("ElicitationResult hook override 用户 Retry now → decline（CC :3000-3006）")
    void resultHookOverride_onRetry_declines() throws Exception {
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");
        // handleRequest → null（不预解析）；handleResponse → decline（override）
        machine.setElicitationHandler(hookHandler(null, new ElicitationResponse("decline", null)));
        AtomicInteger calls = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> {
                calls.incrementAndGet();
                return elicitationError("el-rh-1");
            }));
        try {
            awaitPending(machine, "el-rh-1");
            machine.retryConfirm("el-rh-1");
            McpElicitationStateMachine.ElicitationOutcome outcome =
                future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome.declined()).isTrue();
            assertThat(outcome.declineMessage()).contains("declined by the user");
        } finally {
            executor.shutdownNow();
        }
    }

    // ═══════════ 13. 完成通知发 elicitation_complete 通知（△-11）═══════════

    /**
     * WHY（规则九）：CC elicitationHandler.ts:183-186 完成通知处理同时
     * executeNotificationHooks('elicitation_complete')（observability）。验意图：
     * markElicitationCompleted 触发 elicitation_complete 通知，不依赖是否在队列。
     */
    @Test
    @DisplayName("markElicitationCompleted 发 elicitation_complete 通知（CC elicitationHandler.ts:183-186）")
    void completionNotification_firesElicitationComplete() throws Exception {
        List<HookEvent> events = new CopyOnWriteArrayList<>();
        McpElicitationStateMachine machine = new McpElicitationStateMachine();
        machine.setDecisionTimeoutMs(3000);
        machine.setResponder((serverName, elicitation) -> "accept");
        machine.setElicitationHandler(new ElicitationHandler(new HookRegistry() {
            @Override
            public GenericHook.HookResult executeEvent(HookEvent event) {
                events.add(event);
                return null;
            }
        }));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<McpElicitationStateMachine.ElicitationOutcome> future = executor.submit(
            () -> machine.callWithElicitationRetry("srv", "tool", () -> elicitationError("el-c-1")));
        try {
            awaitPending(machine, "el-c-1");
            machine.markElicitationCompleted("el-c-1");
            boolean fired = events.stream().anyMatch(ev ->
                ev.type() == HookEventType.NOTIFICATION
                    && "elicitation_complete".equals(ev.data().get("notification_type")));
            assertThat(fired)
                .as("完成通知处理时发 elicitation_complete 通知（observability，△-11）")
                .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
