package com.nexusai.application.agent.command;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `/commit-push-pr` 斜杠命令 · 对齐 CC commands/commit-push-pr.ts.
 *
 * <p>L1 语义: 创建 commit + push + 打开 PR 的 prompt.
 *            - 注入: defaultBranch, enhancedPRAttribution, attributionTexts (commit + pr),
 *              executeShellCommandsInPrompt (在 prompt 中执行 !`shell` 命令),
 *              isAnt, isUndercover, undercoverInstructions, env SAFEUSER/USER.
 *            - prompt 含: ## Context (SAFEUSER/whoami/git status/diff/branch) +
 *              ## Git Safety Protocol + ## Your task (5 步:branch+commit+push+PR+slack).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 12 allowedTools 列表 (Bash git/gh + ToolSearch + mcp__slack__*);
 *       4 step (checkout branch + commit + push + pr create/edit) + optional slack step;
 *       getPromptContent(defaultBranch, prAttribution?) → String;
 *       handle(args) → List&lt;PromptBlock&gt;.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — getDefaultBranch + enhancedPRAttribution 并行 →
 *       getPromptContent 拼装 → executeShellCommandsInPrompt 替换 !`shell` →
 *       返回 [{type:'text', text: finalContent}].
 *       ant + undercover → 隐藏 reviewerArg/addReviewerArg/changelogSection/slackStep.</li>
 *   <li><b>A3</b>: 状态: args 空 → 标准 prompt;args 非空 → 追加 "## Additional instructions from user";
 *       ant+undercover → 简化 prompt (无 changelog/slack/reviewer).</li>
 *   <li><b>A4</b>: SAFEUSER 空 → 提示 fallback whoami;PR 已存在 (gh pr view 成功) → update (gh pr edit),
 *       否则 create (gh pr create);attribution 空 → 不附加 attribution.</li>
 *   <li><b>A5</b>: 真实场景 — 用户 `/commit-push-pr "add OAuth support"` →
 *       prompt 含 5 步 instructions + 用户附加指令 → shell execution 替换 git status/diff 等.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `(args, context) => ...` → Java `handle(args)` with injected deps;
 *                    TS `getAttributionTexts()` → 注入式 AttributionSupplier;
 *                    TS `getDefaultBranch()` → Supplier;
 *                    TS `executeShellCommandsInPrompt` → ShellExecutor;
 *                    TS `isUndercover()/getUndercoverInstructions()` → BooleanSupplier + Supplier;
 *                    TS `process.env` → Supplier (testable).
 */
public final class CommitPushPrCommand {

    private static final Logger log = LoggerFactory.getLogger(CommitPushPrCommand.class);

    public static final List<String> ALLOWED_TOOLS = List.of(
        "Bash(git checkout --branch:*)",
        "Bash(git checkout -b:*)",
        "Bash(git add:*)",
        "Bash(git status:*)",
        "Bash(git push:*)",
        "Bash(git commit:*)",
        "Bash(gh pr create:*)",
        "Bash(gh pr edit:*)",
        "Bash(gh pr view:*)",
        "Bash(gh pr merge:*)",
        "ToolSearch",
        "mcp__slack__send_message",
        "mcp__claude_ai_Slack__slack_send_message"
    );

    private final Supplier<String> defaultBranchSupplier;
    private final Supplier<String> enhancedPRAttributionSupplier;
    private final AttributionSupplier attributionSupplier;
    private final ShellExecutor shellExecutor;
    private final BooleanSupplier isAntSupplier;
    private final BooleanSupplier isUndercoverSupplier;
    private final Supplier<String> undercoverInstructionsSupplier;
    private final Supplier<String> safeUserSupplier;
    private final Supplier<String> userSupplier;

    public CommitPushPrCommand(Supplier<String> defaultBranchSupplier,
                                Supplier<String> enhancedPRAttributionSupplier,
                                AttributionSupplier attributionSupplier,
                                ShellExecutor shellExecutor,
                                BooleanSupplier isAntSupplier,
                                BooleanSupplier isUndercoverSupplier,
                                Supplier<String> undercoverInstructionsSupplier,
                                Supplier<String> safeUserSupplier,
                                Supplier<String> userSupplier) {
        this.defaultBranchSupplier = Objects.requireNonNull(defaultBranchSupplier);
        this.enhancedPRAttributionSupplier = Objects.requireNonNull(enhancedPRAttributionSupplier);
        this.attributionSupplier = Objects.requireNonNull(attributionSupplier);
        this.shellExecutor = Objects.requireNonNull(shellExecutor);
        this.isAntSupplier = Objects.requireNonNull(isAntSupplier);
        this.isUndercoverSupplier = Objects.requireNonNull(isUndercoverSupplier);
        this.undercoverInstructionsSupplier = Objects.requireNonNull(undercoverInstructionsSupplier);
        this.safeUserSupplier = Objects.requireNonNull(safeUserSupplier);
        this.userSupplier = Objects.requireNonNull(userSupplier);
    }

    /** Attribution texts. */
    public record Attributions(String commit, String pr) {}

    /** Attribution supplier (注入). */
    @FunctionalInterface
    public interface AttributionSupplier { Attributions get(); }

    /** Shell executor (注入). */
    @FunctionalInterface
    public interface ShellExecutor { String execute(String prompt); }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** Prompt block. */
    public record PromptBlock(String type, String text) {
        public static PromptBlock text(String text) { return new PromptBlock("text", text); }
    }

