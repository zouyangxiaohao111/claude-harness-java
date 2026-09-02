package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.impl.CtxInspectTool;
import com.nexusai.application.agent.tool.impl.MonitorTool;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.application.agent.tool.impl.TerminalCaptureTool;
import com.nexusai.application.agent.tool.impl.TestingPermissionTool;
import com.nexusai.application.agent.tool.impl.VerifyPlanExecutionTool;
import com.nexusai.application.agent.tool.impl.WorkflowTool;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [B5 OPD-10] 7 个 CC feature/env 门控工具注册桩的门控单测。
 *
 * <p><b>WHY（意图验证，规则九）</b>: 对齐 CC tools.ts getAllBaseTools 各门控 spread——
 * flag/env 关闭时工具不暴露（{@code isEnabled()==false}），开启时可用。这是 OPD-10
 * 拍板"A 建注册桩"的核心验收：桩必须可被门控，否则恒注册恒 true 就与 CC 门控语义冲突
 * （verifyStrategy「删除后重跑旧符号：确认未引入恒注册恒 true 的 CC 门控冲突」）。
 *
 * <p>7 桩 feature/env 门控源（CC tools.ts 行号自验 2026-08-11；OverflowTestTool 因
 * G30⑫ 已删除——CC tools.ts:107-108/221 的 OVERFLOW_TEST_TOOL 在 CC 亦为纯测试工具、
 * Java 无对应测试通道）:
 * <ul>
 *   <li>CtxInspectTool      → CONTEXT_COLLAPSE（tools.ts:110-111/222）</li>
 *   <li>TerminalCaptureTool → TERMINAL_PANEL（tools.ts:113-115/223）</li>
 *   <li>VerifyPlanExecutionTool → CLAUDE_CODE_VERIFY_PLAN==='true'（tools.ts:91-94/231）</li>
 *   <li>WorkflowTool        → WORKFLOW_SCRIPTS（tools.ts:129-133/233）</li>
 *   <li>MonitorTool         → MONITOR_TOOL（tools.ts:39-40/237）</li>
 *   <li>SnipTool            → HISTORY_SNIP（tools.ts:123-124/243）</li>
 *   <li>TestingPermissionTool → NODE_ENV==='test'（tools.ts:244）</li>
 * </ul>
 */
class ToolFeatureStubGatingTest {

    @Test
    @DisplayName("7 桩 feature/env 关闭时 isEnabled()==false（默认 ALL_DISABLED 全关）")
    void allStubsDisabledByDefault() {
        // WHY: FeatureFlags.ALL_DISABLED = 对齐 CC flag 关闭时模块为 null。任一桩以
        // ALL_DISABLED 构建必须 isEnabled()==false，否则 flag-off 时工具会泄漏进 LLM schema。
        FeatureFlags off = FeatureFlags.ALL_DISABLED;

        Assertions.assertThat(new CtxInspectTool(off).isEnabled()).as("CtxInspectTool CONTEXT_COLLAPSE 关").isFalse();
        Assertions.assertThat(new TerminalCaptureTool(off).isEnabled()).as("TerminalCaptureTool TERMINAL_PANEL 关").isFalse();
        Assertions.assertThat(new VerifyPlanExecutionTool(off).isEnabled()).as("VerifyPlanExecutionTool CLAUDE_CODE_VERIFY_PLAN 关").isFalse();
        Assertions.assertThat(new WorkflowTool(off).isEnabled()).as("WorkflowTool WORKFLOW_SCRIPTS 关").isFalse();
        Assertions.assertThat(new MonitorTool(off).isEnabled()).as("MonitorTool MONITOR_TOOL 关").isFalse();
        Assertions.assertThat(new SnipTool(off).isEnabled()).as("SnipTool HISTORY_SNIP 关").isFalse();
        Assertions.assertThat(new TestingPermissionTool(off).isEnabled()).as("TestingPermissionTool NODE_ENV==='test' 关").isFalse();
    }

