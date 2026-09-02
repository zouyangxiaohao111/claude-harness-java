package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 CC async generator 的 Java Stream 边界：无 hook 早返，有 hook 才惰性执行并产出聚合结果。
 */
class PermissionDeniedHookExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void permissionDeniedHookExecutor_noHookRegistered_returnsEmptyStream() {
        PermissionDeniedHookExecutor executor = new PermissionDeniedHookExecutor(new HookRegistry());

        List<AggregatedHookResult> results = executor.executePermissionDeniedHooks(
            "Bash", "tool-use-1", JSON.createObjectNode().put("command", "pwd"),
            "Permission denied", context(), "default", new AbortController()).toList();

        assertThat(results).isEmpty();
    }

    @Test
    void permissionDeniedHookExecutor_withPermissionDeniedHook_yieldsAggregatedResults() {
        HookRegistry registry = new HookRegistry();
        registry.register("retry", event -> {
            assertThat(event.type()).isEqualTo(HookEventType.PERMISSION_DENIED);
            assertThat(event.data()).containsEntry("tool_use_id", "tool-use-2");
            assertThat(event.data()).containsEntry("permission_mode", "default");
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);
        PermissionDeniedHookExecutor executor = new PermissionDeniedHookExecutor(registry);

        List<AggregatedHookResult> results = executor.executePermissionDeniedHooks(
            "Bash", "tool-use-2", JSON.createObjectNode().put("command", "rm temp.txt"),
            "classifier denied", context(), "default", new AbortController()).toList();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).retry()).isTrue();
    }

    @Test
    void permissionDeniedHookExecutor_signalCancelled_skipsHooksAndReturnsEmpty() {
        // [P-AL-04 REQ-C-C1] CC executeHooks 入口 {@code if (signal?.aborted) return}
        // (hooks.ts:2015-2017) — 请求取消后 PermissionDenied retry hook 整体跳过。
        // WHY 意图: 用户已取消时执行 retry hook 并注入 isMeta 是错误行为
        // (open-decisions REQ-C-C1 登记偏差, Java 旧实现调用方传 null 导致)。
        int[] hookInvocations = {0};
        HookRegistry registry = new HookRegistry();
        registry.register("retry", event -> {
            hookInvocations[0]++;
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);
        PermissionDeniedHookExecutor executor = new PermissionDeniedHookExecutor(registry);

        AbortController cancelled = new AbortController();
        cancelled.abort("user_cancelled");
        List<AggregatedHookResult> results = executor.executePermissionDeniedHooks(
            "Bash", "tool-use-3", JSON.createObjectNode().put("command", "rm temp.txt"),
            "classifier denied", context(), "default", cancelled).toList();

        // signal 已取消 → 空流 (早返), hook 不得执行
        assertThat(results)
            .as("signal 已取消 → 必须返回空流 (对齐 CC signal.aborted 早返)")
            .isEmpty();
        assertThat(hookInvocations[0])
            .as("signal 已取消 → PermissionDenied hook 不得被调用")
            .isZero();
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }
}
