package com.nexusai.application.agent.command;

import java.util.List;
import java.util.function.Supplier;

/**
 * CommitCommand · 对齐 CC commands/commit.ts (ALLOWED_TOOLS + getPromptContent).
 *
 * <p>L1 语义: /commit slash command 的 prompt 模板 + 工具白名单 (3 个 git Bash)。
 * prompt 内容:展开 git status/diff/branch/log + Git Safety Protocol + commit 任务步骤。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 5 public static final 字段 ({@code NAME=commit} + {@code ALLOWED_TOOLS});{@link #getPromptContent(boolean, String)} (isUndercover, attribution) → String</li>
 *   <li><b>A2 Golden Trace</b>: ALLOWED_TOOLS = 3 git Bash;prompt 含 Context(4 git !`...`)/Safety Protocol/Stage+commit</li>
 *   <li><b>A3 不可变</b>: List 不可变 (List.of)</li>
 *   <li><b>A4 边界</b>: attribution null → 不附加尾段;空字符串 → 同 null</li>
 *   <li><b>A5 业务场景</b>: /commit 允许模型仅 git add/status/commit,exclude 编辑文件</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal with env.USER_TYPE/isUndercover/attribution dynamic segments →
 * Java text block + Supplier 注入 (调用方控制环境);TS Backticks executeShell → Java 占位注释。
 */
public final class CommitCommand {

    public static final String NAME = "commit";
    public static final List<String> ALLOWED_TOOLS = List.of(
        "Bash(git add:*)",
        "Bash(git status:*)",
        "Bash(git commit:*)");

    private final Supplier<Boolean> isUndercover;
    private final Supplier<String> userAttribution;

    public CommitCommand(Supplier<Boolean> isUndercover, Supplier<String> userAttribution) {
        this.isUndercover = isUndercover;
        this.userAttribution = userAttribution;
    }

    /**
     * Returns the commit slash command prompt content. Mirrors CC getPromptContent().
     *
     * @param isAnt mock flag for ant-environment injection
     * @param commitAttribution optional attribution suffix (null/empty → omitted)
     */
    public String getPromptContent(boolean isAnt, String commitAttribution) {
        String prefix = "";
        if (isAnt && Boolean.TRUE.equals(isUndercover.get())) {
            prefix = """
                Do not volunteer internal codenames, model family names, or specifics of
                testing apparatus in commit messages. The commit message must read as
                if a clean-room engineer wrote it without privileged context.
                """;
        }
        String attribution = (commitAttribution == null || commitAttribution.isEmpty())
            ? ""
            : "\n\n" + commitAttribution;
        return prefix + """
            ## Context

            - Current git status: !`git status`
            - Current git diff (staged and unstaged changes): !`git diff HEAD`
            - Current branch: !`git branch --show-current`
            - Recent commits: !`git log --oneline -10`

            ## Git Safety Protocol

            - NEVER update the git config
            - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly requests it
            - CRITICAL: ALWAYS create NEW commits. NEVER use git commit --amend, unless the user explicitly requests it
            - Do not commit files that likely contain secrets (.env, credentials.json, etc). Warn the user if they specifically request to commit those files
            - If there are no changes to commit (i.e., no untracked files and no modifications), do not create an empty commit
            - Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported

            ## Your task

            Based on the above changes, create a single git commit:

            1. Analyze all staged changes and draft a commit message:
               - Look at the recent commits above to follow this repository's commit message style
               - Summarize the nature of the changes (eg, new feature, enhancement, bug fix, refactoring, test, docs, etc)
               - Ensure the message accurately reflects the changes and their purpose (i.e., "add" means a wholly new feature, "update" means an enhancement to an existing feature, "fix" means a bug fix, etc.)
               - Draft a concise (1-2 sentences) commit message that focuses on the "why" rather than the "what"

            2. Stage relevant files and create the commit using HEREDOC syntax:
            ```
            git commit -m "$(cat <<'EOF'
            Commit message here.%s
            EOF
            )"
            ```

            You have the capability to call multiple tools in a single response. Stage and create the commit using a single message. Do not use any other tools or do anything else. Do not send any other text or messages besides these tool calls.
            """.formatted(attribution);
    }
}