    @Test
    @DisplayName("7 桩 feature/env 开启时 isEnabled()==true")
    void allStubsEnabledWhenGatedOn() {
        // WHY: 门控开启必须暴露工具（对齐 CC flag 开 → 工具进 getAllBaseTools 数组）。
        // 每个桩门控打其对应 flag，逐一验证——漏一个就是"门控没接"（恒 false 桩无意义）。
        FeatureFlags on = new FeatureFlags(
            false, false, false, false, false, false, false, false, false, false,
            false,  // bgSessions（BG_SESSIONS）
            false,  // overflowTestTool（死标志 · G30⑫ 删除 OverflowTestTool 后无消费方 · 保留对齐 CC tools.ts:107 死代码）
            true,   // terminalPanel（TERMINAL_PANEL）
            true,   // verifyPlan（CLAUDE_CODE_VERIFY_PLAN）
            true,   // workflowScripts（WORKFLOW_SCRIPTS）
            true,   // monitorTool（MONITOR_TOOL）
            true,   // testingPermission（NODE_ENV==='test'）
            false,  // usePowerShellTool（USER_TYPE + CLAUDE_CODE_USE_POWERSHELL_TOOL，未验证）
            false,  // tokenBudget（TOKEN_BUDGET feature · 未验证）
            false,  // teamMem（TEAMMEM · OPD-CM3-10/B03 可配置开关 · 本测试不开）
            false); // tenguHerringClock（tengu_herring_clock · OPD-CM3-11/B04 运行时开关 · 本测试不开）

        Assertions.assertThat(new TerminalCaptureTool(on).isEnabled()).as("TerminalCaptureTool TERMINAL_PANEL 开").isTrue();
        Assertions.assertThat(new VerifyPlanExecutionTool(on).isEnabled()).as("VerifyPlanExecutionTool CLAUDE_CODE_VERIFY_PLAN 开").isTrue();
        Assertions.assertThat(new WorkflowTool(on).isEnabled()).as("WorkflowTool WORKFLOW_SCRIPTS 开").isTrue();
        Assertions.assertThat(new MonitorTool(on).isEnabled()).as("MonitorTool MONITOR_TOOL 开").isTrue();
        Assertions.assertThat(new TestingPermissionTool(on).isEnabled()).as("TestingPermissionTool NODE_ENV==='test' 开").isTrue();
    }

    @Test
    @DisplayName("CtxInspectTool / SnipTool 复用既有 CONTEXT_COLLAPSE / HISTORY_SNIP flag")
    void ctxInspectAndSnipReuseExistingFlags() {
        // WHY: CtxInspectTool 门控 = CONTEXT_COLLAPSE、SnipTool 门控 = HISTORY_SNIP，
        // 均已有 FeatureFlags 字段。单独开这两个 flag 验证桩 isEnabled 跟随既有字段，
        // 而非各自建独立旗标（避免双轨）。
        FeatureFlags ctxOn = new FeatureFlags(false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        Assertions.assertThat(new CtxInspectTool(ctxOn).isEnabled()).as("CtxInspectTool CONTEXT_COLLAPSE 开").isTrue();

        FeatureFlags snipOn = new FeatureFlags(false, false, false, false, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        Assertions.assertThat(new SnipTool(snipOn).isEnabled()).as("SnipTool HISTORY_SNIP 开").isTrue();
    }

    @Test
    @DisplayName("7 桩 name 对齐 CC 且不冲突 SPECIAL_TOOLS / ALL_NAMES 重名")
    void stubNamesAlignCcAndNoDuplicate() {
        // WHY: 桩 name 必须对齐 CC 工具名（CtxInspect/...），且不得与
        // SPECIAL_TOOLS 冲突（B6 复验）——ToolRegistry.toOpenAiToolsArray 用
        // SPECIAL_TOOLS.contains(name()) 过滤内部工具。
        Tool[] stubs = {
            new CtxInspectTool(),
            new TerminalCaptureTool(),
            new VerifyPlanExecutionTool(),
            new WorkflowTool(),
            new MonitorTool(),
            new SnipTool(),
            new TestingPermissionTool(),
        };

        String[] expected = {
            "CtxInspect", "TerminalCapture", "VerifyPlanExecution",
            "Workflow", "Monitor", "Snip", "TestingPermission",
        };
        for (int i = 0; i < stubs.length; i++) {
            Assertions.assertThat(stubs[i].name()).as("桩 name 对齐 CC（index %d）", i).isEqualTo(expected[i]);
        }

        // 无重名：name 互异
        long distinct = java.util.Arrays.stream(stubs).map(Tool::name).distinct().count();
        Assertions.assertThat(distinct).as("7 桩 name 互异").isEqualTo(7L);

        // 不进入 SPECIAL_TOOLS（内部 dispatch 工具集）
        for (Tool stub : stubs) {
            Assertions.assertThat(ToolNameConstants.SPECIAL_TOOLS)
                .as("桩 name=%s 不得进入 SPECIAL_TOOLS", stub.name())
                .doesNotContain(stub.name());
        }
    }

    @Test
    @DisplayName("Workflow 桩 name 在 ALL_AGENT_DISALLOWED（hook agent 不暴露，对齐 CC ALL_AGENT_DISALLOWED_TOOLS）")
    void workflowInAllAgentDisallowed() {
        // WHY: CC constants/tools.ts ALL_AGENT_DISALLOWED_TOOLS 含 WORKFLOW_TOOL_NAME
        // （feature('WORKFLOW_SCRIPTS') 时）。Java 端 ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS
        // 已无条件含 WORKFLOW_TOOL_NAME。桩注册后 hook agent 仍不可见（对齐验收 #3）。
        Assertions.assertThat(ToolNameConstants.ALL_AGENT_DISALLOWED_TOOLS)
            .as("Workflow 在 ALL_AGENT_DISALLOWED_TOOLS")
            .contains(WorkflowTool.NAME);
    }
}
