package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [S4] resolveAgentTools 调 filterToolsForAgent RED-GREEN 双证测试 (P1 差异项 5).
 *
 * <p>规则九 (验证意图): CC {@code resolveAgentTools} (agentToolUtils.ts:122-225) 内部必调
 * {@code filterToolsForAgent} (:142) 剔除 Agent/TaskOutput/ExitPlanMode 等递归/主线程抽象工具.
 * Java 旧实现 :1025-1039 {@code subagentToolRegistry.all()} 全量返回不调 filter = 双路径漂移风险
 * (过滤靠 SubagentTool.createSubagentToolRegistry 外层做). 若 resolveAgentTools 未过滤,
 * ALL_AGENT_DISALLOWED_TOOLS 里的工具会漏进子 Agent 工具池 (递归风险).
 *
 * <p>测试方式: resolveAgentTools 是 package-private, 用真实 ToolRegistry + Mockito Tool mock
 * 直接调用. RED 依据: 回退 all() 全量返回 → Agent/TaskOutput 未过滤 (断言红).
 */
@DisplayName("[S4] resolveAgentTools 内部调 filterToolsForAgent (ALL_AGENT_DISALLOWED_TOOLS 剔除)")
class ResolveAgentToolsTest {

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        // ToolRegistry.all() 过滤 isEnabled() (ToolRegistry.java:371) — mock 默认 false → 注册了也拿不到
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    private static SubagentExecutor executorWith(ToolRegistry registry) {
        return new SubagentExecutor(registry, null, null, null, null, "model", "system-prompt");
    }

    private static AgentDefinition builtInWildcardAgent() {
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .tools(List.of("*"))
            .build();
    }

    private static AgentDefinition builtInByNameAgent() {
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .tools(List.of("Bash", "NotExist"))
            .build();
    }

    private static AgentDefinition builtInEmptyToolsAgent() {
        // 显式空列表 = CC tools: [] (非 undefined) — 非 wildcard, 应返回空工具集 (CC :162-173).
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .tools(List.of())
            .build();
    }

    private static AgentDefinition builtInStarPlusByNameAgent() {
        // [IMP-SUB-18] 多元素含 '*' → 非 wildcard (CC :163-165 length===1), 走 by-name.
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .tools(List.of("*", "Bash"))
            .build();
    }

    private static AgentDefinition builtInByNamePlusStarAgent() {
        // [IMP-SUB-18] 逆序含 '*' → 同样非 wildcard, 走 by-name.
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .tools(List.of("Bash", "*"))
            .build();
    }

