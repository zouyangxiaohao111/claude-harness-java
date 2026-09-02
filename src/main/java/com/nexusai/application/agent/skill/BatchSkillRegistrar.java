package com.nexusai.application.agent.skill;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `/batch` skill 注册器 · 对齐 CC skills/bundled/batch.ts.
 *
 * <p>L1 语义: 注册 /batch skill — 大规模可并行变更的协调器.
 *            - args 空 → MISSING_INSTRUCTION_MESSAGE
 *            - 不是 git 仓库 → NOT_A_GIT_REPO_MESSAGE
 *            - 否则 → buildPrompt(instruction) 详细 plan mode + worktree worker prompt.
 *            MIN_AGENTS=5 / MAX_AGENTS=30 + WORKER_INSTRUCTIONS 5 步 (simplify/test/commit push/PR).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: registerBatchSkill() → void;MIN_AGENTS=5 / MAX_AGENTS=30;
 *       WORKER_INSTRUCTIONS 5 步 (simplify/unit tests/e2e/commit push/PR: line);
 *       buildPrompt(instruction) → String;3 phase (Plan/Spawn/Track) + 5 step task.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — getPromptForCommand(args) → args 空 → MISSING_INSTRUCTION;
 *       isGit=false → NOT_A_GIT_REPO;否则 → buildPrompt(args) → 3 phase 完整 plan + workers.</li>
 *   <li><b>A3</b>: 状态: NO_ARGS / NOT_GIT_REPO / READY (含 plan + workers);
 *       args.trim() → 空检测.</li>
 *   <li><b>A4</b>: args 含前后空白 → trim;
 *       args 空 (含空白) → MISSING_INSTRUCTION;
 *       not git → NOT_A_GIT_REPO (disableModelInvocation=true).</li>
 *   <li><b>A5</b>: 真实场景 — 用户 `/batch migrate from React to Vue` → 进入 plan mode;
 *       5-30 worker 每个 isolated worktree + gh pr create.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `getIsGit()` → 注入式 BooleanSupplier;
 *                    TS `registerBundledSkill({...})` → 返回 BundledSkillDefinition (上层 register);
 *                    TS `SKILL_TOOL_NAME` 等常量 → Java 常量.
 */
