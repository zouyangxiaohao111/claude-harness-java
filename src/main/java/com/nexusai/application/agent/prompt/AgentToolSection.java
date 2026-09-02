package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.tool.AgentToolConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 工具提示段 · 对齐 CC {@code getAgentToolSection}（Open-ClaudeCode/src/constants/prompts.ts:316-320）。
 *
 * <p>CC 真源（非 fork 变体，prompts.ts:319，CC original: getAgentToolSection）：
 * <pre>
 * Use the ${AGENT_TOOL_NAME} tool with specialized agents when the task at hand matches the
 * agent's description. Subagents are valuable for parallelizing independent queries or for
 * protecting the main context window from excessive results, but they should not be used
 * excessively when not needed. Importantly, avoid duplicating work that subagents are
 * already doing - if you delegate research to a subagent, do not also perform the same
 * searches yourself.
 * </pre>
 *
 * <p><b>CC 语义定位</b>：在 CC 中该段是<b>父会话</b> session_guidance 的子弹之一（prompts.ts:373
 * {@code hasAgentTool ? getAgentToolSection() : null} 门控 + :492-493 systemPromptSection('session_guidance')
 * 注册），并非子代理自身 prompt。CC 子代理自身 prompt = {@code agent.getSystemPrompt()}+env
 * （runAgent.ts:906-932），无任何追加 base。本 Session（IMP-SP-SUB）按用户拍板（OPD-SP-01/17）
 * 用该真源文本替换已删除的伪真源（CC 无 code.py/SUB_SYSTEM，grep 自验 0 命中），作为子代理
 * system prompt 的追加 base。
 *
 * <p><b>双分支接线（RES-SP23）</b>：{@link #get(boolean)} 双分支已按 OPD-SP-23 接线 ——
 * {@link SessionGuidanceSection} 的 agent-tool 子弹按 {@code flags.forkSubagentEnabled()} 选
 * fork 变体（prompts.ts:318）或非 fork 变体（prompts.ts:319），运行时判定值源 =
 * {@link ForkSubagent#isForkSubagentEnabled()}（forkSubagent.ts:32-39）。
 * 无参 {@link #get()} 恒非 fork 变体，保留给 fallback base 调用方（ToolRegistrationConfig /
 * SubagentTool，REQ-SP23-3 非 fork 不回归）。
 */
public final class AgentToolSection {

    private static final Logger log = LoggerFactory.getLogger(AgentToolSection.class);

    private AgentToolSection() {
        // 静态文本源容器
    }

    /**
     * getAgentToolSection() 非 fork 变体 · 对齐 CC prompts.ts:319。
     *
     * <p>fallback base 调用方（ToolRegistrationConfig:388 / SubagentTool:1890）恒使用非 fork 变体
     * （CC getAgentToolSection 只用于 session_guidance 子弹，Java fallback base 语义不动，
     * REQ-SP23-3）。session_guidance 子弹走 {@link #get(boolean)} 双分支。
     *
     * @return 非 fork 变体文本（{@code AGENT_TOOL_NAME}='Agent' 插值后）
     */
    public static String get() {
        return get(false);
    }

    /**
     * getAgentToolSection() 双分支 · 对齐 CC prompts.ts:316-320。
     *
     * @param forkSubagentEnabled 是否启用 fork 子代理（CC original: isForkSubagentEnabled(),
     *                            forkSubagent.ts:32-40）
     * @return fork 变体（prompts.ts:318）或非 fork 变体（prompts.ts:319）
     */
    public static String get(boolean forkSubagentEnabled) {
        String text = forkSubagentEnabled
            ? "Calling " + AgentToolConstants.AGENT_TOOL_NAME
                + " without a subagent_type creates a fork, which runs in the background and keeps its tool output out of your context — so you can keep chatting with the user while it works. Reach for it when research or multi-step implementation work would otherwise fill your context with raw output you won't need again. **If you ARE the fork** — execute directly; do not re-delegate."
            : "Use the " + AgentToolConstants.AGENT_TOOL_NAME
                + " tool with specialized agents when the task at hand matches the agent's description. Subagents are valuable for parallelizing independent queries or for protecting the main context window from excessive results, but they should not be used excessively when not needed. Importantly, avoid duplicating work that subagents are already doing - if you delegate research to a subagent, do not also perform the same searches yourself.";
        if (log.isDebugEnabled()) {
            log.debug("[AgentToolSection] 生成 {} 变体, len={}（CC prompts.ts:{}）",
                forkSubagentEnabled ? "fork" : "非 fork", text.length(),
                forkSubagentEnabled ? "318" : "319");
        }
        return text;
    }
}
