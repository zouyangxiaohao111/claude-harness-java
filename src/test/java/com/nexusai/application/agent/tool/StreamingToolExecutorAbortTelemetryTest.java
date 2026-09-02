package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session I P3-2] abort 路径 tengu_tool_use_cancelled telemetry · 行为级验证.
 *
 * <p>对齐 CC 真源 {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:415-453}:
 * 入口 {@code abortController.signal.aborted} 检查时先
 * {@code logEvent('tengu_tool_use_cancelled', {toolName, toolUseID, isMcp, queryChainId, queryDepth})}
 * 再 yield CANCEL_MESSAGE. Java 端拆两个互斥 abort 分支 (getAbortReason 短路 + agent-level
 * state.cancelled() 短路), 两个分支都必须发射同事件, 且单工具调用恰好 1 次 (分支 1 先 return,
 * 分支 2 不可达 → 不双发).
 *
 * <p><b>RED-GREEN 双证 (Pattern #14)</b>: 本测试先于 queryChainId/queryDepth 字段注入编写 —
 * 接线前 queryChainId/queryDepth 断言必红 (emitCancelledTelemetry 仅发 toolName/toolUseID/isMcp),
 * 接线后转绿. 复用 R32B8_SetInProgressToolUseIDsTest:549-551 的预 abort 模式 +
 * YoloClassifierTelemetryTest:395-413 的 Spy Telemetry 模式.
 *
 * <p><b>queryTracking 来源</b>: {@link ToolUseContext#withQueryTracking} stamp
 * (AgentLoopContext.toolExecContext 每轮注入, 对齐 CC query.ts:346-363).
 */
class StreamingToolExecutorAbortTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Spy Telemetry · 记录事件名 → attrs + 每事件出现次数 (双发检测). */
    static class SpyTelemetry extends Telemetry {
        final Map<String, Map<String, Object>> recordedEvents = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> eventCounts = new ConcurrentHashMap<>();

        @Override
        public void recordEvent(String eventName, Map<String, Object> metadata) {
            recordedEvents.put(eventName, new HashMap<>(metadata));
            eventCounts.computeIfAbsent(eventName, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static ToolUseBlock buildCall(String id, String name) {
        JsonNode input = JSON.createObjectNode().put("pattern", "*.java");
        return new ToolUseBlock(id, name, input);
    }

    private static Tool stubTool() {
        return new Tool() {
            @Override public String name() { return "abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                // 不应执行: 两个测试都要求 executeAsync 入口 abort 短路
                throw new AssertionError("tool 不应被执行 (已被 abort 短路)");
            }
        };
    }

    /**
     * 分支 1: getAbortReason 短路 (siblingAbortController/ctx.abortController/discarded/
     * hasErrored 路径) · 对齐 CC toolExecution.ts:415 单点 abort 检查.
     *
     * <p>abort reason 用非 interrupt 值 ("permission_denied") — getAbortReason 对非 interrupt
     * reason 恒返 "user_interrupted" (:2763-2764), 不依赖 stub 工具的 interruptBehavior() 默认
     * "block" (interrupt + block → 返回 null, 走不到 abort 分支).
     */
    @Test
    @DisplayName("分支1: getAbortReason 短路发射 tengu_tool_use_cancelled (含 queryChainId/queryDepth)")
    void abortReasonPath_emitsCancelledTelemetryWithQueryTracking() throws Exception {
        AbortController parentAbort = new AbortController();
        parentAbort.abort("permission_denied");

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", parentAbort, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            s -> s
        ).withQueryTracking(Map.of("chainId", "chain-test-1", "depth", 2));

        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool());

        SpyTelemetry spy = new SpyTelemetry();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(spy);
        exec.add(buildCall("toolu_abort", "abort_stub"));
        List<ToolResult> results = exec.getRemainingResults();

        // abort 短路 → synthetic error result (不执行 tool)
        assertThat(results)
            .as("abort 路径应返回 synthetic error result")
            .hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_abort"))
            .as("abort 路径返回 synthetic error (isError=true)")
            .isTrue();

        // CC toolExecution.ts:416-424 tengu_tool_use_cancelled attrs
        assertThat(spy.recordedEvents)
            .as("abort 路径必须发射 tengu_tool_use_cancelled (CC toolExecution.ts:416)")
            .containsKey("tengu_tool_use_cancelled");
        Map<String, Object> attrs = spy.recordedEvents.get("tengu_tool_use_cancelled");
        assertThat(attrs.get("toolName"))
            .as("toolName 取工具名 (CC :417)")
            .isEqualTo("abort_stub");
        assertThat(attrs.get("toolUseID"))
            .as("toolUseID 取调用 id (CC :418-419)")
            .isEqualTo("toolu_abort");
        assertThat(attrs.get("isMcp"))
            .as("isMcp 取 tool.isMcp ?? false (CC :420)")
            .isEqualTo(false);
        assertThat(attrs.get("queryChainId"))
            .as("queryChainId 来自 ctx.queryTracking().chainId (CC :422-423)")
            .isEqualTo("chain-test-1");
        assertThat(attrs.get("queryDepth"))
            .as("queryDepth 来自 ctx.queryTracking().depth (CC :424)")
            .isEqualTo(2);

        // 单工具调用恰好 1 次: 两个互斥 abort 分支不双发 (分支 1 先 return)
        assertThat(spy.eventCounts.get("tengu_tool_use_cancelled").get())
            .as("单工具调用恰好 1 次 tengu_tool_use_cancelled (CC 单点检查)")
            .isEqualTo(1);
    }

    /**
     * 分支 2: agent-level cancel · [G29① S-2 修正] 对齐 CC 走 abortController → user_interrupted →
     * REJECT_MESSAGE。旧实现经 agentStateRef.cancelled() 独立短路（Java 独有 "agent_cancelled" 态，
     * CC getAbortReason 无此态）已删除；agent 级取消在 Java 中由 abortController.abort() 承载
     * （ExecAgentHook:334 / SubagentExecutor:3802 onCancel → state.cancel() 是 abort 的副作用，
     * 非工具短路入口）。故本分支与分支 1 同走 getAbortReason 短路，仅以非 interrupt reason
     * 触发（对齐 CC toolExecution.ts:415-424 单点 abort 检查 + :422-424 queryChainId/queryDepth 发射）。
     */
    @Test
    @DisplayName("分支2: agent-level cancel 经 abortController 发射 tengu_tool_use_cancelled (含 queryChainId/queryDepth)")
    void agentLevelCancelPath_emitsCancelledTelemetry() {
        AbortController abort = new AbortController();
        // 非 interrupt reason → getAbortReason 恒返 "user_interrupted"（:2763-2764），不依赖工具
        // interruptBehavior（默认 "block" 会挡 interrupt 分支）。对齐 CC agent 取消 = abort signal。
        abort.abort("agent_cancelled");

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abort, List.of(),
            null, PermissionMode.DEFAULT
        ).withQueryTracking(Map.of("chainId", "chain-agent-2", "depth", 5));

        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool());

        SpyTelemetry spy = new SpyTelemetry();
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, ctx);
        exec.setTelemetry(spy);
        exec.add(buildCall("toolu_agent_cancel", "abort_stub"));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results)
            .as("agent-level cancel 路径应返回 synthetic error result")
            .hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_agent_cancel"))
            .as("agent-level cancel 返回 synthetic error (isError=true)")
            .isTrue();

        assertThat(spy.recordedEvents)
            .as("agent-level cancel 必须发射 tengu_tool_use_cancelled (CC toolExecution.ts:416 等价)")
            .containsKey("tengu_tool_use_cancelled");
        Map<String, Object> attrs = spy.recordedEvents.get("tengu_tool_use_cancelled");
        assertThat(attrs.get("toolName"))
            .as("toolName 取工具名")
            .isEqualTo("abort_stub");
        assertThat(attrs.get("toolUseID"))
            .as("toolUseID 取调用 id")
            .isEqualTo("toolu_agent_cancel");
        assertThat(attrs.get("isMcp"))
            .as("isMcp = tool.isMcp ?? false")
            .isEqualTo(false);
        assertThat(attrs.get("queryChainId"))
            .as("queryChainId 来自 ctx.queryTracking().chainId (分支2 场景 ctx 非空)")
            .isEqualTo("chain-agent-2");
        assertThat(attrs.get("queryDepth"))
            .as("queryDepth 来自 ctx.queryTracking().depth")
            .isEqualTo(5);

        assertThat(spy.eventCounts.get("tengu_tool_use_cancelled").get())
            .as("单工具调用恰好 1 次 tengu_tool_use_cancelled")
            .isEqualTo(1);
    }
}