    /** CC getPromptContent — 主链. */
    public String getPromptContent(String defaultBranch, String prAttribution) {
        Attributions attr = attributionSupplier.get();
        String effectivePrAttribution = prAttribution != null ? prAttribution : attr.pr();
        String safeUser = safeUserSupplier.get() != null ? safeUserSupplier.get() : "";
        String username = userSupplier.get() != null ? userSupplier.get() : "";

        String prefix = "";
        String reviewerArg = " and `--reviewer anthropics/claude-code`";
        String addReviewerArg = " (and add `--add-reviewer anthropics/claude-code`)";
        String changelogSection = "\n\n## Changelog\n"
            + "<!-- CHANGELOG:START -->\n"
            + "[If this PR contains user-facing changes, add a changelog entry here. Otherwise, remove this section.]\n"
            + "<!-- CHANGELOG:END -->";
        String slackStep = "\n\n5. After creating/updating the PR, check if the user's CLAUDE.md mentions posting to Slack channels. "
            + "If it does, use ToolSearch to search for \"slack send message\" tools. If ToolSearch finds a Slack tool, "
            + "ask the user if they'd like you to post the PR URL to the relevant Slack channel. Only post if the user confirms. "
            + "If ToolSearch returns no results or errors, skip this step silently—do not mention the failure, "
            + "do not attempt workarounds, and do not try alternative approaches.";

        if (isAntSupplier.getAsBoolean() && isUndercoverSupplier.getAsBoolean()) {
            prefix = undercoverInstructionsSupplier.get() + "\n";
            reviewerArg = "";
            addReviewerArg = "";
            changelogSection = "";
            slackStep = "";
        }

        String commitAttributionSnippet = attr.commit != null
            ? ", ending with the attribution text shown in the example below" : "";
        String commitAttributionBody = attr.commit != null ? "\n\n" + attr.commit : "";

        return prefix + "## Context\n\n"
            + "- `SAFEUSER`: " + safeUser + "\n"
            + "- `whoami`: " + username + "\n"
            + "- `git status`: !`git status`\n"
            + "- `git diff HEAD`: !`git diff HEAD`\n"
            + "- `git branch --show-current`: !`git branch --show-current`\n"
            + "- `git diff " + defaultBranch + "...HEAD`: !`git diff " + defaultBranch + "...HEAD`\n"
            + "- `gh pr view --json number 2>/dev/null || true`: !`gh pr view --json number 2>/dev/null || true`\n\n"
            + "## Git Safety Protocol\n\n"
            + "- NEVER update the git config\n"
            + "- NEVER run destructive/irreversible git commands (like push --force, hard reset, etc) unless the user explicitly requests them\n"
            + "- NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it\n"
            + "- NEVER run force push to main/master, warn the user if they request it\n"
            + "- Do not commit files that likely contain secrets (.env, credentials.json, etc)\n"
            + "- Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported\n\n"
            + "## Your task\n\n"
            + "Analyze all changes that will be included in the pull request, making sure to look at all relevant commits "
            + "(NOT just the latest commit, but ALL commits that will be included in the pull request from the git diff "
            + defaultBranch + "...HEAD output above).\n\n"
            + "Based on the above changes:\n"
            + "1. Create a new branch if on " + defaultBranch + " (use SAFEUSER from context above for the branch name prefix, "
            + "falling back to whoami if SAFEUSER is empty, e.g., `username/feature-name`)\n"
            + "2. Create a single commit with an appropriate message using heredoc syntax" + commitAttributionSnippet + ":\n"
            + "```\n"
            + "git commit -m \"$(cat <<'EOF'\n"
            + "Commit message here." + commitAttributionBody + "\n"
            + "EOF\n"
            + ")\"\n"
            + "```\n"
            + "3. Push the branch to origin\n"
            + "4. If a PR already exists for this branch (check the gh pr view output above), update the PR title and body "
            + "using `gh pr edit` to reflect the current diff" + addReviewerArg + ". Otherwise, create a pull request using "
            + "`gh pr create` with heredoc syntax for the body" + reviewerArg + ".\n"
            + "   - IMPORTANT: Keep PR titles short (under 70 characters). Use the body for details.\n"
            + "```\n"
            + "gh pr create --title \"Short, descriptive title\" --body \"$(cat <<'EOF'\n"
            + "## Summary\n"
            + "<1-3 bullet points>\n\n"
            + "## Test plan\n"
            + "[Bulleted markdown checklist of TODOs for testing the pull request...]" + changelogSection
            + (effectivePrAttribution != null ? "\n\n" + effectivePrAttribution : "") + "\n"
            + "EOF\n"
            + ")\"\n"
            + "```\n\n"
            + "You have the capability to call multiple tools in a single response. You MUST do all of the above in a single message." + slackStep + "\n\n"
            + "Return the PR URL when you're done, so the user can see it.";
    }

    /** CC command handler — 主链. */
    public List<PromptBlock> handle(String args) {
        String defaultBranch = defaultBranchSupplier.get();
        String prAttribution = enhancedPRAttributionSupplier.get();
        String promptContent = getPromptContent(defaultBranch, prAttribution);

        String trimmedArgs = args == null ? null : args.trim();
        if (trimmedArgs != null && !trimmedArgs.isEmpty()) {
            promptContent += "\n\n## Additional instructions from user\n\n" + trimmedArgs;
        }

        String finalContent = shellExecutor.execute(promptContent);
        return List.of(PromptBlock.text(finalContent));
    }

    /** Command metadata. */
    public String name() { return "commit-push-pr"; }
    public String description() { return "Commit, push, and open a PR"; }
    public List<String> allowedTools() { return ALLOWED_TOOLS; }
    public String progressMessage() { return "creating commit and PR"; }
}
