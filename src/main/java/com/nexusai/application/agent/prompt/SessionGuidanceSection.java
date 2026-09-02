package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.skillsearch.DiscoverSkillsTool;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolNameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * session_guidance 动态 section · 对齐 CC {@code getSessionSpecificGuidanceSection}
 * （prompts.ts:352-400）。
 *
 * <p>注册为 {@code systemPromptSection('session_guidance', ...)}（prompts.ts:492-493）随 registry
 * 解析；替代旧类的三连门控（缺 4 类子弹，DEL-SP-02 残留；旧类已删，IMP-SP-09 清理断链 @link）。
 *
 * <p><b>per-bullet 门控</b>（CC 真源，prompts.ts:364-396，非注释）：
 * <ol>
 *   <li>ask-user 子弹（:365-367）：hasAskUserQuestionTool 门控</li>
 *   <li>{@code !} 命令子弹（:368-370）：CC 门控为 {@code getIsNonInteractiveSession()}
 *       （交互会话 null → 不注入）；Java Web 后端恒交互式 → <b>恒注入</b>（OPD-SP-22）</li>
 *   <li>agent-tool 子弹（:373）：hasAgentTool 门控 → {@link AgentToolSection#get(boolean)}
 *       按 flags.forkSubagentEnabled 选双分支（fork 变体 :318 / 非 fork :319，RES-SP23 接线）</li>
 *   <li>explore-plan 两子弹（:374-381）：hasAgentTool &amp;&amp; explorePlanAgentsEnabled
 *       &amp;&amp; !forkSubagentEnabled；searchTools 非 embedded 分支 = {@code the Glob or Grep}</li>
 *   <li>skill 子弹（:382-384）：hasSkills = skillToolCommands 非空 &amp;&amp; enabledTools 含 Skill</li>
 *   <li>discover-skills 子弹（:385-389）：feature-gated（Java 默认 false，占位常量见
 *       {@link DiscoverSkillsTool}）</li>
 *   <li>verification 子弹（:390-395）：feature('VERIFICATION_AGENT') + tengu_hive_evidence 恒 false
 *       （3P 默认 false）</li>
 * </ol>
 *
 * <p>空 items → null（prompts.ts:398）；格式
 * {@code ['# Session-specific guidance', ...prependBullets(items)].join('\n')}（:399）。
 */
public final class SessionGuidanceSection {

    private static final Logger log = LoggerFactory.getLogger(SessionGuidanceSection.class);

    private SessionGuidanceSection() {
        // 纯静态工具类
    }

    /**
     * 运行时门控 flags · 对齐 CC 各 feature/env 门控位。
     *
     * @param nonInteractiveSession      CC original: getIsNonInteractiveSession()（state.ts，Java Web 后端
     *                                   恒交互式 → 默认 false，'!' 恒注入 OPD-SP-22；非交互时 '!' 子弹
     *                                   跳过 → 可触发空→null 路径）
     * @param explorePlanAgentsEnabled  CC original: areExplorePlanAgentsEnabled()（builtInAgents.ts:13-18，
     *                                  3P 默认 true；BUILTIN_EXPLORE_PLAN_AGENTS feature 下 A/B 默认 true）
     * @param forkSubagentEnabled       CC original: isForkSubagentEnabled()（forkSubagent.ts:32-39，
     *                                  【RES-SP23 已接线】【R-A12 单源收敛】运行时值源 =
     *                                  {@link ForkSubagent#isForkSubagentEnabled()}，其中 coordinator 项
     *                                  经 {@link ForkSubagent#setCoordinatorModeSupplier} 接动态
     *                                  CoordinatorMode bean（env 真源），与 SubagentTool 内部 fork gate 同源）
     * @param discoverSkillsEnabled     CC original: DISCOVER_SKILLS_TOOL_NAME !== null（feature-gated，
     *                                  默认 false）
     * @param verificationAgentEnabled  CC original: feature('VERIFICATION_AGENT') &&
     *                                  getFeatureValue_CACHED_MAY_BE_STALE('tengu_hive_evidence', false)
     *                                  （3P 默认 false）
     */
    public record SessionGuidanceFlags(
        boolean nonInteractiveSession,
        boolean explorePlanAgentsEnabled,
        boolean forkSubagentEnabled,
        boolean discoverSkillsEnabled,
        boolean verificationAgentEnabled
    ) {

        /**
         * Java 端默认门控：交互式（'!' 恒注入）+ explore-plan 默认可达（3P 默认 true），
         * fork/discover-skills/verification 恒 false（语义基准：fork 关闭的确定性基线，
         * 供测试/基准断言；生产走 {@link #runtimeDefaults()} 取真实 gate）。
         *
         * @return {@code (false, true, false, false, false)}
         */
        public static SessionGuidanceFlags defaults() {
            return new SessionGuidanceFlags(false, true, false, false, false);
        }

        /**
         * 运行时门控 · 对齐 CC getSessionSpecificGuidanceSection 渲染时读
         * {@code isForkSubagentEnabled()}（prompts.ts:317/374，全局 feature/env 判定）。
         *
         * <p><b>【RES-SP23】forkSubagentEnabled 接线真实值</b>：取
         * {@link ForkSubagent#isForkSubagentEnabled()}（forkSubagent.ts:32-39 等价判定）。
         * <b>【R-A12 单源收敛】</b> coordinator 项经 {@link ForkSubagent#setCoordinatorModeSupplier}
         * 接动态 CoordinatorMode bean（env 真源，WF-D-UN-3），与 SubagentTool 内部 fork gate 同源，
         * 不再双源分叉；bean 未注入时回退 config 静态槽。其余位与 {@link #defaults()} 一致
         * （交互式恒注入 OPD-SP-22；explore-plan 3P 默认可达）。
         *
         * @return {@code (false, true, ForkSubagent.isForkSubagentEnabled(), false, false)}
         */
        public static SessionGuidanceFlags runtimeDefaults() {
            return runtimeDefaults(false);
        }

        /**
         * 运行时门控（会话级非交互可注入变体）· [SP-10] 批次 F 新增。
         *
         * <p>nonInteractiveSession 来自 sessions.non_interactive_session 会话列
         * （SystemPromptSections.buildDynamicSections 经 input.nonInteractiveSession() 传入，
         * V57；CC getIsNonInteractiveSession() bootstrap/state.ts:1057）。其余位与
         * {@link #runtimeDefaults()} 一致（forkSubagentEnabled 真实 gate）。
         *
         * @param nonInteractive 会话级非交互门控（'!' 子弹抑制；Java Web 交互会话默认 false）
         * @return {@code (nonInteractive, true, ForkSubagent.isForkSubagentEnabled(), false, false)}
         */
        public static SessionGuidanceFlags runtimeDefaults(boolean nonInteractive) {
            return new SessionGuidanceFlags(
                nonInteractive, true, ForkSubagent.isForkSubagentEnabled(), false, false);
        }
    }

    /**
     * 构建 session_guidance 文本段 · 对齐 CC {@code getSessionSpecificGuidanceSection}。
     *
     * @param enabledTools       当前 LLM 可用工具名集合（对齐 CC {@code new Set(tools.map(_ => _.name))}，
     *                           prompts.ts:464）
     * @param skillToolCommands  skill 命令列表（非空 且 enabledTools 含 Skill 时注入 skill 子弹，
     *                           CC original: {@code skillToolCommands.length > 0}，prompts.ts:357）
     * @param flags              运行时门控（见 {@link SessionGuidanceFlags}）
     * @return 指引文本段（含 {@code # Session-specific guidance} 头）；无命中子弹 → {@code null}（:398）
     */
    public static String build(
        Set<String> enabledTools,
        List<String> skillToolCommands,
        SessionGuidanceFlags flags
    ) {
        SessionGuidanceFlags f = flags != null ? flags : SessionGuidanceFlags.defaults();
        boolean hasAskUserQuestionTool = enabledTools != null
            && enabledTools.contains(ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME);
        // CC original: hasSkills = skillToolCommands.length > 0 && enabledTools.has(SKILL_TOOL_NAME) (prompts.ts:357-358)
        boolean hasSkills = skillToolCommands != null && !skillToolCommands.isEmpty()
            && enabledTools != null && enabledTools.contains(ToolNameConstants.SKILL_TOOL_NAME);
        boolean hasAgentTool = enabledTools != null
            && enabledTools.contains(AgentToolConstants.AGENT_TOOL_NAME);

        List<String> items = new ArrayList<>();
        // 1. ask-user 子弹 · 对齐 CC prompts.ts:365-367
        if (hasAskUserQuestionTool) {
            items.add("If you do not understand why the user has denied a tool call, use the "
                + ToolNameConstants.ASK_USER_QUESTION_TOOL_NAME + " to ask them.");
        }
        // 2. `!` 命令子弹 · 对齐 CC prompts.ts:368-370。CC 门控 getIsNonInteractiveSession()：
        //    非交互返回 null → 不注入；Java Web 后端恒交互式（默认 flags）→ 恒注入（OPD-SP-22）
        if (!f.nonInteractiveSession()) {
            items.add("If you need the user to run a shell command themselves (e.g., an interactive login like `gcloud auth login`), "
                + "suggest they type `! <command>` in the prompt — the `!` prefix runs the command in this session so its output lands directly in the conversation.");
        }
        // 3. agent-tool 子弹 · 对齐 CC prompts.ts:373 → getAgentToolSection() 双分支
        //    （RES-SP23：按 flags.forkSubagentEnabled 选变体，CC prompts.ts:317 isForkSubagentEnabled() ? :318 : :319）
        if (hasAgentTool) {
            items.add(AgentToolSection.get(f.forkSubagentEnabled()));
        }
        // 4. explore-plan 两子弹 · 对齐 CC prompts.ts:374-381；searchTools 非 embedded = "the Glob or Grep"
        //    "more than 3 queries" 值 = CC EXPLORE_AGENT_MIN_QUERIES=3（exploreAgent.ts:59，消费于 prompts.ts:379）。
        //    单一真源：旧 ExploreAgentPrompt.EXPLORE_AGENT_MIN_QUERIES 死常量已删（IMP-SUB-21 #7），勿重新引入。
        if (hasAgentTool && f.explorePlanAgentsEnabled() && !f.forkSubagentEnabled()) {
            items.add("For simple, directed codebase searches (e.g. for a specific file/class/function) use the "
                + ToolNameConstants.GLOB_TOOL_NAME + " or " + ToolNameConstants.GREP_TOOL_NAME + " directly.");
            items.add("For broader codebase exploration and deep research, use the " + AgentToolConstants.AGENT_TOOL_NAME
                + " tool with subagent_type=Explore. This is slower than using the "
                + ToolNameConstants.GLOB_TOOL_NAME + " or " + ToolNameConstants.GREP_TOOL_NAME
                + " directly, so use this only when a simple, directed search proves to be insufficient or when your task will clearly require more than 3 queries.");
        }
        // 5. skill 子弹 · 对齐 CC prompts.ts:382-384
        if (hasSkills) {
            items.add("/<skill-name> (e.g., /commit) is shorthand for users to invoke a user-invocable skill. "
                + "When executed, the skill gets expanded to a full prompt. Use the "
                + ToolNameConstants.SKILL_TOOL_NAME + " tool to execute them. IMPORTANT: Only use "
                + ToolNameConstants.SKILL_TOOL_NAME
                + " for skills listed in its user-invocable skills section - do not guess or use built-in CLI commands.");
        }
        // 6. discover-skills 子弹 · 对齐 CC prompts.ts:385-389（feature-gated，默认 false）
        if (f.discoverSkillsEnabled() && hasSkills) {
            items.add("Relevant skills are automatically surfaced each turn as \"Skills relevant to your task:\" reminders. "
                + "If you're about to do something those don't cover — a mid-task pivot, an unusual workflow, a multi-step plan — call "
                + DiscoverSkillsTool.DISCOVER_SKILLS_TOOL_NAME
                + " with a specific description of what you're doing. Skills already visible or loaded are filtered automatically. "
                + "Skip this if the surfaced skills already cover your next action.");
        }
        // 7. verification 子弹 · 对齐 CC prompts.ts:390-395（3P 恒 false，Java 无接线）
        if (f.verificationAgentEnabled() && hasAgentTool) {
            items.add("The contract: when non-trivial implementation happens on your turn, independent adversarial verification must happen before you report completion — regardless of who did the implementing (you directly, a fork you spawned, or a subagent). You are the one reporting to the user; you own the gate. Non-trivial means: 3+ file edits, backend/API changes, or infrastructure changes. Spawn the "
                + AgentToolConstants.AGENT_TOOL_NAME + " tool with subagent_type=\"verification\". Your own checks, caveats, and a fork's self-checks do NOT substitute — only the verifier assigns a verdict; you cannot self-assign PARTIAL. Pass the original user request, all files changed (by anyone), the approach, and the plan file path if applicable. Flag concerns if you have them but do NOT share test results or claim things work. On FAIL: fix, resume the verifier with its findings plus your fix, repeat until PASS. On PASS: spot-check it — re-run 2-3 commands from its report, confirm every PASS has a Command run block with output that matches your re-run. If any PASS lacks a command block or diverges, resume the verifier with the specifics. On PARTIAL (from the verifier): report what passed and what could not be verified.");
        }

        if (items.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionGuidanceSection] 无命中子弹 → 返回 null（CC prompts.ts:398 items.length===0）");
            }
            return null;
        }
        // 对齐 CC prompts.ts:399: ['# Session-specific guidance', ...prependBullets(items)].join('\n')
        String result = "# Session-specific guidance\n"
            + String.join("\n", StaticPromptSections.prependBullets(new ArrayList<>(items)));
        if (log.isDebugEnabled()) {
            log.debug("[SessionGuidanceSection] 构建完成: items={} 行（恒含 '!' 子弹）", items.size());
        }
        return result;
    }
}
