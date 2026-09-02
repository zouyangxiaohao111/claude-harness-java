package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SubagentToolPrompt · 移植 CC {@code tools/AgentTool/prompt.ts:66-287 getPrompt}。
 *
 * <p>职责: 生成 Agent 工具的 LLM 使用指南（agent 列表 / fork 语义 / when NOT to use /
 * usage notes / examples），作为 {@code Tool.prompt()} 的返回值注入工具描述。
 *
 * <p>CC 真源结构（行号已 grep 自验）:
 * <ul>
 *   <li>{@code getToolsDescription} (prompt.ts:15-37) → {@link #getToolsDescription}</li>
 *   <li>{@code formatAgentLine} (prompt.ts:43-46) → {@link #formatAgentLine}</li>
 *   <li>{@code shouldInjectAgentListInMessages} (prompt.ts:59-64) → listViaAttachment 参数
 *       （Java 无 GrowthBook，由 SubagentTool @Value 注入，默认 false → inline agent list）</li>
 *   <li>{@code whenToForkSection} (prompt.ts:80-97) → {@link #whenToForkSection()}</li>
 *   <li>{@code writingThePromptSection} (prompt.ts:99-113) → {@link #writingThePromptSection(boolean)}</li>
 *   <li>{@code forkExamples} (prompt.ts:115-154) → {@link #forkExamples()}</li>
 *   <li>{@code currentExamples} (prompt.ts:156-188) → {@link #currentExamples()}
 *       （Pattern #12: CC 命名反直觉，Java 端同名保留 + JavaDoc 标 CC 原名）</li>
 *   <li>{@code agentListSection} (prompt.ts:196-199) → {@link #agentListSection}</li>
 *   <li>{@code shared} (prompt.ts:202-212) → {@link #sharedSection}</li>
 *   <li>{@code whenNotToUseSection} (prompt.ts:232-240) → {@link #whenNotToUseSection()}</li>
 *   <li>{@code concurrencyNote} (prompt.ts:245-249) → concurrencyNote 局部</li>
 *   <li>coordinator slim return (prompt.ts:216-218) → isCoordinator 分支</li>
 * </ul>
 *
 * <p>外部 build 常量（CC 真源）:
 * <ul>
 *   <li>{@code hasEmbeddedSearchTools() = false}（prompt.ts:3，外部 build 无 embedded bfs/ugrep）
 *       → fileSearchHint/contentSearchHint = "the Glob tool"</li>
 *   <li>{@code USER_TYPE === 'ant' = false}（prompt.ts:273）→ 无 remote CCR isolation 提示</li>
 *   <li>{@code isInProcessTeammate() / isTeammate() = false}（swarms 未启用）→ 无 team 提示</li>
 * </ul>
 *
 * <p>工具名插值（S2-1 决策）: 用 {@link ToolNameConstants} / {@link AgentToolConstants} 常量
 * （值已 grep 自验与 CC 完全一致: FILE_READ_TOOL_NAME='Read' / FILE_WRITE_TOOL_NAME='Write' /
 * GLOB_TOOL_NAME='Glob' / SEND_MESSAGE_TOOL_NAME='SendMessage' / AGENT_TOOL_NAME='Agent'），
 * 替代硬编码字符串（DRY + 单一真源）。CC 模板字面量插值见 prompt.ts:165,225,231,236-238,267。
 */
public final class SubagentToolPrompt {

    private static final Logger log = LoggerFactory.getLogger(SubagentToolPrompt.class);

    /** CC original: AGENT_TOOL_NAME (AgentTool/constants.ts:1) */
    private static final String AGENT_TOOL_NAME = AgentToolConstants.AGENT_TOOL_NAME;
    /** CC original: FILE_READ_TOOL_NAME (FileReadTool/prompt.ts) = "Read" */
    private static final String FILE_READ_TOOL_NAME = ToolNameConstants.FILE_READ_TOOL_NAME;
    /** CC original: FILE_WRITE_TOOL_NAME (FileWriteTool/prompt.ts) = "Write" */
    private static final String FILE_WRITE_TOOL_NAME = ToolNameConstants.FILE_WRITE_TOOL_NAME;
    /** CC original: GLOB_TOOL_NAME (GlobTool/prompt.ts) = "Glob" */
    private static final String GLOB_TOOL_NAME = ToolNameConstants.GLOB_TOOL_NAME;
    /** CC original: SEND_MESSAGE_TOOL_NAME (SendMessageTool/constants.ts) = "SendMessage" */
    private static final String SEND_MESSAGE_TOOL_NAME = ToolNameConstants.SEND_MESSAGE_TOOL_NAME;

    private SubagentToolPrompt() {
    }

    /**
     * 生成 Agent 工具使用指南 · 对齐 CC {@code prompt.ts:66-287 getPrompt}。
     *
     * <p>CC 签名 {@code getPrompt(agentDefinitions, isCoordinator?, allowedAgentTypes?)} 内部
     * 自取全局（fork gate / GrowthBook / subscription），Java 端由 SubagentTool 从注入字段
     * 计算后透传（S2-2 决策: 不改 Tool.prompt() 无参签名）。
     *
     * @param agentDefinitions      所有可用 Agent 定义（SubagentTool.agentRegistry.listAgents()）
     * @param isCoordinator         CC original: isCoordinator (prompt.ts:68)；true 走 slim shared
     *                              return (prompt.ts:216-218)
     * @param allowedAgentTypes     CC original: allowedAgentTypes (prompt.ts:69)；null 不过滤
     * @param options               运行时开关（fork gate / listViaAttachment / backgroundDisabled / pro）
     * @return 完整工具指南文本（非 null）
     */
    public static String getPrompt(
            List<AgentDefinition> agentDefinitions,
            boolean isCoordinator,
            List<String> allowedAgentTypes,
            PromptOptions options) {
        // CC prompt.ts:72-74 effectiveAgents — allowedAgentTypes 过滤
        List<AgentDefinition> effectiveAgents = (allowedAgentTypes != null && !allowedAgentTypes.isEmpty())
                ? agentDefinitions.stream()
                        .filter(a -> allowedAgentTypes.contains(a.agentType()))
                        .collect(Collectors.toCollection(ArrayList::new))
                : agentDefinitions;

        boolean forkEnabled = options.forkEnabled();
        boolean listViaAttachment = options.listViaAttachment();

        // CC prompt.ts:80-97 whenToForkSection（fork 开启才插入）
        String whenToForkSection = forkEnabled ? whenToForkSection() : "";
        // CC prompt.ts:99-113 writingThePromptSection
        String writingThePromptSection = writingThePromptSection(forkEnabled);
        // CC prompt.ts:115-154 forkExamples / :156-188 currentExamples
        String forkExamples = forkExamples();
        String currentExamples = currentExamples();

        // CC prompt.ts:194-199 agentListSection（inline vs attachment 分流）
        String agentListSection = listViaAttachment
                ? "Available agent types are listed in <system-reminder> messages in the conversation."
                : "Available agent types and the tools they have access to:\n"
                        + effectiveAgents.stream()
                                .map(SubagentToolPrompt::formatAgentLine)
                                .collect(Collectors.joining("\n"));

        // CC prompt.ts:202-212 shared
        String shared = sharedSection(agentListSection, forkEnabled);

        // CC prompt.ts:216-218 coordinator slim return
        if (isCoordinator) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentToolPrompt] getPrompt: coordinator 模式返回 slim shared 段, "
                        + "effectiveAgents={}, forkEnabled={}", effectiveAgents.size(), forkEnabled);
            }
            return shared;
        }

        // CC prompt.ts:232-240 whenNotToUseSection（非 fork 才插入 4 条）
        String whenNotToUseSection = forkEnabled ? "" : whenNotToUseSection();

        // CC prompt.ts:245-249 concurrencyNote — listViaAttachment=false && 非 pro 订阅
        //   Java 无 GrowthBook, isProSubscription 由 SubagentTool @Value 注入（默认 false 非 pro）
        String concurrencyNote = (!listViaAttachment && !options.isProSubscription())
                ? "\n- Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses"
                : "";

        StringBuilder sb = new StringBuilder(shared);
        // CC prompt.ts:252-253 shared + whenNotToUseSection + 空行
        sb.append("\n").append(whenNotToUseSection);
        sb.append("\n\nUsage notes:\n");
        // CC prompt.ts:256 concurrencyNote 折入 usage note 第一行
        sb.append("- Always include a short description (3-5 words) summarizing what the agent will do")
                .append(concurrencyNote)
                .append("\n");
        // CC prompt.ts:257 result-not-visible note + background 段 (prompt.ts:259-265)
        sb.append("- When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result.");
        if (!options.backgroundTasksDisabled() && !forkEnabled) {
            // CC prompt.ts:259-265 — !CLAUDE_CODE_DISABLE_BACKGROUND_TASKS && !isInProcessTeammate() && !forkEnabled
            //   isInProcessTeammate() = false（swarms 未启用），故只剩前两个条件
            sb.append("\n- You can optionally run agents in the background using the run_in_background parameter. When an agent runs in the background, you will be automatically notified when it completes — do NOT sleep, poll, or proactively check on its progress. Continue with other work or respond to the user instead.");
            sb.append("\n- **Foreground vs background**: Use foreground (default) when you need the agent's results before you can proceed — e.g., research agents whose findings inform your next steps. Use background when you have genuinely independent work to do in parallel.");
        }
        // CC prompt.ts:267 SendMessage 续接提示
        sb.append("\n- To continue a previously spawned agent, use ")
                .append(SEND_MESSAGE_TOOL_NAME)
                .append(" with the agent's ID or name as the `to` field. The agent resumes with its full context preserved. ")
                .append(forkEnabled
                        ? "Each fresh Agent invocation with a subagent_type starts without context — provide a complete task description."
                        : "Each Agent invocation starts fresh — provide a complete task description.");
        // CC prompt.ts:268-271 信任输出 / 明示代码或研究 / 主动使用 / 并行指令
        sb.append("\n- The agent's outputs should generally be trusted");
        sb.append("\n- Clearly tell the agent whether you expect it to write code or just to do research (search, file reads, web fetches, etc.)")
                .append(forkEnabled ? "" : ", since it is not aware of the user's intent");
        sb.append("\n- If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.");
        sb.append("\n- If the user specifies that they want you to run agents \"in parallel\", you MUST send a single message with multiple ")
                .append(AGENT_TOOL_NAME)
                .append(" tool use content blocks. For example, if you need to launch both a build-validator agent and a test-runner agent in parallel, send a single message with both tool calls.");
        // CC prompt.ts:272-275 isolation:worktree 提示（外部 build 无 remote CCR 段）
        sb.append("\n- You can optionally set `isolation: \"worktree\"` to run the agent in a temporary git worktree, giving it an isolated copy of the repository. The worktree is automatically cleaned up if the agent makes no changes; if changes are made, the worktree path and branch are returned in the result.");
        // CC prompt.ts:276-283 USER_TYPE='ant' / isInProcessTeammate / isTeammate → 外部 build 全部 false, 无附加段
        // CC prompt.ts:284 whenToForkSection + writingThePromptSection
        sb.append(whenToForkSection);
        sb.append(writingThePromptSection);
        // CC prompt.ts:286 forkExamples / currentExamples 二选一
        sb.append("\n\n").append(forkEnabled ? forkExamples : currentExamples);

        if (log.isDebugEnabled()) {
            log.debug("[SubagentToolPrompt] getPrompt: 生成完成, agentCount={}, forkEnabled={}, "
                            + "isCoordinator={}, listViaAttachment={}, len={}",
                    effectiveAgents.size(), forkEnabled, isCoordinator, listViaAttachment, sb.length());
        }
        return sb.toString();
    }

    /**
     * 运行时开关 · SubagentTool 从注入字段计算后透传（S2-2 决策: 不改 Tool.prompt() 签名）。
     */
    public record PromptOptions(
            /** CC original: isForkSubagentEnabled() (prompt.ts:78) */
            boolean forkEnabled,
            /** CC original: shouldInjectAgentListInMessages() (prompt.ts:59-64) */
            boolean listViaAttachment,
            /** CC original: isEnvTruthy(CLAUDE_CODE_DISABLE_BACKGROUND_TASKS) (prompt.ts:259) */
            boolean backgroundTasksDisabled,
            /** CC original: getSubscriptionType() === 'pro' (prompt.ts:246) */
            boolean isProSubscription
    ) {
        public static PromptOptions of(boolean forkEnabled, boolean listViaAttachment,
                                       boolean backgroundTasksDisabled, boolean isProSubscription) {
            return new PromptOptions(forkEnabled, listViaAttachment, backgroundTasksDisabled, isProSubscription);
        }
    }

    /**
     * 工具访问描述 · 对齐 CC {@code getToolsDescription} (prompt.ts:15-37)。
     *
     * <p>4 分支: allowlist+denylist（交集过滤）/ allowlist-only / denylist-only / 无限制。
     */
    static String getToolsDescription(AgentDefinition agent) {
        List<String> tools = agent.tools().orElse(List.of());
        List<String> disallowedTools = agent.disallowedTools().orElse(List.of());
        boolean hasAllowlist = tools != null && !tools.isEmpty();
        boolean hasDenylist = disallowedTools != null && !disallowedTools.isEmpty();

        if (hasAllowlist && hasDenylist) {
            // CC prompt.ts:20-27 双列表: 用 denylist 过滤 allowlist 与运行时行为一致
            Set<String> denySet = new HashSet<>(disallowedTools);
            List<String> effectiveTools = tools.stream()
                    .filter(t -> !denySet.contains(t))
                    .toList();
            if (effectiveTools.isEmpty()) {
                return "None";
            }
            return String.join(", ", effectiveTools);
        } else if (hasAllowlist) {
            return String.join(", ", tools);
        } else if (hasDenylist) {
            return "All tools except " + String.join(", ", disallowedTools);
        }
        return "All tools";
    }

    /**
     * 格式化单个 Agent 行 · 对齐 CC {@code formatAgentLine} (prompt.ts:43-46)。
     *
     * <p>CC: {@code `- ${agent.agentType}: ${agent.whenToUse} (Tools: ${toolsDescription})`}
     */
    static String formatAgentLine(AgentDefinition agent) {
        return "- " + agent.agentType() + ": " + agent.whenToUse() + " (Tools: " + getToolsDescription(agent) + ")";
    }

    /**
     * When to fork 段 · 对齐 CC prompt.ts:80-97。
     *
     * <p>forkEnabled 时才插入（fork 语义 / directive 风格 prompt / 不窥探 / 不臆造）。
     */
    private static String whenToForkSection() {
        return """


                ## When to fork

                Fork yourself (omit `subagent_type`) when the intermediate tool output isn't worth keeping in your context. The criterion is qualitative — "will I need this output again" — not task size.
                - **Research**: fork open-ended questions. If research can be broken into independent questions, launch parallel forks in one message. A fork beats a fresh subagent for this — it inherits context and shares your cache.
                - **Implementation**: prefer to fork implementation work that requires more than a couple of edits. Do research before jumping to implementation.

                Forks are cheap because they share your prompt cache. Don't set `model` on a fork — a different model can't reuse the parent's cache. Pass a short `name` (one or two words, lowercase) so the user can see the fork in the teams panel and steer it mid-run.

                **Don't peek.** The tool result includes an `output_file` path — do not Read or tail it unless the user explicitly asks for a progress check. You get a completion notification; trust it. Reading the transcript mid-flight pulls the fork's tool noise into your context, which defeats the point of forking.

                **Don't race.** After launching, you know nothing about what the fork found. Never fabricate or predict fork results in any format — not as prose, summary, or structured output. The notification arrives as a user-role message in a later turn; it is never something you write yourself. If the user asks a follow-up before the notification lands, tell them the fork is still running — give status, not a guess.

                **Writing a fork prompt.** Since the fork inherits your context, the prompt is a *directive* — what to do, not what the situation is. Be specific about scope: what's in, what's out, what another agent is handling. Don't re-explain background.
                """;
    }

    /**
     * Writing the prompt 段 · 对齐 CC prompt.ts:99-113。
     *
     * <p>{@code forkEnabled} 时首行前缀 "When spawning a fresh agent (with a `subagent_type`)..." +
     * "For fresh agents, terse"（CC prompt.ts:103,110）。
     */
    private static String writingThePromptSection(boolean forkEnabled) {
        String prefix = forkEnabled
                ? "When spawning a fresh agent (with a `subagent_type`), it starts with zero context. "
                : "";
        String terse = forkEnabled ? "For fresh agents, terse" : "Terse";
        return """


                ## Writing the prompt

                %sBrief the agent like a smart colleague who just walked into the room — it hasn't seen this conversation, doesn't know what you've tried, doesn't understand why this task matters.
                - Explain what you're trying to accomplish and why.
                - Describe what you've already learned or ruled out.
                - Give enough context about the surrounding problem that the agent can make judgment calls rather than just following a narrow instruction.
                - If you need a short response, say so ("report in under 200 words").
                - Lookups: hand over the exact command. Investigations: hand over the question — prescribed steps become dead weight when the premise is wrong.

                %s command-style prompts produce shallow, generic work.

                **Never delegate understanding.** Don't write "based on your findings, fix the bug" or "based on the research, implement it." Those phrases push synthesis onto the agent instead of doing it yourself. Write prompts that prove you understood: include file paths, line numbers, what specifically to change.
                """.formatted(prefix, terse);
    }

    /**
     * fork 示例 · 对齐 CC prompt.ts:115-154 forkExamples。
     */
    private static String forkExamples() {
        return """
                Example usage:

                <example>
                user: "What's left on this branch before we can ship?"
                assistant: <thinking>Forking this — it's a survey question. I want the punch list, not the git output in my context.</thinking>
                %s({
                  name: "ship-audit",
                  description: "Branch ship-readiness audit",
                  prompt: "Audit what's left before this branch can ship. Check: uncommitted changes, commits ahead of main, whether tests exist, whether the GrowthBook gate is wired up, whether CI-relevant files changed. Report a punch list — done vs. missing. Under 200 words."
                })
                assistant: Ship-readiness audit running.
                <commentary>
                Turn ends here. The coordinator knows nothing about the findings yet. What follows is a SEPARATE turn — the notification arrives from outside, as a user-role message. It is not something the coordinator writes.
                </commentary>
                [later turn — notification arrives as user message]
                assistant: Audit's back. Three blockers: no tests for the new prompt path, GrowthBook gate wired but not in build_flags.yaml, and one uncommitted file.
                </example>

                <example>
                user: "so is the gate wired up or not"
                <commentary>
                User asks mid-wait. The audit fork was launched to answer exactly this, and it hasn't returned. The coordinator does not have this answer. Give status, not a fabricated result.
                </commentary>
                assistant: Still waiting on the audit — that's one of the things it's checking. Should land shortly.
                </example>

                <example>
                user: "Can you get a second opinion on whether this migration is safe?"
                assistant: <thinking>I'll ask the code-reviewer agent — it won't see my analysis, so it can give an independent read.</thinking>
                <commentary>
                A subagent_type is specified, so the agent starts fresh. It needs full context in the prompt. The briefing explains what to assess and why.
                </commentary>
                %s({
                  name: "migration-review",
                  description: "Independent migration review",
                  subagent_type: "code-reviewer",
                  prompt: "Review migration 0042_user_schema.sql for safety. Context: we're adding a NOT NULL column to a 50M-row table. Existing rows get a backfill default. I want a second opinion on whether the backfill approach is safe under concurrent writes — I've checked locking behavior but want independent verification. Report: is this safe, and if not, what specifically breaks?"
                })
                </example>
                """.formatted(AGENT_TOOL_NAME, AGENT_TOOL_NAME);
    }

    /**
     * 非 fork 示例 · 对齐 CC prompt.ts:156-188 currentExamples。
     *
     * <p>Pattern #12: CC 命名 {@code currentExamples} 反直觉（暗示"当前"实为"旧版非 fork 示例"），
     * Java 端保留同名（与 CC 结构一一对应）+ JavaDoc 标 CC 原名 + 行号。
     */
    private static String currentExamples() {
        return """
                Example usage:

                <example_agent_descriptions>
                "test-runner": use this agent after you are done writing code to run tests
                "greeting-responder": use this agent to respond to user greetings with a friendly joke
                </example_agent_descriptions>

                <example>
                user: "Please write a function that checks if a number is prime"
                assistant: I'm going to use the %s tool to write the following code:
                <code>
                function isPrime(n) {
                  if (n <= 1) return false
                  for (let i = 2; i * i <= n; i++) {
                    if (n %% i === 0) return false
                  }
                  return true
                }
                </code>
                <commentary>
                Since a significant piece of code was written and the task was completed, now use the test-runner agent to run the tests
                </commentary>
                assistant: Uses the %s tool to launch the test-runner agent
                </example>

                <example>
                user: "Hello"
                <commentary>
                Since the user is greeting, use the greeting-responder agent to respond with a friendly joke
                </commentary>
                assistant: "I'm going to use the %s tool to launch the greeting-responder agent"
                </example>
                """.formatted(FILE_WRITE_TOOL_NAME, AGENT_TOOL_NAME, AGENT_TOOL_NAME);
    }

    /**
     * when NOT to use 段 · 对齐 CC prompt.ts:232-240 whenNotToUseSection。
     *
     * <p>非 fork 才插入 4 条。外部 build hasEmbeddedSearchTools()=false →
     * fileSearchHint/contentSearchHint = "the Glob tool"（CC prompt.ts:222-231）。
     */
    private static String whenNotToUseSection() {
        String fileSearchHint = "the " + GLOB_TOOL_NAME + " tool";
        String contentSearchHint = "the " + GLOB_TOOL_NAME + " tool";
        return """

                When NOT to use the %s tool:
                - If you want to read a specific file path, use the %s tool or %s instead of the %s tool, to find the match more quickly
                - If you are searching for a specific class definition like "class Foo", use %s instead, to find the match more quickly
                - If you are searching for code within a specific file or set of 2-3 files, use the %s tool instead of the %s tool, to find the match more quickly
                - Other tasks that are not related to the agent descriptions above
                """.formatted(AGENT_TOOL_NAME, FILE_READ_TOOL_NAME, fileSearchHint,
                AGENT_TOOL_NAME, contentSearchHint, FILE_READ_TOOL_NAME, AGENT_TOOL_NAME);
    }

    /**
     * shared 核心段 · 对齐 CC prompt.ts:202-212。
     *
     * <p>coordinator slim return（prompt.ts:216-218）就是只返回本段。
     */
    private static String sharedSection(String agentListSection, boolean forkEnabled) {
        String forkLine = forkEnabled
                ? "When using the " + AGENT_TOOL_NAME
                        + " tool, specify a subagent_type to use a specialized agent, or omit it to fork yourself — a fork inherits your full conversation context."
                : "When using the " + AGENT_TOOL_NAME
                        + " tool, specify a subagent_type parameter to select which agent type to use. If omitted, the general-purpose agent is used.";
        return "Launch a new agent to handle complex, multi-step tasks autonomously.\n"
                + "\n"
                + "The " + AGENT_TOOL_NAME
                + " tool launches specialized agents (subprocesses) that autonomously handle complex tasks. Each agent type has specific capabilities and tools available to it.\n"
                + "\n"
                + agentListSection
                + "\n"
                + "\n"
                + forkLine;
    }
}
