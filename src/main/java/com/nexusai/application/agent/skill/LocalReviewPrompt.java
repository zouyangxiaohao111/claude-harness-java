package com.nexusai.application.agent.skill;

/**
 * LocalReviewPrompt · 对齐 CC commands/review.ts (LOCAL_REVIEW_PROMPT 部分).
 *
 * <p>L1 语义: /review slash command 的 system prompt 模板。引导模型:
 * <ol>
 *   <li>无 PR 号 → gh pr list 列出 open PR</li>
 *   <li>有 PR 号 → gh pr view 获取详情</li>
 *   <li>gh pr diff 拉差异</li>
 *   <li>分析变更输出 code review (代码质量/项目规范/性能/测试覆盖/安全)</li>
 * </ol>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: {@link #render(String)} (args) → String template</li>
 *   <li><b>A2 Golden Trace</b>: 空 args → "PR number: " (CC literal);非空 → "PR number: {args}"</li>
 *   <li><b>A3 纯函数</b>: 无副作用;每次调用返回相同字符串 (内容相对 args)</li>
 *   <li><b>A4 边界</b>: args null → "PR number: null"(literal);args 含特殊字符保留</li>
 *   <li><b>A5 业务场景</b>: 用户运行 /review 123 → LLM 收到 "PR number: 123" 模板的专家代码 review 提示</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS template literal → Java text block;
 * 多步 Markdown 步骤用 text block 嵌入;CC string concat → Java String。
 */
public final class LocalReviewPrompt {

    public static final String PROMPT = """
          You are an expert code reviewer. Follow these steps:

          1. If no PR number is provided in the args, run `gh pr list` to show open PRs
          2. If a PR number is provided, run `gh pr view <number>` to get PR details
          3. Run `gh pr diff <number>` to get the diff
          4. Analyze the changes and provide a thorough code review that includes:
             - Overview of what the PR does
             - Analysis of code quality and style
             - Specific suggestions for improvements
             - Any potential issues or risks

          Keep your review concise but thorough. Focus on:
          - Code correctness
          - Following project conventions
          - Performance implications
          - Test coverage
          - Security considerations

          Format your review with clear sections and bullet points.

          PR number: %s
        """;

    private LocalReviewPrompt() {
        // 模板常量
    }

    /**
     * Render the local review prompt with the given args (PR number or empty string).
     *
     * @param args user-provided slash command argument (PR number, empty, or null)
     * @return rendered prompt text with "PR number: {args}" substituted
     */
    public static String render(String args) {
        String safe = args == null ? "null" : args;
        return String.format(PROMPT, safe);
    }
}
