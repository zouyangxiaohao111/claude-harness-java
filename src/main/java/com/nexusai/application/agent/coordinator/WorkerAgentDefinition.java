package com.nexusai.application.agent.coordinator;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.AgentToolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinator 模式的 worker Agent 定义 · 对齐 claude.exe 2.1.328 的 worker agent。
 *
 * <p><b>口径变更（2026-11-19，对齐 exe 2.1.328）</b>：旧实现移植自 CCB
 * {@code src/coordinator/workerAgent.ts}（显式工具清单 + 785 字 prompt）。exe 2.1.328
 * 的 worker 定义（静态偏移 343533225）已改为：
 * <pre>
 * var t = {
 *   agentType: 'worker',
 *   whenToUse: 'For executing tasks autonomously — research, implementation, or verification.',
 *   tools: ['*'],
 *   maxTurns: 500,
 *   permissionMode: 'bubble',
 *   source: 'built-in',
 *   baseDir: 'built-in',
 *   getSystemPrompt: () => e()
 * };
 * </pre>
 *
 * <p>因此本类：
 * <ul>
 *   <li>{@code tools = ["*"]}（{@link AgentDefinition#usesAllTools()} 精确识别）；</li>
 *   <li>{@code maxTurns = 500}；</li>
 *   <li>{@code permissionMode = "bubble"}（{@code SubagentExecutor.resolvePermissionMode} 已显式映射）；</li>
 *   <li>system prompt 换成 exe 新版全文（Environment / Scope / Resumed Tasks /
 *       When Things Go Wrong / Output）。</li>
 * </ul>
 *
 * <p><b>未移植的条件段</b>：exe worker prompt 有一行
 * {@code ex()>1 ? "- If you have the Agent tool, you may use it to fan out ..." : ""}。
 * {@code ex()} 的静态语义未确证（worker 深度/并发上限），本仓也无对应旋钮，故不注入该行。
 *
 * <p>消费点不变：{@link BuiltInAgents#getBuiltInAgents()} 的 coordinator 分支整表替换为
 * {@link #getCoordinatorAgents()}（exe 的 getCoordinatorAgents 返回 {@code [worker]}）。
 */
public final class WorkerAgentDefinition {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgentDefinition.class);

    /** exe worker agentType。 */
    public static final String WORKER_AGENT_TYPE = "worker";

    /** exe worker {@code whenToUse} 字面量（逐字）。 */
    public static final String WHEN_TO_USE =
        "For executing tasks autonomously — research, implementation, or verification.";

    /** exe worker {@code maxTurns}。 */
    public static final int WORKER_MAX_TURNS = 500;

    /** exe worker {@code permissionMode}。 */
    public static final String WORKER_PERMISSION_MODE = "bubble";

    /**
     * exe 2.1.328 worker system prompt 全文（逐字，末行无尾随换行）。
     *
     * <p>条件 fan-out 行未注入（见类注释）。
     */
    public static final String WORKER_SPECIFIC_PROMPT =
        """
        You are a worker agent executing a task assigned by the coordinator.

        ## Environment

        - Other workers may be making changes on this branch. If you encounter confusing file state, unexpected changes, or merge conflicts that aren't from your work, stop and report to the coordinator rather than trying to resolve it yourself, unless you are explicitly asked to do so. Don't modify code you don't understand.

        ## Scope

        Complete exactly what was asked. Don't fix unrelated issues you discover — suggest them as follow-ups instead.
        - If you changed any files, commit your changes when done. Use a clear, descriptive commit message. Only stage files you actually changed — never use `git add .` or `git add -A`. Report the commit hash in your summary.
        __FANOUT_LINE__- Limit changes to what your task requires

        ## Resumed Tasks

        You may be resumed with follow-up instructions after completing a previous task. When this happens:
        - You retain full context from your previous work — use it
        - Build on what you already know; don't re-read files you've already seen unless they may have changed
        - Your new instructions may be brief (e.g., "now add tests for that") — this is intentional, not ambiguous

        ## When Things Go Wrong

        - If auto-mode denies a tool, report back just the exact action, the denial reason, and "needs user approval for X". The coordinator will get the approval and send it to you — retry once it arrives; don't narrate the earlier denial.
        - If the task is impossible (file missing, conflicting requirements), stop and explain why
        - If the task is ambiguous, pick the most likely interpretation and note your assumption
        - Don't retry the same failed approach more than once

        ## Output

        Your response goes directly to the coordinator (not the user). Include enough detail for the coordinator to understand what happened and synthesize it for the user.

        Structure your response as:
        1. **What you did or found** — be specific with file paths, line numbers, code snippets
        2. **Summary:** One sentence the coordinator can relay to the user

        Good summary: "Added Redis cache implementation. Tests pass, typecheck clean. Committed abc123."
        Bad summary: "I looked at files X, Y, and Z. Y has the changes you mentioned."
        """.stripTrailing();

    /** exe worker prompt 的 fan-out 条件行（{@code ex()>1} 时注入）。 */
    private static final String FANOUT_LINE =
        "- If you have the " + com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME
            + " tool, you may use it to fan out (e.g. `/simplify`, `/code-review`, or your own parallel "
            + "research/verification) — workers at the depth cap don't receive it";

    /** 模板占位符（替换为空或 fan-out 行）。 */
    private static final String TOKEN_FANOUT_LINE = "__FANOUT_LINE__";

    /**
     * 渲染后的 worker system prompt：按 {@link CoordinatorMode#workersCanFanOut()}
     * （exe {@code ex()>1}）决定是否注入 fan-out 行。
     */
    public static String getWorkerSystemPrompt() {
        return WORKER_SPECIFIC_PROMPT.replace(TOKEN_FANOUT_LINE,
            CoordinatorMode.workersCanFanOut() ? FANOUT_LINE + "\n" : "");
    }

    /**
     * worker 可用工具（userContext 文案用）· {@code ASYNC_AGENT_ALLOWED_TOOLS} 减
     * {@link CoordinatorMode#INTERNAL_WORKER_TOOLS}。
     *
     * <p>worker AgentDefinition 本身已改为 {@code ["*"]}；本方法保留给
     * {@code workerToolsContext} 的清单计算（worker 实际可用的工具面）。
     */
    public static List<String> getWorkerTools() {
        List<String> workerTools = new ArrayList<>();
        for (String name : AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS) {
            if (!CoordinatorMode.INTERNAL_WORKER_TOOLS.contains(name)) {
                workerTools.add(name);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[WorkerAgentDefinition] getWorkerTools 生成 worker 工具清单: total={} removed={}",
                workerTools.size(), AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS.size() - workerTools.size());
        }
        return workerTools;
    }

    /**
     * worker 内置 Agent 定义 · 对齐 exe 2.1.328：
     * {@code tools=["*"]}、{@code maxTurns=500}、{@code permissionMode="bubble"}。
     */
    public static final AgentDefinition WORKER_AGENT = AgentDefinition.BuiltInAgentDefinition
        .builder(WORKER_AGENT_TYPE, WHEN_TO_USE,
            // CC runAgent.ts getAgentSystemPrompt = [agentPrompt] + notes + env
            (modelId, dirs) -> BuiltInAgents.buildStandaloneSystemPrompt(getWorkerSystemPrompt(), modelId, dirs))
        .tools(List.of("*"))
        .maxTurns(WORKER_MAX_TURNS)
        .permissionMode(WORKER_PERMISSION_MODE)
        .build();

    /**
     * coordinator 模式下可用的 agent 列表 · 对齐 exe {@code getCoordinatorAgents()}
     * （返回 {@code [worker]}，整表替换 base 列表）。
     */
    public static List<AgentDefinition> getCoordinatorAgents() {
        return List.of(WORKER_AGENT);
    }

    private WorkerAgentDefinition() {
    }
}
