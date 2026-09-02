package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 CCJ-T6-06/11] 工具链预执行进度事件 + 批级遥测.
 *
 * <p>验证单元 (RED→GREEN):
 * <ol>
 *   <li>白名单开启 + registerHookEventHandler → executePreToolUse 每匹配 hook 产
 *       started + progress 事件 (事件总线 HookProgressEvent 六字段无 command —
 *       hooks_v3 决策 2-4: command/promptText/statusMessage 属消息流 hook_progress
 *       载荷 hooks.ts:2094-2116, 随决策 0-4 走消息流通道)</li>
 *   <li>Telemetry counter: tengu_run_hook=1 (numCommands 正确) +
 *       tengu_repl_hook_finished=1 (numSuccess 计数正确)</li>
 * </ol>
 */
@DisplayName("[IMP-HOOKS-S6 CCJ-T6-06/11] 工具链预执行进度事件 + 批级遥测")
class HookProgressTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    @Test
    @DisplayName("2 programmatic PreToolUse hook → 2 started + 2 progress (六字段) + 批遥测计数")
    void preToolUseChain_emitsStartedProgressAndBatchTelemetry() {
        HookRegistry registry = new HookRegistry();
        registry.registerPreToolUse("hook-a", (toolName, input, ctx) -> AggregatedHookResult.proceed());
        registry.registerPreToolUse("hook-b", (toolName, input, ctx) -> AggregatedHookResult.proceed());

        Telemetry telemetry = new Telemetry();
        registry.setTelemetry(telemetry);

        HookEventBus bus = new HookEventBus();
        bus.setAllHookEventsEnabled(true);
        List<HookEventBus.HookExecutionEvent> events = new CopyOnWriteArrayList<>();
        bus.registerHookEventHandler(events::add);
        registry.setHookEventBus(bus);

        AggregatedHookResult outcome = registry.executePreToolUse(
            "Bash", JSON.createObjectNode(), ctx(), "tu-1");

        assertThat(outcome).isNotNull();
        // ── started/progress 事件 (每匹配 hook 各 1) ──
        long started = events.stream().filter(e -> e instanceof HookEventBus.HookStartedEvent).count();
        long progressed = events.stream().filter(e -> e instanceof HookEventBus.HookProgressEvent).count();
        assertThat(started)
            .as("2 个 programmatic hook → 2 个 started 事件 (CC emitHookStarted)")
            .isEqualTo(2);
        assertThat(progressed)
            .as("2 个 programmatic hook → 2 个 progress 事件 (CC hookEvents.ts:29-37)")
            .isEqualTo(2);
        // [hooks_v3 决策 2-4 / D-WF5-06 · X-WF5-01 WF1-X3] command/promptText/statusMessage
        // 属消息流 hook_progress 载荷 (hooks.ts:2094-2116), 非事件总线 HookProgressEvent
        // 字段 (hookEvents.ts:29-37) — 事件总线 progress 回缩为 6 字段 (stdout/stderr/output);
        // command 载荷随消息流通道发出 (HookRegistry 按 H-WF5a-patch-note.md 合并阶段迁移,
        // 前端接线随决策 0-4), 事件总线侧不再断言 command.

        // ── 批级遥测计数 ──
        assertThat(telemetry.getCounter("tengu_run_hook"))
            .as("批首 tengu_run_hook 恰 1 次")
            .isEqualTo(1);
        assertThat(telemetry.getCounter("tengu_repl_hook_finished"))
            .as("批尾 tengu_repl_hook_finished 恰 1 次")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("无 hook 匹配 → 不产事件不产遥测")
    void noHooks_noEventsNoTelemetry() {
        HookRegistry registry = new HookRegistry();
        Telemetry telemetry = new Telemetry();
        registry.setTelemetry(telemetry);
        HookEventBus bus = new HookEventBus();
        bus.setAllHookEventsEnabled(true);
        List<HookEventBus.HookExecutionEvent> events = new CopyOnWriteArrayList<>();
        bus.registerHookEventHandler(events::add);
        registry.setHookEventBus(bus);

        registry.executePreToolUse("Bash", JSON.createObjectNode(), ctx(), "tu-1");

        assertThat(events).isEmpty();
        assertThat(telemetry.getCounter("tengu_run_hook")).isZero();
        assertThat(telemetry.getCounter("tengu_repl_hook_finished")).isZero();
    }
}
