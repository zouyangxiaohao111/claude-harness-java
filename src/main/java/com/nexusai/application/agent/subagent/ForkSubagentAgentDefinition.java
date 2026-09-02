package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tool.ToolUseContext;

import java.util.Optional;

/**
 * ForkSubagentAgentDefinition · 对齐 CC Open-ClaudeCode/src/tools/AgentTool/forkSubagent.ts:55-77 FORK_AGENT.
 *
 * <p><b>L1 语义</b>: fork subagent 的内置 Agent 定义 · 当 SubagentTool 检测到
 * subagent_type 缺省 + fork gate 启用时, 直接选此 AgentDefinition, 跳过 filterDeniedAgents /
 * findAgent / hasRequiredMcpServers (CC AgentTool.tsx:335 + 367-410 在 fork path 不执行).
 *
 * <p><b>L2 契约 (4 Release Gate)</b>:
 * <ul>
 *   <li><b>A1</b>: agentType = {@link ForkSubagent#FORK_SUBAGENT_TYPE} = "fork"</li>
 *   <li><b>A2</b>: tools = {@link ForkSubagent#FORK_TOOLS} = {@code List.of("*")}
 *       (所有工具可访问, 但 runtime 阶段会移除 task 工具防递归 — SubagentExecutor Step 4)</li>
 *   <li><b>A3</b>: permissionMode = {@link ForkSubagent#PERMISSION_MODE} = "bubble"
 *       (权限冒泡给父终端, CC forkSubagent.ts:62)</li>
 *   <li><b>A4</b>: model = {@link ForkSubagent#MODEL} = "inherit" (继承父 Agent 的 model,
 *       CC forkSubagent.ts:59 + AgentTool.tsx:610)</li>
 *   <li><b>A5</b>: source = {@link ForkSubagent#SOURCE} = "built-in" (对齐 CC ForkSubagent.ts:61)</li>
 *   <li><b>A6</b>: getSystemPrompt() 返回 "" — CC forkSubagent.ts:70 明确说 unused,
 *       fork 子 agent 的 system prompt 透传自父 (CC AgentTool.tsx:496-511 forkParentSystemPrompt),
 *       不是此处的 getSystemPrompt()</li>
 * </ul>
 *
 * <p><b>L3 (Java idiom)</b>: CC {@code satisfies BuiltInAgentDefinition + getSystemPrompt}
 * → Java record {@link AgentDefinition.BuiltInAgentDefinition} + static 工厂 create().
 * 使用 {@link AgentDefinition.BuiltInAgentDefinition#create} 4 参静态工厂构造,
 * 内部 {@code systemPromptFn=null} → getSystemPrompt() 返回空串 (对齐 A6).
 */
public final class ForkSubagentAgentDefinition {

    private ForkSubagentAgentDefinition() {}

    /**
     * 构造 fork subagent 的内置 AgentDefinition · 对齐 CC FORK_AGENT.
     *
     * <p>每次调用 new 一个新实例 (record), 但字段值恒等于 {@link ForkSubagent} 常量.
     * 主路径 SubagentTool.isForkPath 分支调用, 1 turn 内多次 fork 也互不影响.
     *
     * @return ForkAgent 的 BuiltInAgentDefinition
     */
    public static AgentDefinition create() {
        // 使用 canonical BuiltInAgentDefinition 构造器显式设置 permissionMode="bubble"
        //   + model="inherit" — AgentDefinition.BuiltInAgentDefinition.create() 4 参工厂
        //   不支持设置这两个字段, 显式构造才能命中 CC forkSubagent.ts:62 + 59 语义.
        return new AgentDefinition.BuiltInAgentDefinition(
            ForkSubagent.FORK_SUBAGENT_TYPE,                     // agentType
            // [FORK-04 返工] whenToUse 逐字节对齐 CC forkSubagent.ts:62-63 原串
            //   （含 em dash U+2014 「fork — inherits」；「Not selectable via subagent_type;」分号）
            "Implicit fork — inherits full conversation context. Not selectable via subagent_type; "
                + "triggered by omitting subagent_type when the fork experiment is active.", // whenToUse
            java.util.Optional.of(ForkSubagent.FORK_TOOLS),      // tools
            java.util.Optional.empty(),                           // disallowedTools
            java.util.Optional.empty(),                           // skills
            java.util.Optional.empty(),                           // mcpServers
            java.util.Optional.empty(),                           // hooks
            java.util.Optional.empty(),                           // color
            java.util.Optional.of(ForkSubagent.MODEL),            // model ("inherit")
            java.util.Optional.empty(),                           // effort
            java.util.Optional.of(ForkSubagent.PERMISSION_MODE), // permissionMode ("bubble")
            java.util.Optional.of(ForkSubagent.MAX_TURNS),        // maxTurns (200)
            java.util.Optional.empty(),                           // criticalSystemReminder_EXPERIMENTAL
            java.util.Optional.empty(),                           // requiredMcpServers
            java.util.Optional.empty(),                           // background
            java.util.Optional.empty(),                           // initialPrompt
            java.util.Optional.empty(),                           // memory
            java.util.Optional.empty(),                           // isolation
            java.util.Optional.empty(),                           // pendingSnapshotUpdate
            java.util.Optional.empty(),                           // omitClaudeMd
            (modelId, dirs) -> buildForkSystemPrompt(modelId),    // systemPromptFn
            java.util.Optional.empty()                            // callback
        );
    }

    /**
     * Fork path 的 system prompt 工厂 · 对齐 CC forkSubagent.ts:70 systemPrompt unused.
     *
     * <p>CC 行为: getSystemPrompt() 在 fork path 完全 unused, fork 子 agent 用的是父
     * Agent 的 forkParentSystemPrompt (CC AgentTool.tsx:496-511). Java 端这里返回空串
     * 占位, 实际 fork 子 agent 的 systemPrompt 由 SubagentExecutor 通过 forkParentSystemPrompt
     * 透传注入.
     *
     * @param modelId 完整 model id (未使用, 仅为接口兼容)
     * @return 空字符串 (CC unused 占位)
     */
    private static String buildForkSystemPrompt(String modelId) {
        // CC forkSubagent.ts:70: systemPrompt unused — fork 子 agent 继承父 system prompt,
        //   通过 forkParentSystemPrompt 透传 (CC AgentTool.tsx:496-511).
        // Java 端返回空串作为 systemPromptFn 的占位 (SubagentExecutor 不消费此值,
        //   而是用父 ctx.renderedSystemPrompt() 透传).
        if (log.isDebugEnabled()) {
            log.debug("[ForkSubagentAgentDefinition] buildForkSystemPrompt: 返回空串 — "
                + "fork path 下 systemPrompt 透传自父 ctx.renderedSystemPrompt()");
        }
        return "";
    }

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ForkSubagentAgentDefinition.class);
}