    @Test
    @DisplayName("wildcard agent: ALL_AGENT_DISALLOWED_TOOLS (Agent/TaskOutput) 被过滤 (CC agentToolUtils.ts:142)")
    void resolveAgentTools_shouldFilterDisallowedTools_forWildcard() {
        // WHY: Agent/TaskOutput 是递归/主线程抽象工具 — 漏进子 Agent 池 = 嵌套子 Agent 递归风险.
        //   CC constants/tools.ts:36-46 ALL_AGENT_DISALLOWED_TOOLS.
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));
        registry.register(tool(ToolNameConstants.FILE_READ_TOOL_NAME));
        registry.register(tool(AgentToolConstants.AGENT_TOOL_NAME));          // Agent — 必须被过滤
        registry.register(tool(ToolNameConstants.TASK_OUTPUT_TOOL_NAME));     // TaskOutput — 必须被过滤

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInWildcardAgent());

        assertThat(resolved).extracting(Tool::name)
            .as("ALL_AGENT_DISALLOWED_TOOLS 必须剔除 (Agent/TaskOutput), 保留 Bash/Read")
            .containsExactly(ToolNameConstants.BASH_TOOL_NAME, ToolNameConstants.FILE_READ_TOOL_NAME)
            .doesNotContain(AgentToolConstants.AGENT_TOOL_NAME, ToolNameConstants.TASK_OUTPUT_TOOL_NAME);
    }

    @Test
    @DisplayName("by-name 解析: 仅解析声明的工具名, 不存在的名静默跳过 (CC :175-216 valid/invalid 拆分)")
    void resolveAgentTools_shouldResolveByName_notReturnAll() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));
        registry.register(tool(ToolNameConstants.FILE_READ_TOOL_NAME));

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInByNameAgent());

        assertThat(resolved).extracting(Tool::name)
            .as("tools=['Bash','NotExist'] → 只解析 Bash (NotExist 不存在, 静默跳过)")
            .containsExactly(ToolNameConstants.BASH_TOOL_NAME);
    }

    @Test
    @DisplayName("built-in agent 不触发 CUSTOM_AGENT_DISALLOWED 追加过滤 (CC source==='built-in' 分支)")
    void resolveAgentTools_builtIn_shouldNotApplyCustomDisallow() {
        // WHY: CC filterToolsForAgent (:144 isBuiltIn) — built-in agent 跳过 CUSTOM_AGENT_DISALLOWED
        //   (自定义 agent 额外 disallow). 若误当 custom, 语义漂移.
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInWildcardAgent());

        assertThat(resolved).extracting(Tool::name)
            .as("built-in wildcard 保留 Bash (custom disallow 不生效)")
            .contains(ToolNameConstants.BASH_TOOL_NAME);
    }

    @Test
    @DisplayName("显式空 tools 列表 → 空工具集 (C-9: wildcard 仅 undefined/['*'], CC agentToolUtils.ts:163-165)")
    void resolveAgentTools_explicitEmptyList_shouldReturnEmptyTools() {
        // WHY: CC hasWildcard = agentTools === undefined || (length===1 && [0]==='*') —
        //   显式空列表 (tools: []) 既非 undefined 也非 ['*'], 走 by-name 循环返回空集.
        //   旧 Java 实现把空列表也当 wildcard (双语义混淆) = 全量放行, 与 CC 语义相悖 (S4-1 C-9).
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));
        registry.register(tool(ToolNameConstants.FILE_READ_TOOL_NAME));

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInEmptyToolsAgent());

        assertThat(resolved)
            .as("显式空列表必须返回空工具集 (CC by-name 循环空遍历), 不得全量放行")
            .isEmpty();
    }

    @Test
    @DisplayName("['*','x'] 多元素含 * → by-name 精确解析, Read 不得因 * 全量漏入 (IMP-SUB-18 安全边界)")
    void resolveAgentTools_starPlusByName_shouldNotPassAll() {
        // WHY: 旧 usesAllTools()=tools().contains("*") 判 wildcard → ['*','Bash'] 全量放行,
        //   Read 等未声明工具漏进子 Agent 池. CC hasWildcard 要求 length===1 && [0]==='*'
        //   (agentToolUtils.ts:163-165) → 本输入走 by-name 循环, '*' 不命中任何工具名, 仅 Bash.
        //   若回退 contains("*"), 本用例变红 (规则九红线).
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));
        registry.register(tool(ToolNameConstants.FILE_READ_TOOL_NAME));

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInStarPlusByNameAgent());

        assertThat(resolved).extracting(Tool::name)
            .as("tools=['*','Bash'] → by-name 精确解析: 仅 Bash, Read 不得因 '*' 全量漏入")
            .containsExactly(ToolNameConstants.BASH_TOOL_NAME);
    }

    @Test
    @DisplayName("['x','*'] 多元素含 * (逆序) → by-name 精确解析, 不得全量放行")
    void resolveAgentTools_byNamePlusStar_shouldNotPassAll() {
        // WHY: 逆序含 '*' 同样触发旧 contains("*") 误判 (元素相等) → 全量放行; CC 精确
        //   ['*'] 单元素判定 → 本输入走 by-name, 仅 Bash, Read 不漏入.
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(ToolNameConstants.BASH_TOOL_NAME));
        registry.register(tool(ToolNameConstants.FILE_READ_TOOL_NAME));

        List<Tool> resolved = executorWith(registry).resolveAgentTools(builtInByNamePlusStarAgent());

        assertThat(resolved).extracting(Tool::name)
            .as("tools=['Bash','*'] → by-name 精确解析: 仅 Bash, Read 不得全量漏入")
            .containsExactly(ToolNameConstants.BASH_TOOL_NAME);
    }
}
