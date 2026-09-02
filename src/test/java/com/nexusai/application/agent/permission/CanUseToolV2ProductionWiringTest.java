package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [canUseTool v2 对抗核验] 生产接线测试 · 覆盖对抗核验缺口 2/3：
 * coordinator handler / swarm handler 生产接线 + awaitAutomatedChecksBeforeDialog 数据源。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: 对抗核验发现这些"结构对齐但生产恒 fall-through"——
 * createSpringBean 生产路径传 null 导致 coordinator/swarm 死路径，
 * PermissionContextBuilder 恒 false 导致 coordinator 分支永不触发。本测试钉死生产路径
 * （createSpringBean / 真实字段注入）的接线不变量 —— 一旦回退为 null 死路径，测试必须红。
 *
 * @see ToolPermissionGate
 * @see PermissionContextBuilder
 * @since canUseTool v2 修复
 */
class CanUseToolV2ProductionWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    private static final class StubPipeline extends PermissionPipeline {
        final PermissionResult result;
        StubPipeline(PermissionResult result) { this.result = result; }
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                       ToolUseContext ctx, ToolPermissionContext permCtx) {
            return result;
        }
    }

    private static final class RecordingPrompter implements PermissionPrompter {
        final AtomicCounter calls = new AtomicCounter();
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input, PermissionDecisionReason reason,
                                       ToolUseContext ctx, String requestId) {
            calls.inc();
            return new PermissionResult.Allow(JSON.createObjectNode(),
                new PermissionDecisionReason.Other("user allowed"), "c", false, null, List.of());
        }
    }

    private static final class AtomicCounter { int v; void inc() { v++; } }

    private static ToolUseContext newCtx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT);
    }

    private static ToolPermissionContext newPermCtx(boolean awaitAutomatedChecks) {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), false, awaitAutomatedChecks, null);
    }

    // ─────────────────────────── Gap 3: awaitAutomatedChecksBeforeDialog 数据源 ───────────────────────────

    @Test
    @DisplayName("buildPermissionContext 5 参(state,true,BUBBLE,false,base) → awaitAutomatedChecksBeforeDialog=true + mode 透传 (H9 v3 Gap①)")
    void buildPermissionContext_withModePropagatesAwaitAutomatedChecks() {
        // WHY: H9 v3 对抗核验缺口① — 旧便捷重载（已删除）恒 false + 硬编码 DEFAULT mode，
        //      生产 coordinator 分支不可达。CC runAgent.ts:420-432 + 457-464 真源：
        //      fork 子 agent permissionMode=bubble → mode 覆盖 + awaitAutomatedChecksBeforeDialog=true。
        //      5 参重载（AgentLoopContext.toolExecContext 生产路径）必须同时透传 mode=BUBBLE
        //      与 awaitAutomatedChecks=true，让 gate 的 coordinator 分支 (ToolPermissionGate L522)
        //      与 bubble 分支 (L680) 生产可达。
        PermissionContextBuilder builder = new PermissionContextBuilder(List.of());
        com.nexusai.application.agent.AgentState state =
            new com.nexusai.application.agent.AgentState("system", SESSION_ID, AGENT_ID);

        ToolPermissionContext withFlag = builder.buildPermissionContext(state, true, PermissionMode.BUBBLE, false, true);
        assertThat(withFlag.awaitAutomatedChecksBeforeDialog())
            .as("buildPermissionContext(state,true,BUBBLE,false,base) → coordinator 分支生产可达 (gate:522)")
            .isTrue();
        assertThat(withFlag.mode())
            .as("buildPermissionContext(state,true,BUBBLE,false,base) → mode 必须携带 BUBBLE (gate:680 bubble 分支)")
            .isEqualTo(PermissionMode.BUBBLE);

        // 默认路径 (await=false, mode=null) 保持旧行为: 无 awaitAutomatedChecks + DEFAULT mode (主线程/普通子 agent)
        ToolPermissionContext defaultCtx = builder.buildPermissionContext(state, false, null, false, true);
        assertThat(defaultCtx.awaitAutomatedChecksBeforeDialog())
            .as("默认路径保持 false (主线程/同步子 agent)")
            .isFalse();
        assertThat(defaultCtx.mode())
            .as("默认路径 mode 保持 DEFAULT (不改变既有行为)")
            .isEqualTo(PermissionMode.DEFAULT);

        // mode=null: awaitAutomatedChecks 独立透传, mode 保持 DEFAULT
        ToolPermissionContext flagOnly = builder.buildPermissionContext(state, true, null, false, true);
        assertThat(flagOnly.awaitAutomatedChecksBeforeDialog()).isTrue();
        assertThat(flagOnly.mode()).isEqualTo(PermissionMode.DEFAULT);
    }

    // ─────────────────────────── helpers ───────────────────────────

}