public final class BatchSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BatchSkillRegistrar.class);

    public static final int MIN_AGENTS = 5;
    public static final int MAX_AGENTS = 30;

    private final BooleanSupplier isGitSupplier;

    public BatchSkillRegistrar(BooleanSupplier isGitSupplier) {
        this.isGitSupplier = Objects.requireNonNull(isGitSupplier);
    }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** CC buildPrompt — 主链. */
    public String buildPrompt(String instruction) {
        return "# Batch: Parallel Work Orchestration\n\n"
            + "You are orchestrating a large, parallelizable change across this codebase.\n\n"
            + "## User Instruction\n\n"
            + instruction + "\n\n"
            + "## Phase 1: Research and Plan (Plan Mode)\n\n"
            + "Call the `EnterPlanMode` tool now to enter plan mode, then:\n\n"
            + "1. **Understand the scope.** Launch one or more subagents (in the foreground — you need their results) to deeply research what this instruction touches. "
            + "Find all the files, patterns, and call sites that need to change. Understand the existing conventions so the migration is consistent.\n\n"
            + "2. **Decompose into independent units.** Break the work into " + MIN_AGENTS + "–" + MAX_AGENTS + " self-contained units. Each unit must:\n"
            + "   - Be independently implementable in an isolated git worktree (no shared state with sibling units)\n"
            + "   - Be mergeable on its own without depending on another unit's PR landing first\n"
            + "   - Be roughly uniform in size (split large units, merge trivial ones)\n\n"
            + "   Scale the count to the actual work: few files → closer to " + MIN_AGENTS + "; hundreds of files → closer to " + MAX_AGENTS + ". "
            + "Prefer per-directory or per-module slicing over arbitrary file lists.\n\n"
            + "3. **Determine the e2e test recipe.** Figure out how a worker can verify its change actually works end-to-end — not just that unit tests pass. Look for:\n"
            + "   - A `nexusai-in-chrome` skill or browser-automation tool (for UI changes: click through the affected flow, screenshot the result)\n"
            + "   - A `tmux` or CLI-verifier skill (for CLI changes: launch the app interactively, exercise the changed behavior)\n"
            + "   - A dev-server + curl pattern (for API changes: start the server, hit the affected endpoints)\n"
            + "   - An existing e2e/integration test suite the worker can run\n\n"
            + "   If you cannot find a concrete e2e path, use the `AskUserQuestion` tool to ask the user how to verify this change end-to-end. "
            + "Offer 2–3 specific options based on what you found. Do not skip this — the workers cannot ask the user themselves.\n\n"
            + "   Write the recipe as a short, concrete set of steps that a worker can execute autonomously. Include any setup (start a dev server, build first) and the exact command/interaction to verify.\n\n"
            + "4. **Write the plan.** In your plan file, include:\n"
            + "   - A summary of what you found during research\n"
            + "   - A numbered list of work units — for each: a short title, the list of files/directories it covers, and a one-line description of the change\n"
            + "   - The e2e test recipe (or \"skip e2e because …\" if the user chose that)\n"
            + "   - The exact worker instructions you will give each agent (the shared template)\n\n"
            + "5. Call `ExitPlanMode` to present the plan for approval.\n\n"
            + "## Phase 2: Spawn Workers (After Plan Approval)\n\n"
            + "Once the plan is approved, spawn one background agent per work unit using the `Agent` tool. "
            + "**All agents must use `isolation: \"worktree\"` and `run_in_background: true`.** Launch them all in a single message block so they run in parallel.\n\n"
            + "For each agent, the prompt must be fully self-contained. Include:\n"
            + "- The overall goal (the user's instruction)\n"
            + "- This unit's specific task (title, file list, change description — copied verbatim from your plan)\n"
            + "- Any codebase conventions you discovered that the worker needs to follow\n"
            + "- The e2e test recipe from your plan (or \"skip e2e because …\")\n"
            + "- The worker instructions below, copied verbatim:\n\n"
            + "```\n"
            + WORKER_INSTRUCTIONS + "\n"
            + "```\n\n"
            + "Use `subagent_type: \"general-purpose\"` unless a more specific agent type fits.\n\n"
            + "## Phase 3: Track Progress\n\n"
            + "After launching all workers, render an initial status table:\n\n"
            + "| # | Unit | Status | PR |\n"
            + "|---|------|--------|----|\n"
            + "| 1 | <title> | running | — |\n"
            + "| 2 | <title> | running | — |\n\n"
            + "As background-agent completion notifications arrive, parse the `PR: <url>` line from each agent's result and re-render the table with updated status (`done` / `failed`) and PR links. Keep a brief failure note for any agent that did not produce a PR.\n\n"
            + "When all agents have reported, render the final table and a one-line summary (e.g., \"22/24 units landed as PRs\").\n";
    }

    /** CC WORKER_INSTRUCTIONS — 5 步 worker task. */
    public static final String WORKER_INSTRUCTIONS = """
        After you finish implementing the change:
        1. **Simplify** — Invoke the `Skill` tool with `skill: "simplify"` to review and clean up your changes.
        2. **Run unit tests** — Run the project's test suite (check for package.json scripts, Makefile targets, or common commands like `npm test`, `bun test`, `pytest`, `go test`). If tests fail, fix them.
        3. **Test end-to-end** — Follow the e2e test recipe from the coordinator's prompt (below). If the recipe says to skip e2e for this unit, skip it.
        4. **Commit and push** — Commit all changes with a clear message, push the branch, and create a PR with `gh pr create`. Use a descriptive title. If `gh` is not available or the push fails, note it in your final message.
        5. **Report** — End with a single line: `PR: <url>` so the coordinator can track it. If no PR was created, end with `PR: none — <reason>`.""";

    public static final String NOT_A_GIT_REPO_MESSAGE = """
        This is not a git repository. The `/batch` command requires a git repo because it spawns agents in isolated git worktrees and creates PRs from each. Initialize a repo first, or run this from inside an existing one.""";

    public static final String MISSING_INSTRUCTION_MESSAGE = """
        Provide an instruction describing the batch change you want to make.

        Examples:
          /batch migrate from react to vue
          /batch replace all uses of lodash with native equivalents
          /batch add type annotations to all untyped function parameters""";

    /** CC registerBatchSkill — 主入口 · 统一产出 BundledSkillDefinition（P1-4）. */
    public BundledSkillDefinition register() {
        return new BundledSkillDefinition(
            "batch",
            "Research and plan a large-scale change, then execute it in parallel across 5–30 isolated worktree agents that each open a PR.",
            null,   // aliases
            "Use when the user wants to make a sweeping, mechanical change across many files (migrations, refactors, bulk renames) that can be decomposed into independent parallel units.",
            "<instruction>",
            null,   // allowedTools
            null,   // model
            true,   // disableModelInvocation (CC batch.ts:109)
            true,   // userInvocable (CC batch.ts:108)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> {
                String instruction = args == null ? "" : args.trim();
                if (instruction.isEmpty()) {
                    return java.util.List.of(PromptBlock.text(MISSING_INSTRUCTION_MESSAGE));
                }
                if (!isGitSupplier.getAsBoolean()) {
                    return java.util.List.of(PromptBlock.text(NOT_A_GIT_REPO_MESSAGE));
                }
                return java.util.List.of(PromptBlock.text(buildPrompt(instruction)));
            }
        );
    }
}
