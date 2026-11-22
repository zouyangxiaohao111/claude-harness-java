package com.nexusai.application.agent.coordinator;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.AgentToolUtils;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [coordinator 定义侧] worker 内置 Agent 定义 · 对齐 claude.exe 2.1.328（偏移 343533225）。
 *
 * <h2>为何是这组断言</h2>
 * <p>CC coordinator 的系统提示多处要求 {@code subagent_type: "worker"}。若定义缺失，模型每次派活
 * 都撞 {@code Agent type 'worker' not found}。exe 2.1.328 的 worker 定义已从 CCB 的
 * 「显式工具清单 + 785 字 prompt」改为：
 * <pre>
 * tools: ['*'], maxTurns: 500, permissionMode: 'bubble'
 * </pre>
 * 以及 Environment/Scope/Resumed Tasks/When Things Go Wrong/Output 五段 prompt。
 *
 * <p>夹具不依赖真实 env/Spring：{@link BuiltInAgents#setCoordinatorMode} 显式注入。
 */
@DisplayName("coordinator 定义侧 · worker AgentDefinition (exe 2.1.328)")
class WorkerAgentDefinitionTest {

    /** exe worker whenToUse（逐字）。 */
    private static final String CC_WHEN_TO_USE =
        "For executing tasks autonomously — research, implementation, or verification.";

    /** exe worker prompt 全文 golden（条件 fan-out 行未注入）。 */
    private static final String CC_WORKER_SYSTEM_PROMPT = """
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

    private static CoordinatorMode coordinatorOn() {
        return new CoordinatorMode(() -> false, () -> "1");
    }

    @AfterEach
    void resetCoordinatorGate() {
        BuiltInAgents.setCoordinatorMode(null);
    }

    // ─────────────────────── 1. 可命中性 ───────────────────────

    @Test
    @DisplayName("coordinator 激活时 BuiltInAgents.get('worker') 命中 —— 模型派活不再撞 Agent type not found")
    void workerResolvableViaBuiltInAgentsGet_whenCoordinatorActive() {
        BuiltInAgents.setCoordinatorMode(coordinatorOn());

        AgentDefinition def = BuiltInAgents.get("worker");

        assertThat(def).isNotNull();
        assertThat(def.agentType()).isEqualTo("worker");
        assertThat(def.source()).isEqualTo("built-in");
    }

    @Test
    @DisplayName("coordinator 关闭时 get('worker') 为 null —— worker 只在 coordinator 模式存在")
    void workerNotResolvableWhenCoordinatorInactive() {
        BuiltInAgents.setCoordinatorMode(null);
        assertThat(BuiltInAgents.get("worker")).isNull();
    }

    @Test
    @DisplayName("getCoordinatorAgents() 只含 worker（exe getCoordinatorAgents 返回 [worker]）")
    void getCoordinatorAgentsReturnsOnlyWorker() {
        List<AgentDefinition> agents = WorkerAgentDefinition.getCoordinatorAgents();

        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).agentType()).isEqualTo("worker");
        assertThat(agents.get(0)).isSameAs(WorkerAgentDefinition.WORKER_AGENT);
    }

    // ─────────────────────── 2. 定义字段（exe 2.1.328） ───────────────────────

    @Test
    @DisplayName("tools = ['*']（exe tools: ['*']；usesAllTools 精确识别）")
    void toolsIsWildcard() {
        AgentDefinition worker = WorkerAgentDefinition.WORKER_AGENT;

        assertThat(worker.tools()).contains(List.of("*"));
        assertThat(worker.usesAllTools()).isTrue();
    }

    @Test
    @DisplayName("maxTurns = 500（exe maxTurns: 500）")
    void maxTurnsIs500() {
        assertThat(WorkerAgentDefinition.WORKER_AGENT.maxTurns()).contains(500);
    }

    @Test
    @DisplayName("permissionMode = 'bubble'（exe permissionMode: 'bubble'）")
    void permissionModeIsBubble() {
        assertThat(WorkerAgentDefinition.WORKER_AGENT.permissionMode()).contains("bubble");
    }

    @Test
    @DisplayName("whenToUse 逐字等于 exe")
    void whenToUseVerbatimCc() {
        assertThat(WorkerAgentDefinition.WORKER_AGENT.whenToUse()).isEqualTo(CC_WHEN_TO_USE);
    }

    // ─────────────────────── 3. prompt 逐字对齐 ───────────────────────

    /** exe worker prompt 的 fan-out 行（{@code ex()>1} 时注入）。 */
    private static final String CC_FANOUT_LINE =
        "- If you have the Agent tool, you may use it to fan out (e.g. `/simplify`, `/code-review`, or your own "
            + "parallel research/verification) — workers at the depth cap don't receive it";

    @Test
    @DisplayName("agent 专属 prompt 模板逐字等于 exe 2.1.328（含 fan-out 占位符，无尾随换行）")
    void workerSpecificPromptByteExactVsCc() {
        String p = WorkerAgentDefinition.WORKER_SPECIFIC_PROMPT;

        assertThat(p).isEqualTo(CC_WORKER_SYSTEM_PROMPT);
        assertThat(p)
            .as("text block 恒带尾随 \\n，实现必须 stripTrailing")
            .doesNotEndWith("\n")
            .endsWith("Bad summary: \"I looked at files X, Y, and Z. Y has the changes you mentioned.\"")
            .contains("__FANOUT_LINE__");
    }

    @Test
    @DisplayName("渲染结果与 exe 条件一致：ex()>1 注入 fan-out 行，否则不注入")
    void renderedPromptMatchesCcConditional() {
        String expected = CC_WORKER_SYSTEM_PROMPT.replace("__FANOUT_LINE__",
            CoordinatorMode.workersCanFanOut() ? CC_FANOUT_LINE + "\n" : "");

        assertThat(WorkerAgentDefinition.getWorkerSystemPrompt())
            .isEqualTo(expected)
            .doesNotContain("__FANOUT_LINE__");
    }

    @Test
    @DisplayName("getSystemPrompt() 以新首行开头，且旧 CCB 首行已消失")
    void systemPromptStartsWithNewFirstLine() {
        String built = WorkerAgentDefinition.WORKER_AGENT.getSystemPrompt(null, List.of());

        assertThat(built).startsWith("You are a worker agent executing a task assigned by the coordinator.");
        assertThat(built).doesNotContain("You are a worker agent spawned by a coordinator.");
    }

    // ─────────────────────── 4. userContext 用的 worker 工具清单 ───────────────────────

    @Test
    @DisplayName("getWorkerTools() 仍做内部工具减法（workerToolsContext 文案用）")
    void workerToolsExcludeInternalOrchestrationTools() {
        Set<String> internal = CoordinatorMode.INTERNAL_WORKER_TOOLS;
        List<String> workerTools = WorkerAgentDefinition.getWorkerTools();

        assertThat(workerTools).doesNotContainAnyElementsOf(internal);
        assertThat(workerTools).contains(
            ToolNameConstants.BASH_TOOL_NAME,
            ToolNameConstants.FILE_READ_TOOL_NAME,
            ToolNameConstants.FILE_EDIT_TOOL_NAME,
            ToolNameConstants.FILE_WRITE_TOOL_NAME);
        assertThat(workerTools)
            .doesNotContain(ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME);
        assertThat(workerTools).hasSize(
            AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS.size()
                - AgentToolUtils.ASYNC_AGENT_ALLOWED_TOOLS.stream().filter(internal::contains)
                    .collect(Collectors.toSet()).size());
    }
}
