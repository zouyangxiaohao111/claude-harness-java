package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * /remember bundled skill 注册器 · 对齐 CC skills/bundled/remember.ts registerRememberSkill.
 *
 * <p>L1 语义: ant-only skill (USER_TYPE != 'ant' 不注册); 审查 auto-memory entries,
 *            提出 promotions/cleanup/ambiguous 分组报告 (不直接修改文件).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `register(sink, isAntUser, isAutoMemoryEnabled) → boolean` 签名</li>
 *   <li><b>A2 Golden Trace</b>: !isAntUser → false; isAntUser + autoMem enabled → true 注册 'remember'</li>
 *   <li><b>A3</b>: getPromptForCommand 无 args → [SKILL_PROMPT]; 有 args → [prompt + ## Additional context 段]</li>
 *   <li><b>A4</b>: description 含 'Review auto-memory' + 'promote'/'cleanup'</li>
 *   <li><b>A5</b>: 真实场景 args='review last 30 days' → prompt + 'Additional context from user' 段</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CC 'process.env.USER_TYPE' 全局读 → Java boolean 参数; isEnabled 注入 Supplier (CC `isEnabled: () => isAutoMemoryEnabled()`).
 */
public class RememberSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RememberSkillRegistrar.class);

    public static final String SKILL_NAME = "remember";
    /** CC original: description (remember.ts:66-67) — 尾部无 Java 追加句. */
    public static final String DESCRIPTION =
        "Review auto-memory entries and propose promotions to CLAUDE.md, CLAUDE.local.md, or shared memory. " +
        "Also detects outdated, conflicting, and duplicate entries across memory layers.";
    public static final String WHEN_TO_USE =
        "Use when the user wants to review, organize, or promote their auto-memory entries. " +
        "Also useful for cleaning up outdated or conflicting entries across CLAUDE.md, " +
        "CLAUDE.local.md, and auto-memory.";

    /**
     * CC remember.ts SKILL_PROMPT 完整文案（:9-62）· 4 行 destination 表格 + Important distinctions
     * + 每步 Success criteria + Rules 4 条. 对齐 CC 后 description 不再追加 Java 句.
     */
    public static final String SKILL_PROMPT = """
        # Memory Review

        ## Goal
        Review the user's memory landscape and produce a clear report of proposed changes, grouped by action type. Do NOT apply changes — present proposals for user approval.

        ## Steps

        ### 1. Gather all memory layers
        Read CLAUDE.md and CLAUDE.local.md from the project root (if they exist). Your auto-memory content is already in your system prompt — review it there. Note which team memory sections exist, if any.

        **Success criteria**: You have the contents of all memory layers and can compare them.

        ### 2. Classify each auto-memory entry
        For each substantive entry in auto-memory, determine the best destination:

        | Destination | What belongs there | Examples |
        |---|---|---|
        | **CLAUDE.md** | Project conventions and instructions for Claude that all contributors should follow | "use bun not npm", "API routes use kebab-case", "test command is bun test", "prefer functional style" |
        | **CLAUDE.local.md** | Personal instructions for Claude specific to this user, not applicable to other contributors | "I prefer concise responses", "always explain trade-offs", "don't auto-commit", "run tests before committing" |
        | **Team memory** | Org-wide knowledge that applies across repositories (only if team memory is configured) | "deploy PRs go through #deploy-queue", "staging is at staging.internal", "platform team owns infra" |
        | **Stay in auto-memory** | Working notes, temporary context, or entries that don't clearly fit elsewhere | Session-specific observations, uncertain patterns |

        **Important distinctions:**
        - CLAUDE.md and CLAUDE.local.md contain instructions for Claude, not user preferences for external tools (editor theme, IDE keybindings, etc. don't belong in either)
        - Workflow practices (PR conventions, merge strategies, branch naming) are ambiguous — ask the user whether they're personal or team-wide
        - When unsure, ask rather than guess

        **Success criteria**: Each entry has a proposed destination or is flagged as ambiguous.

        ### 3. Identify cleanup opportunities
        Scan across all layers for:
        - **Duplicates**: Auto-memory entries already captured in CLAUDE.md or CLAUDE.local.md → propose removing from auto-memory
        - **Outdated**: CLAUDE.md or CLAUDE.local.md entries contradicted by newer auto-memory entries → propose updating the older layer
        - **Conflicts**: Contradictions between any two layers → propose resolution, noting which is more recent

        **Success criteria**: All cross-layer issues identified.

        ### 4. Present the report
        Output a structured report grouped by action type:
        1. **Promotions** — entries to move, with destination and rationale
        2. **Cleanup** — duplicates, outdated entries, conflicts to resolve
        3. **Ambiguous** — entries where you need the user's input on destination
        4. **No action needed** — brief note on entries that should stay put

        If auto-memory is empty, say so and offer to review CLAUDE.md for cleanup.

        **Success criteria**: User can review and approve/reject each proposal individually.

        ## Rules
        - Present ALL proposals before making any changes
        - Do NOT modify files without explicit user approval
        - Do NOT create new files unless the target doesn't exist yet
        - Ask about ambiguous entries — don't guess
        """;

    /**
     * CC registerRememberSkill — 统一产出 BundledSkillDefinition（P1-4）.
     *
     * @param registrar            统一注册入口 Consumer（Bootstrapper register(def)）
     * @param isAntUser            USER_TYPE='ant' 早返条件（CC remember.ts:5-7）
     * @param isAutoMemoryEnabled  isEnabled 开关（CC remember.ts:71 isEnabled: () => isAutoMemoryEnabled()；修 E10）
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar, boolean isAntUser,
                            BooleanSupplier isAutoMemoryEnabled) {
        if (!isAntUser) {
            log.debug("[RememberSkillRegistrar] USER_TYPE!=ant, skipping registration");
            return false;
        }
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            DESCRIPTION,
            null,   // aliases
            WHEN_TO_USE,   // CC remember.ts:68
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC remember.ts:70)
            () -> isAutoMemoryEnabled.getAsBoolean(),   // isEnabled (CC remember.ts:71; 修 E10/E11)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> {
                String prompt = SKILL_PROMPT;
                if (args != null && !args.isBlank()) {
                    prompt += "\n## Additional context from user\n\n" + args;
                }
                return List.of(PromptBlock.text(prompt));
            }
        );
        registrar.accept(def);
        log.info("[RememberSkillRegistrar] registered skill={}", SKILL_NAME);
        return true;
    }
}