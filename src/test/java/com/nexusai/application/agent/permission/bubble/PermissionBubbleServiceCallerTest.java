package com.nexusai.application.agent.permission.bubble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bubble caller 接入测试 · 对齐 CC {@code Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:60-71}
 * {@code FORK_AGENT.permissionMode: 'bubble'}（:67）+ {@code runAgent.ts:437-463}
 * {@code shouldAvoidPrompts}（bubble → false）。
 *
 * <p><b>WHY (意图验证)</b>: {@link PermissionBubbleService#handleBubble} 是 L1 实现,
 * 但若不接入运行时就是死代码——子 agent 的 ASK 决策实际不会冒泡到父 agent。
 * 本测试 3 项强制保证 caller 已接入 {@code ToolPermissionGate.mapToDecision}
 * （CC {@code useCanUseTool.tsx} 的 Java 等价物 = 工具调用时 Ask 分发点）：
 *
 * <ul>
 *   <li><b>L1 BUBBLE 触发</b>: {@code ctx.permissionMode() == BUBBLE} 时, ASK 决策
 *       必须触发 {@code bubbleService.handleBubble(...)} 恰好 1 次 — 验证 P1-4 caller
 *       已接入生产路径 (REQ-F-01)</li>
 *   <li><b>L2 非 BUBBLE 不触发</b>: DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS /
 *       DONT_ASK / PLAN / AUTO 不能触发 bubble — Pattern #11 严格 guard (非 bypass):
 *       冒泡只路由"弹窗给谁看", 不修改 10 层检查的最终 ALLOW/DENY 语义</li>
 * </ul>
 *
 * <p><b>WHY 必须 RED-GREEN 双向验证 (Pattern #14)</b>: 只绿过的 caller 测试无验证力——
 * 任何"caller 已接入"声明都必须先让本测试在未接入时 RED, 接入后 GREEN。
 *
 * <p><b>文件历史</b>: 本文件最初由旧 F session 创建 (commit 44129b08, RED-GREEN 双证),
 * 于 commit 020bf058 被删除 (删除理由声称"引用已删符号 CompactContext/CompactPipeline",
 * 但 git 历史 grep 证实本文件 0 命中该二符号 —— 删除理由与实际内容不符)。
 * 本 workflow F 任务按 requirement-catalog REQ-F-01 验收 ("当前分支缺该类, 须新建")
 * 重建。T7 对齐 CC 后删除 L3 Deny 短路测试（CC 无深度守卫/工具黑名单，
 * handleBubble 恒返原 Ask —— 对齐 CC runAgent.ts:440-446）。
 */
class PermissionBubbleServiceCallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000002";

    /**
     * 测试桩 Tool · 不参与实际执行, 仅承载 tool.name() 用于 ASK 弹窗 / bubble 调用.
     */
    private static final class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub-result");
        }
    }

    /**
     * 固定返回 Ask 的 PermissionPipeline 桩 · 让 gate.check() 必然走到 ASK 分支.
     *
     * <p>PermissionPipeline 是 public class (非 abstract), 0-arg ctor 即可构造.
     * override check 方法固定返回 Ask, 让 mapToDecision 必然走 bubble/interactive 路径.
     */
    private static final class AskPipelineStub extends PermissionPipeline {
        AskPipelineStub() { super(); }
        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call,
                                       JsonNode input, ToolUseContext ctx,
                                       ToolPermissionContext permCtx) {
            return new PermissionResult.Ask(
                "stub ask: " + tool.name(),
                new PermissionDecisionReason.Other("test"),
                List.of(), null, null, null,
                false, null, List.of()
            );
        }
    }

    /**
     * 固定返回 Allow 的 PermissionPrompter 桩 · 让 ASK 分支不会卡死在用户弹窗.
     */
    private static final class AllowPrompterStub implements PermissionPrompter {
        int promptCount = 0;
        @Override
        public PermissionResult prompt(Tool tool, JsonNode input,
                                        PermissionDecisionReason reason,
                                        ToolUseContext ctx, String toolUseId) {
            promptCount++;
            return new PermissionResult.Allow(
                input, reason, toolUseId, false, null, List.of()
            );
        }
    }

    /**
     * PermissionBubbleService 计数 spy · 通过 override handleBubble 记录调用次数与参数.
     *
     * <p>WHY 计数 spy 而非 mockito spy: bubbleService 是 @Component, 测试手动构造 (避免
     * Spring 容器开销), Mockito 静态 mock 会引入额外依赖且难以 verify 父类链. 简单计数
     * 子类最直接.
     */
    private static class BubbleSpy extends PermissionBubbleService {
        int callCount = 0;
        SubagentPermissionContext lastChildCtx;
        String lastToolName;
        JsonNode lastInput;
        PermissionResult.Ask lastAskResult;

        @Override
        public PermissionResult handleBubble(SubagentPermissionContext childCtx,
                                              String toolName, JsonNode input,
                                              PermissionResult.Ask askResult) {
            callCount++;
            lastChildCtx = childCtx;
            lastToolName = toolName;
            lastInput = input;
            lastAskResult = askResult;
            // 模拟冒泡后让上层 prompter 处理 (返回原 Ask)
            return askResult;
        }
    }

    /**
     * 构造 ToolUseContext · permissionMode 由调用方决定.
     *
     * <p>使用 9 字段 {@link ToolUseContext#of(UUID, UUID, PermissionMode, List, String,
     * AbortController, List, ToolPermissionContext, PermissionMode)} 便利工厂, 后面 37 字段
     * (Stage 3.2 C2 4 + Stage 3.3 UI 11 + Stage 3.4 session 13 + readFileState) 由
     * compact ctor 兜底 null.
     */
    private static ToolUseContext newToolUseContext(PermissionMode mode,
                                                     ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT_ID, SESSION_ID, PermissionMode.DEFAULT,
            List.of(), "",
            com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), permCtx, mode);
    }

    private static ToolUseBlock newCall(String id, String toolName) {
        return new ToolUseBlock(id, toolName, JSON.createObjectNode());
    }

    private static ToolPermissionContext newPermCtx(PermissionMode mode) {
        return new ToolPermissionContext(
            mode,
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            false, false, java.util.Map.of(), false, false, null
        );
    }

    @Test
    @DisplayName("L1: permissionMode == BUBBLE 时, ASK 路径触发 bubbleService.handleBubble")
    void bubbleModeTriggersHandleBubbleOnAsk() {
        BubbleSpy spy = new BubbleSpy();
        ToolPermissionGate gate = new ToolPermissionGate(
            new AskPipelineStub(),
            new AllowPrompterStub(),
            null, null,        // autoModeGate / denialTracker (向后兼容 null)
            spy                // bubbleService (本测试核心 spy)
        );
        StubTool tool = new StubTool("Bash");
        ToolUseBlock call = newCall("call_001", "Bash");
        ToolPermissionContext permCtx = newPermCtx(PermissionMode.BUBBLE);
        ToolUseContext ctx = newToolUseContext(PermissionMode.BUBBLE, permCtx);

        ToolPermissionGate.DecisionResult result = gate.check(
            tool, call, call.input(), ctx, permCtx);

        assertThat(spy.callCount)
            .as("permissionMode=BUBBLE + ASK 决策 → handleBubble 必须被调用恰好 1 次")
            .isEqualTo(1);
        assertThat(spy.lastToolName).isEqualTo("Bash");
        assertThat(spy.lastChildCtx).isNotNull();
        assertThat(spy.lastChildCtx.mode())
            .as("deriveSubagentContext 派生子 ctx 必须为 BUBBLE 模式")
            .isEqualTo(BubblePermissionMode.BUBBLE);
        assertThat(spy.lastAskResult).isNotNull();
        assertThat(result.decision())
            .as("bubble 返回 Ask → 上层 prompter 继续处理 → ALLOW (AllowPrompterStub)")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    @Test
    @DisplayName("L2: 非 BUBBLE mode (DEFAULT) 时, 不触发 bubbleService.handleBubble")
    void nonBubbleModeDoesNotTriggerHandleBubble() {
        BubbleSpy spy = new BubbleSpy();
        ToolPermissionGate gate = new ToolPermissionGate(
            new AskPipelineStub(),
            new AllowPrompterStub(),
            null, null, spy
        );
        StubTool tool = new StubTool("Read");
        ToolUseBlock call = newCall("call_002", "Read");
        ToolPermissionContext permCtx = newPermCtx(PermissionMode.DEFAULT);
        ToolUseContext ctx = newToolUseContext(PermissionMode.DEFAULT, permCtx);

        ToolPermissionGate.DecisionResult result = gate.check(
            tool, call, call.input(), ctx, permCtx);

        assertThat(spy.callCount)
            .as("permissionMode=DEFAULT + ASK 决策 → handleBubble 必须 0 次 (Pattern #11 guard)")
            .isZero();
        assertThat(result.decision())
            .as("非 BUBBLE 走常规 prompter 路径 → ALLOW (AllowPrompterStub)")
            .isEqualTo(ToolPermissionGate.Decision.ALLOW);
    }

    @Test
    @DisplayName("L2 旁证: 其余 5 种非 BUBBLE mode 全 0 调用 (Pattern #11 严格 guard)")
    void otherNonBubbleModesDoNotTriggerHandleBubble() {
        for (PermissionMode mode : new PermissionMode[] {
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.BYPASS_PERMISSIONS,
                PermissionMode.DONT_ASK,
                PermissionMode.PLAN,
                PermissionMode.AUTO }) {
            BubbleSpy spy = new BubbleSpy();
            ToolPermissionGate gate = new ToolPermissionGate(
                new AskPipelineStub(),
                new AllowPrompterStub(),
                null, null, spy
            );
            StubTool tool = new StubTool("Bash");
            ToolUseBlock call = newCall("call_x", "Bash");
            ToolPermissionContext permCtx = newPermCtx(mode);
            ToolUseContext ctx = newToolUseContext(mode, permCtx);

            gate.check(tool, call, call.input(), ctx, permCtx);

            assertThat(spy.callCount)
                .as("permissionMode=%s → handleBubble 必须 0 次 (Pattern #11 严格 guard)", mode)
                .isZero();
        }
    }

}
