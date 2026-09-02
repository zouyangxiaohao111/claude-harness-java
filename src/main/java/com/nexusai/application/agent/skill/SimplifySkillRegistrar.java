package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /simplify bundled skill 注册器 · 对齐 CC skills/bundled/simplify.ts registerSimplifySkill.
 *
 * <p>L1 语义: 注册 'simplify' skill (user-invocable=true), getPromptForCommand 返回 SIMPLIFY_PROMPT
 *            + (optional) ## Additional Focus 段.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `register(Consumer&lt;BundledSkillDefinition&gt;) → boolean` 签名（P1-4 统一类型）</li>
 *   <li><b>A2 Golden Trace</b>: 注册 skill name='simplify' + description + userInvocable=true + files={} (无文件)</li>
 *   <li><b>A3</b>: getPromptForCommand 无 args → [SIMPLIFY_PROMPT]; 有 args → [prompt + ## Additional Focus]</li>
 *   <li><b>A4</b>: description 默认值对齐 CC simplify.ts:58-59</li>
 *   <li><b>A5</b>: 真实 args="focus on concurrency" → 返回 prompt + '## Additional Focus\n\nfocus on concurrency'</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC 'bun:bundle' 隐式 loader → Java 静态字符串字面量; record 简化 file mapping.
 */
public class SimplifySkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SimplifySkillRegistrar.class);

    public static final String SKILL_NAME = "simplify";
    public static final String DESCRIPTION =
        "Review changed code for reuse, quality, and efficiency, then fix any issues found.";

    /**
     * CC simplify.ts SIMPLIFY_PROMPT 完整文案（:4-53）· Agent 1/2/3 每项带详细说明 + Phase 3 完整
     * （do not argue with the finding）。AGENT_TOOL_NAME='Agent'（AgentTool/constants.ts:1）→ 'Agent tool' 文案与 CC 一致.
     */
    public static final String SIMPLIFY_PROMPT = """
        # Simplify: Code Review and Cleanup

        Review all changed files for reuse, quality, and efficiency. Fix any issues found.

        ## Phase 1: Identify Changes

        Run `git diff` (or `git diff HEAD` if there are staged changes) to see what changed. If there are no git changes, review the most recently modified files that the user mentioned or that you edited earlier in this conversation.

        ## Phase 2: Launch Three Review Agents in Parallel

        Use the Agent tool to launch all three agents concurrently in a single message. Pass each agent the full diff so it has the complete context.

        ### Agent 1: Code Reuse Review

        For each change:

        1. **Search for existing utilities and helpers** that could replace newly written code. Look for similar patterns elsewhere in the codebase — common locations are utility directories, shared modules, and files adjacent to the changed ones.
        2. **Flag any new function that duplicates existing functionality.** Suggest the existing function to use instead.
        3. **Flag any inline logic that could use an existing utility** — hand-rolled string manipulation, manual path handling, custom environment checks, ad-hoc type guards, and similar patterns are common candidates.

        ### Agent 2: Code Quality Review

        Review the same changes for hacky patterns:

        1. **Redundant state**: state that duplicates existing state, cached values that could be derived, observers/effects that could be direct calls
        2. **Parameter sprawl**: adding new parameters to a function instead of generalizing or restructuring existing ones
        3. **Copy-paste with slight variation**: near-duplicate code blocks that should be unified with a shared abstraction
        4. **Leaky abstractions**: exposing internal details that should be encapsulated, or breaking existing abstraction boundaries
        5. **Stringly-typed code**: using raw strings where constants, enums (string unions), or branded types already exist in the codebase
        6. **Unnecessary JSX nesting**: wrapper Boxes/elements that add no layout value — check if inner component props (flexShrink, alignItems, etc.) already provide the needed behavior
        7. **Unnecessary comments**: comments explaining WHAT the code does (well-named identifiers already do that), narrating the change, or referencing the task/caller — delete; keep only non-obvious WHY (hidden constraints, subtle invariants, workarounds)

        ### Agent 3: Efficiency Review

        Review the same changes for efficiency:

        1. **Unnecessary work**: redundant computations, repeated file reads, duplicate network/API calls, N+1 patterns
        2. **Missed concurrency**: independent operations run sequentially when they could run in parallel
        3. **Hot-path bloat**: new blocking work added to startup or per-request/per-render hot paths
        4. **Recurring no-op updates**: state/store updates inside polling loops, intervals, or event handlers that fire unconditionally — add a change-detection guard so downstream consumers aren't notified when nothing changed. Also: if a wrapper function takes an updater/reducer callback, verify it honors same-reference returns (or whatever the "no change" signal is) — otherwise callers' early-return no-ops are silently defeated
        5. **Unnecessary existence checks**: pre-checking file/resource existence before operating (TOCTOU anti-pattern) — operate directly and handle the error
        6. **Memory**: unbounded data structures, missing cleanup, event listener leaks
        7. **Overly broad operations**: reading entire files when only a portion is needed, loading all items when filtering for one

        ## Phase 3: Fix Issues

        Wait for all three agents to complete. Aggregate their findings and fix each issue directly. If a finding is a false positive or not worth addressing, note it and move on — do not argue with the finding, just skip it.

        When done, briefly summarize what was fixed (or confirm the code was already clean).
        """;

    /** CC registerSimplifySkill — 统一产出 BundledSkillDefinition（P1-4）. */
    public boolean register(Consumer<BundledSkillDefinition> registrar) {
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            null,   // whenToUse
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC simplify.ts:60)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            Map.of(),   // files (空，无参考文件)
            (args, cwd) -> {
                String prompt = SIMPLIFY_PROMPT;
                if (args != null && !args.isBlank()) {
                    prompt += "\n\n## Additional Focus\n\n" + args;
                }
                return List.of(PromptBlock.text(prompt));
            }
        );
        registrar.accept(def);
        log.info("[SimplifySkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}